#Requires -Version 7.2
param(
    [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')][string]$Repository = 'j1egeng/OpsFlow',
    [string]$EnvironmentFile = (Join-Path (Split-Path $PSScriptRoot -Parent) '.env.docker'),
    [ValidatePattern('^[a-z0-9][a-z0-9_-]+$')][string]$ProjectName = 'opsflow',
    [string]$StateDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) '.codex_work/cd'),
    [ValidateRange(30,3600)][int]$PollSeconds = 60,
    [switch]$Once,
    [switch]$CheckOnly
)
. "$PSScriptRoot/CD.Common.ps1"
$StateDirectory = [IO.Path]::GetFullPath($StateDirectory)
New-Item -ItemType Directory -Path $StateDirectory -Force | Out-Null
$watchLock = [IO.File]::Open((Join-Path $StateDirectory 'watch.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
$client = [Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromMinutes(15)
$client.DefaultRequestHeaders.Add('User-Agent', 'OpsFlow-Local-CD')
$client.DefaultRequestHeaders.Add('Accept', 'application/vnd.github+json')
$client.DefaultRequestHeaders.Add('X-GitHub-Api-Version', '2026-03-10')
$api = "https://api.github.com/repos/$Repository"

function Get-CDToken {
    if ($env:OPSFLOW_GITHUB_TOKEN) { return $env:OPSFLOW_GITHUB_TOKEN }
    # Reuse the local Git credential helper without prompting or printing its output.
    $info = [Diagnostics.ProcessStartInfo]::new('git')
    $info.ArgumentList.Add('credential'); $info.ArgumentList.Add('fill')
    $info.RedirectStandardInput = $true; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.Environment['GIT_TERMINAL_PROMPT'] = '0'
    $info.Environment['GCM_INTERACTIVE'] = 'Never'
    $process = [Diagnostics.Process]::Start($info)
    try {
        $process.StandardInput.Write("protocol=https`nhost=github.com`n`n"); $process.StandardInput.Close()
        $outputTask = $process.StandardOutput.ReadToEndAsync()
        $errorTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(15000)) { $process.Kill($true); throw 'Git credential lookup timed out.' }
        $output = $outputTask.GetAwaiter().GetResult()
        $null = $errorTask.GetAwaiter().GetResult()
        if ($process.ExitCode -eq 0 -and $output -match '(?m)^password=([^\r\n]+)') { return $Matches[1] }
    } finally { $process.Dispose() }
    throw 'GitHub authentication is required to download Actions artifacts. Configure a local read-only token or Git credential helper.'
}

function Get-CDGitHub([string]$Url) {
    $response = $client.GetAsync($Url).GetAwaiter().GetResult()
    try {
        if (-not $response.IsSuccessStatusCode) { throw "GitHub API request failed (HTTP $([int]$response.StatusCode))." }
        return ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)
    } finally { $response.Dispose() }
}

function Invoke-CDPoll {
    $commit = Get-CDGitHub "$api/commits/main"
    $runs = Get-CDGitHub "$api/actions/workflows/ci.yml/runs?branch=main&event=push&head_sha=$($commit.sha)&per_page=10"
    $run = @($runs.workflow_runs | Sort-Object id -Descending) | Select-Object -First 1
    if (-not $run -or -not (Get-CDCandidate $commit $run $Repository)) {
        Write-Output 'Waiting: the current main commit has no successful trusted push CI run.'
        return
    }
    $stateFile = Join-Path $StateDirectory 'current.json'
    if (Test-Path -LiteralPath $stateFile) {
        $current = Get-Content $stateFile -Raw | ConvertFrom-Json
        if ($current.project -cne $ProjectName -or $current.environmentFile -cne [IO.Path]::GetFullPath($EnvironmentFile)) {
            throw 'This state directory belongs to another deployment.'
        }
        if ($current.revision -ceq $commit.sha) { Write-Output "Already deployed: $($commit.sha)"; return }
    }
    $artifacts = Get-CDGitHub "$api/actions/runs/$($run.id)/artifacts?per_page=100"
    $artifacts = @($artifacts.artifacts | Where-Object { $_.name -ceq "opsflow-release-$($commit.sha)" -and -not $_.expired })
    if ($artifacts.Count -ne 1) {
        Write-Output 'Waiting: successful CI has no unique unexpired deployment bundle (older CI runs are not deployable).'
        return
    }
    $artifact = $artifacts[0]
    if ($artifact.workflow_run.id -ne $run.id -or $artifact.workflow_run.head_sha -cne $commit.sha -or
        $artifact.workflow_run.head_branch -cne 'main' -or $artifact.digest -cnotmatch '^sha256:[a-f0-9]{64}$') {
        throw 'Artifact provenance or SHA-256 digest is missing/mismatched.'
    }
    if ($CheckOnly) { Write-Output "Ready: $($commit.sha), CI $($run.html_url), artifact $($artifact.id). No changes made."; return }
    $download = Join-Path $StateDirectory ('download-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $download | Out-Null
    $zipFile = Join-Path $download 'release.zip'
    $bundle = Join-Path $download 'bundle'
    $response = $client.GetAsync("$api/actions/artifacts/$($artifact.id)/zip", [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    try {
        if (-not $response.IsSuccessStatusCode) { throw "Artifact download failed (HTTP $([int]$response.StatusCode))." }
        $stream = [IO.File]::Create($zipFile)
        try { $response.Content.CopyToAsync($stream).GetAwaiter().GetResult() } finally { $stream.Dispose() }
    } finally { $response.Dispose() }
    if ((Get-FileHash $zipFile -Algorithm SHA256).Hash.ToLowerInvariant() -cne $artifact.digest.Substring(7)) { throw 'Downloaded artifact checksum mismatch.' }
    $zip = [IO.Compression.ZipFile]::OpenRead($zipFile)
    try {
        $names = @($zip.Entries.FullName | Sort-Object)
        $allowed = @('compose.yaml', 'deployment-test.json', 'images.tar', 'release.json', 'verification.json')
        if (@(Compare-Object $names $allowed).Count -ne 0 -or $names.Count -ne 5) { throw 'Unexpected artifact contents.' }
    } finally { $zip.Dispose() }
    Expand-Archive -LiteralPath $zipFile -DestinationPath $bundle
    $manifest = Read-CDManifest $bundle
    if ($manifest.revision -cne $commit.sha) { throw 'Bundle commit differs from the CI commit.' }
    $deploymentTest = Get-Content (Join-Path $bundle 'deployment-test.json') -Raw | ConvertFrom-Json
    if (-not $deploymentTest.passed -or $deploymentTest.revision -cne $commit.sha) { throw 'Bundle has no matching deployment/rollback test result.' }
    # A newer merge or a failed/running rerun must not be overwritten by this release.
    $latest = Get-CDGitHub "$api/commits/main"
    $latestRun = Get-CDGitHub "$api/actions/runs/$($run.id)"
    if (-not (Get-CDCandidate $latest $latestRun $Repository)) { throw 'main or CI state changed during download; retry later.' }
    & "$PSScriptRoot/Deploy-Release.ps1" -BundleDirectory $bundle -EnvironmentFile $EnvironmentFile -ProjectName $ProjectName -StateDirectory $StateDirectory
    Write-CDJson (Join-Path $StateDirectory 'github.json') @{ revision = $commit.sha; runUrl = $run.html_url; runId = $run.id; artifactId = $artifact.id }
}

try {
    $token = Get-CDToken
    $client.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
    $token = $null
    Write-Output "OpsFlow CD watches $Repository main. Stop with Ctrl+C. Credentials stay on this computer."
    do {
        try { Invoke-CDPoll }
        catch {
            if ($Once) { throw }
            Write-Warning "CD attempt failed: $($_.Exception.Message)"
            # Avoid repeated login attempts, rebuilds or rollbacks when a release is broken.
            for ($pause = 0; $pause -lt 15; $pause++) { Start-Sleep -Seconds 60 }
        }
        if (-not $Once) { Start-Sleep -Seconds $PollSeconds }
    } while (-not $Once)
} finally {
    $client.Dispose()
    $watchLock.Dispose()
}
