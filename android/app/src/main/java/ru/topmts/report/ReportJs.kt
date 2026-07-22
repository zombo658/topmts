package ru.topmts.report

import org.json.JSONObject

/**
 * JavaScript, выполняемый внутри WebView.
 * Логика сбора данных и отправки — та же, что в расширении и userscript.
 */
object ReportJs {

    private fun q(s: String): String = JSONObject.quote(s)

    /**
     * Открытая на портале страница: собрать показатели из колонки
     * «Результат оказания услуги», применить расшифровку и вернуть
     * готовый текст отчёта. Если данных нет — вернуть пустую строку.
     */
    fun scrapeAndBuild(calls: String, template: String): String = """
(function(){
  try {
    var CALLS = ${q(calls)};
    var TEMPLATE = ${q(template)};
    var ALIASES = [
      ['поквартирный обход дмх','Подомовой обход'],
      ['визуализация дмх','Раздача рекламных материалов'],
      ['общее время поквартирного обхода','Время подомового обхода'],
      ['общее время визуализации','Время раздачи рекламных материалов'],
      ['общее время','Время на территории']
    ];
    function clean(s){return (s||'').replace(/\s+/g,' ').trim();}
    function norm(s){return String(s).toLowerCase().replace(/ё/g,'е').replace(/[^a-zа-я0-9]/g,'');}
    var fields={};
    function add(label,value){
      label=clean(label).replace(/[:：]\s*${'$'}/,'');
      value=clean(String(value));
      if(!label||value===''||label.length>120)return;
      if(!(label in fields))fields[label]=value;
    }
    document.querySelectorAll('table').forEach(function(table){
      var rows=[].slice.call(table.rows);
      var resultIdx=-1;
      for(var i=0;i<rows.length;i++){
        var cells=[].slice.call(rows[i].cells).map(function(c){return clean(c.innerText);});
        var idx=cells.findIndex(function(t){return /результат/i.test(t);});
        if(idx>0){resultIdx=idx;break;}
      }
      rows.forEach(function(tr){
        var cells=[].slice.call(tr.cells).map(function(c){return clean(c.innerText);});
        if(cells.length<2||!cells[0])return;
        if(resultIdx>=0){
          var v=cells[resultIdx];
          if(v===undefined||v===''||/результат/i.test(v))return;
          add(cells[0],v);
        }else{
          var vals=cells.slice(1).filter(function(v){return v!=='';});
          if(vals.length)add(cells[0],vals[vals.length-1]);
        }
      });
    });
    var tableLines={};
    document.querySelectorAll('table').forEach(function(t){
      t.innerText.split('\n').forEach(function(l){tableLines[l.trim()]=1;});
    });
    document.body.innerText.split('\n').forEach(function(line){
      if(tableLines[line.trim()])return;
      var m=line.match(/^(.{2,80}?)\s*[:：]\s*(.+)${'$'}/);
      if(!m)return;
      if(/\d${'$'}/.test(m[1])&&/^\d{2}(:\d{2})?${'$'}/.test(m[2]))return;
      add(m[1],m[2]);
    });
    for(var k in fields){ if(/тип\s+.*дня/i.test(k)){ fields['тип дня']=fields[k].toLowerCase(); break; } }

    if(!Object.keys(fields).length) return '';

    function exact(name){
      var n=norm(name);
      for(var k in fields){ if(norm(k)===n) return fields[k]; }
      return null;
    }
    ALIASES.forEach(function(a){ var v=exact(a[1]); if(v!==null) fields[a[0]]=v; });
    var walk=parseInt(exact('Подомовой обход'),10);
    var promo=parseInt(exact('Раздача рекламных материалов'),10);
    if(!isNaN(walk)&&!isNaN(promo)) fields['общее количество дмх']=String(walk+promo);
    var now=new Date();
    fields['дата']=('0'+now.getDate()).slice(-2)+'.'+('0'+(now.getMonth()+1)).slice(-2);
    fields['количество звонков']=CALLS;

    return TEMPLATE.replace(/\{([^{}]+)\}/g,function(w,name){
      var v=exact(name); return v===null?'0':v;
    });
  } catch(e){ return 'ERR:'+e.message; }
})();
""".trimIndent()

    /** Бутстрап на странице ВК: определяет window.TOPMTS с методами fill/check/retry. */
    fun vkBootstrap(): String = """
(function(){
  if(window.TOPMTS) return 'ready';
  var INPUT=['textarea[name="message"]','#im_editable',
    '[data-testid="im_msg_input"] [contenteditable="true"]',
    '.im-chat-input--text[contenteditable="true"]',
    '[contenteditable="true"][role="textbox"]','[contenteditable="true"]','textarea'];
  var SEND=['[data-testid="im_send_btn"]','#im_send',
    '.im-send-btn:not(.im-send-btn_locked)','[aria-label="Отправить"]','button[type="submit"]'];
  function vis(el){return el&&el.getClientRects().length&&getComputedStyle(el).visibility!=='hidden';}
  function find(list){for(var i=0;i<list.length;i++){var e=[].slice.call(document.querySelectorAll(list[i])).filter(vis);if(e.length)return e[e.length-1];}return null;}
  function txt(el){return (el.value!==undefined?el.value:el.innerText)||'';}
  function fill(text){
    var input=find(INPUT);
    if(!input)return 'nofield';
    input.focus();
    if(input.value!==undefined){
      var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(input),'value');
      if(d&&d.set)d.set.call(input,text); else input.value=text;
    }else{
      var r=document.createRange();r.selectNodeContents(input);r.collapse(false);
      var s=getSelection();s.removeAllRanges();s.addRange(r);
      if(!document.execCommand('insertText',false,text))input.innerText=text;
    }
    input.dispatchEvent(new InputEvent('input',{bubbles:true,data:text,inputType:'insertText'}));
    return 'filled';
  }
  function click(){
    var input=find(INPUT); var btn=find(SEND);
    if(btn){btn.click();return 'clicked';}
    if(input){var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
      input.dispatchEvent(new KeyboardEvent('keydown',o));
      input.dispatchEvent(new KeyboardEvent('keypress',o));
      input.dispatchEvent(new KeyboardEvent('keyup',o));return 'enter';}
    return 'nofield';
  }
  function check(){var i=find(INPUT);if(!i)return 'nofield';return txt(i).trim()===''?'sent':'notsent';}
  function has(){return !!find(INPUT);}
  window.TOPMTS={fill:fill,click:click,check:check,has:has};
  return 'ready';
})();
""".trimIndent()

    fun callFill(text: String) = "window.TOPMTS && window.TOPMTS.fill(${q(text)});"
    fun callClick() = "window.TOPMTS && window.TOPMTS.click();"
    fun callCheck() = "window.TOPMTS && window.TOPMTS.check();"
    fun callHas() = "(window.TOPMTS && window.TOPMTS.has()) ? 'true' : 'false';"
}
