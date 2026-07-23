package ru.topmts.report

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Прогоняет весь цикл в WebView:
 *   портал → сбор отчёта → чат ВК → вставка и отправка.
 * Использует сохранённые cookie (вход выполняется один раз в LoginActivity).
 * WebView прикрепляется к [container] (видимой Activity) — так страница
 * гарантированно рендерится и вызывает колбэки; без окна фон «висит».
 */
class ReportRunner(
    private val context: Context,
    private val settings: Settings,
    private val container: ViewGroup?,
    private val onProgress: (String) -> Unit,
    private val onDone: (Boolean, String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var report: String = ""
    private var finished = false
    private var scrapeTries = 0
    private var btnInfo = "кнопка отправки не проверялась"

    fun start() {
        val wv = WebView(context)
        web = wv
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        // прикрепляем WebView к окну — иначе страница не рендерится
        if (container != null) {
            container.addView(
                wv,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            wv.layout(0, 0, 1080, 1920)
        }

        onProgress("Открываю портал…")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val u = url ?: return
                if (u.contains("agent_day.php")) {
                    onProgress("Собираю данные с портала…")
                    handler.postDelayed({ pollScrape() }, 1500)
                }
                // страница ВК (мессенджер) — «живой» SPA, событие загрузки у
                // него ненадёжно, поэтому этап ВК запускается опросом из loadChat()
            }
        }

        // общий тайм-аут на всю операцию
        handler.postDelayed({ finish(false, "Тайм-аут: не уложились за 2 минуты") }, 120_000)
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
        onProgress("Отчёт собран, открываю чат…")
        web?.loadUrl(settings.chatUrl())
        // не ждём onPageFinished (у SPA ВК ненадёжно) — опрашиваем поле ввода
        handler.postDelayed({ waitComposer(0) }, 3000)
    }

    // Периодически проверяем, появилось ли поле ввода чата, и как только
    // оно есть — вставляем текст и отправляем. Работает независимо от того,
    // сработало ли событие «страница загрузилась».
    private fun waitComposer(attempt: Int) {
        if (finished) return
        eval(ReportJs.vkBootstrap()) {
            eval(ReportJs.callHas()) { has ->
                if (has == "true") {
                    startSend()
                } else if (attempt < 30) {
                    if (attempt == 0) onProgress("Открываю чат ВК…")
                    handler.postDelayed({ waitComposer(attempt + 1) }, 1500)
                } else {
                    finish(false, "Поле ввода ВК не найдено — войдите в ВК в приложении.")
                }
            }
        }
    }

    private fun startSend() {
        onProgress("Отправляю…")
        // 1) фокусируем поле ввода ВК (курсор в конец)
        eval(ReportJs.callFocus()) { focus ->
            if (focus != "ok") {
                finish(false, "Поле ввода ВК не найдено. Нужно войти в ВК в приложении.")
                return@eval
            }
            // 2) «печатаем» отчёт как настоящая клавиатура (через IME WebView) —
            //    так редактор ВК регистрирует ввод и РАЗБЛОКИРУЕТ кнопку отправки.
            //    Синтетическая вставка этого не делает: текст виден, но кнопка заперта.
            val typed = nativeCommitText(report)
            handler.postDelayed({
                eval(ReportJs.callCheck()) { st ->
                    when (st) {
                        // поле не пустое — текст реально напечатан, отправляем
                        "notsent" -> attemptSend(0)
                        // печать не прошла — откатываемся на синтетическую вставку
                        else -> eval(ReportJs.callFill(report)) { fillRes ->
                            if (fillRes == "nofield") {
                                finish(false, "Поле ввода ВК не найдено. Нужно войти в ВК в приложении.")
                            } else {
                                handler.postDelayed({ attemptSend(0) }, 700)
                            }
                        }
                    }
                }
            }, 600)
        }
    }

    // «Печать» текста в WebView через InputConnection.commitText — тот же путь,
    // что и при наборе с экранной клавиатуры (IME → редактор страницы). В отличие
    // от синтетического paste/execCommand, редактор ВК считает это настоящим
    // вводом и разблокирует отправку.
    private fun nativeCommitText(text: String): Boolean {
        val wv = web ?: return false
        wv.requestFocus()
        return try {
            val ic = wv.onCreateInputConnection(EditorInfo()) ?: return false
            ic.beginBatchEdit()
            ic.commitText(text, 1)
            ic.endBatchEdit()
            true
        } catch (e: Exception) {
            false
        }
    }

    // Последовательно пробуем всё более «настоящие» способы отправки,
    // проверяя после каждого, очистилось ли поле:
    //  0 — обычный клик по кнопке из JS (работает на части вёрсток)
    //  1 — НАСТОЯЩИЙ тап пальцем по координатам кнопки (нативный MotionEvent)
    //  2 — НАСТОЯЩЕЕ нажатие Enter (нативный KeyEvent в WebView)
    private fun attemptSend(strategy: Int) {
        if (finished) return
        when (strategy) {
            0 -> eval(ReportJs.callClick()) {
                handler.postDelayed({ verifyOrEscalate(strategy) }, 1500)
            }
            1 -> nativeTapSendButton { handler.postDelayed({ verifyOrEscalate(strategy) }, 1500) }
            2 -> eval(ReportJs.callFocus()) {
                nativeEnter()
                handler.postDelayed({ verifyOrEscalate(strategy) }, 1500)
            }
            else -> finish(
                false,
                "Текст вставлен, но ВК не отправил его ($btnInfo). " +
                    "Проверьте: если вручную в этом чате тоже не шлётся — это блокировка ВК."
            )
        }
    }

    private fun verifyOrEscalate(strategy: Int) {
        eval(ReportJs.callCheck()) { res ->
            if (res == "sent") finish(true, "Отчёт отправлен ✓")
            else attemptSend(strategy + 1)
        }
    }

    // Настоящий тап по кнопке «отправить»: берём её координаты из страницы,
    // переводим CSS-пиксели в пиксели WebView и шлём MotionEvent — для сайта
    // это неотличимо от касания пальцем
    private fun nativeTapSendButton(after: () -> Unit) {
        eval(ReportJs.callRect()) { json ->
            val wv = web
            if (json.isBlank()) { btnInfo = "кнопка отправки не найдена"; after(); return@eval }
            if (wv == null || wv.width == 0) { after(); return@eval }
            try {
                val o = JSONObject(json)
                btnInfo = "кнопка найдена"
                val iw = o.getDouble("iw")
                val scale = if (iw > 0) wv.width / iw else 1.0
                val x = (o.getDouble("x") * scale).toFloat()
                val y = (o.getDouble("y") * scale).toFloat()
                val t = SystemClock.uptimeMillis()
                MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).also {
                    wv.dispatchTouchEvent(it); it.recycle()
                }
                MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0).also {
                    wv.dispatchTouchEvent(it); it.recycle()
                }
            } catch (e: Exception) { /* пойдём к следующей стратегии */ }
            after()
        }
    }

    // Настоящее нажатие Enter в WebView (поле уже сфокусировано через focusInput)
    private fun nativeEnter() {
        val wv = web ?: return
        wv.requestFocus()
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun finish(ok: Boolean, message: String) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        CookieManager.getInstance().flush()
        web?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
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
