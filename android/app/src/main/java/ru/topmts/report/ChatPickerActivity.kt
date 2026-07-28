package ru.topmts.report

import android.app.Activity
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
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONTokener

/**
 * Выбор беседы: открывает список диалогов ВК. Как только пользователь
 * заходит в нужную беседу, её номер (sel=…) вытаскивается из адреса
 * и возвращается в настройки — вручную искать ID не нужно.
 */
class ChatPickerActivity : Activity() {

    private lateinit var web: WebView
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

        val hint = TextView(this).apply {
            text = "Откройте нужную беседу — её номер подставится сам"
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

        web.loadUrl("https://$host/im")
        handler.postDelayed({ poll() }, 1500)
    }

    /** Следим за адресом страницы: как только в нём появится беседа — берём её номер. */
    private fun poll() {
        if (done || isFinishing) return
        web.evaluateJavascript("location.href") { raw ->
            val url = unquote(raw)
            val id = extractPeer(url)
            if (id != null) pick(id) else handler.postDelayed({ poll() }, 400)
        }
    }

    private fun pick(id: String) {
        if (done) return
        done = true
        CookieManager.getInstance().flush()
        Toast.makeText(this, "Чат выбран: $id", Toast.LENGTH_SHORT).show()
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

        /** Номер беседы из адреса: ?sel=c66, ?sel=2000000066, /im/convo/2000000066. */
        fun extractPeer(url: String): String? {
            Regex("[?&#]sel=(c?-?\\d+)").find(url)?.let { return it.groupValues[1] }
            Regex("/im/convo/(-?\\d+)").find(url)?.let { return it.groupValues[1] }
            return null
        }
    }
}
