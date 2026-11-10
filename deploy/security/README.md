# Tenant 与 Runtime 通信密钥配置

Tenant 只保存 `TENANT_MCP_ROOT_SECRET`（Tenant MCP 根密钥）。每个客户 Runtime 只保存由该根密钥、
`SAAS_TENANT_ID`（租户编号）和 `ENTERPRISE_MCP_INSTANCE_ID`（Runtime 实例编号）派生的
`TENANT_MCP_TOKEN`（当前实例通信 Token）。根密钥不得发送到客户服务器。

## 1. 生成并保管 Tenant 根密钥

首次部署 Tenant 时生成一次，之后所有重启和升级必须保持不变，并纳入 Secret 备份：

```powershell
$bytes = [byte[]]::new(32)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

```bash
openssl rand -base64 32
```

将输出仅配置为 Tenant 进程的 `TENANT_MCP_ROOT_SECRET`（Tenant MCP 根密钥）。轮换该根密钥会同时
使所有 Runtime Token 和已保存员工 API 凭证失效，当前版本不提供在线双密钥轮换。

## 2. 为每个 Runtime 生成实例 Token

在可信运维环境执行，脚本只输出实例 Token：

Bash 脚本需要 `openssl` 和 POSIX `od`；PowerShell 脚本无需额外依赖。

```powershell
$env:TENANT_MCP_ROOT_SECRET = '<Tenant 根密钥>'
$env:SAAS_TENANT_ID = '10000'
$env:ENTERPRISE_MCP_INSTANCE_ID = 'tenant-10000-mcp-20001'
.\generate-tenant-mcp-token.ps1
```

```bash
export TENANT_MCP_ROOT_SECRET='<Tenant 根密钥>'
export SAAS_TENANT_ID='10000'
export ENTERPRISE_MCP_INSTANCE_ID='tenant-10000-mcp-20001'
./generate-tenant-mcp-token.sh
```

把输出的 `TENANT_MCP_TOKEN`（当前实例通信 Token）写入该 Runtime 的 Secret 或 `container.env`。
同一租户的不同 Runtime 必须分别生成；不得复制其他实例的 Token。

仓库根目录外的 `saas/start_saas_tenant.sh`（Tenant 启动脚本）会校验并传入
`TENANT_MCP_ROOT_SECRET`（Tenant MCP 根密钥）；`saas/start_mcp.sh`（Runtime 启动脚本）会校验并传入
`SAAS_TENANT_ID`（租户编号）、`ENTERPRISE_MCP_INSTANCE_ID`（Runtime 实例编号）和
`TENANT_MCP_TOKEN`（当前实例通信 Token）。运行脚本前必须在宿主机 Secret 或环境中设置这些值。

## 3. 升级注意事项

旧配置 `TENANT_MCP_UPGRADE_TOKEN`（旧升级 Token）、`ENTERPRISE_MCP_UPGRADE_TOKEN`（旧 Runtime
升级 Token）、`TENANT_MCP_SERVICE_TOKEN`（旧服务通信 Token）和
`TENANT_API_CREDENTIAL_ENCRYPTION_KEY`（旧凭证加密密钥）均不再读取。

历史 `v1.` 员工 API 凭证依赖旧独立密钥，不能在移除旧密钥后自动解密。升级前查询
`tnt_employee_api_credential.credential` 是否存在 `v1.%`；存在时必须先保留旧版本完成一次性迁移，
或升级后由租户管理员重新录入这些凭证。新密文以 `v2.` 开头，并绑定租户和 MCP Server 记录。
