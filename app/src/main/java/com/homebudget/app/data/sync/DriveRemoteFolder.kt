package com.homebudget.app.data.sync

import com.homebudget.app.data.net.NetPurpose
import com.homebudget.app.data.net.NetworkBlockedException
import com.homebudget.app.data.net.NetworkGuard
import com.homebudget.app.domain.sync.RemoteFile
import com.homebudget.app.domain.sync.RemoteFolder
import com.homebudget.app.domain.sync.SyncException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * Minimal Google Drive v3 REST client for the shared household folder. Only encrypted files and the
 * plain household.json are ever sent. [token] returns a current OAuth access token.
 * [apiBase]/[uploadBase] can point at a local test server.
 */
class DriveRemoteFolder(
    private val folderId: String,
    private val token: suspend () -> String,
    private val apiBase: String = "https://www.googleapis.com",
    private val uploadBase: String = "https://www.googleapis.com",
) : RemoteFolder {

    @Serializable private data class Owner(val emailAddress: String? = null)
    @Serializable private data class DriveFile(val id: String, val name: String, val modifiedTime: String? = null, val owners: List<Owner> = emptyList())
    @Serializable private data class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)
    @Serializable private data class Created(val id: String)
    @Serializable private data class User(val emailAddress: String = "", val displayName: String = "")
    @Serializable private data class About(val user: User = User())
    @Serializable private data class Permission(val id: String, val emailAddress: String? = null)
    @Serializable private data class PermissionList(val permissions: List<Permission> = emptyList())

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val MAX_DOWNLOAD = 60 * 1024 * 1024

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Creates a folder in the signed-in user's Drive and returns its id. */
        suspend fun createFolder(name: String, token: suspend () -> String, apiBase: String = "https://www.googleapis.com"): String {
            val body = """{"name":${json.encodeToString(kotlinx.serialization.serializer<String>(), name)},"mimeType":"$FOLDER_MIME"}"""
            val res = Http.request("POST", "$apiBase/drive/v3/files?fields=id", token(), body.toByteArray(), "application/json; charset=UTF-8")
            return json.decodeFromString(Created.serializer(), res.toString(Charsets.UTF_8)).id
        }

        /** Email and name of the signed-in Google account. */
        suspend fun whoAmI(token: suspend () -> String, apiBase: String = "https://www.googleapis.com"): Pair<String, String> {
            val res = Http.request("GET", "$apiBase/drive/v3/about?fields=user(emailAddress,displayName)", token())
            val about = json.decodeFromString(About.serializer(), res.toString(Charsets.UTF_8))
            return about.user.emailAddress.lowercase() to about.user.displayName
        }
    }

    override suspend fun list(): List<RemoteFile> {
        val out = mutableListOf<RemoteFile>()
        var page: String? = null
        do {
            val q = enc("'$folderId' in parents and trashed = false")
            val url = "$apiBase/drive/v3/files?q=$q&pageSize=1000&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                "&fields=${enc("nextPageToken,files(id,name,modifiedTime,owners(emailAddress))")}" + (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val list = json.decodeFromString(FileList.serializer(), Http.request("GET", url, token()).toString(Charsets.UTF_8))
            list.files.forEach { f ->
                out += RemoteFile(
                    id = f.id, name = f.name, ownerEmail = f.owners.firstOrNull()?.emailAddress?.lowercase(),
                    modifiedAt = f.modifiedTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0,
                )
            }
            page = list.nextPageToken
        } while (page != null)
        // If two files have the same name (e.g. a retried upload), use the newest.
        return out.groupBy { it.name }.map { (_, same) -> same.maxBy { it.modifiedAt } }
    }

    override suspend fun read(file: RemoteFile): ByteArray =
        Http.request("GET", "$apiBase/drive/v3/files/${enc(file.id)}?alt=media&supportsAllDrives=true", token(), maxBytes = MAX_DOWNLOAD)

    override suspend fun write(name: String, bytes: ByteArray, existing: RemoteFile?): RemoteFile {
        if (existing != null) {
            val currentEmail = whoAmI(token, apiBase).first
            if (existing.ownerEmail.isNullOrBlank() || !existing.ownerEmail.equals(currentEmail, ignoreCase = true)) {
                throw SyncException("This phone cannot overwrite a file created by another family member.")
            }
        }
        val res = if (existing != null) {
            // HttpURLConnection has no PATCH; Google APIs accept the override header.
            Http.request("POST", "$uploadBase/upload/drive/v3/files/${enc(existing.id)}?uploadType=media&supportsAllDrives=true&fields=id", token(), bytes,
                "application/octet-stream", extraHeaders = mapOf("X-HTTP-Method-Override" to "PATCH"))
        } else {
            val boundary = "talenta" + System.nanoTime()
            val meta = """{"name":${json.encodeToString(kotlinx.serialization.serializer<String>(), name)},"parents":["$folderId"]}"""
            val body = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray() +
                bytes + "\r\n--$boundary--\r\n".toByteArray()
            Http.request("POST", "$uploadBase/upload/drive/v3/files?uploadType=multipart&supportsAllDrives=true&fields=id", token(), body, "multipart/related; boundary=$boundary")
        }
        val id = json.decodeFromString(Created.serializer(), res.toString(Charsets.UTF_8)).id
        return RemoteFile(id, name, existing?.ownerEmail, System.currentTimeMillis())
    }

    /** Shares the folder with [email] as an editor (Google emails them a notification). */
    suspend fun share(email: String) {
        val body = """{"type":"user","role":"writer","emailAddress":${json.encodeToString(kotlinx.serialization.serializer<String>(), email)}}"""
        Http.request("POST", "$apiBase/drive/v3/files/${enc(folderId)}/permissions?sendNotificationEmail=true&supportsAllDrives=true", token(), body.toByteArray(), "application/json; charset=UTF-8")
    }

    /** Removes [email]'s access to the folder, if any. */
    suspend fun unshare(email: String) {
        val list = json.decodeFromString(
            PermissionList.serializer(),
            Http.request("GET", "$apiBase/drive/v3/files/${enc(folderId)}/permissions?supportsAllDrives=true&fields=${enc("permissions(id,emailAddress)")}", token()).toString(Charsets.UTF_8),
        )
        list.permissions.filter { it.emailAddress.equals(email, ignoreCase = true) }.forEach { p ->
            Http.request("DELETE", "$apiBase/drive/v3/files/${enc(folderId)}/permissions/${enc(p.id)}?supportsAllDrives=true", token())
        }
    }
}

/** Tiny HTTPS helper. Response bodies of errors are not shown to the user (they could contain file names). */
internal object Http {
    suspend fun request(
        method: String, url: String, token: String, body: ByteArray? = null, contentType: String? = null,
        maxBytes: Int = 8 * 1024 * 1024, extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray = withContext(Dispatchers.IO) {
        try {
            NetworkGuard.checkOutgoing(url, NetPurpose.DRIVE_SYNC)
        } catch (e: NetworkBlockedException) {
            throw SyncException(e.message ?: "Connection blocked")
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                contentType?.let { conn.setRequestProperty("Content-Type", it) }
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.errorStream?.close()
                throw SyncException(
                    when (code) {
                        401 -> "Google sign-in expired. Sign in again."
                        403 -> "Google Drive refused access. Check that the folder is shared with this account."
                        404 -> "The shared folder was not found. It may have been deleted or unshared."
                        429, 503 -> "Google Drive is busy. Try again in a minute."
                        else -> "Google Drive error ($code)"
                    }
                )
            }
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("Response too large")
                }
                NetworkGuard.record(NetPurpose.DRIVE_SYNC, conn.url.host, (body?.size ?: 0).toLong() + out.size())
                out.toByteArray()
            }
        } catch (e: IOException) {
            throw SyncException("No connection to Google Drive. Check the internet connection.")
        } finally {
            conn.disconnect()
        }
    }
}
