/**
 * update-projects.groovy — 免重启维护 perf-platform 的 project 下拉
 *
 * 数据源：config/projects.txt（compose 挂载到容器 /opt/perf-platform/config/projects.txt）
 * 语义：整表幂等设定（非追加），重复执行结果一致；不碰 script/csvFile 级联与其余参数。
 *
 * 用法：Manage Jenkins → Script Console → 整段粘贴执行。
 * 预期输出：OK:[, <项目1>, <项目2>, ...]（首项空 = 强制人工选择）
 *
 * 兼容性（实测坑，勿简化）：
 *   Jenkins ≥2.568 已移除 ChoiceParameterDefinition(String, List, String) 构造器，
 *   直接传 List 报 GroovyRuntimeException。改用 String 构造后，空首项是否保留因版本
 *   解析行为而异——故做变更前自校：getChoices() 与目标不符即回退 XSTREAM2 精确构建，
 *   仍不符则 FAIL 并 return，绝不带着错误状态写任务。
 */
import jenkins.model.Jenkins
import hudson.model.ParametersDefinitionProperty
import hudson.model.ChoiceParameterDefinition

final String JOB    = "perf-platform"
final String CONFIG = "/opt/perf-platform/config/projects.txt"

def f = new File(CONFIG)
if (!f.isFile()) { println "FAIL: 未找到 ${CONFIG}（compose 的 ./config 挂载是否就位？）"; return }

def desired = ['']                       // 首项空白 = 强制人工选择
f.readLines('UTF-8').each { line ->
    def t = line.trim()
    if (t && !t.startsWith('#')) desired << t
}
if (desired.size() < 2) { println "FAIL: 清单无任何有效项目（至少填一行；首项空白为内置）"; return }

def job = Jenkins.get().getItem(JOB)
if (job == null) { println "FAIL: 任务 ${JOB} 不存在（init.groovy.d/30-seed-jobs.groovy 是否已执行？）"; return }
def pdp = job.getProperty(ParametersDefinitionProperty)
if (pdp == null) { println "FAIL: 任务无参数定义块"; return }
def old = pdp.getParameterDefinition("project")
if (old == null) { println "FAIL: project 参数不存在"; return }

def np = new ChoiceParameterDefinition("project", desired.join("\n"), old.getDescription())
if (np.getChoices() != desired) {
    println "WARN: String 构造器结果不符（疑空首项被吞），回退 XSTREAM2 精确构建"
    def esc = { s -> s.replace("&", "&amp;").replace("<", "&lt;") }
    def sb = new StringBuilder()
    sb << '<hudson.model.ChoiceParameterDefinition><name>project</name><description>'
    sb << esc(old.getDescription() ?: '') << '</description><choices class="java.util.ArrayList">'
    desired.each { sb << '<string>' << esc(it) << '</string>' }
    sb << '</choices></hudson.model.ChoiceParameterDefinition>'
    np = (ChoiceParameterDefinition) Jenkins.XSTREAM2.fromXML(sb.toString())
}
if (np.getChoices() != desired) { println "FAIL: 选项不符: " + np.getChoices(); return }

def defs = new ArrayList(pdp.getParameterDefinitions())
defs[defs.indexOf(old)] = np
job.removeProperty(ParametersDefinitionProperty)
job.addProperty(new ParametersDefinitionProperty(defs))
job.save()
println "OK:" + np.getChoices()
