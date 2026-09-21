# WrenAI 官方 Models 管理界面源码调研

> 调研日期：2026-09-17  
> 调研范围：只研究 WrenAI 官方源码和官方文档，不开发 `enterprise-mcp` 功能。  
> 当前官方主线快照：[`871118e94f1525c401d867074c05e7e8eefca1cc`](https://github.com/Canner/WrenAI/commit/871118e94f1525c401d867074c05e7e8eefca1cc)。  
> Classic 官方冻结基线：tag [`v1-final`](https://github.com/Canner/WrenAI/tree/v1-final)，提交 [`e42b8d057c611016d781c7bbe74ba4e5aeb9712d`](https://github.com/Canner/WrenAI/commit/e42b8d057c611016d781c7bbe74ba4e5aeb9712d)，`wren-ui` 版本 `0.32.2`。  
> 源码核查快照：官方 `legacy/v1` 后续安全修复提交 [`ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc`](https://github.com/Canner/WrenAI/commit/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc)，`wren-ui` 版本 `0.32.3`。  
> GitHub 官方 compare 显示，从 `v1-final` 到该安全修复提交，`wren-ui` 仅修改 `package.json` 和 Sidebar 商业入口，本文列出的 Models 页面、画布、抽屉、关系、GraphQL 与 E2E 文件没有变化：[官方 compare](https://github.com/Canner/WrenAI/compare/e42b8d057c611016d781c7bbe74ba4e5aeb9712d...ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc)。本文源码链接锁定到后者，产品冻结基线仍以 `v1-final` 为准。

## 一、结论：已经找到官方 Models 管理界面

官方 Models 管理界面已经准确定位。它不在当前 `main` 的 Core/CLI 产品中，而在官方已冻结的 **Wren GenBI Classic** `legacy/v1` 分支的 `wren-ui` 目录。官方当前 README 明确说明旧 Docker GenBI 产品保留在 `legacy/v1` / `v1-final`，且不再获得新功能或安全修复：[当前官方说明](https://github.com/Canner/WrenAI/blob/871118e94f1525c401d867074c05e7e8eefca1cc/README.md#L228-L233)。

Models 不是一个普通列表页，而是一套完整的语义建模工作台：

```text
首次设置
  数据源连接
    -> 选择表生成 Models
    -> 审核/编辑自动推荐关系
    -> 进入 Modeling

日常建模
  左侧 Models / Views 树
    -> 中央 React Flow 模型与关系画布
    -> 点击模型打开右侧元数据抽屉
    -> Update Columns 打开字段选择抽屉
    -> Add/Edit Relationship 打开关系弹窗
    -> 修改后显示 Undeployed changes
    -> Deploy 后变为 Synced
```

官方入口是 [`wren-ui/src/pages/modeling.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/pages/modeling.tsx#L60-L475)，路由是 `/modeling`；首次设置路由依次为 `/setup/connection`、`/setup/models`、`/setup/relationships`，定义见 [`path.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/utils/enum/path.ts#L1-L15)。

## 二、准确源码定位

| 能力 | 官方源码 | 作用 |
|---|---|---|
| Modeling 页面编排 | [`pages/modeling.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/pages/modeling.tsx#L60-L475) | 查询 Diagram，编排模型、字段、元数据、计算字段和关系 CRUD，修改后刷新 Diagram 与部署状态 |
| 左侧 Models/Views 树 | [`sidebar/Modeling.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/sidebar/Modeling.tsx)、[`ModelTree.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/sidebar/modeling/ModelTree.tsx) | 浏览、定位模型，新增模型，触发 Schema 变化检测 |
| 模型关系画布 | [`diagram/index.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/diagram/index.tsx#L53-L171) | React Flow 画布、缩略图、缩放控制、自动 fit view、节点/边高亮 |
| 模型节点 | [`ModelNode.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/diagram/customNode/ModelNode.tsx#L37-L242) | 一个节点内分组显示 Columns、Calculated Fields、Relationships，并提供增加/编辑/删除操作 |
| 创建/更新模型字段 | [`ModelDrawer.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/modeling/ModelDrawer.tsx)、[`ModelForm.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/modeling/form/ModelForm.tsx#L43-L213) | 选择源表、Transfer 选择字段、指定一个主键；更新时增删已选字段 |
| 模型详情 | [`MetadataDrawer.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/modeling/MetadataDrawer.tsx)、[`ModelMetadata.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/modeling/metadata/ModelMetadata.tsx#L12-L109) | 760px 右侧抽屉显示 Name、Alias、Description、字段、计算字段、关系和 100 行数据预览 |
| 元数据编辑 | [`EditMetadataModal.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/modeling/EditMetadataModal.tsx)、[`EditModelMetadata.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/modeling/metadata/EditModelMetadata.tsx#L39-L157) | 编辑模型 Alias/Description、字段 Alias/Description、计算字段和关系 Description |
| 关系编辑 | [`RelationModal.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/modals/RelationModal.tsx)、[`useRelationshipModal.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/hooks/useRelationshipModal.tsx) | 选择 From Model.Field、To Model.Field 和关系类型；编辑时只允许修改类型 |
| 首次选择 Models | [`SelectModels.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/setup/SelectModels.tsx#L23-L98)、[`useSetupModels.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/hooks/useSetupModels.tsx) | 批量选择数据表，保存后进入关系定义步骤 |
| 首次定义关系 | [`DefineRelations.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/setup/DefineRelations.tsx#L45-L354)、[`useSetupRelations.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/hooks/useSetupRelations.tsx) | 按模型展示 PK/FK 推荐关系，可增删改、跳过或完成后进入 Modeling |
| 部署状态 | [`Deploy.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/deploy/Deploy.tsx#L11-L94) | `Deploying...`、`Synced`、`Undeployed changes` 三态和 Deploy 按钮 |
| GraphQL 客户端契约 | [`diagram.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/apollo/client/graphql/diagram.ts)、[`model.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/apollo/client/graphql/model.ts)、[`relationship.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/apollo/client/graphql/relationship.ts) | Diagram 查询与 Model、Metadata、Relationship mutation |
| GraphQL 服务端 | [`diagramResolver.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/apollo/server/resolvers/diagramResolver.ts)、[`modelResolver.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/apollo/server/resolvers/modelResolver.ts) | 从项目、模型、字段、关系和 View 仓储拼装 Diagram，并执行 CRUD |
| 官方浏览器行为证据 | [`e2e/commonTests/modeling.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/e2e/commonTests/modeling.ts#L10-L547) | Playwright 覆盖模型 CRUD、关系 CRUD、元数据更新、部署前后状态 |

## 三、页面和交互流程

### 3.1 首次初始化

1. `/setup/models` 调用 `listDataSourceTables`，用可多选表格让管理员选择源表；提交 `saveTables(tables)` 后跳转 `/setup/relationships`。来源：[`SelectModels.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/setup/SelectModels.tsx#L23-L98)、[`useSetupModels.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/hooks/useSetupModels.tsx#L11-L47)。
2. `/setup/relationships` 读取 `autoGenerateRelation`；官方文案明确推荐来源是数据源中的主键和外键。推荐项标记 `(auto-generated)`，管理员可逐条编辑、删除、新增，也可以跳过。来源：[`DefineRelations.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/setup/DefineRelations.tsx#L70-L133)、[`DefineRelations.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/setup/DefineRelations.tsx#L273-L349)。
3. 完成关系保存后进入 `/modeling`。来源：[`useSetupRelations.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/hooks/useSetupRelations.tsx#L14-L67)。

### 3.2 Modeling 工作台

- 左侧固定 280px Sidebar，按 Models 和 Views 分树显示；选中树节点后画布定位到对应节点。来源：[`SiderLayout.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/layouts/SiderLayout.tsx)、[`modeling.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/pages/modeling.tsx#L273-L287)。
- 中央画布使用 React Flow，提供 MiniMap、Controls、背景网格、节点/边悬停高亮和自动 fit view。来源：[`diagram/index.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/diagram/index.tsx#L53-L171)。
- 每个模型节点按 Columns、Calculated Fields、Relationships 三组展示；列过多时只显示限制数量并给出 `and N more`，避免单节点无限增长。来源：[`ModelNode.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/diagram/customNode/ModelNode.tsx#L57-L118)、[`ModelNode.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/diagram/customNode/ModelNode.tsx#L227-L247)。
- 点击模型节点打开只读元数据抽屉；抽屉内再进入 Edit metadata 弹窗。字段结构调整则从模型菜单进入独立的 Update Columns 抽屉，避免把结构变更和业务元数据编辑混在一起。来源：[`modeling.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/pages/modeling.tsx#L289-L354)、[`MetadataDrawer.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/modeling/MetadataDrawer.tsx)、[`ModelDrawer.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/modeling/ModelDrawer.tsx)。
- 关系从某个模型节点的 Relationships 分组新增；From 模型固定为当前模型，管理员选择两端字段和 `ONE_TO_ONE`、`ONE_TO_MANY`、`MANY_TO_ONE`。编辑既有关系时端点锁定，只修改类型。来源：[`RelationModal.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/modals/RelationModal.tsx)、[`schema.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/apollo/server/schema.ts#L175-L228)。

### 3.3 模型、字段和元数据编辑

- 创建 Model：选择一个尚未建模的源表，通过左右 Transfer 选择字段，再选择一个主键；提交后创建 Model 与字段记录。来源：[`ModelForm.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/modeling/form/ModelForm.tsx#L43-L213)、[`modelResolver.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/apollo/server/resolvers/modelResolver.ts#L330-L427)。
- 更新 Model 字段：同一 Transfer 增删字段并调整主键；服务端对比已有字段，分别删除、创建或更新类型。来源：[`modelResolver.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/apollo/server/resolvers/modelResolver.ts#L429-L549)。
- 查看 Model：显示稳定 Name、可编辑 Alias、Description、Columns、Calculated fields、Relationships，并可预览 100 行数据。来源：[`ModelMetadata.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/modeling/metadata/ModelMetadata.tsx#L46-L108)。
- 编辑 Metadata：模型 Name 不可改，Alias 和 Description 可改；普通字段可改 Alias/Description，计算字段和关系可改 Description。来源：[`EditBasicMetadata.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/modeling/metadata/EditBasicMetadata.tsx)、[`EditModelMetadata.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/pages/modeling/metadata/EditModelMetadata.tsx#L39-L157)。

### 3.4 状态流

`modeling.tsx` 通过 Apollo hooks 发起 mutation；每次成功后重新查询 `DIAGRAM`，模型变更还会刷新 `LIST_MODELS`，并刷新部署状态。来源：[`modeling.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/pages/modeling.tsx#L65-L153)。

```text
React 事件
  -> Apollo GraphQL mutation
  -> ModelResolver / ModelService
  -> SQLite 或 PostgreSQL repository
  -> DiagramResolver 聚合 Project + Models + Columns + Relations + Views
  -> refetch DIAGRAM
  -> React Flow 重新生成 nodes / edges

任一模型变更
  -> 当前 MDL hash != 最近部署 hash
  -> Undeployed changes
  -> Deploy mutation + 1 秒轮询
  -> hash 一致后 Synced
```

Diagram 查询返回模型的字段、计算字段、关系字段以及 Views，服务端再通过 `MDLBuilder` 构造 manifest 后转换为画布 DTO。来源：[`diagram.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/apollo/client/graphql/diagram.ts)、[`diagramResolver.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/apollo/server/resolvers/diagramResolver.ts#L25-L84)。部署状态由当前 manifest hash 与最近一次 deployment hash 对比得出。来源：[`modelResolver.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/apollo/server/resolvers/modelResolver.ts#L173-L197)、[`Deploy.tsx`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/components/deploy/Deploy.tsx#L11-L94)。

官方 E2E 不是只测渲染：它实际覆盖删除并重新创建 Model、更新字段、关系增删改、元数据编辑，以及 `Synced` / `Undeployed changes` / `Deploying...` 状态。来源：[`modeling.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/e2e/commonTests/modeling.ts#L10-L547)、[`connectSampleECommerce.spec.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/e2e/specs/connectSampleECommerce.spec.ts)。

## 四、可照搬到 enterprise-mcp 的产品边界

这里的“照搬”应定义为：**沿用官方信息架构、任务顺序、交互位置和状态反馈，基于本仓库契约重新实现**。不应定义为复制 Classic 源码和 GraphQL/SQLite 后端。

| 官方 Classic 设计 | customerapp 建议 | 边界 |
|---|---|---|
| `/setup/models` 表批量选择 | 结构导入的 table/schema 树、搜索、批量选择和差异预览 | 沿用“先批量选表，再建模”；数据来自持久化 Snapshot，不能浏览器逐表探测 |
| `/setup/relationships` 推荐关系审核 | Modeling 生成后进入关系候选审核 | 沿用自动推荐、可增删改、无推荐提示；必须区分物理外键 `CONFIRMED` 与命名推导 `CANDIDATE` |
| 左树 + 中央关系画布 + 右抽屉 | `/admin/wren/models` 主工作台 | 这是最值得直接复用的页面结构；当前计划中的单独“模型/关系”页面可合并为同一工作台内的 Models 与 Relationships 视图 |
| 节点内 Columns / Calculated Fields / Relationships | 模型节点显示字段、业务计算字段、关系 | 沿用分组和列数截断；大量模型必须再加搜索、虚拟化或按需装载 |
| Model Drawer 选择表、字段、一个主键 | Snapshot -> Modeling 后编辑字段选择和键 | 沿用结构/元数据分离；本项目必须支持复合主键、类型、可见性、原始映射和只读来源信息，不能照搬“一个主键下拉框” |
| Metadata Drawer + Edit modal | 查看态抽屉 + 明确编辑态 | 沿用查看与编辑分离；中文名应映射为 displayName/alias，原始稳定 name 不允许随意改 |
| Relationship modal | 关系新增/编辑弹窗与可访问表格 | 沿用两端 Model.Field 和基数选择；本项目还要显示来源、状态、复合字段和冲突影响 |
| Synced / Undeployed / Deploying | 当前生效 / 规划未就绪 / 应用更新中 | 沿用顶部持续可见状态和操作按钮位置，但本项目只有一个 Current MDL，不保留发布历史 |
| Schema change detection | Snapshot 差异预览和显式合并 | 可沿用警告入口；本项目不能自动删除受影响模型，必须用 revision、影响清单和管理员确认维护 Modeling |

### 不能直接照搬的实现

1. **不能复制 Next.js 全栈结构。** Classic `wren-ui` 是 Next.js 14 + Apollo GraphQL + Knex + SQLite/PostgreSQL 的独立全栈应用，依赖项见 [`package.json`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/package.json)。本项目约束是 React/Vite 静态资源进入 Java Fat JAR，由 Java BFF 调用 loopback Adapter。
2. **不能复制 Classic 持久化模型。** Classic 直接把 Model、Column、Relationship 写入 UI 数据库；本项目的事实来源是 `profiles.yml`、Modeling YAML 和唯一 Current MDL。
3. **不能复制三态 Deploy。** Classic 用当前 manifest hash 与最后部署 hash 比较；本项目使用“应用更新”，先由 Control Plane 确认候选 Catalog，再原子替换 Current MDL；失败时保留旧 Current。
4. **不能复制字段与关系的能力上限。** Classic 创建模型只允许选择一个 primary key；关系只有单字段端点和三种基数；元数据编辑不包含字段可见性和业务类型覆盖。接口定义见 [`schema.ts`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/wren-ui/src/apollo/server/schema.ts#L175-L334)。
5. **不能复制“提交即持久化”的状态流。** Classic 每次 mutation 后直接 refetch Diagram；本项目必须以 Modeling revision 做乐观锁，并保留校验结果和应用前差异。
6. **不能把 Classic 的数据预览直接开放。** 官方抽屉提供 100 行预览；本项目任何预览仍须经过管理员鉴权、只读 AST、超时/行数/字节限制、脱敏和审计。

## 五、许可证前置门槛

固定 Classic 提交的仓库根许可证是 **GNU AGPL-3.0**，不是当前 Core 子树使用的 Apache-2.0：[Classic `LICENSE`](https://github.com/Canner/WrenAI/blob/ce9ed54c86acb8a0b6ff5d5fea23ed60f057acfc/LICENSE)。该许可证还明确涉及通过网络交互时向用户提供对应源代码的义务。

因此开发前必须在以下两条中选择一条：

1. **推荐：行为和视觉结构参考，独立实现。** 只采用“左树 + 画布 + 抽屉 + 弹窗 + 发布状态”等抽象交互，不复制 WrenAI 的 TypeScript、样式、图标、文案和数据库/BFF 实现；保留本项目自己的 React/Vite/Ant Design/React Flow 技术栈和接口。
2. **直接复制官方源码：先完成法务和开源策略决策。** 若确实要求复制或改造 `wren-ui` 源码，必须先确认 AGPL-3.0 对 customerapp、Fat JAR 分发、远程用户源码获取方式和组合边界的影响，或向 Canner 获取适用的商业许可。未完成该决策前不得开始源码复制。

本报告只做源码事实定位，不构成法律意见。

## 六、开发时建议锁定的官方交互基线

后续开发可以把以下内容作为验收基线，而不是只凭文字重新设计：

1. `/admin/wren/models` 使用官方同类的 280px 左树、中央关系画布和右侧元数据抽屉；模型节点固定分 Columns、Calculated Fields、Relationships。
2. 首次结构导入遵循“选择表 -> 审核推荐关系 -> 进入 Modeling”的官方顺序，但在前面保留本项目的数据源测试和异步探测任务。
3. Model 结构编辑与 Metadata 编辑分开；稳定原始名称只读，中文显示名和描述可编辑。
4. 关系既可从模型节点新增，也必须提供等价表格视图；关系端点、基数、来源、状态和应用影响必须可见。
5. 页面顶部始终显示 Current MDL 的规划就绪状态；管理员通过“应用更新”显式提交完整 Modeling。
6. Playwright 至少复刻官方 E2E 所覆盖的模型 CRUD、关系 CRUD 和元数据更新，再补本项目的复合主键、候选关系、revision 冲突、云端失败不切换 Current MDL 和 375px/768px 响应式验证。

## 七、调研边界

- 已确认官方源码位置、固定提交、路由、主要组件、GraphQL 状态流、服务端持久化链路和官方 E2E 行为。
- 未启动已 Sunset 的六容器 Classic 产品，也未以运行截图代替源码判断。
- 本报告的官方源码事实仍作为交互参考；本仓库已经建立独立 React/Vite 管理页面和 Java BFF，不使用 Classic 的 GraphQL/SQLite 持久化链路。
- 当前实现采用 Modeling、MDL Candidate 和唯一 Current MDL；后续不得重新引入 Wren Release、激活或版本历史。
