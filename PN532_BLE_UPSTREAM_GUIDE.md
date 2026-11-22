# PN532 蓝牙版 · 上游更新指南

本仓库是在上游 `m0h31h31/3DPrint-Filament-RFID-Tool`（Gitee 镜像 `JackMoHeiHei/BambuRfidReader`）
基础上增加的 **PN532 BLE 蓝牙连接支持**，包名 `com.m0h31h31.bamburfidreader.pn532`，与官方版可并存安装。

上游更新后如何同步进来，看这一份就够了。

---

## 一、仓库结构

| 分支 / 远端 | 说明 |
|---|---|
| `upstream/master` | 上游主线（GitHub `m0h31h31/3DPrint-Filament-RFID-Tool`） |
| `feature/pn532-ble` | **本地开发分支**，包含全部 PN532 蓝牙改动 |
| `master` | 与上游同步的干净主线，不含本地改动 |
| `fork` | 你自己的 GitHub fork（`Doikk-xk/...`），用于备份/推送 |
| `origin` | Gitee 镜像，只读参考 |

关键原则：**PN532 改动全部是「增量提交」，不修改上游逻辑本身**。
因此 rebase 时冲突只可能出现在下面这几个文件，且位置固定：

```
app/src/main/java/com/m0h31h31/bamburfidreader/MainActivity.kt
app/src/main/java/com/m0h31h31/bamburfidreader/nfc/BambuMifareOperator.kt
app/src/main/java/com/m0h31h31/bamburfidreader/nfc/MifareClassicSession.kt
app/src/main/java/com/m0h31h31/bamburfidreader/ui/screens/MiscScreen.kt
app/src/main/java/com/m0h31h31/bamburfidreader/ui/navigation/AppNavigation.kt
app/src/main/res/values*/strings.xml
```

而这些**新增文件永远不会有冲突**（上游不存在同名文件）：

```
app/src/main/java/com/m0h31h31/bamburfidreader/bluetooth/*.kt   (6 个)
app/src/main/java/com/m0h31h31/bamburfidreader/nfc/MifareCard.kt
app/src/main/java/com/m0h31h31/bamburfidreader/ui/screens/BluetoothReaderPanel.kt
```

---

## 二、上游发新版后的标准流程

### 一条命令搞定

```bash
cd G:/windows/workbuddy/2026-11-22-11-10-33/BambuRfidReader
./sync-upstream.sh
```

脚本会依次完成：

1. `git fetch upstream --tags --prune` 拉取上游
2. 打印上游新增了哪些提交
3. 工作区不干净时自动 `git stash push -u`
4. `git rebase upstream/master` 把上游提交垫到下面，你的改动重放在最上面
5. 自动 `git stash pop` 恢复
6. 编译 APK，输出安装命令

常用参数：

```bash
./sync-upstream.sh --check      # 只看上游有没有新版本，不动工作区
./sync-upstream.sh --no-build   # 合并但不编译
```

### 出冲突了怎么办

脚本会停下来并列出冲突文件。手动处理：

```bash
# 1. 看冲突
grep -n -A5 '^<<<<<<<' app/src/main/java/com/m0h31h31/bamburfidreader/MainActivity.kt

# 2. 编辑文件，删掉 <<<<<<< / ======= / >>>>>>> 标记
#    通常策略：保留 PN532 相关逻辑（HEAD 侧）+ 采纳上游新功能

#    整个文件取我的版本： git checkout --ours  <文件>
#    整个文件取上游版本： git checkout --theirs <文件>

# 3. 标记已解决
git add <文件>

# 4. 继续
git rebase --continue

# 5. 想放弃重来
git rebase --abort
```

解决完重新跑 `./sync-upstream.sh` 即可继续编译。

---

## 三、为什么构建配置放在仓库外

上游的 `gradle/gradle-daemon-jvm.properties` 要求使用 **JetBrains 厂商**的 JDK。
本机 PyCharm 自带的 JBR 报告的 vendor 是 `"JetBrains s.r.o."`，Gradle 精确匹配不上会直接失败。

解决办法是**预置官方 JBR SDK**，并通过仓库外的 Gradle 用户目录引入，**完全不碰仓库文件**：

- JBR SDK 位置：`G:/windows/workbuddy/toolchain/jbr/jbrsdk_jcef-21.0.10-windows-x64-b1163.105`
- 引入配置：`G:/windows/workbuddy/toolchain/gradle-home/gradle.properties`
  ```properties
  org.gradle.java.installations.paths=G\:/windows/workbuddy/toolchain/jbr/jbrsdk_jcef-21.0.10-windows-x64-b1163.105,...
  ```

这样每次 rebase 都不会在 `gradle/` 目录产生冲突。

### 手动编译命令

```bash
cd G:/windows/workbuddy/2026-11-22-11-10-33/BambuRfidReader
export JAVA_HOME="G:/windows/workbuddy/toolchain/jbr/jbrsdk_jcef-21.0.10-windows-x64-b1163.105"
export GRADLE_USER_HOME="G:/windows/workbuddy/toolchain/gradle-home"
export ANDROID_HOME="G:/windows/workbuddy/toolchain/android-sdk"
/g/windows/workbuddy/toolchain/gradle-9.3.1/bin/gradle assembleDebug --offline
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 安装到手机

```bash
/g/windows/workbuddy/toolchain/android-sdk/platform-tools/adb.exe \
    -s 192.168.100.199:39295 install -r -t app/build/outputs/apk/debug/app-debug.apk
```

---

## 四、备份到自己的 fork（可选）

```bash
git push fork feature/pn532-ble:feature/pn532-ble
```

推上去后，即使本地磁盘出问题，代码也在 GitHub 上存着。

---

## 五、PN532 蓝牙实现要点（供后续维护参考）

这几条是踩过坑才定下来的，改动时别退回旧写法：

### 1. PN532 HSU 帧绝不能拆开写
帧一旦在链路中间被拆开（哪怕间隔 0ms），芯片会丢弃整帧，连 ACK 都不返回。
典型症状：能答固件版本，但寻卡永远无响应、射频场不开启（蓝灯灭）。

- 唤醒序列 26 字节、认证帧 22 字节、写卡数据帧 26 字节，**都超过 BLE 默认 20 字节载荷**
- 必须先 `requestMtu(124)` 协商，再按 `(negotiatedMtu - 3)` 整帧写
- 实测该模块协商结果 = 124（载荷 121）

### 2. SAM 必须显式单独下发
SAM 处于 IRQ 模式（mode=`0x03`）时 `InListPassiveTarget` **完全沉默**；
Normal 模式（mode=`0x01`）才能寻卡。
初始化顺序必须是：**唤醒 → 固件版本 → SAM(Normal)**，三步各自独立成帧。

### 3. 唤醒序列前导与帧分两次写
```
前导: 55 55 00×16           (16 字节，单独一次写)
帧  : 00 00 FF 03 FD D4 14 01 17 00   (10 字节，单独一次写)
```

### 4. 卡片密钥是 HKDF 派生的，不是 FF
来源 `NfcTagReader.kt` 的 `deriveBambuKeys`，salt 为 **16 字节**：
```
9A759CF2C4F7CAFF222CB9769B41BC96
```
按 UID 派生每个扇区的 KeyA / KeyB。写密钥时注意少一个字节都会全部算错。

### 5. GATT 服务/特征自适应
- JDY 方案：`FFE0` / `FFE1`
- HC 方案：`FF00` / `FF01`(notify) / `FF02`(write + notify)
- 实测该模块 `Minicopy(BLE)` 用的是 HC 方案
- `FF02` 自带 notify 属性，但订阅它会报 `Write Not Permitted`，只能作为写入特征

### 6. 写卡尚未闭环
当前卡片所有扇区 trailer 相同：`00×6 | 87 87 87 69 | 00×6`
（KeyA 仅读权限，写操作需要 KeyB `0x61`）。
写块第一步 `0xA0` 仍返回超时 `0x7F`，时序变体均未成功 —— **写卡路径还没跑通**。
