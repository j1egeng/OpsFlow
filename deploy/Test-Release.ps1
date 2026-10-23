#Requires -Version 7.2
param([Parameter(Mandatory)][string]$BundleDirectory)
. "$PSScriptRoot/CD.Common.ps1"
$BundleDirectory = [IO.Path]::GetFullPath($BundleDirectory)
$release = Read-CDManifest $BundleDirectory
$project = 'opsflow-release-test-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
$scratch = Join-Path ([IO.Path]::GetTempPath()) $project
New-Item -ItemType Directory -Path $scratch | Out-Null
$privateFile = Join-Path $scratch '.env.docker'
$override = Join-Path $scratch 'test.yaml'
# Publishing 0 asks Docker to allocate an unused loopback port.
$overrideText = @"
services:
  db:
    volumes: !override
      - type: tmpfs
        target: /var/lib/postgresql
  backend:
    image: $($release.images.backend.tag)
  frontend:
    image: $($release.images.frontend.tag)
    ports: !override
      - "127.0.0.1::80"
"@
[IO.File]::WriteAllText($override, $overrideText)
$password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
$key = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
[IO.File]::WriteAllText($privateFile, "OPSFLOW_DB_PASSWORD=$password`nJWT_SECRET_BASE64=$key`nBOOTSTRAP_ADMIN_ENABLED=true`nBOOTSTRAP_ADMIN_USERNAME=releaseadmin`nBOOTSTRAP_ADMIN_EMAIL=release@example.test`nBOOTSTRAP_ADMIN_PASSWORD=$password`n")
$compose = @('compose', '--project-name', $project, '--env-file', $privateFile, '-f', (Join-Path $BundleDirectory 'compose.yaml'), '-f', $override)
try {
    foreach ($service in @('backend', 'frontend')) {
        if ((Get-CDImage $release.images.$service.tag).Id -cne $release.images.$service.id) { throw 'Image identity mismatch.' }
    }
    Invoke-CDNative docker ($compose + @('up', '-d', '--no-build', '--wait', '--wait-timeout', '240')) | Out-Host
    $binding = (Invoke-CDNative docker ($compose + @('port', 'frontend', '80'))).Trim()
    if ($binding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Unexpected test port binding.' }
    [IO.File]::AppendAllText($privateFile, "OPSFLOW_HTTP_PORT=$($Matches[1])`n")
    $checks = Test-CDSmoke $privateFile $release.revision
    Write-CDJson (Join-Path $BundleDirectory 'verification.json') @{
        revision = $release.revision; passed = $true; checkedAt = [DateTime]::UtcNow.ToString('o'); checks = $checks
    }
    Write-Output "Isolated release acceptance passed for $($release.revision)."
} finally {
    # This project has only a disposable tmpfs database. Never remove any volume.
    Invoke-CDNative docker ($compose + @('down', '--timeout', '20')) | Out-Host
    if (Test-Path -LiteralPath $privateFile) { Remove-Item -LiteralPath $privateFile }
}
