package com.m0h31h31.bamburfidreader.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult

/**
 * PCR532 / PN532 蓝牙读卡器识别器。
 *
 * PCR532 = PN532 + BLE 透传模块(JDY/HC 类, 115200 8N1) + CH340 USB。
 * 透传模块的广播特征因厂商而异，这里用多信号判定，降低误报：
 *
 * 1. 广播服务 UUID —— 最强信号
 *    - FFE0：JDY 单特征方案
 *    - FF00：HC 方案（实测 PCR532 蓝牙版就是它）
 * 2. 设备名关键字 —— PCR532 / Minicopy / JDY / HC / PN532 等
 * 3. 名称为空时靠信号强度兜底（透传模块一般带名字，不带名字的按弱信号丢弃）
 */
internal object Pn532DeviceMatcher {

    /** 已知的透传模块广播服务 UUID（小写 16bit 形式）。 */
    private val SERVICE_UUID_HINTS = setOf(
        "ffe0", // JDY 方案：FFE0 服务 + FFE1 特征
        "ff00"  // HC 方案：FF00 服务 + FF01(notify) / FF02(write)
    )

    /** 设备名关键字（小写匹配）。 */
    private val NAME_HINTS = listOf(
        "pcr532",
        "pn532",
        "minicopy",
        "jdy",
        "jdyt",
        "jf",
        "hc-05",
        "hc05",
        "ble serial",
        "ble_uart",
        "bleuart",
        "uart",
        "rfid",
        "nfc"
    )

    /**
     * 判断是否为 PCR532 读卡器。
     *
     * @param name 设备名（可能为空）
     * @param serviceUuids 广播携带的服务 UUID 列表
     * @param rssi 信号强度
     */
    fun isPn532Reader(name: String?, serviceUuids: List<String>?, rssi: Int): Boolean {
        val lowerName = name?.trim()?.lowercase().orEmpty()

        // 信号 1：广播服务 UUID 命中 —— 最可靠，直接判定
        if (hasKnownService(serviceUuids)) return true

        // 信号 2：设备名关键字命中
        if (lowerName.isNotBlank() && NAME_HINTS.any { lowerName.contains(it) }) return true

        // 信号 3：无名称且信号很弱 —— 一律丢弃（杂散 BLE 广播）
        if (lowerName.isBlank() && rssi < -85) return false

        // 有名称但不含关键字：不是 PCR532
        return false
    }

    /** BLE 扫描结果版本：直接读取广播里的服务 UUID。 */
    fun isPn532Reader(result: ScanResult): Boolean {
        val device = result.device ?: return false
        val name = safeName(device)
        val uuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString().lowercase() }
        return isPn532Reader(name, uuids, result.rssi)
    }

    /** 经典蓝牙设备版本：只有名称可用（无广播 UUID）。 */
    fun isPn532Reader(device: BluetoothDevice): Boolean {
        val name = safeName(device)
        return isPn532Reader(name, null, -60)
    }

    private fun hasKnownService(serviceUuids: List<String>?): Boolean {
        if (serviceUuids.isNullOrEmpty()) return false
        return serviceUuids.any { uuid ->
            val compact = uuid.replace("-", "").lowercase()
            SERVICE_UUID_HINTS.any { hint -> compact.startsWith(hint) }
        }
    }

    private fun safeName(device: BluetoothDevice): String? = try {
        device.name
    } catch (_: SecurityException) {
        null
    }
}
