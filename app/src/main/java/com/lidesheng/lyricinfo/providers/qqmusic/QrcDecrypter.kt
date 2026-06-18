package com.lidesheng.lyricinfo.providers.qqmusic

import java.util.zip.InflaterInputStream

/**
 * QRC lyric decryption. Ported from LyricProvider/qrckit.
 * Decrypts 3DES-encrypted, zlib-compressed QRC lyrics from QQ Music.
 */
internal object QrcDecrypter {
    private val QQ_KEY = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    private val decryptSchedule = Array(3) { Array(16) { ByteArray(6) } }.also {
        DESHelper.tripleDESKeySetup(QQ_KEY, it, DESHelper.DECRYPT)
    }

    /**
     * Decrypt QRC lyric data.
     *
     * @param encrypted Hex-encoded encrypted QRC data
     * @return Decrypted lyric text, or null if decryption fails
     */
    fun decrypt(encrypted: String?): String? {
        if (encrypted.isNullOrBlank()) return null
        if (!isHexString(encrypted)) return encrypted

        return runCatching {
            val encryptedBytes = hexStringToByteArray(encrypted)
            val decryptedData = ByteArray(encryptedBytes.size)

            val temp = ByteArray(8)
            val inputBlock = ByteArray(8)

            for (i in encryptedBytes.indices step 8) {
                val blockSize = if (i + 8 <= encryptedBytes.size) 8 else encryptedBytes.size - i
                System.arraycopy(encryptedBytes, i, inputBlock, 0, blockSize)
                DESHelper.tripleDESCrypt(inputBlock, temp, decryptSchedule)
                System.arraycopy(temp, 0, decryptedData, i, blockSize)
            }

            decompress(decryptedData).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun decompress(data: ByteArray): ByteArray =
        InflaterInputStream(data.inputStream()).use { it.readBytes() }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        require(len % 2 == 0) { "Hex string length must be even" }

        return ByteArray(len / 2).apply {
            for (i in indices) {
                this[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }

    private fun isHexString(input: String): Boolean {
        if (input.isEmpty() || input.length % 2 != 0) return false
        for (char in input) {
            if (char !in '0'..'9' && char !in 'a'..'f' && char !in 'A'..'F') return false
        }
        return true
    }
}
