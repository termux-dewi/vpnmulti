package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * State and dynamic configuration holder for the VPN Multiplexer.
 * Exposes thread-safe reactive StateFlows for seamless Jetpack Compose integration.
 */
object VpnState {
    val isRunning = MutableStateFlow(false)
    val logs = MutableStateFlow<List<String>>(emptyList())
    val activeConnections = MutableStateFlow<Map<String, Boolean>>(mapOf("wg0" to false, "ts0" to false))
    
    // Dynamic mapping of App UID -> Virtual Interface Name ("wg0", "ts0", or "direct")
    val routingRules = MutableStateFlow<Map<Int, String>>(emptyMap())
    
    // Static app details (UID -> Name/Package) to support user friendly configuration UI
    val appInfoMap = MutableStateFlow<Map<Int, AppInfo>>(emptyMap())

    data class AppInfo(
        val uid: Int,
        val appName: String,
        val packageName: String
    )

    fun log(message: String) {
        val current = logs.value.toMutableList()
        current.add(0, "[${System.currentTimeMillis() % 100000}] $message")
        if (current.size > 150) {
            current.removeAt(current.size - 1)
        }
        logs.value = current
    }
}

/**
 * Clean, zero-allocation packet parser for extracting IPv4/IPv6 headers and TCP/UDP ports.
 * Highly optimized for low-latency network filtering.
 */
object PacketParser {
    class PacketInfo {
        var protocol: Int = 0
        var srcIp = ByteArray(16)
        var destIp = ByteArray(16)
        var isSrcIpV4 = true
        var srcPort: Int = 0
        var destPort: Int = 0
        var length: Int = 0

        fun reset() {
            protocol = 0
            srcPort = 0
            destPort = 0
            length = 0
            isSrcIpV4 = true
        }
    }

    /**
     * Parses the raw IP packet buffer and populates the zero-allocation outInfo object.
     * Returns true on successful parse of IPv4/IPv6 headers.
     */
    fun parse(packet: ByteArray, len: Int, outInfo: PacketInfo): Boolean {
        if (len < 20) return false
        outInfo.reset()

        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version == 4) {
            outInfo.isSrcIpV4 = true
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (len < ihl) return false

            outInfo.length = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
            outInfo.protocol = packet[9].toInt() and 0xFF

            // Extract IPv4 source address
            System.arraycopy(packet, 12, outInfo.srcIp, 0, 4)
            // Extract IPv4 destination address
            System.arraycopy(packet, 16, outInfo.destIp, 0, 4)

            // Parse ports for TCP (6) and UDP (17)
            if (outInfo.protocol == 6 || outInfo.protocol == 17) {
                if (len >= ihl + 4) {
                    outInfo.srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                    outInfo.destPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                }
            }
            return true
        } else if (version == 6) {
            if (len < 40) return false
            outInfo.isSrcIpV4 = false

            val payloadLength = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
            outInfo.length = payloadLength + 40
            outInfo.protocol = packet[6].toInt() and 0xFF

            // Extract IPv6 source address
            System.arraycopy(packet, 8, outInfo.srcIp, 0, 16)
            // Extract IPv6 destination address
            System.arraycopy(packet, 24, outInfo.destIp, 0, 16)

            // Parse ports for TCP/UDP (assuming direct transport layer after fixed 40-byte header)
            if (outInfo.protocol == 6 || outInfo.protocol == 17) {
                if (len >= 44) {
                    outInfo.srcPort = ((packet[40].toInt() and 0xFF) shl 8) or (packet[41].toInt() and 0xFF)
                    outInfo.destPort = ((packet[42].toInt() and 0xFF) shl 8) or (packet[43].toInt() and 0xFF)
                }
            }
            return true
        }
        return false
    }
}

/**
 * Dynamic Multi-Interface VPN Service.
 * Runs in foreground, multiplexing outbound traffic into Linux Abstract Domain Sockets (UDS)
 * based on App UID, and pumping inbound packets back to tun0.
 */
class MultiVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var vpnThread: Thread? = null

    private val serverSockets = ConcurrentHashMap<String, LocalServerSocket>()
    private val activeSockets = ConcurrentHashMap<String, LocalSocket>()
    private val interfaceThreads = ConcurrentHashMap<String, Thread>()
    private val tunWriteLock = Any()

    companion object {
        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        private const val CHANNEL_ID = "vpn_multiplexer_channel"
        private const val NOTIFICATION_ID = 4483
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            startService()
        } else if (action == ACTION_STOP) {
            stopService()
        }
        return START_NOT_STICKY
    }

    private fun startService() {
        if (isRunning) return
        isRunning = true
        VpnState.log("Starting VPN Multiplexer Service...")

        // Setup notification channel and show foreground notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Multiplexer",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VPN Multiplexer Active")
            .setContentText("No-Root User-Space Routing Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        try {
            // Setup virtual interface (tun0)
            setupVpn()
            
            // Start local abstract namespace UDS servers for wg0 and ts0 interfaces
            startUdsServer("wg0")
            startUdsServer("ts0")

            // Start TUN0 reader thread
            startVpnLoop()

            VpnState.isRunning.value = true
            VpnState.log("VPN Multiplexer successfully started!")
        } catch (e: Exception) {
            VpnState.log("Failed to start VPN Service: ${e.message}")
            stopService()
        }
    }

    private fun setupVpn() {
        val builder = Builder()
        builder.setMtu(1500)
        builder.addAddress("10.0.0.1", 24)
        builder.addRoute("0.0.0.0", 0) // Capture all traffic of targeted apps
        
        // Setup DNS to resolve network queries inside the virtual interface
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")

        // Exclude our own application to prevent routing loops
        builder.addDisallowedApplication(packageName)

        // Include only the apps that are explicitly configured to route to wg0 or ts0
        val targetUids = VpnState.routingRules.value.filter { it.value == "wg0" || it.value == "ts0" }.keys
        val pm = packageManager
        var addedAny = false

        for (uid in targetUids) {
            val packages = pm.getPackagesForUid(uid)
            if (packages != null) {
                for (pkg in packages) {
                    if (pkg != packageName) {
                        try {
                            builder.addAllowedApplication(pkg)
                            addedAny = true
                        } catch (e: Exception) {
                            VpnState.log("Failed to allow app $pkg: ${e.message}")
                        }
                    }
                }
            }
        }

        // Standard fallback to prevent establish() from throwing if no apps are selected
        if (!addedAny) {
            VpnState.log("No applications selected for routing. Adding fallback exemption.")
            // Allow ourselves just to establish the interface without crashing
            try {
                builder.addAllowedApplication("com.example.placeholder.dummy")
            } catch (e: Exception) {}
        }

        vpnInterface = builder.setSession("VPN Multiplexer Interface")
            .establish()

        VpnState.log("TUN0 established successfully at 10.0.0.1/24")
    }

    private fun startUdsServer(interfaceName: String) {
        val serverThread = Thread {
            val serverSocketName = "uds_interface_$interfaceName"
            try {
                // Binding the server socket in Linux abstract namespace (no filesystem permission needed)
                val server = LocalServerSocket(serverSocketName)
                serverSockets[interfaceName] = server
                VpnState.log("UDS Server active on abstract path: \\0$serverSocketName")

                while (isRunning) {
                    try {
                        val clientSocket = server.accept()
                        VpnState.log("Engine connected to $interfaceName!")

                        // Clean up old socket if existing
                        activeSockets[interfaceName]?.let {
                            try { it.close() } catch (e: Exception) {}
                        }
                        activeSockets[interfaceName] = clientSocket

                        val connState = VpnState.activeConnections.value.toMutableMap()
                        connState[interfaceName] = true
                        VpnState.activeConnections.value = connState

                        // Dedicated thread for parsing and forwarding inbound packets from this socket back to tun0
                        Thread {
                            val buffer = ByteArray(32768)
                            val inputStream = clientSocket.inputStream
                            try {
                                while (isRunning) {
                                    val versionByte = inputStream.read()
                                    if (versionByte == -1) break

                                    buffer[0] = versionByte.toByte()
                                    val version = (versionByte ushr 4) and 0x0F

                                    if (version == 4) {
                                        if (!readFully(inputStream, buffer, 1, 19)) break
                                        val totalLength = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
                                        val remaining = totalLength - 20
                                        if (remaining > 0) {
                                            if (!readFully(inputStream, buffer, 20, remaining)) break
                                        }
                                        writeToTun(buffer, totalLength)
                                    } else if (version == 6) {
                                        if (!readFully(inputStream, buffer, 1, 39)) break
                                        val payloadLength = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
                                        val totalLength = payloadLength + 40
                                        val remaining = totalLength - 40
                                        if (remaining > 0) {
                                            if (!readFully(inputStream, buffer, 40, remaining)) break
                                        }
                                        writeToTun(buffer, totalLength)
                                    } else {
                                        VpnState.log("UDS Inbound [$interfaceName]: Invalid IP version: $version")
                                    }
                                }
                            } catch (e: Exception) {
                                VpnState.log("UDS Inbound error on $interfaceName: ${e.message}")
                            } finally {
                                VpnState.log("Engine disconnected from $interfaceName")
                                val updateConn = VpnState.activeConnections.value.toMutableMap()
                                updateConn[interfaceName] = false
                                VpnState.activeConnections.value = updateConn
                                try { clientSocket.close() } catch (e: Exception) {}
                                activeSockets.remove(interfaceName)
                            }
                        }.start()

                    } catch (e: Exception) {
                        if (isRunning) {
                            VpnState.log("Error accepting client on $interfaceName: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                VpnState.log("Server socket error on $interfaceName: ${e.message}")
            }
        }
        serverThread.start()
        interfaceThreads[interfaceName] = serverThread
    }

    private fun readFully(inputStream: InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean {
        var bytesRead = 0
        while (bytesRead < length) {
            val result = inputStream.read(buffer, offset + bytesRead, length - bytesRead)
            if (result == -1) return false
            bytesRead += result
        }
        return true
    }

    private fun writeToTun(packet: ByteArray, length: Int) {
        vpnInterface?.let { pfd ->
            synchronized(tunWriteLock) {
                try {
                    val fos = FileOutputStream(pfd.fileDescriptor)
                    fos.write(packet, 0, length)
                } catch (e: Exception) {
                    VpnState.log("Error writing to TUN: ${e.message}")
                }
            }
        }
    }

    private fun startVpnLoop() {
        vpnThread = Thread {
            val pfd = vpnInterface ?: return@Thread
            val fis = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32768)
            val packetInfo = PacketParser.PacketInfo()
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            VpnState.log("Outbound raw packet capture active on TUN0.")

            try {
                while (isRunning) {
                    val length = fis.read(buffer)
                    if (length <= 0) break

                    // Parse the raw packet headers
                    if (PacketParser.parse(buffer, length, packetInfo)) {
                        var ownerUid = -1
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && 
                            (packetInfo.protocol == 6 || packetInfo.protocol == 17)) {
                            try {
                                val size = if (packetInfo.isSrcIpV4) 4 else 16
                                val srcBytes = ByteArray(size)
                                val destBytes = ByteArray(size)
                                System.arraycopy(packetInfo.srcIp, 0, srcBytes, 0, size)
                                System.arraycopy(packetInfo.destIp, 0, destBytes, 0, size)

                                val srcAddr = InetAddress.getByAddress(srcBytes)
                                val destAddr = InetAddress.getByAddress(destBytes)
                                val localSocket = InetSocketAddress(srcAddr, packetInfo.srcPort)
                                val remoteSocket = InetSocketAddress(destAddr, packetInfo.destPort)

                                ownerUid = connectivityManager.getConnectionOwnerUid(
                                    packetInfo.protocol,
                                    localSocket,
                                    remoteSocket
                                )
                            } catch (e: SecurityException) {
                                // Gracefully capture security constraint on some builds
                            } catch (e: Exception) {
                                // Other resolution issues
                            }
                        }

                        // Determine the user chosen virtual interface
                        val interfaceRoute = VpnState.routingRules.value[ownerUid] ?: "direct"

                        if (interfaceRoute == "wg0" || interfaceRoute == "ts0") {
                            val clientSocket = activeSockets[interfaceRoute]
                            if (clientSocket != null) {
                                try {
                                    val os = clientSocket.outputStream
                                    synchronized(clientSocket) {
                                        os.write(buffer, 0, length)
                                    }
                                } catch (e: Exception) {
                                    VpnState.log("Write failed to $interfaceRoute UDS: ${e.message}")
                                }
                            } else {
                                // No active engine connected, drop packet
                            }
                        } else {
                            // Routed directly by system (handled by addAllowedApplication configuration)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    VpnState.log("TUN0 read loop error: ${e.message}")
                }
            } finally {
                try { fis.close() } catch (e: Exception) {}
            }
        }
        vpnThread?.start()
    }

    private fun stopService() {
        isRunning = false
        VpnState.log("Stopping VPN Multiplexer Service...")

        // Close UDS Client sockets
        for ((_, socket) in activeSockets) {
            try { socket.close() } catch (e: Exception) {}
        }
        activeSockets.clear()

        // Close UDS Server sockets
        for ((_, server) in serverSockets) {
            try { server.close() } catch (e: Exception) {}
        }
        serverSockets.clear()

        // Close TUN0
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null

        // Join threads
        vpnThread?.interrupt()
        vpnThread = null

        for ((_, thread) in interfaceThreads) {
            thread.interrupt()
        }
        interfaceThreads.clear()

        VpnState.isRunning.value = false
        VpnState.activeConnections.value = mapOf("wg0" to false, "ts0" to false)
        VpnState.log("VPN Multiplexer successfully stopped.")
        
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopService()
        super.onDestroy()
    }
}
