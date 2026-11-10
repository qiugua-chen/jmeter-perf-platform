# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 规范，版本号遵循语义化版本（平台自身用 `v*`，与用户脚本的 `round/*` 发布 tag 互不相关，见 README「版本约定」）。

## [1.0.0] - 2026-11-10

首个公开版本。

### 新增

- **一体化镜像**（`Dockerfile`）：Jenkins (LTS JDK17) + JMeter 5.6.3，安装包版本/哈希双钉死；apt / JMeter / 插件管理器镜像源全部 ARG 可覆盖（海外构建友好）
- **首启自动化**（`jenkins/init.groovy.d/` 三件套）：装插件（uno-choice 2.8.9 / parameterized-scheduler / job-dsl / git，已兼容 Jenkins ≥2.568 构造器变更）→ admin 密码按 `.env` 同步 → 自动创建 perf-platform 与 perf-scripts-sync 任务（project/script/csvFile 三级级联参数）
- **发布式脚本同步**（`scripts/scripts-sync.sh`）：仅 `round/*` tag 生效；单向镜像 + 差集自净；`.sync-version` 版本标记
- **构建命令**（`scripts/build-command.sh`）：空值快速失败、CSV 三种合规写法、JMeter keep-alive 修复、超时强杀兜底、错误率门禁（FAILED ≠ 运行失败，报告始终完整）
- **报告中心**（`scripts/report-gen/`）：UTF-8 查看页、错误分组明细、门禁判定三独立模块
- **project 清单单一事实源**（`config/projects.txt`）+ 免重启维护脚本（`jenkins/update-projects.groovy`，整表幂等、空清单拒绝写入）
- **demo 资产**：demo-shop（CSV 参数化）/ demo-order（无 CSV）双示例 + monitors 监控清单样例
- **静态检查 CI**（`.github/workflows/ci.yml`）：shellcheck / py_compile / JMX XML / Groovy 括号平衡 / hadolint / compose 校验
- **下载者验收清单**（`docs/验证清单.md`）：A→G 七组逐项勾选，含排障锚点
