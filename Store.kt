package com.grabvr.manager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

/** Settings, the installed-mod list and the mod store. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("grabvr", Context.MODE_PRIVATE)
    val modsDir = File(ctx.filesDir, "mods").apply { mkdirs() }

    var autoReconnect: Boolean
        get() = p.getBoolean("auto", true)
        set(v) { p.edit().putBoolean("auto", v).apply() }

    var storeUrl: String
        get() = p.getString("storeUrl", "") ?: ""
        set(v) { p.edit().putString("storeUrl", v.trim()).apply() }

    var lastPort: Int
        get() = p.getInt("lastPort", 0)
        set(v) { p.edit().putInt("lastPort", v).apply() }

    // ---- installed mods ----
    @Synchronized fun mods(): JSONArray = JSONArray(p.getString("mods", "[]") ?: "[]")

    @Synchronized private fun saveMods(a: JSONArray) { p.edit().putString("mods", a.toString()).apply() }

    @Synchronized fun addMod(id: String, name: String, version: String, desc: String, file: String) {
        val out = JSONArray()
        val a = mods()
        for (i in 0 until a.length()) if (a.getJSONObject(i).getString("id") != id) out.put(a.getJSONObject(i))
        out.put(JSONObject().put("id", id).put("name", name).put("version", version)
            .put("description", desc).put("enabled", false).put("file", file))
        saveMods(out)
    }

    /** on == null flips each mod. */
    @Synchronized fun setEnabled(ids: Collection<String>, on: Boolean?) {
        val a = mods()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getString("id") in ids) o.put("enabled", on ?: !o.optBoolean("enabled"))
        }
        saveMods(a)
    }

    @Synchronized fun remove(id: String) {
        val a = mods()
        val out = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getString("id") == id) {
                val f = o.optString("file")
                if (f.isNotEmpty()) File(modsDir, f).delete()
            } else out.put(o)
        }
        saveMods(out)
    }

    // ---- mod store ----
    // Expected manifest: {"mods":[{"id":"...","name":"...","version":"...","description":"...","url":"file or full URL"}]}
    private fun manifestUrl(): String {
        var u = storeUrl
        require(u.isNotBlank()) { "Set a store URL first" }
        if (!u.endsWith(".json")) u = u.trimEnd('/') + "/manifest.json"
        return u
    }

    private fun manifest(): JSONArray {
        val text = URL(manifestUrl()).openStream().bufferedReader().use { it.readText() }
        val j = try { JSONObject(text) } catch (e: Exception) { error("manifest.json is not valid JSON") }
        return j.optJSONArray("mods") ?: JSONArray()
    }

    fun storeList(query: String): String {
        val a = manifest()
        val out = JSONArray()
        val q = query.trim().lowercase()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val hay = (o.optString("name") + " " + o.optString("description")).lowercase()
            if (q.isEmpty() || hay.contains(q)) out.put(o)
        }
        return out.toString()
    }

    fun storeInstall(id: String) {
        val a = manifest()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getString("id") != id) continue
            val url = URL(URL(manifestUrl()), o.getString("url"))
            val fileName = url.path.substringAfterLast('/').ifEmpty { "$id.bin" }
            url.openStream().use { i2 -> File(modsDir, fileName).outputStream().use { i2.copyTo(it) } }
            addMod(id, o.optString("name", id), o.optString("version"), o.optString("description"), fileName)
            return
        }
        error("Mod not found in the store")
    }
}
