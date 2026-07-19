// Контент-скрипт: вставляет текст в поле ввода чата ВК и отправляет его.
// ВК периодически меняет вёрстку, поэтому селекторы перебираются по списку.

const INPUT_SELECTORS = [
  '#im_editable',                                  // старый интерфейс im
  '[data-testid="im_msg_input"] [contenteditable="true"]',
  '.im-chat-input--text[contenteditable="true"]',
  '.im-chat-input [contenteditable="true"]',
  '[aria-label="Напишите сообщение…"]',
  '[aria-label="Напишите сообщение..."]',
  '.ConvoComposer [contenteditable="true"]',
  '[contenteditable="true"][role="textbox"]'
];

const SEND_BUTTON_SELECTORS = [
  '#im_send',
  '[data-testid="im_send_btn"]',
  '.im-send-btn:not(.im-send-btn_locked)',
  '[aria-label="Отправить"]',
  'button[type="submit"][class*="Send"]'
];

function findFirst(selectors) {
  for (const sel of selectors) {
    const el = document.querySelector(sel);
    if (el && el.offsetParent !== null) return el;
  }
  return null;
}

function setComposerText(input, text) {
  input.focus();

  // execCommand до сих пор работает в Chromium и корректно
  // триггерит внутреннее состояние редактора ВК
  document.execCommand('selectAll', false, null);
  const inserted = document.execCommand('insertText', false, text);

  if (!inserted || !input.innerText.trim()) {
    // запасной путь: прямое изменение DOM + событие input
    input.innerText = text;
    input.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
  }
}

function pressEnter(input) {
  const opts = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
  input.dispatchEvent(new KeyboardEvent('keydown', opts));
  input.dispatchEvent(new KeyboardEvent('keypress', opts));
  input.dispatchEvent(new KeyboardEvent('keyup', opts));
}

async function sendMessage(text) {
  const input = findFirst(INPUT_SELECTORS);
  if (!input) {
    return { ok: false, error: 'Поле ввода сообщения не найдено (чат ещё не загрузился?)' };
  }

  setComposerText(input, text);

  // небольшая пауза, чтобы ВК успел обработать ввод и активировать кнопку
  await new Promise(r => setTimeout(r, 500));

  const btn = findFirst(SEND_BUTTON_SELECTORS);
  if (btn) {
    btn.click();
  } else {
    pressEnter(input);
  }

  // проверяем, что поле очистилось — признак успешной отправки
  await new Promise(r => setTimeout(r, 1000));
  if (input.innerText.trim() === '') {
    return { ok: true };
  }

  // последняя попытка — Enter по полю
  pressEnter(input);
  await new Promise(r => setTimeout(r, 1000));
  return input.innerText.trim() === ''
    ? { ok: true }
    : { ok: false, error: 'Текст вставлен, но сообщение не отправилось — отправьте вручную' };
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type === 'SEND_VK_MESSAGE') {
    sendMessage(msg.text).then(sendResponse);
    return true; // асинхронный ответ
  }
});
