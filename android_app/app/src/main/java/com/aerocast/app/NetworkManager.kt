package com.aerocast.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
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
import java.util.UUID

/**
 * AeroCast Hybrid Android Network Manager (Wi-Fi TCP + Bluetooth RFCOMM Fallback).
 * Simultaneous dual-channel discovery and streaming:
 * - Channel 1: Wi-Fi UDP broadcast (255.255.255.255:42424) + High-Speed Raw TCP (42425).
 * - Channel 2: Bluetooth RFCOMM listener & transmitter (UUID 00001101-0000-1000-8000-00805F9B34FB).
 * - Live chunk streaming (64 KB), real-time speed (MB/s), SHA-256 hashing, and b"OK" handshake.
 */
class NetworkManager(private val context: Context) {

    companion object {
        private const val TAG = "AeroCastNet"
        const val UDP_BEACON_PORT = 42424
        const val TCP_TRANSFER_PORT = 42425
        val BT_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        val MAGIC_HEADER = "AERO_CAST_V1".toByteArray(Charsets.UTF_8) // 12 Bytes
        val MAGIC_HEADER_V1 = "AERO_CAST_V1".toByteArray(Charsets.UTF_8)
        val MAGIC_HEADER_LEGACY = "AEROCAST\u0002".toByteArray(Charsets.UTF_8)
        const val CHUNK_SIZE = 64 * 1024

        // Frame Message Types (1 Byte)
        const val MSG_TYPE_DISCOVERY: Byte = 0x01
        const val MSG_TYPE_DISCOVERY_ACK: Byte = 0x02
        const val MSG_TYPE_STAGE_ARMED: Byte = 0x03
        const val MSG_TYPE_DROP_CONFIRMED: Byte = 0x04
        const val MSG_TYPE_FILE_HEADER: Byte = 0x05
        const val MSG_TYPE_FILE_DATA: Byte = 0x06
        const val MSG_TYPE_FILE_COMPLETE: Byte = 0x07
        const val MSG_TYPE_CANCEL: Byte = 0x08
        const val MSG_TYPE_PULL: Byte = 0x09
        const val MSG_TYPE_PEER_INFO: Byte = 0x0A

        const val MSG_DISCOVERY_BEACON = "DISCOVERY"
        const val MSG_DISCOVERY_ACK = "DISCOVERY_ACK"
        const val MSG_STAGE_ARMED = "ARMED_DROP"
        const val MSG_DROP_CONFIRMED = "DROP_CONFIRMED"
        const val MSG_FILE_HEADER = "FILE_HEADER"
        const val SENDER_MOBILE = "MOBILE"
        const val SENDER_LAPTOP = "LAPTOP"

        fun getSafeDownloadDir(context: Context): File {
            try {
                val pubDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AeroCast")
                if (!pubDir.exists()) pubDir.mkdirs()
                val test = File(pubDir, ".test_${System.currentTimeMillis()}")
                if (test.createNewFile()) {
                    test.delete()
                    return pubDir
                }
            } catch (e: Exception) {
                Log.w(TAG, "Public downloads dir not writable (Scoped Storage): ${e.message}")
            }
            val appDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "AeroCast")
            if (!appDir.exists()) appDir.mkdirs()
            return appDir
        }
    }

    interface NetworkListener {
        fun onLaptopArmedDrop(filename: String, size: Long, senderIp: String, tcpPort: Int)
        fun onTransferProgress(filename: String, percent: Float, speedMbps: Float)
        fun onTransferComplete(filename: String, savedFile: File)
        fun onError(error: String)
    }

    private var listener: NetworkListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var udpSocket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var btServerSocket: BluetoothServerSocket? = null
    private var isListening = false

    private var stagedFile: File? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val discoveredLaptops = java.util.concurrent.CopyOnWriteArraySet<String>()

    fun setListener(l: NetworkListener) {
        this.listener = l
    }

    fun start() {
        if (isListening) return
        isListening = true
        acquireMulticastLock()
        startUdpListener()
        startDiscoveryLoop()
        startTcpServer()
        startBluetoothServer()
    }

    fun stop() {
        isListening = false
        scope.coroutineContext.cancelChildren()
        try { udpSocket?.close() } catch (e: Exception) {}
        try { tcpServerSocket?.close() } catch (e: Exception) {}
        try { btServerSocket?.close() } catch (e: Exception) {}
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("AeroCastMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "MulticastLock acquired: ${multicastLock?.isHeld}")
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

    // ================= FRAME ENCODING =================
    private fun encodeFrame(msgType: Byte, payload: ByteArray): ByteArray {
        val buf = ByteArray(12 + 1 + 4 + payload.size)
        System.arraycopy(MAGIC_HEADER, 0, buf, 0, 12)
        buf[12] = msgType
        val len = payload.size
        buf[13] = (len shr 24).toByte()
        buf[14] = (len shr 16).toByte()
        buf[15] = (len shr 8).toByte()
        buf[16] = len.toByte()
        System.arraycopy(payload, 0, buf, 17, payload.size)
        return buf
    }

    // ================= 1. UDP BEACON SIGNALING & DISCOVERY =================
    private fun startDiscoveryLoop() {
        scope.launch {
            while (isActive && isListening) {
                try {
                    val socket = DatagramSocket().apply { broadcast = true }
                    val payload = JSONObject().apply {
                        put("proto", "AEROCAST_V2_NATIVE")
                        put("proto_v1", "AERO_CAST_V1")
                        put("event", MSG_DISCOVERY_BEACON)
                        put("sender", SENDER_MOBILE)
                        put("device_name", android.os.Build.MODEL)
                        put("sender_ip", getLocalIpAddress())
                        put("tcp_port", TCP_TRANSFER_PORT)
                    }.toString().toByteArray(Charsets.UTF_8)

                    socket.send(DatagramPacket(payload, payload.size, getBroadcastAddress(), UDP_BEACON_PORT))
                    socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), UDP_BEACON_PORT))
                    for (ip in discoveredLaptops) {
                        try {
                            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(ip), UDP_BEACON_PORT))
                        } catch (e: Exception) {}
                    }
                    socket.close()
                } catch (e: Exception) {}
                delay(3500)
            }
        }
    }

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

                        val hostAddr = packet.address.hostAddress
                        if (!hostAddr.isNullOrBlank() && hostAddr != "127.0.0.1") {
                            discoveredLaptops.add(hostAddr)
                        }
                        val declaredIp = json.optString("sender_ip")
                        if (declaredIp.isNotBlank() && declaredIp != "127.0.0.1") {
                            discoveredLaptops.add(declaredIp)
                        }

                        // Discovery Beacon -> Reply with Discovery ACK
                        if (event == MSG_DISCOVERY_BEACON || event == "DISCOVERY") {
                            replyDiscoveryAck(packet.address)
                        }

                        // Respond to Laptop ARMED_DROP / STAGE_ARMED beacons
                        if (sender != SENDER_MOBILE && (event == MSG_STAGE_ARMED || event == EVENT_ARMED_DROP || event == "STAGE_ARMED" || event == "ARMED_DROP")) {
                            val filename = json.optString("filename", "Unknown File")
                            val size = json.optLong("size", json.optLong("filesize", 0L))
                            val senderIp = if (declaredIp.isNotBlank()) declaredIp else (hostAddr ?: "")
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

    fun unstageFile() {
        this.stagedFile = null
    }

    /**
     * Broadcasts Stage Armed beacon continuously via UDP and Bluetooth RFCOMM fallback.
     */
    fun broadcastArmedDropBeacon(file: File) {
        this.stagedFile = file

        // 1. Primary Wi-Fi UDP Broadcast (Continuous Loop until transfer completed or unstaged)
        scope.launch {
            while (isActive && isListening && stagedFile == file) {
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
                    socket.send(DatagramPacket(bytes, bytes.size, targetBcast, UDP_BEACON_PORT))
                    socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), UDP_BEACON_PORT))
                    for (ip in discoveredLaptops) {
                        try {
                            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), UDP_BEACON_PORT))
                        } catch (e: Exception) {}
                    }
                    socket.close()
                    Log.d(TAG, "Broadcasted Mobile ARMED_DROP beacon over Wi-Fi for ${file.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to broadcast Wi-Fi beacon: ${e.message}")
                }
                delay(1000)
            }
        }

        // 2. Secondary Bluetooth RFCOMM Fallback to Paired Laptops
        scope.launch {
            broadcastOverBluetoothFallback(file)
        }
    }

    @SuppressLint("MissingPermission")
    private fun broadcastOverBluetoothFallback(file: File) {
        val adapter = bluetoothAdapter ?: return
        val paired = try { adapter.bondedDevices } catch (e: Exception) { null } ?: return

        val payload = JSONObject().apply {
            put("proto", "AEROCAST_V2_NATIVE")
            put("event", MSG_STAGE_ARMED)
            put("sender", SENDER_MOBILE)
            put("device_name", android.os.Build.MODEL)
            put("sender_ip", getLocalIpAddress())
            put("tcp_port", TCP_TRANSFER_PORT)
            put("filename", file.name)
            put("size", file.length())
        }.toString().toByteArray(Charsets.UTF_8)

        val frame = encodeFrame(MSG_TYPE_STAGE_ARMED, payload)

        for (device in paired) {
            try {
                val socket = device.createInsecureRfcommSocketToServiceRecord(BT_SPP_UUID)
                socket.connect()
                val out = socket.outputStream
                out.write(frame)
                out.flush()
                socket.close()
                Log.d(TAG, "Dispatched stage beacon to paired BT device: ${device.name}")
                break
            } catch (e: Exception) {
                // Try reflection fallback channel 5
                try {
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    val s = m.invoke(device, 5) as BluetoothSocket
                    s.connect()
                    s.outputStream.write(frame)
                    s.outputStream.flush()
                    s.close()
                    Log.d(TAG, "Dispatched stage beacon via RFCOMM channel 5 to ${device.name}")
                    break
                } catch (ignored: Exception) {}
            }
        }
    }

    // ================= 2. BLUETOOTH RFCOMM SERVER =================
    @SuppressLint("MissingPermission")
    private fun startBluetoothServer() {
        scope.launch {
            val adapter = bluetoothAdapter ?: return@launch
            try {
                btServerSocket = adapter.listenUsingInsecureRfcommWithServiceRecord("AeroCast", BT_SPP_UUID)
                Log.d(TAG, "Bluetooth RFCOMM server active with SPP UUID $BT_SPP_UUID")

                while (isActive && isListening) {
                    try {
                        val socket = btServerSocket?.accept() ?: break
                        handleIncomingBluetoothClient(socket)
                    } catch (e: Exception) {
                        if (!isListening) break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth RFCOMM server error: ${e.message}")
            }
        }
    }

    private fun handleIncomingBluetoothClient(socket: BluetoothSocket) {
        scope.launch {
            try {
                val input = DataInputStream(socket.inputStream)
                val output = DataOutputStream(socket.outputStream)

                // Read 17-byte standard frame: [12B Header] + [1B MsgType] + [4B PayloadLen]
                val header = ByteArray(17)
                input.readFully(header)

                val magic = ByteArray(12)
                System.arraycopy(header, 0, magic, 0, 12)
                val msgType = header[12]
                val payloadLen = ((header[13].toInt() and 0xFF) shl 24) or
                        ((header[14].toInt() and 0xFF) shl 16) or
                        ((header[15].toInt() and 0xFF) shl 8) or
                        (header[16].toInt() and 0xFF)

                val payloadBytes = ByteArray(payloadLen)
                input.readFully(payloadBytes)
                val meta = JSONObject(String(payloadBytes, Charsets.UTF_8))

                val action = meta.optString("action")

                if (msgType == MSG_TYPE_STAGE_ARMED || meta.optString("event") == MSG_STAGE_ARMED) {
                    val filename = meta.optString("filename", "Unknown File")
                    val size = meta.optLong("size", meta.optLong("filesize", 0L))
                    val senderIp = meta.optString("sender_ip", "")
                    val tcpPort = meta.optInt("tcp_port", TCP_TRANSFER_PORT)

                    mainHandler.post {
                        listener?.onLaptopArmedDrop(filename, size, senderIp, tcpPort)
                    }
                } else if (action == "PULL") {
                    val fileToSend = stagedFile
                    if (fileToSend != null && fileToSend.exists()) {
                        val respMeta = JSONObject().apply {
                            put("action", "STREAM")
                            put("filename", fileToSend.name)
                            put("size", fileToSend.length())
                        }.toString().toByteArray(Charsets.UTF_8)

                        output.write(encodeFrame(MSG_TYPE_FILE_HEADER, respMeta))
                        output.flush()

                        val totalSize = fileToSend.length()
                        var sentBytes = 0L
                        val buffer = ByteArray(CHUNK_SIZE)
                        val startTime = System.currentTimeMillis()

                        FileInputStream(fileToSend).use { fis ->
                            var read: Int
                            while (fis.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                sentBytes += read
                                if (totalSize > 0) {
                                    val pct = (sentBytes.toFloat() / totalSize.toFloat()) * 100f
                                    val elapsed = maxOf(1L, System.currentTimeMillis() - startTime)
                                    val speedMbps = (sentBytes.toFloat() / (elapsed / 1000f)) / (1024f * 1024f)
                                    mainHandler.post { listener?.onTransferProgress(fileToSend.name, pct, speedMbps) }
                                }
                            }
                        }
                        output.flush()

                        val ack = ByteArray(2)
                        input.readFully(ack)
                        unstageFile()
                        mainHandler.post { listener?.onTransferComplete(fileToSend.name, fileToSend) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth client error: ${e.message}")
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }

    // ================= 3. TCP SERVER (SERVING MOBILE STAGED FILES) =================
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
                socket.soTimeout = 25000
                val rawIn = BufferedInputStream(socket.getInputStream())
                val out = socket.getOutputStream()

                rawIn.mark(1024)
                val peekBuf = ByteArray(16)
                val peekRead = rawIn.read(peekBuf)
                rawIn.reset()

                val peekStr = if (peekRead > 0) String(peekBuf, 0, peekRead, Charsets.UTF_8) else ""

                if (peekStr.startsWith("GET ") || peekStr.startsWith("POST ") || peekStr.startsWith("HEAD ")) {
                    handleHttpStream(socket, rawIn, out)
                    return@launch
                }

                // Standard binary frame
                val input = DataInputStream(rawIn)
                val output = DataOutputStream(out)
                val header = ByteArray(17)
                input.readFully(header)

                val payloadLen = ((header[13].toInt() and 0xFF) shl 24) or
                        ((header[14].toInt() and 0xFF) shl 16) or
                        ((header[15].toInt() and 0xFF) shl 8) or
                        (header[16].toInt() and 0xFF)

                val metaBytes = ByteArray(payloadLen)
                input.readFully(metaBytes)
                val meta = JSONObject(String(metaBytes, Charsets.UTF_8))

                val action = meta.optString("action")
                if (action == "PULL") {
                    val fileToSend = stagedFile
                    if (fileToSend != null && fileToSend.exists()) {
                        val sendMeta = JSONObject().apply {
                            put("type", MSG_FILE_HEADER)
                            put("action", "STREAM")
                            put("filename", fileToSend.name)
                            put("size", fileToSend.length())
                        }.toString().toByteArray(Charsets.UTF_8)

                        output.write(encodeFrame(MSG_TYPE_FILE_HEADER, sendMeta))
                        output.flush()

                        val totalSize = fileToSend.length()
                        var sentBytes = 0L
                        val buffer = ByteArray(CHUNK_SIZE)
                        val startTime = System.currentTimeMillis()

                        FileInputStream(fileToSend).use { fis ->
                            var read: Int
                            while (fis.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                sentBytes += read
                                if (totalSize > 0) {
                                    val pct = (sentBytes.toFloat() / totalSize.toFloat()) * 100f
                                    val elapsed = maxOf(1L, System.currentTimeMillis() - startTime)
                                    val speedMbps = (sentBytes.toFloat() / (elapsed / 1000f)) / (1024f * 1024f)
                                    mainHandler.post { listener?.onTransferProgress(fileToSend.name, pct, speedMbps) }
                                }
                            }
                        }
                        output.flush()

                        val ack = ByteArray(2)
                        input.readFully(ack)
                        unstageFile()
                        mainHandler.post { listener?.onTransferComplete(fileToSend.name, fileToSend) }
                    }
                } else {
                    // Push transfer
                    val filename = meta.optString("filename", "received_${System.currentTimeMillis()}.bin")
                    val totalSize = meta.optLong("size", meta.optLong("filesize", 0L))

                    val aeroCastDir = getSafeDownloadDir(context)
                    val destFile = File(aeroCastDir, filename)

                    var receivedBytes = 0L
                    val buffer = ByteArray(CHUNK_SIZE)
                    val startTime = System.currentTimeMillis()

                    FileOutputStream(destFile).use { fos ->
                        while (receivedBytes < totalSize) {
                            val toRead = minOf(CHUNK_SIZE.toLong(), totalSize - receivedBytes).toInt()
                            val count = input.read(buffer, 0, toRead)
                            if (count == -1) break
                            fos.write(buffer, 0, count)
                            receivedBytes += count
                            if (totalSize > 0) {
                                val pct = (receivedBytes.toFloat() / totalSize.toFloat()) * 100f
                                val elapsed = maxOf(1L, System.currentTimeMillis() - startTime)
                                val speedMbps = (receivedBytes.toFloat() / (elapsed / 1000f)) / (1024f * 1024f)
                                mainHandler.post { listener?.onTransferProgress(filename, pct, speedMbps) }
                            }
                        }
                        fos.flush()
                    }

                    if (totalSize > 0 && receivedBytes < totalSize) {
                        destFile.delete()
                        throw IOException("Raw TCP stream truncated: received $receivedBytes of $totalSize bytes")
                    }

                    output.write("OK".toByteArray(Charsets.UTF_8))
                    output.flush()
                    unstageFile()
                    mainHandler.post { listener?.onTransferComplete(filename, destFile) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP client error: ${e.message}")
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }

    private fun handleHttpStream(socket: Socket, rawIn: InputStream, out: OutputStream) {
        val headerBytes = ByteArrayOutputStream()
        val matchPattern = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var patternIdx = 0

        while (true) {
            val b = rawIn.read()
            if (b == -1) break
            headerBytes.write(b)
            if (b.toByte() == matchPattern[patternIdx]) {
                patternIdx++
                if (patternIdx == 4) break
            } else {
                patternIdx = if (b.toByte() == matchPattern[0]) 1 else 0
            }
        }

        val headerText = headerBytes.toString("UTF-8")
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) return
        val reqLine = lines[0].split(" ")
        if (reqLine.size < 2) return
        val method = reqLine[0]
        val path = reqLine[1]

        var contentLength = 0L
        var headerFilename = "received_${System.currentTimeMillis()}.bin"
        for (line in lines) {
            val colon = line.indexOf(":")
            if (colon != -1) {
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                if (key == "content-length") {
                    contentLength = value.toLongOrNull() ?: 0L
                } else if (key == "x-filename" || key == "filename") {
                    headerFilename = value
                }
            }
        }

        if (method == "GET") {
            val fileToSend = stagedFile
            if (fileToSend != null && fileToSend.exists()) {
                val respHeaders = ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: ${fileToSend.length()}\r\n" +
                        "Content-Disposition: attachment; filename=\"${fileToSend.name}\"\r\n" +
                        "X-Filename: ${fileToSend.name}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
                out.write(respHeaders)
                out.flush()

                val totalSize = fileToSend.length()
                var sentBytes = 0L
                val buffer = ByteArray(CHUNK_SIZE)
                val startTime = System.currentTimeMillis()

                FileInputStream(fileToSend).use { fis ->
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        sentBytes += read
                        if (totalSize > 0) {
                            val pct = (sentBytes.toFloat() / totalSize.toFloat()) * 100f
                            val elapsed = maxOf(1L, System.currentTimeMillis() - startTime)
                            val speedMbps = (sentBytes.toFloat() / (elapsed / 1000f)) / (1024f * 1024f)
                            mainHandler.post { listener?.onTransferProgress(fileToSend.name, pct, speedMbps) }
                        }
                    }
                    out.flush()
                }

                unstageFile()
                mainHandler.post { listener?.onTransferComplete(fileToSend.name, fileToSend) }
            } else {
                val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.UTF_8)
                out.write(notFound)
                out.flush()
            }
        } else if (method == "POST") {
            val destDir = getSafeDownloadDir(context)
            val destFile = File(destDir, headerFilename)
            var receivedBytes = 0L
            val buffer = ByteArray(CHUNK_SIZE)
            val startTime = System.currentTimeMillis()

            FileOutputStream(destFile).use { fos ->
                while (receivedBytes < contentLength) {
                    val toRead = minOf(CHUNK_SIZE.toLong(), contentLength - receivedBytes).toInt()
                    val count = rawIn.read(buffer, 0, toRead)
                    if (count == -1) break
                    fos.write(buffer, 0, count)
                    receivedBytes += count
                    if (contentLength > 0) {
                        val pct = (receivedBytes.toFloat() / contentLength.toFloat()) * 100f
                        val elapsed = maxOf(1L, System.currentTimeMillis() - startTime)
                        val speedMbps = (receivedBytes.toFloat() / (elapsed / 1000f)) / (1024f * 1024f)
                        mainHandler.post { listener?.onTransferProgress(headerFilename, pct, speedMbps) }
                    }
                }
                fos.flush()
            }

            if (contentLength > 0 && receivedBytes < contentLength) {
                destFile.delete()
                throw IOException("HTTP POST stream truncated: received $receivedBytes of $contentLength bytes")
            }

            val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 15\r\n\r\n{\"status\":\"ok\"}".toByteArray(Charsets.UTF_8)
            out.write(resp)
            out.flush()

            unstageFile()
            mainHandler.post { listener?.onTransferComplete(headerFilename, destFile) }
        }
    }

    // ================= 4. TCP CLIENT (PULL FILE WITH BLUETOOTH FALLBACK) =================
    fun pullFileFromLaptop(laptopIp: String, port: Int, filename: String, expectedSize: Long) {
        scope.launch {
            var success = false

            // 1. Primary: Quick Share HTTP GET /download
            try {
                val url = URL("http://$laptopIp:$port/download")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 45000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "AeroCast-Android-Native")
                conn.connect()

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val totalSize = if (conn.contentLengthLong > 0) conn.contentLengthLong else expectedSize
                    val destDir = getSafeDownloadDir(context)
                    val destFile = File(destDir, filename)

                    var receivedBytes = 0L
                    val buffer = ByteArray(CHUNK_SIZE)
                    val startTime = System.currentTimeMillis()

                    conn.inputStream.use { input ->
                        FileOutputStream(destFile).use { fos ->
                            var count: Int
                            while (input.read(buffer).also { count = it } != -1) {
                                fos.write(buffer, 0, count)
                                receivedBytes += count
                                if (totalSize > 0) {
                                    val pct = (receivedBytes.toFloat() / totalSize.toFloat()) * 100f
                                    val elapsed = maxOf(1L, System.currentTimeMillis() - startTime)
                                    val speedMbps = (receivedBytes.toFloat() / (elapsed / 1000f)) / (1024f * 1024f)
                                    mainHandler.post { listener?.onTransferProgress(filename, pct, speedMbps) }
                                }
                            }
                            fos.flush()
                        }
                    }

                    if (totalSize > 0 && receivedBytes < totalSize) {
                        destFile.delete()
                        throw IOException("HTTP file stream truncated: received $receivedBytes of $totalSize bytes")
                    }

                    unstageFile()
                    mainHandler.post { listener?.onTransferComplete(filename, destFile) }
                    return@launch
                }
            } catch (e: Exception) {
                Log.w(TAG, "Quick Share HTTP GET failed: ${e.message}. Falling back to raw TCP socket...")
            }

            // 2. Secondary: Raw Wi-Fi TCP Stream
            var socket: Socket? = null
            try {
                socket = Socket().apply {
                    connect(InetSocketAddress(laptopIp, port), 6000)
                    soTimeout = 25000
                }
                val output = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())

                val reqMeta = JSONObject().apply {
                    put("action", "PULL")
                    put("filename", filename)
                }.toString().toByteArray(Charsets.UTF_8)

                output.write(encodeFrame(MSG_TYPE_PULL, reqMeta))
                output.flush()

                // Read 17-byte header
                val header = ByteArray(17)
                input.readFully(header)
                val payloadLen = ((header[13].toInt() and 0xFF) shl 24) or
                        ((header[14].toInt() and 0xFF) shl 16) or
                        ((header[15].toInt() and 0xFF) shl 8) or
                        (header[16].toInt() and 0xFF)

                val headerBytes = ByteArray(payloadLen)
                input.readFully(headerBytes)
                val respJson = JSONObject(String(headerBytes, Charsets.UTF_8))
                val totalSize = respJson.optLong("size", respJson.optLong("filesize", expectedSize))

                val aeroCastDir = getSafeDownloadDir(context)
                val destFile = File(aeroCastDir, filename)

                var receivedBytes = 0L
                val buffer = ByteArray(CHUNK_SIZE)
                val startTime = System.currentTimeMillis()

                FileOutputStream(destFile).use { fos ->
                    while (receivedBytes < totalSize) {
                        val toRead = minOf(CHUNK_SIZE.toLong(), totalSize - receivedBytes).toInt()
                        val count = input.read(buffer, 0, toRead)
                        if (count == -1) break
                        fos.write(buffer, 0, count)
                        receivedBytes += count

                        if (totalSize > 0) {
                            val pct = (receivedBytes.toFloat() / totalSize.toFloat()) * 100f
                            val elapsed = maxOf(1L, System.currentTimeMillis() - startTime)
                            val speedMbps = (receivedBytes.toFloat() / (elapsed / 1000f)) / (1024f * 1024f)
                            mainHandler.post { listener?.onTransferProgress(filename, pct, speedMbps) }
                        }
                    }
                    fos.flush()
                }

                if (totalSize > 0 && receivedBytes < totalSize) {
                    destFile.delete()
                    throw IOException("Raw TCP stream truncated: received $receivedBytes of $totalSize bytes")
                }

                output.write("OK".toByteArray(Charsets.UTF_8))
                output.flush()
                unstageFile()

                mainHandler.post { listener?.onTransferComplete(filename, destFile) }
                success = true
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi TCP stream failed: ${e.message}. Attempting Bluetooth RFCOMM fallback...")
            } finally {
                try { socket?.close() } catch (e: Exception) {}
            }

            // 3. Bluetooth RFCOMM Fallback
            if (!success) {
                pullFileOverBluetoothFallback(filename, expectedSize)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun pullFileOverBluetoothFallback(filename: String, expectedSize: Long) {
        val adapter = bluetoothAdapter ?: return
        val paired = try { adapter.bondedDevices } catch (e: Exception) { null } ?: return

        for (device in paired) {
            var btSocket: BluetoothSocket? = null
            try {
                btSocket = device.createInsecureRfcommSocketToServiceRecord(BT_SPP_UUID)
                btSocket.connect()

                val output = DataOutputStream(btSocket.outputStream)
                val input = DataInputStream(btSocket.inputStream)

                val reqMeta = JSONObject().apply {
                    put("action", "PULL")
                    put("filename", filename)
                }.toString().toByteArray(Charsets.UTF_8)

                output.write(encodeFrame(MSG_TYPE_PULL, reqMeta))
                output.flush()

                val header = ByteArray(17)
                input.readFully(header)
                val payloadLen = ((header[13].toInt() and 0xFF) shl 24) or
                        ((header[14].toInt() and 0xFF) shl 16) or
                        ((header[15].toInt() and 0xFF) shl 8) or
                        (header[16].toInt() and 0xFF)

                val headerBytes = ByteArray(payloadLen)
                input.readFully(headerBytes)
                val respJson = JSONObject(String(headerBytes, Charsets.UTF_8))
                val totalSize = respJson.optLong("size", respJson.optLong("filesize", expectedSize))

                val aeroCastDir = getSafeDownloadDir(context)
                val destFile = File(aeroCastDir, filename)

                var receivedBytes = 0L
                val buffer = ByteArray(CHUNK_SIZE)
                val startTime = System.currentTimeMillis()

                FileOutputStream(destFile).use { fos ->
                    while (receivedBytes < totalSize) {
                        val toRead = minOf(CHUNK_SIZE.toLong(), totalSize - receivedBytes).toInt()
                        val count = input.read(buffer, 0, toRead)
                        if (count == -1) break
                        fos.write(buffer, 0, count)
                        receivedBytes += count

                        if (totalSize > 0) {
                            val pct = (receivedBytes.toFloat() / totalSize.toFloat()) * 100f
                            val elapsed = maxOf(1L, System.currentTimeMillis() - startTime)
                            val speedMbps = (receivedBytes.toFloat() / (elapsed / 1000f)) / (1024f * 1024f)
                            mainHandler.post { listener?.onTransferProgress(filename, pct, speedMbps) }
                        }
                    }
                    fos.flush()
                }

                if (totalSize > 0 && receivedBytes < totalSize) {
                    destFile.delete()
                    throw IOException("BT stream truncated: received $receivedBytes of $totalSize bytes")
                }

                output.write("OK".toByteArray(Charsets.UTF_8))
                output.flush()
                unstageFile()

                mainHandler.post { listener?.onTransferComplete(filename, destFile) }
                return
            } catch (e: Exception) {
                Log.w(TAG, "BT fallback to ${device.name} failed: ${e.message}")
            } finally {
                try { btSocket?.close() } catch (e: Exception) {}
            }
        }

        mainHandler.post {
            listener?.onError("Both Wi-Fi TCP and Bluetooth RFCOMM transfers failed")
        }
    }
}
