#Requires -Version 7.2
. "$PSScriptRoot/../CD.Common.ps1"
$sha = 'a' * 40
$other = 'b' * 40
$repository = 'j1egeng/OpsFlow'
$commit = [pscustomobject]@{ sha = $sha }
$base = @{ head_sha = $sha; head_branch = 'main'; event = 'push'; status = 'completed'; conclusion = 'success'
    path = '.github/workflows/ci.yml'; head_repository = @{ full_name = $repository } }
$count = 0
function Assert-Gate($Run, [bool]$Expected, [string]$Case) {
    $accepted = [bool](Get-CDCandidate $commit ([pscustomobject]$Run) $repository)
    if ($accepted -ne $Expected) { throw "Deployment gate failed: $Case" }
    $script:count++
}
Assert-Gate $base $true 'successful main push'
foreach ($case in @(
    @{ event = 'pull_request' }, @{ event = 'workflow_dispatch' }, @{ head_branch = 'feature' },
    @{ head_sha = $other }, @{ status = 'in_progress' }, @{ status = 'queued' },
    @{ conclusion = 'failure' }, @{ conclusion = 'cancelled' }, @{ conclusion = 'skipped' },
    @{ path = '.github/workflows/untrusted.yml' }, @{ head_repository = @{ full_name = 'someone/OpsFlow' } }
)) {
    $run = $base.Clone()
    foreach ($key in $case.Keys) { $run[$key] = $case[$key] }
    Assert-Gate $run $false ($case | ConvertTo-Json -Compress)
}
Write-Output "$count deployment gate cases passed."
$migrations = [pscustomobject]@{ 'V1__initial.sql' = 'one'; 'V2__audit.sql' = 'two' }
Assert-CDMigrations @('V1__initial.sql', 'V2__audit.sql') $migrations $migrations
foreach ($case in @(
    @{ applied = @('V1__initial.sql'); previous = $migrations },
    @{ applied = @('V1__initial.sql', 'V2__audit.sql', 'V3__other.sql'); previous = $migrations },
    @{ applied = @('V1__initial.sql', 'V2__audit.sql'); previous = [pscustomobject]@{ 'V1__initial.sql' = 'changed'; 'V2__audit.sql' = 'two' } }
)) {
    $blocked = $false
    try { Assert-CDMigrations $case.applied $migrations $case.previous } catch { $blocked = $true }
    if (-not $blocked) { throw 'Unsafe migration was accepted.' }
}
Write-Output '4 migration gate cases passed.'
