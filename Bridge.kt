package com.grabvr.manager

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Receives window.GrabvrNative.call(id, method, argsJson) from the page and answers with window.__gvDone. */
class Bridge(private val act: MainActivity) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = Store(act)
    private val adb = Adb(act, store)
    private val game = Game(act, adb, this::progress) { multiple -> act.pick(multiple) }

    init {
        // Try to reconnect to a previously paired headset in the background.
        scope.launch {
            if (store.autoReconnect && store.lastPort > 0) {
                try { adb.reconnect() } catch (_: Throwable) { }
            }
        }
    }

    @JavascriptInterface
    fun call(id: Int, method: String, argsJson: String) {
        scope.launch {
            try {
                done(id, true, dispatch(method, JSONArray(argsJson)))
            } catch (e: Throwable) {
                done(id, false, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun done(id: Int, ok: Boolean, value: String) {
        act.runOnUiThread {
            act.web.evaluateJavascript("window.__gvDone($id,$ok,${JSONObject.quote(value)})", null)
        }
    }

    private fun progress(msg: String, pct: Int) {
        val o = JSONObject().put("message", msg).put("percent", pct)
        act.runOnUiThread { act.web.evaluateJavascript("window.__gvProgress($o)", null) }
    }

    private fun ids(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    private fun state(): String {
        val s = JSONObject()
        s.put("connected", adb.isUp())
        s.put("autoReconnect", store.autoReconnect)
        s.put("hasBase", game.baseApk.exists())
        s.put("baseVersion", game.baseVersion() ?: JSONObject.NULL)
        s.put("gameInstalled", game.installed())
        s.put("mods", store.mods())
        return s.toString()
    }

    private suspend fun dispatch(m: String, a: JSONArray): String = when (m) {
        "state" -> state()
        "pairingPort" -> "null"
        "pair" -> {
            adb.pair(a.getInt(0), a.get(1).toString(), if (a.isNull(2)) null else a.getInt(2))
            "true"
        }
        "reconnect" -> { adb.reconnect(); "true" }
        "setAutoReconnect" -> { store.autoReconnect = a.getBoolean(0); "true" }
        "openDevSettings" -> { act.openDevSettings(); "true" }

        "launch" -> { game.launch(); "true" }
        "stopGame" -> { game.stop(); "true" }
        "pullBase", "saveOriginal" -> game.saveOriginal()
        "chooseBase" -> game.chooseBase()
        "restoreBase" -> { game.restoreBase(); "true" }

        "toggle" -> { store.setEnabled(listOf(a.getString(0)), null); "true" }
        "setMany" -> { store.setEnabled(ids(a.getJSONArray(0)), a.getBoolean(1)); "true" }
        "remove" -> { store.remove(a.getString(0)); "true" }
        "installLocal" -> {
            val uri = act.pick(false).firstOrNull() ?: error("No file selected")
            val name = game.displayName(uri) ?: "mod_${System.currentTimeMillis()}"
            game.copyUri(uri, File(store.modsDir, name))
            val id = name.substringBeforeLast('.')
            store.addMod(id, id, "", "Local file", name)
            "true"
        }
        "apply" -> error("Apply mods is not built yet: the GRAB mod format still has to be decided.")

        "storeConfig" -> JSONObject().put("url", store.storeUrl).toString()
        "storeSetUrl" -> { store.storeUrl = a.getString(0); "true" }
        "storeList" -> store.storeList(a.optJSONObject(0)?.optString("query") ?: "")
        "storeInstall" -> { store.storeInstall(a.getString(0)); "true" }

        "listLevels" -> game.listLevels()
        "importLevels" -> game.importLevels()
        "removeLevel" -> { game.removeLevel(a.getString(0)); "true" }

        else -> error("Unknown call: $m")
    }
}
