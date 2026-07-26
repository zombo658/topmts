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
  function cls(el){var c=el.className;return (c&&c.baseVal!==undefined?c.baseVal:c)||'';}
  // Кнопка «отправить»: ищем по подписи/классу среди кликабельных элементов и
  // выбираем ту, что стоит на одной линии с полем ввода (вёрстка ВК меняется,
  // поэтому жёсткие селекторы — только запасной вариант).
  function sendBtn(){
    var input=find(INPUT);
    var nodes=[].slice.call(document.querySelectorAll(
      'button,[role="button"],[aria-label],[title],[data-testid],[class*="send"],[class*="Send"]'));
    var best=null,bestD=1e9;
    var ir=input?input.getBoundingClientRect():null;
    nodes.forEach(function(el){
      if(!vis(el)||el.disabled)return;
      var c=cls(el);
      var lab=((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('title')||'')+' '+
               (el.getAttribute('data-testid')||'')+' '+c).toLowerCase();
      if(!/send|отправ/.test(lab))return;
      if(/locked|disabled|attach|voice|audio|emoji|sticker|graffiti/.test(lab))return;
      var b=el.getBoundingClientRect();
      if(b.width<12||b.height<12||b.width>260)return;
      // ближе всего по вертикали к полю ввода и правее его начала
      var d=ir?Math.abs((b.top+b.height/2)-(ir.top+ir.height/2)):b.top;
      if(ir&&b.left<ir.left-40)d+=500;
      if(d<bestD){bestD=d;best=el;}
    });
    return best||find(SEND);
  }
  // «Нажатие» кнопки максимально похоже на палец: касание → указатель → мышь → click
  function realClick(el){
    var b=el.getBoundingClientRect();
    var cx=b.left+b.width/2, cy=b.top+b.height/2;
    try{
      var t=new Touch({identifier:1,target:el,clientX:cx,clientY:cy,pageX:cx,pageY:cy});
      el.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,composed:true,
        touches:[t],targetTouches:[t],changedTouches:[t]}));
      el.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,composed:true,
        touches:[],targetTouches:[],changedTouches:[t]}));
    }catch(e){}
    var P=window.PointerEvent||MouseEvent;
    function fire(type,Ctor,extra){
      var o={bubbles:true,cancelable:true,composed:true,clientX:cx,clientY:cy,button:0,buttons:1};
      for(var k in extra)o[k]=extra[k];
      try{el.dispatchEvent(new Ctor(type,o));}catch(e){}
    }
    var pt={pointerId:1,pointerType:'touch',isPrimary:true};
    fire('pointerdown',P,pt); fire('mousedown',MouseEvent,{});
    fire('pointerup',P,{pointerId:1,pointerType:'touch',isPrimary:true,buttons:0});
    fire('mouseup',MouseEvent,{buttons:0}); fire('click',MouseEvent,{buttons:0});
    try{el.click();}catch(e){}
  }
  function txt(el){return (el.value!==undefined?el.value:el.innerText)||'';}
  function cur(input){return (input.value!==undefined?input.value:input.innerText)||'';}
  function caretEnd(input){
    if(input.value!==undefined)return;
    var r=document.createRange();r.selectNodeContents(input);r.collapse(false);
    var s=getSelection();s.removeAllRanges();s.addRange(r);
  }
  function fill(text){
    var input=find(INPUT);
    if(!input)return 'nofield';
    input.focus();
    var probe=text.slice(0,15);
    // 1) синтетический paste — надёжнее всего регистрируется редактором ВК
    try{
      caretEnd(input);
      var dt=new DataTransfer();dt.setData('text/plain',text);
      input.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));
    }catch(e){}
    if(cur(input).indexOf(probe)>=0)return 'filled';
    // 2) execCommand insertText
    caretEnd(input);
    document.execCommand('insertText',false,text);
    if(cur(input).indexOf(probe)>=0){
      input.dispatchEvent(new InputEvent('input',{bubbles:true,data:text,inputType:'insertText'}));
      return 'filled';
    }
    // 3) прямое значение
    if(input.value!==undefined){
      var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(input),'value');
      if(d&&d.set)d.set.call(input,text); else input.value=text;
    }else{input.innerText=text;}
    input.dispatchEvent(new InputEvent('input',{bubbles:true,data:text,inputType:'insertText'}));
    return cur(input).indexOf(probe)>=0?'filled':'nofield';
  }
  function click(){
    var input=find(INPUT); var btn=sendBtn();
    if(btn){realClick(btn);return 'clicked';}
    if(input){var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
      input.dispatchEvent(new KeyboardEvent('keydown',o));
      input.dispatchEvent(new KeyboardEvent('keypress',o));
      input.dispatchEvent(new KeyboardEvent('keyup',o));return 'enter';}
    return 'nofield';
  }
  function check(){var i=find(INPUT);if(!i)return 'nofield';return txt(i).trim()===''?'sent':'notsent';}
  function has(){return !!find(INPUT);}
  function clear(){var i=find(INPUT);if(!i)return false;i.focus();
    if(i.value!==undefined){var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(i),'value');
      if(d&&d.set)d.set.call(i,''); else i.value='';}
    else{i.innerText='';}
    i.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContentBackward'}));return true;}
  // координаты центра кнопки «отправить» (в CSS-пикселях) + ширина вьюпорта —
  // для нативного тапа из Android
  function rect(){var b=sendBtn();if(!b)return '';var r=b.getBoundingClientRect();
    var lab=(b.getAttribute('aria-label')||b.getAttribute('title')||b.getAttribute('data-testid')||cls(b)||'кнопка').slice(0,24);
    return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,iw:window.innerWidth,label:lab});}
  // поставить фокус в поле ввода и курсор в конец — перед нативным Enter
  function focusInput(){var i=find(INPUT);if(!i)return false;i.focus();
    if(i.value===undefined){var r=document.createRange();r.selectNodeContents(i);r.collapse(false);
      var s=getSelection();s.removeAllRanges();s.addRange(r);}return true;}
  window.TOPMTS={fill:fill,click:click,check:check,has:has,rect:rect,focusInput:focusInput,clear:clear};
  return 'ready';
})();
""".trimIndent()

    fun callFill(text: String) = "window.TOPMTS && window.TOPMTS.fill(${q(text)});"
    fun callClick() = "window.TOPMTS && window.TOPMTS.click();"
    fun callCheck() = "window.TOPMTS && window.TOPMTS.check();"
    fun callHas() = "(window.TOPMTS && window.TOPMTS.has()) ? 'true' : 'false';"
    fun callRect() = "window.TOPMTS ? window.TOPMTS.rect() : '';"
    fun callFocus() = "(window.TOPMTS && window.TOPMTS.focusInput()) ? 'ok' : 'no';"
    fun callClear() = "(window.TOPMTS && window.TOPMTS.clear()) ? 'ok' : 'no';"
}
