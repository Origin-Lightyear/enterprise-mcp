# Customer Wren Adapter

这是 customerapp 托管的内部 RPC 入口，不是 MCP Server，不依赖 Python MCP SDK。

启动：

```powershell
$env:WREN_PROJECT_ROOT = "D:\customer-mcp\wren"
$env:WREN_INSTANCE_DIR = "D:\customer-mcp\wren"
$env:WREN_ADAPTER_SERVICE_TOKEN = "<random-service-token>"
$env:WREN_ADAPTER_PORT = "8091"
$env:WREN_LOG_LEVEL = "INFO"
python server.py
```

接口（均仅监听 loopback）：

```text
GET  /internal/health
POST /internal/query
POST /internal/dry-plan
POST /internal/dry-run
POST /internal/context/rebuild
GET/POST/PUT/DELETE /internal/admin/** # 仅供 customerapp BFF
```

`/internal/query` 可接收 `sql`，也可接收 `question + llm`。`llm` 仅允许 `baseUrl`、`apiKey`、`generationModel`、`embeddingModel`、`rerankModel`、`timeoutSeconds` 字段，`apiKey` 只允许请求级传入，Adapter 不会写入 profile、索引或日志。所有响应统一为 `ok/code/message/data/requestId`，响应头 `X-Wren-Request-Id` 使用相同的请求标识。

Adapter 默认以 `INFO` 级别记录每个内部方法的入口、成功出口和耗时。异常路径会逐层记录方法名、异常类型和脱敏消息，并在 HTTP 边界输出一次完整 traceback。日志不记录方法参数、SQL、数据库凭据、LLM API Key 或完整业务结果。需要减少成功链路日志时，可将 `WREN_LOG_LEVEL` 设置为 `WARNING`；排查 customerapp 查询失败时，应使用 Java 异常中的 `requestId` 检索 Adapter 日志。

自然语言查询采用 Wren 官方 Memory 的混合策略：小于 30K 字符的 Schema 使用完整结构；大型 Schema 使用员工 `embeddingModel` 建立 LanceDB Schema Memory，并从 `knowledge/sql/*.md` 单独建立 Query Memory。索引按 `sourceKey + mdlDigest + embeddingModel` 隔离，向量维度由真实 Embeddings 响应探测。Schema 召回后会依据完整 MDL 补齐一跳关系和 Join 条件，`knowledge/rules/*.md` 始终完整加载。`rerankModel` 是可降级增强，接口不可用时保留向量排序。

每个客户必须设置独立的 `WREN_INSTANCE_DIR`。该目录下的 `.wren/profiles.yml` 保存 profile；密码等敏感值应写成 `${ENV_NAME}` 占位符，由 customerapp/systemd SecretProvider 注入，禁止把明文凭据提交到项目目录。数据源管理应通过 customerapp 的 `/internal/wren/datasources` 代理入口调用，不能直接暴露 Adapter 端口。

生产安装使用 [Wren 一键安装部署说明](../installer/安装部署说明.md)，不要直接复用开发机 Python 环境。
