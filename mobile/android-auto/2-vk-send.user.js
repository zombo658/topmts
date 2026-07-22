// ==UserScript==
// @name         МТС→ВК авто (Android) — шаг 2: отправка в чат ВК
// @namespace    topmts
// @version      1.0
// @description  Получает текст отчёта из адреса, вставляет в поле чата и отправляет. От вашего имени, без бота.
// @match        https://vk.ru/im*
// @match        https://vk.com/im*
// @match        https://m.vk.com/*
// @grant        none
// @run-at       document-idle
// ==/UserScript==

(() => {
  'use strict';

  // достаём текст отчёта из #vkreport=...
  const m = location.hash.match(/vkreport=([^&]+)/);
  if (!m) return; // обычный заход в ВК — не мешаем
  let report;
  try {
    report = decodeURIComponent(escape(atob(decodeURIComponent(m[1]))));
  } catch (e) { return; }

  // чтобы не отправить дважды при перезагрузке
  const guard = 'vksent:' + report.slice(0, 40);
  if (sessionStorage.getItem(guard)) return;

  const sleep = ms => new Promise(r => setTimeout(r, ms));

  const INPUT_SELECTORS = [
    'textarea[name="message"]',                 // старый m.vk.com
    '#im_editable',
    '[data-testid="im_msg_input"] [contenteditable="true"]',
    '.im-chat-input--text[contenteditable="true"]',
    '[contenteditable="true"][role="textbox"]',
    '[contenteditable="true"]',
    'textarea'
  ];
  const SEND_SELECTORS = [
    '[data-testid="im_send_btn"]',
    '#im_send',
    '.im-send-btn:not(.im-send-btn_locked)',
    '[aria-label="Отправить"]',
    'button[type="submit"]'
  ];

  const visible = el => el && el.getClientRects().length &&
    getComputedStyle(el).visibility !== 'hidden';

  function find(sels) {
    for (const s of sels) {
      const els = [...document.querySelectorAll(s)].filter(visible);
      if (els.length) return els[els.length - 1]; // нижнее — поле ввода чата
    }
    return null;
  }

  function fill(input, text) {
    input.focus();
    if (input.value !== undefined) {
      // textarea — нативный сеттер + событие input (для React/Vue)
      const proto = Object.getPrototypeOf(input);
      const setter = Object.getOwnPropertyDescriptor(proto, 'value');
      setter && setter.set ? setter.set.call(input, text) : (input.value = text);
    } else {
      const range = document.createRange();
      range.selectNodeContents(input);
      range.collapse(false);
      const sel = getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
      if (!document.execCommand('insertText', false, text)) input.innerText = text;
    }
    input.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
  }

  function getText(el) {
    return (el.value !== undefined ? el.value : el.innerText) || '';
  }

  function pressEnter(input) {
    const o = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
    input.dispatchEvent(new KeyboardEvent('keydown', o));
    input.dispatchEvent(new KeyboardEvent('keypress', o));
    input.dispatchEvent(new KeyboardEvent('keyup', o));
  }

  function toast(text, color) {
    const t = document.createElement('div');
    t.textContent = text;
    t.style.cssText = 'position:fixed;left:50%;bottom:90px;transform:translateX(-50%);z-index:99999;' +
      'background:' + (color || '#222') + ';color:#fff;padding:12px 18px;border-radius:12px;' +
      'font:14px sans-serif;max-width:92%;text-align:center;box-shadow:0 4px 16px rgba(0,0,0,.4)';
    document.body.appendChild(t);
    setTimeout(() => t.remove(), 6000);
  }

  async function run() {
    // ждём появления поля ввода (чат грузится не сразу)
    let input = null;
    for (let i = 0; i < 30 && !input; i++) { input = find(INPUT_SELECTORS); if (!input) await sleep(1000); }
    if (!input) { toast('Не нашёл поле ввода ВК — отправьте вручную', '#c0392b'); return; }

    fill(input, report);
    await sleep(700);

    const btn = find(SEND_SELECTORS);
    if (btn) btn.click(); else pressEnter(input);
    await sleep(1500);

    if (!getText(input).trim()) {
      sessionStorage.setItem(guard, '1');
      history.replaceState(null, '', location.pathname + location.search); // убираем текст из адреса
      toast('Отчёт отправлен ✓', '#27ae60');
    } else {
      // синтетическая отправка не прошла — оставляем текст в поле,
      // остаётся один тап по кнопке «отправить»
      toast('Отчёт вставлен — нажмите «отправить» ▶', '#e67e22');
    }
  }

  run();
})();
