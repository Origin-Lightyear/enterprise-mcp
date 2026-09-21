# Wren 客户侧管理控制台与 Current MDL 设计

## 1. 目标

Wren 只负责客户内网数据库的语义建模、只读 SQL 生成和查询执行。管理员通过 customerapp 内置管理页面维护 Modeling，并用一次“应用更新”把完整候选变为唯一 Current MDL。Wren 不提供 Release、版本历史、激活或回滚入口。

云端 Control Plane 使用 Runtime 上传的完整 Catalog Snapshot 生成计划。Catalog 包含当前已配置的 Java Capability；存在有效 Current MDL 时，才额外包含 `database.semantic-query`、`mdlDigest` 和语义模型。

## 2. 领域对象

| 对象 | 含义 | 存储位置 |
|---|---|---|
| 数据源 Profile | 数据库类型、地址和凭据引用 | 实例目录 `profiles.yml` |
| Schema Snapshot | 一次探测得到的表、字段、约束和注释 | `jobs/` 短期任务结果 |
| Modeling | 管理员可编辑、尚不参与查询的 Wren YAML v5 项目 | `projects/modeling/<profileName>/` |
| MDL Candidate | 从全部 Current 数据源加本次 Modeling 固化出的短生命周期不可变候选 | `projects/candidates/<token>/` |
| Current MDL | 当前查询唯一允许读取的完整语义模型集合 | `projects/current/<profileName>/` |
| MDL Digest | Current MDL 内容指纹，不是版本号 | Current 快照响应 |
| Catalog Snapshot | Capability 与 Current MDL 的 canonical JSON 快照 | Control Plane MySQL |
| Catalog Digest | Catalog Snapshot 的 `sha256:` 内容身份 | Runtime 与 Control Plane |

## 3. 模块与调用方向

```text
管理员浏览器
  -> customerapp WrenAdminController
       -> RuntimeCatalog
            -> WrenSidecarClient
                 -> Wren Adapter
            -> PlanningGatewayClient
                 -> Control Plane PlanningCatalogService

员工请求
  -> customerapp
       -> Control Plane 精确 Catalog 规划
       -> RuntimeCatalog 校验计划绑定
       -> Wren Adapter 使用 Current MDL 查询客户数据库
```

`RuntimeCatalog` 是客户侧深模块。它集中隐藏 Catalog canonical 编码、摘要计算、候选确认、Current MDL 提交、规划就绪状态、恢复重试和旧计划失效。调用方不得自行拼装或同步 Catalog。

`PlanningCatalogService` 是云端 Registry 模块。它独立 canonicalize、复算摘要、保存不可变 Catalog Snapshot，并维护 Runtime 当前实际引用。规划请求必须显式携带精确 `catalogDigest`，不得推断“最新 Catalog”。

## 4. MDL Update 一致性

管理员点击“应用更新”时执行以下顺序：

1. customerapp 校验管理员会话、CSRF、`profileName`、`expectedMdlDigest` 和幂等键。
2. Wren Adapter 校验 Modeling、数据库连接和可构建性，并固化 MDL Candidate；此时 Current MDL 不变。
3. RuntimeCatalog 将 Candidate 与当前 Java Capability 合成为 canonical Catalog Snapshot。
4. Control Plane 独立复算 `catalogDigest`，校验 Schema 后确认并保存 Snapshot。
5. 只有摘要确认一致，Wren Adapter 才在同一写锁内原子替换对应数据源的 Current 目录和 Engine 引用。
6. Runtime 更新云端当前引用并进入 `READY`；引用暂时失败时进入 `NOT_READY`，后台自动恢复。
7. 删除 Candidate 和被替换的旧 Current 临时内容，不保留用户可见历史版本。

云端不可用、摘要不一致、Modeling 校验失败、数据库试连失败或 `expectedMdlDigest` 冲突时，Current MDL 必须保持原样。提交后 Catalog 或 MDL Digest 变化，所有未完成旧计划在下一检查点失败关闭并重新规划，不返回部分结果。

## 5. 并发与查询

- Modeling 编辑使用单调 `revision` 乐观锁，旧 revision 返回 `409 MODELING_CHANGED`。
- MDL Update 使用 `expectedMdlDigest` 防止基于过期 Current 提交。
- 同一 Runtime 的 MDL Update 串行执行；幂等键不能绑定不同候选内容。
- Wren 查询在同一读写锁内完成摘要检查、SQL 执行和二次摘要检查。
- Runtime 在执行计划前、每个步骤前后、每次 `foreach` 迭代和最终返回前校验 Catalog/MDL 绑定。
- 一条 SQL 只能访问一个 `sourceKey`；跨库请求由云端计划拆成多个步骤。
- Current MDL 不存在时不宣告 `database.semantic-query`，但 customerapp 和管理页面仍可启动。

## 6. 管理接口

customerapp 对管理员提供统一 `/internal/wren` BFF，Adapter 只监听 loopback：

```text
GET    /internal/wren/datasources
POST   /internal/wren/datasources
PUT    /internal/wren/datasources/{name}
DELETE /internal/wren/datasources/{name}
POST   /internal/wren/introspection-jobs
GET    /internal/wren/introspection-jobs/{jobId}
GET    /internal/wren/introspection-jobs/{jobId}/snapshot
GET    /internal/wren/modeling
POST   /internal/wren/modeling/from-snapshot/{jobId}
PUT    /internal/wren/modeling/models/{modelName}
PUT    /internal/wren/modeling/relationships/{relationshipName}
DELETE /internal/wren/modeling/relationships/{relationshipName}
POST   /internal/wren/modeling/validation
POST   /internal/wren/mdl-update
GET    /internal/wren/catalog-state
```

Adapter 内部候选接口不向浏览器直接开放：

```text
POST   /internal/admin/mdl-candidates
POST   /internal/admin/mdl-candidates/{token}/commit
DELETE /internal/admin/mdl-candidates/{token}
```

Control Plane 接口：

```text
POST /control-plane/catalog-snapshots/confirm
PUT  /control-plane/runtime-catalog-references
POST /control-plane/planning/plans
```

## 7. 状态目录

```text
<project-root>/
  projects/
    modeling/<profileName>/
    current/<profileName>/
    candidates/<token>/

<instance-dir>/
  profiles.yml
  jobs/
  audit/
```

Adapter 启动时删除遗留 Candidate，只从 `projects/current/` 重建 Engine。旧 `draft/`、`drafts/`、`releases/` 和 `active.json` 不再读取或迁移，部署前应由运维备份后清理。

## 8. 安全与运维

- 管理接口必须有 customerapp 管理员会话与 CSRF；Adapter 使用独立 loopback service token。
- 数据库密码、LLM Key 和员工数据不得进入 Catalog、计划、日志或审计正文。
- Catalog 只保存规划所需 Schema，不包含 Java 类名、方法键、SQL 或客户凭据。
- Runtime 可在 Control Plane 暂时不可用时启动，但规划状态必须为 `NOT_READY`。
- 后台恢复任务自动重新确认本地实际 Current Catalog；不允许管理员直接编辑 YAML 或手工覆盖摘要。
- Profile 被 Modeling 或 Current MDL 使用时禁止删除。

## 9. 验收证据

| 模型要素 | 行为 | 入口 | 最低验证 |
|---|---|---|---|
| Modeling | 多数据源独立编辑并以 revision 防并发覆盖 | 管理页面 Models/关系编辑 | 前端测试、Adapter 单测 |
| MDL Update | 先云端确认，再原子替换 Current | “应用更新” | RuntimeCatalog 接口级失败/成功测试 |
| Current MDL | 查询只读取唯一当前内容 | Wren `/internal/query` | 摘要前后检查和过期结果丢弃测试 |
| Catalog Snapshot | canonical JSON 与云端独立复算一致 | confirm 接口 | 摘要稳定性、伪造摘要拒绝测试 |
| Runtime 引用 | 规划只使用精确 Catalog Digest | reference 与 planning 接口 | 精确绑定测试 |
| Stale Plan | Current 变化后旧计划失败关闭 | Runtime 执行检查点 | catalogDigest/mdlDigest 回归测试 |
| 无 MDL | 不发布 Wren Capability | 启动同步 | RuntimeCatalog 接口级测试 |

真实客户数据库、真实员工 LLM、认证后的完整浏览器流程、Linux/Windows 服务生命周期和生产 MySQL 迁移仍必须在对应部署环境验证。
