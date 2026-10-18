package com.orcaphone.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted file store: AES-256-GCM with a non-exportable key in the Android Keystore.
 * Used for the Orca Cloud session (refresh token). If the key is lost the data simply reads as null.
 */
class SecureStore(private val file: File) {
    private val alias = "orcaphone_cloud_session"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    fun write(plain: String?) {
        if (plain == null) { file.delete(); return }
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val enc = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(byteArrayOf(c.iv.size.toByte()) + c.iv + enc)
            tmp.renameTo(file)
        }
    }

    @Synchronized
    fun read(): String? = runCatching {
        if (!file.exists()) return null
        val all = file.readBytes()
        val ivLen = all[0].toInt()
        val iv = all.copyOfRange(1, 1 + ivLen)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(all.copyOfRange(1 + ivLen, all.size)), Charsets.UTF_8)
    }.getOrNull()
}
