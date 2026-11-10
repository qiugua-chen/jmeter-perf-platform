#!/bin/bash
# =============================================================================
# build-command.sh — perf-platform 构建命令模板（脱敏版）
#
# 与实装版的差异：路径/域名/项目名全部占位符化；保留了全部治理逻辑骨架：
#   ① 参数强制选择（空白默认值 → 快速失败，不静默兜底）
#   ② CSV 三种合规写法（null / 空值 / 不写参数）
#   ③ JMeter keep-alive 修复（reset_state_on_thread_group_iteration=false）
#   ④ 超时强杀（防跑飞兜底）
#   ⑤ 报告中心（调用 scripts/report-gen/ 独立模块：UTF-8 查看页 + 错误明细）
#   ⑥ 门禁（错误率阈值 → 构建 FAILED，但报告完整）
#
# 路径约定（均可环境变量覆盖，便于本地验证与自定义部署）：
#   SCRIPTS_DIR  脚本仓库同步目标（与 scripts-sync.sh 的 TARGET 保持一致）
#   PLATFORM_DIR 平台自身脚本目录（report-gen 所在；compose 挂载 ./scripts）
# =============================================================================
set -uo pipefail

# Jenkins 以构建参数（环境变量）注入以下变量；此处集中声明默认值——
# ① shellcheck 可静态追踪（SC2154：Jenkins 注入的变量在脚本内无赋值点）
# ② 脱离 Jenkins 手工执行时行为可预期（SC2086 已由数组引用规避）
project="${project:-}"
script="${script:-}"
threads="${threads:-}"
duration="${duration:-}"
ramp_up="${ramp_up:-}"
csvFile="${csvFile:-}"

# ---------- 0. 参数校验：无意义默认值即错误 ----------
: "${project:?未选择 project}"
: "${script:?未选择 script}"
[ -z "${project}" ] && { echo "❌ 错误: 未选择项目"; exit 1; }
[ -z "${script}" ]  && { echo "❌ 错误: 未选择脚本"; exit 1; }
# 数值参数：duration 必填正整数（timeout 兜底依赖它）；threads/ramp_up 可空（脚本内
# ${__P(threads,默认值)} 自带兜底），但填写则必须是正整数——乱填会在算术处炸出难懂错误
[ -n "${duration:-}" ] || { echo "❌ 错误: 未填写 duration（压测时长秒数）"; exit 1; }
for v in duration threads ramp_up; do
    val="${!v:-}"
    if [ -n "${val}" ] && ! [[ "${val}" =~ ^[0-9]+$ ]]; then
        echo "❌ 错误: ${v} 必须为正整数（收到: '${val}'）"; exit 1
    fi
done

# ---------- 1. CSV 规范化（三种合规写法归一） ----------
csvFile=${csvFile:-}
NO_CSV=false
if [ -z "${csvFile}" ] || [ "${csvFile}" = "null" ]; then NO_CSV=true; fi

SCRIPTS_DIR="${SCRIPTS_DIR:-/opt/jmeter/scripts}"    # 占位：按部署环境调整
PLATFORM_DIR="${PLATFORM_DIR:-/opt/perf-platform}"   # report-gen 所在目录
SCRIPT_PATH="${SCRIPTS_DIR}/performance/${project}/${script%%/*}"
[[ "${script}" == */* ]] && SCRIPT_PATH="${SCRIPTS_DIR}/performance/${script}"
CSV_PATH="${SCRIPTS_DIR}/common/${project}/${csvFile}"

# ---------- 2. 预检 ----------
[ ! -f "${SCRIPT_PATH}" ] && { echo "❌ 脚本不存在: ${SCRIPT_PATH}"; exit 1; }
if [ "${NO_CSV}" = "false" ]; then
    [ ! -f "${CSV_PATH}" ] && { echo "❌ CSV 不存在: ${CSV_PATH}"; exit 1; }
fi

# ---------- 3. 执行 JMeter ----------
# -Jhttpclient.reset_state_on_thread_group_iteration=false：
#   JMeter 默认 true 会使 keep-alive 跨循环失效 → 高并发下 TIME_WAIT 端口耗尽
#   （500/s × 60s ≈ 3 万 > 容器 ~2.8 万端口，必现 BindException）
JMETER_OPTS=(
    -n -t "${SCRIPT_PATH}"
    -JcsvPath="${CSV_PATH}"          # 脚本内 CSV 路径约定走 ${__P(csvPath)}
    -Jthreads="${threads}" -Jduration="${duration}" -Jramp_up="${ramp_up}"
    -Jhttpclient.reset_state_on_thread_group_iteration=false
)
[ "${NO_CSV}" = "true" ] && unset 'JMETER_OPTS[2]'   # 无 CSV 不传 csvPath

timeout $((duration + 300)) jmeter "${JMETER_OPTS[@]}" -l result.jtl -e -o report/ \
    || { echo "❌ 压测执行失败"; exit 1; }            # timeout=防跑飞兜底

# ---------- 4. 报告中心（report-gen 独立模块） ----------
python3 "${PLATFORM_DIR}/report-gen/textpage.py" report/statistics.json result-kpi.html "门禁明细"
[ -f jmeter.log ] && python3 "${PLATFORM_DIR}/report-gen/textpage.py" jmeter.log jmeter-log.html "jmeter.log"
python3 "${PLATFORM_DIR}/report-gen/error-details.py" result.jtl errors.html "错误样本"

# ---------- 5. 门禁：错误率阈值 → FAILED ----------
# 门禁即状态：FAILED 表示"未过质量线"，不等于"运行失败"——报告始终完整产出
# 阈值环境变量可覆盖（任务/容器注入 GATE_ERR_MAX=1.5 即 1.5%）
GATE_ERR_MAX="${GATE_ERR_MAX:-0.5}"
python3 "${PLATFORM_DIR}/report-gen/gate.py" result.jtl "${GATE_ERR_MAX}"
