/**
 * 30-seed-jobs.groovy — 自动建任务（核心价值：消灭手工 Groovy）
 *
 * 每次启动都执行，幂等：任务不存在则创建，存在则把参数/保留策略/构建步骤同步为最新定义。
 *
 * 产出两个任务：
 *   perf-platform      压测统一入口（三级级联参数 + 构建命令 + 保留 50 次）
 *   perf-scripts-sync  发布式同步（round/* tag → /opt/jmeter/scripts 单向镜像）
 *
 * 关键实现约定（对应踩坑实录，勿改回 XML 方式）：
 *   ① 级联参数用 uno-choice CascadeChoiceParameter（Groovy 原生 API addProperty），
 *      空 referencedParameters = 非级联；勿用 updateByXml/POST config.xml 回写——
 *      XStream 会静默丢弃无法实例化的元素，且外部 XML 回写曾触发 500。
 *   ② 参数一律 removeProperty + addProperty 整组替换，不做元素级修补。
 *   ③ 级联脚本 setSandbox(false)：列目录需要读文件系统（等价 UI 取消 sandbox 勾选）。
 *   ④ 标准下拉勿用 (String, List, String) 构造器——Jenkins ≥2.568 已移除（GroovyRuntimeException）；
 *      用 String 构造 + getChoices() 自校（防空首项被吞），不符回退 XSTREAM2 精确重建（见 choice 助手）。
 */
import jenkins.model.Jenkins
import hudson.model.FreeStyleProject
import hudson.model.ParametersDefinitionProperty
import hudson.model.ChoiceParameterDefinition
import hudson.model.StringParameterDefinition
import hudson.model.BooleanParameterDefinition
import hudson.tasks.Shell
import hudson.tasks.LogRotator
import org.biouno.unochoice.CascadeChoiceParameter
import org.biouno.unochoice.model.GroovyScript

// ---------- 与 docker-compose 挂载、scripts-sync.sh / build-command.sh 内部约定保持一致 ----------
final String SCRIPTS_DIR  = "/opt/jmeter/scripts"   // 发布式同步的目标（jmeter_scripts 卷）
final String PLATFORM_DIR = "/opt/perf-platform"    // 平台自身脚本（./scripts 只读挂载）
final String PROJECTS_CONFIG = "${PLATFORM_DIR}/config/projects.txt"   // project 下拉数据源（首项空白内置；缺失回退 demo 占位）

def jenkins = Jenkins.get()

// ---------- 前置：等 uno-choice 动态加载完成（10-plugins.groovy 同 boot 内安装） ----------
def pm = jenkins.pluginManager
long deadline = System.currentTimeMillis() + 3 * 60 * 1000
while (System.currentTimeMillis() < deadline) {
    def uno = pm.getPlugin("uno-choice")
    if (uno != null && uno.isActive()) break
    Thread.sleep(1000)
}
def uno = pm.getPlugin("uno-choice")
if (uno == null || !uno.isActive()) {
    println "init.groovy.d: ❌ uno-choice 插件未就绪，跳过级联参数创建。插件就绪后重跑本脚本，或按部署指南第 4 节手动补建"
    return
}

// ---------- 级联参数构造助手 ----------
def cascade = { String name, String desc, String referenced, String body ->
    def gs = new GroovyScript(body, 'return [""]')   // 兜底脚本：异常时给空白选项，强制重新选择
    gs.setSandbox(false)                             // 读文件系统列目录需出沙箱
    new CascadeChoiceParameter(name, desc, "choice-${name}", gs, referenced, false, false, null)
}

// ---------- 项目清单读取（单一事实源 = config/projects.txt） ----------
// # 注释、空行忽略；文件缺失或无任何有效行 → 回退 demo 占位（保证首启下拉非空）
def readProjects = { ->
    def list = ['']
    def f = new File(PROJECTS_CONFIG)
    if (f.isFile()) {
        f.readLines('UTF-8').each { line ->
            def t = line.trim()
            if (t && !t.startsWith('#')) list << t
        }
    }
    if (list.size() < 2) list.addAll(['demo-shop', 'demo-order'])
    return list
}

// ---------- 标准下拉构造（Jenkins ≥2.568 兼容，见头部约定 ④） ----------
def choice = { String name, List<String> opts, String desc ->
    def np = new ChoiceParameterDefinition(name, opts.join('\n'), desc)
    if (np.getChoices() != opts) {
        println "init.groovy.d: ⚠️ ${name} String 构造器空首项丢失，XSTREAM2 重建"
        def esc = { s -> s.replace('&', '&amp;').replace('<', '&lt;') }
        def sb = new StringBuilder()
        sb << '<hudson.model.ChoiceParameterDefinition><name>' << esc(name) << '</name><description>'
        sb << esc(desc ?: '') << '</description><choices class="java.util.ArrayList">'
        opts.each { sb << '<string>' << esc(it) << '</string>' }
        sb << '</choices></hudson.model.ChoiceParameterDefinition>'
        np = (ChoiceParameterDefinition) Jenkins.XSTREAM2.fromXML(sb.toString())
    }
    return np
}

// script 级联 project：未选 project → 空；目录不存在 → 空；选项为裸文件名（.jmx）
final String SCRIPT_CASCADE = '''
def p = binding.hasVariable('project') ? project : ''
p = p.toString().trim()
if (!p) return ['']
def d = new File("/opt/jmeter/scripts/performance/${p}")
if (!d.isDirectory()) return ['']
def files = d.listFiles({ f -> f.isFile() && f.name.endsWith('.jmx') } as java.io.FileFilter)
return [''] + (files ?: []).collect { it.name }.sort()
'''

// csvFile 级联 project+script：任一未选 → 空；选项为裸文件名（.csv）
final String CSV_CASCADE = '''
def p = binding.hasVariable('project') ? project : ''
def s = binding.hasVariable('script') ? script : ''
p = p.toString().trim(); s = s.toString().trim()
if (!p || !s) return ['']
def d = new File("/opt/jmeter/scripts/common/${p}")
if (!d.isDirectory()) return ['']
def files = d.listFiles({ f -> f.isFile() && f.name.toLowerCase().endsWith('.csv') } as java.io.FileFilter)
return [''] + (files ?: []).collect { it.name }.sort()
'''

// ---------- 任务一：perf-platform ----------
def perf = jenkins.getItem("perf-platform")
if (perf == null) {
    perf = jenkins.createProject(FreeStyleProject, "perf-platform")
    println "init.groovy.d: 已创建任务 perf-platform"
}

perf.description = '''【作用】性能压测统一入口：调度 → 传参 → 门禁 → 报告中心
【使用】选 project/script/csvFile（三级级联，默认空白必须全选）→ 填并发/时长/启动 → 构建
【结果】门禁明细/过程数据/错误样本（报告中心）
【兜底】超时强杀/构建仅留最近 50 次'''

perf.setBuildDiscarder(new LogRotator(-1, 50, -1, -1))

def projects = readProjects()
perf.removeProperty(ParametersDefinitionProperty)
perf.addProperty(new ParametersDefinitionProperty([
    choice('project', projects,
        '项目名（必选，级联 script/csvFile；清单维护见 config/projects.txt）'),
    cascade('script', '脚本名（级联 project；未选 project 时为空）', 'project', SCRIPT_CASCADE),
    cascade('csvFile', 'CSV 文件名（级联 project+script；留空 = 不使用 CSV）', 'project,script', CSV_CASCADE),
    new StringParameterDefinition('threads', '', '并发数（脚本内 ${__P(threads)} 消费）'),
    new StringParameterDefinition('duration', '', '压测时长(秒)'),
    new StringParameterDefinition('ramp_up', '', '启动时间(秒)'),
    new BooleanParameterDefinition('is_nightly', false, '夜间定时标记（报告中心打标用）'),
    new StringParameterDefinition('app_version', '', '被测版本号（可选，溯源用）'),
]))

perf.buildersList.replaceBy([new Shell("""\
bash ${PLATFORM_DIR}/build-command.sh
""")])
perf.save()
println "init.groovy.d: perf-platform 就绪（级联参数/保留策略/构建命令已同步为最新定义）"

// ---------- 任务二：perf-scripts-sync ----------
def sync = jenkins.getItem("perf-scripts-sync")
if (sync == null) {
    sync = jenkins.createProject(FreeStyleProject, "perf-scripts-sync")
    println "init.groovy.d: 已创建任务 perf-scripts-sync"
}

sync.description = '''【作用】脚本发布单向镜像：Git 仓库 round/* tag → /opt/jmeter/scripts
【使用】手动触发；RELEASE_TAG 空 = 最新 tag，指定 = 回滚
【注意】① 只认 round/* tag（tag 人工打并推送，本任务只读凭据、永不自动打 tag）
② 镜像差集自动清理 → 勿手改服务器副本 ③ 完成后核对 .sync-version ④ 失败先看控制台 ❌ 行'''

sync.setBuildDiscarder(new LogRotator(-1, 20, -1, -1))

sync.removeProperty(ParametersDefinitionProperty)
sync.addProperty(new ParametersDefinitionProperty([
    new StringParameterDefinition('RELEASE_TAG', '', '发布 tag（round/*）；留空 = 取最新'),
]))

sync.buildersList.replaceBy([new Shell("""\
bash ${PLATFORM_DIR}/scripts-sync.sh
""")])
sync.save()
println "init.groovy.d: perf-scripts-sync 就绪"

// 定时调度按需追加（需 parameterized-scheduler，cron % key=value;key=value 格式），示例：
//   perf.addTrigger(new org.jenkinsci.plugins.parameterizedscheduler.ParameterizedTimerTrigger(
//       '0 2 * * * % is_nightly=true;project=demo-shop;script=demo-shop.jmx;csvFile=users.csv;threads=5;duration=300;ramp_up=30', null))
//   perf.save()

println "init.groovy.d: 全部任务初始化完成（project 选项来自 config/projects.txt，缺失时回退 demo 占位）"
