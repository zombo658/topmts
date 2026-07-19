// Логика попапа: загрузка/сохранение настроек, предпросмотр данных
// с портала МТС и отправка «сейчас».

const DEFAULT_TEMPLATE = [
  '{дата} {тип дня}',
  'Общее количество ДМХ: {общее количество дмх}',
  'Поквартирный обход ДМХ: {поквартирный обход дмх}',
  'Общее время поквартирного обхода: {общее время поквартирного обхода}',
  'Визуализация, дмх: {визуализация дмх}',
  'Общее время визуализации: {общее время визуализации}',
  'Общее время: {общее время}',
  'Количество звонков: {количество звонков}'
].join('\n');

const DEFAULTS = {
  enabled: false,
  peerId: '',
  time: '18:00',
  days: [1, 2, 3, 4, 5],
  template: DEFAULT_TEMPLATE,
  calls: '0'
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
  $('calls').value = s.calls;
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
    template: $('template').value || DEFAULT_TEMPLATE,
    calls: $('calls').value.trim() || '0'
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

  if (!$('peerId').value.trim()) {
    setStatus('Укажите чат', true);
    return;
  }
  if (!(await save())) return;

  btn.classList.add('loading');
  setStatus('Собираю данные и отправляю…');
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

// Предпросмотр: собирает данные с портала и показывает готовый текст
// отчёта + список меток, которые не нашлись
async function onPreview() {
  const btn = $('preview');
  pressAnim(btn);
  await save();

  btn.classList.add('loading');
  setStatus('Загружаю данные с портала…');
  const box = $('previewBox');
  try {
    const resp = await chrome.runtime.sendMessage({ type: 'PREVIEW_REPORT' });
    box.hidden = false;
    if (resp && resp.ok) {
      let html = '— Так будет выглядеть отчёт —\n\n' + escapeHtml(resp.text);
      if (resp.unmatched && resp.unmatched.length) {
        html += '\n\n<span class="warn">⚠ Пусто на портале, подставлен 0: ' +
          escapeHtml(resp.unmatched.join(', ')) + '</span>';
      }
      html += '\n\n— Все данные, найденные на странице —\n' +
        Object.entries(resp.fields || {})
          .map(([k, v]) => escapeHtml(k + ': ' + v))
          .join('\n');
      box.innerHTML = html;
      setStatus('Данные получены ✓');
    } else {
      box.hidden = true;
      setStatus('Ошибка: ' + (resp && resp.error ? resp.error : 'неизвестная'), true);
    }
  } finally {
    btn.classList.remove('loading');
  }
}

function escapeHtml(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

$('save').addEventListener('click', onSave);
$('sendNow').addEventListener('click', onSendNow);
$('preview').addEventListener('click', onPreview);
$('resetTemplate').addEventListener('click', (e) => {
  e.preventDefault();
  $('template').value = DEFAULT_TEMPLATE;
  setStatus('Стандартный шаблон вставлен — нажмите «Сохранить»');
});
load();
