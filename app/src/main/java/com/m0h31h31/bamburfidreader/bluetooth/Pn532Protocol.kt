package com.m0h31h31.bamburfidreader.bluetooth

import com.m0h31h31.bamburfidreader.logging.logDebug
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * PN532 HSU(High Speed UART) 帧协议实现。
 *
 * 蓝牙透传模块（HC-05/06/08 等）把 PN532 的 UART TX/RX 原样转发，
 * 因此这里直接使用 HSU 帧格式：checksum（LCS/DCS），不是 CRC。
 *
 * 真机验证帧结构（PN532 V1.6, IC=0x32）：
 *   请求: 00 00 FF 02 FE D4 02 2A 00
 *   ACK : 00 00 FF 00 FF 00
 *   应答: 00 00 FF 06 FA D5 03 32 01 06 07 E8 00
 * 即 00 00 FF LEN LCS TFI DATA... DCS 00，其中 LEN = 1(TFI) + DATA 长度。
 * DATA 首字节是「响应码」，与命令码不一定相同（如 GetFirmwareVersion 请求 0x02 / 应答 0x03）。
 *
 * 参考：PN532 User Manual (UM0701-02) 第 6 章。
 */
internal object Pn532Protocol {

    private const val TFI_HOST_TO_PN532 = 0xD4
    private const val TFI_PN532_TO_HOST = 0xD5

    // PN532 command codes
    const val CMD_GET_FIRMWARE_VERSION = 0x02
    const val CMD_SAM_CONFIGURATION = 0x14
    const val CMD_IN_LIST_PASSIVE_TARGET = 0x4A
    const val CMD_IN_DATA_EXCHANGE = 0x40

    // PN532 response codes (与命令码不同，必须按响应码解析)
    private const val RESP_GET_FIRMWARE_VERSION = 0x03
    const val RESP_IN_DATA_EXCHANGE = 0x41

    // MIFARE Classic commands carried inside InDataExchange
    const val MIFARE_AUTH_KEY_A = 0x60
    const val MIFARE_AUTH_KEY_B = 0x61
    const val MIFARE_READ = 0x30
    const val MIFARE_WRITE = 0xA0

    /** MIFARE 层 ACK，出现在 InDataExchange 写流程第一步的响应数据中。 */
    const val MIFARE_ACK = 0x0A

    /** 底层帧类型。 */
    sealed class Frame {
        data class Response(val tfi: Int, val data: ByteArray) : Frame()
        data object Ack : Frame()
        data object Nak : Frame()
    }

    class Pn532Exception(message: String, val nak: Boolean = false) : IOException(message)

    private fun checksum(value: Int): Int = (-value) and 0xFF

    fun toHex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

    /**
     * 构造 HSU 帧：00 00 FF LEN LCS TFI DATA... DCS 00
     * [data] 为 TFI 之后的内容（命令码 + 参数），LEN 会把 TFI 自身计入。
     */
    fun buildFrame(tfi: Int, data: ByteArray): ByteArray {
        val len = data.size + 1
        require(len in 2..255) { "PN532 payload length out of range: $len" }
        val lcs = checksum(len)
        var sum = len + lcs + tfi
        for (b in data) sum += b.toInt() and 0xFF
        val dcs = checksum(sum)

        val frame = ByteArray(8 + data.size)
        frame[0] = 0x00
        frame[1] = 0x00
        frame[2] = 0xFF.toByte()
        frame[3] = len.toByte()
        frame[4] = lcs.toByte()
        frame[5] = tfi.toByte()
        System.arraycopy(data, 0, frame, 6, data.size)
        frame[6 + data.size] = dcs.toByte()
        frame[7 + data.size] = 0x00
        return frame
    }

    fun buildCommand(command: Int, payload: ByteArray): ByteArray {
        val data = ByteArray(payload.size + 1)
        data[0] = command.toByte()
        System.arraycopy(payload, 0, data, 1, payload.size)
        return buildFrame(TFI_HOST_TO_PN532, data)
    }

    /** 等待并读取一帧；跳过线路上的 0x00 空字节。 */
    fun readFrame(input: InputStream, timeoutMs: Int): Frame {
        val start = System.currentTimeMillis()

        // 同步到 00 00 FF
        var zeros = 0
        while (true) {
            val b = readByte(input, start, timeoutMs, "frame header")
            when {
                b == 0x00 -> {
                    zeros++
                    if (zeros >= 3) zeros = 3
                }
                b == 0xFF && zeros >= 2 -> break
                else -> zeros = 0
            }
        }

        val len = readByte(input, start, timeoutMs, "LEN")
        val lcs = readByte(input, start, timeoutMs, "LCS")

        // ACK 帧没有 TFI/DCS。真机（PN532 V1.6）实测为 00 00 FF 00 FF 00（LEN=0），
        // 部分手册/固件为 00 00 FF 01 FF 00（LEN=1），两者都兼容。
        if ((len == 0 || len == 1) && lcs == 0xFF) {
            readByte(input, start, timeoutMs, "ACK postamble")
            return Frame.Ack
        }

        if (len !in 2..255) throw Pn532Exception("invalid PN532 frame length: $len")
        if (lcs != checksum(len)) {
            throw Pn532Exception("PN532 LCS mismatch: expect ${checksum(len)}, got $lcs")
        }

        val tfi = readByte(input, start, timeoutMs, "TFI")
        val data = ByteArray(len - 1)
        var sum = len + lcs + tfi
        for (i in data.indices) {
            data[i] = readByte(input, start, timeoutMs, "DATA[$i]").toByte()
            sum += data[i].toInt() and 0xFF
        }

        // NACK 帧: 00 00 FF 02 FE D5 00 —— 只有 7 字节，没有 postamble。
        if (len == 2 && tfi == TFI_PN532_TO_HOST && data[0] == 0x00.toByte()) {
            // 若后面还有字节，说明这可能是正常的短 Response（如 4B 00），交给下面 DCS 校验。
            if (input.available() == 0) return Frame.Nak
        }

        val dcs = readByte(input, start, timeoutMs, "DCS")
        if (dcs != checksum(sum)) {
            throw Pn532Exception("PN532 DCS mismatch: expect ${checksum(sum)}, got $dcs")
        }
        readByte(input, start, timeoutMs, "postamble")

        if (tfi != TFI_PN532_TO_HOST) {
            throw Pn532Exception("unexpected PN532 TFI: $tfi (expected $TFI_PN532_TO_HOST)")
        }
        return Frame.Response(tfi, data)
    }

    /**
     * BluetoothSocket 的 InputStream 不支持 soTimeout，read() 会无限阻塞。
     * 这里用 available() 轮询实现软超时，超时后抛出 Pn532Exception。
     */
    private fun readByte(input: InputStream, start: Long, timeoutMs: Int, label: String): Int {
        val deadline = start + timeoutMs
        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                throw Pn532Exception("timeout reading $label")
            }
            if (input.available() > 0) {
                val b = input.read()
                if (b < 0) throw Pn532Exception("PN532 stream closed while reading $label")
                return b
            }
            Thread.sleep(2)
        }
    }

    /**
     * 发送命令并读取响应。
     *
     * 标准 HSU 流程是先 ACK 帧再 Response 帧；但蓝牙透传环境下：
     *  - 部分模块/固件会吞掉 ACK 或直接回 Response；
     *  - 连接建立瞬间的残留字节可能夹杂在前面。
     * 因此这里循环读帧直到拿到 Response（最多容忍 4 帧），不再强制第一帧必须是 ACK。
     *
     * 另外可传入 [expectedResponse]：若先读到的是不匹配的应答帧（例如上一条
     * 命令迟到的应答），会继续等待直到出现匹配帧或超时，而不是立刻失败。
     * 序列化由 synchronized(output) 保证（同一时刻只有一个命令在等响应）。
     */
    fun transceive(
        output: OutputStream,
        input: InputStream,
        command: Int,
        payload: ByteArray,
        timeoutMs: Int,
        expectedResponse: Int? = null
    ): ByteArray {
        val frame = buildCommand(command, payload)
        val cmdHex = "%02X".format(command)
        synchronized(output) {
            output.write(frame)
            output.flush()
            logDebug("PN532 TX cmd=0x$cmdHex frame=${toHex(frame)}")

            var response: ByteArray? = null
            var mismatched: ByteArray? = null
            val deadline = System.currentTimeMillis() + timeoutMs * 2L
            var frames = 0
            while (response == null && frames < 6) {
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                if (remaining <= 0) break
                when (val f = readFrame(input, remaining)) {
                    is Frame.Ack -> logDebug("PN532 RX ACK (cmd=0x$cmdHex)")
                    is Frame.Nak -> throw Pn532Exception("PN532 returned NACK for command 0x$cmdHex", true)
                    is Frame.Response -> {
                        val code = f.data.firstOrNull()?.toInt()?.and(0xFF)
                        if (expectedResponse == null || code == expectedResponse) {
                            response = f.data
                        } else {
                            // 不是本命令的应答（多半是上一条命令迟到的响应），记下后继续等。
                            mismatched = f.data
                            logDebug(
                                "PN532 RX cmd=0x$cmdHex stale response code=0x%02X (expected 0x%02X), keep waiting"
                                    .format(code ?: 0, expectedResponse)
                            )
                        }
                    }
                }
                frames++
            }
            if (response == null) {
                if (mismatched != null) {
                    throw Pn532Exception(
                        "PN532 $cmdHex response code mismatch: got 0x%02X, expected 0x%02X"
                            .format(mismatched.firstOrNull()?.toInt()?.and(0xFF) ?: 0, expectedResponse ?: 0)
                    )
                }
                throw Pn532Exception("no response for command 0x$cmdHex after $frames frame(s)")
            }
            logDebug("PN532 RX cmd=0x$cmdHex data=${toHex(response)}")
            return response
        }
    }

    /** 校验响应码，不符直接抛错，避免把错误载荷当数据解析。 */
    fun requireResponse(raw: ByteArray, expected: Int, name: String) {
        val code = raw.firstOrNull()?.toInt()?.and(0xFF)
        if (code != expected) {
            throw Pn532Exception(
                "$name: unexpected response code 0x${code?.let { "%02X".format(it) } ?: "empty"} (expected 0x${"%02X".format(expected)})"
            )
        }
    }

    /** InDataExchange 的 payload：目标号 1 + 数据。 */
    fun inDataExchangePayload(data: ByteArray): ByteArray {
        val payload = ByteArray(data.size + 1)
        payload[0] = 0x01 // Tg = 1
        System.arraycopy(data, 0, payload, 1, data.size)
        return payload
    }

    /**
     * 解析 InDataExchange 响应载荷：[0x41 STATUS DATA...]
     * 返回 DATA（认证成功时可能为空）。
     */
    fun unwrapDataExchange(raw: ByteArray): ByteArray {
        if (raw.isEmpty()) return ByteArray(0)
        requireResponse(raw, RESP_IN_DATA_EXCHANGE, "InDataExchange")
        if (raw.size < 2) throw Pn532Exception("InDataExchange missing status byte")
        val status = raw[1].toInt() and 0xFF
        if (status != 0x00) {
            throw Pn532Exception("InDataExchange failed, status=0x${"%02X".format(status)}")
        }
        return if (raw.size > 2) raw.copyOfRange(2, raw.size) else ByteArray(0)
    }

    /** 固件版本载荷：[0x03 IC VER REV SUPPORT] */
    fun parseFirmwareVersion(raw: ByteArray): String {
        requireResponse(raw, RESP_GET_FIRMWARE_VERSION, "GetFirmwareVersion")
        if (raw.size < 5) throw Pn532Exception("GetFirmwareVersion payload too short: ${raw.size}")
        val ic = when (val v = raw[1].toInt() and 0xFF) {
            0x32 -> "PN532"
            else -> "IC(0x${"%02X".format(v)})"
        }
        val ver = raw[2].toInt() and 0xFF
        val rev = raw[3].toInt() and 0xFF
        val support = raw[4].toInt() and 0xFF
        return "$ic v$ver.$rev (support=0x${"%02X".format(support)})"
    }
}
