package com.semput.imgsampahsorter

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient

/** Shows a page served by the PC helper (e.g. the live speed test report). */
class ReportActivity : Activity() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.hideFromRecents(this)
        val base = ComfyApi.base(this)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setCookie(base, "isb_token=${ComfyApi.code(this)}; Path=/")
        cm.flush()
        val w = WebView(this)
        w.setBackgroundColor(Color.parseColor("#121418"))
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.webViewClient = WebViewClient()
        setContentView(w)
        web = w
        val path = intent.getStringExtra("path") ?: "/isb/bench/report"
        w.loadUrl(base + path, mapOf("X-ISB-Token" to ComfyApi.code(this)))
    }

    override fun onDestroy() {
        web?.destroy()
        super.onDestroy()
    }
}
