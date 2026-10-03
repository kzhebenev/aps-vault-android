package ru.devkz.apsvault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Keeps the vault session id between launches, readable only after biometric authentication
 * (contract, section 8: biometric unlock on the device protects the stored sid; the vault
 * itself does not see biometrics).
 *
 * An RSA key pair in the Android Keystore: the public half encrypts without authentication,
 * so the sid is saved right after a password unlock with no extra prompt; the private half
 * requires a strong biometric for every use and is invalidated when a new fingerprint or
 * face is enrolled. Without the biometric option the sid lives in memory only.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun hasStored(base: String): Boolean =
        prefs.getString(K_BASE, null) == base && prefs.getString(K_DATA, null) != null && ks.containsAlias(ALIAS)

    fun save(base: String, sid: String) {
        val pub = ensureKey().let { KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(it.encoded)) }
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, pub, OAEP) }
        val data = c.doFinal(sid.toByteArray())
        prefs.edit().putString(K_BASE, base).putString(K_DATA, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    /** A cipher to hand to BiometricPrompt; null when the key is gone or was invalidated. */
    fun decryptCipher(): Cipher? = try {
        val key = ks.getKey(ALIAS, null) as? PrivateKey ?: return null
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, OAEP) }
    } catch (_: KeyPermanentlyInvalidatedException) {
        clear(); null
    }

    /** Called with the cipher BiometricPrompt has authorised. */
    fun read(cipher: Cipher): String? {
        val data = prefs.getString(K_DATA, null) ?: return null
        return runCatching { String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))) }.getOrNull()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun deleteKey() {
        clear()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
    }

    private fun ensureKey(): java.security.PublicKey {
        ks.getCertificate(ALIAS)?.publicKey?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply { initialize(spec) }
            .generateKeyPair().public
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "aps_vault_session"
        const val TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        const val K_BASE = "base"
        const val K_DATA = "sid"
        // Keystore implements MGF1 with SHA-1 only; both sides must say so explicitly.
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}
