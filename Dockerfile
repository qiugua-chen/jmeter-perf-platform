# syntax=docker/dockerfile:1
# =============================================================================
# jmeter-perf-platform 一体化镜像：Jenkins (LTS, JDK17) + JMeter 5.6.3
#
#   - JMeter 版本 + sha512 双钉死，构建可复现（哈希经清华/阿里双镜像交叉验证）
#   - 国内加速：apt 源(阿里云) / JMeter 安装包(清华 TUNA → 阿里云 → Apache 归档回退)
#   - 镜像源全部 ARG 化（JMETER_MIRRORS / PM_MIRRORS / APT_MIRROR），海外用户构建时可
#     用 --build-arg 重排或替换（详见 README「海外网络构建」）；APT_MIRROR 置空即跳过改写
#   - 运行时依赖补齐：python3（build-command 的报告页/门禁依赖）、中文字体（HTML 报告）
#   - JMeter 插件：管理器 2.0 预置 + 构建期预装 jpgc-casutg（阶梯/并发线程组）
#   - Jenkins 自身插件安装由 jenkins/init.groovy.d/ 负责，此处不重复
# =============================================================================

ARG JENKINS_IMAGE=jenkins/jenkins:lts-jdk17
# hadolint ignore=DL3006 —— 版本经 ARG 钉死（默认 lts-jdk17；升级改 ARG 或 --build-arg），
# 变量形式的 FROM 无法被 hadolint 静态识别，并非未锁版本
FROM ${JENKINS_IMAGE}

# RUN 内使用 bash（进程替换拆分镜像列表）+ pipefail（多 RUN 块内嵌管道）；
# 显式声明（默认 /bin/sh 不支持进程替换；pipefail 进 SHELL 选项免去逐块 set）
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

ARG JMETER_VERSION=5.6.3
# apache-jmeter-5.6.3.tgz 的 sha512（清华/阿里双镜像 2026-11 交叉验证一致）
ARG JMETER_SHA512=5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083
ARG PM_VERSION=2.0
# 预装 JMeter 插件 id（空格分隔；构建时 --build-arg JMETER_PLUGINS="..." 可覆盖）
ARG JMETER_PLUGINS="jpgc-casutg"
# 下载源列表（空格分隔，按序尝试；海外用户可重排，如把 archive.apache.org 放首位）
ARG JMETER_MIRRORS="https://mirrors.tuna.tsinghua.edu.cn/apache/jmeter/binaries https://mirrors.aliyun.com/apache/jmeter/binaries https://archive.apache.org/dist/jmeter/binaries"
ARG PM_MIRRORS="https://maven.aliyun.com/repository/public https://repo1.maven.org/maven2"
# apt 源镜像（置空字符串 = 不改写，保持基础镜像默认源）
ARG APT_MIRROR=mirrors.aliyun.com

USER root

# ---------- 1. apt 加速（可关）+ 运行时依赖 ----------
#    python3   ：build-command.sh 的 UTF-8 查看页/门禁统计依赖（基础镜像不含）
#    中文字体  ：HTML 报告含中文事务名时防方框；DejaVu 兼修 headless 图表缺字体 NPE
RUN set -euxo pipefail; \
    if [ -n "${APT_MIRROR}" ]; then \
        for f in /etc/apt/sources.list /etc/apt/sources.list.d/debian.sources; do \
            if [ -f "${f}" ]; then \
                sed -i "s|deb.debian.org|${APT_MIRROR}|g; s|security.debian.org|${APT_MIRROR}|g" "${f}"; \
            fi; \
        done; \
    fi; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        python3 fontconfig fonts-dejavu fonts-noto-cjk; \
    rm -rf /var/lib/apt/lists/*

# ---------- 2. JMeter：多源回退下载（可覆盖）+ sha512 强校验 ----------
RUN set -euxo pipefail; \
    tgz_name="apache-jmeter-${JMETER_VERSION}.tgz"; \
    tgz="/tmp/${tgz_name}"; \
    ok=""; \
    while IFS= read -r base; do \
        [ -n "${base}" ] || continue; \
        echo "==> 尝试下载: ${base}/${tgz_name}"; \
        if curl -fsSL --connect-timeout 15 --retry 2 -o "${tgz}" "${base}/${tgz_name}"; then ok=1; break; fi; \
    done < <(printf '%s' "${JMETER_MIRRORS}" | tr ' ' '\n'); \
    [ -n "${ok}" ] || { echo "❌ JMeter 全部镜像源下载失败"; exit 1; }; \
    echo "${JMETER_SHA512}  ${tgz}" | sha512sum -c -; \
    tar -xzf "${tgz}" -C /opt; \
    mv "/opt/apache-jmeter-${JMETER_VERSION}" /opt/jmeter; \
    rm -f "${tgz}"; \
    mkdir -p /opt/jmeter/scripts; \
    chown -R jenkins:jenkins /opt/jmeter

# ---------- 3. JMeter 插件管理器（多源回退，可覆盖） ----------
RUN set -euxo pipefail; \
    jar_name="jmeter-plugins-manager-${PM_VERSION}.jar"; \
    jar="/tmp/${jar_name}"; \
    ok=""; \
    while IFS= read -r base; do \
        [ -n "${base}" ] || continue; \
        echo "==> 尝试下载: ${base}/kg/apc/jmeter-plugins-manager/${PM_VERSION}/${jar_name}"; \
        if curl -fsSL --connect-timeout 15 --retry 2 -o "${jar}" "${base}/kg/apc/jmeter-plugins-manager/${PM_VERSION}/${jar_name}"; then ok=1; break; fi; \
    done < <(printf '%s' "${PM_MIRRORS}" | tr ' ' '\n'); \
    [ -n "${ok}" ] || { echo "❌ 插件管理器下载失败"; exit 1; }; \
    mv "${jar}" /opt/jmeter/lib/ext/; \
    chown jenkins:jenkins "/opt/jmeter/lib/ext/${jar_name}"

USER jenkins

# ---------- 4. 构建期预装 JMeter 插件 ----------
#    注意：构建期需可达 jmeter-plugins.org 插件仓库（解析依赖元数据与下载）
RUN set -eux; \
    if [ -n "${JMETER_PLUGINS}" ]; then \
        /opt/jmeter/bin/PluginsManagerCMD.sh install ${JMETER_PLUGINS} \
            || { echo "❌ JMeter 插件安装失败: ${JMETER_PLUGINS}"; exit 1; }; \
    fi

ENV JMETER_HOME=/opt/jmeter \
    PATH="/opt/jmeter/bin:${PATH}"

EXPOSE 8080
# 首启可能伴随插件初始化较慢，start-period 给到 180s
HEALTHCHECK --interval=30s --timeout=10s --start-period=180s --retries=5 \
    CMD curl -fsS http://localhost:8080/login >/dev/null || exit 1
