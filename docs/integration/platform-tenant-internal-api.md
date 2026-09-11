# Platform 与 Tenant 内部接口文档

## 1. 文档范围

本文档基于以下代码整理：

- `platform` 服务：`TenantInnerController`、`BaseInnerController`
- `tenant` 服务：`AgentInnerController`

这里仅覆盖服务之间以及 MCPServer 等内部调用方会使用到的内部接口，不包含管理后台接口和租户前台接口。

## 2. 通用约定

### 2.1 统一响应结构

除 `tenant` 服务的 `GET /tenant/employee_permission` 外，其余内部接口均使用统一响应结构：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {}
}
```

字段说明：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `code` | `int` | 响应状态码，`0` 表示成功，非 `0` 表示失败 |
| `msg` | `string` | 响应提示信息，成功时固定为 `ok` |
| `data` | `object / array / null` | 业务数据，无返回内容时通常为 `null` |

### 2.2 时间字段格式

文档中的 `LocalDateTime` 字段由 Spring 默认序列化，通常表现为 ISO-8601 格式，例如：

```json
"2026-08-10T15:30:00"
```

### 2.3 状态字段约定

不同服务中的状态值含义不同，调用方需要区分：

| 字段 | 服务 | 取值 | 含义 |
| --- | --- | --- | --- |
| `status` | `platform` 租户状态 | `1` / `2` | `1` 表示启用，`2` 表示禁用 |
| `enabled` | `tenant` 员工或规则状态 | `1` / `0` | `1` 表示启用，`0` 表示停用 |
| `status` | `tenant` 员工权限快照响应 | `ENABLED` / `DISABLED` | 根据员工 `enabled` 转换后的文本状态 |

## 3. Platform 服务内部接口

服务代码位置：

- `D:\Codes\Origin-Lightyear\saas\server\platform\src\main\java\com\kevin\saas\controller\tenant\TenantInnerController.java`
- `D:\Codes\Origin-Lightyear\saas\server\platform\src\main\java\com\kevin\saas\controller\tenant\BaseInnerController.java`

### 3.1 校验租户管理员登录

- 方法：`POST`
- 路径：`/inner/tenant/login-check`
- 认证要求：`@AllowAnonymous`
- 用途：校验租户登录手机号和密码，并返回租户主数据，供 `tenant` 服务完成登录准入

请求体：

```json
{
  "mobile": "13800138000",
  "pwd": "12345678"
}
```

请求字段说明：

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | --- | --- |
| `mobile` | `string` | 是 | 租户登录手机号，同时也是 Platform 侧识别租户身份的账号 |
| `pwd` | `string` | 是 | 租户登录原始密码，由 Platform 内部完成校验 |

成功响应：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "id": 1,
    "name": "测试租户",
    "linkman": "张三",
    "mobile": "13800138000",
    "planId": 2,
    "empNum": 100,
    "status": 1,
    "authEndTime": "2026-12-31T23:59:59"
  }
}
```

### 3.2 查询租户内部配置

- 方法：`GET`
- 路径：`/inner/tenant/config/{id}`
- 认证要求：`@AllowAnonymous`
- 用途：按租户 ID 读取 LLM 与 MCP 相关配置，供 `tenant` 服务拉取平台侧租户配置

路径参数说明：

| 参数 | 类型 | 必填 | 含义 |
| --- | --- | --- | --- |
| `id` | `long` | 是 | Platform 租户 ID |

成功响应：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "id": 1,
    "llmKey": "sk-xxx",
    "llmUrl": "https://example.com/v1",
    "mcpUrl": "https://tenant-mcp.example.com",
    "status": 1,
    "empNum": 100,
    "authEndTime": "2026-12-31T23:59:59",
    "version": 8
  }
}
```

### 3.3 递增租户配置版本

- 方法：`POST`
- 路径：`/inner/tenant/{id}/version`
- 认证要求：`@AllowAnonymous`
- 用途：通知 Platform 将指定租户的配置版本号加一，供下游缓存或配置同步机制感知变更

路径参数说明：

| 参数 | 类型 | 必填 | 含义 |
| --- | --- | --- | --- |
| `id` | `long` | 是 | Platform 租户 ID |

成功响应：

```json
{
  "code": 0,
  "msg": "ok",
  "data": null
}
```

### 3.4 查询 NewAPI 全局配置

- 方法：`GET`
- 路径：`/inner/base/newapi/config`
- 认证要求：`@AllowAnonymous`
- 用途：读取 Platform 统一维护的 NewAPI 全局配置，供 `tenant` 服务访问下游 NewAPI

成功响应：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "baseUrl": "https://newapi.example.com",
    "key": "downstream-key"
  }
}
```

## 4. Tenant 服务内部接口

服务代码位置：

- `D:\Codes\Origin-Lightyear\saas\server\tenant\src\main\java\com\kevin\saas\controller\permission\AgentInnerController.java`

这些接口主要供 MCPServer、下游业务服务或其他内部调用方使用。

### 4.1 查询员工有效权限快照

- 方法：`GET`
- 路径：`/tenant/employee_permission`
- 认证要求：`@AllowAnonymous`
- 用途：按租户和员工读取当前有效权限快照，供 MCPServer 做权限判断
- 特别说明：该接口不使用 `ApiResult` 包装，直接返回 JSON 对象

查询参数说明：

| 参数 | 类型 | 必填 | 含义 |
| --- | --- | --- | --- |
| `tenantId` | `long` | 是 | Platform 租户 ID |
| `employeeId` | `long` | 是 | 租户下员工 ID |

成功响应示例：

```json
{
  "employeeId": "1001",
  "status": "ENABLED",
  "versionNumber": 1754812800,
  "departmentInfo": {
    "departmentName": "研发部"
  },
  "permissionConfig": {
    "tenantId": 1,
    "employeeId": 1001,
    "employeeCode": "E1001",
    "employeeName": "李四",
    "departmentId": 10,
    "departmentName": "研发部",
    "roles": [],
    "denyRules": [],
    "sensitiveRules": [],
    "ruleVersion": 1754812800,
    "enabled": 1
  }
}
```

### 4.2 员工鉴权

- 方法：`POST`
- 路径：`/tenant/auth`
- 认证要求：`@AllowAnonymous`
- 用途：校验员工手机号和密码，并返回该手机号下可登录的全部租户信息
- 特别说明：当前接口不再要求传入 `tenantId`；服务会根据手机号查询所有匹配员工，再按密码过滤

请求体：

```json
{
  "mobile": "13800138000",
  "password": "Passw0rd"
}
```

请求字段说明：

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | --- | --- |
| `mobile` | `string` | 是 | 员工登录手机号 |
| `password` | `string` | 是 | 员工原始登录密码 |

成功响应示例：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "mobile": "13800138000",
    "tenants": [
      {
        "tenantId": 1,
        "tenantName": "测试租户A",
        "employeeId": 1001,
        "employeeCode": "E1001",
        "employeeName": "李四",
        "departmentName": "研发部",
        "enabled": 1,
        "llmKey": "nk-tenant-a",
        "llmUrl": "https://newapi.example.com"
      },
      {
        "tenantId": 2,
        "tenantName": "测试租户B",
        "employeeId": 2001,
        "employeeCode": "E2001",
        "employeeName": "李四",
        "departmentName": "交付部",
        "enabled": 1,
        "llmKey": "nk-tenant-b",
        "llmUrl": "https://newapi.example.com"
      }
    ]
  }
}
```

`data` 字段说明：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `mobile` | `string` | 本次用于鉴权的手机号 |
| `tenants` | `array<object>` | 该手机号下密码匹配成功、且员工状态允许登录的租户列表 |

`tenants[]` 元素字段说明：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `tenantId` | `long` | Platform 租户 ID |
| `tenantName` | `string` | 租户名称 |
| `employeeId` | `long` | 员工 ID |
| `employeeCode` | `string` | 员工编码 |
| `employeeName` | `string` | 员工姓名 |
| `departmentName` | `string` | 员工部门名称 |
| `enabled` | `int` | 员工启用状态，`1` 表示启用 |
| `llmKey` | `string` | 该员工在 NewAPI 侧的 API Key |
| `llmUrl` | `string` | NewAPI 基础地址 |

常见失败响应：

```json
{
  "code": 20206,
  "msg": "账号或密码错误",
  "data": null
}
```

### 4.3 员工自助修改密码

- 方法：`POST`
- 路径：`/tenant/employee/change-password`
- 认证要求：`@AllowAnonymous`
- 用途：供下游业务服务在员工完成登录识别后，调用内部接口校验原密码并修改新密码
- 特别说明：接口同时兼容下划线命名和驼峰命名字段

请求体示例一：

```json
{
  "tenant_id": 1,
  "employee_id": 1001,
  "old_pwd": "Passw0rd",
  "new_pwd": "NewPass123",
  "repeat_pwd": "NewPass123"
}
```

请求体示例二：

```json
{
  "tenantId": 1,
  "employeeId": 1001,
  "oldPwd": "Passw0rd",
  "newPwd": "NewPass123",
  "repeatPwd": "NewPass123"
}
```

请求字段说明：

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | --- | --- |
| `tenant_id` / `tenantId` | `long` | 是 | Platform 租户 ID |
| `employee_id` / `employeeId` | `long` | 是 | 员工 ID |
| `old_pwd` / `oldPwd` | `string` | 是 | 原登录密码 |
| `new_pwd` / `newPwd` | `string` | 是 | 新登录密码 |
| `repeat_pwd` / `repeatPwd` | `string` | 是 | 再次输入的新登录密码 |

密码规则：

- 新密码必须为 8 到 20 位
- 新密码必须同时包含字母和数字
- 仅允许启用状态员工修改密码

成功响应：

```json
{
  "code": 0,
  "msg": "ok",
  "data": null
}
```

常见失败响应：

```json
{
  "code": 20214,
  "msg": "原密码输入错误",
  "data": null
}
```

```json
{
  "code": 20215,
  "msg": "两次输入的新密码不一致",
  "data": null
}
```

```json
{
  "code": 20213,
  "msg": "密码必须为 8 到 20 位字母和数字组合",
  "data": null
}
```

### 4.4 查询员工 LLM 配置

> Enterprise MCP 的云端 Control Plane 调用本接口，按 `tenantId + employeeId` 获取请求级 LLM 配置。
> 客户 Runtime 不拉取、不缓存也不向云端传递员工 LLM Key。

- 方法：`POST`
- 路径：`/api/v1/agent/inner/employee-llm-config`
- 认证要求：`@AllowAnonymous`
- 用途：供 Control Plane 读取指定员工可用的 LLM 配置

当前 `@AllowAnonymous` 依赖受控服务网络，禁止直接暴露公网。生产环境必须在 API Gateway 或 Tenant 服务补充
mTLS/服务身份 Token，并限制 Control Plane 只能查询其有权服务的 `tenantId`。

请求体：

```json
{
  "tenantId": 1,
  "employeeId": 1001
}
```

请求字段说明：

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | --- | --- |
| `tenantId` | `long` | 是 | Platform 租户 ID |
| `employeeId` | `long` | 是 | 员工 ID |

成功响应：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "apiKey": "nk-xxx",
    "newapiUrl": "https://newapi.example.com"
  }
}
```

### 4.5 上报权限审计日志

- 方法：`POST`
- 路径：`/tenant/permission-audit`
- 认证要求：`@AllowAnonymous`
- 用途：接收 MCPServer 的权限审计结果并落库，便于后续追溯

请求体示例：

```json
{
  "tenantId": 1,
  "employeeId": 1001,
  "requestMessage": "导出全部客户手机号",
  "planSummary": {
    "steps": [
      "查询客户表",
      "导出手机号"
    ]
  },
  "reviewResult": {
    "riskLevel": "high",
    "reason": "命中禁止规则"
  },
  "decision": "deny",
  "matchedRules": [
    {
      "ruleId": 11,
      "ruleName": "禁止导出客户手机号"
    }
  ],
  "modelVersion": "gpt-5-2026-08"
}
```

请求字段说明：

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | --- | --- |
| `tenantId` | `long` | 是 | Platform 租户 ID，缺失时直接返回参数错误 |
| `employeeId` | `long` | 是 | 发起请求的员工 ID，缺失时直接返回参数错误 |
| `requestMessage` | `string` | 否 | 员工原始请求内容 |
| `planSummary` | `object / array / string` | 否 | 执行计划摘要，服务端会序列化后保存 |
| `reviewResult` | `object / array / string` | 否 | 权限审查结果，服务端会序列化后保存 |
| `decision` | `string` | 是 | 审查决策，例如 `allow`、`deny` |
| `matchedRules` | `object / array / string` | 否 | 命中规则摘要，服务端会序列化后保存 |
| `modelVersion` | `string` | 否 | 参与审查的模型版本标识 |

成功响应：

```json
{
  "code": 0,
  "msg": "ok",
  "data": null
}
```

## 5. 调用关系简表

### 5.1 Tenant 调用 Platform

| 调用方 | 接口 | 作用 |
| --- | --- | --- |
| `PlatformTenantClient.loginCheck` | `POST /inner/tenant/login-check` | 校验租户登录，并同步拉取租户主数据 |
| `PlatformTenantClient.getConfig` | `GET /inner/tenant/config/{id}` | 拉取租户平台配置 |
| `PlatformTenantClient.getNewApiConfig` | `GET /inner/base/newapi/config` | 拉取 NewAPI 全局配置 |
| `PlatformTenantClient.bumpVersion` | `POST /inner/tenant/{id}/version` | 通知 Platform 递增租户版本号 |

### 5.2 MCPServer / 下游服务调用 Tenant

| 调用方 | 接口 | 作用 |
| --- | --- | --- |
| MCPServer | `GET /tenant/employee_permission` | 获取员工权限快照 |
| MCPServer / 下游服务 | `POST /tenant/auth` | 校验员工身份并返回可登录租户列表 |
| 下游服务 | `POST /tenant/employee/change-password` | 校验原密码并修改员工登录密码 |
| Control Plane | `POST /api/v1/agent/inner/employee-llm-config` | 按租户和员工获取请求级 LLM 配置 |
| MCPServer | `POST /tenant/permission-audit` | 上报权限审计记录 |
