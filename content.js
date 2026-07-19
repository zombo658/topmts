// Контент-скрипт: находит поле ввода чата ВК и кнопку «Отправить».
// Внедряется во ВСЕ фреймы страницы (мессенджер ВК может жить в iframe
// web.vk.me). Сам ввод текста делает background.js через DevTools-протокол,
// здесь только поиск элементов, фокус и проверка состояния.

(() => {
  // защита от повторного внедрения (декларативно + программно)
  if (window.__vkAutoReportLoaded) return;
  window.__vkAutoReportLoaded = true;

  const INPUT_SELECTORS = [
    '#im_editable',                                  // старый интерфейс im
    '[data-testid="im_msg_input"] [contenteditable="true"]',
    '[data-testid="im_msg_input"]',
    '.im-chat-input--text[contenteditable="true"]',
    '.im-chat-input [contenteditable="true"]',
    '.ConvoComposer [contenteditable="true"]',
    '[contenteditable="true"][role="textbox"]'
  ];

  const SEND_BUTTON_SELECTORS = [
    '#im_send',
    '[data-testid="im_send_btn"]',
    '.im-send-btn:not(.im-send-btn_locked)',
    '[aria-label="Отправить"]',
    '[aria-label="Отправить сообщение"]'
  ];

  let composer = null; // найденное поле ввода — используется между запросами

  function isVisible(el) {
    if (!el) return false;
    const rects = el.getClientRects();
    if (!rects.length) return false;
    const style = getComputedStyle(el);
    return style.visibility !== 'hidden' && style.display !== 'none';
  }

  function editableCandidates() {
    return [...document.querySelectorAll('[contenteditable="true"], textarea')]
      .filter(isVisible);
  }

  function findComposer() {
    for (const sel of INPUT_SELECTORS) {
      const el = document.querySelector(sel);
      if (isVisible(el)) return el;
    }
    // Эвристика: самое нижнее видимое редактируемое поле на странице —
    // в мессенджере это всегда строка ввода сообщения
    const candidates = editableCandidates();
    if (!candidates.length) return null;
    candidates.sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top);
    return candidates[candidates.length - 1];
  }

  function findSendButton(near) {
    for (const sel of SEND_BUTTON_SELECTORS) {
      const el = document.querySelector(sel);
      if (isVisible(el)) return el;
    }
    const nearRect = near ? near.getBoundingClientRect() : { top: window.innerHeight };
    const buttons = [...document.querySelectorAll('button, [role="button"]')]
      .filter(isVisible)
      .filter(b => {
        const label = (b.getAttribute('aria-label') || '') + ' ' +
                      (b.title || '') + ' ' + b.textContent;
        return /отправить|send/i.test(label);
      });
    if (!buttons.length) return null;
    buttons.sort((a, b) =>
      Math.abs(a.getBoundingClientRect().top - nearRect.top) -
      Math.abs(b.getBoundingClientRect().top - nearRect.top));
    return buttons[0];
  }

  function describe(el) {
    const id = el.id ? '#' + el.id : '';
    const cls = el.className && typeof el.className === 'string'
      ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
    return el.tagName.toLowerCase() + id + cls;
  }

  function getText(el) {
    return (el.value !== undefined ? el.value : el.innerText) || '';
  }

  function prepareComposer() {
    composer = findComposer();
    if (!composer) {
      return { ok: false, error: 'поле ввода не найдено' };
    }
    composer.scrollIntoView({ block: 'center' });
    composer.click();
    composer.focus();
    // ставим курсор в конец для contenteditable
    if (composer.value === undefined) {
      const range = document.createRange();
      range.selectNodeContents(composer);
      range.collapse(false);
      const sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    }
    const focused = document.activeElement === composer ||
                    composer.contains(document.activeElement);
    console.log('[VK Авто-отчёт] поле ввода:', describe(composer), 'фокус:', focused);
    return { ok: true, desc: describe(composer), focused };
  }

  chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    switch (msg.type) {
      case 'PREPARE_COMPOSER':
        sendResponse(prepareComposer());
        break;
      case 'COMPOSER_STATE': {
        const el = composer && document.contains(composer) ? composer : findComposer();
        sendResponse(el ? { ok: true, text: getText(el) } : { ok: false, text: '' });
        break;
      }
      case 'CLICK_SEND': {
        const btn = findSendButton(composer);
        if (btn) {
          console.log('[VK Авто-отчёт] жму кнопку:', describe(btn));
          btn.click();
          sendResponse({ ok: true, desc: describe(btn) });
        } else {
          sendResponse({ ok: false });
        }
        break;
      }
      case 'DIAG':
        // диагностика фрейма: адрес и число редактируемых полей
        sendResponse({
          url: location.host + location.pathname,
          editables: editableCandidates().length
        });
        break;
      case 'PING':
        sendResponse({ ok: true });
        break;
    }
  });
})();
