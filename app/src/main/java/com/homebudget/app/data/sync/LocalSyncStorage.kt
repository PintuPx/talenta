package com.homebudget.app.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.homebudget.app.domain.sync.LocalSyncState
import com.homebudget.app.domain.sync.SyncJson
import com.homebudget.app.domain.sync.SyncStateStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Writes [bytes] to [target] atomically: a temporary file is written fully, then renamed. */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.outputStream().use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    if (!tmp.renameTo(target)) {
        target.delete()
        check(tmp.renameTo(target)) { "Could not save sync state" }
    }
}

/** Sync state in a private file (filesDir/sync/state.json). */
class FileSyncStateStore(private val file: File) : SyncStateStore {
    private val lock = Mutex()

    override suspend fun load(): LocalSyncState = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!file.isFile) LocalSyncState()
            else SyncJson.json.decodeFromString(LocalSyncState.serializer(), file.readText())
        }
    }

    override suspend fun save(state: LocalSyncState) = lock.withLock {
        withContext(Dispatchers.IO) { writeAtomically(file, SyncJson.json.encodeToString(LocalSyncState.serializer(), state).toByteArray()) }
    }

    suspend fun delete() = lock.withLock { withContext(Dispatchers.IO) { file.delete(); Unit } }
}

/** Stores secret bytes (the household key) wrapped by a non-exportable Android Keystore key. */
interface KeyVault {
    suspend fun save(secret: ByteArray)
    suspend fun load(): ByteArray?
    suspend fun clear()
}

@Singleton
// REVIEW: protects the household encryption key with the Android Keystore.
class AndroidKeyVault @Inject constructor(@ApplicationContext context: Context) : KeyVault {
    private val file = File(File(context.filesDir, "sync"), "household.key")
    private val alias = "talenta_household_key_wrap"

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override suspend fun save(secret: ByteArray) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        val body = cipher.doFinal(secret)
        writeAtomically(file, byteArrayOf(iv.size.toByte()) + iv + body)
    }

    override suspend fun load(): ByteArray? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        val data = file.readBytes()
        val ivLen = data[0].toInt()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, data, 1, ivLen))
        cipher.doFinal(data, 1 + ivLen, data.size - 1 - ivLen)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
        Unit
    }
}
