package com.m0h31h31.bamburfidreader.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.m0h31h31.bamburfidreader.logging.logDebug
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * 经典蓝牙 SPP (Serial Port Profile) 客户端。
 * 负责与外接 PN532 蓝牙透传模块建立/维护串口连接。
 *
 * 只做连接与字节流收发，不包含 PN532 协议（见 [Pn532Protocol]）。
 */
class BluetoothSppClient(private val context: Context) {

    companion object {
        /** 标准 SPP UUID，绝大多数 HC-05/06/08 等透传模块都使用它。 */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val CONNECT_TIMEOUT_MS = 10_000

        /** Android 12+ 连接已配对设备所需的权限。 */
        fun requiredPermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.Manifest.permission.BLUETOOTH_CONNECT
            } else {
                android.Manifest.permission.BLUETOOTH
            }

        /** 主动扫描周边设备所需的权限（Android 12+ 用 BLUETOOTH_SCAN，低版本用定位）。 */
        fun scanPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(android.Manifest.permission.BLUETOOTH_SCAN)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            }
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    @Volatile
    var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var inputStream: InputStream? = null

    @Volatile
    private var outputStream: OutputStream? = null

    @Volatile
    private var connectedDevice: BluetoothDevice? = null

    @Volatile
    var isDiscovering: Boolean = false
        private set

    private var discoveryCallback: ((BluetoothDevice) -> Unit)? = null
    private var discoveryFinishedCallback: (() -> Unit)? = null
    private val discoveryHandler = Handler(Looper.getMainLooper())
    private val discoveryTimeout = Runnable {
        if (isDiscovering) {
            logDebug("BT discovery timeout guard fired")
            finishDiscovery()
        }
    }

    /** 发现到的设备（含未配对），地址去重。 */
    private val discoveredDevices = LinkedHashMap<String, BluetoothDevice>()

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        val name = safeDeviceName(device).orEmpty()
                        // 纳入所有发现到的设备：HC-05 等模块常无名称，需用地址展示。
                        // 只过滤 deviceType 未知的 BLE 杂散信号，避免列表被刷屏。
                        val isBleOnly = device.type == android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE
                        if (!isBleOnly) {
                            synchronized(discoveredDevices) {
                                discoveredDevices[device.address] = device
                            }
                            discoveryCallback?.invoke(device)
                            logDebug("BT discovered: ${name.ifBlank { "<no-name>" }} (${device.address}) type=${device.type}")
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    logDebug("BT discovery finished, found ${discoveredDevices.size} device(s)")
                    finishDiscovery()
                }
            }
        }
    }
    private var receiverRegistered = false

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val isConnected: Boolean
        get() = state == ConnectionState.CONNECTED && socket?.isConnected == true

    val connectedDeviceName: String?
        get() = safeDeviceName(connectedDevice)

    /** 是否支持蓝牙硬件。 */
    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null

    /** 是否已开启蓝牙。 */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * 已配对设备列表。Android 12+ 需要 BLUETOOTH_CONNECT 权限，
     * 权限由调用方（Activity）在调用前申请。
     */
    fun bondedDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.toList().orEmpty()
        } catch (e: SecurityException) {
            logDebug("Bluetooth bonded devices denied: ${e.message}")
            emptyList()
        }
    }

    /**
     * 主动扫描周边蓝牙设备。权限由调用方在调用前申请。
     * 发现的设备通过 [onFound] 逐个回调（主线程），扫描结束回调 [onFinished]。
     * 返回 false 表示扫描无法启动（蓝牙关闭或权限不足）。
     */
    fun startDiscovery(
        onFound: (BluetoothDevice) -> Unit,
        onFinished: () -> Unit
    ): Boolean {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            logDebug("BT startDiscovery: adapter null or disabled")
            return false
        }
        if (adapter.isDiscovering) {
            logDebug("BT startDiscovery: already discovering, cancel first")
            adapter.cancelDiscovery()
        }

        stopDiscovery()
        synchronized(discoveredDevices) { discoveredDevices.clear() }
        discoveryCallback = onFound
        discoveryFinishedCallback = onFinished
        isDiscovering = true

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try {
            // ACTION_FOUND / ACTION_DISCOVERY_FINISHED 是系统广播，必须 RECEIVER_EXPORTED，
            // 否则 Android 13+ 收不到，UI 会永远停在"正在扫描"。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(discoveryReceiver, filter)
            }
            receiverRegistered = true
        } catch (e: Exception) {
            logDebug("BT register discovery receiver failed: ${e.message}")
            isDiscovering = false
            return false
        }

        // Android 12+ 蓝牙开启也可能需要 BLUETOOTH_CONNECT，这里做防御性兜底。
        val started = try {
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            logDebug("BT startDiscovery denied: ${e.message}")
            false
        }
        if (!started) {
            isDiscovering = false
            unregisterDiscoveryReceiver()
            logDebug("BT startDiscovery returned false")
        } else {
            logDebug("BT discovery started")
            // 兜底：部分机型 ACTION_DISCOVERY_FINISHED 广播丢失，20 秒后强制收尾。
            discoveryHandler.removeCallbacks(discoveryTimeout)
            discoveryHandler.postDelayed(discoveryTimeout, 20_000)
        }
        return started
    }

    /** 扫描结束统一收尾：置位、回调、注销、移除超时守护。 */
    private fun finishDiscovery() {
        if (!isDiscovering) return
        isDiscovering = false
        discoveryHandler.removeCallbacks(discoveryTimeout)
        discoveryFinishedCallback?.invoke()
        discoveryFinishedCallback = null
        unregisterDiscoveryReceiver()
    }

    /** 停止扫描。 */
    fun stopDiscovery() {
        try {
            if (isDiscovering) bluetoothAdapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            logDebug("BT cancelDiscovery denied: ${e.message}")
        }
        discoveryHandler.removeCallbacks(discoveryTimeout)
        isDiscovering = false
        unregisterDiscoveryReceiver()
    }

    private fun unregisterDiscoveryReceiver() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
    }

    /**
     * 按地址查找设备：先在已发现列表里找（未配对的新设备），再回退到已配对列表。
     * 这样 UI 无论展示扫描结果还是配对列表，点击后都能拿到 BluetoothDevice。
     */
    fun findDevice(address: String): BluetoothDevice? {
        synchronized(discoveredDevices) {
            discoveredDevices[address]?.let { return it }
        }
        return bondedDevices().firstOrNull { it.address == address }
    }

    /**
     * 连接指定设备（阻塞，请在后台线程调用）。
     * 使用反射创建 Insecure RFCOMM socket 作为 createRfcommSocketToServiceRecord 的兜底，
     * 兼容部分国产模块/老固件。
     */
    fun connect(device: BluetoothDevice): String? {
        if (isConnected) disconnect()
        state = ConnectionState.CONNECTING
        bluetoothAdapter?.cancelDiscovery()

        var candidate: BluetoothSocket? = null
        var error: String? = null

        // 方式一：标准 SPP
        try {
            candidate = device.createRfcommSocketToServiceRecord(SPP_UUID)
            candidate.connect()
            logDebug("BT SPP connected via service record: ${safeDeviceName(device)}")
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            logDebug("BT SPP service record connect failed: $error")
            try { candidate?.close() } catch (_: Exception) {}
            candidate = null
        }

        // 方式二：反射 createRfcommSocket(channel 1)
        if (candidate == null) {
            try {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                candidate = method.invoke(device, 1) as? BluetoothSocket
                candidate?.connect()
                logDebug("BT SPP connected via reflection channel 1: ${safeDeviceName(device)}")
                error = null
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                logDebug("BT SPP reflection connect failed: $error")
                try { candidate?.close() } catch (_: Exception) {}
                candidate = null
            }
        }

        if (candidate == null || candidate.isConnected.not()) {
            state = ConnectionState.ERROR
            return error ?: "unknown bluetooth connection error"
        }

        socket = candidate
        inputStream = candidate.inputStream
        outputStream = candidate.outputStream
        connectedDevice = device
        state = ConnectionState.CONNECTED
        return null
    }

    fun getInputStream(): InputStream? = inputStream
    fun getOutputStream(): OutputStream? = outputStream

    /** 写入字节并 flush。线程安全由调用方 Pn532Protocol.transceive 的 synchronized 保证。 */
    @Throws(IOException::class)
    fun write(data: ByteArray) {
        val out = outputStream ?: throw IOException("bluetooth not connected")
        out.write(data)
        out.flush()
    }

    fun disconnect() {
        state = ConnectionState.DISCONNECTED
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
        connectedDevice = null
        logDebug("BT SPP disconnected")
    }

    fun safeDeviceName(device: BluetoothDevice?): String? {
        if (device == null) return null
        return try {
            @Suppress("MissingPermission")
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }
    }

}
