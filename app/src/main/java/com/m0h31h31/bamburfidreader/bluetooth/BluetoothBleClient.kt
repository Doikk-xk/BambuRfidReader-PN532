package com.m0h31h31.bamburfidreader.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.m0h31h31.bamburfidreader.logging.logDebug
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * BLE（低功耗蓝牙）GATT 串口透传客户端。
 *
 * 与 [BluetoothSppClient] 暴露完全相同的公开接口，可直接替换。区别在于：
 *  - SPP 走经典蓝牙 RFCOMM socket；
 *  - BLE 走 GATT 特征值读写 + 通知，模块（JDY/HC 类 BLE 透传）把特征值内容
 *    原样转发到 PN532 的 UART，PN532 帧格式（HSU）不变。
 *
 * 小程序抓包结论：服务 FFE0 / 读写特征 FFE1；HC 方案 FF00 + FF01(notify)/FF02(write)。
 * 这里按优先级自适应多种常见 UUID 组合，并用 CCCD 订阅通知。
 * 写入按 20 字节分包（默认 ATT MTU 23 - 3 开销），包间留 25ms 间隔。
 */
class BluetoothBleClient(private val context: Context) : Pn532Transport {

    companion object {
        private val UUID_SERVICE_FFE0 = uuid("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val UUID_CHAR_FFE1 = uuid("0000ffe1-0000-1000-8000-00805f9b34fb")
        private val UUID_CHAR_FFE2 = uuid("0000ffe2-0000-1000-8000-00805f9b34fb")
        private val UUID_SERVICE_FF00 = uuid("0000ff00-0000-1000-8000-00805f9b34fb")
        private val UUID_CHAR_FF01 = uuid("0000ff01-0000-1000-8000-00805f9b34fb")
        private val UUID_CHAR_FF02 = uuid("0000ff02-0000-1000-8000-00805f9b34fb")
        private val UUID_CCCD = uuid("00002902-0000-1000-8000-00805f9b34fb")

        private const val CHUNK_SIZE = 20
        private const val CHUNK_DELAY_MS = 25L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val SCAN_TIMEOUT_MS = 15_000L

        /**
         * 申请的 ATT MTU。实测 Minicopy(BLE) / PCR532 蓝牙版可协商到 124，
         * 单次写入可承载完整 PN532 帧（含 26 字节唤醒序列），避免帧被拆断。
         */
        private const val REQUESTED_MTU = 124

        private fun uuid(s: String) = java.util.UUID.fromString(s)

        fun requiredPermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.Manifest.permission.BLUETOOTH_CONNECT
            } else {
                android.Manifest.permission.BLUETOOTH
            }

        fun scanPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(android.Manifest.permission.BLUETOOTH_SCAN)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            }
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    @Volatile
    var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    /**
     * 协商后的 ATT MTU。默认 23（载荷 20 字节）；协商到 124 后载荷 121 字节，
     * 一个 PN532 HSU 帧（含 26 字节唤醒序列）可以一次写完。
     */
    @Volatile
    private var negotiatedMtu: Int = 23
        private set

    @Volatile
    private var connectedDevice: BluetoothDevice? = null

    @Volatile
    var isDiscovering: Boolean = false
        private set

    /** 扫描到的设备（含未配对），地址去重。 */
    private val discoveredDevices = LinkedHashMap<String, BluetoothDevice>()

    private var discoveryCallback: ((BluetoothDevice) -> Unit)? = null
    private var discoveryFinishedCallback: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable {
        if (isDiscovering) finishDiscovery()
    }

    /** 通知字节流接收队列：onCharacteristicChanged 写入，InputStream 读取。 */
    private val rxQueue = LinkedBlockingQueue<Int>()

    /** 写特征回调信号：writeCharacteristic 必须串行等待上一个完成。 */
    @Volatile
    private var writeLatch: CountDownLatch? = null

    /** 连接/服务发现/订阅完成信号。 */
    @Volatile
    private var connectLatch: CountDownLatch? = null

    /** CCCD 订阅是否完成。 */
    @Volatile
    private var cccdDone = false

    /** MTU 协商是否完成（成功或失败都算完成）。 */
    @Volatile
    private var mtuDone = false

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override val isConnected: Boolean
        get() = state == ConnectionState.CONNECTED && gatt != null

    val connectedDeviceName: String?
        get() = safeDeviceName(connectedDevice)

    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun bondedDevices(): List<BluetoothDevice> = try {
        bluetoothAdapter?.bondedDevices?.toList().orEmpty()
    } catch (e: SecurityException) {
        logDebug("BLE bonded devices denied: ${e.message}")
        emptyList()
    }

    // ------------------------------------------------------------------
    // BLE 扫描（替代经典蓝牙 startDiscovery）
    // ------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = safeDeviceName(device).orEmpty()
            // 只保留 PCR532 / PN532 蓝牙读卡器：靠广播服务 UUID (FFE0/FF00) 或设备名关键字识别，
            // 其它 BLE 广播（耳机、手环、传感器等）一律忽略，UI 不再刷屏。
            if (!Pn532DeviceMatcher.isPn532Reader(result)) {
                logDebug("BLE ignored non-PN532: ${name.ifBlank { "<no-name>" }} (${device.address})")
                return
            }
            val isNew: Boolean
            synchronized(discoveredDevices) {
                isNew = !discoveredDevices.containsKey(device.address)
                if (isNew) discoveredDevices[device.address] = device
            }
            if (isNew) {
                logDebug("BLE scanned PN532: ${name.ifBlank { "<no-name>" }} (${device.address}) RSSI=${result.rssi}")
                // ScanCallback 运行在 Binder 线程，UI 回调切回主线程。
                mainHandler.post { discoveryCallback?.invoke(device) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            logDebug("BLE scan failed: errorCode=$errorCode")
            mainHandler.post { finishDiscovery() }
        }
    }

    fun startDiscovery(
        onFound: (BluetoothDevice) -> Unit,
        onFinished: () -> Unit
    ): Boolean {
        val adapter = bluetoothAdapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            logDebug("BLE startScan: adapter null/disabled or no LE scanner")
            return false
        }
        stopDiscovery()
        synchronized(discoveredDevices) { discoveredDevices.clear() }
        discoveryCallback = onFound
        discoveryFinishedCallback = onFinished
        isDiscovering = true
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, scanCallback)
            logDebug("BLE scan started")
            mainHandler.removeCallbacks(scanTimeoutRunnable)
            mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
        } catch (e: Exception) {
            logDebug("BLE startScan exception: ${e.message}")
            isDiscovering = false
            return false
        }
        return true
    }

    private fun finishDiscovery() {
        if (!isDiscovering) return
        isDiscovering = false
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            logDebug("BLE stopScan exception: ${e.message}")
        }
        discoveryFinishedCallback?.invoke()
        discoveryFinishedCallback = null
    }

    fun stopDiscovery() {
        isDiscovering = false
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        discoveryCallback = null
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
        }
    }

    fun findDevice(address: String): BluetoothDevice? {
        synchronized(discoveredDevices) {
            discoveredDevices[address]?.let { return it }
        }
        return try {
            bluetoothAdapter?.getRemoteDevice(address)
                ?: bondedDevices().firstOrNull { it.address == address }
        } catch (e: Exception) {
            bondedDevices().firstOrNull { it.address == address }
        }
    }

    // ------------------------------------------------------------------
    // GATT 连接 + 特征发现 + 通知订阅
    // ------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            logDebug("BLE onConnectionStateChange status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logDebug("BLE connection failed with status=$status")
                state = ConnectionState.ERROR
                signalConnectReady()
                g.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                state = ConnectionState.CONNECTING
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                state = ConnectionState.DISCONNECTED
                signalConnectReady()
                g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            logDebug("BLE onServicesDiscovered status=$status services=${g.services?.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                state = ConnectionState.ERROR
                signalConnectReady()
                return
            }
            val selected = selectCharacteristics(g.services.orEmpty())
            if (selected == null) {
                logDebug("BLE no compatible UART characteristic found")
                state = ConnectionState.ERROR
                signalConnectReady()
                return
            }
            writeCharacteristic = selected.first
            notifyCharacteristic = selected.second
            logDebug("BLE writeChar=${selected.first.uuid} notifyChar=${selected.second.uuid}")

            // 订阅通知：setCharacteristicNotification + 写 CCCD。
            try {
                g.setCharacteristicNotification(selected.second, true)
                val cccd = selected.second.getDescriptor(UUID_CCCD)
                if (cccd != null) {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                    logDebug("BLE CCCD notification requested")
                } else {
                    logDebug("BLE no CCCD descriptor; relying on setCharacteristicNotification only")
                    cccdDone = true
                    requestMtu(g)
                }
            } catch (e: Exception) {
                logDebug("BLE subscribe notification exception: ${e.message}")
                cccdDone = true
                requestMtu(g)
            }

            connectedDevice = g.device
            state = ConnectionState.CONNECTED
            maybeSignalReady()
        }

        /**
         * 协商更大 MTU。实测 PCR532 蓝牙版可到 124，单次写入即可承载完整的
         * PN532 帧——写卡第二步的帧长 26 字节、认证帧 21 字节，若仍按 20 字节
         * 分包会被截断，PN532 直接丢帧，表现为「写卡无反应」。
         */
        private fun requestMtu(g: BluetoothGatt) {
            try {
                g.requestMtu(REQUESTED_MTU)
            } catch (e: Exception) {
                logDebug("BLE requestMtu failed: ${e.message}")
                mtuDone = true
                maybeSignalReady()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 0) {
                negotiatedMtu = mtu
                logDebug("BLE MTU negotiated: $mtu (payload ${mtu - 3} bytes)")
            } else {
                logDebug("BLE MTU negotiation failed status=$status mtu=$mtu")
            }
            mtuDone = true
            maybeSignalReady()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value
            if (value != null && value.isNotEmpty()) {
                logDebug("BLE RX [${value.size}] ${Pn532Protocol.toHex(value)}")
                for (b in value) rxQueue.offer(b.toInt() and 0xFF)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeLatch?.countDown()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            logDebug("BLE descriptor write status=$status")
            cccdDone = true
            // CCCD 写完之后再协商 MTU：Android 的 GATT 操作是串行的，
            // writeDescriptor 与 requestMtu 同时进行会互相抢占导致一方失败。
            requestMtu(g)
        }
    }

    /** 从服务列表里挑选 UART 读写特征，兼容多种模块 UUID 方案。 */
    private fun selectCharacteristics(services: List<BluetoothGattService>): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        // 方案 A：FFE0 服务下的 FFE1（JDY 单特征读写 + 通知）。
        for (s in services) {
            if (s.uuid == UUID_SERVICE_FFE0) {
                val ffe1 = s.characteristics.firstOrNull { it.uuid == UUID_CHAR_FFE1 }
                if (ffe1 != null && ffe1.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 &&
                    ffe1.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ) {
                    return ffe1 to ffe1
                }
            }
        }
        // 方案 B：FF00 服务下 FF02(write) + FF01(notify)。
        val ff00 = services.firstOrNull { it.uuid == UUID_SERVICE_FF00 }
        if (ff00 != null) {
            val write = ff00.characteristics.firstOrNull { it.uuid == UUID_CHAR_FF02 }
            val notify = ff00.characteristics.firstOrNull { it.uuid == UUID_CHAR_FF01 }
            if (write != null && notify != null) return write to notify
        }
        // 方案 C：FFE0 服务下 FFE2(write) + FFE1(notify)。
        val ffe0 = services.firstOrNull { it.uuid == UUID_SERVICE_FFE0 }
        if (ffe0 != null) {
            val write = ffe0.characteristics.firstOrNull { it.uuid == UUID_CHAR_FFE2 }
            val notify = ffe0.characteristics.firstOrNull { it.uuid == UUID_CHAR_FFE1 }
            if (write != null && notify != null) return write to notify
        }
        // 方案 D：通用兜底 —— 遍历所有特征，找可通知 + 可写的组合。
        val all = services.flatMap { it.characteristics }
        val single = all.firstOrNull { c ->
            c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 &&
                c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }
        if (single != null) return single to single
        val notifier = all.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }
        val writer = all.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }
        return if (notifier != null && writer != null) writer to notifier else null
    }

    private fun signalConnectReady() {
        connectLatch?.countDown()
    }

    /**
     * 通知订阅与 MTU 协商都完成后才放行 connect()。
     * MTU 必须先确定，写卡时 26 字节的帧才能整帧送达。
     */
    private fun maybeSignalReady() {
        if (cccdDone && mtuDone) signalConnectReady()
    }

    /**
     * 连接指定 BLE 设备（阻塞，请在后台线程调用）。
     * 返回 null 表示成功，否则返回错误描述。
     */
    fun connect(device: BluetoothDevice): String? {
        disconnect()
        state = ConnectionState.CONNECTING
        rxQueue.clear()
        cccdDone = false
        mtuDone = false
        val latch = CountDownLatch(1)
        connectLatch = latch
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            state = ConnectionState.ERROR
            connectLatch = null
            return e.message ?: e.javaClass.simpleName
        }

        try {
            latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        connectLatch = null
        if (state != ConnectionState.CONNECTED) {
            val err = when (state) {
                ConnectionState.ERROR -> "GATT connect/service discovery failed"
                else -> "GATT connect timeout"
            }
            logDebug("BLE connect failed: $err")
            disconnect()
            return err
        }
        logDebug("BLE connected: ${safeDeviceName(device)}")
        return null
    }

    // ------------------------------------------------------------------
    // 字节流适配：让 PN532 协议层像用串口一样用 BLE
    // ------------------------------------------------------------------

    private val bleInputStream = object : InputStream() {
        override fun available(): Int = rxQueue.size
        override fun read(): Int = try {
            rxQueue.poll(1, TimeUnit.MILLISECONDS) ?: -1
        } catch (_: InterruptedException) {
            -1
        }
    }

    private val bleOutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            writeChunks(buffer.copyOfRange(offset, offset + length))
        }
        override fun flush() {}
    }

    /**
     * 写入字节流。
     *
     * 关键：PN532 的 HSU 帧必须在一次连续写入中完整送达芯片。实测（PC 串口与
     * 真实 BLE 双路验证）只要帧在中间被切开——哪怕间隔 0ms——PN532 就会丢弃
     * 整帧，连 ACK 都不会返回，表现为「能答固件版本，但寻卡永远无响应」。
     *
     * 因此这里以协商后的 MTU 载荷上限（mtu - 3）为整帧写入阈值：
     *  - 帧长度 ≤ 载荷上限：一次写完，绝不拆断；
     *  - 帧长度 > 载荷上限：按载荷上限分块，此时芯片可能丢帧，
     *    但本项目实际使用的帧最长 26 字节（唤醒序列），远小于协商后的载荷。
     */
    private fun writeChunks(data: ByteArray) {
        val g = gatt ?: throw IOException("BLE not connected")
        val characteristic = writeCharacteristic ?: throw IOException("BLE write characteristic unavailable")
        val payloadLimit = (negotiatedMtu - 3).coerceAtLeast(CHUNK_SIZE)
        var offset = 0
        while (offset < data.size) {
            val size = minOf(payloadLimit, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + size)
            @Suppress("DEPRECATION")
            characteristic.value = chunk
            val latch = CountDownLatch(1)
            writeLatch = latch
            logDebug("BLE TX [${chunk.size}] ${Pn532Protocol.toHex(chunk)}")
            val ok = @Suppress("DEPRECATION") g.writeCharacteristic(characteristic)
            if (!ok) {
                writeLatch = null
                throw IOException("BLE writeCharacteristic returned false")
            }
            try {
                latch.await(1500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
            writeLatch = null
            offset += size
            if (offset < data.size) {
                try {
                    Thread.sleep(CHUNK_DELAY_MS)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    override fun getInputStream(): InputStream? = if (isConnected) bleInputStream else null
    override fun getOutputStream(): OutputStream? = if (isConnected) bleOutputStream else null

    override fun disconnect() {
        val g = gatt
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        connectedDevice = null
        state = ConnectionState.DISCONNECTED
        rxQueue.clear()
        if (g != null) {
            try {
                g.disconnect()
                g.close()
            } catch (e: Exception) {
                logDebug("BLE disconnect exception: ${e.message}")
            }
        }
        logDebug("BLE disconnected")
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
