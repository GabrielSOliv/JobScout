package com.jobscout.core.database

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.jobscout.feature.search.data.FreehireKeyInput
import com.jobscout.feature.search.data.FreehireTokenProvider
import com.jobscout.domain.FreehireCredentialSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Authenticated encryption; the AES key is generated inside Android Keystore. */
object TokenCipher {
    fun encrypt(token: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encoder = Base64.getEncoder()
        return "v1:" + encoder.encodeToString(cipher.iv) + ":" +
            encoder.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)))
    }
    fun decrypt(payload: String, key: SecretKey): String {
        require(payload.length <= 4096)
        val parts = payload.split(':')
        require(parts.size == 3 && parts[0] == "v1")
        val iv = Base64.getDecoder().decode(parts[1])
        val encrypted = Base64.getDecoder().decode(parts[2])
        require(iv.size == 12 && encrypted.size in 17..2064)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return FreehireKeyInput.normalize(cipher.doFinal(encrypted).toString(Charsets.UTF_8))
    }
}

@Singleton
class ApiCredentialStore @Inject constructor(@param:ApplicationContext context: Context) : FreehireTokenProvider, FreehireCredentialSettings {
    private val preferences = context.getSharedPreferences("api_credentials", Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.freehire.aes"
    private val mutex = Mutex()
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Credencial indisponível. Remova e cadastre a chave novamente." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override suspend fun hasStoredToken(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { preferences.contains("freehire") }
    }
    override suspend fun token(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val payload = preferences.getString("freehire", null) ?: return@withLock null
            TokenCipher.decrypt(payload, key(create = false))
        }
    }
    override suspend fun save(value: String): Unit = withContext(Dispatchers.IO) {
        val token = FreehireKeyInput.normalize(value)
        mutex.withLock {
            check(preferences.edit().putString("freehire", TokenCipher.encrypt(token, key(create = true))).commit()) {
                "Não foi possível salvar a credencial."
            }
        }
    }
    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(preferences.edit().remove("freehire").commit()) { "Não foi possível remover a credencial." }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
