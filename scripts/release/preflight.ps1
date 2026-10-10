<#
.SYNOPSIS
    The quick checks a release push would otherwise reach late, in a few minutes.

.DESCRIPTION
    Run before pushing a release commit, so a slip costs minutes instead of a 20 minute gate:

    1. The checkout: no uncommitted changes to tracked files, and on main.
    2. The fixtures: HUSHGRAM_FIXTURE_DIR holds every build the catalog declares, and the desktop
       CLI is found, or the gate patches nothing and the receipt can't be cut.
    3. The release facts, with the set an ordinary push uses (no network-heavy asset checks, no
       description test counts, no build outputs read).
    4. The CHANGELOG section the notes are built from: every bullet scoped, no dashes.
    5. The tests a code-only commit left stale, read from the sources in under a second: every
       method verifyAndroidBoundaries requires is still a test method, and every text the settings
       tests expect is still in the extension sources or the translation tables.
    6. One Gradle build through the machine's queue: the catalog regenerated and compared with the
       committed one, and the patch tests that read no Instagram APK (the README table, the
       categories, the notices and source checks), so the patch tests' long fixture scans aren't
       what finds a README row out of step.

    Each step says how long it took, and the run says if the whole took more than five minutes.
    -SkipGradle leaves the last step out. A failed step stops the run with exit 1.

.EXAMPLE
    scripts/release/preflight.ps1
    scripts/release/preflight.ps1 -Version 0.0.8

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>
[CmdletBinding()]
param(
    # The dated CHANGELOG section the notes will be built from. Unreleased when left out.
    [string]$Version,
    [string]$Root,
    [switch]$SkipGradle
)

$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
if (-not $Root) { $Root = Split-Path -Parent $scripts }
. (Join-Path $scripts 'common.ps1')
. (Join-Path $scripts 'patch-target.ps1')
. (Join-Path $scripts 'build-jobs.ps1')
if ($env:HUSHGRAM_ALLOW_RELEASE -eq '1') { $env:BUILD_QUEUE_PRIORITY = 'release' }
foreach ($name in @('HUSHGRAM_FIXTURE_DIR', 'HUSHGRAM_DESKTOP_JAR', 'HUSHGRAM_WORKDIR')) {
    if (-not (Test-Path "Env:\$name")) {
        $value = [Environment]::GetEnvironmentVariable($name, [EnvironmentVariableTarget]::User)
        if ($value) { Set-Item -LiteralPath "Env:\$name" -Value $value }
    }
}

# The patch tests that open no fixture: they read the README, the catalog, the notices and the
# sources, and take seconds once the module is compiled.
$quickPatchTests = @('ReadmePatchNamesTest', 'PatchCategoriesTest', 'ProvenanceTest', 'SourceNoticeTest',
    'OriginNoticeMirrorsTest', 'ExtensionHostsTest', 'GuavaPatchSourceGuardTest', 'ConstantReturnSourceTest',
    'ObfuscatedIdentityTest')
$started = Get-Date
$stepStarted = $null
$stepName = $null

# Steps are written out in order, not handed over as script blocks, so the wiring checks see
# every command a step runs.
function Start-Step([string]$Name) {
    $script:stepName = $Name
    $script:stepStarted = Get-Date
    Write-Host "[preflight] $Name"
}
function Complete-Step {
    Write-Host ("[preflight] {0} passed in {1:N0} s" -f $script:stepName, ((Get-Date) - $script:stepStarted).TotalSeconds)
}
function Stop-Preflight([string]$Reason) {
    Write-Host ("[preflight] {0} failed in {1:N0} s" -f $script:stepName, ((Get-Date) - $script:stepStarted).TotalSeconds)
    Write-Host ("[preflight] refused after {0:N0} s: {1}: {2}" -f ((Get-Date) - $script:started).TotalSeconds, $script:stepName, $Reason)
    exit 1
}

Start-Step 'the checkout'
$dirty = @(& git -C $Root status --porcelain --untracked-files=no)
if ($dirty.Count -gt 0) { Stop-Preflight "uncommitted changes to tracked files, the first $("$($dirty[0])".Trim())" }
$branch = "$(& git -C $Root rev-parse --abbrev-ref HEAD)".Trim()
if ($branch -ne 'main') { Stop-Preflight "the checkout is on $branch, and releases are cut on main" }
Complete-Step

Start-Step 'the fixtures and the desktop CLI'
$folder = $env:HUSHGRAM_FIXTURE_DIR
if (-not $folder -or -not (Test-Path -LiteralPath $folder -PathType Container)) {
    Stop-Preflight 'HUSHGRAM_FIXTURE_DIR names no folder, so the gate would patch nothing and the receipt has no fixture'
}
$target = Get-PatchTarget -PatchList (Get-Content -LiteralPath (Join-Path $Root 'patches-list.json') -Raw | ConvertFrom-Json)
foreach ($declared in @($target.PackageVersions)) {
    $fixture = Find-DeclaredFixture -Target $target -Version $declared -Folder $folder
    if (-not $fixture) { Stop-Preflight "no fixture of the declared build $(Format-DeclaredBuilds -Target $target) in $folder" }
    Write-Host "[preflight]   $declared`: $($fixture.Name)"
}
try { $cli = Resolve-DesktopCli -Root $Root -Required } catch { Stop-Preflight $_.Exception.Message }
Write-Host "[preflight]   desktop CLI $(Split-Path -Leaf $cli)"
Complete-Step

Start-Step 'the release facts'
& pwsh -NoProfile -File (Join-Path $scripts 'validate-release-facts.ps1') `
    @(ConvertTo-ScriptArguments (Get-ReleaseFactsArguments -Push Ordinary -Root $Root))
if ($LASTEXITCODE -ne 0) { Stop-Preflight "validate-release-facts.ps1 exited $LASTEXITCODE" }
Complete-Step

Start-Step 'the CHANGELOG section for the notes'
$notes = @('-3.13', '-I', (Join-Path $PSScriptRoot 'release_notes.py'), '--check', '--changelog', (Join-Path $Root 'CHANGELOG.md'))
if ($Version) { $notes += @('--version', $Version) }
& py @notes
if ($LASTEXITCODE -ne 0) { Stop-Preflight "release_notes.py exited $LASTEXITCODE" }
Complete-Step

Start-Step 'the test names and texts the sources still have'
& py -3.13 -I (Join-Path $PSScriptRoot 'stale_tests.py') --root $Root
if ($LASTEXITCODE -ne 0) { Stop-Preflight "stale_tests.py exited $LASTEXITCODE" }
Complete-Step

if (-not $SkipGradle) {
    Start-Step 'the catalog and the patch tests that read no APK'
    $catalog = Join-Path $Root 'patches-list.json'
    $before = Get-Content -LiteralPath $catalog -Raw
    $tasks = @('--console=plain', ':patches:generatePatchesList', ':patches:test')
    foreach ($class in $quickPatchTests) { $tasks += @('--tests', "app.morphe.$class") }
    Invoke-GradleBuild -ProjectDir $Root -Tasks $tasks
    if ($LASTEXITCODE -ne 0) { Stop-Preflight "Gradle exited $LASTEXITCODE" }
    if ((Get-Content -LiteralPath $catalog -Raw) -cne $before) {
        Stop-Preflight 'patches-list.json is stale: the regenerated catalog is in the checkout now, so review and commit it'
    }
    Complete-Step
}

$total = ((Get-Date) - $started).TotalSeconds
Write-Host ("[preflight] every check passed in {0:N0} s" -f $total)
if ($total -gt 300) { Write-Warning ("[preflight] that took {0:N1} minutes, more than the five it should" -f ($total / 60)) }
exit 0
