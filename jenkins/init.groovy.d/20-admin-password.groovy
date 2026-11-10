/**
 * 20-admin-password.groovy — 首启初始化管理员账号与安全策略
 *
 * 数据源：docker-compose.yml 注入的容器环境变量（.env 文件 → environment）
 *   JENKINS_ADMIN_ID        账号名，缺省 admin
 *   JENKINS_ADMIN_PASSWORD  初始密码（compose 中以 :? 强制必填，禁止弱密码启动）
 *
 * 幂等语义（每次启动都执行）：
 *   账号不存在 → 创建；账号已存在 → 按环境变量同步密码（即"重启改密改 .env 即可"）。
 *   密码以哈希落盘（$JENKINS_HOME/users/<id>/），明文只存在于容器环境变量。
 *
 * 安全模型：登录即全权 + 禁止匿名读。压测报告/门禁明细含内网拓扑与版本信息，
 * 不对匿名开放；多角色精细授权超出本平台定位（轻量级单团队平台），如需请替换
 * AuthorizationStrategy 为 Role-Based Strategy。
 */
import jenkins.model.Jenkins
import hudson.model.User
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy
import hudson.security.HudsonPrivateSecurityRealm

def env = System.getenv()
def adminId       = (env.JENKINS_ADMIN_ID ?: "admin").trim()
def adminPassword = env.JENKINS_ADMIN_PASSWORD ?: ""

if (!adminPassword.trim()) {
    println "init.groovy.d: ⚠️ 未注入 JENKINS_ADMIN_PASSWORD，跳过账号初始化（Jenkins 将保持无认证状态，仅限本地体验，勿用于生产）"
    return
}

def jenkins = Jenkins.get()
def realm = new HudsonPrivateSecurityRealm(false)   // false = 关闭自助注册

def existing = User.getById(adminId, false)
if (existing == null || existing.getProperty(HudsonPrivateSecurityRealm.Details) == null) {
    realm.createAccount(adminId, adminPassword)
    println "init.groovy.d: 已创建管理员账号 ${adminId}"
} else {
    try {
        existing.addProperty(HudsonPrivateSecurityRealm.Details.fromPlainPassword(adminPassword))
        println "init.groovy.d: 已按环境变量同步 ${adminId} 的登录密码"
    } catch (Throwable t) {
        println "init.groovy.d: 更新密码失败（${t.getMessage()}），保留 ${adminId} 原密码"
    }
}

jenkins.setSecurityRealm(realm)

def strategy = new FullControlOnceLoggedInAuthorizationStrategy()
strategy.setAllowAnonymousRead(false)   // 匿名不可读：报告含内网信息
jenkins.setAuthorizationStrategy(strategy)
jenkins.save()

println "init.groovy.d: 安全策略已启用（登录即全权，匿名不可读）"
