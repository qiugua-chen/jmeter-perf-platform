#!/bin/bash
# =============================================================================
# scripts-sync.sh — 发布式脚本同步模板（脱敏版）
# 核心思想：tag 即发布。master 日常提交不打 tag 不生效——
# 杜绝"定时任务踩到半成品脚本"，tag 是"已评审可执行版本"的法定标记。
# =============================================================================
set -euo pipefail

WORK="${WORK:-/opt/jmeter/.sync/repo}"   # 容器内克隆工作副本（可用环境变量覆盖，便于测试/自定义部署路径）
TARGET="${TARGET:-/opt/jmeter/scripts}" # JMeter 运行时读取的目录（覆盖时须与构建命令 SCRIPTS_DIR 保持一致）

# ---------- 凭据（GIT_TOKEN 环境变量注入；模板经 docker-compose .env → 容器环境传递） ----------
# GIT_URL 可整体覆盖：适配 GitLab/自建 git/SSH 等任意远端（默认 gitee 风格 HTTPS）
: "${GIT_TOKEN:?未注入 GIT_TOKEN 凭据}"
GIT_URL="${GIT_URL:-https://${GIT_USER:-git}:${GIT_TOKEN}@git.example.com/group/perf-scripts.git}"

# ---------- 1. 获取仓库 ----------
mkdir -p "$(dirname "$WORK")"
[ ! -d "$WORK/.git" ] && git clone --quiet "$GIT_URL" "$WORK"
# --force：tag 被修正/重新定位后需强制更新（否则 fetch 报 would clobber existing tag）
git -C "$WORK" fetch --quiet --tags --force origin \
    || { echo "❌ git fetch 失败（网络/凭据）"; exit 1; }

# ---------- 2. 解析发布版本：只认 round/* tag ----------
TAG="${RELEASE_TAG:-}"
[ -z "$TAG" ] && TAG=$(git -C "$WORK" tag -l 'round/*' | sort -V | tail -1)
[ -z "$TAG" ] && { echo "❌ 仓库不存在 round/* tag"; exit 1; }
git -C "$WORK" checkout --quiet -f "$TAG"

# ---------- 3. 镜像同步（复制 + 差集删除 = 单向一致） ----------
for sub in performance common monitors; do
    mkdir -p "$TARGET/$sub"
    cp -a "$WORK/$sub/." "$TARGET/$sub/"
    # 差集删除：目标有而源没有的文件 → 自动清理（服务器端手工改动会被清除）
    (cd "$TARGET/$sub" && find . -type f | sort) > "/tmp/.t.$$"
    (cd "$WORK/$sub"    && find . -type f | sort) > "/tmp/.s.$$"
    comm -23 "/tmp/.t.$$" "/tmp/.s.$$" | while read -r f; do
        echo "    清理多余文件: $sub/$f"; rm -f "$TARGET/$sub/$f"
    done
    rm -f "/tmp/.t.$$" "/tmp/.s.$$"
done

# ---------- 4. 版本标记：任何报告可溯源到脚本版本 ----------
echo "$TAG" > "$TARGET/.sync-version"
echo "✅ 同步完成: $TAG"
