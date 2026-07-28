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
 * Выбор беседы. Работает двумя путями, чтобы не зависеть от вёрстки ВК:
 *  1) кнопка «Список бесед» — собирает диалоги со страницы и показывает
 *     их обычным списком: выбрал → номер подставился;
 *  2) откройте беседу в ВК и нажмите «Взять открытую» (или номер поймается
 *     из адреса сам, если ВК его туда пишет).
 */
class ChatPickerActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var hint: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty().ifEmpty { "vk.ru" }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0D1117"))
        setContentView(root)

        web = WebView(this)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        root.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        hint = TextView(this).apply {
            text = "Откройте беседу или нажмите «Список бесед»"
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

        // нижняя панель с кнопками — не зависит от того, что делает ВК с адресом
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
        handler.postDelayed({ poll() }, 2000)
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#2787F5"))
        setOnClickListener { action() }
    }

    /** Если ВК всё же пишет беседу в адрес — подхватываем автоматически. */
    private fun poll() {
        if (done || isFinishing) return
        web.evaluateJavascript("location.href") { raw ->
            val id = extractPeer(unquote(raw))
            if (id != null) pick(id) else handler.postDelayed({ poll() }, 500)
        }
    }

    /** Собрать беседы со страницы и показать списком. */
    private fun showChatList() {
        web.evaluateJavascript(JS_LIST) { raw ->
            val json = unquote(raw)
            val items = parseList(json)
            if (items.isEmpty()) {
                Toast.makeText(
                    this,
                    "Список пуст — прокрутите диалоги и повторите, либо откройте беседу и нажмите «Взять открытую»",
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

    /** Взять беседу, открытую сейчас на экране: из адреса или из разметки. */
    private fun takeCurrent() {
        web.evaluateJavascript(JS_CURRENT) { raw ->
            val id = unquote(raw).trim()
            if (id.isNotEmpty() && id != "null") {
                pick(id)
            } else {
                Toast.makeText(
                    this,
                    "Не удалось определить беседу. Откройте её и повторите, либо впишите номер вручную.",
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
                if (id.isEmpty()) null
                else id to o.optString("name").ifBlank { id }
            }
        } catch (e: Exception) {
            emptyList()
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

        /** Номер беседы из адреса: ?sel=c66, ?sel=2000000066, /im/convo/…, ?peer=… */
        fun extractPeer(url: String): String? {
            Regex("[?&#]sel=(c?-?\\d+)").find(url)?.let { return it.groupValues[1] }
            Regex("[?&#]peer=(c?-?\\d+)").find(url)?.let { return it.groupValues[1] }
            Regex("/im/convo/(-?\\d+)").find(url)?.let { return it.groupValues[1] }
            return null
        }

        /** Собирает беседы из ссылок и data-атрибутов списка диалогов. */
        private const val JS_LIST = """
(function(){
  var out=[],seen={};
  function add(id,name){
    if(!id||seen[id])return; seen[id]=1;
    out.push({id:id,name:(name||'').replace(/\s+/g,' ').trim().slice(0,60)||id});
  }
  function fromHref(h){
    var m=h.match(/sel=(c?-?\d+)/)||h.match(/peer=(c?-?\d+)/)||h.match(/\/im\/convo\/(-?\d+)/);
    return m?m[1]:null;
  }
  var links=document.querySelectorAll('a[href]');
  for(var i=0;i<links.length;i++){
    var id=fromHref(links[i].getAttribute('href')||'');
    if(id)add(id,links[i].innerText);
  }
  var nodes=document.querySelectorAll('[data-peer],[data-peer-id],[data-convo-id],[data-id]');
  for(var j=0;j<nodes.length;j++){
    var el=nodes[j];
    var v=el.getAttribute('data-peer')||el.getAttribute('data-peer-id')||
          el.getAttribute('data-convo-id')||el.getAttribute('data-id')||'';
    if(/^c?-?\d+${'$'}/.test(v)) add(v,el.innerText);
  }
  return JSON.stringify(out);
})();
"""

        /** Беседа, открытая прямо сейчас: адрес, затем разметка. */
        private const val JS_CURRENT = """
(function(){
  var h=location.href;
  var m=h.match(/[?&#]sel=(c?-?\d+)/)||h.match(/[?&#]peer=(c?-?\d+)/)||h.match(/\/im\/convo\/(-?\d+)/);
  if(m)return m[1];
  var sel=document.querySelector('[data-peer].im-page--chat,[data-peer-id].im-page--chat,'+
    '.im-page--chat[data-peer],.im_convo[data-peer],[data-convo-id]');
  if(sel){
    var v=sel.getAttribute('data-peer')||sel.getAttribute('data-peer-id')||sel.getAttribute('data-convo-id');
    if(v)return v;
  }
  var act=document.querySelector('a[href*="sel="][class*="selected"],a[href*="sel="][class*="active"],'+
    'a[href*="sel="][aria-selected="true"]');
  if(act){var mm=(act.getAttribute('href')||'').match(/sel=(c?-?\d+)/);if(mm)return mm[1];}
  return '';
})();
"""
    }
}
