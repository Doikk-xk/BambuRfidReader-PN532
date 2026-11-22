#!/usr/bin/env bash
# =============================================================================
# 上游更新一键合并脚本
#
# 用途: 上游 (m0h31h31/3DPrint-Filament-RFID-Tool) 发布新版本后，
#       把上游改动合并进本地 feature/pn532-ble 分支，并重新编译 APK。
#
# 原理: 本地分支与上游共用同一套 git 历史，PN532 改动是纯增量提交，
#       用 rebase 把上游新提交"垫到下面"，我的改动重放在最上面。
#       冲突只会出现在我改过的文件里 (MainActivity / BambuMifareOperator 等)，
#       数量少且位置固定，人工看一眼就能解决。
#
# 用法:
#   ./sync-upstream.sh              # 完整流程: fetch -> rebase -> 编译
#   ./sync-upstream.sh --check      # 只检查是否有新版本，不动工作区
#   ./sync-upstream.sh --no-build   # 合并但不编译
# =============================================================================
set -uo pipefail

cd "$(dirname "$0")" || exit 1

BRANCH="feature/pn532-ble"
UPSTREAM=""      # 运行时自动选择: origin(Gitee, 直连) -> gh-upstream(GitHub, 需代理)
CHECK_ONLY=0
DO_BUILD=1

for arg in "$@"; do
  case "$arg" in
    --check)    CHECK_ONLY=1 ;;
    --no-build) DO_BUILD=0 ;;
    -h|--help)  sed -n '2,20p' "$0"; exit 0 ;;
  esac
done

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[警告] %s\033[0m\n' "$*"; }
err()  { printf '\033[1;31m[错误] %s\033[0m\n' "$*"; }

# --- 1. 抓取上游 ------------------------------------------------------------
# 基准仓库是 Gitee (origin)：国内直连，实测 ls-remote 0.27s，不需要代理。
# GitHub (gh-upstream) 作为降级备选 —— 它和 Gitee 同步同一份代码，
# 但国内访问需要代理，代理不通时自动回落。
UPSTREAM=""
for remote in origin gh-upstream; do
  if git ls-remote --exit-code "$remote" HEAD >/dev/null 2>&1; then
    UPSTREAM="$remote"
    break
  fi
  warn "远端 $remote 不可达，尝试下一个"
done

if [ -z "$UPSTREAM" ]; then
  err "所有远端都不可达，请检查网络/代理"
  exit 1
fi

say "抓取上游 $UPSTREAM"
if ! git fetch "$UPSTREAM" --tags --prune; then
  err "fetch 失败"
  # GitHub 需要代理，若恰好选中的远端是它，提示切换
  if [ "$UPSTREAM" = "gh-upstream" ]; then
    echo "    GitHub 需要代理才能访问，可临时执行:"
    echo "    git config http.proxy http://127.0.0.1:7890"
    echo "    git config https.proxy http://127.0.0.1:7890"
  fi
  exit 1
fi

BASE=$(git merge-base HEAD "$UPSTREAM/master")
UP_SHA=$(git rev-parse --short "$UPSTREAM/master")
LOCAL_SHA=$(git rev-parse --short HEAD)
BEHIND=$(git rev-list --count HEAD.."$UPSTREAM/master")
AHEAD=$(git rev-list --count "$UPSTREAM/master"..HEAD)

echo "    上游 master : $UP_SHA"
echo "    本地分支    : $LOCAL_SHA"
echo "    共同祖先    : $(git rev-parse --short "$BASE")"
echo "    上游领先    : $BEHIND 个提交"
echo "    本地领先    : $AHEAD 个提交 (PN532 改动)"

if [ "$BEHIND" -eq 0 ]; then
  say "上游没有新提交，无需合并"
  if [ "$CHECK_ONLY" -eq 0 ] && [ "$DO_BUILD" -eq 1 ]; then
    say "仍执行一次编译，确保当前代码可用"; 
  else
    exit 0
  fi
else
  echo
  echo "上游新增提交:"
  git log --oneline --no-decorate "$BASE".."$UPSTREAM/master" | sed 's/^/    /'
fi

[ "$CHECK_ONLY" -eq 1 ] && { say "仅检查模式，退出"; exit 0; }

# --- 2. 确保工作区干净 ------------------------------------------------------
if [ -n "$(git status --porcelain)" ]; then
  warn "工作区有未提交改动，先 stash"
  git stash push -u -m "sync-upstream auto-stash" || { err "stash 失败"; exit 1; }
  STASHED=1
else
  STASHED=0
fi

# --- 3. rebase 上游 ---------------------------------------------------------
say "把 $UPSTREAM/master rebase 到本地分支 $BRANCH"
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
  echo "  1) 查看冲突:   grep -n -A5 '^<<<<<<<' <冲突文件>"
  echo "  2) 编辑文件，删掉 <<<<<<< / ======= / >>>>>>> 标记"
  echo "     通常策略: 保留 PN532 相关改动 (HEAD 侧) + 采纳上游新功能"
  echo "     整个文件取我的版本: git checkout --ours <文件>"
  echo "     整个文件取上游版本: git checkout --theirs <文件>"
  echo "  3) 标记已解决: git add <文件>"
  echo "  4) 继续:       git rebase --continue"
  echo "  5) 想放弃:     git rebase --abort"
  echo
  echo "解完后重新执行本脚本即可继续编译。"
  exit $RC
fi

# --- 4. 恢复 stash ----------------------------------------------------------
if [ "$STASHED" -eq 1 ]; then
  say "恢复之前 stash 的改动"
  git stash pop || warn "stash pop 有冲突，请手动处理"
fi

# --- 5. 编译 ----------------------------------------------------------------
if [ "$DO_BUILD" -eq 1 ]; then
  say "编译 APK (JDK: JBR / Temurin，Gradle 离线)"
  export JAVA_HOME="G:/windows/workbuddy/toolchain/jbr/jbrsdk_jcef-21.0.10-windows-x64-b1163.105"
  export GRADLE_USER_HOME="G:/windows/workbuddy/toolchain/gradle-home"
  export ANDROID_HOME="G:/windows/workbuddy/toolchain/android-sdk"
  GRADLE="/g/windows/workbuddy/toolchain/gradle-9.3.1/bin/gradle"

  # 上游的 gradle/gradle-daemon-jvm.properties 要求 JetBrains 厂商 JDK，
  # 本机已预置官方 JBR SDK (21.0.10) 在仓库外的 toolchain 目录，
  # 通过 gradle-home/gradle.properties 的 installations.paths 引入，
  # 仓库文件保持上游原样 —— 这样 rebase 时不会产生额外冲突。
  "$GRADLE" assembleDebug --offline \
    || { err "编译失败，见上方日志"; exit 1; }

  APK="app/build/outputs/apk/debug/app-debug.apk"
  if [ -f "$APK" ]; then
    say "编译成功"
    ls -la "$APK"
    echo
    echo "安装到手机:"
    echo "    /g/windows/workbuddy/toolchain/android-sdk/platform-tools/adb.exe \\"
    echo "        -s 192.168.100.199:39295 install -r -t $APK"
  fi
fi

say "完成"
