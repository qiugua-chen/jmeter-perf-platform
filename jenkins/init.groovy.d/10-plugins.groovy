/**
 * 10-plugins.groovy — 首启装插件（init.groovy.d 按文件名序执行：10 → 20 → 30）
 *
 * 为什么用 init.groovy.d 而非常见的 docker 环境变量装插件？
 *   本项目 Dockerfile 复用官方 jenkins 基础镜像的 entrypoint，插件装好后必须"立即可用"——
 *   30-seed-jobs.groovy 在同一个启动流程里就要 new CascadeChoiceParameter 的类。
 *   deploy(true) 动态加载让插件类在当前 JVM 立即可见，无需重启。
 *
 * 注意：更新中心元数据需联网拉取；纯离线环境本脚本会打印警告后跳过，
 *       可在有网机器构建镜像（元数据已缓存）或按部署指南手动上传 .hpi。
 */
import jenkins.model.Jenkins

def jenkins = Jenkins.get()
def pm = jenkins.pluginManager
def uc  = jenkins.updateCenter

// ---------- 等更新中心元数据就绪（最多 5 分钟） ----------
def site = uc.getSite("default")
long deadline = System.currentTimeMillis() + 5 * 60 * 1000
while (site.getDataTimestamp() == null && System.currentTimeMillis() < deadline) {
    Thread.sleep(1000)
}
if (site.getDataTimestamp() == null) {
    println "init.groovy.d: ⚠️ 更新中心元数据未就绪（离线环境？），跳过全部插件安装"
    return
}

// ---------- 插件清单 ----------
// 各司其职，缺一平台即不完整：
//   uno-choice:2.8.9        级联参数（script←project、csvFile←project+script）。
//                           版本钉死：2.8.x 系我们验证过 CascadeChoiceParameter 构造器签名，
//                           新版本可能改 API，升级前先看 30-seed-jobs.groovy 的 new 调用
//   parameterized-scheduler 定时参数化调度（cron 行 % k=v 格式）
//   job-dsl                 手动路径：seed-job.dsl 生成任务（自动建任务失败时的备选）
//   git                     后续任务接 SCM 检出 / Jenkinsfile 时用
def plugins = [
    "uno-choice:2.8.9",
    "parameterized-scheduler",
    "job-dsl",
    "git",
]

plugins.each { spec ->
    def parts = spec.split(":", 2)
    def name  = parts[0]
    def ver   = parts.length > 1 ? parts[1] : null

    def installed = pm.getPlugin(name)
    if (installed != null && installed.isActive()) {
        println "init.groovy.d: 插件已存在，跳过 ${name}"
        return
    }

    def plugin = (ver != null)
        ? uc.getPlugin(name, new hudson.util.VersionNumber(ver))
        : uc.getPlugin(name)
    if (plugin == null) {
        println "init.groovy.d: ⚠️ 更新中心不存在 ${spec}（版本下线？），跳过"
        return
    }

    println "init.groovy.d: 安装插件 ${spec} ..."
    try {
        plugin.deploy(true).get()   // dynamicLoad=true：同 boot 内立即可用；get() 阻塞至装完
        println "init.groovy.d: ✅ ${spec} 安装完成"
    } catch (Throwable t) {
        // 不中断启动：30-seed-jobs.groovy 对 uno-choice 有就绪检查并会给出补救指引
        println "init.groovy.d: ❌ ${spec} 安装失败: ${t.getMessage()}"
    }
}
