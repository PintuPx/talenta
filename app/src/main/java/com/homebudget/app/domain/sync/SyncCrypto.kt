package com.homebudget.app.domain.sync

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption for files in the shared Drive folder.
 *
 * - Key: PBKDF2-HMAC-SHA256 of the household passphrase with a random 16-byte household salt,
 *   [ITERATIONS] iterations, 256-bit output.
 * - Each file: AES-256-GCM with a fresh random 12-byte IV and a 128-bit tag. The file name is the
 *   associated data, so a file cannot be swapped for another one without detection.
 * - Layout: MAGIC (4 bytes) | version (1) | IV (12) | ciphertext+tag.
 *
 * The passphrase never leaves the phone. Without it, the files cannot be read.
 */
object SyncCrypto {
    const val ITERATIONS = 210_000
    const val SALT_BYTES = 16
    const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val MAGIC = byteArrayOf('T'.code.toByte(), 'L'.code.toByte(), 'N'.code.toByte(), 'T'.code.toByte())
    private const val VERSION: Byte = 1
    private val random = SecureRandom()

    const val MIN_PASSPHRASE = 10

    /** Returns an error message, or null when the passphrase is acceptable. */
    fun checkPassphrase(passphrase: String): String? = when {
        passphrase.length < MIN_PASSPHRASE -> "Use at least $MIN_PASSPHRASE characters"
        passphrase.isBlank() -> "Use at least $MIN_PASSPHRASE characters"
        else -> null
    }

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(salt.size == SALT_BYTES) { "Bad salt" }
        val chars = passphrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    fun encrypt(key: ByteArray, plain: ByteArray, associated: String): ByteArray {
        require(key.size == KEY_BYTES) { "Bad key" }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associated.toByteArray(Charsets.UTF_8))
        val body = cipher.doFinal(plain)
        return MAGIC + byteArrayOf(VERSION) + iv + body
    }

    /** Throws [SyncCryptoException] for a wrong key, a tampered or renamed file, or an unknown format. */
    fun decrypt(key: ByteArray, data: ByteArray, associated: String): ByteArray {
        require(key.size == KEY_BYTES) { "Bad key" }
        val header = MAGIC.size + 1
        if (data.size < header + IV_BYTES + TAG_BITS / 8) throw SyncCryptoException("The file is too short")
        if (!data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw SyncCryptoException("Not a Talenta sync file")
        if (data[MAGIC.size] != VERSION) throw SyncCryptoException("Created by a newer version of the app")
        val iv = data.copyOfRange(header, header + IV_BYTES)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(associated.toByteArray(Charsets.UTF_8))
            cipher.doFinal(data, header + IV_BYTES, data.size - header - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            throw SyncCryptoException("Wrong passphrase or damaged file")
        }
    }
}

class SyncCryptoException(message: String) : Exception(message)
