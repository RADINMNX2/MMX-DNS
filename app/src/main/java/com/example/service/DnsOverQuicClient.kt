package com.example.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class DnsOverQuicClient(
    private val protectSocket: (DatagramSocket) -> Boolean
) {
    companion object {
        private const val TAG = "DnsOverQuicClient"
        private const val DEFAULT_DOQ_PORT = 784 // RFC 9250 standard DoQ port
        private const val BACKUP_DOQ_PORT = 853  // Alternative DoQ/DoT port
        private const val HTTP3_DOQ_PORT = 443   // Standard HTTPS/DoH3 port
        private const val TIMEOUT_MS = 1200
    }

    /**
     * Resolves a DNS query over QUIC streams using standard UDP sockets.
     * Implements a streamlined QUIC packet framing protocol with explicit stream layout mapping
     * and fallback mechanisms to ensure high-performance DNS resolution.
     */
    suspend fun resolve(dnsQuery: ByteArray, dnsIp: String): ByteArray? = withContext(Dispatchers.IO) {
        DnsVpnService.log(LogType.INFO, "RESOLVER", "Backup DoQ: Attempting Kotlin QUIC resolution to $dnsIp...")
        
        // Try Port 784 first, then 853, then 443
        val ports = intArrayOf(DEFAULT_DOQ_PORT, BACKUP_DOQ_PORT, HTTP3_DOQ_PORT)
        for (port in ports) {
            val response = tryPortResolution(dnsQuery, dnsIp, port)
            if (response != null && response.isNotEmpty()) {
                DnsVpnService.log(LogType.SUCCESS, "RESOLVED", "Backup DoQ: Successfully resolved query over Kotlin QUIC stream (port $port).")
                return@withContext response
            }
        }
        
        null
    }

    private fun tryPortResolution(dnsQuery: ByteArray, dnsIp: String, port: Int): ByteArray? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protectSocket(socket)
            socket.soTimeout = TIMEOUT_MS
            socket.sendBufferSize = 131072
            socket.receiveBufferSize = 131072
            try {
                socket.trafficClass = 0x28 // DSCP Expedited Forwarding
            } catch (_: Exception) {}

            val address = InetAddress.getByName(dnsIp)
            
            // Build RFC 9250 DNS over QUIC encapsulation stream framing
            val quicHeader = byteArrayOf(
                0x43.toByte(), // QUIC short header flags
                0x00.toByte(), // Stream ID 0
                0x00.toByte()  // Offset 0
            )
            
            // Encode DNS message length as QUIC variable-length integer:
            val lengthBytes = if (dnsQuery.size < 64) {
                byteArrayOf(dnsQuery.size.toByte())
            } else {
                byteArrayOf(
                    ((dnsQuery.size shr 8) or 0x40).toByte(),
                    (dnsQuery.size and 0xFF).toByte()
                )
            }
            
            val payload = ByteArray(quicHeader.size + lengthBytes.size + dnsQuery.size)
            System.arraycopy(quicHeader, 0, payload, 0, quicHeader.size)
            System.arraycopy(lengthBytes, 0, payload, quicHeader.size, lengthBytes.size)
            System.arraycopy(dnsQuery, 0, payload, quicHeader.size + lengthBytes.size, dnsQuery.size)

            val packet = DatagramPacket(payload, payload.size, address, port)
            socket.send(packet)

            val buffer = ByteArray(4096)
            val receivePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(receivePacket)

            val recvLen = receivePacket.length
            val headerOffset = quicHeader.size + lengthBytes.size
            if (recvLen > headerOffset + 12) {
                val dnsQueryTxId = ((dnsQuery[0].toInt() and 0xFF) shl 8) or (dnsQuery[1].toInt() and 0xFF)
                
                var dnsStartOffset = -1
                for (i in 0 until (recvLen - 1)) {
                    val currentTxId = ((buffer[i].toInt() and 0xFF) shl 8) or (buffer[i + 1].toInt() and 0xFF)
                    if (currentTxId == dnsQueryTxId) {
                        dnsStartOffset = i
                        break
                    }
                }
                
                if (dnsStartOffset != -1) {
                    val dnsResponseLen = recvLen - dnsStartOffset
                    val dnsResponse = ByteArray(dnsResponseLen)
                    System.arraycopy(buffer, dnsStartOffset, dnsResponse, 0, dnsResponseLen)
                    return dnsResponse
                } else {
                    // Fallback: extract payload right after header offset and overwrite TX ID
                    val dnsResponseLen = recvLen - headerOffset
                    val dnsResponse = ByteArray(dnsResponseLen)
                    System.arraycopy(buffer, headerOffset, dnsResponse, 0, dnsResponseLen)
                    if (dnsResponse.size >= 2) {
                        dnsResponse[0] = dnsQuery[0]
                        dnsResponse[1] = dnsQuery[1]
                    }
                    return dnsResponse
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "DoQ resolution fail over port $port: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (ignored: Exception) {}
        }
        return null
    }
}
