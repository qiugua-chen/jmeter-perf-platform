// Job DSL：手动路径生成 perf-platform 构建任务（自动路径见 init.groovy.d/30-seed-jobs.groovy）
// 用法：Jenkins 新建 freestyle 任务 "seed" → 构建步骤 "Process Job DSLs" → 指向本文件
// 注意：DSL 无法完整表达 uno-choice CascadeChoiceParameter，执行后 script/csvFile 仍是普通参数，
//       需按部署指南第 4 节用 Groovy 原生 API 补建级联（自动路径无此问题）。

freestyleJob('perf-platform') {
    description('''【作用】性能压测统一入口：调度 → 传参 → 门禁 → 报告中心
【使用】选 project/script/csvFile（三级级联，默认空白必须全选）→ 填并发/时长/启动 → 构建
【结果】门禁明细/过程数据/错误样本（报告中心）
【兜底】超时强杀/构建仅留最近50次''')
    logRotator { numToKeep(50) }
    parameters {
        choiceParam('project', ['', 'demo-shop', 'demo-order'], '项目名（必选，级联 script/csvFile；实装替换为你的项目清单——首选改 config/projects.txt 走自动路径，本 DSL 为手动备选）')
        stringParam('script', '', '脚本名（不使用则留空；级联补建后变为下拉）')
        stringParam('threads', '', '并发数（脚本内 ${__P(threads)} 消费）')
        stringParam('duration', '', '压测时长(秒)')
        stringParam('ramp_up', '', '启动时间(秒)')
        stringParam('csvFile', '', 'CSV 文件名（不使用则留空或写 null；级联补建后变为下拉）')
        booleanParam('is_nightly', false, '夜间定时标记（报告中心打标用）')
        stringParam('app_version', '', '被测版本号（可选，溯源用）')
    }
    steps {
        // 平台脚本由 docker-compose 挂载到 /opt/perf-platform（只读）
        shell('bash /opt/perf-platform/build-command.sh')
    }
    triggers {
        // 定时参数化调度（示例注释，格式：cron % key=value;key=value）
        parameterizedCron('''
# 0 2 * * * % is_nightly=true;project=demo-shop;script=demo-shop.jmx;csvFile=users.csv;threads=5;duration=300;ramp_up=30
''')
    }
}
