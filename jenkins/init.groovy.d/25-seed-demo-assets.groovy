/**
 * 25-seed-demo-assets.groovy — 首启把仓库自带 demo 资产种入运行时脚本目录
 *
 * 解决的开箱断点：级联参数扫描 /opt/jmeter/scripts/（jmeter_scripts 卷），而新装机
 * 该卷是空的 → project 能选出 demo-shop、script 却一片空白。demo 资产已在容器内
 * 只读挂载 /opt/perf-platform/（compose 的 ./scripts），本脚本在"零脚本"状态下
 * 把它种入运行时目录，使 happy path 无需任何手工步骤。
 *
 * 触发条件（all-or-nothing，保守策略）：
 *   /opt/jmeter/scripts 整棵树下没有任何 .jmx → 视为新装机，种子 performance/common/monitors；
 *   已有 .jmx → 说明真实脚本已就位或由 GitOps 接管，绝不触碰（含 common/monitors）。
 *
 * 幂等：每次启动都执行同一检查；种子只可能发生在"零脚本"状态，重复启动无操作。
 * 之后 scripts-sync.sh 的镜像差集会自然接管这三个目录（含清理 demo 文件）。
 */
import jenkins.model.Jenkins

final String SCRIPTS_DIR  = "/opt/jmeter/scripts"     // 运行时脚本目录（jmeter_scripts 卷）
final String PLATFORM_DIR = "/opt/perf-platform"      // 仓库 scripts/ 的只读挂载（种子来源）

def runtime = new File(SCRIPTS_DIR)
boolean hasScripts = false
if (runtime.isDirectory()) {
    runtime.eachFileRecurse { f ->
        if (f.isFile() && f.name.endsWith('.jmx')) hasScripts = true
    }
}
if (hasScripts) {
    println "init.groovy.d: ${SCRIPTS_DIR} 已有脚本，跳过 demo 资产种子（由脚本仓库/GitOps 接管）"
    return
}

['performance', 'common', 'monitors'].each { sub ->
    def src = new File("${PLATFORM_DIR}/${sub}")
    def dst = new File("${SCRIPTS_DIR}/${sub}")
    if (!src.isDirectory()) {
        println "init.groovy.d: ⚠️ 未找到 ${src}（compose 的 ./scripts 挂载是否就位？），跳过 ${sub}"
        return
    }
    dst.mkdirs()
    src.eachFileRecurse { f ->
        if (f.isFile()) {
            def rel = f.absolutePath.substring(src.absolutePath.length() + 1)
            def t = new File(dst, rel)
            t.parentFile?.mkdirs()
            t.bytes = f.bytes
        }
    }
    println "init.groovy.d: 已种子 demo 资产 ${sub} → ${dst}"
}
println "init.groovy.d: demo 资产就绪（级联参数现在能列出 demo-shop/demo-order）"
