package com.m0h31h31.bamburfidreader.bluetooth

import com.m0h31h31.bamburfidreader.nfc.MifareCard
import com.m0h31h31.bamburfidreader.logging.logDebug
import java.io.IOException

/**
 * 通过蓝牙 SPP 连接的外接 PN532 实现的 [MifareCard]。
 *
 * PN532 对 MIFARE Classic 的操作通过 InDataExchange (0x40) 承载：
 *  - 认证：0x60/0x61 + 块地址 + 6 字节密钥 + 4 字节 UID
 *  - 读块：0x30 + 块地址 → 16 字节（认证后连续读无需重复认证）
 *  - 写块：0xA0 + 块地址 → 等待 MIFARE ACK(0x0A) → 发送 16 字节 → 状态字节
 *
 * 扇区/块布局按标准 MIFARE Classic 1K（16 扇区 × 4 块）计算，
 * 与 Android MifareClassic 在 1K 卡上的 sectorToBlock/getBlockCountInSector 行为一致。
 */
class Pn532MifareCard(
    private val spp: Pn532Transport,
    override val uid: ByteArray,
    private val timeoutMs: Int = 2000
) : MifareCard {

    @Volatile
    override var isConnected: Boolean = false
        private set

    override var timeout: Int = timeoutMs

    /** 标准 1K 卡：16 扇区、每扇区 4 块、共 64 块。 */
    override val sectorCount: Int = 16
    override val blockCount: Int = 64

    override fun connect() {
        if (!spp.isConnected) throw IOException("bluetooth SPP not connected")
        isConnected = true
    }

    override fun close() {
        isConnected = false
    }

    override fun sectorToBlock(sector: Int): Int = sector * 4
    override fun getBlockCountInSector(sector: Int): Int = 4
    override fun blockToSector(block: Int): Int = block / 4

    override fun authenticateSectorWithKeyA(sector: Int, key: ByteArray): Boolean =
        authenticate(sector, key, Pn532Protocol.MIFARE_AUTH_KEY_A)

    override fun authenticateSectorWithKeyB(sector: Int, key: ByteArray): Boolean =
        authenticate(sector, key, Pn532Protocol.MIFARE_AUTH_KEY_B)

    private fun authenticate(sector: Int, key: ByteArray, authCommand: Int): Boolean {
        val block = sectorToBlock(sector)
        // PN532 认证命令需要携带卡片 UID（4 字节），否则部分固件会返回错误状态。
        val payload = ByteArray(1 + 1 + 6 + 4)
        payload[0] = authCommand.toByte()
        payload[1] = block.toByte()
        System.arraycopy(key, 0, payload, 2, 6)
        System.arraycopy(uid, 0, payload, 8, minOf(4, uid.size))

        return try {
            val raw = transceiveDataExchange(payload)
            // 认证成功时 PN532 返回 [0x41, 0x00]；异常状态由 unwrap 抛出
            Pn532Protocol.unwrapDataExchange(raw)
            logDebug("PN532 auth OK sector=$sector key=${if (authCommand == Pn532Protocol.MIFARE_AUTH_KEY_A) "A" else "B"}")
            true
        } catch (e: Exception) {
            logDebug("PN532 auth failed sector=$sector: ${e.message}")
            false
        }
    }

    override fun readBlock(blockIndex: Int): ByteArray {
        val payload = byteArrayOf(Pn532Protocol.MIFARE_READ.toByte(), blockIndex.toByte())
        val body = Pn532Protocol.unwrapDataExchange(transceiveDataExchange(payload))
        if (body.size != 16) {
            throw IOException("MIFARE read block=$blockIndex returned ${body.size} bytes (expected 16)")
        }
        return body
    }

    override fun writeBlock(blockIndex: Int, data: ByteArray) {
        if (data.size != 16) throw IOException("MIFARE write requires 16 bytes, got ${data.size}")

        // 第一步：发送写命令，期望 MIFARE ACK 0x0A
        val step1 = Pn532Protocol.unwrapDataExchange(
            transceiveDataExchange(byteArrayOf(Pn532Protocol.MIFARE_WRITE.toByte(), blockIndex.toByte()))
        )
        if (step1.isEmpty() || (step1[0].toInt() and 0xFF) != Pn532Protocol.MIFARE_ACK) {
            val got = step1.firstOrNull()?.let { "%02X".format(it) } ?: "empty"
            throw IOException("MIFARE write ACK expected 0x0A, got $got")
        }

        // 第二步：发送 16 字节数据，期望状态 0x00
        Pn532Protocol.unwrapDataExchange(transceiveDataExchange(data))
    }

    private fun transceiveDataExchange(data: ByteArray): ByteArray {
        val input = spp.getInputStream() ?: throw IOException("bluetooth input stream unavailable")
        val output = spp.getOutputStream() ?: throw IOException("bluetooth output stream unavailable")
        val payload = Pn532Protocol.inDataExchangePayload(data)
        return Pn532Protocol.transceive(
            output = output,
            input = input,
            command = Pn532Protocol.CMD_IN_DATA_EXCHANGE,
            payload = payload,
            timeoutMs = timeout,
            // 必须锁定 0x41：上一条命令迟到的应答（如 0x4B 寻卡结果）要被跳过，
            // 否则会把寻卡帧误当成 InDataExchange 结果解析。
            expectedResponse = Pn532Protocol.RESP_IN_DATA_EXCHANGE
        )
    }
}
