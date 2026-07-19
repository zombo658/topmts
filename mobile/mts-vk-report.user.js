// ==UserScript==
// @name         МТС → ВК отчёт (телефон)
// @namespace    topmts
// @version      1.0
// @description  Кнопка на странице agent_day.php: собирает отчёт, копирует в буфер и открывает чат ВК — остаётся вставить и отправить
// @match        https://inventory.ural.mts.ru/pc/agent_day.php*
// @grant        none
// ==/UserScript==

(() => {
  'use strict';

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

  // Соответствие строк отчёта полям на портале — как в расширении
  const FIELD_ALIASES = [
    ['поквартирный обход дмх', 'Подомовой обход'],
    ['визуализация дмх', 'Раздача рекламных материалов'],
    ['общее время поквартирного обхода', 'Время подомового обхода'],
    ['общее время визуализации', 'Время раздачи рекламных материалов'],
    ['общее время', 'Время на территории']
  ];

  const clean = s => (s || '').replace(/\s+/g, ' ').trim();
  const norm = s => String(s).toLowerCase().replace(/ё/g, 'е').replace(/[^a-zа-я0-9]/g, '');

  // ---------- сбор данных со страницы (колонка «Результат оказания услуги») ----------

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

  // ---------- заполнение шаблона ----------

  function exactField(name, fields) {
    const key = norm(name);
    for (const [label, value] of Object.entries(fields)) {
      if (norm(label) === key) return value;
    }
    return null;
  }

  function buildReport(fields, calls) {
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
    fields['количество звонков'] = calls;

    // пусто на портале — ставим 0
    return TEMPLATE.replace(/\{([^{}]+)\}/g, (_w, name) => exactField(name, fields) ?? '0');
  }

  // ---------- интерфейс ----------

  function toast(text) {
    const t = document.createElement('div');
    t.textContent = text;
    t.style.cssText = 'position:fixed;left:50%;bottom:90px;transform:translateX(-50%);' +
      'background:#222;color:#fff;padding:10px 16px;border-radius:10px;z-index:99999;' +
      'font:14px sans-serif;max-width:90%;text-align:center;box-shadow:0 4px 14px rgba(0,0,0,.4)';
    document.body.appendChild(t);
    setTimeout(() => t.remove(), 3500);
  }

  async function onSend() {
    const peer = localStorage.getItem('vkReportPeer') ||
      prompt('Чат ВК (как в адресе vk.ru/im?sel=…, например c66):', 'c66');
    if (!peer) return;
    localStorage.setItem('vkReportPeer', peer);

    const savedCalls = localStorage.getItem('vkReportCalls') || '0';
    const calls = prompt('Количество звонков за сегодня:', savedCalls);
    if (calls === null) return;
    localStorage.setItem('vkReportCalls', calls.trim() || '0');

    const text = buildReport(scrape(), calls.trim() || '0');

    try {
      await navigator.clipboard.writeText(text);
      toast('Отчёт скопирован ✓ Открываю чат — вставьте и отправьте');
    } catch (e) {
      // буфер недоступен — показываем текст, чтобы скопировать вручную
      prompt('Скопируйте отчёт вручную:', text);
    }

    setTimeout(() => {
      window.location.href = 'https://vk.com/im?sel=' + encodeURIComponent(peer);
    }, 1200);
  }

  function addButton() {
    if (document.getElementById('vk-report-btn')) return;
    const btn = document.createElement('button');
    btn.id = 'vk-report-btn';
    btn.textContent = '📋 Отчёт в ВК';
    btn.style.cssText = 'position:fixed;right:14px;bottom:20px;z-index:99999;' +
      'padding:14px 20px;border:none;border-radius:14px;cursor:pointer;' +
      'background:linear-gradient(135deg,#2787f5,#0b5bd3);color:#fff;' +
      'font:600 16px sans-serif;box-shadow:0 4px 16px rgba(39,135,245,.5)';
    btn.addEventListener('click', onSend);
    document.body.appendChild(btn);
  }

  addButton();
  // страница может дорисовываться скриптами — следим, чтобы кнопка не пропала
  new MutationObserver(addButton).observe(document.body, { childList: true });
})();
