package com.folia.shell

import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * JS ↔ 原生 桥
 *
 * 网页里注入的脚本会调用 window.FoliaNative.* 把状态推上来；
 * 原生指令（播放/暂停/上一首/下一首）通过 evaluateJavascript 调 window.FoliaShell.* 下去。
 */
class MediaBridge(private val webView: WebView) {

    /** 由主线程安全地执行 JS */
    private fun runJs(code: String) {
        webView.post { webView.evaluateJavascript(code, null) }
    }

    // ===== 网页 -> 原生 =====

    @JavascriptInterface
    fun onState(json: String) {
        try {
            android.util.Log.d("FoliaState", json)
            val o = org.json.JSONObject(json)
            val svc = PlaybackService.instance ?: return
            svc.updateState(
                o.optString("title", ""),
                o.optString("artist", ""),
                o.optString("album", ""),
                o.optString("cover", ""),
                o.optLong("duration", 0L),
                o.optLong("position", 0L),
                o.optBoolean("playing", false),
                o.optString("lyric", "")
            )
        } catch (_: Throwable) {
        }
    }

    @JavascriptInterface
    fun log(msg: String) {
        android.util.Log.d("FoliaBridge", msg)
    }

    // ===== 原生 -> 网页 =====

    fun sendCommand(cmd: String) {
        val safe = cmd.replace("'", "")
        runJs("try{window.FoliaShell&&window.FoliaShell.onCommand('$safe')}catch(e){}")
    }
}