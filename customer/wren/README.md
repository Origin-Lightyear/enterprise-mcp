# Customer Wren Runtime

本目录用于存放锁定版本的 WrenAI 副本、客户 MDL 和 customerapp 内部 Adapter。

Wren 不启动 MCP Server，不对 Agent 或云端 Control Plane 暴露 `/mcp`。生产调用方向固定为：

```text
customerapp -> 本机 Wren Adapter -> Wren Engine -> 客户数据库
```

`customerapp` 通过 `enterprise.wren.*` 配置托管 Adapter 生命周期。数据库 profile，以及 MDL 中的表、字段、主键、关系和中文含义由 Wren 管理；第一版不要求维护独立业务指标口径。员工 LLM 配置只能由 customerapp 按请求注入，不得写入长期 profile。

Adapter 会校验请求绑定的 `mdlDigest`，使用 `sqlglot` 拒绝多语句、写操作和普通 `SELECT *`，并从 SQL AST 自动生成输出列到 MDL 字段的来源关系。查询超过完整性行数限制时直接失败，不返回截断结果。

生产环境优先使用 `installer/` 中的一键安装器：

```text
installer/install-wren-linux.sh     Linux systemd 安装器
installer/install-wren-windows.ps1  Windows 开机计划任务安装器
installer/安装部署说明.md            完整参数、目录和运维说明
```

两端默认都使用交互向导，无需在命令后拼接安装参数：

```bash
sudo bash customer/wren/installer/install-wren-linux.sh
```

```powershell
Set-ExecutionPolicy -Scope Process Bypass
& .\customer\wren\installer\install-wren-windows.ps1
```

数据源选择界面使用 `↑/↓` 移动、空格键多选、回车键确认。带参数的调用方式仅作为 CI/无人值守安装入口保留。

开发环境的手工启动脚本位于 `launcher/`。所有启动方式均只绑定 loopback，并将数据库 profile 放在客户实例目录，禁止使用开发机的全局 `~/.wren`。脚本优先使用仓库根目录的 `.venv-wrenai`，其次使用 `customer/wren/.venv`；只有虚拟环境不存在时才检查 PATH 中的 `python3`/`python`。启动前会加载 Adapter 及其全部依赖，依赖不完整时直接报告实际解释器和修复方式。

Windows 可通过 `-PythonExecutable` 覆盖解释器，Linux 可通过 `WREN_PYTHON_EXECUTABLE` 覆盖。两端都支持只检查依赖而不启动服务：

```powershell
.\customer\wren\launcher\run-adapter.ps1 -CheckOnly
```

```bash
WREN_LAUNCHER_CHECK_ONLY=1 customer/wren/launcher/run-adapter.sh
```

安装器会创建并固定使用安装目录专属 Python 虚拟环境、安装选定数据源 extras、保留客户 MDL/profile，并在启动后检查 `/internal/health`，不依赖开发机 PATH。

详细步骤见 [Wren 一键安装部署说明](installer/安装部署说明.md)。

当前锁定的 Wren 源码位于 `customer/wren/upstream`。正式交付应保留许可证/NOTICE，并完成 Python/Rust 依赖扫描；版本、来源和定制范围见 `NOTICE.md`。
