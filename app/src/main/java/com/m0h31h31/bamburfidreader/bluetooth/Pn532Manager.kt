package com.m0h31h31.bamburfidreader.bluetooth

import com.m0h31h31.bamburfidreader.logging.logDebug
import com.m0h31h31.bamburfidreader.nfc.CardHandle
import com.m0h31h31.bamburfidreader.nfc.CardSource
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * PN532 读卡器高层管理：初始化、标签检测、生成 [CardHandle]。
 *
 * 蓝牙透传模块只负责字节转发，PN532 本身的上电/HSU 模式切换通常由模块硬件完成；
 * 这里只做协议层初始化（唤醒 + 固件版本探测 + SAM 配置）。
 */
class Pn532Manager(private val spp: Pn532Transport) {

    private var initialized = false

    /** 最近一次检测到的 UID，用于避免对同一张卡重复触发读卡流程。 */
    @Volatile
    private var lastSeenUid: String? = null

    /** 蓝牙 SPP 延迟远大于 USB 串口，给足超时避免假失败。 */
    val timeoutMs = 4000

    /**
     * 初始化 PN532：唤醒 → 固件版本探测 → 正常模式 SAM 配置。
     * 返回的固件版本号字符串可用于 UI 状态显示。
     */
    fun initialize(): String {
        val input = spp.getInputStream() ?: throw IOException("bluetooth input stream unavailable")
        val output = spp.getOutputStream() ?: throw IOException("bluetooth output stream unavailable")

        // ① 唤醒：PN532 上电/低电模式需要 0x55 0x55 前导，再跟一个 HSU 帧。
        //    这里刻意拆成「前导」和「完整帧」两次独立写入，而不是把整段拼在一起
        //    交给链路层分包——因为 PN532 的 HSU 帧一旦在中间被切开就会整帧作废。
        wakeAndDrain(output, input)

        // ② 固件版本探测
        val fw = transceiveWithRetry(
            output, input,
            Pn532Protocol.CMD_GET_FIRMWARE_VERSION,
            ByteArray(0),
            "GetFirmwareVersion",
            expectedResponse = 0x03
        )
        val version = Pn532Protocol.parseFirmwareVersion(fw)
        logDebug("PN532 firmware: $version")

        // ③ SAM 配置：显式单独下发 Normal 模式。
        //    唤醒帧里虽然也内嵌了一帧 SAMConfiguration，但只要那一帧在链路上
        //    被拆断，SAM 就从未生效，芯片会「能答版本但忽略所有卡」。
        //    独立下发可保证这一步一定被芯片完整接收。
        val sam = transceiveWithRetry(
            output, input,
            Pn532Protocol.CMD_SAM_CONFIGURATION,
            byteArrayOf(0x01),
            "SAMConfiguration",
            expectedResponse = 0x15
        )
        Pn532Protocol.requireResponse(sam, 0x15, "SAMConfiguration")
        logDebug("PN532 SAM configured (normal mode)")

        initialized = true
        lastSeenUid = null
        return version
    }

    /**
     * 唤醒 PN532 并清空输入缓冲中的残留字节。
     *
     * 抓包得到的 LowVbat 唤醒序列为：
     *   55 55 00×16 FF 03 FD D4 14 01 17 00
     * 其中 `55 55 00×14` 是唤醒前导，后 10 字节 `00 00 FF 03 FD D4 14 01 17 00`
     * 才是完整的 HSU 帧。
     *
     * 关键：前导和帧分两次写。若把整段 26 字节交给链路层按 20 字节分包，
     * 帧会在 `FF 03` 之后被截断，PN532 直接丢弃整帧（连 ACK 都不回），
     * 表现为能查到固件版本、但寻卡永远无响应、射频场不开启（指示灯不亮）。
     */
    private fun wakeAndDrain(output: OutputStream, input: InputStream) {
        // 前导：55 55 + 14 个 0x00
        val preamble = ByteArray(16)
        preamble[0] = 0x55.toByte()
        preamble[1] = 0x55.toByte()
        // 帧：00 00 FF 03 FD D4 14 01 17 00
        val frame = byteArrayOf(
            0x00.toByte(), 0x00.toByte(), 0xFF.toByte(), 0x03.toByte(), 0xFD.toByte(),
            0xD4.toByte(), 0x14.toByte(), 0x01.toByte(), 0x17.toByte(), 0x00.toByte()
        )
        try {
            output.write(preamble)
            output.flush()
            output.write(frame)
            output.flush()
            logDebug("PN532 wake sent: ${Pn532Protocol.toHex(preamble)} + ${Pn532Protocol.toHex(frame)}")
        } catch (e: Exception) {
            logDebug("PN532 wake failed, fallback to single 0x55: ${e.message}")
            try {
                output.write(0x55)
                output.flush()
            } catch (_: Exception) {
            }
        }
        // 给芯片一点时间吐出唤醒应答，随后清空，避免污染第一帧的帧同步。
        Thread.sleep(200)
        drain(input)
    }

    /** 丢弃当前可读的所有残留字节。 */
    private fun drain(input: InputStream) {
        try {
            var total = 0
            while (input.available() > 0) {
                val buf = ByteArray(input.available().coerceAtMost(1024))
                val n = input.read(buf)
                if (n <= 0) break
                total += n
            }
            if (total > 0) logDebug("PN532 drained $total stale bytes")
        } catch (e: Exception) {
            logDebug("PN532 drain failed: ${e.message}")
        }
    }

    /**
     * 命令发送 + 校验响应码 + 重试。
     *
     * 重试时重新唤醒 PN532（因为丢帧往往意味着芯片根本没收到上一条命令，
     * 此时直接重发命令会拿到上一条命令的应答）。校验响应码不符时丢弃该帧，
     * 继续等待真正匹配的应答。
     */
    private fun transceiveWithRetry(
        output: OutputStream,
        input: InputStream,
        command: Int,
        payload: ByteArray,
        name: String,
        expectedResponse: Int? = null
    ): ByteArray {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val raw = Pn532Protocol.transceive(output, input, command, payload, timeoutMs)
                if (expectedResponse != null) {
                    Pn532Protocol.requireResponse(raw, expectedResponse, name)
                }
                return raw
            } catch (e: Exception) {
                lastError = e
                logDebug("PN532 $name attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < 2) {
                    // 重新唤醒并清空残留，再试一次。
                    wakeAndDrain(output, input)
                }
            }
        }
        throw lastError ?: IOException("PN532 $name failed")
    }

    /**
     * 轮询检测场内的 ISO14443A 标签（MIFARE Classic）。
     * 检测到返回 [CardHandle]，无卡或失败返回 null。
     *
     * 注意：PN532 检测到卡后必须尽快完成读写；调用方在读卡完成后会调用
     * [clearLastSeen] 以允许同一张卡再次触发流程。
     */
    fun detectCard(): CardHandle? {
        if (!initialized || !spp.isConnected) return null
        val input = spp.getInputStream() ?: return null
        val output = spp.getOutputStream() ?: return null

        return try {
            // InListPassiveTarget: max targets=1, baud rate 106 kbps (ISO14443A)
            val raw = transceiveWithRetry(
                output, input,
                Pn532Protocol.CMD_IN_LIST_PASSIVE_TARGET,
                byteArrayOf(0x01, 0x00),
                "InListPassiveTarget",
                expectedResponse = 0x4B
            )
            if (raw.isEmpty()) return null
            if (raw.size < 6) return null

            // 真机验证的响应载荷布局（Type A）：
            //   [0]=4B 响应码
            //   [1]=NbTg 目标数量
            //   [2]=Tg 目标编号（通常 1）
            //   [3..4]=SENS_RES(ATQA, 2字节)
            //   [5]=SEL_RES(SAK, 1字节) —— 不是 UID 长度！
            //   [6]=NFCIDLength(UID 长度)
            //   [7..7+n]=UID
            // 实测卡：4B 01 01 00 04 08 04 53 99 7A E6 → SAK=08(MIFARE Classic 1K), UIDLen=4, UID=53 99 7A E6
            val targetCount = raw[1].toInt() and 0xFF
            if (targetCount == 0) {
                lastSeenUid = null
                return null
            }
            if (raw.size < 7) return null

            val uidLength = raw[6].toInt() and 0xFF
            if (uidLength !in 4..7 || raw.size < 7 + uidLength) {
                logDebug("PN532 unexpected UID length=$uidLength rawSize=${raw.size}")
                return null
            }
            val uid = raw.copyOfRange(7, 7 + uidLength)
            val uidHex = uid.joinToString("") { "%02X".format(Locale.US, it) }

            // 同一张卡在不离开场区时会持续被检测到；避免重复进入读卡流程。
            if (uidHex == lastSeenUid) return null
            lastSeenUid = uidHex

            val card = Pn532MifareCard(spp, uid, timeoutMs)
            card.connect()
            logDebug("PN532 detected card UID=$uidHex")
            CardHandle(uid, card, CardSource.BLUETOOTH_PN532)
        } catch (e: Exception) {
            logDebug("PN532 detectCard failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 读卡流程结束后调用，允许同一张卡再次触发。 */
    fun clearLastSeen() {
        lastSeenUid = null
    }

    fun reset() {
        initialized = false
        lastSeenUid = null
    }
}
