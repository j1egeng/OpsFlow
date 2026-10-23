#Requires -Version 7.2
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-CDNative {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed (exit $LASTEXITCODE)." }
}

function Read-CDEnvironment([string]$Path) {
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            $values[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
        }
    }
    return $values
}

function Write-CDJson([string]$Path, $Value) {
    $temporary = "$Path.new"
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
    [IO.File]::Move($temporary, $Path, $true)
}

function Read-CDManifest([string]$BundleDirectory) {
    $manifest = Get-Content -LiteralPath (Join-Path $BundleDirectory 'release.json') -Raw | ConvertFrom-Json
    if ($manifest.schema -ne 1 -or $manifest.revision -cnotmatch '^[a-f0-9]{40}$') {
        throw 'Invalid deployment manifest.'
    }
    foreach ($service in @('backend', 'frontend')) {
        $image = $manifest.images.$service
        if ($image.tag -cne "opsflow-${service}:$($manifest.revision)" -or $image.id -cnotmatch '^sha256:[a-f0-9]{64}$' -or
            $image.configDigest -cnotmatch '^sha256:[a-f0-9]{64}$') {
            throw 'Unexpected release image identity.'
        }
    }
    foreach ($file in @('images.tar', 'compose.yaml')) {
        $hash = (Get-FileHash -LiteralPath (Join-Path $BundleDirectory $file) -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -cne $manifest.files.$file) { throw "Release file checksum mismatch: $file" }
    }
    return $manifest
}

function Get-CDImage([string]$Image) {
    $value = Invoke-CDNative docker @('image', 'inspect', $Image)
    return @($value | ConvertFrom-Json)[0]
}

function Get-CDArchiveConfigs([string]$Archive) {
    # Engine IDs may describe a config, manifest or index. The exact config blob is portable.
    # Docker save includes a compatibility manifest even with the containerd image store.
    $entries = @( (Invoke-CDNative tar @('-xOf', $Archive, 'manifest.json') | ConvertFrom-Json) )
    $scratch = Join-Path ([IO.Path]::GetTempPath()) ('opsflow-image-config-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $scratch | Out-Null
    $configs = @{}
    foreach ($entry in $entries) {
        $configName = $entry.Config
        if ($configName -cnotmatch '^([a-f0-9]{64}\.json|blobs/sha256/[a-f0-9]{64})$') { throw 'Unexpected image config path.' }
        Invoke-CDNative tar @('-xf', $Archive, '-C', $scratch, '--', $configName) | Out-Null
        $configPath = Join-Path $scratch $configName
        $digest = 'sha256:' + (Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash.ToLowerInvariant()
        foreach ($tag in $entry.RepoTags) {
            if ($configs.ContainsKey($tag)) { throw 'Duplicate image tag in archive.' }
            $configs[$tag] = $digest
        }
    }
    return $configs
}

function Get-CDLocalConfigDigests([string[]]$Images, [string]$Directory) {
    $archive = Join-Path $Directory ('loaded-images-' + [Guid]::NewGuid().ToString('N') + '.tar')
    try {
        Invoke-CDNative docker (@('image', 'save', '--output', $archive) + $Images) | Out-Host
        return Get-CDArchiveConfigs $archive
    } finally {
        # Only this freshly created archive is removed; no Docker image or volume is deleted.
        if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
    }
}

function Assert-CDLoadedImage($Expected, $Actual, [string]$ConfigDigest, [string]$Revision) {
    if ($ConfigDigest -cne $Expected.configDigest -or
        $Actual.Config.Labels.'org.opencontainers.image.revision' -cne $Revision) {
        throw 'Loaded image config digest or revision mismatch.'
    }
    return $Actual.Id
}

function Get-CDContainers([string]$ProjectName) {
    $ids = @(Invoke-CDNative docker @('ps', '-aq', '--filter', "label=com.docker.compose.project=$ProjectName"))
    if ($ids.Count -eq 0) { return @() }
    return @( (Invoke-CDNative docker (@('inspect') + $ids) | ConvertFrom-Json) )
}

function Get-CDCandidate($Commit, $Run, [string]$Repository) {
    # Fail closed on the newest attempt, including queued runs, failed reruns and PR checks.
    if ($Commit.sha -cnotmatch '^[a-f0-9]{40}$' -or $Run.head_sha -cne $Commit.sha -or
        $Run.head_branch -cne 'main' -or $Run.event -cne 'push' -or
        $Run.status -cne 'completed' -or $Run.conclusion -cne 'success' -or
        $Run.path -cne '.github/workflows/ci.yml' -or
        $Run.head_repository.full_name -cne $Repository) { return $null }
    return $Commit.sha
}

function Assert-CDMigrations($AppliedScripts, $Migrations, $PreviousMigrations = $null) {
    $expected = @($Migrations.PSObject.Properties.Name | Sort-Object)
    if (@(Compare-Object @($AppliedScripts | Sort-Object) $expected).Count -ne 0) {
        throw 'Schema migration detected; automatic deployment is blocked.'
    }
    if ($null -ne $PreviousMigrations) {
        foreach ($name in $expected) {
            if ($PreviousMigrations.$name -cne $Migrations.$name) { throw 'Migration files changed; automatic deployment is blocked.' }
        }
    }
}

function Test-CDSmoke {
    param([string]$EnvironmentFile, [string]$ExpectedRevision = '')
    $config = Read-CDEnvironment $EnvironmentFile
    $port = if ($config.ContainsKey('OPSFLOW_HTTP_PORT')) { [int]$config.OPSFLOW_HTTP_PORT } else { 8088 }
    $base = "http://127.0.0.1:$port"
    $homeResponse = Invoke-WebRequest "$base/" -TimeoutSec 20
    if ($homeResponse.StatusCode -ne 200 -or $homeResponse.Content -notmatch '<div id="root">') { throw 'Frontend smoke check failed.' }
    $health = Invoke-RestMethod "$base/actuator/health" -TimeoutSec 20
    if ($health.status -cne 'UP') { throw 'Backend/database health failed.' }
    foreach ($route in @('/login', '/dashboard', '/services', '/incidents', '/changes', '/users', '/account', '/audit-logs')) {
        $page = Invoke-WebRequest "$base$route" -TimeoutSec 20
        if ($page.StatusCode -ne 200 -or $page.Content -cne $homeResponse.Content) { throw "SPA refresh failed: $route" }
    }
    if ($ExpectedRevision) {
        $version = Invoke-RestMethod "$base/release.json" -TimeoutSec 20
        if ($version.revision -cne $ExpectedRevision) { throw 'Served frontend revision does not match the release.' }
    }
    try {
        $body = @{ username = $config.BOOTSTRAP_ADMIN_USERNAME; password = $config.BOOTSTRAP_ADMIN_PASSWORD } | ConvertTo-Json -Compress
        $login = Invoke-RestMethod "$base/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 20
        $headers = @{ Authorization = "Bearer $($login.accessToken)" }
        $me = Invoke-RestMethod "$base/api/v1/auth/me" -Headers $headers -TimeoutSec 20
        if ($me.username -cne $config.BOOTSTRAP_ADMIN_USERNAME) { throw 'Wrong signed-in account.' }
        $audit = Invoke-RestMethod "$base/api/v1/audit-logs?action=USER_LOGIN&size=100" -Headers $headers -TimeoutSec 20
        $event = @($audit.items | Where-Object { $_.actor -and $_.actor.id -eq $me.id }) | Select-Object -First 1
        if (-not $event) { throw 'Login audit record not found.' }
        $detail = Invoke-RestMethod "$base/api/v1/audit-logs/$($event.id)" -Headers $headers -TimeoutSec 20
        if ($detail.action -cne 'USER_LOGIN') { throw 'Audit detail failed.' }
    } catch {
        # Do not print request bodies, credentials, tokens, or server response bodies.
        throw 'Authenticated smoke checks failed. Check the local administrator credentials and application logs privately.'
    }
    return @{ healthy = $true; login = $true; audit = $true; spaRoutes = 8; revision = $ExpectedRevision; url = $base }
}
