<#
.SYNOPSIS
运行真实员工 MCP 端到端测试，并输出完整过程日志和业务结果。

.EXAMPLE
.\scripts\test-real-mcp.ps1

.EXAMPLE
.\scripts\test-real-mcp.ps1 -Mobile "13666666666" -TenantId "1" `
    -Question "查询公司ID是1的所有门店用户信息"
#>
[CmdletBinding()]
param(
    [string]$Mobile = "13666666666",
    [string]$Password = $env:REAL_MCP_PASSWORD,
    [string]$TenantId = "1",
    [string]$Question = "查询公司ID是1的所有门店用户信息",
    [string]$ToolName = "linko_ent",
    [string]$TenantUrl = "http://127.0.0.1:8002",
    [string]$RuntimeUrl = "http://127.0.0.1:6001/mcp",
    [string]$InstanceId = "mcp-1-b7074066-680e-45f3-b421-835542d349db",
    [int]$ExpectedMinRows = 1,
    [ValidateSet("true", "false", "ignore")]
    [string]$ExpectedTruncated = "false"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Password)) {
    $securePassword = Read-Host "请输入真实员工登录密码" -AsSecureString
    $credential = [System.Management.Automation.PSCredential]::new("real-mcp-test", $securePassword)
    $Password = $credential.GetNetworkCredential().Password
}

$testEnvironment = [ordered]@{
    REAL_MCP_LIVE_TEST = "true"
    REAL_MCP_MOBILE = $Mobile
    REAL_MCP_PASSWORD = $Password
    REAL_MCP_TENANT_ID = $TenantId
    REAL_MCP_QUESTION = $Question
    REAL_MCP_TOOL_NAME = $ToolName
    REAL_MCP_TENANT_URL = $TenantUrl
    REAL_MCP_RUNTIME_URL = $RuntimeUrl
    REAL_MCP_INSTANCE_ID = $InstanceId
    REAL_MCP_EXPECT_MIN_ROWS = $ExpectedMinRows.ToString()
    REAL_MCP_EXPECT_TRUNCATED = $ExpectedTruncated
}

$previousEnvironment = @{}
foreach ($entry in $testEnvironment.GetEnumerator()) {
    $previousEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, "Process")
    [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
}

$previousJavaToolOptions = [Environment]::GetEnvironmentVariable("JAVA_TOOL_OPTIONS", "Process")
$encodingOptions = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
$effectiveJavaToolOptions = ($previousJavaToolOptions, $encodingOptions | Where-Object {
    -not [string]::IsNullOrWhiteSpace($_)
}) -join " "
[Environment]::SetEnvironmentVariable("JAVA_TOOL_OPTIONS", $effectiveJavaToolOptions, "Process")

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$testExitCode = 1
Push-Location $repositoryRoot
try {
    & "$repositoryRoot\mvnw.cmd" -pl customer/app -am `
        '-Dtest=CustomerInternalMcpToolRealUserTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' `
        '-Dexec.skip=true' test
    $testExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
    foreach ($entry in $previousEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
    [Environment]::SetEnvironmentVariable("JAVA_TOOL_OPTIONS", $previousJavaToolOptions, "Process")
    $Password = $null
}

exit $testExitCode
