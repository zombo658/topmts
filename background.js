// Фоновый service worker: планирует отправку отчёта, открывает чат ВК
// и печатает сообщение через протокол DevTools (chrome.debugger) —
// такие события клавиатуры для страницы неотличимы от настоящих,
// поэтому работают с любым редактором ВК.

const ALARM_NAME = 'vk-report';

const DEFAULTS = {
  enabled: false,
  peerId: '',            // id диалога: число, "c123" для беседы или короткое имя
  time: '18:00',         // время отправки ЧЧ:ММ
  days: [1, 2, 3, 4, 5], // дни недели (0 = воскресенье)
  template: 'Отчёт за {date}:\n- ',
  lastSentDate: ''       // защита от повторной отправки в тот же день
};

function getSettings() {
  return chrome.storage.sync.get(DEFAULTS);
}

// ---------- расписание ----------

async function scheduleAlarm() {
  await chrome.alarms.clear(ALARM_NAME);
  const s = await getSettings();
  if (!s.enabled || !s.peerId) return;

  const [hh, mm] = s.time.split(':').map(Number);
  const now = new Date();
  const next = new Date(now);
  next.setHours(hh, mm, 0, 0);

  for (let i = 0; i < 8; i++) {
    if (next > now && s.days.includes(next.getDay())) break;
    next.setDate(next.getDate() + 1);
    next.setHours(hh, mm, 0, 0);
  }

  chrome.alarms.create(ALARM_NAME, { when: next.getTime() });
}

function formatReport(template) {
  const now = new Date();
  const date = now.toLocaleDateString('ru-RU');
  const time = now.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  return template.replaceAll('{date}', date).replaceAll('{time}', time);
}

function chatUrl(peerId) {
  const id = String(peerId).trim();
  return `https://vk.com/im?sel=${encodeURIComponent(id)}`;
}

// ---------- утилиты ----------

const sleep = ms => new Promise(r => setTimeout(r, ms));

function notify(title, message) {
  chrome.notifications.create({
    type: 'basic',
    iconUrl: 'icons/icon128.png',
    title,
    message
  });
}

// Запрос контент-скрипту в конкретном фрейме
function askFrame(tabId, frameId, msg) {
  return chrome.tabs.sendMessage(tabId, msg, { frameId });
}

// Внедряет content.js во все фреймы вкладки (в самом скрипте стоит
// защита от повторного внедрения) и возвращает список frameId
async function ensureInjected(tabId) {
  const results = await chrome.scripting.executeScript({
    target: { tabId, allFrames: true },
    files: ['content.js']
  });
  return results.map(r => r.frameId);
}

// Ищет фрейм, в котором есть поле ввода чата (мессенджер ВК может
// находиться в iframe web.vk.me внутри vk.com)
async function findComposerFrame(tabId) {
  const frameIds = await ensureInjected(tabId);
  for (const frameId of frameIds) {
    try {
      const resp = await askFrame(tabId, frameId, { type: 'PREPARE_COMPOSER' });
      if (resp && resp.ok) return { frameId, prep: resp };
    } catch (e) { /* фрейм недоступен — пропускаем */ }
  }
  return null;
}

// Диагностика по всем фреймам — попадает в текст ошибки
async function collectDiag(tabId) {
  try {
    const frameIds = await ensureInjected(tabId);
    const parts = [];
    for (const frameId of frameIds) {
      try {
        const d = await askFrame(tabId, frameId, { type: 'DIAG' });
        if (d) parts.push(`${d.url} (полей: ${d.editables})`);
      } catch (e) { /* пропускаем */ }
    }
    return parts.join('; ') || 'фреймы не ответили';
  } catch (e) {
    return 'диагностика не удалась: ' + e.message;
  }
}

// ---------- ввод через DevTools-протокол ----------

function cdpAttach(tabId) {
  return new Promise((resolve, reject) => {
    chrome.debugger.attach({ tabId }, '1.3', () => {
      if (chrome.runtime.lastError) reject(new Error(chrome.runtime.lastError.message));
      else resolve();
    });
  });
}

function cdpDetach(tabId) {
  return new Promise(resolve => {
    chrome.debugger.detach({ tabId }, () => {
      void chrome.runtime.lastError; // уже отсоединён — не страшно
      resolve();
    });
  });
}

function cdp(tabId, method, params) {
  return new Promise((resolve, reject) => {
    chrome.debugger.sendCommand({ tabId }, method, params, (result) => {
      if (chrome.runtime.lastError) reject(new Error(chrome.runtime.lastError.message));
      else resolve(result);
    });
  });
}

async function cdpPressEnter(tabId) {
  const base = { key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 };
  await cdp(tabId, 'Input.dispatchKeyEvent', { ...base, type: 'keyDown', text: '\r', unmodifiedText: '\r' });
  await cdp(tabId, 'Input.dispatchKeyEvent', { ...base, type: 'keyUp' });
}

// ---------- отправка отчёта ----------

async function sendReport() {
  const s = await getSettings();
  if (!s.peerId) throw new Error('Не указан чат (peerId)');

  const message = formatReport(s.template);
  const url = chatUrl(s.peerId);

  // Ищем уже открытую вкладку с этим чатом, иначе открываем новую
  const tabs = await chrome.tabs.query({ url: ['https://vk.com/im*', 'https://*.vk.me/*'] });
  let tab = tabs.find(t => t.url && t.url.includes(`sel=${s.peerId}`));
  if (!tab) {
    tab = await chrome.tabs.create({ url, active: true });
  } else {
    await chrome.tabs.update(tab.id, { active: true });
  }
  // Фокус на окно — нужен, чтобы ввод с клавиатуры попадал в поле чата
  await chrome.windows.update(tab.windowId, { focused: true });

  // Ждём, пока чат загрузится, и ищем фрейм с полем ввода
  // (мессенджер может быть как в основной странице, так и в iframe)
  const deadline = Date.now() + 45000;
  let found = null;
  while (Date.now() < deadline) {
    try {
      found = await findComposerFrame(tab.id);
      if (found) break;
    } catch (e) { /* страница ещё грузится */ }
    await sleep(1500);
  }
  if (!found) {
    const diag = await collectDiag(tab.id);
    throw new Error('Поле ввода чата не появилось за 45 сек. ' +
      'Проверьте, что выполнен вход в ВК и чат указан верно. Фреймы: ' + diag);
  }
  const { frameId, prep } = found;

  // Печатаем через DevTools-протокол — «настоящий» ввод
  await cdpAttach(tab.id);
  try {
    await askFrame(tab.id, frameId, { type: 'PREPARE_COMPOSER' }); // повторный фокус
    await cdp(tab.id, 'Input.insertText', { text: message });
    await sleep(600);

    let state = await askFrame(tab.id, frameId, { type: 'COMPOSER_STATE' });
    if (!state.text || !state.text.trim()) {
      throw new Error('Текст не появился в поле ввода (найдено: ' + prep.desc + ')');
    }

    await cdpPressEnter(tab.id);
    await sleep(1200);

    state = await askFrame(tab.id, frameId, { type: 'COMPOSER_STATE' });
    if (state.text && state.text.trim()) {
      // Enter не сработал — пробуем кнопку «Отправить»
      const click = await askFrame(tab.id, frameId, { type: 'CLICK_SEND' });
      await sleep(1200);
      state = await askFrame(tab.id, frameId, { type: 'COMPOSER_STATE' });
      if (state.text && state.text.trim()) {
        throw new Error('Текст набран, но не отправился. ' +
          (click.ok ? 'Кнопка: ' + click.desc : 'Кнопка «Отправить» не найдена'));
      }
    }
  } finally {
    await cdpDetach(tab.id);
  }
}

// ---------- события ----------

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name !== ALARM_NAME) return;
  const s = await getSettings();
  const today = new Date().toDateString();
  try {
    if (s.enabled && s.lastSentDate !== today) {
      await sendReport();
      await chrome.storage.sync.set({ lastSentDate: today });
      notify('VK Авто-отчёт', 'Отчёт отправлен ✓');
    }
  } catch (e) {
    console.error('VK Авто-отчёт: ошибка отправки', e);
    notify('VK Авто-отчёт — ошибка', e.message);
  } finally {
    scheduleAlarm();
  }
});

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type === 'RESCHEDULE') {
    scheduleAlarm().then(() => sendResponse({ ok: true }));
    return true;
  }
  if (msg.type === 'SEND_NOW') {
    sendReport()
      .then(() => {
        notify('VK Авто-отчёт', 'Отчёт отправлен ✓');
        sendResponse({ ok: true });
      })
      .catch(e => {
        notify('VK Авто-отчёт — ошибка', e.message);
        sendResponse({ ok: false, error: e.message });
      });
    return true;
  }
});

chrome.runtime.onInstalled.addListener(scheduleAlarm);
chrome.runtime.onStartup.addListener(scheduleAlarm);
