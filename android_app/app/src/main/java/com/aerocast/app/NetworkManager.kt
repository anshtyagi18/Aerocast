package com.aerocast.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.*
import java.security.MessageDigest

/**
 * AeroCast Android Network Manager
 * Coordinates UDP Subnet Broadcast Signaling & High-Speed Raw TCP File Streaming.
 * Uses Kotlin Coroutines for asynchronous socket I/O and WifiManager.MulticastLock
 * to prevent Android OS from discarding local UDP multicast/broadcast frames.
 */
class NetworkManager(private val context: Context) {

    companion object {
        private const val TAG = "AeroCastNet"
        const val UDP_BEACON_PORT = 42424
        const val TCP_TRANSFER_PORT = 42425
        val MAGIC_HEADER = "AEROCAST\u0002".toByteArray(Charsets.UTF_8)
        val MAGIC_HEADER_V1 = "AERO_CAST_V1".toByteArray(Charsets.UTF_8)
        const val CHUNK_SIZE = 64 * 1024

        const val MSG_DISCOVERY_BEACON = "DISCOVERY"
        const val MSG_DISCOVERY_ACK = "DISCOVERY_ACK"
        const val MSG_STAGE_ARMED = "ARMED_DROP"
        const val MSG_DROP_CONFIRMED = "DROP_CONFIRMED"
        const val MSG_FILE_HEADER = "FILE_HEADER"

        const val EVENT_ARMED_DROP = "ARMED_DROP"
        const val EVENT_DROP_CONFIRMED = "DROP_CONFIRMED"
        const val SENDER_MOBILE = "MOBILE"
        const val SENDER_LAPTOP = "LAPTOP"
    }

    interface NetworkListener {
        fun onLaptopArmedDrop(filename: String, size: Long, senderIp: String, tcpPort: Int)
        fun onTransferProgress(filename: String, percent: Float)
        fun onTransferComplete(filename: String, savedFile: File)
        fun onError(error: String)
    }

    private var listener: NetworkListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var udpSocket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var isListening = false

    private var stagedFile: File? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun setListener(l: NetworkListener) {
        this.listener = l
    }

    fun start() {
        if (isListening) return
        isListening = true
        acquireMulticastLock()
        startUdpListener()
        startTcpServer()
    }

    fun stop() {
        isListening = false
        scope.coroutineContext.cancelChildren()
        try { udpSocket?.close() } catch (e: Exception) {}
        try { tcpServerSocket?.close() } catch (e: Exception) {}
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("AeroCastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "MulticastLock successfully acquired: ${multicastLock?.isHeld}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "MulticastLock released cleanly")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MulticastLock: ${e.message}")
        } finally {
            multicastLock = null
        }
    }

    // ================= LOCAL IP UTILITIES =================
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP: ${e.message}")
        }
        return "127.0.0.1"
    }

    fun getBroadcastAddress(): InetAddress {
        val localIp = getLocalIpAddress()
        if (localIp.startsWith("192.168.") || localIp.startsWith("10.") || localIp.startsWith("172.")) {
            val parts = localIp.split(".")
            val bcastStr = "${parts[0]}.${parts[1]}.${parts[2]}.255"
            return InetAddress.getByName(bcastStr)
        }
        return InetAddress.getByName("255.255.255.255")
    }

    // ================= UDP BEACON SIGNALING =================
    private fun startUdpListener() {
        scope.launch {
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_BEACON_PORT))
                    soTimeout = 2000
                }
                val buffer = ByteArray(4096)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive && isListening) {
                    try {
                        udpSocket?.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val json = JSONObject(text)

                        val proto = json.optString("proto", json.optString("proto_v1"))
                        if (proto != "AEROCAST_V2_NATIVE" && proto != "AERO_CAST_V1") continue

                        val sender = json.optString("sender")
                        val event = json.optString("event")

                        // Discovery Beacon -> Reply with Discovery ACK
                        if (event == MSG_DISCOVERY_BEACON || event == "DISCOVERY") {
                            replyDiscoveryAck(packet.address)
                        }

                        // Respond to Laptop ARMED_DROP / STAGE_ARMED beacons
                        if (sender == SENDER_LAPTOP && (event == MSG_STAGE_ARMED || event == EVENT_ARMED_DROP)) {
                            val filename = json.optString("filename", "Unknown File")
                            val size = json.optLong("size", json.optLong("filesize", 0L))
                            val senderIp = json.optString("sender_ip", packet.address.hostAddress ?: "")
                            val tcpPort = json.optInt("tcp_port", TCP_TRANSFER_PORT)

                            mainHandler.post {
                                listener?.onLaptopArmedDrop(filename, size, senderIp, tcpPort)
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        if (!isListening) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP listener setup error: ${e.message}")
            }
        }
    }

    private fun replyDiscoveryAck(target: InetAddress) {
        scope.launch {
            try {
                val socket = DatagramSocket()
                val payload = JSONObject().apply {
                    put("proto", "AEROCAST_V2_NATIVE")
                    put("proto_v1", "AERO_CAST_V1")
                    put("event", MSG_DISCOVERY_ACK)
                    put("sender", SENDER_MOBILE)
                    put("device_name", android.os.Build.MODEL)
                    put("sender_ip", getLocalIpAddress())
                    put("tcp_port", TCP_TRANSFER_PORT)
                }.toString().toByteArray(Charsets.UTF_8)

                val packet = DatagramPacket(payload, payload.size, target, UDP_BEACON_PORT)
                socket.send(packet)
                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reply discovery ACK: ${e.message}")
            }
        }
    }

    fun broadcastArmedDropBeacon(file: File) {
        this.stagedFile = file
        scope.launch {
            try {
                val socket = DatagramSocket().apply { broadcast = true }
                val payload = JSONObject().apply {
                    put("proto", "AEROCAST_V2_NATIVE")
                    put("proto_v1", "AERO_CAST_V1")
                    put("event", MSG_STAGE_ARMED)
                    put("sender", SENDER_MOBILE)
                    put("device_name", android.os.Build.MODEL)
                    put("sender_ip", getLocalIpAddress())
                    put("tcp_port", TCP_TRANSFER_PORT)
                    put("filename", file.name)
                    put("size", file.length())
                    put("filesize", file.length())
                }.toString()

                val bytes = payload.toByteArray(Charsets.UTF_8)
                val targetBcast = getBroadcastAddress()
                val packet = DatagramPacket(bytes, bytes.size, targetBcast, UDP_BEACON_PORT)
                socket.send(packet)

                // Also send to 255.255.255.255 for subnet edge routers
                val universalPacket = DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), UDP_BEACON_PORT)
                socket.send(universalPacket)
                socket.close()

                Log.d(TAG, "Broadcasted Mobile ARMED_DROP beacon for ${file.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast beacon: ${e.message}")
            }
        }
    }

    // ================= TCP SERVER (SERVING MOBILE STAGED FILES) =================
    private fun startTcpServer() {
        scope.launch {
            try {
                tcpServerSocket = ServerSocket(TCP_TRANSFER_PORT).apply {
                    reuseAddress = true
                }
                while (isActive && isListening) {
                    try {
                        val client = tcpServerSocket?.accept() ?: break
                        handleIncomingTcpClient(client)
                    } catch (e: Exception) {
                        if (!isListening) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP Server error: ${e.message}")
            }
        }
    }

    private fun handleIncomingTcpClient(socket: Socket) {
        scope.launch {
            try {
                socket.soTimeout = 20000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                // Verify magic header
                val magic = ByteArray(MAGIC_HEADER.size)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC_HEADER) && !magic.contentEquals(MAGIC_HEADER_V1)) {
                    socket.close()
                    return@launch
                }

                // 4-byte big-endian header length
                val metaLen = input.readInt()
                val metaBytes = ByteArray(metaLen)
                input.readFully(metaBytes)
                val meta = JSONObject(String(metaBytes, Charsets.UTF_8))

                val action = meta.optString("action")
                if (action == "PULL") {
                    val fileToSend = stagedFile
                    if (fileToSend != null && fileToSend.exists()) {
                        // Stream staged file to Laptop
                        output.write(MAGIC_HEADER)
                        val sendMeta = JSONObject().apply {
                            put("type", MSG_FILE_HEADER)
                            put("action", "STREAM")
                            put("filename", fileToSend.name)
                            put("size", fileToSend.length())
                            put("filesize", fileToSend.length())
                        }.toString().toByteArray(Charsets.UTF_8)
                        output.writeInt(sendMeta.size)
                        output.write(sendMeta)

                        val totalSize = fileToSend.length()
                        var sentBytes = 0L
                        val buffer = ByteArray(CHUNK_SIZE)
                        FileInputStream(fileToSend).use { fis ->
                            var read: Int
                            while (fis.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                sentBytes += read
                                if (totalSize > 0) {
                                    val pct = (sentBytes.toFloat() / totalSize.toFloat()) * 100f
                                    mainHandler.post { listener?.onTransferProgress(fileToSend.name, pct) }
                                }
                            }
                        }
                        output.flush()

                        // Read ACK
                        val ack = ByteArray(2)
                        input.readFully(ack)

                        mainHandler.post {
                            listener?.onTransferComplete(fileToSend.name, fileToSend)
                        }
                    }
                } else {
                    // Laptop pushing file directly
                    val filename = meta.optString("filename", "received_${System.currentTimeMillis()}.bin")
                    val totalSize = meta.optLong("size", meta.optLong("filesize", 0L))

                    val aeroCastDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AeroCast")
                    if (!aeroCastDir.exists()) aeroCastDir.mkdirs()
                    val destFile = File(aeroCastDir, filename)

                    var receivedBytes = 0L
                    val buffer = ByteArray(CHUNK_SIZE)
                    FileOutputStream(destFile).use { fos ->
                        while (receivedBytes < totalSize) {
                            val toRead = minOf(CHUNK_SIZE.toLong(), totalSize - receivedBytes).toInt()
                            val count = input.read(buffer, 0, toRead)
                            if (count == -1) break
                            fos.write(buffer, 0, count)
                            receivedBytes += count
                            if (totalSize > 0) {
                                val pct = (receivedBytes.toFloat() / totalSize.toFloat()) * 100f
                                mainHandler.post { listener?.onTransferProgress(filename, pct) }
                            }
                        }
                        fos.flush()
                    }

                    // Send OK
                    output.write("OK".toByteArray(Charsets.UTF_8))
                    output.flush()

                    mainHandler.post {
                        listener?.onTransferComplete(filename, destFile)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP client error: ${e.message}")
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }

    // ================= TCP CLIENT (PULL FILE FROM LAPTOP) =================
    fun pullFileFromLaptop(laptopIp: String, port: Int, filename: String, expectedSize: Long) {
        scope.launch {
            var socket: Socket? = null
            try {
                socket = Socket().apply {
                    connect(InetSocketAddress(laptopIp, port), 10000)
                    soTimeout = 25000
                }
                val output = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())

                // 1. Send magic header
                output.write(MAGIC_HEADER)

                // 2. Send PULL request metadata
                val reqJson = JSONObject().apply {
                    put("action", "PULL")
                    put("filename", filename)
                }.toString().toByteArray(Charsets.UTF_8)
                output.writeInt(reqJson.size)
                output.write(reqJson)
                output.flush()

                // 3. Read Laptop Stream Header
                val magic = ByteArray(MAGIC_HEADER.size)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC_HEADER) && !magic.contentEquals(MAGIC_HEADER_V1)) {
                    throw IOException("Invalid magic header from Laptop")
                }

                val headerLen = input.readInt()
                val headerBytes = ByteArray(headerLen)
                input.readFully(headerBytes)
                val respJson = JSONObject(String(headerBytes, Charsets.UTF_8))

                val totalSize = respJson.optLong("size", respJson.optLong("filesize", expectedSize))

                // 4. Save stream directly to Download/AeroCast/
                val aeroCastDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AeroCast")
                if (!aeroCastDir.exists()) aeroCastDir.mkdirs()
                val destFile = File(aeroCastDir, filename)

                var receivedBytes = 0L
                val buffer = ByteArray(CHUNK_SIZE)
                FileOutputStream(destFile).use { fos ->
                    while (receivedBytes < totalSize) {
                        val toRead = minOf(CHUNK_SIZE.toLong(), totalSize - receivedBytes).toInt()
                        val count = input.read(buffer, 0, toRead)
                        if (count == -1) break
                        fos.write(buffer, 0, count)
                        receivedBytes += count

                        if (totalSize > 0) {
                            val pct = (receivedBytes.toFloat() / totalSize.toFloat()) * 100f
                            mainHandler.post { listener?.onTransferProgress(filename, pct) }
                        }
                    }
                    fos.flush()
                }

                // 5. Send OK ack
                output.write("OK".toByteArray(Charsets.UTF_8))
                output.flush()

                mainHandler.post {
                    listener?.onTransferComplete(filename, destFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Pull error: ${e.message}", e)
                mainHandler.post { listener?.onError("Transfer failed: ${e.message}") }
            } finally {
                try { socket?.close() } catch (e: Exception) {}
            }
        }
    }
}
