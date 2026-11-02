package com.pindrop.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps Pindrop's secrets (this phone's key, the laptop code, the paired phones) encrypted on disk.
 * The encryption key lives in the Android Keystore, so it never leaves the phone's secure hardware/area
 * and is not included in backups. Values written by older versions (plain text) are read and upgraded.
 */
object Vault {
    private const val ALIAS = "pindrop_vault"
    private const val SEALED = "v1:"
    private const val PLAIN = "p:"

    private fun key(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            val existing = ks.getKey(ALIAS, null) as? SecretKey
            if (existing != null) return existing
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        } catch (e: Exception) {
            null
        }
    }

    fun isSealed(stored: String): Boolean = stored.startsWith(SEALED) || stored.startsWith(PLAIN)

    /** Encrypts [text]. If the Keystore isn't usable on this phone it is kept as marked plain text instead. */
    fun seal(text: String): String {
        val k = key() ?: return PLAIN + text
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k)
            val ct = c.doFinal(text.toByteArray(Charsets.UTF_8))
            SEALED + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            PLAIN + text
        }
    }

    /** Returns the original text, or null if it can't be read (for example after a Keystore reset). */
    fun open(stored: String?): String? {
        if (stored == null) return null
        if (stored.startsWith(PLAIN)) return stored.substring(PLAIN.length)
        if (!stored.startsWith(SEALED)) return stored // written by an older version: plain text
        return try {
            val raw = Base64.decode(stored.substring(SEALED.length), Base64.NO_WRAP)
            val k = key() ?: return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
