#Requires -Version 7.2
. "$PSScriptRoot/../CD.Common.ps1"
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('opsflow-bundle-gates-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
$sha = 'a' * 40
$manifest = @{ schema = 1; revision = $sha; files = @{}; images = @{} }
foreach ($file in @('images.tar', 'compose.yaml')) {
    [IO.File]::WriteAllText((Join-Path $scratch $file), 'fixture')
    $manifest.files[$file] = (Get-FileHash (Join-Path $scratch $file) -Algorithm SHA256).Hash.ToLowerInvariant()
}
foreach ($service in @('backend', 'frontend')) { $manifest.images[$service] = @{ tag = "opsflow-${service}:$sha"; id = 'sha256:' + ('b' * 64) } }
$path = Join-Path $scratch 'release.json'
Write-CDJson $path $manifest
$null = Read-CDManifest $scratch
$cases = 1
foreach ($file in @('images.tar', 'compose.yaml')) {
    [IO.File]::WriteAllText((Join-Path $scratch $file), 'tampered')
    $blocked = $false
    try { $null = Read-CDManifest $scratch } catch { $blocked = $true }
    if (-not $blocked) { throw "Tampering was accepted: $file" }
    [IO.File]::WriteAllText((Join-Path $scratch $file), 'fixture')
    $cases++
}
foreach ($case in @('schema', 'revision', 'tag', 'id')) {
    $copy = $manifest | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    switch ($case) {
        schema { $copy.schema = 2 }
        revision { $copy.revision = '../../unsafe' }
        tag { $copy.images.frontend.tag = 'untrusted/frontend:latest' }
        id { $copy.images.backend.id = 'latest' }
    }
    Write-CDJson $path $copy
    $blocked = $false
    try { $null = Read-CDManifest $scratch } catch { $blocked = $true }
    if (-not $blocked) { throw "Invalid manifest was accepted: $case" }
    $cases++
}
Write-Output "$cases deployment bundle integrity cases passed."
