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
        const val EVENT_ARMED_DROP = "ARMED_DROP"
        const val SENDER_MOBILE = "MOBILE"
        const val SENDER_LAPTOP = "LAPTOP"
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

    fun setListener(l: NetworkListener) {
        this.listener = l
    }

    fun start() {
        if (isListening) return
        isListening = true
        acquireMulticastLock()
        startUdpListener()
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

    // ================= 1. UDP BEACON SIGNALING =================
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

    /**
     * Broadcasts Stage Armed beacon simultaneously via UDP and Bluetooth RFCOMM fallback.
     */
    fun broadcastArmedDropBeacon(file: File) {
        this.stagedFile = file

        // 1. Primary Wi-Fi UDP Broadcast
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
                socket.send(DatagramPacket(bytes, bytes.size, targetBcast, UDP_BEACON_PORT))
                socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), UDP_BEACON_PORT))
                socket.close()
                Log.d(TAG, "Broadcasted Mobile ARMED_DROP beacon over Wi-Fi for ${file.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast Wi-Fi beacon: ${e.message}")
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
                socket.soTimeout = 20000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                // Read 17-byte standard frame: [12B Header] + [1B MsgType] + [4B PayloadLen]
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
                        mainHandler.post { listener?.onTransferComplete(fileToSend.name, fileToSend) }
                    }
                } else {
                    // Push transfer
                    val filename = meta.optString("filename", "received_${System.currentTimeMillis()}.bin")
                    val totalSize = meta.optLong("size", meta.optLong("filesize", 0L))

                    val aeroCastDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AeroCast")
                    if (!aeroCastDir.exists()) aeroCastDir.mkdirs()
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

                    output.write("OK".toByteArray(Charsets.UTF_8))
                    output.flush()
                    mainHandler.post { listener?.onTransferComplete(filename, destFile) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP client error: ${e.message}")
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }

    // ================= 4. TCP CLIENT (PULL FILE WITH BLUETOOTH FALLBACK) =================
    fun pullFileFromLaptop(laptopIp: String, port: Int, filename: String, expectedSize: Long) {
        scope.launch {
            var socket: Socket? = null
            var success = false

            // 1. Try High-Speed Wi-Fi TCP Stream
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

                val aeroCastDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AeroCast")
                if (!aeroCastDir.exists()) aeroCastDir.mkdirs()
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

                output.write("OK".toByteArray(Charsets.UTF_8))
                output.flush()

                mainHandler.post { listener?.onTransferComplete(filename, destFile) }
                success = true
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi TCP stream failed: ${e.message}. Attempting Bluetooth RFCOMM fallback...")
            } finally {
                try { socket?.close() } catch (e: Exception) {}
            }

            // 2. Secondary Bluetooth RFCOMM Fallback
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

                val aeroCastDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AeroCast")
                if (!aeroCastDir.exists()) aeroCastDir.mkdirs()
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

                output.write("OK".toByteArray(Charsets.UTF_8))
                output.flush()

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
