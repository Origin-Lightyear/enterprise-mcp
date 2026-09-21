# Agent 与 MCP 认证调用

## 安全边界

租户 RSA 私钥只保存在 Platform，由 Platform 在收到 Tenant 的可信服务间请求后完成签名。Tenant
负责员工登录态和身份透传，MCP Runtime 只持有对应租户的公钥及其指纹。Agent 不获取私钥，
也不能自行构造可信的租户、员工或 Runtime 实例身份。

生产环境必须在 Platform 与 Tenant 同时配置同一个 `AGENT_SIGNING_SHARED_SECRET`，并使用 TLS
保护 Tenant 到 Platform 的内部调用。该共享密钥只用于 Tenant 调用 Platform 内部接口时的服务
认证，不是租户 RSA 私钥，不能下发给 Agent。

每个 Runtime 必须通过 `SAAS_AGENT_PUBLIC_KEY_PEM` 和
`SAAS_AGENT_PUBLIC_KEY_FINGERPRINT` 注入所属租户公钥，应用不提供示例公钥默认值。Runtime
泄露公钥不会获得签发能力；只有持有私钥的 Platform 能生成可通过 Runtime 校验的签名。

## 调用顺序

### 1. 员工登录

Agent 调用 Tenant：

```http
POST /api/v1/agent/inner/auth
Content-Type: application/json

{"mobile":"13800000000","password":"employee-password"}
```

返回的每个 `tenants` 条目都包含与该租户和员工绑定的 `agentSessionToken` 与
`agentSessionExpiresAt`。Agent 选择租户后，只保存对应条目的 Session。

### 2. 查询当前租户的 MCP 接入清单

Agent 使用所选租户对应的 Session 调用 Tenant：

```http
GET /api/v1/agent/inner/system-settings/endpoints
Authorization: AgentSession <agentSessionToken>
```

Tenant 只从已验证的 Session 中读取租户和员工身份，不接受 Agent 传入租户 ID。返回前，Tenant
会从 Platform 同步该租户的最新 MCP 清单。响应示例：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "tenantId": 1,
    "mcpServers": [
      {
        "id": 10,
        "platformMcpId": 4,
        "instanceId": "mcp-1-b7074066-680e-45f3-b421-835542d349db",
        "name": "AI集成平台",
        "url": "http://10.69.188.230:6001",
        "sortOrder": 1,
        "healthStatus": "HEALTHY",
        "healthMessage": null,
        "checkedAt": "2026-09-19T09:10:00",
        "platformLatestVersion": "1.0.0",
        "platformReleaseNotes": null,
        "platformDownloadUrl": null,
        "platformFileHash": null,
        "installedVersion": "1.0.0",
        "hasUpdate": false
      }
    ],
    "ragUrl": null
  }
}
```

Agent 调用时必须遵守以下规则：

- 使用 `instanceId` 申请该 MCP 的一次性调用凭证，不得自行构造实例标识。
- 使用同一列表项的 `url` 作为目标 MCP 地址，不能将一个 MCP 的凭证发送给另一个地址。
- `mcpServers` 为空表示当前租户没有可用 MCP；Agent 应展示空状态，不应继续申请凭证。
- 健康状态用于界面提示和调用前判断，不参与签名；实际调用仍以 Runtime 返回结果为准。
- 不缓存列表中的 `instanceId` 和 `url` 作为永久配置，应在登录、切换租户或需要刷新时重新查询。

带租户路径参数的旧接口：

```http
GET /api/v1/agent/inner/system-settings/tenants/{tenantId}/endpoints
```

只供 Platform、MCP 等后端服务间调用，Agent Client 不得调用。

### 3. 申请一次性 MCP Header

Agent 选中 MCP 后，使用列表返回的 `instanceId` 调用 Tenant：

```http
POST /api/v1/agent/inner/mcp-credentials
Authorization: AgentSession <agentSessionToken>
Content-Type: application/json

{"mcpInstanceId":"mcp-1-b7074066-680e-45f3-b421-835542d349db"}
```

Tenant 从 Session 提取可信的 `tenantId` 和 `employeeId`，再通过以下 Platform 内部接口申请签名：

```http
POST /inner/agent/mcp-credentials
X-Internal-Service-Token: <AGENT_SIGNING_SHARED_SECRET>
```

Agent 不得直接调用该 Platform 内部接口，也不能接触 `X-Internal-Service-Token`。Platform 会校验
租户状态、目标实例归属和租户私钥，然后返回签名结果给 Tenant。

Tenant 返回示例：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "headers": {
      "Authorization": "<rsa-signature>",
      "Tenant": "<base64-encoded-tenant-context>",
      "Mcp-Instance-Id": "mcp-1-b7074066-680e-45f3-b421-835542d349db",
      "Timestamp": "1789780297",
      "Expires-At": "1789780357",
      "Nonce": "<one-time-random-value>"
    },
    "expiresAt": 1789780357
  }
}
```

`data.headers` 包含必须原样转发给 MCP Runtime 的六个 Header：

```text
Authorization
Tenant
Mcp-Instance-Id
Timestamp
Expires-At
Nonce
```

默认凭证有效期为 60 秒，且每个 `Nonce` 只能成功使用一次。Agent 不应缓存或重用这些 Header。
MCP 列表接口不能提前返回签名 Header，否则用户选择和真正发起调用之间的等待很容易导致凭证过期，
同时也会为未实际发生的调用生成无意义的一次性 Nonce。

### 4. 调用 MCP

Agent 将 `data.headers` 全部附加到同一列表项 `url` 指向的 MCP HTTP 请求。不得修改 Header 值，
也不得补充由 Agent 自己生成的租户或员工身份。Runtime 会依次校验签名时间窗口、租户、当前
Runtime 实例、RSA 签名、Nonce 重放、租户状态以及员工启用状态，任一失败均拒绝执行。

每次独立的 MCP HTTP 调用都应申请一组新 Header。因超时尚未发送、凭证已过期或 Nonce 已使用时，
应重新申请凭证，不得使用旧 Header 重试。

### 5. 注销和失效

```http
POST /api/v1/agent/inner/logout
Authorization: AgentSession <agentSessionToken>
```

Session 默认有效 12 小时。员工改密、停用或管理员修改员工资料时，会话代次递增，既有 Session
立即失效。旧的 `X-Tenant-Id`、`X-Employee-Id` 身份 Header 不再被 Agent 接口接受。

## 接口失败处理

Tenant 的 Agent 接口使用统一响应结构，客户端必须判断响应体 `code`，不能只判断 HTTP 状态：

| 场景 | 客户端处理 |
|---|---|
| `code=20314`，Agent Session 已失效 | 清除当前 Session，要求员工重新登录并选择租户 |
| MCP 清单同步 Platform 失败 | 不使用旧实例标识申请凭证，提示服务暂时不可用并允许重新查询列表 |
| `mcpServers` 为空 | 展示当前租户未配置可用 MCP，不发起凭证申请 |
| `code=20315`，MCP 凭证签发失败 | 不调用 Runtime；刷新 MCP 列表后重新申请，持续失败时提示服务不可用 |
| Runtime 返回凭证过期 | 为同一 MCP 重新申请一组 Header，再重试尚未执行的请求 |
| Runtime 返回 Nonce 重放或签名不合法 | 不复用旧 Header；重新申请一次，持续失败时停止重试并记录诊断信息 |

自动重试前必须确认原 MCP 请求尚未成功执行。对于可能产生写操作的 MCP Tool，不能在执行结果
未知时盲目重试，避免重复写入。

## 尚未覆盖的设备证明

当前 Session 能证明员工已通过账号密码登录，但无法从密码已泄露或 Session 被窃取的场景中证明
调用进程一定是官方 Agent。若需要设备级防伪，Agent 客户端还需生成不可导出的设备密钥，Tenant
再把 Session 绑定到设备公钥并对每次申请执行挑战签名。
