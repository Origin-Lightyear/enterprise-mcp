# 客户 MCP 单容器交付

开发环境继续使用 IDE/Maven 启动 customerapp，并通过现有 Wren launcher 启动本机 Adapter；无需安装 Docker。Docker 目录只负责测试、预发布和正式环境的不可变交付。

## 构建并导出发布包

`container.env` 只填写该租户/实例与其他租户不同的配置：租户 ID、实例 ID、对外端口和升级 token。
公共服务地址、Wren 内部路径、超时和版本不放在这里。版本直接读取
`application.yml` 的 `enterprise.runtime.artifact-version` 默认值，镜像构建后由镜像自身携带。
构建脚本不再重复接收版本和身份参数，也不接收 Platform 内部的 `platformMcpId`。
Wren Adapter 和管理端 token 在首次安装时自动生成，并只保存在 root 可读的部署配置中。

Linux/macOS：

```bash
./deploy/docker/build-container-package.sh \
  --env-file ./container.env
```

Windows PowerShell：

```powershell
.\deploy\docker\build-container-package.ps1 `
  -EnvFile .\container.env
```

输出为 `target/container-release/*.tar.gz` 和对应 `.sha256`。发布包已经包含 customerapp、管理前端、Wren Adapter、Wren Engine 和数据库驱动；Platform 只校验并存储该发布包，不再重新封装 JAR。

## 客户环境首次安装

客户 Linux 主机需要 Docker Engine、curl、jq、tar、sha256sum、flock 和 systemd。先从 `container.env.example` 复制并填写客户配置，再以 root 执行：

```bash
sudo ./deploy/docker/host/install-container.sh \
  --package ./enterprise-mcp-tenant-10000-mcp-20001-<version>.tar.gz \
  --sha256 <发布包SHA-256> \
  --env-file ./container.env
```

安装后只对外发布 customerapp 端口。Wren 仅监听容器内 `127.0.0.1:8091`。配置位于 `/etc/enterprise-mcp/<instance-id>/container.env`，Wren 模型、索引、审计和升级状态位于 `/var/lib/enterprise-mcp/<instance-id>/`，升级镜像不会覆盖这些数据。

## 一键升级与回滚

Platform 上传并发布 `.tar.gz` 后，Tenant 仍调用 Runtime 的 `/internal/upgrades`。容器内命令只把升级请求原子写入挂载目录；root-owned systemd path 触发宿主机 updater 下载和校验发布包、加载镜像、替换容器，并等待 `/internal/health` 同时确认 Runtime 与 Wren 可用。新容器失败时自动恢复旧容器。

查看状态：

```bash
cat /var/lib/enterprise-mcp/<instance-id>/upgrade-state.json
systemctl status enterprise-mcp-container-<instance-id>.path
docker logs enterprise-mcp-<instance-id>
```

业务容器不会挂载 `/var/run/docker.sock`，只有宿主机 root updater 可以操作 Docker。
