#Requires -Version 7.2
param(
    [Parameter(Mandatory)][string]$BundleDirectory,
    [string]$EnvironmentFile = (Join-Path (Split-Path $PSScriptRoot -Parent) '.env.docker'),
    [ValidatePattern('^[a-z0-9][a-z0-9_-]+$')][string]$ProjectName = 'opsflow',
    [string]$StateDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) '.codex_work/cd')
)
. "$PSScriptRoot/CD.Common.ps1"
$BundleDirectory = [IO.Path]::GetFullPath($BundleDirectory)
$EnvironmentFile = (Resolve-Path -LiteralPath $EnvironmentFile).Path
$StateDirectory = [IO.Path]::GetFullPath($StateDirectory)
New-Item -ItemType Directory -Path $StateDirectory -Force | Out-Null
# An exclusive file handle serializes watchers and manual deployments.
$lock = [IO.File]::Open((Join-Path $StateDirectory 'deploy.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$environmentHash = (Get-FileHash -LiteralPath $EnvironmentFile -Algorithm SHA256).Hash
$previousLocation = Get-Location
try {
    $release = Read-CDManifest $BundleDirectory
    $proof = Get-Content -LiteralPath (Join-Path $BundleDirectory 'verification.json') -Raw | ConvertFrom-Json
    if (-not $proof.passed -or $proof.revision -cne $release.revision) { throw 'Release has no matching acceptance result.' }
    $stateFile = Join-Path $StateDirectory 'current.json'
    $previous = if (Test-Path -LiteralPath $stateFile) { Get-Content $stateFile -Raw | ConvertFrom-Json } else { $null }
    if ($previous -and ($previous.project -cne $ProjectName -or $previous.environmentFile -cne $EnvironmentFile)) {
        throw 'This state directory belongs to another deployment.'
    }
    $containers = @(Get-CDContainers $ProjectName)
    $db = @($containers | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq 'db' })
    if ($db.Count -ne 1 -or -not $db[0].State.Running) { throw 'Initialize and start the Docker deployment manually before enabling CD.' }
    if ($db[0].NetworkSettings.Ports.'5432/tcp') { throw 'Refusing a deployment with a published database port.' }
    # Automatic releases never apply a new schema. Migrations require a separate reviewed backup/release plan.
    $applied = @(Invoke-CDNative docker @('exec', $db[0].Id, 'psql', '-U', 'opsflow', '-d', 'opsflow', '-At', '-c', 'SELECT script FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY script'))
    Assert-CDMigrations $applied $release.migrations $(if ($previous) { $previous.migrations } else { $null })
    foreach ($service in @('backend', 'frontend')) {
        $existing = @($containers | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq $service })
        if ($existing.Count -ne 1) { throw "Expected one existing $service container for rollback." }
    }
    # Verify the old version before replacing it. Its image IDs remain available for rollback.
    $null = Test-CDSmoke $EnvironmentFile
    Invoke-CDNative docker @('image', 'load', '--input', (Join-Path $BundleDirectory 'images.tar')) | Out-Host
    $configs = Get-CDLocalConfigDigests @($release.images.backend.tag, $release.images.frontend.tag) $StateDirectory
    $localImages = @{}
    foreach ($service in @('backend', 'frontend')) {
        $image = Get-CDImage $release.images.$service.tag
        $localImages[$service] = Assert-CDLoadedImage $release.images.$service $image $configs[$release.images.$service.tag] $release.revision
    }
    $attempt = Join-Path $StateDirectory ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff') + '-' + $release.revision.Substring(0, 8))
    New-Item -ItemType Directory -Path $attempt | Out-Null
    $newCompose = Join-Path $attempt 'compose.yaml'
    Copy-Item (Join-Path $BundleDirectory 'compose.yaml') $newCompose
    $newOverride = Join-Path $attempt 'images.json'
    Write-CDJson $newOverride @{ services = @{ backend = @{ image = $release.images.backend.tag }; frontend = @{ image = $release.images.frontend.tag } } }
    $oldCompose = Join-Path $attempt 'previous-compose.yaml'
    if ($previous) { Copy-Item $previous.composeFile $oldCompose }
    else { Copy-Item (Join-Path (Split-Path $PSScriptRoot -Parent) 'compose.yaml') $oldCompose }
    $oldOverride = Join-Path $attempt 'previous-images.json'
    $oldImages = @{}
    foreach ($service in @('backend', 'frontend')) {
        $old = @($containers | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq $service })[0]
        # Use exact local IDs, even if a mutable tag has since moved.
        $oldImages[$service] = @{ image = $old.Image }
    }
    Write-CDJson $oldOverride @{ services = $oldImages }
    $common = @('compose', '--project-name', $ProjectName, '--env-file', $EnvironmentFile)
    $up = @('up', '-d', '--no-build', '--pull', 'never', '--no-deps', '--wait', '--wait-timeout', '240', 'backend', 'frontend')
    try {
        Invoke-CDNative docker ($common + @('-f', $newCompose, '-f', $newOverride) + $up) | Out-Host
        $checks = Test-CDSmoke $EnvironmentFile $release.revision
        $after = @(Get-CDContainers $ProjectName)
        $newDb = @($after | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq 'db' })[0]
        if ($newDb.Id -cne $db[0].Id) { throw 'Database container unexpectedly changed.' }
        foreach ($service in @('backend', 'frontend')) {
            $actual = @($after | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq $service })[0]
            if ($actual.Image -cne $localImages[$service] -or $actual.State.Health.Status -cne 'healthy') { throw 'Container identity/health mismatch.' }
        }
        if ((Get-FileHash -LiteralPath $EnvironmentFile -Algorithm SHA256).Hash -cne $environmentHash) { throw 'Private environment file changed.' }
        $state = @{ project = $ProjectName; revision = $release.revision; deployedAt = [DateTime]::UtcNow.ToString('o')
            environmentFile = $EnvironmentFile; composeFile = $newCompose; overrideFile = $newOverride
            migrations = $release.migrations; checks = $checks; images = $release.images; localImageIds = $localImages; databaseContainer = $db[0].Id }
        Write-CDJson (Join-Path $attempt 'result.json') (@{ status = 'deployed'; revision = $release.revision; checks = $checks })
        Write-CDJson $stateFile $state
        Write-Output "Deployed $($release.revision) to $($checks.url). Database container and credentials preserved."
    } catch {
        Write-Warning 'Release failed; restoring the previous application image IDs. Database data is not rolled back.'
        try {
            Invoke-CDNative docker ($common + @('-f', $oldCompose, '-f', $oldOverride) + $up) | Out-Host
            $null = Test-CDSmoke $EnvironmentFile
            Write-CDJson (Join-Path $attempt 'result.json') @{ status = 'rolled-back'; revision = $release.revision; checkedAt = [DateTime]::UtcNow.ToString('o') }
        } catch {
            Write-CDJson (Join-Path $attempt 'result.json') @{ status = 'rollback-failed'; revision = $release.revision }
            throw "Deployment and rollback failed. Inspect the local Docker state. Evidence: $attempt"
        }
        throw "Release failed and previous application images were restored. Evidence: $attempt"
    }
} finally {
    Set-Location $previousLocation
    $lock.Dispose()
}
