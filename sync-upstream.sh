#!/usr/bin/env bash
# =============================================================================
# 上游更新一键合并脚本 (PN532 BLE 分支)
#
# 用途: 上游 Gitee (JackMoHeiHei/BambuRfidReader) 发布新版本后，
#       把上游改动合并进本地 feature/pn532-ble 分支，并重新编译 APK。
#
# 原理: 本地分支与上游共用同一套 git 历史，PN532 改动是纯增量提交，
#       用 rebase 把上游新提交"垫到下面"，我的改动重放在最上面。
#       冲突只会出现在我改过的文件里 (MainActivity / strings.xml 等)，
#       数量少且位置固定，人工看一眼就能解决。
#
# 用法:
#   ./sync-upstream.sh              # 完整流程: fetch -> rebase -> 编译
#   ./sync-upstream.sh --check      # 只检查是否有新版本，不动工作区
#   ./sync-upstream.sh --no-build   # 合并但不编译
#   ./sync-upstream.sh --push       # 合并后推送到 GitHub 正式仓库 / fork / Gitee 备份
#   ./sync-upstream.sh --no-proxy   # 禁用 GitHub 代理探测 (只走 Gitee)
#
# 基准: Gitee (origin) 直连优先；GitHub (gh-upstream) 作为降级备选，
#       需要代理，脚本会自动探测常见代理端口。
# =============================================================================
set -uo pipefail

cd "$(dirname "$0")" || exit 1

BRANCH="feature/pn532-ble"
CHECK_ONLY=0
DO_BUILD=1
DO_PUSH=0
NO_PROXY=0

for arg in "$@"; do
  case "$arg" in
    --check)    CHECK_ONLY=1 ;;
    --no-build) DO_BUILD=0 ;;
    --push)     DO_PUSH=1 ;;
    --no-proxy) NO_PROXY=1 ;;
    -h|--help)  sed -n '2,28p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg (用 --help 查看用法)"; exit 2 ;;
  esac
done

# --- 常量 ------------------------------------------------------------------
PROXY_PORTS="7890 10809 1080 7897 8080 10808"
JBR="G:/windows/workbuddy/toolchain/jbr/jbrsdk_jcef-21.0.10-windows-x64-b1163.105"
GRADLE_HOME_DIR="G:/windows/workbuddy/toolchain/gradle-home"
ANDROID_SDK="G:/windows/workbuddy/toolchain/android-sdk"
GRADLE_BIN="/g/windows/workbuddy/toolchain/gradle-9.3.1/bin/gradle"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
EXPECTED_APP_ID="com.m0h31h31.bamburfidreader.pn532"

# 本包必须存在的关键文件 (上游删除或改名会导致功能丢失)
PN532_CRITICAL_FILES=(
  "app/src/main/java/com/m0h31h31/bamburfidreader/bluetooth/BluetoothBleClient.kt"
  "app/src/main/java/com/m0h31h31/bamburfidreader/bluetooth/Pn532Manager.kt"
  "app/src/main/java/com/m0h31h31/bamburfidreader/bluetooth/Pn532Protocol.kt"
  "app/src/main/java/com/m0h31h31/bamburfidreader/bluetooth/Pn532MifareCard.kt"
  "app/src/main/java/com/m0h31h31/bamburfidreader/bluetooth/Pn532DeviceMatcher.kt"
  "app/src/main/java/com/m0h31h31/bamburfidreader/ui/screens/BluetoothReaderPanel.kt"
)

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[警告] %s\033[0m\n' "$*"; }
err()  { printf '\033[1;31m[错误] %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m[OK] %s\033[0m\n' "$*"; }

# 扫描仓库里的冲突标记残留（兼容 CRLF 行尾）。
# 残留会让 XML 解析失败 / Kotlin 编译失败，必须在编译前拦下来。
scan_conflict_markers() {
  local hits
  hits=$(git grep -l -E '^(<<<<<<<|>>>>>>>|=======)' -- . 2>/dev/null | grep -v '^resolve-conflicts.js$')
  if [ -n "$hits" ]; then
    err "检测到未清理的冲突标记，编译会失败:"
    echo "$hits" | sed 's/^/    /'
    echo
    echo "  定位: grep -n -E '^(<<<<<<<|>>>>>>>|=======)' <文件>"
    echo "  解决后重新运行本脚本。"
    return 1
  fi
  return 0
}

# --- 0. 前置自检 ------------------------------------------------------------
if [ -d .git/rebase-merge ] || [ -d .git/rebase-apply ]; then
  err "仓库处于 rebase 中断状态，请先处理或执行: git rebase --abort"
  exit 1
fi

if [ ! -f local.properties ]; then
  err "缺少 local.properties（SDK 路径 / TAG_PACKAGE_KEY / EVENT_API_KEY 等）"
  exit 1
fi
MISSING_KEYS=""
for key in TAG_PACKAGE_KEY EVENT_API_KEY; do
  grep -q "^${key}=.\+" local.properties || MISSING_KEYS="$MISSING_KEYS $key"
done
if [ -n "$MISSING_KEYS" ]; then
  warn "local.properties 缺少键:$MISSING_KEYS"
  echo "    这些键不在 git 里（已 gitignore），rebase 不会动它们，但缺失会导致"
  echo "    共享标签库无法解密 / 事件上报被拒。"
fi
# SHARED_INSTALL_ID 是可选的：默认留空，让每台设备用自己的编号。
# 只有想统一用一个编号时才配置它。

# git 身份：没有它 rebase --continue 会直接失败（提交时无法确定作者）
if [ -z "$(git config user.email)" ]; then
  err "git 未配置 user.email，rebase --continue 会失败"
  echo "    执行: git config user.email \"you@example.com\""
  echo "          git config user.name  \"Your Name\""
  exit 1
fi

# --- 1. 选上游远端 ----------------------------------------------------------
say "探测上游可达性 (基准: Gitee 直连优先)"
UPSTREAM=""
PROXY_ARG=""
for remote in origin gh-upstream; do
  if [ "$remote" = "gh-upstream" ] && [ "$NO_PROXY" -eq 1 ]; then
    continue
  fi
  if [ "$remote" = "origin" ]; then
    if git ls-remote --exit-code "$remote" HEAD >/dev/null 2>&1; then
      UPSTREAM="origin"; PROXY_ARG=""
      ok "Gitee (origin) 可达"
      break
    fi
    warn "Gitee (origin) 不可达，尝试 GitHub"
    continue
  fi
  # GitHub: 先直连, 再逐个试代理端口
  if git ls-remote --exit-code "$remote" HEAD >/dev/null 2>&1; then
    UPSTREAM="gh-upstream"; PROXY_ARG=""
    ok "GitHub (gh-upstream) 直连可达"
    break
  fi
  for port in $PROXY_PORTS; do
    if git -c "http.https://github.com/.proxy=http://127.0.0.1:${port}" \
            ls-remote --exit-code "$remote" HEAD >/dev/null 2>&1; then
      UPSTREAM="gh-upstream"
      PROXY_ARG="http://127.0.0.1:${port}"
      ok "GitHub (gh-upstream) 通过代理 127.0.0.1:${port} 可达"
      break
    fi
  done
  [ -n "$UPSTREAM" ] && break
done

if [ -z "$UPSTREAM" ]; then
  err "所有上游远端都不可达"
  echo "    Gitee 直连即可（无需代理）。若 Gitee 挂了，请确认网络或代理已启动。"
  exit 1
fi

# --- 2. fetch ---------------------------------------------------------------
say "抓取上游 $UPSTREAM"
if [ -n "$PROXY_ARG" ]; then
  git -c "http.https://github.com/.proxy=${PROXY_ARG}" fetch "$UPSTREAM" --tags --prune || {
    err "fetch 失败"; exit 1; }
else
  git fetch "$UPSTREAM" --tags --prune || { err "fetch 失败"; exit 1; }
fi

BASE=$(git merge-base HEAD "$UPSTREAM/master")
UP_SHA=$(git rev-parse --short "$UPSTREAM/master")
LOCAL_SHA=$(git rev-parse --short HEAD)
BEHIND=$(git rev-list --count HEAD.."$UPSTREAM/master")
AHEAD=$(git rev-list --count "$UPSTREAM/master"..HEAD)
UP_TAG=$(git describe --tags --abbrev=0 "$UPSTREAM/master" 2>/dev/null || echo "无标签")

echo "    上游 master : $UP_SHA  (最新标签 $UP_TAG)"
echo "    本地分支    : $LOCAL_SHA"
echo "    上游领先    : $BEHIND 个提交"
echo "    本地领先    : $AHEAD 个提交 (PN532 改动)"

if [ "$BEHIND" -eq 0 ]; then
  ok "上游没有新提交，无需合并"
  if [ "$CHECK_ONLY" -eq 1 ]; then exit 0; fi
else
  echo
  echo "上游新增提交:"
  git log --oneline --no-decorate "$BASE".."$UPSTREAM/master" | sed 's/^/    /'
  echo
  echo "上游涉及的改动文件:"
  git diff --name-only "$BASE".."$UPSTREAM/master" | sed 's/^/    /'
fi

if [ "$CHECK_ONLY" -eq 1 ]; then
  say "仅检查模式，未改动任何内容"
  exit 0
fi

# --- 3. 备份工作区 ----------------------------------------------------------
STASHED=0
if [ -n "$(git status --porcelain)" ]; then
  warn "工作区有未提交改动，先 stash 保存"
  git stash push -u -m "sync-upstream auto-stash $(date +%Y%m%d-%H%M%S)" \
    || { err "stash 失败"; exit 1; }
  STASHED=1
  ok "已 stash (git stash list 可查)"
fi

# --- 4. 打回滚点 ------------------------------------------------------------
ROLLBACK_TAG="local/pn532-$(echo "$UP_TAG" | tr -d 'v' | tr '/' '-')-$(date +%Y%m%d-%H%M%S)"
git tag -f "$ROLLBACK_TAG" HEAD >/dev/null 2>&1
ok "回滚点已创建: $ROLLBACK_TAG  (回退: git reset --hard $ROLLBACK_TAG)"

# --- 5. rebase --------------------------------------------------------------
say "把 $UPSTREAM/master rebase 到 $BRANCH"
git checkout "$BRANCH" 2>/dev/null || git checkout -b "$BRANCH"
git rebase "$UPSTREAM/master"
RC=$?

if [ $RC -ne 0 ]; then
  err "rebase 出现冲突，需要人工解决"
  echo
  echo "冲突文件:"
  git diff --name-only --diff-filter=U | sed 's/^/    /'
  echo
  echo "解决步骤:"
  echo "  1) 查看冲突:   grep -n -A8 '^<<<<<<<' <冲突文件>"
  echo "  2) 编辑文件，删掉 <<<<<<< / ======= / >>>>>>> 标记"
  echo "     原则: 保留 PN532 相关逻辑 (HEAD 侧) + 采纳上游新功能"
  echo "     整个文件取我的版本: git checkout --ours <文件>"
  echo "     整个文件取上游版本: git checkout --theirs <文件>"
  echo "  3) 标记已解决: git add <文件>"
  echo "  4) 继续:       git rebase --continue"
  echo "  5) 想放弃:     git rebase --abort"
  echo
  echo "不用担心的文件 (上游没有同名文件, 不会冲突):"
  for f in "${PN532_CRITICAL_FILES[@]}"; do echo "    $f"; done
  echo
  echo "半自动解决 (推荐先试):"
  echo "    node resolve-conflicts.js    # 自动合并 strings.xml 同名 key 冲突"
  echo "    git add -A && git rebase --continue"
  echo
  if [ "$STASHED" -eq 1 ]; then
    warn "之前 stash 的改动仍在 stash 里，解完冲突后用 git stash pop 取回"
  fi
  echo "解完后重新执行本脚本即可继续编译。"
  exit $RC
fi
ok "rebase 完成"

# --- 6. 恢复 stash ----------------------------------------------------------
if [ "$STASHED" -eq 1 ]; then
  say "恢复之前 stash 的改动"
  if git stash pop; then
    ok "stash 已恢复"
  else
    warn "stash pop 有冲突，请手动处理: git stash list / git status"
  fi
fi

# --- 7. 合并后自检 ----------------------------------------------------------
say "合并后自检"

APP_ID_NOW=$(grep -oP 'applicationId\s*=\s*"\K[^"]+' app/build.gradle.kts 2>/dev/null | head -1)
if [ "$APP_ID_NOW" = "$EXPECTED_APP_ID" ]; then
  ok "applicationId 正确: $APP_ID_NOW"
else
  err "applicationId 变了! 当前='$APP_ID_NOW' 期望='$EXPECTED_APP_ID'"
  echo "    上游可能改了包名。请手动改回，否则会和官方版互相覆盖安装。"
fi

MISSING_CRIT=""
for f in "${PN532_CRITICAL_FILES[@]}"; do
  [ -f "$f" ] || MISSING_CRIT="$MISSING_CRIT $f"
done
if [ -z "$MISSING_CRIT" ]; then
  ok "PN532 关键文件齐全"
else
  err "PN532 文件丢失:$MISSING_CRIT"
  echo "    可用 git checkout $ROLLBACK_TAG -- <文件> 找回"
fi

if grep -q "SHARED_INSTALL_ID" app/build.gradle.kts 2>/dev/null; then
  ok "install_id 构建配置保留（默认留空，每台设备用自己的编号）"
else
  warn "build.gradle.kts 里没有 SHARED_INSTALL_ID 了，检查一下是否被上游改掉"
fi

# --- 8. 编译 ----------------------------------------------------------------
if [ "$DO_BUILD" -eq 1 ]; then
  say "编译前检查冲突标记残留"
  if ! scan_conflict_markers; then
    err "存在冲突标记残留，终止编译"
    exit 1
  fi
  ok "无冲突标记残留"

  say "编译 APK (JBR 21, Gradle 离线)"
  export JAVA_HOME="$JBR"
  export GRADLE_USER_HOME="$GRADLE_HOME_DIR"
  export ANDROID_HOME="$ANDROID_SDK"
  if [ ! -x "$GRADLE_BIN" ]; then
    err "找不到 Gradle: $GRADLE_BIN"
  elif "$GRADLE_BIN" assembleDebug --offline; then
    if [ -f "$APK_PATH" ]; then
      ok "编译成功"
      ls -la "$APK_PATH"
      echo
      echo "安装到手机:"
      echo "    /g/windows/workbuddy/toolchain/android-sdk/platform-tools/adb.exe \\"
      echo "        -s 192.168.100.199:39295 install -r -t $APK_PATH"
    else
      err "编译结束但找不到 APK: $APK_PATH"
    fi
  else
    err "编译失败，见上方日志"
    exit 1
  fi
fi

# --- 9. 备份到远端 ---------------------------------------------------------
if [ "$DO_PUSH" -eq 1 ]; then
  # fork         = GitHub 上对上游仓库的 fork（备份用）
  # github-pn532 = GitHub 上的独立正式仓库（主分支 main 承载 PN532 版）
  # gitee        = Gitee 上的自己的仓库
  for target in github-pn532 fork gitee; do
    if ! git remote get-url "$target" >/dev/null 2>&1; then
      warn "远端 $target 未配置，跳过"
      continue
    fi
    # github-pn532 的默认分支是 main，本地分支名不同，单独映射
    if [ "$target" = "github-pn532" ]; then
      REMOTE_REF="main"
    else
      REMOTE_REF="$BRANCH"
    fi
    say "推送到 $target ($REMOTE_REF) 备份"
    if git push "$target" "$BRANCH:refs/heads/$REMOTE_REF" 2>&1 | tail -3; then
      ok "已推送到 $target/$REMOTE_REF"
    else
      warn "推送到 $target 失败"
      if [ "$target" = "gitee" ]; then
        echo "    Gitee 需要私人令牌: 设置 -> 私人令牌 -> 生成新令牌"
        echo "    勾选 projects 权限即可。推送时会在弹窗里填用户名和令牌。"
      else
        echo "    GitHub 需要代理，可先设置:"
        echo "        git config http.https://github.com/.proxy http://127.0.0.1:7890"
      fi
    fi
  done
fi

ok "全部完成"
