package com.homebudget.app.data.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/** Why the app opens a connection. */
enum class NetPurpose(val label: String) {
    DRIVE_SYNC("Household sync (Google Drive)"),
    LAPTOP_VIEW("Laptop view (your Wi-Fi)"),
}

data class ConnectionRecord(val at: Long, val purpose: NetPurpose, val destination: String, val bytes: Long, val allowed: Boolean)

class NetworkBlockedException(message: String) : Exception(message)

/**
 * The single gate for every network connection the app makes (enforced by GuardrailTest: only this
 * package and the Drive client may use network classes). Default: everything blocked.
 *
 * - Household sync may connect only to [DRIVE_HOST], and only while shared mode is on.
 * - Laptop view only accepts connections from the local network, and only while its screen is open.
 *
 * Every attempt (allowed or blocked) is recorded in memory for the Connections screen. Records hold
 * only time, purpose, host and size, never content.
 */
object NetworkGuard {
    const val DRIVE_HOST = "www.googleapis.com"
    private const val MAX_RECORDS = 200

    @Volatile var syncEnabled: Boolean = false
    @Volatile var laptopViewEnabled: Boolean = false

    /** Test hook: hosts allowed in addition to [DRIVE_HOST] (e.g. a local fake server). */
    @Volatile var extraSyncHosts: Set<String> = emptySet()

    private val _records = MutableStateFlow<List<ConnectionRecord>>(emptyList())
    val records: StateFlow<List<ConnectionRecord>> = _records.asStateFlow()

    var clock: () -> Long = System::currentTimeMillis

    /** Checks an outgoing request. Throws [NetworkBlockedException] (and records it) if not allowed. */
    fun checkOutgoing(url: String, purpose: NetPurpose) {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host.orEmpty()
        val allowed = when (purpose) {
            NetPurpose.DRIVE_SYNC -> syncEnabled && (
                (uri?.scheme == "https" && host == DRIVE_HOST) || host in extraSyncHosts
            )
            NetPurpose.LAPTOP_VIEW -> false // Laptop view never connects out.
        }
        if (!allowed) {
            record(purpose, host.ifEmpty { "?" }, 0, allowed = false)
            throw NetworkBlockedException("Blocked a connection to ${host.ifEmpty { "an unknown address" }}")
        }
    }

    /** Checks an incoming Laptop-view connection from [address]. Only private (local network) addresses are accepted. */
    fun checkIncoming(address: java.net.InetAddress): Boolean {
        val ok = laptopViewEnabled && (address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress)
        if (!ok) record(NetPurpose.LAPTOP_VIEW, address.hostAddress ?: "?", 0, allowed = false)
        return ok
    }

    fun record(purpose: NetPurpose, destination: String, bytes: Long, allowed: Boolean = true) {
        val r = ConnectionRecord(clock(), purpose, destination, bytes, allowed)
        _records.value = (listOf(r) + _records.value).take(MAX_RECORDS)
    }

    fun clearRecords() {
        _records.value = emptyList()
    }
}
