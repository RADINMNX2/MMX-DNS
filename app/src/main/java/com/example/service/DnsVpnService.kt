package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null

    companion object {
        private const val TAG = "DnsVpnService"
        private const val NOTIFICATION_ID = 4004
        private const val CHANNEL_ID = "dns_vpn_channel"

        const val ACTION_START = "com.example.service.START"
        const val ACTION_STOP = "com.example.service.STOP"

        const val EXTRA_PRIMARY_DNS = "primary_dns"
        const val EXTRA_SECONDARY_DNS = "secondary_dns"
        const val EXTRA_PROFILE_NAME = "profile_name"

        private val _state = MutableStateFlow(VpnState.DISCONNECTED)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        private val _activeProfileName = MutableStateFlow("None")
        val activeProfileName: StateFlow<String> = _activeProfileName.asStateFlow()

        private val _activePrimaryDns = MutableStateFlow("")
        val activePrimaryDns: StateFlow<String> = _activePrimaryDns.asStateFlow()

        private val _activeSecondaryDns = MutableStateFlow("")
        val activeSecondaryDns: StateFlow<String> = _activeSecondaryDns.asStateFlow()

        private val _totalQueriesResolved = MutableStateFlow(0)
        val totalQueriesResolved: StateFlow<Int> = _totalQueriesResolved.asStateFlow()

        @Volatile
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        } else if (action == ACTION_START) {
            val primary = intent.getStringExtra(EXTRA_PRIMARY_DNS) ?: "8.8.8.8"
            val secondary = intent.getStringExtra(EXTRA_SECONDARY_DNS) ?: "8.8.4.4"
            val name = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "Custom"
            startVpn(primary, secondary, name)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn(primaryDns: String, secondaryDns: String, profileName: String) {
        if (isRunning) {
            stopVpn()
        }

        _state.value = VpnState.CONNECTING
        _activeProfileName.value = profileName
        _activePrimaryDns.value = primaryDns
        _activeSecondaryDns.value = secondaryDns
        _totalQueriesResolved.value = 0

        // Create the notification to run as a foreground service
        val notification = createNotification(profileName, "$primaryDns | $secondaryDns")
        startForeground(NOTIFICATION_ID, notification)

        isRunning = true

        workerThread = thread(start = true, name = "DNS-VPN-Worker") {
            try {
                runVpnTunnel(primaryDns, secondaryDns)
            } catch (e: Exception) {
                Log.e(TAG, "Error in VPN tunnel thread", e)
                _state.value = VpnState.DISCONNECTED
            } finally {
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        if (!isRunning) return
        isRunning = false

        _state.value = VpnState.DISCONNECTED
        _activeProfileName.value = "None"
        _activePrimaryDns.value = ""
        _activeSecondaryDns.value = ""
        _totalQueriesResolved.value = 0

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close VPN interface", e)
        }
        vpnInterface = null

        workerThread?.interrupt()
        workerThread = null

        // Trigger memory-level DNS Cache Flush
        flushSystemDnsCache()

        stopForeground(true)
    }

    private fun runVpnTunnel(primaryDns: String, secondaryDns: String) {
        // Establish the interface
        val builder = Builder()
        builder.setSession("Vibrant DNS Changer")
        builder.setMtu(1500)
        
        // Local dummy IP address for our local routing
        builder.addAddress("10.0.0.2", 32)
        
        // Add our active DNS servers
        builder.addDnsServer(primaryDns)
        if (secondaryDns.isNotEmpty() && secondaryDns != primaryDns) {
            builder.addDnsServer(secondaryDns)
        }

        // Intercept ONLY DNS traffic. To do this cleanly, we add specific routes to our target DNS IPs.
        // This ensures other internet traffic doesn't get sucked into our empty local VPN!
        try {
            builder.addRoute(primaryDns, 32)
            if (secondaryDns.isNotEmpty() && secondaryDns != primaryDns) {
                builder.addRoute(secondaryDns, 32)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add specific DNS routes, falling back to routing DNS subnet", e)
            // Fallback: Add routing for common DNS subnets if routing is rejected
            try {
                builder.addRoute("8.8.8.8", 32)
                builder.addRoute("8.8.4.4", 32)
                builder.addRoute("1.1.1.1", 32)
                builder.addRoute("1.0.0.1", 32)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to establish routes", ex)
            }
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface (null)")
            _state.value = VpnState.DISCONNECTED
            return
        }

        _state.value = VpnState.CONNECTED
        Log.i(TAG, "VPN tunnel established successfully")

        val fileDescriptor = vpnInterface!!.fileDescriptor
        val input = FileInputStream(fileDescriptor)
        val output = FileOutputStream(fileDescriptor)

        // Create protected UDP socket
        val dnsSocket = DatagramSocket()
        protect(dnsSocket)
        dnsSocket.soTimeout = 2000

        val packetBuffer = ByteBuffer.allocate(32767)

        while (isRunning) {
            try {
                packetBuffer.clear()
                val length = input.read(packetBuffer.array())
                if (length <= 0) {
                    Thread.sleep(10)
                    continue
                }

                packetBuffer.limit(length)

                // Parse IP header
                val versionAndIHL = packetBuffer.get(0).toInt() and 0xFF
                val version = versionAndIHL shr 4
                val ihl = versionAndIHL and 0x0F
                val protocol = packetBuffer.get(9).toInt() and 0xFF

                // Check if UDP (17)
                if (version == 4 && protocol == 17) {
                    val ipHeaderLength = ihl * 4

                    // Get Source and Destination IP
                    val srcIpBytes = ByteArray(4)
                    val dstIpBytes = ByteArray(4)

                    packetBuffer.position(12)
                    packetBuffer.get(srcIpBytes)
                    packetBuffer.get(dstIpBytes)

                    val srcIp = InetAddress.getByAddress(srcIpBytes)
                    val dstIp = InetAddress.getByAddress(dstIpBytes)

                    // Get UDP Source and Destination Ports
                    packetBuffer.position(ipHeaderLength)
                    val srcPort = packetBuffer.getShort().toInt() and 0xFFFF
                    val dstPort = packetBuffer.getShort().toInt() and 0xFFFF
                    val udpLength = packetBuffer.getShort().toInt() and 0xFFFF
                    packetBuffer.getShort() // Checksum

                    // Check if DNS Query (Destination Port is 53)
                    if (dstPort == 53) {
                        val dnsPayloadLength = udpLength - 8
                        if (dnsPayloadLength > 0) {
                            val dnsQuery = ByteArray(dnsPayloadLength)
                            packetBuffer.position(ipHeaderLength + 8)
                            packetBuffer.get(dnsQuery)

                            // Route query to active DNS servers
                            var success = false
                            val dnsServersToTry = mutableListOf<String>()
                            dnsServersToTry.add(primaryDns)
                            if (secondaryDns.isNotEmpty() && secondaryDns != primaryDns) {
                                dnsServersToTry.add(secondaryDns)
                            }

                            for (dnsIp in dnsServersToTry) {
                                try {
                                    val forwardPacket = DatagramPacket(
                                        dnsQuery,
                                        dnsQuery.size,
                                        InetAddress.getByName(dnsIp),
                                        53
                                    )
                                    dnsSocket.send(forwardPacket)

                                    val responseBuffer = ByteArray(4096)
                                    val receivePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                                    dnsSocket.receive(receivePacket)

                                    val responseLength = receivePacket.length

                                    // Build reply IP and UDP packet
                                    val responseIpHeaderLength = 20
                                    val responsePacketSize = responseIpHeaderLength + 8 + responseLength
                                    val responseBufferFull = ByteBuffer.allocate(responsePacketSize)

                                    // IP Header Setup
                                    responseBufferFull.put(0, 0x45.toByte()) // IPv4, IHL = 5 (20 bytes)
                                    responseBufferFull.put(1, 0.toByte()) // TOS
                                    responseBufferFull.putShort(2, responsePacketSize.toShort()) // Packet Length
                                    responseBufferFull.putShort(4, 0.toShort()) // Ident
                                    responseBufferFull.putShort(6, 0x4000.toShort()) // Flags (DF)
                                    responseBufferFull.put(8, 64.toByte()) // TTL
                                    responseBufferFull.put(9, 17.toByte()) // UDP Protocol
                                    responseBufferFull.putShort(10, 0.toShort()) // IP Checksum (will fill later)

                                    // Swap IPs (Destination becomes Source, Source becomes Destination)
                                    responseBufferFull.position(12)
                                    responseBufferFull.put(dstIpBytes) // Source IP (the original DNS server queried)
                                    responseBufferFull.put(srcIpBytes) // Destination IP (the requesting client)

                                    // UDP Header Setup
                                    responseBufferFull.position(20)
                                    responseBufferFull.putShort(dstPort.toShort()) // Source Port (53)
                                    responseBufferFull.putShort(srcPort.toShort()) // Destination Port
                                    responseBufferFull.putShort((8 + responseLength).toShort()) // Length
                                    responseBufferFull.putShort(0.toShort()) // No checksum (0 is valid in UDP)

                                    // DNS Payload
                                    responseBufferFull.position(28)
                                    responseBufferFull.put(responseBuffer, 0, responseLength)

                                    // Calculate IP checksum
                                    val ipChecksum = calculateChecksum(responseBufferFull.array(), 0, 20)
                                    responseBufferFull.putShort(10, ipChecksum)

                                    // Write response packet back to VPN tunnel
                                    output.write(responseBufferFull.array(), 0, responsePacketSize)
                                    _totalQueriesResolved.value++
                                    success = true
                                    break // Resolved successfully!
                                } catch (e: Exception) {
                                    Log.w(TAG, "DNS resolution timed out or failed on $dnsIp, trying next...", e)
                                }
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "VPN loop interrupted")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error processing VPN packet", e)
            }
        }
    }

    private fun calculateChecksum(buf: ByteArray, offset: Int, length: Int): Short {
        var sum = 0
        var i = offset
        var len = length
        while (len > 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
            len -= 2
        }
        if (len > 0) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv()).toShort()
    }

    private fun flushSystemDnsCache() {
        Log.i(TAG, "Flushing InetAddress system cache programmatically...")
        try {
            // JVM level Cache Flush using Reflection on InetAddress addressCache
            val addressCacheField = InetAddress::class.java.getDeclaredField("addressCache")
            addressCacheField.isAccessible = true
            val addressCache = addressCacheField.get(null)
            val clearMethod = addressCache.javaClass.getDeclaredMethod("clear")
            clearMethod.isAccessible = true
            clearMethod.invoke(addressCache)
            Log.i(TAG, "InetAddress addressCache cleared successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush InetAddress JVM cache", e)
        }
    }

    private fun createNotification(profileName: String, dnsDetails: String): Notification {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("DNS Changer Connected")
            .setContentText("Profile: $profileName ($dnsDetails)")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "DNS Changer VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
