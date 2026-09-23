param(
    [Parameter(Mandatory = $true)][string]$EnvFile,
    [string]$ImageRepository = "origin-lightyear/enterprise-mcp",
    [string]$OutputDirectory = "",
    [ValidateSet("linux/amd64", "linux/arm64")][string]$Platform = "linux/amd64",
    [string]$WrenExtras = "mysql,postgres,mssql"
)

$ErrorActionPreference = "Stop"
$scriptDir = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $scriptDir "..\..")).Path
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) { throw "Env file does not exist" }
$envValues = @{}
Get-Content -LiteralPath $EnvFile | ForEach-Object {
    if ($_ -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $envValues[$Matches[1]] = $Matches[2] }
}
$TenantId = $envValues["SAAS_TENANT_ID"]
$InstanceId = $envValues["ENTERPRISE_MCP_INSTANCE_ID"]
$applicationConfig = Join-Path $repoRoot "customer\app\src\main\resources\application.yml"
$applicationText = Get-Content -Raw -LiteralPath $applicationConfig
$versionMatch = [regex]::Match($applicationText, 'artifact-version:[^\r\n]*:([0-9]+\.[0-9]+\.[0-9]+)\}')
$Version = $versionMatch.Groups[1].Value
if ($TenantId -notmatch '^[0-9]+$') { throw "SAAS_TENANT_ID must be numeric" }
if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { throw "application.yml artifact-version must use x.y.z format" }
if ($InstanceId -notmatch '^[a-zA-Z0-9._-]+$') { throw "Invalid instance ID" }
if ($ImageRepository -notmatch '^[a-z0-9][a-z0-9._/-]*$') { throw "Invalid image repository" }
if ($WrenExtras -and $WrenExtras -notmatch '^[a-z0-9_,.-]+$') { throw "Invalid Wren extras" }

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot "target\container-release"
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$workDir = Join-Path ([IO.Path]::GetTempPath()) ("enterprise-mcp-container-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $workDir | Out-Null

try {
    $imageRef = "${ImageRepository}:${Version}"
    & docker build --platform $Platform --build-arg "VERSION=$Version" --build-arg "WREN_EXTRAS=$WrenExtras" `
        --tag $imageRef --file (Join-Path $scriptDir "Dockerfile") $repoRoot
    if ($LASTEXITCODE -ne 0) { throw "Docker image build failed" }

    $imageId = (& docker image inspect --format '{{.Id}}' $imageRef).Trim()
    if ($LASTEXITCODE -ne 0 -or $imageId -notmatch '^sha256:[a-f0-9]{64}$') { throw "Unable to read image ID" }
    $imageArchiveName = "enterprise-mcp-image.tar"
    $imageArchive = Join-Path $workDir $imageArchiveName
    & docker save --output $imageArchive $imageRef
    if ($LASTEXITCODE -ne 0) { throw "Docker image export failed" }
    $archiveSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $imageArchive).Hash.ToLowerInvariant()

    $manifest = [ordered]@{
        manifestVersion = 3
        artifactType = "DOCKER_IMAGE"
        tenantId = [long]$TenantId
        instanceId = $InstanceId
        version = $Version
        image = [ordered]@{
            archive = $imageArchiveName
            repository = $ImageRepository
            tag = $Version
            imageId = $imageId
            archiveSha256 = $archiveSha256
            platform = $Platform
        }
        health = [ordered]@{ type = "HTTP"; path = "/internal/health"; expectedStatus = 200 }
    }
    $utf8NoBom = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText(
        (Join-Path $workDir "manifest.json"),
        ($manifest | ConvertTo-Json -Depth 5),
        $utf8NoBom
    )

    $package = Join-Path $OutputDirectory "enterprise-mcp-$InstanceId-$Version.tar.gz"
    & tar -czf $package -C $workDir manifest.json $imageArchiveName
    if ($LASTEXITCODE -ne 0) { throw "Container package compression failed" }
    $packageHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $package).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$package.sha256" -Value "$packageHash  $([IO.Path]::GetFileName($package))" -Encoding ascii
    Write-Host "Generated customer MCP container package: $package"
} finally {
    if (Test-Path -LiteralPath $workDir) {
        Remove-Item -LiteralPath $workDir -Recurse -Force
    }
}
