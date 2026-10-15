package com.smarttvremote

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.Executors

/** Cầu nối JS <-> Kotlin: thay cho các lệnh gọi fetch('/api/...') của bản Node. */
class Bridge(private val act: Activity, private val web: WebView, private val ctl: Controller) {
    private val pool = Executors.newCachedThreadPool()

    @JavascriptInterface
    fun call(id: Int, method: String, path: String, body: String) {
        pool.execute {
            val res: JSONObject = try {
                ctl.handle(method, path, if (body.isBlank()) JSONObject() else JSONObject(body))
            } catch (e: ApiError) {
                JSONObject().put("ok", false).put("error", e.code).put("message", e.message ?: "")
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", "internal").put("message", "Lỗi không xác định.")
            }
            val js = "window.__nativeResolve($id," + JSONObject.quote(res.toString()) + ")"
            act.runOnUiThread { web.evaluateJavascript(js, null) }
        }
    }
}
