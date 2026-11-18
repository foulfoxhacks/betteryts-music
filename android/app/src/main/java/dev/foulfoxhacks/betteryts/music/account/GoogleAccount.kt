package dev.foulfoxhacks.betteryts.music.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.foulfoxhacks.betteryts.music.core.Core
import dev.foulfoxhacks.betteryts.music.core.CoreException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Strings kept in SharedPreferences, encrypted with an AES key that lives in the Android
 * Keystore (it can't be read out of the device). If the key is gone (backup restored on another
 * phone), values just read as null and the user connects again.
 */
private class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("google_account", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit { remove(name) }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray())
        prefs.edit { putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    fun get(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12))
        }.getOrNull()
    }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val ALIAS = "betteryts_google"
    }
}

/**
 * Optional Google sign-in, used only to import the user's YouTube library (read-only).
 *
 * Sign-in runs in the browser through the BetterYTS Worker, which holds the OAuth client secret,
 * so it doesn't need Google Play services: it works the same on stock Android, microG and
 * de-Googled phones. PKCE: the code verifier never leaves this phone, so an intercepted
 * redirect is useless to anyone else.
 */
class GoogleAccount(context: Context) {
    private val store = SecureStore(context)
    private var accessToken: String? = null
    private var expiresAt = 0L

    val isConnected: Boolean get() = store.get(REFRESH) != null
    val channel: String? get() = store.get(CHANNEL)

    /** Step 1: the browser intent that starts sign-in. */
    fun signInIntent(): Intent {
        val state = random(32)
        val verifier = random(64)
        store.put(PENDING_STATE, state)
        store.put(PENDING_VERIFIER, verifier)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val uri = Uri.parse("$BASE/auth/google/start").buildUpon()
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .build()
        return Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    }

    /** Step 3: the browser came back with betterytsmusic://oauth?code=…&state=…. */
    suspend fun complete(uri: Uri) {
        val expected = store.get(PENDING_STATE)
        val verifier = store.get(PENDING_VERIFIER)
        store.put(PENDING_STATE, null)
        store.put(PENDING_VERIFIER, null)
        uri.getQueryParameter("error")?.let {
            throw CoreException(if (it == "access_denied") "Sign-in cancelled" else "Google sign-in failed ($it)")
        }
        val state = uri.getQueryParameter("state")
        if (expected == null || verifier == null || state != expected) throw CoreException("That sign-in link has expired. Try connecting again.")
        val code = uri.getQueryParameter("code") ?: throw CoreException("Google sign-in failed")
        val tokens = Core.googleExchange(code, verifier)
        val refresh = tokens.refreshToken ?: throw CoreException("Google didn't allow offline access. Remove BetterYTS Music from your Google account's connected apps and try again.")
        store.put(REFRESH, refresh)
        accessToken = tokens.accessToken
        expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        store.put(CHANNEL, runCatching { Core.googleProfile(tokens.accessToken) }.getOrNull())
    }

    private suspend fun validAccessToken(): String {
        accessToken?.let { if (System.currentTimeMillis() < expiresAt - 60_000) return it }
        val refresh = store.get(REFRESH) ?: throw CoreException("Connect your YouTube account first")
        val tokens = try {
            Core.googleRefresh(refresh)
        } catch (e: CoreException) {
            if (e.message == Core.GOOGLE_AUTH_EXPIRED) {
                forget()
                throw CoreException("Your Google sign-in expired. Connect again.")
            }
            throw e
        }
        accessToken = tokens.accessToken
        expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        return tokens.accessToken
    }

    /** Runs a call with a fresh access token, refreshing once if Google says it's expired. */
    suspend fun <T> withToken(block: suspend (String) -> T): T = try {
        block(validAccessToken())
    } catch (e: CoreException) {
        if (e.message != Core.GOOGLE_AUTH_EXPIRED) throw e
        accessToken = null
        block(validAccessToken())
    }

    /** Revokes the app's access at Google, then forgets everything here. */
    suspend fun disconnect() {
        val token = store.get(REFRESH) ?: accessToken
        try {
            if (token != null) Core.googleRevoke(token)
        } finally {
            forget()
        }
    }

    private fun forget() {
        accessToken = null
        expiresAt = 0
        store.clear()
    }

    private companion object {
        const val BASE = "https://yts.foulfoxhacks.dev"
        const val REFRESH = "refresh"
        const val CHANNEL = "channel"
        const val PENDING_STATE = "pending_state"
        const val PENDING_VERIFIER = "pending_verifier"

        fun b64url(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        fun random(bytes: Int): String = b64url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
    }
}
