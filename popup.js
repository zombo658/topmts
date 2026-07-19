// Логика попапа: загрузка/сохранение настроек и отправка «сейчас».

const DEFAULTS = {
  enabled: false,
  peerId: '',
  time: '18:00',
  days: [1, 2, 3, 4, 5],
  template: 'Отчёт за {date}:\n- '
};

const $ = id => document.getElementById(id);

function setStatus(text, isError) {
  const el = $('status');
  el.textContent = text;
  el.className = isError ? 'err' : 'ok';
}

async function load() {
  const s = await chrome.storage.sync.get(DEFAULTS);
  $('enabled').checked = s.enabled;
  $('peerId').value = s.peerId;
  $('time').value = s.time;
  $('template').value = s.template;
  document.querySelectorAll('#days input').forEach(cb => {
    cb.checked = s.days.includes(Number(cb.value));
  });
}

async function save() {
  const days = [...document.querySelectorAll('#days input:checked')].map(cb => Number(cb.value));
  const s = {
    enabled: $('enabled').checked,
    peerId: $('peerId').value.trim(),
    time: $('time').value || '18:00',
    days,
    template: $('template').value || DEFAULTS.template
  };

  if (s.enabled && !s.peerId) {
    setStatus('Укажите чат, чтобы включить автоотправку', true);
    return;
  }
  if (s.enabled && days.length === 0) {
    setStatus('Выберите хотя бы один день недели', true);
    return;
  }

  await chrome.storage.sync.set(s);
  await chrome.runtime.sendMessage({ type: 'RESCHEDULE' });
  setStatus('Сохранено');
}

async function sendNow() {
  const peerId = $('peerId').value.trim();
  if (!peerId) {
    setStatus('Укажите чат', true);
    return;
  }
  await save();
  setStatus('Отправляю…');
  const resp = await chrome.runtime.sendMessage({ type: 'SEND_NOW' });
  if (resp && resp.ok) {
    setStatus('Отчёт отправлен');
  } else {
    setStatus('Ошибка: ' + (resp && resp.error ? resp.error : 'неизвестная'), true);
  }
}

$('save').addEventListener('click', save);
$('sendNow').addEventListener('click', sendNow);
load();
