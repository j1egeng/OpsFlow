#Requires -Version 7.2
param([Parameter(Mandatory)][string]$BundleDirectory)
. "$PSScriptRoot/../CD.Common.ps1"
$BundleDirectory = [IO.Path]::GetFullPath($BundleDirectory)
$release = Read-CDManifest $BundleDirectory
$project = 'opsflow-cd-test-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
$scratch = Join-Path ([IO.Path]::GetTempPath()) $project
$state = Join-Path $scratch 'state'
$privateFile = Join-Path $scratch '.env.docker'
$override = Join-Path $scratch 'test.yaml'
New-Item -ItemType Directory -Path $scratch | Out-Null
$password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
$key = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
[IO.File]::WriteAllText($privateFile, "OPSFLOW_DB_PASSWORD=$password`nJWT_SECRET_BASE64=$key`nBOOTSTRAP_ADMIN_ENABLED=true`nBOOTSTRAP_ADMIN_USERNAME=releaseadmin`nBOOTSTRAP_ADMIN_EMAIL=release@example.test`nBOOTSTRAP_ADMIN_PASSWORD=$password`n")
[IO.File]::WriteAllText($override, @"
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
"@)
$compose = @('compose', '--project-name', $project, '--env-file', $privateFile, '-f', (Join-Path $BundleDirectory 'compose.yaml'), '-f', $override)
try {
    Invoke-CDNative docker ($compose + @('up', '-d', '--no-build', '--wait', '--wait-timeout', '240')) | Out-Host
    $binding = (Invoke-CDNative docker ($compose + @('port', 'frontend', '80'))).Trim()
    if ($binding -notmatch '^127\.0\.0\.1:(\d+)$') { throw 'Unexpected test binding.' }
    [IO.File]::AppendAllText($privateFile, "OPSFLOW_HTTP_PORT=$($Matches[1])`n")
    $privateHash = (Get-FileHash $privateFile -Algorithm SHA256).Hash
    $db = @((Get-CDContainers $project) | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq 'db' })[0]
    $marker = [Guid]::NewGuid().ToString('N')
    Invoke-CDNative docker @('exec', $db.Id, 'psql', '-U', 'opsflow', '-d', 'opsflow', '-v', 'ON_ERROR_STOP=1', '-c', "CREATE TABLE cd_persistence_probe (value text); INSERT INTO cd_persistence_probe VALUES ('$marker');") | Out-Host
    & "$PSScriptRoot/../Deploy-Release.ps1" -BundleDirectory $BundleDirectory -EnvironmentFile $privateFile -ProjectName $project -StateDirectory $state
    $currentFile = Join-Path $state 'current.json'
    $successfulState = [IO.File]::ReadAllText($currentFile)
    if (($successfulState | ConvertFrom-Json).revision -cne $release.revision) { throw 'Successful release was not recorded.' }

    # Simulate a bundle that passed acceptance but regresses at the deployment target.
    # Both containers stay healthy; the frontend serves an incorrect version, so post-deploy smoke must roll back.
    $badRevision = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(20)).ToLowerInvariant()
    $badBundle = Join-Path $scratch 'bad-bundle'
    $buildContext = Join-Path $scratch 'bad-context'
    New-Item -ItemType Directory -Path $badBundle, $buildContext | Out-Null
    $bad = $release | ConvertTo-Json -Depth 20 | ConvertFrom-Json
    $bad.revision = $badRevision
    foreach ($service in @('backend', 'frontend')) {
        $dockerfile = "FROM $($release.images.$service.tag)`nLABEL org.opencontainers.image.revision=$badRevision`n"
        if ($service -eq 'frontend') { $dockerfile += ('RUN printf ''{"revision":"wrong-version"}'' > /usr/share/nginx/html/release.json' + [Environment]::NewLine) }
        [IO.File]::WriteAllText((Join-Path $buildContext 'Dockerfile'), $dockerfile)
        $tag = "opsflow-${service}:$badRevision"
        Invoke-CDNative docker @('build', '-t', $tag, $buildContext) | Out-Host
        $bad.images.$service.tag = $tag
        $bad.images.$service.id = (Get-CDImage $tag).Id
    }
    Copy-Item (Join-Path $BundleDirectory 'compose.yaml') (Join-Path $badBundle 'compose.yaml')
    Invoke-CDNative docker @('image', 'save', '-o', (Join-Path $badBundle 'images.tar'), $bad.images.backend.tag, $bad.images.frontend.tag) | Out-Host
    foreach ($file in @('images.tar', 'compose.yaml')) { $bad.files.$file = (Get-FileHash (Join-Path $badBundle $file) -Algorithm SHA256).Hash.ToLowerInvariant() }
    Write-CDJson (Join-Path $badBundle 'release.json') $bad
    Write-CDJson (Join-Path $badBundle 'verification.json') @{ revision = $badRevision; passed = $true }
    $failed = $false
    try { & "$PSScriptRoot/../Deploy-Release.ps1" -BundleDirectory $badBundle -EnvironmentFile $privateFile -ProjectName $project -StateDirectory $state }
    catch { $failed = $true }
    if (-not $failed) { throw 'Broken release was accepted.' }
    $outcomes = @(Get-ChildItem $state -Filter result.json -Recurse | ForEach-Object { Get-Content $_.FullName -Raw | ConvertFrom-Json })
    if (@($outcomes | Where-Object { $_.status -eq 'rolled-back' -and $_.revision -ceq $badRevision }).Count -ne 1) { throw 'Rollback was not recorded.' }
    if ([IO.File]::ReadAllText($currentFile) -cne $successfulState) { throw 'A failed release overwrote successful deployment state.' }
    $null = Test-CDSmoke $privateFile $release.revision
    foreach ($service in @('backend', 'frontend')) {
        $container = @((Get-CDContainers $project) | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq $service })[0]
        if ($container.Image -cne $release.images.$service.id) { throw 'Exact previous image ID was not restored.' }
    }
    $afterDb = @((Get-CDContainers $project) | Where-Object { $_.Config.Labels.'com.docker.compose.service' -eq 'db' })[0]
    if ($afterDb.Id -cne $db.Id) { throw 'CD replaced the database container.' }
    $stored = (Invoke-CDNative docker @('exec', $db.Id, 'psql', '-U', 'opsflow', '-d', 'opsflow', '-At', '-c', 'SELECT value FROM cd_persistence_probe')).Trim()
    if ($stored -cne $marker) { throw 'Database persistence check failed.' }
    if ((Get-FileHash $privateFile -Algorithm SHA256).Hash -cne $privateHash) { throw 'CD modified private credentials.' }
    Write-CDJson (Join-Path $BundleDirectory 'deployment-test.json') @{ passed = $true; revision = $release.revision
        checks = @('deployment', 'regression-rejected', 'exact-image-rollback', 'state-preserved', 'same-database-container', 'data-preserved', 'credentials-preserved') }
    Write-Output 'Real Docker deployment, failed-release rollback and persistence tests passed.'
} finally {
    Invoke-CDNative docker ($compose + @('down', '--timeout', '20')) | Out-Host
    if (Test-Path -LiteralPath $privateFile) { Remove-Item -LiteralPath $privateFile }
}
