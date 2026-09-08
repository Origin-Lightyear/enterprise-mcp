# Enterprise MCP Linux 部署

本目录提供首期 Linux 部署工具。每个租户的每个 MCPServer 都应使用独立的 `instance-id`、目录、运行用户和 systemd 服务。

## 目录约定

```text
/opt/enterprise-mcp/<instance-id>/
  releases/<version>/
  current -> releases/<version>
  previous -> releases/<version>
  shared/
```

配置和状态分别放在 `/etc/enterprise-mcp/<instance-id>/` 与 `/var/lib/enterprise-mcp/<instance-id>/`，不会随版本包覆盖。

## 首次安装

```bash
sudo ./enterprise-mcp-bootstrap.sh \
  --instance-id tenant-10000-mcp-20001 \
  --platform-mcp-id 20001 \
  --tenant-id 10000 \
  --package /tmp/enterprise-mcp-1.0.0.tar.gz \
  --config /tmp/application.yml \
  --sha256 PACKAGE_SHA256
```

安装包根目录必须包含 `manifest.json` 和一个 Spring Boot jar。不同版本必须保持相同 `jarName`。安装前需要 `jq`、`sha256sum`、`tar`、`systemctl` 和 Java 21。

## 升级或回滚

```bash
sudo ./enterprise-mcp-updater.sh \
  --instance-id tenant-10000-mcp-20001 \
  --package /tmp/enterprise-mcp-1.1.0.tar.gz
```

`--sha256` 为 Platform 版本记录中的安装包 SHA-256；`manifest.json` 中的 `artifactSha256` 为 jar SHA-256。首期健康检查只支持 manifest 中的 `HTTP` 类型。

回滚使用已安装版本目录重新执行同一流程：

```bash
sudo ./enterprise-mcp-updater.sh \
  --instance-id tenant-10000-mcp-20001 \
  --version 1.0.0
```

updater 只接受 `manifest.json` 中的 Platform MCP 主键与目标版本和当前实例配置一致的包；切换后必须同时满足 systemd active 和健康探针成功，否则自动切回 previous。

## Tenant 一键升级调用

Tenant 后端向该 MCP 实例的管理入口发起调用，Enterprise-mcp 不读取 Tenant 的 MCP URL 配置，也不主动拉取 Platform 地址。管理入口为：

```text
POST /internal/upgrades
X-MCP-Upgrade-Token: <与 ENTERPRISE_MCP_UPGRADE_TOKEN 相同的值>
Content-Type: application/json
```

请求体包含 Platform 下发的 `downloadUrl`、`version` 和 `sha256`。Enterprise-mcp 只启动固定的 `enterprise-mcp-updater.sh`，不接受请求方传入任意 shell 命令。配置 `ENTERPRISE_MCP_INSTANCE_ID`、`ENTERPRISE_MCP_UPGRADE_TOKEN` 和 `ENTERPRISE_MCP_UPDATER_COMMAND` 后重启服务即可启用该入口。
