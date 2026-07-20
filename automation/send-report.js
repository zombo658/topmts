// Полностью автоматическая отправка отчётов: запускается по расписанию
// в GitHub Actions (или вручную: node automation/send-report.js).
// Для каждого сотрудника: входит на портал МТС по логину/паролю,
// собирает показатели со страницы agent_day.php и отправляет отчёт
// в беседу ВК через официальный Bot API (токен сообщества).
//
// Настройки через переменные окружения (в Actions — через Secrets):
//   EMPLOYEES — JSON-массив сотрудников:
//     [{"name":"Иван","login":"...","password":"...","calls":"0"}, ...]
//   VK_TOKEN  — токен сообщества с правами на сообщения
//   VK_PEER   — id беседы (2000000066 или c66)
//   DAYS      — дни отправки, номера через запятую (0=вс … 6=сб),
//               по умолчанию все дни
//   TZ_OFFSET — смещение часового пояса от UTC в часах (по умолчанию 5 — Урал)

const MTS_URL = 'https://inventory.ural.mts.ru/pc/agent_day.php';
const VK_API = 'https://api.vk.com/method';
const VK_V = '5.199';

const TEMPLATE = [
  '{дата} {тип дня}',
  'Общее количество ДМХ: {общее количество дмх}',
  'Поквартирный обход ДМХ: {поквартирный обход дмх}',
  'Общее время поквартирного обхода: {общее время поквартирного обхода}',
  'Визуализация, дмх: {визуализация дмх}',
  'Общее время визуализации: {общее время визуализации}',
  'Общее время: {общее время}',
  'Количество звонков: {количество звонков}'
].join('\n');

const FIELD_ALIASES = [
  ['поквартирный обход дмх', 'Подомовой обход'],
  ['визуализация дмх', 'Раздача рекламных материалов'],
  ['общее время поквартирного обхода', 'Время подомового обхода'],
  ['общее время визуализации', 'Время раздачи рекламных материалов'],
  ['общее время', 'Время на территории']
];

// ---------- утилиты ----------

const clean = s => (s || '').replace(/\s+/g, ' ').trim();
const norm = s => String(s).toLowerCase().replace(/ё/g, 'е').replace(/[^a-zа-я0-9]/g, '');

function decodeEntities(s) {
  return s
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(n));
}

function stripTags(html) {
  return decodeEntities(
    html
      .replace(/<(script|style)[\s\S]*?<\/\1>/gi, '')
      .replace(/<(br|\/tr|\/p|\/div|\/li|\/h\d)[^>]*>/gi, '\n')
      .replace(/<[^>]+>/g, '')
  );
}

// ---------- HTTP с cookie и автологином ----------

class Session {
  constructor() { this.cookies = new Map(); }

  cookieHeader() {
    return [...this.cookies.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
  }

  storeCookies(res) {
    const list = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
    for (const c of list) {
      const [pair] = c.split(';');
      const eq = pair.indexOf('=');
      if (eq > 0) this.cookies.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
    }
  }

  // fetch с ручными редиректами, чтобы не терять set-cookie
  async request(url, options = {}) {
    for (let i = 0; i < 10; i++) {
      const res = await fetch(url, {
        ...options,
        redirect: 'manual',
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
          Cookie: this.cookieHeader(),
          ...(options.headers || {})
        }
      });
      this.storeCookies(res);
      if (res.status >= 300 && res.status < 400) {
        const loc = res.headers.get('location');
        if (!loc) return res;
        url = new URL(loc, url).href;
        options = { method: 'GET' }; // после редиректа — обычный GET
        continue;
      }
      return res;
    }
    throw new Error('Слишком много редиректов: ' + url);
  }
}

// Ищет на странице форму входа и возвращает {action, fields}
function parseLoginForm(html, baseUrl) {
  const formMatch = html.match(/<form[^>]*>[\s\S]*?<\/form>/gi);
  if (!formMatch) return null;
  for (const formHtml of formMatch) {
    if (!/type\s*=\s*["']?password/i.test(formHtml)) continue;
    const actionMatch = formHtml.match(/action\s*=\s*["']?([^"'\s>]*)/i);
    const action = actionMatch && actionMatch[1]
      ? new URL(decodeEntities(actionMatch[1]), baseUrl).href
      : baseUrl;
    const fields = {};
    let passwordField = null;
    let loginField = null;
    for (const inputHtml of formHtml.match(/<input[^>]*>/gi) || []) {
      const name = (inputHtml.match(/name\s*=\s*["']?([^"'\s>]+)/i) || [])[1];
      if (!name) continue;
      const type = ((inputHtml.match(/type\s*=\s*["']?([^"'\s>]+)/i) || [])[1] || 'text').toLowerCase();
      const value = (inputHtml.match(/value\s*=\s*["']([^"']*)["']/i) || [])[1] || '';
      fields[name] = decodeEntities(value);
      if (type === 'password' && !passwordField) passwordField = name;
      if ((type === 'text' || type === 'email') && !loginField) loginField = name;
    }
    if (passwordField) return { action, fields, loginField, passwordField };
  }
  return null;
}

// Загружает страницу отчёта, при необходимости выполняя вход
async function fetchAgentDayHtml(login, password) {
  const session = new Session();
  let res = await session.request(MTS_URL);
  let html = await res.text();

  if (/type\s*=\s*["']?password/i.test(html)) {
    const form = parseLoginForm(html, res.url || MTS_URL);
    if (!form) throw new Error('Не удалось разобрать форму входа на портале');
    form.fields[form.loginField || 'login'] = login;
    form.fields[form.passwordField] = password;
    res = await session.request(form.action, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(form.fields).toString()
    });
    html = await res.text();
    if (/type\s*=\s*["']?password/i.test(html)) {
      throw new Error('Портал не принял логин/пароль (форма входа показана повторно)');
    }
    // после логина заново открываем страницу отчёта
    res = await session.request(MTS_URL);
    html = await res.text();
  }

  if (!res.ok && res.status !== 200) {
    throw new Error('Портал ответил кодом ' + res.status);
  }
  return html;
}

// ---------- разбор страницы (те же правила, что в расширении) ----------

function scrapeFields(html) {
  const fields = {};
  const add = (label, value) => {
    label = clean(label).replace(/[:：]\s*$/, '');
    value = clean(String(value));
    if (!label || value === '' || label.length > 120) return;
    if (!(label in fields)) fields[label] = value;
  };

  // таблицы: значение — из колонки «Результат оказания услуги»
  const tables = html.match(/<table[\s\S]*?<\/table>/gi) || [];
  for (const tableHtml of tables) {
    const rows = (tableHtml.match(/<tr[\s\S]*?<\/tr>/gi) || []).map(rowHtml =>
      (rowHtml.match(/<t[dh][^>]*>[\s\S]*?<\/t[dh]>/gi) || [])
        .map(cell => clean(stripTags(cell))));
    let resultIdx = -1;
    for (const cells of rows) {
      const idx = cells.findIndex(t => /результат/i.test(t));
      if (idx > 0) { resultIdx = idx; break; }
    }
    for (const cells of rows) {
      if (cells.length < 2 || !cells[0]) continue;
      if (resultIdx >= 0) {
        const v = cells[resultIdx];
        if (v === undefined || v === '' || /результат/i.test(v)) continue;
        add(cells[0], v);
      } else {
        const vals = cells.slice(1).filter(v => v !== '');
        if (vals.length) add(cells[0], vals[vals.length - 1]);
      }
    }
  }

  // текст вне таблиц: «Метка: значение»
  const htmlNoTables = html.replace(/<table[\s\S]*?<\/table>/gi, '\n');
  stripTags(htmlNoTables).split('\n').forEach(line => {
    const m = line.trim().match(/^(.{2,80}?)\s*[:：]\s*(.+)$/);
    if (!m) return;
    if (/\d$/.test(m[1]) && /^\d{2}(:\d{2})?$/.test(m[2])) return;
    add(m[1], m[2]);
  });

  for (const [label, value] of Object.entries(fields)) {
    if (/тип\s+.*дня/i.test(label)) { fields['тип дня'] = value.toLowerCase(); break; }
  }
  return fields;
}

function exactField(name, fields) {
  const key = norm(name);
  for (const [label, value] of Object.entries(fields)) {
    if (norm(label) === key) return value;
  }
  return null;
}

function buildReport(fields, calls, now) {
  for (const [alias, source] of FIELD_ALIASES) {
    const v = exactField(source, fields);
    if (v !== null) fields[alias] = v;
  }
  const walk = parseInt(exactField('Подомовой обход', fields), 10);
  const promo = parseInt(exactField('Раздача рекламных материалов', fields), 10);
  if (!Number.isNaN(walk) && !Number.isNaN(promo)) {
    fields['общее количество дмх'] = String(walk + promo);
  }
  fields['дата'] = String(now.getUTCDate()).padStart(2, '0') + '.' +
                   String(now.getUTCMonth() + 1).padStart(2, '0');
  fields['количество звонков'] = String(calls || '0');

  // пусто на портале — ставим 0
  return TEMPLATE.replace(/\{([^{}]+)\}/g, (_w, name) => exactField(name, fields) ?? '0');
}

// ---------- отправка в ВК (Bot API сообщества) ----------

function normalizePeer(peer) {
  const id = String(peer).trim();
  const chatMatch = id.match(/^c(\d+)$/i);
  if (chatMatch) return 2000000000 + Number(chatMatch[1]);
  return Number(id);
}

async function vkSend(token, peer, message) {
  const res = await fetch(VK_API + '/messages.send', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      access_token: token,
      v: VK_V,
      peer_id: String(normalizePeer(peer)),
      random_id: String(Date.now()) + Math.floor(Math.random() * 1000),
      message
    }).toString()
  });
  const data = await res.json();
  if (data.error) {
    throw new Error('VK API: ' + data.error.error_msg + ' (код ' + data.error.error_code + ')');
  }
  return data.response;
}

// ---------- главный цикл ----------

async function main() {
  const employees = JSON.parse(process.env.EMPLOYEES || '[]');
  const token = process.env.VK_TOKEN;
  const peer = process.env.VK_PEER;
  const tzOffset = Number(process.env.TZ_OFFSET || '5');

  if (!employees.length) throw new Error('Секрет EMPLOYEES пуст — добавьте сотрудников');
  if (!token) throw new Error('Секрет VK_TOKEN не задан');
  if (!peer) throw new Error('Секрет VK_PEER не задан');

  // локальное время сотрудников (по умолчанию Урал, UTC+5)
  const nowLocal = new Date(Date.now() + tzOffset * 3600 * 1000);
  const days = (process.env.DAYS || '0,1,2,3,4,5,6').split(',').map(Number);
  if (!days.includes(nowLocal.getUTCDay())) {
    console.log('Сегодня отправка не запланирована (DAYS=' + days.join(',') + ')');
    return;
  }

  let failed = 0;
  for (const emp of employees) {
    const name = emp.name || emp.login;
    try {
      if (!emp.login || !emp.password) throw new Error('не указан логин или пароль');
      const html = await fetchAgentDayHtml(emp.login, emp.password);
      const fields = scrapeFields(html);
      if (!Object.keys(fields).length) throw new Error('на странице портала не нашлось данных');
      const report = buildReport(fields, emp.calls, nowLocal);
      await vkSend(token, peer, 'Отчёт — ' + name + '\n' + report);
      console.log('✓ ' + name + ': отчёт отправлен');
    } catch (e) {
      failed++;
      console.error('✗ ' + name + ': ' + e.message);
    }
  }

  if (failed) throw new Error('Не отправлено отчётов: ' + failed + ' из ' + employees.length);
}

main().catch(e => {
  console.error(e.message);
  process.exit(1);
});
