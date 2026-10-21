# 金蝶能力迁入 CustomerApp：当前范围与验收边界

## 运行拓扑

Agent 调用 CustomerApp 公开元数据 MCP Tool，或调用 Runtime 内部 MCP Tool。公开探索经过 Agent/员工身份校验、权限审查、限流和本地审计；内部查询仍走云端规划、Catalog/制品绑定、Runtime 本地授权、员工权限审查、执行和本地审计。金蝶 SDK 与凭证只在客户侧。Control Plane 只保存不含业务行、凭证的结构知识快照，并按租户、Runtime 实例、Catalog 摘要、知识摘要查找。

Runtime 遇到不确定的 FormId、报表账簿或期间，通过 MCP Elicitation 要求用户选择，然后**完整重新规划**。接受的答案仅在当前请求内暂存；权限审查通过、最终计划仍使用已确认参数且执行成功后才写入账套知识。API 返回成功或非空数据不会自动升级为“符合用户意图”。客户端不支持 Elicitation 时失败关闭，不猜测继续查询。

## 当前配置

默认关闭。启用需在客户 Runtime 环境配置 `ENTERPRISE_MCP_K3_ENABLED=true`、`ENTERPRISE_MCP_K3_SERVER_URL`、`ENTERPRISE_MCP_K3_ACCT_ID`、`ENTERPRISE_MCP_K3_USERNAME`。应用密钥模式还需 `ENTERPRISE_MCP_K3_APP_ID`、`ENTERPRISE_MCP_K3_APP_SECRET`；密码模式需 `ENTERPRISE_MCP_K3_AUTH_MODE=password`、`ENTERPRISE_MCP_K3_PASSWORD`。可配置 `ENTERPRISE_MCP_K3_KNOWLEDGE_DIR` 与 `ENTERPRISE_MCP_K3_METADATA_CACHE_DIR`。启用后须完成 Runtime Catalog 注册及 Control Plane 的 V2 Flyway 迁移，才能发布知识摘要并规划。

知识存于客户本地 `data/k3/learned`，文件名由服务器地址和账套 ID 摘要生成；运维备份、清理和迁移须按账套隔离。知识不是权限授予，也不应含业务行或凭证。变更金蝶账套/凭证后应重新验证元数据与业务语义。

## 源项目覆盖矩阵

源 `D:\Codes\k3cloud_test` 的 `mcp/tools` 下有 97 个 `@McpTool` 声明。本次不是 97 个工具的等价移植。

| 源工具组 | 数量 | 当前 CustomerApp 状态 |
| --- | ---: | --- |
| CoreTools | 7 | 只读通用列表查询由 `k3.query-data` 覆盖一部分；其他未迁 |
| ReportTools | 1 | 通用报表查询由 `k3.query-report` 承接，仍需真实金蝶报表验证 |
| AssetWorkflowTools | 9 | 未迁 |
| CompositeTools | 3 | 未迁 |
| CostTools | 12 | 未迁 |
| DomainQueryTools | 8 | 未迁；不能假定通用查询等价于领域语义 |
| LifecycleTools | 12 | 未迁，写入状态机不开放 |
| LogTransferTools | 13 | 未迁 |
| ProductionTools | 12 | 未迁 |
| SupplyChainTools | 8 | 未迁 |
| SystemTools | 6 | 未迁；仅新增三个元数据探索工具，不等价于源组 |
| UsageDbTools | 6 | 未迁 |

已迁的基础设施包括官方 SDK、静态表单目录、部分结构知识与报表/单据只读网关；`MetadataValidator`、`K3Status`、`KnownErrors`、`BillTemplates`、`DocLifecycle`、`Actions`、`SqlDiscoverService` 仍需逐项评估与迁移。源工具的写操作不得因通用读能力存在而被视为已经支持。

## 2026-10-16 错误闭环修复

- `K3BusinessException`（customer/runtime）：区分金蝶业务失败与系统故障，`CommonInternalTool` 捕获后把原始错误+修正建议透传给 Agent，不再折叠为"联系管理员"。
- `ErrorPatternCatalog`（customer/app/k3/errors）：激活源项目 `error-patterns.json`（19 条模式，含排序条件缺失），按包含匹配返回原因+建议+下一步工具。
- `K3QueryTool.toBusinessExceptionAdvice`：把金蝶原始错误匹配为可自纠建议，并对结构性失败沉淀负向知识。
- `PlanningPrompt` 规则 16-18：K3 FilterString 语法约束、OrderString 必传条件、报表 dataJson 结构，让规划阶段就生成正确参数。
- `k3.query-data` 缺省排序不硬编码 `FID desc`，改用本次 `FieldKeys` 首个字段升序；工具描述和规划提示禁止假设 `FID` 存在，要求 `order` 只能来自本次字段集。Agent 不支持 MCP Elicitation 时返回候选清单和下一轮修正建议，不折叠成系统错误。
- 全量测试 75 个通过、1 个跳过（contract 4 + runtime 46 + app 18 + control-plane 7），包含 K3 业务异常透传和不支持 Elicitation 回归。

## 2026-10-16 409 规划自纠修复与规则复查

- 14:30 的 `HTTP 409 Duplicate step name at steps: page1` 不是金蝶查询参数问题，而是云端规划器只校验一次：LLM 偶发输出重复步骤名后没有纠错轮，直接被 Control Plane 映射成 409。`ExecutionPlanGenerator` 现在把确定性校验错误回喂给模型，最多重试 3 次；重试耗尽保留首个可诊断原因。
- `PlanningPrompt` 规则 6 删除“多页示例可编号 page1/page2”的误导表述，改为：id/saveAs 全局唯一，一个业务动作通常一个 call 步骤，不要手动分页。
- 复查全部金蝶能力与 CustomerApp 规则：内部只读能力仍是 `k3.query-data` / `k3.query-report`，公开探索是 `k3_search_forms` / `k3_describe_form` / `k3_suggest_params`；不迁入源项目写操作。
- `error-patterns.json` 已清除全部旧 `kingdee_*` 工具引用，统一指向当前 `k3.*` / `k3_*` 能力；不存在的反审核操作改为明确提示人工处理，避免 Agent 追溯不存在的工具。
- 报表 `ResponseStatus` 失败现在同样走 `ErrorPatternCatalog`，与单据行内错误一样返回原因和建议，不再只包一层无诊断前缀。
- 回归测试 77 个通过、1 个跳过，新增 `ExecutionPlanGeneratorRetryTest` 覆盖重复步骤名重试成功与重试耗尽原因保留。


## 架构决策：金蝶公开化与统一安全管线

### 为什么金蝶能力从内部规划 MCP 迁出

金蝶工具的使用模式是探索型闭环：`k3.search-forms` 找表单 → `k3.describe-form` 看字段 → `k3.query-data` 试查 → 失败后修正参数重试。每次重试如果走内部规划，Agent 都要把探索结果上报云端重新生成执行计划，云端 LLM 消耗大量 token 却只产出单步调用，调试和现场自纠效率极低。因此确认以下分工：

- **内部规划 MCP（`linko_ent`）**：只保留固定、确定性、云端可一次性出完整计划的简单能力。金蝶能力已全部移出，`PlanningPrompt` 中原规则 16/17/18（K3 FilterString 语法、OrderString 必传条件、报表 dataJson 结构）已删除，不再污染通用规划提示词。
- **公开直调 MCP Tool**：承接全部金蝶能力，Agent 在本地多轮自纠，不触发云端重新规划。共 5 个工具：
  - `k3.query-data` / `k3.query-report`（K3QueryTool）
  - `k3.search-forms` / `k3.describe-form` / `k3.suggest-params`（K3ExploreTool）

### 统一安全管线 ToolSecurityPipeline

公开工具与内部计划共用同一份安全实现，消除"公开工具需要再写一遍验证/权限/审计"的重复：

| 调用入口 | 进入方式 | 安全流程 |
| --- | --- | --- |
| 公开工具 | `PublicMcpToolSecurityAspect`（AOP） | `ToolSecurityPipeline.executePublicTool` |
| 内部计划 | `CommonInternalTool` | 同一管线组件（身份/授权/审计语义一致） |

管线职责：Agent 身份校验、`LocalAuthorizationService` 只读能力授权、每分钟 30 次限流、`PermissionReviewService` 员工规则审查、本地审计、`RequestExecutionContext` 绑定、工具执行。任何新增调用入口必须复用本组件，禁止另建第二套验证/权限/审计。

### 审查缓存（120 秒）

公开探索工具常以相同参数重复调用（Agent 重试、用户追问）。管线以 `paramsDigest + ':' + capabilityId` 为 key 缓存审查结果，TTL 120 秒；命中时审计标记 `REVIEW_CACHED`，跳过云端 LLM 审查。仅缓存 `allow` 且非 `uncertain` 的结果，`deny` 与不确定结果每次重审，保证权限收紧立即生效。

### 失败参数记忆（10 分钟）

`K3QueryTool` 内部维护 `ConcurrentHashMap<String, FailedCall>`，key 为 `formId + '|' + 各参数签名`，TTL 10 分钟：

- 命中同参组合重试时直接拒绝，返回"该参数组合刚被金蝶拒绝……请修正参数或先用探索工具确认"，阻止 Agent 无效循环。
- 记录时机：`query-data` 行内 `ResponseStatus` 失败、`query-report` 业务失败，均附带 `ErrorPatternCatalog` 匹配建议。
- 清除时机：同 formId 查询成功后清空该表单全部失败记忆。
- 容量上限 128 条，超出时整体清空。

### 错误模式工具引用同步

`error-patterns.json` 中原 `k3.query-data` 等含点号 key 已改名为下划线形式（`k3_query_data`、`k3_describe_form` 等），与公开 MCP 工具注册名一致，确保 Agent 收到 `nextActionTool` 后可直接调用。


## 验收尚缺

- 真实金蝶环境：目录外 FormId 发现、现场字段元数据、正确/错误/零行报表、账簿与期间消歧。
- 真实 Agent：Elicitation 接受、取消、不支持、超时，以及多轮重新规划后最终结果确属用户所需。
- 真实 Tenant 与 Control Plane：员工身份/权限边界、知识上传与按 Catalog 摘要隔离、数据库 V2 迁移及重启恢复。
- 性能：按相同意图复测 MCP 次数和总耗时；不得只以 HTTP 200 或有数据作为准确率证据。





