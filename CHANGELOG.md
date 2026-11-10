# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 规范，版本号遵循语义化版本（平台自身用 `v*`，与用户脚本的 `round/*` 发布 tag 互不相关，见 README「版本约定」）。

## [1.0.0] - 2026-11-10

首个公开版本。

### 新增

- **一体化镜像**（`Dockerfile`）：Jenkins (LTS JDK17) + JMeter 5.6.3，安装包版本/哈希双钉死；apt / JMeter / 插件管理器镜像源全部 ARG 可覆盖（海外构建友好）
- **首启自动化**（`jenkins/init.groovy.d/` 四件套）：装插件（uno-choice 2.8.9 / parameterized-scheduler / job-dsl / git，已兼容 Jenkins ≥2.568 构造器变更）→ admin 密码按 `.env` 同步 → demo 资产自动种子（25 号，零脚本状态触发）→ 自动创建 perf-platform 与 perf-scripts-sync 任务（project/script/csvFile 三级级联参数）
- **发布式脚本同步**（`scripts/scripts-sync.sh`）：仅 `round/*` tag 生效；单向镜像 + 差集自净；`.sync-version` 版本标记
- **构建命令**（`scripts/build-command.sh`）：空值快速失败、CSV 三种合规写法、JMeter keep-alive 修复、超时强杀兜底、错误率门禁（FAILED ≠ 运行失败，报告始终完整）
- **报告中心**（`scripts/report-gen/`）：UTF-8 查看页、错误分组明细、门禁判定三独立模块
- **project 清单单一事实源**（`config/projects.txt`）+ 免重启维护脚本（`jenkins/update-projects.groovy`，整表幂等、空清单拒绝写入）
- **demo 资产**：demo-shop（CSV 参数化）/ demo-order（无 CSV）双示例 + monitors 监控清单样例
- **静态检查 CI**（`.github/workflows/ci.yml`）：shellcheck / py_compile / JMX XML / Groovy 括号平衡 / hadolint / compose 校验 / gate.py 功能冒烟
- **下载者验收清单**（`docs/验证清单.md`）：A→G 七组逐项勾选，含排障锚点

## [1.0.1] - 2026-11-10

新用户视角 review 后的修复与打磨（发布当日）。

### 修复

- **CI 红点**：compose 校验步骤注入 dummy 的 `JENKINS_ADMIN_PASSWORD`（`:?` 强制插值在 CI 无 .env 时必失败）
- **开箱断点**：新增 `25-seed-demo-assets.groovy`，零脚本状态自动把 demo 资产种入运行时目录——happy path 不再依赖手工 `docker compose cp`；已有真实脚本时自动跳过
- **文档与实现矛盾**：`GIT_TOKEN` 改为"用默认 GIT_URL 模板时必填，显式覆盖 GIT_URL 可留空"（此前 .env.example 标注"可选"但脚本硬退出）
- **30-seed 等待偏紧**：uno-choice 就绪等待 3 分钟 → 8 分钟，超时提示补充"重启即自动补建"
- **参数健壮性**：`duration` 必填正整数，`threads/ramp_up` 可空但填写必须是正整数（此前乱填会在算术处炸出难懂错误）
- **CI 首跑 lint 对齐**：shellcheck SC2154（构建参数顶部集中声明默认值，消除"未赋值引用"）、hadolint DL4006×2/DL3006（SHELL 选项加 `-o pipefail`；DL3006 登记 .hadolint.yaml——行内 ignore 需紧贴指令行，脆弱）

### 变更

- 门禁阈值支持 `GATE_ERR_MAX` 环境变量覆盖（默认 0.5 不变）
- compose 增加 `TZ`（默认 Asia/Shanghai，海外可覆盖）；`ports` 增加仅本机绑定提示
- README/部署指南补充：30 号种子幂等会覆盖 UI 手工自定义参数的警告
- 验证清单 F 节断网实验标注可选；B 节增补 demo 种子验证项
