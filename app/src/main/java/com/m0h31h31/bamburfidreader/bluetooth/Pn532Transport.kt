package com.m0h31h31.bamburfidreader.bluetooth

import java.io.InputStream
import java.io.OutputStream

/**
 * PN532 字节传输层抽象。
 *
 * 上层协议（[Pn532Protocol] / [Pn532Manager] / [Pn532MifareCard]）只关心
 * 「往一个流写命令帧、从另一个流读响应帧」，不关心底层是 BLE GATT、
 * 经典蓝牙 SPP 还是 USB 串口。这样换传输方式时协议与业务逻辑完全不动。
 */
interface Pn532Transport {
    val isConnected: Boolean
    fun getInputStream(): InputStream?
    fun getOutputStream(): OutputStream?
    fun disconnect()
}
