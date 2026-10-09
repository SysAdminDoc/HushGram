<#
.SYNOPSIS
    Patches every Instagram build in the fixture folder with the whole catalog and reports what
    applied, what failed and what the desktop CLI warned about on each.

.DESCRIPTION
    A release checks more than the declared build: the other arm64 builds of the same version, the
    x86 ones and older versions kept beside it, to see what still applies where. This runs the
    desktop CLI over each of them with every patch in patches-list.json, the way
    verify-all-patches.ps1 does (--exclusive --continue-on-error --unsigned), with -f for a build
    the catalog doesn't declare.

    The builds come from -FixtureDir (HUSHGRAM_FIXTURE_DIR by default). Each one is named
    instagram-<version>-<version code>, as a file (.apks, .apkm, .xapk or .apk) or as a folder
    holding base.apk. A build kept both ways is patched from its bundle: a folder's base.apk is the
    base split, and patching the bundle is what a phone installs. A build with only its base split
    is patched from that, and the summary says so, since that APK won't install without its
    config splits.

    Each build takes a slot of its own in the machine's build queue (build-jobs.ps1), at release
    priority when HUSHGRAM_ALLOW_RELEASE=1. Its patched APK and working folder are deleted as soon
    as its report is read. The CLI's report, its log and summary.json stay in -OutDir.

    With -FromGate, a build the push gate already patched for HEAD with this bundle, catalog, CLI
    and APK (gate-evidence.ps1) is read from the gate's kept report instead of being patched again.
    With -ListOnly the builds and the input each would be patched from are listed, and nothing is
    patched.

    Exits 1 when a run breaks (the CLI exits non-zero or writes no report) or a declared build
    misses a patch. A forced build missing some is reported and, without -RequireAll, doesn't
    fail the run, since older and other builds are expected to miss a few.

.EXAMPLE
    scripts/release/patch-all-builds.ps1 -OutDir C:\scratch\all-builds

.NOTES
    Based on the variant patch helper used for the v0.0.7 release. Copyright 2026 HushGram
    contributors. https://github.com/SysAdminDoc/HushGram GPL-3.0-only.
#>
[CmdletBinding()]
param(
    [string]$FixtureDir,
    [string]$OutDir,
    [string]$Root,
    [string]$Bundle,
    [string]$PatchList,
    [string]$DesktopJar,
    [string]$Java,
    [string]$Aapt2,
    # Version codes or version names to keep, such as 385611438; every build when left out.
    [string[]]$Only,
    [switch]$ListOnly,
    [switch]$FromGate,
    # Fail the run when any build, forced or not, misses a patch.
    [switch]$RequireAll
)

$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
if (-not $Root) { $Root = Split-Path -Parent $scripts }
. (Join-Path $scripts 'common.ps1')
. (Join-Path $scripts 'patch-target.ps1')
. (Join-Path $scripts 'patch-report.ps1')
. (Join-Path $scripts 'build-jobs.ps1')

if (-not $FixtureDir) { $FixtureDir = [Environment]::GetEnvironmentVariable('HUSHGRAM_FIXTURE_DIR') }
if (-not $FixtureDir) { $FixtureDir = [Environment]::GetEnvironmentVariable('HUSHGRAM_FIXTURE_DIR', [EnvironmentVariableTarget]::User) }
if (-not $FixtureDir -or -not (Test-Path -LiteralPath $FixtureDir -PathType Container)) {
    throw 'No fixture folder: pass -FixtureDir or set HUSHGRAM_FIXTURE_DIR.'
}
if ($env:HUSHGRAM_ALLOW_RELEASE -eq '1') { $env:BUILD_QUEUE_PRIORITY = 'release' }

function Get-FixtureBuilds {
    <#
    One entry per build in -Folder, with the input it is patched from: its bundle when there is
    one, then a lone APK, then a folder's base split.
    #>
    param([Parameter(Mandatory = $true)][string]$Folder)
    $pattern = '^instagram-(?<version>\d+(?:\.\d+)+)-(?<code>\d+)(?:-.*)?$'
    $found = @{}
    foreach ($item in @(Get-ChildItem -LiteralPath $Folder)) {
        if ($item.PSIsContainer) {
            $match = [regex]::Match($item.Name, $pattern)
            $base = Join-Path $item.FullName 'base.apk'
            if (-not $match.Success -or -not (Test-Path -LiteralPath $base -PathType Leaf)) { continue }
            $kind = 'base split'; $rank = 3; $path = $base
        } else {
            $match = [regex]::Match([IO.Path]::GetFileNameWithoutExtension($item.Name), $pattern)
            $extension = $item.Extension.ToLowerInvariant()
            if (-not $match.Success -or $extension -notin '.apks', '.apkm', '.xapk', '.apk') { continue }
            $kind = if ($extension -eq '.apk') { 'APK' } else { 'bundle' }
            $rank = if ($extension -eq '.apk') { 2 } else { 1 }
            $path = $item.FullName
        }
        $key = "$($match.Groups['version'].Value)-$($match.Groups['code'].Value)"
        if (-not $found.ContainsKey($key) -or $found[$key].Rank -gt $rank -or
                ($found[$key].Rank -eq $rank -and [string]::CompareOrdinal($found[$key].Path, $path) -gt 0)) {
            $found[$key] = [pscustomobject]@{
                Label = $key; Version = $match.Groups['version'].Value; Code = $match.Groups['code'].Value
                Kind = $kind; Rank = $rank; Path = $path
            }
        }
    }
    return @($found.Values | Sort-Object Version, Code)
}

$builds = @(Get-FixtureBuilds -Folder $FixtureDir)
# -Only 385611438,385611395 arrives as one string through pwsh -File.
$Only = @($Only | ForEach-Object { "$_" -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($Only) { $builds = @($builds | Where-Object { $_.Code -in $Only -or $_.Version -in $Only -or $_.Label -in $Only }) }
if ($builds.Count -eq 0) { throw "No Instagram build in $FixtureDir$(if ($Only) { " matches $($Only -join ', ')" })." }
foreach ($build in $builds) {
    Write-Host "[all-builds] $($build.Label): $($build.Kind) $(Split-Path -Leaf $build.Path)"
}
if ($ListOnly) { exit 0 }

. (Join-Path $scripts 'Resolve-Java.ps1')
. (Join-Path $scripts 'apk-facts.ps1')
$Java = Resolve-Java -Explicit $Java
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $Root
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $Root -Required
if (-not $PatchList) { $PatchList = Join-Path $Root 'patches-list.json' }
if (-not $Bundle) { $Bundle = Get-ReleaseBundlePath -Root $Root -Version (Get-BundleVersion -Root $Root) }
$gateRun = $null
if ($FromGate) {
    . (Join-Path $scripts 'gate-evidence.ps1')
    $gateRun = Find-GateEvidence -Root $Root -AllowIndexCommits -Prefix '[all-builds]'
    if ($gateRun -and -not (Test-Path -LiteralPath $Bundle -PathType Leaf)) { $Bundle = $gateRun.Bundle }
}
if (-not (Test-Path -LiteralPath $Bundle -PathType Leaf)) { throw "No bundle at $Bundle. Run :patches:buildAndroid, or pass -Bundle." }
$Bundle = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Bundle)
$bundleHash = (Get-FileHash -LiteralPath $Bundle -Algorithm SHA256).Hash.ToUpperInvariant()
$catalog = Get-Content -LiteralPath $PatchList -Raw | ConvertFrom-Json
$names = @($catalog.patches | ForEach-Object { [string]$_.name })
$target = Get-PatchTarget -PatchList $catalog
if (-not $OutDir) { $OutDir = Join-Path ([IO.Path]::GetTempPath()) "hushgram-all-builds-$([guid]::NewGuid().ToString('N').Substring(0, 12))" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path -LiteralPath $OutDir).Path
Write-Host "[all-builds] $($names.Count) patches from $(Split-Path -Leaf $Bundle) on $($builds.Count) builds, reports in $OutDir"

$summary = New-Object System.Collections.Generic.List[object]
foreach ($build in $builds) {
    $buildDir = Join-Path $OutDir $build.Label
    New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
    $work = Join-Path $buildDir 'work'
    $result = Join-Path $buildDir 'result.json'
    $log = Join-Path $buildDir 'patch.log'
    $entry = [ordered]@{ build = $build.Label; input = Split-Path -Leaf $build.Path; kind = $build.Kind
        versionName = $null; versionCode = $null; forced = $null; source = 'patched here'; cliExit = $null
        applied = 0; failed = @(); warnings = @(); ok = $false; broken = $false }
    $job = $null
    try {
        $job = Enter-HeavyJob -Label "all-builds $($build.Label)"
        $stock = Get-ApkManifestFacts -Apk (Get-BaseApk -Apk $build.Path -Destination (Join-Path $work 'stock-base.apk')) -Aapt2 $Aapt2
        $entry.versionName = [string]$stock.versionName
        $entry.versionCode = [string]$stock.versionCode
        $forced = -not (Test-DeclaredBuild -Target $target -VersionName $entry.versionName -VersionCode $entry.versionCode)
        $entry.forced = $forced
        $kept = $null
        if ($gateRun) {
            $notKept = $null
            $kept = Get-GateKeptRun -Evidence $gateRun -VersionName $entry.versionName -Apk $build.Path -BundleSha256 $bundleHash `
                -PatchList $PatchList -DesktopJar $DesktopJar -Forced $forced -Why ([ref]$notKept)
            if (-not $kept) { Write-Host "[all-builds] $($build.Label): $notKept, so it is patched here" }
        }
        if ($kept) {
            Copy-Item -LiteralPath $kept.Result -Destination $result -Force
            $entry.source = "the gate's run of $($gateRun.Commit.Substring(0, 12))"
            $entry.cliExit = 0
        } else {
            $arguments = @('patch', '--exclusive', '--continue-on-error', '--unsigned', '-p', $Bundle,
                '-o', (Join-Path $work 'patched.apk'), '-t', (Join-Path $work 'tmp'), '-r', $result)
            if ($forced) { $arguments += '-f' }
            foreach ($name in $names) { $arguments += '-e'; $arguments += $name }
            $arguments += $build.Path
            # Continue for the call alone: the CLI logs WARNING and SEVERE on stderr, which Windows
            # PowerShell 5.1 turns into a terminating error under Stop.
            $preference = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                $global:LASTEXITCODE = -1
                & $Java '-jar' $DesktopJar @arguments 2>&1 | ForEach-Object { "$_" } | Set-Content -LiteralPath $log -Encoding UTF8
                $entry.cliExit = $LASTEXITCODE
            } finally {
                $ErrorActionPreference = $preference
            }
            $entry.warnings = @(Get-Content -LiteralPath $log | Where-Object { $_ -match '\b(WARNING|SEVERE)\b' } | ForEach-Object { $_.Trim() })
        }
        if (Test-Path -LiteralPath $result -PathType Leaf) {
            $report = Get-Content -LiteralPath $result -Raw | ConvertFrom-Json
            $appliedNames = @(Get-ReportPatchNames -Entries $report.appliedPatches)
            $entry.applied = @($names | Where-Object { $appliedNames -ccontains $_ }).Count
            $entry.failed = @(foreach ($failure in @($report.failedPatches)) {
                $name = if ($failure.patch) { [string]$failure.patch.name } else { '(unnamed)' }
                [ordered]@{ name = $name; reason = (([string]$failure.reason) -split "`n" | Select-Object -First 1).Trim() }
            })
            $entry.broken = $entry.cliExit -ne 0
            $entry.ok = -not $entry.broken -and $entry.failed.Count -eq 0 -and $entry.applied -eq $names.Count
        } else {
            $entry.broken = $true
            $entry.failed = @([ordered]@{ name = '(no report)'; reason = "the CLI exited $($entry.cliExit) and wrote no report" })
        }
    } catch {
        $entry.broken = $true
        $entry.failed = @([ordered]@{ name = '(run)'; reason = $_.Exception.Message })
    } finally {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
        Exit-HeavyJob $job
    }
    $note = if ($build.Kind -eq 'base split') { ', from its base split alone' } else { '' }
    $forcedNote = if ($entry.forced) { ', forced' } else { '' }
    Write-Host ("[all-builds] $($build.Label) ($($entry.versionName) $($entry.versionCode)$forcedNote$note): " +
        "$($entry.applied)/$($names.Count) applied, $($entry.failed.Count) failed, $($entry.warnings.Count) WARNING lines, $($entry.source)")
    foreach ($failure in $entry.failed) { Write-Host "[all-builds]   failed $($failure.name): $($failure.reason)" }
    foreach ($warning in @($entry.warnings | Select-Object -First 10)) { Write-Host "[all-builds]   $warning" }
    if ($entry.warnings.Count -gt 10) { Write-Host "[all-builds]   and $($entry.warnings.Count - 10) more in $log" }
    $summary.Add([pscustomobject]$entry)
}

$summaryPath = Join-Path $OutDir 'summary.json'
[IO.File]::WriteAllText($summaryPath, (@{ bundle = Split-Path -Leaf $Bundle; bundleSha256 = $bundleHash; patches = $names.Count
        builds = $summary.ToArray() } | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
$short = @($summary | Where-Object { -not $_.ok })
$bad = @($summary | Where-Object { $_.broken -or (-not $_.ok -and ($RequireAll -or $_.forced -eq $false)) })
Write-Host "[all-builds] $($summary.Count - $short.Count) of $($summary.Count) builds took every patch; summary in $summaryPath"
foreach ($build in $bad) { Write-Host "[all-builds] refused: $($build.build) $(if ($build.broken) { 'did not finish' } else { 'missed a patch' })" }
exit $(if ($bad.Count -gt 0) { 1 } else { 0 })
