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
  el.classList.remove('show', 'ok', 'err');
  // перезапуск анимации появления
  void el.offsetWidth;
  el.textContent = text;
  el.classList.add('show', isError ? 'err' : 'ok');
}

// анимация «нажатия клавиши» на кнопке
function pressAnim(btn) {
  btn.classList.remove('pressed');
  void btn.offsetWidth;
  btn.classList.add('pressed');
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
    return false;
  }
  if (s.enabled && days.length === 0) {
    setStatus('Выберите хотя бы один день недели', true);
    return false;
  }

  await chrome.storage.sync.set(s);
  await chrome.runtime.sendMessage({ type: 'RESCHEDULE' });
  return true;
}

async function onSave() {
  pressAnim($('save'));
  if (await save()) setStatus('Сохранено ✓');
}

async function onSendNow() {
  const btn = $('sendNow');
  pressAnim(btn);

  const peerId = $('peerId').value.trim();
  if (!peerId) {
    setStatus('Укажите чат', true);
    return;
  }
  if (!(await save())) return;

  btn.classList.add('loading');
  setStatus('Отправляю…');
  try {
    const resp = await chrome.runtime.sendMessage({ type: 'SEND_NOW' });
    if (resp && resp.ok) {
      setStatus('Отчёт отправлен ✓');
    } else {
      setStatus('Ошибка: ' + (resp && resp.error ? resp.error : 'неизвестная'), true);
    }
  } finally {
    btn.classList.remove('loading');
  }
}

$('save').addEventListener('click', onSave);
$('sendNow').addEventListener('click', onSendNow);
load();
