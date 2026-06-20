package com.lidesheng.lyricinfo.providers.netease

import android.annotation.SuppressLint
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 EApi 加密。移植自 LyricProvider/yrckit。
 */
internal object NeteaseCrypto {

    private const val E_API_KEY = "e82ckenh8dichen8"
    private const val E_API_FORMAT = "%s-36cd479b6b5-%s-36cd479b6b5-%s"
    private const val E_API_SALT = "nobody%suse%smd5forencrypt"

    fun eApiEncrypt(url: String, jsonData: String): Map<String, String> {
        val modifiedUrl = url.replace("eapi", "api")
        val digest = hexDigest(String.format(E_API_SALT, modifiedUrl, jsonData))
        val text = String.format(E_API_FORMAT, modifiedUrl, jsonData, digest)
        val encrypted = aesEncryptEcb(text, E_API_KEY)
        return mapOf("params" to encrypted.uppercase())
    }

    private fun hexDigest(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    @SuppressLint("GetInstance")
    private fun aesEncryptEcb(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(), "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        return cipher.doFinal(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
