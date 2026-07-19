// Контент-скрипт для портала МТС (inventory.ural.mts.ru).
// Собирает со страницы agent_day.php все показатели в виде пар
// «метка → значение»: строки таблиц и текст вида «Метка: значение».
// Подстановкой в шаблон занимается background.js.

(() => {
  if (window.__mtsScrapeLoaded) return;
  window.__mtsScrapeLoaded = true;

  const clean = s => (s || '').replace(/\s+/g, ' ').trim();

  function scrape() {
    const fields = {};
    const add = (label, value) => {
      label = clean(label).replace(/[:：]\s*$/, '');
      value = clean(String(value));
      if (!label || value === '' || label.length > 120) return;
      if (!(label in fields)) fields[label] = value;
    };

    // 1) строки таблиц: первая ячейка — метка, значение — строго из
    //    колонки «Результат оказания услуги»; если такой колонки в
    //    таблице нет — последняя непустая ячейка строки
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
          // пропускаем саму шапку и пустые ячейки «Результата»
          if (v === undefined || v === '' || /результат/i.test(v)) return;
          add(cells[0], v);
        } else {
          const vals = cells.slice(1).filter(v => v !== '');
          if (vals.length) add(cells[0], vals[vals.length - 1]);
        }
      });
    });

    // 2) текстовые строки «Метка: значение» (например «Конверсия: 88%»,
    //    «Тип сегодняшнего дня: Выходной»). Строки, входящие в таблицы,
    //    пропускаем — они уже разобраны по колонкам выше
    const tableLines = new Set();
    document.querySelectorAll('table').forEach(t =>
      t.innerText.split('\n').forEach(l => tableLines.add(l.trim())));
    document.body.innerText.split('\n').forEach(line => {
      if (tableLines.has(line.trim())) return;
      const m = line.match(/^(.{2,80}?)\s*[:：]\s*(.+)$/);
      if (!m) return;
      // не режем время (04:00:00) по двоеточию: «метка 04» → «00:00»
      if (/\d$/.test(m[1]) && /^\d{2}(:\d{2})?$/.test(m[2])) return;
      add(m[1], m[2]);
    });

    // 3) специальные поля для шаблона
    const dateMatch = document.body.innerText.match(/\b(\d{2})\.(\d{2})\.(\d{4})\b/);
    if (dateMatch) {
      add('дата', dateMatch[1] + '.' + dateMatch[2]);
      add('дата полная', dateMatch[0]);
    }
    for (const [label, value] of Object.entries(fields)) {
      if (/тип\s+.*дня/i.test(label)) {
        add('тип дня', value.toLowerCase());
        break;
      }
    }

    return fields;
  }

  chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    if (msg.type === 'SCRAPE_MTS') {
      try {
        const fields = scrape();
        sendResponse({ ok: true, url: location.host + location.pathname, fields });
      } catch (e) {
        sendResponse({ ok: false, error: e.message });
      }
    }
  });
})();
