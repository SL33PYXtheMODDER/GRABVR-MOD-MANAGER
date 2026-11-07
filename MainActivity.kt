package com.grabvr.manager

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred

class MainActivity : Activity() {
    lateinit var web: WebView
    private var pending: CompletableDeferred<List<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            // The page uses confirm() for destructive actions.
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }
        // Must be registered before the page loads.
        web.addJavascriptInterface(Bridge(this), "GrabvrNative")
        web.loadUrl("file:///android_asset/index.html")
    }

    /** Opens the system file picker and returns the chosen files (empty list if cancelled). */
    suspend fun pick(multiple: Boolean): List<Uri> {
        val d = CompletableDeferred<List<Uri>>()
        pending = d
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }
        runOnUiThread {
            try {
                startActivityForResult(i, 1)
            } catch (e: Exception) {
                d.completeExceptionally(Exception("This headset has no file picker app."))
            }
        }
        return d.await()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        val out = mutableListOf<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            data.clipData?.let { c -> for (k in 0 until c.itemCount) out.add(c.getItemAt(k).uri) }
            if (out.isEmpty()) data.data?.let { out.add(it) }
        }
        pending?.complete(out)
        pending = null
    }

    fun openDevSettings() {
        runOnUiThread {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
