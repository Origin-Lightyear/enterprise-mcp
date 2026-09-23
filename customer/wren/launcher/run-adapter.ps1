param(
    [string]$ProjectRoot = "$PSScriptRoot\..\project-root",
    [int]$Port = 8091,
    [string]$InstanceDir = "",
    [string]$ServiceToken = "70891e19-5732-4c72-978c-c97b8463ef24",
    [string]$PythonExecutable = "",
    [switch]$CheckOnly
)

$ErrorActionPreference = "Stop"

Write-Host "[1/3] 正在查找 Wren Python 解释器..."

function Resolve-WrenPython {
    param([string]$RequestedExecutable)

    if (-not [string]::IsNullOrWhiteSpace($RequestedExecutable)) {
        $requestedCommand = Get-Command $RequestedExecutable -ErrorAction SilentlyContinue
        if ($requestedCommand) {
            return $requestedCommand.Source
        }
        throw "指定的 Wren Python 解释器不存在: $RequestedExecutable"
    }

    $candidates = @(
        (Join-Path $PSScriptRoot "..\..\..\.venv-wrenai\Scripts\python.exe"),
        (Join-Path $PSScriptRoot "..\.venv\Scripts\python.exe")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    foreach ($commandName in @("python", "python3")) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue
        if ($command) {
            return $command.Source
        }
    }
    throw "未找到可用的 Python 解释器。请先运行 Wren 安装器，或通过 -PythonExecutable 指定虚拟环境中的 python.exe。"
}

$requestedPython = if ($PythonExecutable) { $PythonExecutable } else { $env:WREN_PYTHON_EXECUTABLE }
$python = Resolve-WrenPython -RequestedExecutable $requestedPython
Write-Host "      Python: $python"
$upstreamPath = Join-Path -Path $PSScriptRoot -ChildPath "..\upstream\src"
$serverPath = Join-Path -Path $PSScriptRoot -ChildPath "..\adapter\server.py"
if (-not (Test-Path -LiteralPath $upstreamPath -PathType Container)) {
    throw "Wren upstream 源码目录不存在: $upstreamPath"
}
if (-not (Test-Path -LiteralPath $serverPath -PathType Leaf)) {
    throw "Wren Adapter 服务入口不存在: $serverPath"
}
$upstream = (Resolve-Path -LiteralPath $upstreamPath).Path
$server = (Resolve-Path -LiteralPath $serverPath).Path
$dependencyChecker = Join-Path -Path $PSScriptRoot -ChildPath "ensure_dependencies.py"
$env:PYTHONPATH = $upstream
if (-not (Test-Path -LiteralPath $ProjectRoot)) {
    New-Item -ItemType Directory -Path $ProjectRoot | Out-Null
}
$env:WREN_PROJECT_ROOT = (Resolve-Path -LiteralPath $ProjectRoot).Path
$env:WREN_INSTANCE_DIR = if ($InstanceDir) { $InstanceDir } else { $env:WREN_PROJECT_ROOT }
$env:WREN_ADAPTER_SERVICE_TOKEN = $ServiceToken
$env:WREN_ADAPTER_HOST = "127.0.0.1"
$env:WREN_ADAPTER_PORT = "$Port"

Write-Host "[2/3] 正在检查 Wren Adapter 依赖..."
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$dependencyOutput = & $python $dependencyChecker (Split-Path -Parent $upstream) 2>&1
$dependencyExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorActionPreference
if ($dependencyExitCode -ne 0) {
    $details = ($dependencyOutput | Out-String).Trim()
    throw "Wren 数据源依赖检查失败。当前解释器: $python。`n$details"
}
$preflightCode = "import runpy, sys; from pathlib import Path; sys.path.insert(0, str(Path(sys.argv[1]).parent)); runpy.run_path(sys.argv[1], run_name='wren_adapter_preflight')"
$ErrorActionPreference = "Continue"
$preflightOutput = & $python -c $preflightCode $server 2>&1
$preflightExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorActionPreference
if ($preflightExitCode -ne 0) {
    $details = ($preflightOutput | Out-String).Trim()
    throw "Wren Adapter 依赖检查失败。当前解释器: $python。请运行安装器或指定已安装 customer/wren/upstream 的虚拟环境。`n$details"
}

if ($CheckOnly -or $env:WREN_LAUNCHER_CHECK_ONLY -eq "1") {
    Write-Host "Wren Adapter 依赖检查通过: $python"
    exit 0
}

Write-Host "[3/3] 正在启动 Wren Adapter，首次加载可能需要一些时间..."
& $python $server
exit $LASTEXITCODE
