param(
    [Parameter(Mandatory = $false)]
    [string]$RootSecret = $env:TENANT_MCP_ROOT_SECRET,

    [Parameter(Mandatory = $false)]
    [string]$TenantId = $env:SAAS_TENANT_ID,

    [Parameter(Mandatory = $false)]
    [string]$InstanceId = $env:ENTERPRISE_MCP_INSTANCE_ID
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RootSecret)) {
    throw "TENANT_MCP_ROOT_SECRET（Tenant MCP 根密钥）不能为空"
}
if ($TenantId -notmatch '^[1-9][0-9]*$') {
    throw "SAAS_TENANT_ID（租户编号）必须是大于 0 的整数"
}
if ([string]::IsNullOrWhiteSpace($InstanceId)) {
    throw "ENTERPRISE_MCP_INSTANCE_ID（Runtime 实例编号）不能为空"
}

try {
    $rootBytes = [Convert]::FromBase64String($RootSecret.Trim())
} catch {
    throw "TENANT_MCP_ROOT_SECRET（Tenant MCP 根密钥）必须是合法 Base64"
}
if ($rootBytes.Length -lt 32) {
    throw "TENANT_MCP_ROOT_SECRET（Tenant MCP 根密钥）解码后不得少于 32 字节"
}

$context = "tenant-mcp-token:v1:$TenantId`:$($InstanceId.Trim())"
$hmac = [System.Security.Cryptography.HMACSHA256]::new($rootBytes)
try {
    $tokenBytes = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($context))
    $token = [Convert]::ToBase64String($tokenBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    Write-Output "TENANT_MCP_TOKEN=$token"
} finally {
    $hmac.Dispose()
    [Array]::Clear($rootBytes, 0, $rootBytes.Length)
}
