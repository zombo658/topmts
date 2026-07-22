// ==UserScript==
// @name         МТС→ВК авто (Android) — шаг 1: сбор отчёта на портале
// @namespace    topmts
// @version      1.0
// @description  На agent_day.php собирает отчёт и сам переходит в чат ВК, передавая текст. Работает от вашего имени, без бота.
// @match        https://inventory.ural.mts.ru/pc/agent_day.php*
// @grant        none
// @run-at       document-idle
// ==/UserScript==

(() => {
  'use strict';

  // ===== НАСТРОЙКИ — заполните один раз =====
  const CONFIG = {
    peer: 'c66',          // чат ВК: как в адресе vk.ru/im?sel=…  (c66 — беседа №66)
    calls: '0',           // количество звонков (портал его не показывает)
    vkHost: 'vk.ru',      // домен ВК: vk.ru или vk.com
    // Если открыли портал вручную (не по расписанию) — не уходить автоматически:
    autoOnlyFromScheduler: true,
    schedulerFlag: 'auto' // MacroDroid открывает URL с ?auto=1 — тогда переход сработает
  };
  // ==========================================

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

  const clean = s => (s || '').replace(/\s+/g, ' ').trim();
  const norm = s => String(s).toLowerCase().replace(/ё/g, 'е').replace(/[^a-zа-я0-9]/g, '');

  function scrape() {
    const fields = {};
    const add = (label, value) => {
      label = clean(label).replace(/[:：]\s*$/, '');
      value = clean(String(value));
      if (!label || value === '' || label.length > 120) return;
      if (!(label in fields)) fields[label] = value;
    };
    document.querySelectorAll('table').forEach(table => {
      const rows = [...table.rows];
      let resultIdx = -1;
      for (const tr of rows) {
        const cells = [...tr.cells].map(c => clean(c.innerText));
        const idx = cells.findIndex(t => /результат/i.test(t));
        if (idx > 0) { resultIdx = idx; break; }
      }
      rows.forEach(tr => {
        const cells = [...tr.cells].map(c => clean(c.innerText));
        if (cells.length < 2 || !cells[0]) return;
        if (resultIdx >= 0) {
          const v = cells[resultIdx];
          if (v === undefined || v === '' || /результат/i.test(v)) return;
          add(cells[0], v);
        } else {
          const vals = cells.slice(1).filter(v => v !== '');
          if (vals.length) add(cells[0], vals[vals.length - 1]);
        }
      });
    });
    const tableLines = new Set();
    document.querySelectorAll('table').forEach(t =>
      t.innerText.split('\n').forEach(l => tableLines.add(l.trim())));
    document.body.innerText.split('\n').forEach(line => {
      if (tableLines.has(line.trim())) return;
      const m = line.match(/^(.{2,80}?)\s*[:：]\s*(.+)$/);
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

  function buildReport(fields) {
    for (const [alias, source] of FIELD_ALIASES) {
      const v = exactField(source, fields);
      if (v !== null) fields[alias] = v;
    }
    const walk = parseInt(exactField('Подомовой обход', fields), 10);
    const promo = parseInt(exactField('Раздача рекламных материалов', fields), 10);
    if (!Number.isNaN(walk) && !Number.isNaN(promo)) {
      fields['общее количество дмх'] = String(walk + promo);
    }
    const now = new Date();
    fields['дата'] = String(now.getDate()).padStart(2, '0') + '.' +
                     String(now.getMonth() + 1).padStart(2, '0');
    const calls = localStorage.getItem('vkReportCalls') || CONFIG.calls;
    fields['количество звонков'] = calls;
    return TEMPLATE.replace(/\{([^{}]+)\}/g, (_w, name) => exactField(name, fields) ?? '0');
  }

  // base64 UTF-8 для передачи текста через адрес
  const b64 = s => btoa(unescape(encodeURIComponent(s)));

  function go() {
    const fields = scrape();
    if (!Object.keys(fields).length) return false; // страница ещё не готова
    const report = buildReport(fields);
    const peer = localStorage.getItem('vkReportPeer') || CONFIG.peer;
    const url = 'https://' + CONFIG.vkHost + '/im?sel=' + encodeURIComponent(peer) +
                '#vkreport=' + encodeURIComponent(b64(report));
    location.assign(url);
    return true;
  }

  // Переходить автоматически только когда портал открыт планировщиком
  const params = new URLSearchParams(location.search);
  const isAuto = !CONFIG.autoOnlyFromScheduler || params.has(CONFIG.schedulerFlag);
  if (!isAuto) return;

  // Ждём, пока прогрузится таблица с данными (до ~20 сек)
  let tries = 0;
  const timer = setInterval(() => {
    if (go() || ++tries > 20) clearInterval(timer);
  }, 1000);
})();
