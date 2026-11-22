[English](#-3dprint-filament-rfid-tool) | [中文](#-3dprint-filament-rfid-tool-1)

---

# 📱 BambuRfidReader · PN532 蓝牙版

An Android app for reading, cloning, and managing **3D printer filament RFID tags** directly with your phone — no additional hardware required.

Supports **Bambu Lab**, **Creality**, and **Snapmaker** filament tags.

> **本仓库是 PN532 蓝牙增强版**，基于官方 `v3.2.4` 增量开发，新增 **PCR532 / PN532 蓝牙（BLE）** 读卡支持，
> 原有 NFC 功能完全不变。包名 `com.m0h31h31.bamburfidreader.pn532`，可与官方版并存安装。
> 官方版：https://github.com/m0h31h31/3DPrint-Filament-RFID-Tool

---

## 📲 Download

👉 [GitHub Releases (v3.2.4-pn532)](https://github.com/Doikk-xk/BambuRfidReader-PN532/releases/tag/v3.2.4-pn532) &nbsp;&nbsp; 👉 [Gitee 仓库](https://gitee.com/doikk/BambuRfidReader-PN532)

---

## ✅ Requirements

- Android 9.0+
- **One of** the following read sources:
  - Phone NFC with **Mifare Classic** read/write support (default), **or**
  - External **PN532 module + Bluetooth SPP transparent module** (HC-05/06/08 etc.), selected in *Misc → Bluetooth PN532 Reader*, **or**
  - **PCR532 / PN532 over Bluetooth LE (BLE)** — new in this build

> Most mid-range and flagship Android phones support Mifare Classic. Some phones (e.g. certain Pixel and Samsung models) may only support detection but not full read/write due to hardware limitations. The Bluetooth PN532 option is an alternative read source for those cases; pair the module in system Bluetooth settings first, then connect it inside the app.
>
> **BLE (PCR532 / PN532)** — the app scans for nearby BLE readers and only surfaces devices it recognises as PCR532/PN532; other BLE peripherals are filtered out. No system pairing is needed, just tap the detected reader to connect. The module speaks PN532 HSU framing over a GATT service, so the app negotiates a 124-byte MTU and writes each frame in one piece — HSU frames must not be split.

---

## 🏷️ Supported Brands

| Brand | Read | Clone / Write | Inventory | Share Library |
|-------|------|--------------|-----------|---------------|
| **Bambu Lab** | ✅ | ✅ | ✅ | ✅ |
| **Creality** | ✅ | ✅ | — | — |
| **Snapmaker (快造)** | ✅ | ✅ | — | ✅ |

---

## ✅ Features

### Bambu Lab
- Read filament tag data — type, color, weight, temperature parameters, print settings, and more
- Filament **inventory management** — track remaining weight/percentage per spool, stock in/out records, statistics
- **Clone & write** tag data to FUID / CUID(Minority) cards
- **Community tag library** — upload your tag data and download others' data for filament without an official tag
- Report **anomalous tags** (unknown or mis-encoded tags) to help the community
- Full tag data **export** (raw blocks + keys), tag package **import/export** (JSON)
- Database **backup & restore**

### Creality
- Read Creality filament tag data (type, color, weight)
- Write / configure Creality tags *(enable in Settings)*
- write tag data to CUID cards

### Snapmaker (快造)
- Read Snapmaker filament tag data — type, colors (up to 5), diameter, temperatures, manufacture date
- **Community share library** — browse and write tags by type and color
- Clone tag data to CUID cards

### General
- **Multi-brand reader** — switch brand on the reader screen with a single tap
- **Two UI styles** — Neumorphic or MIUIX
- **Theme modes** — System / Light / Dark
- Multiple **color palettes**
- In-app **log viewer** for diagnostics
- Remote config auto-update (filament catalog, color codes, app config)

---

## 🖼️ Screenshots

![Screenshot](https://github.com/m0h31h31/BambuRfidReader/blob/master/img/brr.png)

![Screenshot](https://gitee.com/JackMoHeiHei/BambuRfidReader/raw/master/img/brr.png)

---

## 🧩 MakerWorld Model

A logo model and tag holder are available on MakerWorld — boost it and print one!

🔗 https://makerworld.com/en/models/2252770

---

## 📚 References

- [RFID Tag Guide — Bambu Research Group](https://github.com/Bambu-Research-Group/RFID-Tag-Guide)
- [MifareClassicTool](https://github.com/ikarus23/MifareClassicTool)
- [Bambu-Lab-RFID-Library](https://github.com/queengooborg/Bambu-Lab-RFID-Library)
- [K2-RFID](https://github.com/DnG-Crafts/K2-RFID)
- [U1-RFID](https://github.com/DnG-Crafts/U1-RFID)

---
---

# 📱 BambuRfidReader · PN532 蓝牙版

一款使用 Android 手机直接读取、复制和管理 **3D 打印机耗材 RFID 标签** 的应用，无需任何额外硬件。

支持 **拓竹（Bambu Lab）**、**创想三维（Creality）** 和 **快造（Snapmaker）** 耗材标签。

> **本仓库是 PN532 蓝牙增强版**，基于官方 `v3.2.4` 增量开发，新增 **PCR532 / PN532 蓝牙（BLE）** 读卡支持，
> 原有 NFC 功能完全不变。包名 `com.m0h31h31.bamburfidreader.pn532`，可与官方版并存安装。
> 官方版：https://github.com/m0h31h31/3DPrint-Filament-RFID-Tool

---

## 📲 下载地址

👉 [GitHub Releases (v3.2.4-pn532)](https://github.com/Doikk-xk/BambuRfidReader-PN532/releases/tag/v3.2.4-pn532) &nbsp;&nbsp; 👉 [Gitee 仓库](https://gitee.com/doikk/BambuRfidReader-PN532)

---

## ✅ 运行环境

- Android 9.0+
- 以下读卡方式任选其一：
  - 手机 NFC 且支持 **Mifare Classic** 读写（默认方式）
  - **PN532 模块 + 蓝牙串口透传模块**（HC-05/06/08 等），在「设置 → 蓝牙 PN532 读卡器」中选择
  - **PCR532 / PN532 蓝牙（BLE）** —— 本版本新增

> 大多数中高端 Android 手机支持 Mifare Classic。部分机型（如部分 Pixel、三星）因硬件限制只能识别标签存在，无法完整读写。
>
> **BLE（PCR532 / PN532）** —— 应用会扫描附近的蓝牙设备，但只显示识别为 PCR532/PN532 的读卡器，其它蓝牙设备一律过滤掉。
> 无需在系统蓝牙设置里配对，找到设备后点一下即可连接。模块走 GATT 上的 PN532 HSU 帧协议，
> 因此应用会协商 124 字节 MTU 并整帧写入 —— HSU 帧不能拆包，拆开会导致校验失败。

---

## 🏷️ 支持品牌

| 品牌 | 读取 | 复制/写入 | 库存管理 | 共享标签库 |
|------|------|----------|---------|-----------|
| **拓竹（Bambu Lab）** | ✅ | ✅ | ✅ | ✅ |
| **创想三维（Creality）** | ✅ | ✅ | — | — |
| **快造（Snapmaker）** | ✅ | ✅| — | ✅ |

---

## ✅ 功能介绍

### 拓竹（Bambu Lab）
- 读取耗材标签信息 — 型号、颜色、重量、温度参数、打印设置等
- **耗材库存管理** — 追踪每卷余量/剩余百分比，支持入库/出库记录和统计
- **复制写入** 标签数据到 FUID / CUID(极个别) 空白卡
- **社区标签库** — 上传自己的标签数据，下载他人共享的数据（适合无官方标签的耗材）
- 上报**异常标签**（未知/编码错误的标签），协助社区维护数据质量
- 完整标签数据导出（原始 block 数据 + 密钥），标签数据打包导入/导出（JSON 格式）
- 数据库**备份与恢复**

### 创想三维（Creality）
- 读取创想三维耗材标签信息（型号、颜色、重量）
- 写入/配置 Creality 标签 *（需在设置中启用）*
- 可写入标签到CUID卡

### 快造（Snapmaker）
- 读取快造耗材标签信息 — 型号、颜色（最多 5 色）、线径、温度参数、生产日期
- **社区共享标签库** — 按型号和颜色浏览，支持写入到空白卡
- 可克隆标签到CUID卡

### 通用功能
- **多品牌读卡器** — 识别页一键切换品牌
- **两种 UI 风格** — 拟物风（Neumorphic）或 MIUIX 风格
- **主题模式** — 跟随系统 / 浅色 / 深色
- 多种**配色方案**
- 内置**日志查看器**，方便调试与问题排查
- 耗材目录、颜色数据、应用配置**远程自动更新**

---

## 🖼️ 应用截图

![应用截图](https://github.com/m0h31h31/BambuRfidReader/blob/master/img/brr.png)

![应用截图](https://gitee.com/JackMoHeiHei/BambuRfidReader/raw/master/img/brr.png)

---

## 🧩 MakerWorld 模型

我在 MakerWorld 发布了应用 Logo 模型和标签固定壳，欢迎助力 & 打印支持一下！

🔗 https://makerworld.com.cn/zh/models/2020787

---

## 📚 参考资料

- [RFID 标签指南 — Bambu Research Group](https://github.com/Bambu-Research-Group/RFID-Tag-Guide)
- [MifareClassicTool](https://github.com/ikarus23/MifareClassicTool)
- [Bambu-Lab-RFID-Library](https://github.com/queengooborg/Bambu-Lab-RFID-Library)
- [K2-RFID](https://github.com/DnG-Crafts/K2-RFID)
- [U1-RFID](https://github.com/DnG-Crafts/U1-RFID)

