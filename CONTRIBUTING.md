# 贡献指南

感谢关注。本项目定位明确：**轻量、可自托管、治理优先**——提交前请先确认方向一致（不追求功能大而全，拒绝破坏"tag 即发布"等核心约束的改动）。

## 报告问题（Issue）

新建 Issue 请附上：

1. `docs/验证清单.md` 执行到哪一步、哪一项失败
2. 环境信息：操作系统 / Docker 版本 / Jenkins 镜像标签（`docker images | grep jmeter-perf-platform`）
3. 关键日志：
   - 首启问题：`docker logs perf-jenkins | grep init.groovy.d` 的完整输出
   - 构建问题：控制台输出中的 ❌ 行（错误率类业务问题请先自查被测环境，不属平台缺陷）

**先自查**：部署指南各节"排障锚点"与验证清单对应章节，多数首启问题可在 5 分钟内自行定位。

## 提交改动（Pull Request）

- **CI 全绿是合并前提**（shellcheck / py_compile / JMX XML / Groovy 括号平衡 / hadolint / compose 校验，见 `.github/workflows/ci.yml`）
- Shell 脚本保持 shellcheck 0 警告；新增 Python 模块须过 `py_compile`
- 改动 Jenkins 参数结构（`jenkins/init.groovy.d/`）：PR 描述里必须写明回拉验证方式（config.xml 检查点、ScriptApproval 检查），这是本类改动历史上翻车最多的地方
- 镜像源、默认值等"环境相关"改动：保持 ARG 可覆盖原则，只改默认值不改死
- 文档与代码同 PR；图片资源 ≤500KB/张，不得包含内网地址、真实项目名等敏感信息

## 维护者操作备忘（仓库设置）

- **Topics**：`jmeter` `jenkins` `performance-testing` `load-testing` `ci-cd`
- **Description**：基于 Jenkins + JMeter 的轻量级性能自动化平台——tag 发布式脚本治理 / 三级级联参数 / 门禁与报告中心
- **版本发布流程**：CHANGELOG 加条目 → 打 `v*` tag → GitHub Release 粘贴对应条目
- **分支保护**：main 开启 PR 评审 + 状态检查必过；直接 push 到 main 仅供维护者紧急修复
- 用户脚本仓库（使用者 fork 后自建）与本模板仓库的版本号互不相关，见 README「版本约定」
