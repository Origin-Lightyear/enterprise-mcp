# Enterprise-mcp 首次部署、升级回滚与技术栈保护方案

## 1. 结论先行

Enterprise-mcp 部署在客户自有 Linux 机器上时，无法做到“绝对防止反编译”。客户如果拥有 root 权限，就可以读取磁盘、抓取运行时内存、替换启动参数，甚至调试进程。因此方案目标应定义为：

1. 不向客户交付源码、调试信息和构建映射文件。
2. 使用代码混淆、裁剪和签名校验提高静态逆向成本。
3. 将最有价值的算法、规则和密钥能力放在 Platform 或自有服务，客户侧只保留必要执行层。
4. 用 Linux 权限、systemd 沙箱、签名安装包和许可证限制降低复制与篡改风险。
5. 保证升级失败可自动回滚，且升级器本身不因业务进程故障而失效。

不要把“加密 JAR”当作核心防护。只要程序能够在客户机器上运行，解密密钥最终也必须在客户机器或进程内出现，具备足够权限的客户仍然可以提取它。

## 2. 当前实现的真实边界

当前目录下的部署工具已经实现了版本目录、manifest、SHA-256、current/previous 软链接、systemd 和健康检查，但仍有以下边界：

- `bootstrap` 是 root 脚本，负责首次安装。
- `updater` 是 root 脚本，负责远程下载、本地版本切换、重启和回滚。
- Enterprise-mcp 主进程由独立非 root 用户运行。
- 当前升级 Controller 使用主进程用户直接启动 updater，而 updater 要求 root。若没有额外的受限提权配置，一键升级会被 updater 的 root 检查拒绝。
- 当前 Platform 记录的 SHA-256 只能证明下载文件与记录一致，不能证明记录本身来自可信发布者；正式方案必须增加数字签名。
- 当前脚本和 Java 类名、Spring Boot 运行方式、依赖信息仍然容易被客户观察到；这不满足“隐藏技术栈”的要求。

因此，现有脚本适合作为部署原型和内部交付工具，正式私有化交付前还需要补齐“受限提权、签名发布、构建保护和许可证”四部分。

## 3. 首次部署流程

### 3.1 Platform 发布安装包

每个租户的每个 MCP 都有独立的 `platformMcpId`。Platform 发布时生成一个不可变安装包，包内至少包含：

```text
manifest.json
enterprise-mcp.jar 或 enterprise-mcp 可执行文件
签名文件 signature.sig
```

建议 manifest 内容：

```json
{
  "platformMcpId": 20001,
  "version": "1.0.0",
  "artifact": "enterprise-mcp.jar",
  "artifactSha256": "...",
  "packageSha256": "...",
  "health": {
    "type": "HTTP",
    "path": "/internal/health",
    "expectedStatus": 200
  },
  "runtime": {
    "javaMajor": 21,
    "os": "linux",
    "arch": "x86_64"
  }
}
```

正式版本还应加入 `manifestVersion`、签名算法和发布者 key id。版本号、MCP 主键、jar 名称、健康检查配置一旦发布不可修改。

Platform 的发布顺序：

1. 编译生产包，不包含源码、测试类、调试符号和混淆映射文件。
2. 对 Java 字节码执行混淆和裁剪，或生成 native image。
3. 计算 jar 或可执行文件 SHA-256。
4. 生成 manifest。
5. 使用只存在于 CI 发布机的私钥对 manifest 和制品摘要签名。
6. 将 tar.gz、manifest、签名上传 OSS。
7. Platform 数据库保存版本、更新说明、下载地址、SHA-256 和签名 key id。
8. 发布后才同步 Tenant；Tenant 只收到已发布版本元数据。

### 3.2 客户现场准备

客户机器由交付人员以 root 执行安装前检查：

1. 操作系统为支持的 Linux 发行版。
2. CPU 架构与制品一致。
3. Java 21 可用，或者交付自带受控 JRE。
4. `jq`、`sha256sum`、`tar`、`systemctl`、`curl` 可用。
5. MCP 使用的企业数据库账号和网络策略已准备好。
6. MCP 对 Agent 的访问端口已规划，升级管理入口只允许 Tenant 后端或本机访问。
7. `/opt/enterprise-mcp`、`/etc/enterprise-mcp`、`/var/lib/enterprise-mcp` 的磁盘空间和权限满足要求。

### 3.3 bootstrap 脚本逐步流程

当前 `deploy/enterprise-mcp-bootstrap.sh` 的流程如下：

1. 解析 `instance-id`、`platform-mcp-id`、`tenant-id`、安装包和配置文件。
2. 检查必须由 root 执行，并校验 ID 格式。
3. 检查 jq、sha256sum、Java 21 和输入文件。
4. 如果调用方提供安装包 SHA-256，则先校验 tar.gz。
5. 创建临时目录并解压安装包。
6. 读取 `manifest.json`，校验 Platform MCP ID、语义化版本和 jarName。
7. 创建 `/opt/enterprise-mcp/<instance-id>/releases/<version>`。
8. 创建独立系统用户 `enterprise-mcp-<instance-id>`，将应用文件归属该用户。
9. 用 manifest 内的 `artifactSha256` 再校验 jar。
10. 将运行配置写入 `/etc/enterprise-mcp/<instance-id>/application.yml`。
11. 将租户 ID 和 Platform MCP ID 写入 root 可读的实例绑定文件。
12. 生成独立的 systemd unit，主进程以非 root 用户运行。
13. 将 `current` 指向首次安装版本。
14. 执行 `systemctl daemon-reload`，启用并启动服务。
15. 正式版本还必须执行启动后的健康检查，失败时删除本次安装目录和 unit，不能留下半安装状态。

首次部署成功后的目录：

```text
/opt/enterprise-mcp/<instance-id>/
  releases/1.0.0/enterprise-mcp.jar
  releases/1.0.0/manifest.json
  current -> releases/1.0.0
  previous 不存在或为空
/etc/enterprise-mcp/<instance-id>/
  application.yml
  instance.properties
/var/lib/enterprise-mcp/<instance-id>/
/etc/systemd/system/enterprise-mcp-<instance-id>.service
```

## 4. 升级流程

### 4.1 用户可见流程

1. Platform 为某个租户的某个 MCP 发布新版本。
2. Platform 将版本号、更新说明、下载地址、安装包哈希同步给 Tenant。
3. Tenant 管理员登录后台看到“有更新”和更新内容。
4. 管理员点击“一键更新”。
5. Tenant 后端向该 MCP 实例的管理入口提交升级任务。
6. Enterprise-mcp 校验升级 token、实例身份和请求参数。
7. 受限升级服务下载并校验安装包，完成版本切换。
8. Enterprise-mcp 返回升级任务状态；Tenant 刷新后显示成功版本或失败原因。

Tenant 保存的 URL 仍然是 Agent 使用的企业内部 MCP 地址。Enterprise-mcp 不会主动读取或拉取这个 URL；升级请求由 Tenant 后端发起。正式部署时建议 Agent 访问入口和升级管理入口使用不同监听地址，避免把 `/internal/upgrades` 暴露给 Agent 网络。

### 4.2 updater 脚本逐步流程

当前 `deploy/enterprise-mcp-updater.sh` 的远程升级流程：

1. 解析实例 ID、下载地址、目标版本、SHA-256 和目录参数。
2. 强制要求 root，校验实例 ID 和输入模式。
3. 检查当前实例已安装且 `current` 是软链接。
4. 读取当前 manifest，获得当前版本和当前 jarName。
5. 下载 Platform 指定的 tar.gz，并强制要求包 SHA-256。
6. 解压到临时目录，读取新 manifest。
7. 校验 Platform MCP ID 必须与实例绑定一致。
8. 校验版本号、jarName、健康检查类型和 jar 文件路径。
9. 校验 jar SHA-256，防止压缩包内文件被替换。
10. 创建新版本目录并复制 jar、manifest。
11. 将 `previous` 原子指向旧的 `current` 目标。
12. 将 `current` 原子切换到新版本目录。
13. 重启该实例对应的 systemd 服务。
14. 在超时时间内同时检查 systemd active 和 HTTP 健康探针。
15. 健康检查成功则保留新版本并返回成功。
16. 健康检查失败则将 `current` 切回旧目标，重启服务并返回失败。

本地回滚流程与远程升级相同，只是目标版本来自已安装的 `releases/<version>`，不重复下载。回滚版本必须先校验为语义化版本，不能直接拼接未校验的路径。

### 4.3 正式版本必须补充的升级状态

当前接口返回 `202 Accepted` 后即结束，Tenant 不能立即知道最终安装结果。建议增加：

```text
POST /internal/upgrades
GET  /internal/upgrades/{operationId}
```

状态至少包括：`QUEUED`、`DOWNLOADING`、`VERIFYING`、`SWITCHING`、`HEALTH_CHECKING`、`SUCCEEDED`、`ROLLED_BACK`、`FAILED`。状态文件放在 `/var/lib/enterprise-mcp/<instance-id>/upgrade-state.json`，由 root helper 写入、主进程只读。Tenant 保存最终成功版本到 `installed_version`，不能在任务提交时提前写入。

## 5. 权限与受限提权方案

### 5.1 推荐结构

不要让 Spring 主进程直接以 root 运行。推荐拆成三层：

```text
Tenant backend
    -> Enterprise-mcp management endpoint
        -> root-owned fixed helper
            -> systemctl/release switch/health rollback
```

主进程只负责鉴权、参数校验和提交任务。root helper 只接受固定参数或读取 root 拥有的任务文件，不接受任意 shell 命令、不接受任意脚本路径、不把请求体直接拼入 shell。

### 5.2 两种可选实现

推荐使用独立的 root systemd service：

- Enterprise-mcp 将升级任务写入受保护目录。
- 通过预先配置的 sudoers 规则只允许执行固定的 `enterprise-mcp-upgrade@.service`。
- 模板 service 读取固定实例配置，调用 root-owned updater。
- sudoers 规则不允许客户通过参数替换脚本、环境变量或工作目录。

也可以使用 root-owned Unix socket helper：

- helper 以 root 运行并监听 Unix socket。
- socket 权限只允许对应 Enterprise-mcp 用户访问。
- helper 对 JSON 请求做严格 schema 校验。
- helper 只允许白名单实例、版本和下载域名。

不建议使用 setuid Bash、允许执行任意 `sudo bash`，或把 `enterprise.upgrade.command` 配成可由外部配置覆盖的命令字符串。

## 6. 隐藏技术栈和防止反编译方案

### 6.1 交付物治理

CI 产出的客户包不得包含：

- Java 源码、测试类、源码 JAR。
- `*.map`、ProGuard/R8 mapping、调试符号。
- Maven `pom.xml`、依赖清单和内部仓库地址。
- 包含数据库密码、Platform token、签名私钥的配置。
- 开发环境默认配置和测试接口。

生产包使用独立的 `application-private.yml` 或环境文件，敏感值通过 root-only 文件、systemd credential 或外部密钥系统注入。

### 6.2 JVM 交付方案

如果继续使用 Spring Boot JAR，建议采用以下组合：

1. 使用 ProGuard、R8、DashO 或 Zelix 等工具混淆业务包名、类名、方法名和字段名。
2. 仅保留 Spring、MyBatis、MCP、序列化和反射需要的白名单；Spring Boot 启动类、Controller 映射、配置绑定类不能盲目改名。
3. 删除 debug attributes、LineNumberTable、SourceFile 和本地变量信息。
4. 对关键字符串做运行时拆分或加密，但不要把它当成主要防护。
5. mapping 文件只存放在 CI 的受控归档，不上传 OSS，不放在客户机器。
6. 构建完成后使用 `jdeps`、启动测试和 MCP 协议测试验证混淆包。

JVM 方案上线快、兼容性好，但客户仍可通过 JADX、CFR、FernFlower、调试器和运行时 instrumentation 进行分析。它的定位是提高成本，不是绝对保密。

### 6.3 Native image 方案

第二阶段可评估 GraalVM Native Image：

- 交付 Linux ELF 可执行文件，不再交付主要业务 JAR。
- 关闭调试符号，剥离符号表，限制可执行文件读取权限。
- 使用 Spring AOT、MCP 协议测试和现场数据库连接测试验证反射配置。
- 将 Java 运行时依赖从客户环境中移除，降低技术栈暴露。

Native image 仍然可以被逆向，只是逆向成本显著高于普通 JAR；同时构建时间、内存占用、Spring AI MCP 兼容性和动态代理配置需要专门做 POC，不能直接假设现有项目一次成功。

### 6.4 最有效的架构保护

真正重要的业务能力不要全部交付到客户机器：

- Platform 或自有云服务保存规则编排、授权策略、敏感算法和版本签发能力。
- Enterprise-mcp 只保留企业数据库连接、MCP 协议适配和必要的本地执行逻辑。
- 对高价值操作使用短期签名授权或在线许可证，许可证绑定租户、MCP 主键、机器指纹和有效期。
- 离线客户使用签名许可证文件，但许可证必须包含有效期、功能范围、实例 ID 和版本范围。
- 私钥永不进入 Enterprise-mcp、安装包、客户配置或脚本。

如果客户要求完全离线且拥有 root，只能承诺“提高逆向成本和防篡改”，不能承诺绝对防反编译。

## 7. 签名、下载和完整性校验

SHA-256 解决的是完整性，不解决发布者认证。正式发布链路应为：

1. CI 用发布私钥对 canonical manifest 签名。
2. Platform 保存并展示签名 key id、版本和摘要。
3. updater 内置 Platform 公钥或从 root-only 信任库读取公钥。
4. 下载后先校验包摘要，再校验 manifest 签名，再校验 manifest 中的 artifact 摘要。
5. 校验 MCP ID、版本、OS、CPU、jarName 和健康检查配置。
6. 禁止从任意 URL 下载，至少限制到 Platform 配置的 HTTPS 域名；更严格时使用一次性签名下载 URL。
7. 记录发布版本、操作者、下载结果、切换结果和回滚原因。

## 8. systemd 加固建议

每个 MCP 实例使用独立 unit 和独立用户，并增加：

```ini
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
PrivateDevices=true
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
ReadWritePaths=/var/lib/enterprise-mcp/<instance-id>
ReadOnlyPaths=/opt/enterprise-mcp/<instance-id>
```

需要根据企业数据库网络、日志目录和临时文件实际需求调整白名单。升级 helper 与业务 unit 分离，helper 不应继承业务进程的环境变量和工作目录。

## 9. 推荐落地顺序

### P0：先修复可用性和安全底座

- 增加 root helper 或受限 systemd 提权，确保一键升级可实际执行。
- 增加升级状态查询，Tenant 只在成功后更新 `installed_version`。
- 安装后首次健康检查和失败清理。
- 升级接口限制来源网络、token、下载域名和版本格式。

### P1：正式发布防篡改

- Platform 增加 manifest 签名和 key id。
- updater 校验签名、平台、架构和版本。
- OSS 下载统一使用 HTTPS 和短期签名 URL。
- 增加升级审计和失败原因记录。

### P2：隐藏技术栈

- 先做混淆 JAR 生产构建，完成 Spring 反射白名单和 MCP 协议回归。
- 客户包去除源码、调试信息、mapping 和构建元数据。
- 评估 GraalVM Native Image POC，验证 Spring Boot 4、Spring AI MCP、MyBatis、数据库连接和现有工具注册。

### P3：保护核心知识产权

- 把高价值规则和授权逻辑移到 Platform 或自有服务。
- 引入签名许可证、实例绑定和版本授权。
- 对离线客户提供受限能力和有效期明确的离线许可证。

## 10. 验收标准

- 首次安装成功后，业务进程不是 root，Agent 可以访问 MCP，管理入口不能被 Agent 直接调用。
- 非法 MCP ID、版本、jarName、签名、摘要、下载域名都无法安装。
- 升级成功后 `current`、systemd、HTTP 健康检查和 Tenant `installed_version` 一致。
- 新版本启动失败、健康检查超时或进程异常时自动回滚，旧版本仍可用。
- 手工回滚不会覆盖租户配置、数据库连接配置、日志和审计记录。
- 客户包中不存在源码、mapping、调试符号、私钥和明文生产密钥。
- 使用 JADX/CFR 对 JVM 包进行抽样逆向时，关键业务类名、方法名和字符串已混淆；Native image 方案另行完成逆向成本评估。
- root 客户的风险承诺明确写入交付合同：本方案提高逆向和篡改成本，不承诺绝对防止反编译。
