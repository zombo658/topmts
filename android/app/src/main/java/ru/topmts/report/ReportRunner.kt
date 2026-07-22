package ru.topmts.report

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener

/**
 * Прогоняет весь цикл в скрытом WebView:
 *   портал → сбор отчёта → чат ВК → вставка и отправка.
 * Использует сохранённые cookie (вход выполняется один раз в LoginActivity).
 */
class ReportRunner(
    private val context: Context,
    private val settings: Settings,
    private val onDone: (Boolean, String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var report: String = ""
    private var finished = false
    private var scrapeTries = 0

    fun start() {
        val wv = WebView(context)
        web = wv
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        // скрытый WebView всё равно нужно «разложить», чтобы страница отрендерилась
        wv.layout(0, 0, 1080, 1920)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val u = url ?: return
                if (u.contains("agent_day.php")) {
                    handler.postDelayed({ pollScrape() }, 1500)
                } else if (u.contains("/im")) {
                    handler.postDelayed({ startSend() }, 2500)
                }
            }
        }

        // общий тайм-аут на всю операцию
        handler.postDelayed({ finish(false, "Тайм-аут: не уложились в 90 секунд") }, 90_000)
        wv.loadUrl(settings.portalUrl)
    }

    private fun eval(js: String, cb: (String) -> Unit) {
        web?.evaluateJavascript(js) { raw -> cb(unquote(raw)) }
    }

    private fun pollScrape() {
        eval(ReportJs.scrapeAndBuild(settings.calls, settings.template)) { result ->
            if (result.isNotEmpty() && !result.startsWith("ERR:")) {
                report = result
                loadChat()
            } else if (++scrapeTries <= 12) {
                handler.postDelayed({ pollScrape() }, 1500)
            } else {
                finish(false, "Не удалось собрать данные с портала. Нужно войти в портал в приложении.")
            }
        }
    }

    private fun loadChat() {
        web?.loadUrl(settings.chatUrl())
    }

    private fun startSend() {
        eval(ReportJs.vkBootstrap()) {
            eval(ReportJs.callFill(report)) { fillRes ->
                if (fillRes == "nofield") {
                    finish(false, "Поле ввода ВК не найдено. Нужно войти в ВК в приложении.")
                    return@eval
                }
                handler.postDelayed({
                    eval(ReportJs.callClick()) {
                        handler.postDelayed({ checkSent(1) }, 1500)
                    }
                }, 600)
            }
        }
    }

    private fun checkSent(attempt: Int) {
        eval(ReportJs.callCheck()) { res ->
            when {
                res == "sent" -> finish(true, "Отчёт отправлен ✓")
                attempt < 2 -> {
                    eval(ReportJs.callClick()) {
                        handler.postDelayed({ checkSent(attempt + 1) }, 1500)
                    }
                }
                else -> finish(false, "Текст вставлен, но ВК не отправил — откройте чат и нажмите «отправить».")
            }
        }
    }

    private fun finish(ok: Boolean, message: String) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        CookieManager.getInstance().flush()
        web?.let {
            it.stopLoading()
            it.destroy()
        }
        web = null
        onDone(ok, message)
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
}
