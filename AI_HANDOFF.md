# AI 交接文档 · BambuRfidReader PN532 蓝牙版

> **给接手这个项目的 AI 读。** 这份文档是自包含的：读完就能接手，
> 不需要翻聊天记录、不需要问人。事实已全部核对到 2026-11-22 的实际磁盘状态。
>
> 如果你只做一件事，先读 **§4 不可回退的坑**——那里每一条都是踩出来的，
> 改回去会直接失败。

---

## 1. 接手后请照此执行

**你要做的（收到任务后按此顺序）**：

1. 先读 §2（不可回退的坑）+ §5（架构）+ §10（快速上手清单）
2. 动手前跑 `./sync-upstream.sh --check` —— 确认上游期间有没有发新版本
3. 改代码 → 编译（§3.3 命令，`--offline` 必须加）
4. 提交（**不要 `--amend`**，新开一个 commit）
5. 推送：`git push github-pn532 feature/pn532-ble:refs/heads/main`
   （分支名映射见 §6.5，别推成 `feature/pn532-ble`）

**你不要做的**：

- ❌ 不要修改上游 `master` 分支或上游基线提交
- ❌ 不要动 `app/src/main/java/com/m0h31h31/bamburfidreader/bluetooth/BluetoothSppClient.kt`
  —— 那是上游原有的 SPP 通路，本项目没动它
- ❌ 不要改仓库内 `gradle/` 目录（见 §8 第3条）
- ❌ 不要把 `local.properties` 提交进 git
- ❌ 不要把 `SHARED_INSTALL_ID` 的值写进任何被 git 追踪的文件
- ❌ 不要在写卡进度上反复汇报（用户明确要求）
- ❌ 不要重新发明 §4 里已经定死的协议细节（整帧写 / SAM 独立下发 / HKDF salt）

**用户沟通风格**：

- 中文；直接给结论，不铺垫；用表格和清单代替段落
- 有事实先查证再回答，别猜
- 出问题先给可执行的解决方案，再解释原因
- 不要重复已经说过的状态

---

## 2. 一句话背景

给开源 Android 应用 `BambuRfidReader`（拓竹/3D 打印耗材 RFID 标签读写工具）
**新增 PN532 蓝牙读卡器支持**。原版只支持手机 NFC 和蓝牙 SPP 串口透传，
用户手上的 PCR532 是「PN532 芯片 + BLE 透传模块 + CH340」，走的是 **BLE GATT**，
所以要在应用里新增一条 BLE 通路。

上游项目**不可修改**（我们要定期 rebase 合并它的更新），
所有改动必须做成**可增量 rebase 的形态**。

---

## 3. 环境与代码位置

### 3.1 代码

| 项 | 路径 |
|---|---|
| 仓库根 | `G:\windows\workbuddy\2026-11-22-11-10-33\BambuRfidReader` |
| 开发分支 | `feature/pn532-ble`（本地 HEAD `be0746e`） |
| 上游基线 | `master` = `675832e`（上游 v3.2.4），不含本地改动 |
| PN532 源码 | `app/src/main/java/com/m0h31h31/bamburfidreader/bluetooth/`（7 个 .kt，共 1666 行） |
| 产物 APK | `app/build/outputs/apk/debug/app-debug.apk`（约 42.5 MB） |

**所有东西都在 G 盘，C 盘不留文件**（用户硬性要求）。

### 3.2 构建工具链（全在仓库外）

| 工具 | 路径 |
|---|---|
| JDK | `G:/windows/workbuddy/toolchain/jbr/jbrsdk_jcef-21.0.10-windows-x64-b1163.105` |
| Gradle | `G:/windows/workbuddy/toolchain/gradle-9.3.1/bin/gradle` |
| Android SDK | `G:/windows/workbuddy/toolchain/android-sdk` |
| adb | `G:/windows/workbuddy/toolchain/android-sdk/platform-tools/adb.exe` |
| Gradle 依赖缓存 | `G:/windows/workbuddy/toolchain/gradle-home` |

### 3.3 编译命令

```bash
cd /g/windows/workbuddy/2026-11-22-11-10-33/BambuRfidReader
export JAVA_HOME="G:/windows/workbuddy/toolchain/jbr/jbrsdk_jcef-21.0.10-windows-x64-b1163.105"
export GRADLE_USER_HOME="G:/windows/workbuddy/toolchain/gradle-home"
export ANDROID_HOME="G:/windows/workbuddy/toolchain/android-sdk"
/g/windows/workbuddy/toolchain/gradle-9.3.1/bin/gradle assembleDebug --offline
```

`--offline` 必须加（依赖已缓存；联网会因代理卡住）。

### 3.4 应用标识

| 键 | 值 |
|---|---|
| `namespace` | `com.m0h31h31.bamburfidreader`（与上游相同） |
| `applicationId` | `com.m0h31h31.bamburfidreader.pn532` ← **与官方版并存** |
| 显示名 | `M0 RFID PN532` |
| minSdk / targetSdk | 28 / 36 |
| versionCode / Name | 324 / `3.2.4` |

改 `applicationId` 是为了和官方包（`com.m0h31h31.bamburfidreader`）同时装在手机上。
**副作用见 §5.6**。

---

## 4. 不可回退的坑（最重要）

### 4.1 PN532 HSU 帧绝不能拆开写

帧一旦在链路中间被拆开（哪怕间隔 0ms），芯片丢弃整帧，**连 ACK 都不返回**。
症状极具迷惑性：能答固件版本，但寻卡永远无响应、射频场不开、蓝灯灭。

- 唤醒序列 26 字节、认证帧 22 字节、写卡数据帧 26 字节 —— **全部超过 BLE 默认 20 字节载荷**
- 必须先 `requestMtu(124)` 协商，再按 `(negotiatedMtu - 3)` **整帧**写
- 实测协商结果 = 124（载荷 121）
- 实现位置：`BluetoothBleClient.kt` — `REQUESTED_MTU = 124`（L59）、`writeChunks()`（L511）

### 4.2 SAM 必须显式单独下发

SAM 处于 IRQ 模式（mode=`0x03`）时 `InListPassiveTarget` **完全沉默**；
Normal 模式（mode=`0x01`）才能寻卡。

初始化顺序必须是三步各自独立成帧：**唤醒 → GetFirmwareVersion → SAM(Normal)**。
注意：唤醒帧里虽然也内嵌了一帧 SAMConfiguration，但那一帧在链路上不可靠，
必须单独再发一次。

PN532 命令与响应码配对（`expectedResponse` 用于精确匹配，防止拿到迟到帧）：

| 命令 | 命令码 | 期望响应码 |
|---|---|---|
| `SAMConfiguration` | `0x14` | `0x15` |
| `InListPassiveTarget` | `0x4A` | `0x4B` |
| `InDataExchange` | `0x40` | `0x41` |
| `GetFirmwareVersion` | `0x02` | `0x03` |

实现见 `Pn532Manager.kt` L53-L63（SAM）、`Pn532MifareCard.kt`（InDataExchange
已补 `expectedResponse = 0x41`）。

### 4.3 唤醒序列前导与帧分两次写

```
前导: 55 55 00×16                 (16 字节，一次写)
帧  : 00 00 FF 03 FD D4 14 01 17 00  (10 字节，另一次写)
```

### 4.4 卡片密钥是 HKDF 派生的，不是 FF

来源 `NfcTagReader.kt` 的 `deriveBambuKeys`（L99）。
**salt 恰好 16 字节**（`HKDF_SALT`，L19）：

```
9A 75 9C F2 C4 F7 CA FF 22 2C B9 76 9B 41 BC 96
```

按 UID 派生每个扇区的 KeyA / KeyB。info 串是 `RFID-A\0` / `RFID-B\0`（L26/L27）。
**salt 多写或少写一个字节会导致全部认证失败**（返回 `0x14`）——这个坑误诊过一次。

### 4.5 GATT 服务/特征自适应

- JDY 方案：`FFE0` / `FFE1`
- HC 方案：`FF00` / `FF01`(notify) / `FF02`(write + notify)
- 实测该模块（`Minicopy(BLE)`）用 HC 方案
- `FF02` 自带 notify 属性，但订阅它会报 `Write Not Permitted` —— **只能当写入特征**

### 4.6 冲突标记残留会毁掉编译

上游 rebase 后若残留 `<<<<<<< / ======= / >>>>>>>`，Android XML 解析报错位置
离现场极远，排查成本高。同步脚本已在编译前全仓库扫描拦截。
另：**仓库文件是 CRLF 行尾**，用 `/^<<<<<<< [^\n]*\n/` 匹配永远不中 ——
`resolve-conflicts.js` 已改为 `/^<<<<<<< /` 判定并保留原 EOL。

### 4.7 仓库必须是完整克隆

早期是 shallow clone（历史边界正好卡在 v3.2.4，8 个提交），
上游一旦改写历史 rebase 直接断。已 `git fetch --unshallow origin --tags` 补成完整克隆（209 提交）。
**换机器克隆务必用完整克隆，不要 `--depth`。**

### 4.8 git 必须配好身份

没配 `user.email` 时 `git rebase --continue` 直接失败：

```bash
git config user.name  "Doikk"
git config user.email "doikk@users.noreply.github.com"
```

### 4.9 上游 `MainActivity.kt` 含 1 个 NUL 字节

上游注释里写了 SQLite 魔数 `"SQLite format 3\0"`。
**grep 会把这个文件判成二进制**（`grep` 直接不输出）。用 node 读文件绕过：

```bash
node -e 'const s=require("fs").readFileSync("app/src/main/java/com/m0h31h31/bamburfidreader/MainActivity.kt","utf8"); ...'
```

**这是上游代码，不要改** —— 改了会制造 rebase 冲突。

---

## 5. 架构与功能实现

### 5.1 BLE 文件职责

| 文件 | 行数 | 职责 |
|---|---|---|
| `BluetoothBleClient.kt` | 575 | GATT 连接、MTU 协商、整帧写、CCCD 订阅 |
| `BluetoothSppClient.kt` | 348 | 原有的蓝牙 SPP 串口透传通路（**上游原有，勿动**） |
| `Pn532Protocol.kt` | 283 | HSU 帧编解码、响应码匹配、迟到帧容忍 |
| `Pn532Manager.kt` | 234 | 初始化编排（唤醒→固件→SAM）、收发重试 |
| `Pn532MifareCard.kt` | 114 | MIFARE 命令封装（InDataExchange 等） |
| `Pn532DeviceMatcher.kt` | 94 | 按名称/广播特征识别 PCR532 |
| `Pn532Transport.kt` | 18 | 传输接口抽象 |

### 5.2 设备识别（`Pn532DeviceMatcher`）

按**设备名 + 广播服务特征**双重判定，只上报 PCR532/PN532。
扫描到就弹连接确认框，**不渲染通用设备列表**。
`MainActivity.bondedBluetoothDevices()` 同样过滤 —— 这是「列表还是出现别的设备」的根因。

### 5.3 设备编号（install_id）

`ANDROID_ID` 由系统按「包名 + 用户 + 设备」生成，改 `applicationId` 后必然不同，
云端按 `install_id` 做的**白名单校验**（共享标签库下载）会拒绝。

解法链路：

```
local.properties: SHARED_INSTALL_ID
  → BuildConfig.SHARED_INSTALL_ID
  → MainActivity.applySharedInstallId()   启动时写入 legacy_install_id
  → AnalyticsReporter.getInstallId()      优先读 legacy_install_id
```

**安全性**：该常量只在 `local.properties`（已 gitignore），**全分支扫描确认不在任何
git 追踪文件里**。别人 clone fork 自己编译 → 常量是空串 → 代码首行
`if (shared.isEmpty()) return` → 自动回退自己的 ANDROID_ID。**公开仓库不泄露。**
唯一泄露路径是分发编译好的 APK（常量硬编码在 BuildConfig）。

后续又加了「设置页可编辑/可清空」：手动设过之后记 `install_id_manual` 标记，
构建期默认值不再覆盖。两套 UI（Modern / MIUIX）都接上了。

### 5.4 共享标签库解密

依赖 `local.properties` 里的 `TAG_PACKAGE_KEY`（从官方 APK 的 BuildConfig 用 `dexdump` 提取）。
缺失则下载后无法解密/解压。实测导入成功 **37322 条**（PLA Basic 15172 / PLA Matte 5769 /
PETG Basic 5237 / PLA Lite 3650 等）。

### 5.5 local.properties（不在 git 里，gitignored）

| 键 | 缺失后果 |
|---|---|
| `TAG_PACKAGE_KEY` | 共享标签库下载后无法解密/解压 |
| `EVENT_API_KEY` | 事件上报被拒 |
| `SHARED_INSTALL_ID` | 云端 install_id 白名单校验失败，共享库下不了 |

`rebase` 不会动这个文件，但换机器 / 误删必须补回。同步脚本启动时会检查这三个键。

### 5.6 应用身份相关

- `applicationId` 改了 → `ANDROID_ID` 变了 → 用 `SHARED_INSTALL_ID` 沿用官方值
- 官方包与 PN532 包**并存**，官方更新不会覆盖本版本

---

## 6. Git 与远端

### 6.1 远端（5 个）

| 名称 | 地址 | 角色 |
|---|---|---|
| `origin` | `gitee.com/JackMoHeiHei/BambuRfidReader` | **基准**，Gitee 直连最快 |
| `gh-upstream` | `github.com/m0h31h31/3DPrint-Filament-RFID-Tool` | 降级备选，需代理 |
| `github-pn532` | `github.com/Doikk-xk/BambuRfidReader-PN532` | **正式仓库**，`main` = PN532 版 |
| `fork` | `github.com/Doikk-xk/3DPrint-Filament-RFID-Tool` | 上游的 fork，额外备份 |
| `gitee` | `gitee.com/doikk/BambuRfidReader-PN532` | 备用备份（需私人令牌） |

### 6.2 代理

GitHub 需要代理，Gitee 不需要：

```bash
git config http.https://github.com/.proxy  http://127.0.0.1:7890
git config http.https://github.com/.httpsproxy http://127.0.0.1:7890
```

代理端口**实际是 7890**（之前配置的 13441 已失效，环境变量里的 10864 对 GitHub 返 502）。

### 6.3 提交历史（12 个，全在 `feature/pn532-ble`）

```
732d5c9  feat(bluetooth): add PN532 BLE support for M0 RFID PN532   ← 主体
6b8009e  chore: add sync-upstream.sh helper for merging upstream releases
b625285  docs: add upstream sync guide for the PN532 BLE fork
621b8ba  fix(sync): base on Gitee origin, GitHub as proxy fallback
2ecd20c  fix(sync): keep stashed changes recoverable when rebase hits conflicts
2b0f0d1  feat(bluetooth): only surface PCR532 readers, hide other BLE devices
ab75823  fix(identity): reuse official install_id so cloud whitelist checks pass
b1e1261  chore(sync): harden upstream update flow with a full drill
394d063  docs: drop the write-card status note from the upstream guide
a22305b  feat(settings): let the device ID be edited or cleared in-app
8c050f3  chore(sync): push the backup branch to Gitee as well as GitHub
be0746e  chore(sync): publish the PN532 build to its own GitHub repository
```

相对上游：**32 文件，+3814 行 / -184 行**。

### 6.4 同步工具（仓库根目录）

| 文件 | 用途 |
|---|---|
| `sync-upstream.sh` | 主脚本（Git Bash） |
| `sync-upstream.bat` | 双击运行版（`chcp 65001` 防中文乱码） |
| `resolve-conflicts.js` | 半自动解 strings.xml 冲突 |
| `PN532_BLE_UPSTREAM_GUIDE.md` | 升级指南（**权威文档，以它为准**） |

```bash
./sync-upstream.sh          # 一条命令：自检 → fetch → rebase → 自检 → 编译
./sync-upstream.sh --check      # 只看上游有没有新版
./sync-upstream.sh --no-build   # 合并不编译
./sync-upstream.sh --push       # 合并后推 github-pn532 / fork / gitee
./sync-upstream.sh --no-proxy   # 禁用 GitHub 代理探测，只走 Gitee
```

脚本行为：git 身份检查 → local.properties 三键检查 → rebase 中断态检查 →
探测上游（Gitee 直连优先，失败自动试 GitHub 并扫 7890/10809/1080/7897/8080/10808）→
fetch → 打印新增提交与改动文件 → stash → 打回滚 tag `local/pn532-<版本>-<时间戳>` →
rebase → stash pop → 合并后自检（applicationId / PN532 关键文件 / SHARED_INSTALL_ID）→
冲突标记扫描 → 编译 → 打印安装命令。

**出冲突时**：

```bash
node resolve-conflicts.js          # 只自动处理 strings.xml 同名 key（上游侧加 _upstream 后缀）
git add -A && git rebase --continue
git rebase --abort                 # 搞不定就退回
```

`resolve-conflicts.js` 保留双方、保留原 EOL、合并后报告重复 key；
**Kotlin 等逻辑文件不自动合并**，保持冲突交人工。

**回退**：`git reset --hard local/pn532-<版本>-<时间戳>`（每次合并前自动打）。

### 6.5 GitHub 建仓权限（踩过的坑）

- GitHub 连接器（`mcp__github__create_repository`）**无建仓权限**：
  `POST /user/repos` 返回 `403 Resource not accessible by integration`
- `git push` 自动建仓也不行：`Repository not found`
- **可行路径**：从 `git credential fill` 取出存好的 PAT（40 字符，账号 `Doikk-xk`），
  直接调 `POST https://api.github.com/user/repos` 建仓
  （注意：连接器凭据和 git 凭据是两套，后者权限更大）
- 仓库已建：`Doikk-xk/BambuRfidReader-PN532`，public，默认分支 `main`，标签 `v3.2.4-pn532`
- ⚠️ `github-pn532` 默认分支是 `main`，推送必须写成
  `feature/pn532-ble:refs/heads/main`，不能直接 `$BRANCH:$BRANCH`

### 6.6 Gitee 待办

`gitee.com/doikk/BambuRfidReader-PN532` 在 API 上返回 `Not Found Project` ——
**仓库尚未创建**。需用户手动建仓（公开、**不要初始化 README**）+ 生成
`projects` 权限的私人令牌。远端地址已配好，等凭据即可推。

---

## 7. 本机环境注意事项

- **bash 缺 coreutils**（head/grep/tail/mkdir 不可用）→ 用 node 脚本代替
- **node 走 cmd.exe**，不认 MSYS 路径 `/c/...` → 用 `C:\...`
- **`-e` 内联脚本多层转义易错** → 写成 `.js` 文件执行
- **PowerShell 工具基本不可用**（返回空 + exit 1）→ 一律改用 node
- **本机无 ffmpeg**；需要时用
  `python -m pip install --target "G:\windows\workbuddy\pylibs" imageio-ffmpeg pillow`
  （**不要用 `python -m venv`**，复制 venvlauncher.exe 会失败）
- 渲染/验证网页用无头 Chrome 截图：
  ```
  "C:/Program Files/Google/Chrome/Application/chrome.exe" --headless=new --disable-gpu \
    --hide-scrollbars --window-size=1920,1080 --virtual-time-budget=6000 \
    --screenshot=out.png "file:///G:/..."
  ```
- SSH 登录软路由 `192.168.100.1`（NanoPi R2S Plus）：
  `ssh -i "G:/windows/workbuddy/sshkey_r2s" -o IdentitiesOnly=yes root@192.168.100.1 "<cmd>"`
  （本机无 sshpass / plink，只能走密钥）

---

## 8. 硬性约束

1. **所有文件放 G 盘**，C 盘不留任何产物
2. **不修改上游逻辑** —— 改动必须能增量 rebase
3. **仓库内 `gradle/` 目录保持上游原样**（`gradle-daemon-jvm.properties` 要求
   JetBrains vendor JDK，本机 JBR 报 "JetBrains s.r.o." 匹配不上）——
   通过仓库外的 `gradle-home/gradle.properties` 的
   `org.gradle.java.installations.paths` 引入 JBR
4. **`local.properties` 不进 git**
5. **不要在写卡进度上反复啰嗦** —— 用户明确表示不需要

---

## 9. 待办 / 可接手的方向

| 优先级 | 事项 | 状态 |
|---|---|---|
| 高 | Gitee 仓库创建 + 私人令牌 → 推 `gitee` 远端 | 等用户操作 |
| 中 | 写卡闭环（`0xA0` 写块第一步返回 `0x7F`） | 未解决，见 §9.1 |
| 中 | 手机无线调试端口变化导致无法安装验证 | 需要用户给新 IP:端口 |
| 低 | `toolchain/` 下 30 个历史编译日志可清理 | 可选 |

### 9.1 写卡未闭环的已知线索

- 卡片所有扇区 trailer 相同：`00×6 | 87 87 87 69 | 00×6`
  → **KeyA 只有读权限**，写操作需要 KeyB（`0x61`）
- 写块第一步 `0xA0` 返回超时 `0x7F`，各种时序变体均未成功
- 读卡完全正常（16 扇区全部认证成功，block 4 能读出 `PETG Basic`）
- 待查方向：KeyB 权限获取路径 / PN532 MIFARE 写时序 / 认证后 `0x61` 的正确时序

---

## 10. 快速上手清单

接手后按这个顺序做，5 分钟内可进入状态：

```bash
# 1. 进仓库，确认状态
cd /g/windows/workbuddy/2026-11-22-11-10-33/BambuRfidReader
git status && git log --oneline -3 && git remote -v

# 2. 看 PN532 源码全貌
ls -la app/src/main/java/com/m0h31h31/bamburfidreader/bluetooth/

# 3. 读升级指南（权威）
cat PN532_BLE_UPSTREAM_GUIDE.md

# 4. 编译验证
export JAVA_HOME="G:/windows/workbuddy/toolchain/jbr/jbrsdk_jcef-21.0.10-windows-x64-b1163.105"
export GRADLE_USER_HOME="G:/windows/workbuddy/toolchain/gradle-home"
export ANDROID_HOME="G:/windows/workbuddy/toolchain/android-sdk"
/g/windows/workbuddy/toolchain/gradle-9.3.1/bin/gradle assembleDebug --offline

# 5. 检查上游有无新版
./sync-upstream.sh --check

# 6. 装到手机（需用户提供新的无线调试 IP:端口）
adb connect <IP:PORT>
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

---

## 11. 参考资料

| 资源 | 地址 |
|---|---|
| 上游（基准） | https://gitee.com/JackMoHeiHei/BambuRfidReader/releases#release-v3.2.4 |
| 本项目正式仓库 | https://github.com/Doikk-xk/BambuRfidReader-PN532 |
| 密钥算法参考 | https://github.com/Doikk-xk/3DPrint-Filament-RFID-Tool |
| 硬件 | PCR532 = PN532 + BLE 透传模块 + CH340 |

PN532 官方手册章节：HSU 帧格式 / `InListPassiveTarget` /
`InDataExchange` / `SAMConfiguration` / `MIFARE Authenticate` / `MIFARE Write`。
