package com.m0h31h31.bamburfidreader.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic

/**
 * MIFARE Classic 卡片的统一操作接口。
 *
 * 这一层只做「传输方式」抽象，不包含任何认证/重连/格式化业务规则。
 * NFC 路径由 [NfcMifareCard] 委托 android.nfc.tech.MifareClassic 实现；
 * 蓝牙 PN532 路径由 Pn532MifareCard 通过 SPP + PN532 指令实现。
 *
 * 方法签名与 MifareClassic 中被本工程使用到的部分一一对应，
 * 因此现有 NFC 逻辑（BambuMifareOperator / MifareClassicSession）只需做类型替换，
 * 已验证的算法保持不变。
 */
interface MifareCard {
    /** 卡片 UID（字节），Bambu/Creality/Snapmaker 密钥派生都依赖它。 */
    val uid: ByteArray

    val isConnected: Boolean

    /** 与 MifareClassic.timeout 语义一致：单位 ms，0 表示使用系统默认。 */
    var timeout: Int

    val sectorCount: Int
    val blockCount: Int

    fun connect()
    fun close()

    fun sectorToBlock(sector: Int): Int
    fun getBlockCountInSector(sector: Int): Int
    fun blockToSector(block: Int): Int

    fun authenticateSectorWithKeyA(sector: Int, key: ByteArray): Boolean
    fun authenticateSectorWithKeyB(sector: Int, key: ByteArray): Boolean

    fun readBlock(blockIndex: Int): ByteArray
    fun writeBlock(blockIndex: Int, data: ByteArray)
}

/**
 * 手机内置 NFC 的实现：纯委托，不改变任何行为。
 */
class NfcMifareCard(
    private val mifare: MifareClassic,
    override val uid: ByteArray
) : MifareCard {

    override val isConnected: Boolean
        get() = mifare.isConnected

    override var timeout: Int
        get() = mifare.timeout
        set(value) { mifare.timeout = value }

    override val sectorCount: Int
        get() = mifare.sectorCount

    override val blockCount: Int
        get() = mifare.blockCount

    override fun connect() = mifare.connect()
    override fun close() = mifare.close()

    override fun sectorToBlock(sector: Int): Int = mifare.sectorToBlock(sector)
    override fun getBlockCountInSector(sector: Int): Int = mifare.getBlockCountInSector(sector)
    override fun blockToSector(block: Int): Int = mifare.blockToSector(block)

    override fun authenticateSectorWithKeyA(sector: Int, key: ByteArray): Boolean =
        mifare.authenticateSectorWithKeyA(sector, key)

    override fun authenticateSectorWithKeyB(sector: Int, key: ByteArray): Boolean =
        mifare.authenticateSectorWithKeyB(sector, key)

    override fun readBlock(blockIndex: Int): ByteArray = mifare.readBlock(blockIndex)
    override fun writeBlock(blockIndex: Int, data: ByteArray) = mifare.writeBlock(blockIndex, data)

    companion object {
        /**
         * 从 Android Tag 构造。MIFARE Classic 不支持时返回 null，
         * 上层据此产生 [BambuNfcFailureReason.MIFARE_UNSUPPORTED]，语义与原逻辑一致。
         */
        fun fromTag(tag: Tag): NfcMifareCard? {
            val uid = tag.id ?: return null
            val mifare = MifareClassic.get(tag) ?: return null
            return NfcMifareCard(mifare, uid)
        }
    }
}

/** 读卡来源，仅用于日志与状态提示。 */
enum class CardSource {
    NFC,
    BLUETOOTH_PN532
}

/**
 * 一次读卡会话的句柄。替代原先在各函数间传递的 android.nfc.Tag。
 */
data class CardHandle(
    val uid: ByteArray,
    val card: MifareCard,
    val source: CardSource
) {
    val uidHex: String
        get() = uid.joinToString("") { "%02X".format(java.util.Locale.US, it) }

    val isBluetooth: Boolean
        get() = source == CardSource.BLUETOOTH_PN532

    companion object {
        fun fromTag(tag: Tag): CardHandle? {
            val card = NfcMifareCard.fromTag(tag) ?: return null
            return CardHandle(card.uid, card, CardSource.NFC)
        }
    }
}
