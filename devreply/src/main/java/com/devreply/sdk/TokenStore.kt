package com.devreply.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the SDK keeps its secrets by name: the install token, the device key, queued deletions. */
internal interface SecretStore {
    fun token(account: String): String?
    fun setToken(token: String, account: String)
    fun deleteToken(account: String)
}

/**
 * This app's key on this device (spec 03, same device after logout, 0.5.0): 256 random bits, base64url, made
 * once and kept in the encrypted store. Logout and deleteUser never remove it (they forget the install's
 * entries only); reinstalling the app clears it. Sent as `device_key` with every install registration, so
 * the server can give the same account on the same device its conversations back.
 */
internal object DeviceKey {
    /** Its entry in the store: one per app, whatever the key or server. */
    const val ENTRY = "device_key"

    fun generate(random: java.security.SecureRandom = java.security.SecureRandom()): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    /** A usable key: 32 bytes of base64url without padding (43 characters). */
    fun isValid(key: String): Boolean =
        key.length == 43 && runCatching { java.util.Base64.getUrlDecoder().decode(key).size == 32 }.getOrDefault(false)

    /** The stored key, or a new one saved now. */
    fun get(store: SecretStore): String {
        store.token(ENTRY)?.takeIf(::isValid)?.let { return it }
        val key = generate()
        store.setToken(key, ENTRY)
        return key
    }
}

/**
 * The install token, encrypted with an Android Keystore key that never leaves this device (spec 03,
 * the Keychain on iOS). A backup restored elsewhere can't decrypt it, so that device registers anew.
 * One entry per API host + public key.
 */
internal class TokenStore(context: Context) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences("com.devreply.sdk.install", Context.MODE_PRIVATE)

    override fun token(account: String): String? {
        val stored = prefs.getString(account, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun setToken(token: String, account: String) {
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(account, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        }
    }

    override fun deleteToken(account: String) {
        prefs.edit().remove(account).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "com.devreply.sdk.install"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
