#Requires -Version 7.2
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{40}$')][string]$Revision,
    [Parameter(Mandatory)][string]$OutputDirectory
)
. "$PSScriptRoot/CD.Common.ps1"
$root = Split-Path $PSScriptRoot -Parent
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a new output directory for each release.' }
$head = (Invoke-CDNative git @('-C', $root, 'rev-parse', 'HEAD')).Trim()
if ($head -cne $Revision) { throw 'Revision must be the checked-out commit.' }
$dirty = @(Invoke-CDNative git @('-C', $root, 'status', '--porcelain', '--untracked-files=normal'))
if ($dirty.Count -gt 0) { throw 'Commit the release inputs first; dirty source cannot be given a commit identity.' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$images = @{}
foreach ($service in @('backend', 'frontend')) {
    $tag = "opsflow-${service}:$Revision"
    Invoke-CDNative docker @('build', '--build-arg', "OPSFLOW_REVISION=$Revision", '--tag', $tag, (Join-Path $root $service)) | Out-Host
    $images[$service] = @{ tag = $tag; id = (Get-CDImage $tag).Id }
}
Invoke-CDNative docker @('image', 'save', '--output', (Join-Path $OutputDirectory 'images.tar'), $images.backend.tag, $images.frontend.tag) | Out-Host
Copy-Item -LiteralPath (Join-Path $root 'compose.yaml') -Destination (Join-Path $OutputDirectory 'compose.yaml')
$files = @{}
foreach ($file in @('images.tar', 'compose.yaml')) {
    $files[$file] = (Get-FileHash -LiteralPath (Join-Path $OutputDirectory $file) -Algorithm SHA256).Hash.ToLowerInvariant()
}
$migrations = @{}
Get-ChildItem (Join-Path $root 'backend/src/main/resources/db/migration') -Filter '*.sql' | ForEach-Object {
    $migrations[$_.Name] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
Write-CDJson (Join-Path $OutputDirectory 'release.json') @{
    schema = 1; revision = $Revision; createdAt = [DateTime]::UtcNow.ToString('o')
    images = $images; files = $files; migrations = $migrations
}
Write-Output "Built deployment bundle for $Revision. No local credentials were included."
