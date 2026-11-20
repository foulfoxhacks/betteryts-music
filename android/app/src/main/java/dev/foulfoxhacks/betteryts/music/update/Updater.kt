package dev.foulfoxhacks.betteryts.music.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.foulfoxhacks.betteryts.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * In-app updates for the sideloaded build (Google Play builds update through the store).
 *
 * 1. Reads https://yts.foulfoxhacks.dev/app/update.json: { packageName, versionCode, versionName,
 *    notes, apk: { url, size, sha256 }, signerSha256 }.
 * 2. If it's newer, downloads the APK (only from yts.foulfoxhacks.dev, over HTTPS) and checks:
 *    the file's SHA-256 and size match the manifest; it's this package with a higher versionCode;
 *    and it's signed by the same certificate as the installed app. Android checks the signature
 *    again when installing, so a tampered or foreign APK can never replace the app.
 * 3. Hands it to PackageInstaller. After the first update the app is its own installer, so on
 *    Android 12+ later updates can install without a prompt; otherwise Android asks once.
 */
class Updater(private val context: Context) {
    data class Available(val versionCode: Long, val versionName: String, val notes: String, val url: String, val size: Long, val sha256: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Ready(val update: Available) : State
        data class Downloading(val update: Available, val done: Long) : State
        data class Downloaded(val update: Available, val file: File) : State
        data class Installing(val update: Available) : State
        data class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    val enabled: Boolean get() = BuildConfig.SELF_UPDATE

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val apkFile get() = File(File(context.cacheDir, "updates").apply { mkdirs() }, "update.apk")

    private fun installed(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES)

    private fun versionCodeOf(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    /** SHA-256 of each signing certificate, hex. Newer keys after rotation are included. */
    private fun signers(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = p.signingInfo ?: return emptySet()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") p.signatures
        } ?: return emptySet()
        return sigs.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    /** Checks for an update; with [auto], at most every 6 hours. Downloads it if found. */
    suspend fun check(auto: Boolean = false) {
        if (!enabled) return
        if (auto && System.currentTimeMillis() - prefs.getLong("checkedAt", 0) < 6 * 3600_000L) {
            restoreDownloaded()
            return
        }
        if (state is State.Downloading || state is State.Installing) return
        state = State.Checking
        state = try {
            val found = withContext(Dispatchers.IO) { fetchManifest() }
            prefs.edit().putLong("checkedAt", System.currentTimeMillis()).apply()
            if (found == null) State.UpToDate else download(found).also { if (it is State.Downloaded) remember(found) }
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            if (auto) State.Idle else State.Failed(e.message ?: "Couldn't check for updates")
        }
    }

    /** A download finished in an earlier run: offer it again if it's still newer and still checks out. */
    private suspend fun restoreDownloaded() {
        val j = prefs.getString("ready", null) ?: return
        val u = runCatching { JSONObject(j).let { Available(it.getLong("versionCode"), it.getString("versionName"), it.optString("notes"), it.getString("url"), it.getLong("size"), it.getString("sha256")) } }.getOrNull() ?: return
        if (u.versionCode <= versionCodeOf(installed()) || apkFile.length() != u.size) {
            forgetDownload()
            return
        }
        val ok = withContext(Dispatchers.IO) { sha256(apkFile) == u.sha256 } && verifyApk(u) == null
        if (ok) {
            Log.i(TAG, "update ${u.versionName} ready (from an earlier download)")
            state = State.Downloaded(u, apkFile)
        } else {
            forgetDownload()
        }
    }

    private fun remember(u: Available) {
        prefs.edit().putString("ready", JSONObject().put("versionCode", u.versionCode).put("versionName", u.versionName).put("notes", u.notes).put("url", u.url).put("size", u.size).put("sha256", u.sha256).toString()).apply()
        Log.i(TAG, "update ${u.versionName} downloaded and verified")
    }

    private fun forgetDownload() {
        prefs.edit().remove("ready").apply()
        apkFile.delete()
    }

    private fun fetchManifest(): Available? {
        val res = http.newCall(Request.Builder().url(MANIFEST).header("Cache-Control", "no-cache").build()).execute()
        val body = res.use { if (!it.isSuccessful) throw IllegalStateException("Update server answered HTTP ${it.code}") else it.body?.string().orEmpty() }
        val m = JSONObject(body)
        require(m.optString("packageName") == context.packageName) { "The update is for a different app" }
        val code = m.optLong("versionCode")
        if (code <= versionCodeOf(installed())) return null
        val signer = m.optString("signerSha256").lowercase().replace(":", "")
        require(signer in signers(installed())) { "The update isn't signed with this app's key" }
        val apk = m.getJSONObject("apk")
        val url = apk.optString("url")
        val u = Uri.parse(url)
        // HTTPS from our own host only (debug builds may use their local test server).
        require((u.scheme == "https" || (BuildConfig.DEBUG && u.scheme == "http")) && u.host == HOST) { "Unexpected download address" }
        val sha = apk.optString("sha256").lowercase()
        require(Regex("^[0-9a-f]{64}$").matches(sha)) { "The update has no valid checksum" }
        val size = apk.optLong("size")
        require(size in 1..MAX_APK_BYTES) { "The update has an unexpected size" }
        return Available(code, m.optString("versionName"), m.optString("notes"), url, size, sha)
    }

    /** Downloads (resuming isn't worth it at this size), hashing as it goes, then verifies. */
    private suspend fun download(u: Available): State {
        // Already downloaded and still valid? Skip straight to install.
        if (apkFile.length() == u.size && withContext(Dispatchers.IO) { sha256(apkFile) } == u.sha256 && verifyApk(u) == null) return State.Downloaded(u, apkFile)
        state = State.Downloading(u, 0)
        val err = withContext(Dispatchers.IO) {
            val tmp = File(apkFile.parentFile, "update.part")
            val digest = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(u.url).build()).execute().use { res ->
                if (!res.isSuccessful) return@withContext "Download failed (HTTP ${res.code})"
                val input = res.body?.byteStream() ?: return@withContext "Download failed"
                var total = 0L
                var lastReport = 0L
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > u.size) return@withContext "The download is bigger than announced"
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        if (total - lastReport > 256 * 1024) {
                            lastReport = total
                            withContext(Dispatchers.Main) { state = State.Downloading(u, total) }
                        }
                    }
                }
                if (total != u.size) return@withContext "The download was incomplete"
            }
            if (hex(digest.digest()) != u.sha256) {
                tmp.delete()
                return@withContext "The download didn't match its checksum, so it was thrown away"
            }
            if (!tmp.renameTo(apkFile)) return@withContext "Couldn't save the update"
            null
        }
        if (err != null) {
            Log.w(TAG, "update download refused: $err")
            return State.Failed(err)
        }
        verifyApk(u)?.let {
            Log.w(TAG, "update refused: $it")
            apkFile.delete()
            return State.Failed(it)
        }
        return State.Downloaded(u, apkFile)
    }

    /** The APK itself: same package, newer version, same signing key. Null if it's fine. */
    private fun verifyApk(u: Available): String? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(apkFile.path, flags) ?: return "The download isn't a valid app"
        if (info.packageName != context.packageName) return "The download is a different app"
        if (versionCodeOf(info) != u.versionCode) return "The download's version doesn't match the announcement"
        val mine = signers(installed())
        if (signers(info).none { it in mine }) return "The download isn't signed with this app's key"
        return null
    }

    /** True if Android lets this app install updates; otherwise opens the setting for it. */
    fun canInstall(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) return true
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return false
    }

    /** Installs a verified download. The app restarts into the new version. */
    suspend fun install() {
        val s = state as? State.Downloaded ?: return
        if (!canInstall()) return
        state = State.Installing(s.update)
        state = try {
            withContext(Dispatchers.IO) {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(s.file.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, s.file.length()).use { out -> s.file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java).setPackage(context.packageName), flags)
                    session.commit(pi.intentSender)
                }
            }
            State.Installing(s.update)
        } catch (e: Exception) {
            Log.w(TAG, "install failed", e)
            State.Failed("Couldn't install: ${e.message}")
        }
    }

    internal fun installFinished(ok: Boolean, message: String?) {
        Log.i(TAG, "install finished: ok=$ok ${message.orEmpty()}")
        if (ok) forgetDownload()
        state = if (ok) State.UpToDate else State.Failed(message ?: "The update didn't install")
    }

    companion object {
        private const val TAG = "BTMUpdater"
        private val HOST = BuildConfig.UPDATE_HOST
        private val MANIFEST = BuildConfig.UPDATE_MANIFEST
        private const val MAX_APK_BYTES = 200L * 1024 * 1024

        /** The one updater, so the install result can reach the screen that started it. */
        @Volatile
        var current: Updater? = null

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        fun sha256(f: File): String {
            val d = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    d.update(buf, 0, n)
                }
            }
            return hex(d.digest())
        }
    }
}

/** PackageInstaller's answer: show the system confirmation if Android needs one, else report. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.current?.installFinished(true, null)
            else -> Updater.current?.installFinished(false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed (status $status)")
        }
    }
}
