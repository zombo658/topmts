// Контент-скрипт: вставляет текст в поле ввода чата ВК и отправляет его.
// Вёрстка ВК часто меняется, поэтому сначала пробуем известные селекторы,
// а затем ищем поле ввода и кнопку отправки эвристически.

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

function isVisible(el) {
  if (!el) return false;
  const rects = el.getClientRects();
  if (!rects.length) return false;
  const style = getComputedStyle(el);
  return style.visibility !== 'hidden' && style.display !== 'none';
}

function findComposer() {
  for (const sel of INPUT_SELECTORS) {
    const el = document.querySelector(sel);
    if (isVisible(el)) return el;
  }
  // Эвристика: самое нижнее видимое contenteditable-поле на странице —
  // в мессенджере это всегда строка ввода сообщения
  const candidates = [...document.querySelectorAll('[contenteditable="true"], textarea')]
    .filter(isVisible);
  if (!candidates.length) return null;
  candidates.sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top);
  return candidates[candidates.length - 1];
}

function findSendButton(composer) {
  for (const sel of SEND_BUTTON_SELECTORS) {
    const el = document.querySelector(sel);
    if (isVisible(el)) return el;
  }
  // Эвристика: видимая кнопка с подписью/aria-label «Отправить»,
  // ближайшая к полю ввода
  const composerRect = composer.getBoundingClientRect();
  const buttons = [...document.querySelectorAll('button, [role="button"]')]
    .filter(isVisible)
    .filter(b => {
      const label = (b.getAttribute('aria-label') || '') + ' ' +
                    (b.title || '') + ' ' + b.textContent;
      return /отправить|send/i.test(label);
    });
  if (!buttons.length) return null;
  buttons.sort((a, b) => {
    const da = Math.abs(a.getBoundingClientRect().top - composerRect.top);
    const db = Math.abs(b.getBoundingClientRect().top - composerRect.top);
    return da - db;
  });
  return buttons[0];
}

function getText(input) {
  return (input.value !== undefined ? input.value : input.innerText) || '';
}

function placeCaret(input) {
  input.focus();
  if (input.value !== undefined) return; // textarea
  const range = document.createRange();
  range.selectNodeContents(input);
  range.collapse(false);
  const sel = window.getSelection();
  sel.removeAllRanges();
  sel.addRange(range);
}

// Три способа вставки текста — от самого «естественного» к грубому.
// Современные редакторы (Slate/ProseMirror, которые использует ВК)
// надёжнее всего реагируют на событие paste.
function insertText(input, text) {
  placeCaret(input);

  // 1) синтетический paste
  try {
    const dt = new DataTransfer();
    dt.setData('text/plain', text);
    input.dispatchEvent(new ClipboardEvent('paste', {
      clipboardData: dt, bubbles: true, cancelable: true
    }));
    if (getText(input).includes(text.slice(0, 20))) return 'paste';
  } catch (e) { /* пробуем дальше */ }

  // 2) execCommand insertText
  placeCaret(input);
  document.execCommand('insertText', false, text);
  if (getText(input).includes(text.slice(0, 20))) return 'execCommand';

  // 3) прямое изменение + событие input
  if (input.value !== undefined) {
    input.value = text;
  } else {
    input.innerText = text;
  }
  input.dispatchEvent(new InputEvent('input', {
    bubbles: true, data: text, inputType: 'insertText'
  }));
  return getText(input).includes(text.slice(0, 20)) ? 'direct' : null;
}

function pressEnter(input) {
  const opts = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
  input.focus();
  input.dispatchEvent(new KeyboardEvent('keydown', opts));
  input.dispatchEvent(new KeyboardEvent('keypress', opts));
  input.dispatchEvent(new KeyboardEvent('keyup', opts));
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function sendMessage(text) {
  const input = findComposer();
  if (!input) {
    return { ok: false, error: 'Поле ввода не найдено — чат ещё не загрузился или интерфейс изменился' };
  }

  const method = insertText(input, text);
  if (!method) {
    return { ok: false, error: 'Не удалось вставить текст в поле ввода (' + describe(input) + ')' };
  }
  console.log('[VK Авто-отчёт] текст вставлен методом:', method, 'в', describe(input));

  await sleep(600); // даём ВК обработать ввод и активировать кнопку

  const btn = findSendButton(input);
  if (btn) {
    console.log('[VK Авто-отчёт] жму кнопку отправки:', describe(btn));
    btn.click();
  } else {
    console.log('[VK Авто-отчёт] кнопка не найдена, отправляю Enter');
    pressEnter(input);
  }

  // поле очистилось — сообщение ушло
  await sleep(1200);
  if (!getText(input).trim()) return { ok: true, method, sent: btn ? 'button' : 'enter' };

  // вторая попытка другим способом
  if (btn) pressEnter(input); else { const b2 = findSendButton(input); if (b2) b2.click(); }
  await sleep(1200);
  if (!getText(input).trim()) return { ok: true, method, sent: 'retry' };

  return {
    ok: false,
    error: 'Текст вставлен (' + method + '), но отправка не сработала: поле не очистилось. ' +
           (btn ? 'Кнопка: ' + describe(btn) : 'Кнопка отправки не найдена')
  };
}

function describe(el) {
  const id = el.id ? '#' + el.id : '';
  const cls = el.className && typeof el.className === 'string'
    ? '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
  return el.tagName.toLowerCase() + id + cls;
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type === 'SEND_VK_MESSAGE') {
    sendMessage(msg.text)
      .then(sendResponse)
      .catch(e => sendResponse({ ok: false, error: e.message }));
    return true; // асинхронный ответ
  }
  if (msg.type === 'PING') {
    sendResponse({ ok: true });
  }
});
