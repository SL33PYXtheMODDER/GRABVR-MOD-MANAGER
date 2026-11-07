package com.grabvr.manager

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

const val GAME_PACKAGE = "com.slindev.grab_demo"
private const val LEVEL_DIR = "/storage/emulated/0/Android/data/$GAME_PACKAGE/files/levels/user"
private val SAFE_LEVEL = Regex("^[A-Za-z0-9._ \\-()]{1,100}\\.level$")

/** Everything that touches the GRAB game itself: base.apk, launch, stop, levels. */
class Game(
    private val ctx: Context,
    private val adb: Adb,
    private val progress: (String, Int) -> Unit,
    private val pick: suspend (Boolean) -> List<Uri>,
) {
    private val baseDir = File(ctx.filesDir, "base").apply { mkdirs() }
    val baseApk = File(baseDir, "base.apk")

    // ---------- helpers ----------
    fun displayName(uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun copyUri(uri: Uri, dest: File) {
        ctx.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it) } }
    }

    private fun installedInfo(): ApplicationInfo? = try {
        ctx.packageManager.getApplicationInfo(GAME_PACKAGE, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun installed() = installedInfo() != null

    fun baseVersion(): String? =
        if (baseApk.exists()) ctx.packageManager.getPackageArchiveInfo(baseApk.path, 0)?.versionName else null

    private fun copyWithProgress(input: InputStream, dest: File, total: Long, label: String) {
        input.use { i ->
            dest.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                var last = -1
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != last && pct % 5 == 0) { last = pct; progress(label, pct) }
                    }
                }
            }
        }
    }

    private fun finishBase(tmp: File): String {
        val info = ctx.packageManager.getPackageArchiveInfo(tmp.path, 0)
        if (info?.packageName != GAME_PACKAGE) {
            tmp.delete()
            error("That file is not the GRAB apk (expected $GAME_PACKAGE)")
        }
        baseApk.delete()
        if (!tmp.renameTo(baseApk)) error("Could not save base.apk")
        progress("Original game file saved", 100)
        return JSONObject().put("file", "base.apk").put("bytes", baseApk.length())
            .put("version", info?.versionName ?: "").toString()
    }

    // ---------- base.apk (single APK, no OBB) ----------
    /** Copies the installed GRAB apk. The apk is readable by other apps, so no ADB is needed. */
    fun saveOriginal(): String {
        val info = installedInfo() ?: error("GRAB ($GAME_PACKAGE) is not installed on this headset")
        val src = File(info.sourceDir)
        val tmp = File(baseDir, "base.apk.tmp")
        progress("Saving base.apk", 0)
        copyWithProgress(src.inputStream(), tmp, src.length(), "Saving base.apk")
        return finishBase(tmp)
    }

    /** "Manually save original game file": the user picks the .apk. */
    suspend fun chooseBase(): String {
        val uri = pick(false).firstOrNull() ?: error("No file selected")
        val tmp = File(baseDir, "base.apk.tmp")
        progress("Copying file", 0)
        copyUri(uri, tmp)
        return finishBase(tmp)
    }

    /** Reinstalls the saved original. Needs wireless ADB. Uninstalling resets the game's data. */
    fun restoreBase() {
        require(baseApk.exists()) { "No original game file saved. Use Save original game file first." }
        require(adb.isUp()) { "Pair wireless debugging in the Setup tab first." }
        progress("Removing installed game", 10)
        adb.shell("pm uninstall $GAME_PACKAGE")
        progress("Installing original game", 30)
        val out = adb.installApk(baseApk)
        require(out.contains("Success")) { "Install failed: ${out.trim()}" }
        progress("Original game restored", 100)
    }

    // ---------- launch / stop ----------
    fun launch() {
        val pm = ctx.packageManager
        var i: Intent? = pm.getLaunchIntentForPackage(GAME_PACKAGE)
        if (i == null) {
            // Quest VR apps sometimes only declare the Oculus VR category.
            val vr = Intent(Intent.ACTION_MAIN).addCategory("com.oculus.intent.category.VR").setPackage(GAME_PACKAGE)
            val r = pm.queryIntentActivities(vr, 0).firstOrNull()
            if (r != null) i = vr.setClassName(r.activityInfo.packageName, r.activityInfo.name)
        }
        if (i == null) error("GRAB ($GAME_PACKAGE) is not installed on this headset")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun stop() {
        if (adb.isUp()) {
            adb.shell("am force-stop $GAME_PACKAGE")
        } else {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(GAME_PACKAGE)
        }
    }

    // ---------- levels (needs wireless ADB) ----------
    fun listLevels(): String {
        val out = adb.shell("mkdir -p '$LEVEL_DIR'; cd '$LEVEL_DIR' && stat -c '%s|%n' *.level 2>/dev/null")
        val arr = JSONArray()
        out.lineSequence().map { it.trim() }.filter { it.contains('|') }.forEach { line ->
            val k = line.indexOf('|')
            arr.put(JSONObject().put("name", line.substring(k + 1)).put("size", line.substring(0, k).toLongOrNull() ?: 0L))
        }
        return arr.toString()
    }

    suspend fun importLevels(): String {
        val uris = pick(true)
        val imported = JSONArray()
        val failed = JSONArray()
        adb.shell("mkdir -p '$LEVEL_DIR'")
        uris.forEachIndexed { n, uri ->
            val name = displayName(uri) ?: "unknown"
            val tmp = File(ctx.cacheDir, "import_$n.level")
            try {
                require(SAFE_LEVEL.matches(name)) { "Not a valid .level file name" }
                progress("Importing $name", n * 100 / uris.size)
                copyUri(uri, tmp)
                adb.push(tmp, "$LEVEL_DIR/$name")
                imported.put(name)
            } catch (e: Exception) {
                failed.put(JSONObject().put("name", name).put("error", e.message ?: "failed"))
            } finally {
                tmp.delete()
            }
        }
        progress("Import finished", 100)
        return JSONObject().put("imported", imported).put("failed", failed).toString()
    }

    fun removeLevel(name: String) {
        require(SAFE_LEVEL.matches(name)) { "Invalid level name" }
        adb.shell("rm -f '$LEVEL_DIR/$name'")
    }
}
