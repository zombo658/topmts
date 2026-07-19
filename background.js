// Фоновый service worker: планирует отправку отчёта и открывает чат ВК.

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

// Ставим будильник на ближайший подходящий день/время
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
  // "c123" — беседа, число — пользователь/группа, иначе короткое имя
  if (/^c\d+$/.test(id) || /^-?\d+$/.test(id)) {
    return `https://vk.com/im?sel=${id}`;
  }
  return `https://vk.com/im?sel=${encodeURIComponent(id)}`;
}

// Открывает чат и просит контент-скрипт отправить текст
async function sendReport() {
  const s = await getSettings();
  if (!s.peerId) throw new Error('Не указан чат (peerId)');

  const message = formatReport(s.template);
  const url = chatUrl(s.peerId);

  // Ищем уже открытую вкладку с этим чатом, иначе открываем новую
  const tabs = await chrome.tabs.query({ url: 'https://vk.com/im*' });
  let tab = tabs.find(t => t.url && t.url.includes(`sel=${s.peerId}`));
  if (!tab) {
    tab = await chrome.tabs.create({ url, active: false });
  }

  // Ждём загрузку страницы и появления поля ввода, затем шлём сообщение
  const text = await waitAndSend(tab.id, message);
  return text;
}

function waitAndSend(tabId, message) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + 60000;

    const attempt = async () => {
      try {
        const resp = await chrome.tabs.sendMessage(tabId, { type: 'SEND_VK_MESSAGE', text: message });
        if (resp && resp.ok) return resolve(resp);
        throw new Error(resp && resp.error ? resp.error : 'Не удалось отправить');
      } catch (e) {
        if (Date.now() > deadline) {
          return reject(new Error('Тайм-аут: поле ввода чата не найдено. ' + e.message));
        }
        setTimeout(attempt, 2000);
      }
    };

    setTimeout(attempt, 3000);
  });
}

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name !== ALARM_NAME) return;
  const s = await getSettings();
  const today = new Date().toDateString();
  try {
    if (s.enabled && s.lastSentDate !== today) {
      await sendReport();
      await chrome.storage.sync.set({ lastSentDate: today });
    }
  } catch (e) {
    console.error('VK Авто-отчёт: ошибка отправки', e);
  } finally {
    scheduleAlarm();
  }
});

// Сообщения из попапа
chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type === 'RESCHEDULE') {
    scheduleAlarm().then(() => sendResponse({ ok: true }));
    return true;
  }
  if (msg.type === 'SEND_NOW') {
    sendReport()
      .then(() => sendResponse({ ok: true }))
      .catch(e => sendResponse({ ok: false, error: e.message }));
    return true;
  }
});

chrome.runtime.onInstalled.addListener(scheduleAlarm);
chrome.runtime.onStartup.addListener(scheduleAlarm);
