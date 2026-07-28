package ru.topmts.report

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONTokener

/**
 * Выбор беседы.
 *
 * Вёрстка мессенджера ВК постоянно меняется, а номер беседы в адрес он не
 * пишет — поэтому номер берётся из сетевых запросов самого ВК: страница
 * запрашивает список бесед и историю сообщений, а в этих запросах номер
 * (peer_id) есть всегда. Открыли беседу — номер поймался.
 */
class ChatPickerActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var hint: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    /** Беседа, открытая на момент запуска: её не считаем выбором пользователя. */
    private var baseline: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty().ifEmpty { "vk.com" }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0D1117"))
        setContentView(root)

        web = WebView(this)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                inject()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                inject()
            }
        }
        root.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        hint = TextView(this).apply {
            text = "Откройте нужную беседу — её номер определится сам"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#F02787F5"))
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(24, 26, 24, 26)
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
        )

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F0161B22"))
            setPadding(16, 16, 16, 16)
        }
        bar.addView(
            button("Список бесед") { showChatList() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(
            button("Взять открытую") { takeCurrent() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        )

        web.loadUrl("https://$host/im")
        // подстраховка: перехватчик ставим несколько раз, пока грузится страница
        listOf(300L, 900L, 1800L, 3000L).forEach { handler.postDelayed({ inject() }, it) }
        // всё, что ВК открыл сам при запуске, выбором не считаем
        handler.postDelayed({ readCurrent { baseline = it.ifEmpty { "-" } } }, 4000)
        handler.postDelayed({ poll() }, 4500)
    }

    private fun inject() {
        if (!done) web.evaluateJavascript(HOOK, null)
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#2787F5"))
        setOnClickListener { action() }
    }

    /** Как только пользователь откроет беседу — её номер придёт сюда. */
    private fun poll() {
        if (done || isFinishing) return
        readCurrent { id ->
            if (id.isNotEmpty() && baseline != null && id != baseline) {
                pick(id)
            } else {
                // видно, что механизм работает, даже если беседа была открыта заранее
                if (id.isNotEmpty()) {
                    hint.text = "Сейчас открыта: ${chatLabel(id)} — нажмите «Взять открытую»"
                }
                handler.postDelayed({ poll() }, 500)
            }
        }
    }

    /** Номер открытой беседы: из перехваченных запросов, затем из адреса. */
    private fun readCurrent(cb: (String) -> Unit) {
        web.evaluateJavascript(JS_CURRENT) { raw -> cb(unquote(raw).trim()) }
    }

    private fun showChatList() {
        web.evaluateJavascript(JS_LIST) { raw ->
            val items = parseList(unquote(raw))
            if (items.isEmpty()) {
                Toast.makeText(
                    this,
                    "Список пока пуст. Подождите, пока загрузятся диалоги, прокрутите их — и нажмите ещё раз.",
                    Toast.LENGTH_LONG
                ).show()
                return@evaluateJavascript
            }
            val names = items.map { it.second }.toTypedArray()
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle("Выберите беседу")
                .setItems(names) { _, which -> pick(items[which].first) }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun takeCurrent() {
        readCurrent { id ->
            if (id.isNotEmpty()) {
                pick(id)
            } else {
                Toast.makeText(
                    this,
                    "Пока не удалось определить беседу. Откройте её (чтобы загрузились сообщения) и нажмите снова.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun pick(id: String) {
        if (done) return
        done = true
        CookieManager.getInstance().flush()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PEER, id))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.stopLoading()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun parseList(json: String): List<Pair<String, String>> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").trim()
                if (id.isEmpty()) return@mapNotNull null
                val name = o.optString("name").trim()
                id to if (name.isEmpty()) chatLabel(id) else name
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Человекочитаемая подпись, если названия беседы нет. */
    private fun chatLabel(id: String): String {
        val n = id.toLongOrNull()
        return when {
            n != null && n >= 2_000_000_000L -> "Беседа c${n - 2_000_000_000L}"
            else -> "Диалог $id"
        }
    }

    private fun unquote(raw: String?): String {
        if (raw == null || raw == "null") return ""
        return try {
            val v = JSONTokener(raw).nextValue()
            if (v is String) v else raw
        } catch (e: Exception) {
            raw
        }
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PEER = "peer"

        /**
         * Перехватчик запросов ВК. Из ответов со списком бесед берёт их
         * номера и названия, из запросов истории сообщений — номер той
         * беседы, которую пользователь открыл.
         */
        private const val HOOK = """
(function(){
  if(window.__TP) return 'dup';
  var TP={last:'',convos:[],seen:{}};
  window.__TP=TP;
  function addConvo(id,name){
    id=String(id||'');
    if(!id||id==='0'||TP.seen[id])return;
    TP.seen[id]=1;
    TP.convos.push({id:id,name:String(name||'')});
  }
  function walk(o,d){
    if(!o||typeof o!=='object'||d>6)return;
    try{
      if(o.peer&&o.peer.id){
        var nm='';
        if(o.chat_settings&&o.chat_settings.title)nm=o.chat_settings.title;
        addConvo(o.peer.id,nm);
      }
    }catch(e){}
    for(var k in o){
      try{ var v=o[k]; if(v&&typeof v==='object') walk(v,d+1); }catch(e){}
    }
  }
  function grabPeer(s){
    if(!s)return;
    var m=String(s).match(/peer_?id["'=:\s]*(-?\d+)/i);
    if(m&&m[1]&&m[1]!=='0') TP.last=m[1];
  }
  function handle(url,body,resp){
    var u=String(url||''), b=String(body||'');
    if(/history/i.test(u+' '+b)){ grabPeer(u); grabPeer(b); }
    if(resp&&resp.length<4000000&&/conversation|"peer"/i.test(resp)){
      try{ walk(JSON.parse(resp),0); }catch(e){}
    }
  }
  var O=XMLHttpRequest.prototype.open, S=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(m,u){ this.__tpUrl=u; return O.apply(this,arguments); };
  XMLHttpRequest.prototype.send=function(b){
    var self=this;
    try{
      self.addEventListener('load',function(){
        var t=''; try{ t=self.responseText||''; }catch(e){}
        handle(self.__tpUrl,b,t);
      });
    }catch(e){}
    return S.apply(this,arguments);
  };
  var F=window.fetch;
  if(F){
    window.fetch=function(i,init){
      var u=(typeof i==='string')?i:((i&&i.url)||'');
      var b=(init&&typeof init.body==='string')?init.body:'';
      var p=F.apply(this,arguments);
      try{ p.then(function(r){
        try{ r.clone().text().then(function(t){ handle(u,b,t); }); }catch(e){}
        return r;
      }); }catch(e){}
      return p;
    };
  }
  return 'ok';
})();
"""

        /** Номер открытой беседы: перехваченный, иначе из адреса. */
        private const val JS_CURRENT = """
(function(){
  if(window.__TP && window.__TP.last) return String(window.__TP.last);
  var h=location.href;
  var m=h.match(/[?&#]sel=(c?-?\d+)/)||h.match(/[?&#]peer=(c?-?\d+)/)||h.match(/\/im\/convo\/(-?\d+)/);
  return m?m[1]:'';
})();
"""

        /** Список бесед: перехваченный из ответов ВК плюс ссылки на странице. */
        private const val JS_LIST = """
(function(){
  var out=[],seen={};
  function add(id,name){
    id=String(id||'');
    if(!id||id==='0'||seen[id])return; seen[id]=1;
    out.push({id:id,name:String(name||'').replace(/\s+/g,' ').trim().slice(0,60)});
  }
  if(window.__TP){
    for(var i=0;i<window.__TP.convos.length;i++){
      add(window.__TP.convos[i].id, window.__TP.convos[i].name);
    }
  }
  var links=document.querySelectorAll('a[href]');
  for(var j=0;j<links.length;j++){
    var h=links[j].getAttribute('href')||'';
    var m=h.match(/sel=(c?-?\d+)/)||h.match(/peer=(c?-?\d+)/)||h.match(/\/im\/convo\/(-?\d+)/);
    if(m) add(m[1], links[j].innerText);
  }
  // беседы вперёд — обычно нужна именно беседа
  out.sort(function(a,b){
    var A=(String(a.id).indexOf('2000000')===0)?0:1;
    var B=(String(b.id).indexOf('2000000')===0)?0:1;
    return A-B;
  });
  return JSON.stringify(out);
})();
"""
    }
}
