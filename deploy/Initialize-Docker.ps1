param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{2,49}$')]
    [string]$AdminUsername = 'dockeradmin',
    [ValidatePattern('^[^\s=\r\n]+@[^\s=\r\n]+\.[^\s=\r\n]+$')]
    [string]$AdminEmail = 'dockeradmin@example.test',
    [ValidateRange(1024,65535)]
    [int]$HttpPort = 8088
)

$ErrorActionPreference = 'Stop'
$taskProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskEnvironmentFile = Join-Path $taskProjectRoot '.env.docker'
if (Test-Path -LiteralPath $taskEnvironmentFile) {
    Write-Output 'Existing .env.docker was preserved; no credentials were changed.'
    return
}

function New-TaskRandomBytes([int]$Count) {
    $taskBytes = [byte[]]::new($Count)
    $taskRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
    return ,$taskBytes
}

$taskDatabasePassword = [BitConverter]::ToString((New-TaskRandomBytes 24)).Replace('-','').ToLowerInvariant()
$taskAdminPassword = [BitConverter]::ToString((New-TaskRandomBytes 18)).Replace('-','').ToLowerInvariant()
$taskSigningKey = [Convert]::ToBase64String((New-TaskRandomBytes 32))
$taskEnvironmentText = @"
# PRIVATE: generated for the isolated Docker database. Do not commit or share.
OPSFLOW_HTTP_PORT=$HttpPort
OPSFLOW_DB_PASSWORD=$taskDatabasePassword
JWT_SECRET_BASE64=$taskSigningKey
BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_USERNAME=$AdminUsername
BOOTSTRAP_ADMIN_EMAIL=$AdminEmail
BOOTSTRAP_ADMIN_PASSWORD=$taskAdminPassword
"@

# CreateNew prevents an accidental overwrite, including a concurrent invocation.
$taskFileStream = [System.IO.File]::Open($taskEnvironmentFile,[System.IO.FileMode]::CreateNew,
    [System.IO.FileAccess]::Write,[System.IO.FileShare]::None)
try {
    $taskWriter = [System.IO.StreamWriter]::new($taskFileStream,[System.Text.UTF8Encoding]::new($false))
    try { $taskWriter.Write($taskEnvironmentText) } finally { $taskWriter.Dispose() }
} finally { $taskFileStream.Dispose() }
Write-Output 'Created private .env.docker with random database/admin passwords and a stable JWT key.'
Write-Output "Local URL after successful deployment: http://127.0.0.1:$HttpPort/"
Write-Output 'Read BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD locally from .env.docker to log in.'
