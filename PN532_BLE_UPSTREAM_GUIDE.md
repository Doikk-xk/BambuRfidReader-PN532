# PN532 蓝牙版 · 上游更新指南

本仓库是在上游 `m0h31h31/3DPrint-Filament-RFID-Tool`（Gitee 镜像 `JackMoHeiHei/BambuRfidReader`）
基础上增加的 **PN532 BLE 蓝牙连接支持**，包名 `com.m0h31h31.bamburfidreader.pn532`，与官方版可并存安装。

上游更新后如何同步进来，看这一份就够了。

---

## 零、当前状态（2026-11-22）

| 项目 | 状态 |
|---|---|
| 上游最新版 | **v3.2.4**（commit `675832e`），Gitee / GitHub 两边一致 |
| 本地分支 | `feature/pn532-ble`，在 v3.2.4 之上领先 7 个提交 |
| 仓库完整度 | 已 `fetch --unshallow`，完整历史 209 个提交（**不要退回浅克隆**） |
| 最近验证 | 模拟 v3.2.5 全流程演练通过：rebase 成功 → 自动解冲突 → 编译成功出 APK |

**结论：目前上游没有新版本，不需要做升级动作。**

---

## 一、仓库结构

| 分支 / 远端 | 说明 |
|---|---|
| `origin` | **Gitee 镜像 `JackMoHeiHei/BambuRfidReader`** —— 基准，国内直连，最快 |
| `gh-upstream` | GitHub `m0h31h31/3DPrint-Filament-RFID-Tool` —— 降级备选，需代理 |
| `github-pn532` | **自己的 GitHub 正式仓库 `Doikk-xk/BambuRfidReader-PN532`** —— 主分支 `main` 即 PN532 版，网页直接能看 |
| `fork` | 自己的 GitHub fork `Doikk-xk/3DPrint-Filament-RFID-Tool` —— **只读留档，不再推分支**（见下方说明） |
| `gitee` | 自己的 Gitee 仓库 `doikk/BambuRfidReader-PN532` —— 备用备份（需私人令牌） |
| `feature/pn532-ble` | **本地开发分支**，含全部 PN532 蓝牙改动 |
| `master` | 与上游同步的干净主线，不含本地改动 |

远端用途一览：

| 远端 | 地址 | 状态 |
|---|---|---|
| `github-pn532` | `github.com/Doikk-xk/BambuRfidReader-PN532` | ✅ 已推送，`main` = `780a2a2`，标签 `v3.2.4-pn532` |
| `fork` | `github.com/Doikk-xk/3DPrint-Filament-RFID-Tool` | ⛔ 分支已删除，只与上游一致，避免上游首页弹推送横幅 |
| `gitee` | `gitee.com/doikk/BambuRfidReader-PN532` | ⚠️ 仓库需手动创建 + 私人令牌（`projects` 权限） |

> **为什么不再往 fork 推分支**：只要 fork 上出现领先于上游的提交，
> 上游官方仓库 `m0h31h31/3DPrint-Filament-RFID-Tool` 首页就会自动弹出
> 「Doikk-xk:feature/pn532-ble 分支有了新的推送 → 比较 & 拉取请求」横幅。
> PN532 版已有自己的正式仓库 `BambuRfidReader-PN532` 托管，
> 所以 2026-11-22 已删除 fork 上的 `feature/pn532-ble`，同步脚本也不再推 fork。

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

1. 自检：git 身份、`local.properties` 必需键、是否处于 rebase 中断状态
2. 探测上游：Gitee 直连优先；Gitee 不通则自动试 GitHub，并自动探测
   代理端口（7890 / 10809 / 1080 / 7897 / 8080 / 10808）
3. `git fetch --tags --prune`
4. 打印上游新增了哪些提交、动了哪些文件
5. 工作区不干净时自动 `git stash push -u`
6. 打回滚 tag：`local/pn532-<上游版本>-<时间戳>`
7. `git rebase origin/master`（上游提交垫底，PN532 改动重放在最上）
8. `git stash pop` 恢复
9. 合并后自检：`applicationId` 是否被上游改掉、PN532 关键文件是否还在、
   `TAG_PACKAGE_KEY` 配置是否还在
10. 编译前扫描冲突标记残留 → 编译 APK → 打印安装命令

常用参数：

```bash
./sync-upstream.sh --check      # 只看上游有没有新版本，不动工作区
./sync-upstream.sh --no-build   # 合并但不编译
./sync-upstream.sh --push       # 合并后推送到 GitHub 正式仓库 / Gitee
./sync-upstream.sh --no-proxy   # 禁用 GitHub 代理探测（只走 Gitee）
```

### 出冲突了怎么办

脚本会停下来并列出冲突文件。**第一步先试半自动解决**：

```bash
node resolve-conflicts.js      # 自动合并 strings.xml 同名 key 冲突
git add -A
git rebase --continue
```

`resolve-conflicts.js` 的行为：
- 只自动处理 `strings.xml`（保留双方文案，上游侧重名 key 自动加 `_upstream` 后缀，避免
  Android 报 Duplicate resources）
- 其他文件（如 `MainActivity.kt`）保持冲突状态不动，交给人工判断 —— 那类冲突涉及逻辑，
  盲目合并会引入 bug
- 结束后扫描全文件重复 key 并提示
- 保留原文件 CRLF/LF 行尾风格，不会产生整文件行尾 diff

人工处理的完整步骤：

```bash
# 1. 看冲突
grep -n -A8 '^<<<<<<<' app/src/main/java/com/m0h31h31/bamburfidreader/MainActivity.kt

# 2. 编辑文件，删掉 <<<<<<< / ======= / >>>>>>> 标记
#    原则：保留 PN532 相关逻辑（HEAD 侧）+ 采纳上游新功能

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

### 回退

每次合并前脚本都会打一个回滚 tag，输出形如：

```
local/pn532-3.2.5-20261122-171700
```

回退：

```bash
git tag -l "local/pn532-*"
git reset --hard local/pn532-3.2.5-20261122-171700
```

---

## 三、几个必须知道的坑

### 1. 不要用浅克隆

早期这个仓库是 `--depth` 浅克隆，历史边界正好卡在 v3.2.4。一旦上游改写历史，
rebase 就会断。现已 `fetch --unshallow` 补成完整克隆（209 个提交），**别再退回浅克隆**。

### 2. git 必须配好身份

没配 `user.email` 时，`git rebase --continue` 会直接失败：

```bash
git config user.email "you@example.com"
git config user.name  "Your Name"
```

脚本启动时会检查并提示。

### 3. 冲突标记残留会让编译失败

`strings.xml` 里残留 `<<<<<<<` 会导致 XML 解析失败，且往往报错位置离现场很远。
脚本在编译前会自动全仓库扫描（兼容 CRLF 行尾），发现残留就终止编译。
自己手动排查：

```bash
git grep -n -E '^(<<<<<<<|>>>>>>>|=======)' -- .
```

### 4. local.properties 不在 git 里

`local.properties` 已被 `.gitignore`，rebase 不会动它，但**换机器或误删后必须补回**，
否则以下功能失效：

| 键 | 作用 | 缺失后果 |
|---|---|---|
| `TAG_PACKAGE_KEY` | 共享标签库包解密密钥 | 下载后无法解压 / 导入失败 |
| `EVENT_API_KEY` | 事件上报 | 上报被拒 |
| `SHARED_INSTALL_ID` | 可选，默认注释掉 | 无影响。服务端按 install_id 累计共享标签数，满 3 张即放行下载，每台设备用自己的编号即可 |

脚本启动时会检查这三项并提示。

### 5. 构建配置放在仓库外

上游的 `gradle/gradle-daemon-jvm.properties` 要求使用 **JetBrains 厂商**的 JDK。
本机 PyCharm 自带的 JBR 报告的 vendor 是 `"JetBrains s.r.o."`，Gradle 精确匹配不上会直接失败。

解决办法是**预置官方 JBR SDK**，并通过仓库外的 Gradle 用户目录引入，**完全不碰仓库文件**：

- JBR SDK 位置：`G:/windows/workbuddy/toolchain/jbr/jbrsdk_jcef-21.0.10-windows-x64-b1163.105`
- 引入配置：`G:/windows/workbuddy/toolchain/gradle-home/gradle.properties`
  ```properties
  org.gradle.java.installations.paths=G\:/windows/workbuddy/toolchain/jbr/jbrsdk_jcef-21.0.10-windows-x64-b1163.105,...
  ```

这样每次 rebase 都不会在 `gradle/` 目录产生冲突。

### 6. 手动编译命令

```bash
cd G:/windows/workbuddy/2026-11-22-11-10-33/BambuRfidReader
export JAVA_HOME="G:/windows/workbuddy/toolchain/jbr/jbrsdk_jcef-21.0.10-windows-x64-b1163.105"
export GRADLE_USER_HOME="G:/windows/workbuddy/toolchain/gradle-home"
export ANDROID_HOME="G:/windows/workbuddy/toolchain/android-sdk"
/g/windows/workbuddy/toolchain/gradle-9.3.1/bin/gradle assembleDebug --offline
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 7. 安装到手机

```bash
/g/windows/workbuddy/toolchain/android-sdk/platform-tools/adb.exe \
    -s 192.168.100.199:39295 install -r -t app/build/outputs/apk/debug/app-debug.apk
```

---

## 四、备份到远端（可选）

`sync-upstream.sh --push` 会自动推两个地方：

```bash
# GitHub 正式仓库（主分支 main = PN532 版，网页直接能看）
git push github-pn532 feature/pn532-ble:refs/heads/main

# Gitee 备用（需先建仓库 + 私人令牌）
git push gitee feature/pn532-ble:feature/pn532-ble
```

> 不推 fork —— 原因见上方「远端用途一览」里的说明。

推上去后，即使本地磁盘出问题，代码也在 GitHub 上存着。

> **注意**：`github-pn532` 的默认分支是 `main`，不是本地的 `feature/pn532-ble`，
> 所以推送时必须显式写成 `feature/pn532-ble:refs/heads/main`，脚本已处理。

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
