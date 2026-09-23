[CmdletBinding()]
param(
    [ValidatePattern('^[a-zA-Z0-9._-]+$')]
    [string]$InstanceId = "",

    [string]$SourceDir = (Split-Path -Parent $PSScriptRoot),
    [string]$InstallRoot = "$env:ProgramData\EnterpriseMcp",
    [string]$StateRoot = "",
    [string]$PythonExecutable = "python",
    [ValidateRange(1, 65535)]
    [int]$Port = 8091,
    [ValidatePattern('^[a-z0-9_,.-]*$')]
    [string]$Extras = "mysql,postgres,mssql",
    [string]$EnvironmentFile = "",
    [string]$ProfileFile = "",
    [ValidateRange(1, 600)]
    [int]$HealthTimeoutSeconds = 60,
    [switch]$NoStart
)

$ErrorActionPreference = "Stop"
$WrenCoreVersion = "0.8.0"

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw "必须使用管理员 PowerShell 执行"
    }
}

function Remove-ManagedPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    $fullPath = [IO.Path]::GetFullPath($Path)
    $managedRoot = [IO.Path]::GetFullPath($script:InstanceRoot) + [IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($managedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝删除实例目录以外的路径: $fullPath"
    }
    if (Test-Path -LiteralPath $fullPath) {
        Remove-Item -LiteralPath $fullPath -Recurse -Force
    }
}

function ConvertTo-SingleQuotedLiteral {
    param([Parameter(Mandatory = $true)][string]$Value)
    return $Value.Replace("'", "''")
}

function New-SecureToken {
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Test-InteractiveConsole {
    try {
        return -not [Console]::IsInputRedirected -and -not [Console]::IsOutputRedirected
    } catch {
        return $Host.Name -eq "ConsoleHost"
    }
}

function Read-ValueWithDefault {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [AllowEmptyString()][string]$DefaultValue = ""
    )

    $prompt = if ($DefaultValue) { "$Label [$DefaultValue]" } else { $Label }
    $value = Read-Host $prompt
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $DefaultValue
    }
    return $value.Trim()
}

function Select-MultipleConsole {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][object[]]$Items,
        [AllowEmptyString()][string]$InitialValues = ""
    )

    $cursor = 0
    $selected = [bool[]]::new($Items.Count)
    $initialSet = @($InitialValues -split ',' | Where-Object { $_ })
    for ($index = 0; $index -lt $Items.Count; $index++) {
        $selected[$index] = $initialSet -contains $Items[$index].Value
    }
    while ($true) {
        Clear-Host
        Write-Host $Title
        Write-Host "使用 ↑/↓ 移动，空格多选或取消，回车确认。DuckDB/本地文件无需选择。"
        Write-Host
        for ($index = 0; $index -lt $Items.Count; $index++) {
            $pointer = if ($index -eq $cursor) { ">" } else { " " }
            $marker = if ($selected[$index]) { "x" } else { " " }
            Write-Host ("{0} [{1}] {2}" -f $pointer, $marker, $Items[$index].Label)
        }

        $key = [Console]::ReadKey($true)
        switch ($key.Key) {
            "UpArrow" { $cursor = ($cursor - 1 + $Items.Count) % $Items.Count }
            "DownArrow" { $cursor = ($cursor + 1) % $Items.Count }
            "Spacebar" { $selected[$cursor] = -not $selected[$cursor] }
            "Enter" {
                $values = for ($index = 0; $index -lt $Items.Count; $index++) {
                    if ($selected[$index]) { $Items[$index].Value }
                }
                return ($values -join ",")
            }
        }
    }
}

function Select-OneConsole {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][object[]]$Items
    )

    $cursor = 0
    while ($true) {
        Clear-Host
        Write-Host $Title
        Write-Host "使用 ↑/↓ 移动，回车确认。"
        Write-Host
        for ($index = 0; $index -lt $Items.Count; $index++) {
            $pointer = if ($index -eq $cursor) { ">" } else { " " }
            Write-Host ("{0} {1}" -f $pointer, $Items[$index].Label)
        }

        $key = [Console]::ReadKey($true)
        switch ($key.Key) {
            "UpArrow" { $cursor = ($cursor - 1 + $Items.Count) % $Items.Count }
            "DownArrow" { $cursor = ($cursor + 1) % $Items.Count }
            "Enter" { return $Items[$cursor].Value }
        }
    }
}

function Invoke-InteractiveWizard {
    $dataSources = @(
        [pscustomobject]@{ Label = "MySQL"; Value = "mysql" },
        [pscustomobject]@{ Label = "PostgreSQL"; Value = "postgres" },
        [pscustomobject]@{ Label = "SQL Server"; Value = "mssql" },
        [pscustomobject]@{ Label = "Oracle"; Value = "oracle" },
        [pscustomobject]@{ Label = "ClickHouse"; Value = "clickhouse" },
        [pscustomobject]@{ Label = "Trino"; Value = "trino" },
        [pscustomobject]@{ Label = "Snowflake"; Value = "snowflake" },
        [pscustomobject]@{ Label = "BigQuery"; Value = "bigquery" },
        [pscustomobject]@{ Label = "Databricks"; Value = "databricks" },
        [pscustomobject]@{ Label = "Redshift"; Value = "redshift" },
        [pscustomobject]@{ Label = "Athena"; Value = "athena" },
        [pscustomobject]@{ Label = "Spark"; Value = "spark" },
        [pscustomobject]@{ Label = "Memory"; Value = "memory" }
    )
    $startOptions = @(
        [pscustomobject]@{ Label = "立即启动"; Value = "start" },
        [pscustomobject]@{ Label = "仅安装，暂不启动"; Value = "no-start" }
    )
    $confirmOptions = @(
        [pscustomobject]@{ Label = "开始安装"; Value = "install" },
        [pscustomobject]@{ Label = "返回修改"; Value = "edit" },
        [pscustomobject]@{ Label = "取消"; Value = "cancel" }
    )

    while ($true) {
        Clear-Host
        Write-Host "Wren 交互安装向导"
        Write-Host
        $previousInstanceId = $script:InstanceId
        do {
            $script:InstanceId = Read-ValueWithDefault -Label "客户实例 ID" -DefaultValue $script:InstanceId
            $validInstanceId = $script:InstanceId -match '^[a-zA-Z0-9._-]+$'
            if (-not $validInstanceId) {
                Write-Warning "实例 ID 只能包含字母、数字、点、下划线和连字符。"
                $script:InstanceId = ""
            }
        } until ($validInstanceId)

        $script:PythonExecutable = Read-ValueWithDefault -Label "Python 3.11+ 命令" -DefaultValue $script:PythonExecutable
        do {
            $portText = Read-ValueWithDefault -Label "Adapter 端口" -DefaultValue ([string]$script:Port)
            $parsedPort = 0
            $validPort = [int]::TryParse($portText, [ref]$parsedPort) -and $parsedPort -ge 1 -and $parsedPort -le 65535
            if (-not $validPort) { Write-Warning "端口必须是 1-65535 之间的整数。" }
        } until ($validPort)
        $script:Port = $parsedPort

        $previousInstallRoot = $script:InstallRoot
        $script:InstallRoot = Read-ValueWithDefault -Label "程序安装根目录" -DefaultValue $script:InstallRoot
        $previousDefaultStateRoot = if ($previousInstanceId) { Join-Path (Join-Path $previousInstallRoot $previousInstanceId) "data" } else { "" }
        $defaultStateRoot = if ($script:StateRoot -and $script:StateRoot -ne $previousDefaultStateRoot) {
            $script:StateRoot
        } else {
            Join-Path (Join-Path $script:InstallRoot $script:InstanceId) "data"
        }
        $script:StateRoot = Read-ValueWithDefault -Label "持久化目录" -DefaultValue $defaultStateRoot
        $script:EnvironmentFile = Read-ValueWithDefault -Label "wren.env 文件（可选）" -DefaultValue $script:EnvironmentFile
        $script:ProfileFile = Read-ValueWithDefault -Label "profiles.yml 文件（可选）" -DefaultValue $script:ProfileFile
        $script:Extras = Select-MultipleConsole -Title "选择需要安装的数据源驱动" -Items $dataSources -InitialValues $script:Extras
        $startChoice = Select-OneConsole -Title "安装后是否立即启动 Wren？" -Items $startOptions
        $script:NoStart = $startChoice -eq "no-start"

        Clear-Host
        Write-Host "安装配置摘要"
        Write-Host "----------------------------------------"
        Write-Host "实例 ID:       $script:InstanceId"
        Write-Host "Python:        $script:PythonExecutable"
        Write-Host "监听地址:      127.0.0.1:$script:Port"
        Write-Host "安装根目录:    $script:InstallRoot"
        Write-Host "持久化目录:    $script:StateRoot"
        Write-Host ("数据源驱动:    {0}" -f $(if ($script:Extras) { $script:Extras } else { "无（DuckDB/本地文件无需驱动）" }))
        Write-Host ("环境文件:      {0}" -f $(if ($script:EnvironmentFile) { $script:EnvironmentFile } else { "保留现有配置或创建示例" }))
        Write-Host ("Profile 文件: {0}" -f $(if ($script:ProfileFile) { $script:ProfileFile } else { "保留现有配置" }))
        Write-Host ("安装后启动:    {0}" -f $(if ($script:NoStart) { "否" } else { "是" }))
        Write-Host
        Read-Host "按回车继续确认" | Out-Null

        $confirmation = Select-OneConsole -Title "请确认安装操作" -Items $confirmOptions
        if ($confirmation -eq "install") { Clear-Host; return }
        if ($confirmation -eq "cancel") { Write-Host "已取消安装"; exit 0 }
    }
}

Assert-Administrator
if (-not $InstanceId) {
    if (-not (Test-InteractiveConsole)) {
        throw "交互安装需要可用终端；CI/无人值守环境请使用 -InstanceId 等参数"
    }
    Invoke-InteractiveWizard
}
$SourceDir = (Resolve-Path -LiteralPath $SourceDir).Path
foreach ($requiredPath in @("adapter\server.py", "upstream\pyproject.toml", "launcher\ensure_dependencies.py")) {
    if (-not (Test-Path -LiteralPath (Join-Path $SourceDir $requiredPath))) {
        throw "Wren 交付目录缺少文件: $requiredPath"
    }
}
if ($EnvironmentFile -and -not (Test-Path -LiteralPath $EnvironmentFile)) {
    throw "环境文件不存在: $EnvironmentFile"
}
if ($ProfileFile -and -not (Test-Path -LiteralPath $ProfileFile)) {
    throw "profile 文件不存在: $ProfileFile"
}

$pythonCommand = Get-Command $PythonExecutable -ErrorAction Stop
if ($pythonCommand.Source -like "$env:LOCALAPPDATA\*") {
    Write-Warning "当前 Python 安装在用户目录；SYSTEM 计划任务可能无法访问，生产建议安装 machine-wide Python"
}
$pythonVersion = & $pythonCommand.Source -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"
if ($LASTEXITCODE -ne 0 -or [version]$pythonVersion -lt [version]"3.11") {
    throw "Wren 要求 Python 3.11 或更高版本，当前版本: $pythonVersion"
}

$script:InstanceRoot = Join-Path $InstallRoot $InstanceId
if (-not $StateRoot) {
    $StateRoot = Join-Path $script:InstanceRoot "data"
}
$RuntimeDir = Join-Path $script:InstanceRoot "runtime"
$PreviousDir = Join-Path $script:InstanceRoot "runtime.previous"
$StagingDir = Join-Path $script:InstanceRoot "runtime.new"
$VenvDir = Join-Path $script:InstanceRoot "venv"
$PreviousVenvDir = Join-Path $script:InstanceRoot "venv.previous"
$StagingVenvDir = Join-Path $script:InstanceRoot "venv.new"
$ConfigDir = Join-Path $script:InstanceRoot "config"
$ProjectRoot = $StateRoot
$WrenHomeDir = Join-Path $StateRoot ".wren"
$InstalledEnvironmentFile = Join-Path $ConfigDir "wren.env"
$CustomerAppEnvironmentFile = Join-Path $ConfigDir "customerapp-wren.env"
$LauncherPath = Join-Path $script:InstanceRoot "启动Wren.ps1"
$TaskName = "EnterpriseMcp-Wren-$InstanceId"

New-Item -ItemType Directory -Force -Path $script:InstanceRoot, $StateRoot, $ConfigDir | Out-Null
Remove-ManagedPath -Path $StagingDir
New-Item -ItemType Directory -Path $StagingDir | Out-Null
Copy-Item -LiteralPath (Join-Path $SourceDir "adapter") -Destination $StagingDir -Recurse
Copy-Item -LiteralPath (Join-Path $SourceDir "upstream") -Destination $StagingDir -Recurse
New-Item -ItemType Directory -Force -Path (Join-Path $StagingDir "launcher") | Out-Null
Copy-Item -LiteralPath (Join-Path $SourceDir "launcher\ensure_dependencies.py") -Destination (Join-Path $StagingDir "launcher")
if (Test-Path -LiteralPath (Join-Path $SourceDir "NOTICE.md")) {
    Copy-Item -LiteralPath (Join-Path $SourceDir "NOTICE.md") -Destination $StagingDir
}

Remove-ManagedPath -Path $StagingVenvDir
& $pythonCommand.Source -m venv $StagingVenvDir
if ($LASTEXITCODE -ne 0) { throw "创建 Python 虚拟环境失败" }
$StagingVenvPython = Join-Path $StagingVenvDir "Scripts\python.exe"
& $StagingVenvPython -m pip install --disable-pip-version-check --upgrade pip
if ($LASTEXITCODE -ne 0) { throw "升级 pip 失败" }
& $StagingVenvPython -m pip install --disable-pip-version-check "wren-core-py==$WrenCoreVersion"
if ($LASTEXITCODE -ne 0) { throw "安装 wren-core-py $WrenCoreVersion 失败" }
$PackageSpec = Join-Path $StagingDir "upstream"
if ($Extras) {
    $PackageSpec = "${PackageSpec}[$Extras]"
}
& $StagingVenvPython -m pip install --disable-pip-version-check $PackageSpec
if ($LASTEXITCODE -ne 0) { throw "安装 Wren Python 依赖失败" }
& $StagingVenvPython -m pip install --disable-pip-version-check "lancedb==0.38.0"
if ($LASTEXITCODE -ne 0) { throw "安装 Wren 向量索引依赖失败" }

$existingTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existingTask) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
}
Remove-ManagedPath -Path $PreviousDir
Remove-ManagedPath -Path $PreviousVenvDir
if (Test-Path -LiteralPath $RuntimeDir) {
    Move-Item -LiteralPath $RuntimeDir -Destination $PreviousDir
}
if (Test-Path -LiteralPath $VenvDir) {
    Move-Item -LiteralPath $VenvDir -Destination $PreviousVenvDir
}
Move-Item -LiteralPath $StagingDir -Destination $RuntimeDir
Move-Item -LiteralPath $StagingVenvDir -Destination $VenvDir

New-Item -ItemType Directory -Force -Path $ProjectRoot | Out-Null
New-Item -ItemType Directory -Force -Path $WrenHomeDir | Out-Null

if ($EnvironmentFile) {
    Copy-Item -LiteralPath $EnvironmentFile -Destination $InstalledEnvironmentFile -Force
} elseif (-not (Test-Path -LiteralPath $InstalledEnvironmentFile)) {
    @(
        "# 数据库密钥示例；profiles.yml 使用 `${CUSTOMER_DB_PASSWORD} 引用。",
        "# CUSTOMER_DB_PASSWORD=replace-me"
    ) | Set-Content -LiteralPath $InstalledEnvironmentFile -Encoding UTF8
}
if ($ProfileFile) {
    Copy-Item -LiteralPath $ProfileFile -Destination (Join-Path $WrenHomeDir "profiles.yml") -Force
}

$serviceToken = ""
foreach ($line in Get-Content -LiteralPath $InstalledEnvironmentFile -ErrorAction SilentlyContinue) {
    if ($line -match '^WREN_ADAPTER_SERVICE_TOKEN=(.+)$') { $serviceToken = $Matches[1] }
}
if (-not $serviceToken) {
    $serviceToken = New-SecureToken
    Add-Content -LiteralPath $InstalledEnvironmentFile -Value "`nWREN_ADAPTER_SERVICE_TOKEN=$serviceToken" -Encoding UTF8
}
$adminToken = ""
foreach ($line in Get-Content -LiteralPath $CustomerAppEnvironmentFile -ErrorAction SilentlyContinue) {
    if ($line -match '^ENTERPRISE_MCP_WREN_ADMIN_TOKEN=(.+)$') { $adminToken = $Matches[1] }
}
if (-not $adminToken) { $adminToken = New-SecureToken }
@(
    "ENTERPRISE_MCP_WREN_ENABLED=true",
    "ENTERPRISE_MCP_WREN_BASE_URL=http://127.0.0.1:$Port",
    "ENTERPRISE_MCP_WREN_PROJECT_ROOT=$ProjectRoot",
    "ENTERPRISE_MCP_WREN_INSTANCE_DIR=$StateRoot",
    "ENTERPRISE_MCP_WREN_ADAPTER_SERVICE_TOKEN=$serviceToken",
    "ENTERPRISE_MCP_WREN_ADMIN_TOKEN=$adminToken"
) | Set-Content -LiteralPath $CustomerAppEnvironmentFile -Encoding UTF8

# SYSTEM 任务读取连接密钥；移除普通本机用户继承权限，避免凭据横向泄漏。
& icacls.exe $InstalledEnvironmentFile /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw "无法收紧 Wren 环境文件权限" }
& icacls.exe $CustomerAppEnvironmentFile /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw "无法收紧 customerapp Wren 配置文件权限" }
$installedProfile = Join-Path $WrenHomeDir "profiles.yml"
if (Test-Path -LiteralPath $installedProfile) {
    & icacls.exe $installedProfile /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "无法收紧 Wren profile 文件权限" }
}

$environmentPathLiteral = ConvertTo-SingleQuotedLiteral $InstalledEnvironmentFile
$VenvPython = Join-Path $VenvDir "Scripts\python.exe"
$pythonPathLiteral = ConvertTo-SingleQuotedLiteral $VenvPython
$serverPathLiteral = ConvertTo-SingleQuotedLiteral (Join-Path $RuntimeDir "adapter\server.py")
$dependencyCheckerLiteral = ConvertTo-SingleQuotedLiteral (Join-Path $RuntimeDir "launcher\ensure_dependencies.py")
$pythonModulePathLiteral = ConvertTo-SingleQuotedLiteral (Join-Path $RuntimeDir "upstream\src")
$projectPathLiteral = ConvertTo-SingleQuotedLiteral $ProjectRoot
$statePathLiteral = ConvertTo-SingleQuotedLiteral $StateRoot
$logPathLiteral = ConvertTo-SingleQuotedLiteral (Join-Path $StateRoot "wren-adapter.log")
$launcherContent = @"
`$ErrorActionPreference = 'Stop'
foreach (`$line in Get-Content -LiteralPath '$environmentPathLiteral' -ErrorAction SilentlyContinue) {
    `$trimmed = `$line.Trim()
    if (-not `$trimmed -or `$trimmed.StartsWith('#')) { continue }
    `$separator = `$trimmed.IndexOf('=')
    if (`$separator -le 0) { throw "环境变量格式非法: `$line" }
    `$name = `$trimmed.Substring(0, `$separator).Trim()
    `$value = `$trimmed.Substring(`$separator + 1)
    [Environment]::SetEnvironmentVariable(`$name, `$value, 'Process')
}
`$env:PYTHONDONTWRITEBYTECODE = '1'
`$env:PYTHONPATH = '$pythonModulePathLiteral'
`$env:WREN_PROJECT_ROOT = '$projectPathLiteral'
`$env:WREN_INSTANCE_DIR = '$statePathLiteral'
`$env:WREN_ADAPTER_HOST = '127.0.0.1'
`$env:WREN_ADAPTER_PORT = '$Port'
& '$pythonPathLiteral' '$dependencyCheckerLiteral' '$RuntimeDir\upstream' *>> '$logPathLiteral'
if (`$LASTEXITCODE -ne 0) { exit `$LASTEXITCODE }
& '$pythonPathLiteral' '$serverPathLiteral' *>> '$logPathLiteral'
exit `$LASTEXITCODE
"@
$launcherContent | Set-Content -LiteralPath $LauncherPath -Encoding UTF8

$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$LauncherPath`""
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal `
    -Settings $settings -Description "Enterprise MCP Wren Adapter $InstanceId" -Force | Out-Null

if ($NoStart) {
    Write-Host "Wren 已安装但未启动，计划任务: $TaskName"
    Write-Host "customerapp 配置文件: $CustomerAppEnvironmentFile"
    exit 0
}

Start-ScheduledTask -TaskName $TaskName
$deadline = [DateTime]::UtcNow.AddSeconds($HealthTimeoutSeconds)
$healthy = $false
while ([DateTime]::UtcNow -lt $deadline) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/internal/health" -TimeoutSec 3
        $health = $response.Content | ConvertFrom-Json
        $taskState = (Get-ScheduledTask -TaskName $TaskName).State
        if ($response.StatusCode -eq 200 -and $health.ok -eq $true -and $health.data.status -eq "UP" `
                -and $taskState -eq "Running") {
            $healthy = $true
            break
        }
    } catch {
        Start-Sleep -Seconds 2
    }
}

if (-not $healthy) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $PreviousDir) {
        Remove-ManagedPath -Path $RuntimeDir
        Move-Item -LiteralPath $PreviousDir -Destination $RuntimeDir
        Remove-ManagedPath -Path $VenvDir
        if (Test-Path -LiteralPath $PreviousVenvDir) {
            Move-Item -LiteralPath $PreviousVenvDir -Destination $VenvDir
        }
        Start-ScheduledTask -TaskName $TaskName
        Write-Warning "Wren 健康检查失败，已恢复上一次 runtime 和 Python venv"
    }
    throw "Wren 健康检查失败，请查看计划任务历史和启动脚本输出"
}

Write-Host "Wren 安装成功"
Write-Host "计划任务: $TaskName"
Write-Host "健康地址: http://127.0.0.1:$Port/internal/health"
Write-Host "Customer Runtime 配置:"
Write-Host "  ENTERPRISE_MCP_WREN_ENABLED=true"
Write-Host "  ENTERPRISE_MCP_WREN_BASE_URL=http://127.0.0.1:$Port"
Write-Host "  ENTERPRISE_MCP_WREN_PROJECT_ROOT=$ProjectRoot"
Write-Host "  ENTERPRISE_MCP_WREN_INSTANCE_DIR=$StateRoot"
Write-Host "  customerapp 配置文件=$CustomerAppEnvironmentFile"
