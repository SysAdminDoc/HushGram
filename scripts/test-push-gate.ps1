<#
.SYNOPSIS
    Runs pre-push.ps1 end to end on a throwaway repository, with a stand-in Gradle wrapper, build
    queue and patch check, and checks the order the gate builds in and where it stops.
.DESCRIPTION
    The repository holds this checkout's pre-push.ps1 and the scripts it loads, a one-patch
    catalog and a source file. Each case pushes a commit that changes that file, which the hook
    reads as a build change. The stand-ins write what a real build would leave in the gate's
    worktree (test results, the bundle, its SBOM) and log what they were asked, so the order of
    the builds, the queue slot and the release priority can be read back. Nothing is compiled.
    The last cases push an index commit over a gated release commit, with the release check and
    the suites it starts as stand-ins, to see which pushes skip the build and which don't.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
$Root = [IO.Path]::GetFullPath($Root)
$scratch = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('hushgram-push-gate-' + [guid]::NewGuid().ToString('N'))))
$passed = 0
$version = '450.0.0.50.77'
$hookScripts = @('pre-push.ps1', 'common.ps1', 'patch-target.ps1', 'build-jobs.ps1', 'gate-evidence.ps1')
$hookVariables = @('HUSHGRAM_SKIP_PRE_PUSH', 'HUSHGRAM_ALLOW_RELEASE', 'HUSHGRAM_FIXTURE_DIR', 'HUSHGRAM_DESKTOP_JAR',
    'HUSHGRAM_WORKDIR', 'HUSHGRAM_BUILD_WRAPPER', 'BUILD_QUEUE_SCRIPT', 'BUILD_QUEUE_PRIORITY', 'BUILD_QUEUE_TICKET',
    'HUSHGRAM_GATE_CACHE', 'HUSHGRAM_GATE_TEST_LOG', 'HUSHGRAM_GATE_TEST_FAIL', 'HUSHGRAM_GATE_TEST_STALE',
    'HUSHGRAM_GATE_TEST_FACTS_EXIT')
$saved = @{}
foreach ($name in $hookVariables) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, [EnvironmentVariableTarget]::Process) }

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:passed++
}

function Invoke-FixtureGit {
    # $args, not a parameter: git's own switches (-A, -c, -m) would bind to one.
    $gitArguments = $args
    $output = & git -C $script:repo @gitArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "git $($gitArguments -join ' ') failed: $output" }
    return $output
}

function Write-FixtureFile([string]$Relative, [string]$Text) {
    $path = Join-Path $script:repo $Relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    [IO.File]::WriteAllText($path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function New-FixtureCommit([string]$Message) {
    Invoke-FixtureGit add -A | Out-Null
    Invoke-FixtureGit -c user.name=SysAdminDoc -c user.email=matt_parker@outlook.com commit -q -m $Message | Out-Null
    return "$(Invoke-FixtureGit rev-parse HEAD)".Trim()
}

function Clear-Log { Remove-Item -LiteralPath $script:log -ErrorAction SilentlyContinue }
function Read-Log { if (Test-Path -LiteralPath $script:log) { @(Get-Content -LiteralPath $script:log) } else { @() } }
function Get-GradleRuns { @(Read-Log | Where-Object { $_ -like 'gradle *' }) }

function Invoke-Push {
    <# The hook as git runs it, for the push of -Tip over -Base, with the stand-ins switched in. #>
    param([string]$Tip, [string]$Base, [hashtable]$Environment = @{})
    foreach ($name in $hookVariables) { Remove-Item -LiteralPath "Env:\$name" -ErrorAction SilentlyContinue }
    $env:HUSHGRAM_BUILD_WRAPPER = $script:wrapper
    $env:BUILD_QUEUE_SCRIPT = $script:queue
    $env:HUSHGRAM_GATE_TEST_LOG = $script:log
    $env:HUSHGRAM_GATE_CACHE = $script:gateCache
    # Somewhere that isn't a folder, so a maintainer's own fixtures aren't read unless a case asks.
    $env:HUSHGRAM_FIXTURE_DIR = Join-Path $scratch 'no-fixtures'
    $env:HUSHGRAM_DESKTOP_JAR = Join-Path $scratch 'no-cli.jar'
    foreach ($name in $Environment.Keys) { Set-Item -LiteralPath "Env:\$name" -Value $Environment[$name] }
    Clear-Log
    $output = @("refs/heads/main $Tip refs/heads/main $Base" |
        & pwsh -NoProfile -File (Join-Path $script:repo 'scripts/pre-push.ps1') origin 'https://example.invalid/hushgram.git' 2>&1 |
        ForEach-Object { "$_" })
    $exit = $LASTEXITCODE
    foreach ($name in $hookVariables) { Remove-Item -LiteralPath "Env:\$name" -ErrorAction SilentlyContinue }
    return [pscustomobject]@{ Exit = $exit; Output = $output -join "`n" }
}

try {
    New-Item -ItemType Directory -Path $scratch | Out-Null
    $script:log = Join-Path $scratch 'calls.log'

    # The stand-in Gradle wrapper: logs the tasks, the queue priority and whether it ran inside a
    # slot, and writes what each task would leave. HUSHGRAM_GATE_TEST_FAIL names a task that fails;
    # HUSHGRAM_GATE_TEST_STALE has the catalog come out different from the committed one.
    $script:wrapper = Join-Path $scratch 'wrapper.ps1'
    Set-Content -LiteralPath $script:wrapper -Encoding UTF8 -Value @'
param([string]$ProjectDir, [string[]]$Tasks)
Add-Content -LiteralPath $env:HUSHGRAM_GATE_TEST_LOG -Value ("gradle $($Tasks -join ' ') priority=$env:BUILD_QUEUE_PRIORITY " +
    "slot=$([bool]$env:BUILD_QUEUE_TICKET)")
function Write-Results([string]$Folder, [string]$Suite, [int]$Cases) {
    New-Item -ItemType Directory -Force -Path $Folder | Out-Null
    $body = (1..$Cases | ForEach-Object { "<testcase name=`"case$_`" classname=`"$Suite`"/>" }) -join ''
    Set-Content -LiteralPath (Join-Path $Folder "TEST-$Suite.xml") -Encoding UTF8 -Value (
        "<?xml version=`"1.0`"?><testsuite name=`"$Suite`" tests=`"$Cases`" failures=`"0`" errors=`"0`" skipped=`"0`">$body</testsuite>")
}
if ($Tasks -contains ':patches:generatePatchesList' -and $env:HUSHGRAM_GATE_TEST_STALE) {
    $catalogPath = Join-Path $ProjectDir 'patches-list.json'
    if ($env:HUSHGRAM_GATE_TEST_STALE -eq 'case') {
        [IO.File]::WriteAllText($catalogPath, [IO.File]::ReadAllText($catalogPath).Replace('Stand-in patch', 'stand-in patch'))
    } else {
        Add-Content -LiteralPath $catalogPath -Value ' '
    }
}
if ($Tasks -contains ':extensions:instagram:testDebugUnitTest') {
    Write-Results (Join-Path $ProjectDir 'extensions/instagram/build/test-results/testDebugUnitTest') 'app.hushgram.RuntimeTest' 3
}
if ($Tasks -contains ':patches:test') {
    Write-Results (Join-Path $ProjectDir 'patches/build/test-results/test') 'app.morphe.PatchTest' 2
}
if ($Tasks -contains ':patches:buildAndroid') {
    $release = Join-Path $ProjectDir 'patches/build/release'
    New-Item -ItemType Directory -Force -Path $release | Out-Null
    Set-Content -LiteralPath (Join-Path $release 'patches-0.0.1.mpp') -Value 'bundle'
    Set-Content -LiteralPath (Join-Path $release 'patches-0.0.1.cdx.json') -Value '{}'
    Set-Content -LiteralPath (Join-Path $release 'bundle.sha256') -Value 'sum'
}
if ($env:HUSHGRAM_GATE_TEST_FAIL -and $Tasks -contains $env:HUSHGRAM_GATE_TEST_FAIL) { exit 1 }
exit 0
'@
    # The stand-in queue: one ticket at a time, logged.
    $script:queue = Join-Path $scratch 'queue.ps1'
    Set-Content -LiteralPath $script:queue -Encoding UTF8 -Value @'
param([switch]$Status, [string]$Label, [string]$Priority, [string]$Run)
function Enter-BuildQueue {
    param([string]$Label, [string]$Priority)
    Add-Content -LiteralPath $env:HUSHGRAM_GATE_TEST_LOG -Value "queue enter $Label $Priority"
    $env:BUILD_QUEUE_TICKET = 'stand-in'
    [pscustomobject]@{ slot = 0; path = 'stand-in' }
}
function Exit-BuildQueue {
    param($Ticket)
    Add-Content -LiteralPath $env:HUSHGRAM_GATE_TEST_LOG -Value 'queue exit'
    Remove-Item Env:\BUILD_QUEUE_TICKET -ErrorAction SilentlyContinue
}
function Get-BuildQueueMask { param([int]$Slot) [System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity }
'@

    $script:gateCache = Join-Path $scratch 'gate-cache'
    $script:repo = Join-Path $scratch 'repo'
    New-Item -ItemType Directory -Path $script:repo | Out-Null
    Invoke-FixtureGit init -q -b main | Out-Null
    Invoke-FixtureGit config core.autocrlf false | Out-Null
    foreach ($name in $hookScripts) {
        Write-FixtureFile "scripts/$name" ([IO.File]::ReadAllText((Join-Path $Root "scripts/$name")))
    }
    # The stand-in patch check: writes the reports the real one leaves beside its result, and keeps
    # a stamped run through gate-evidence.ps1 the way the real one does with -KeepIn.
    Write-FixtureFile 'scripts/verify-all-patches.ps1' @'
param([string]$Apk, [string]$DesktopJar, [string]$WorkDir, [string]$Bundle, [string]$PatchList, [string]$KeepIn)
Add-Content -LiteralPath $env:HUSHGRAM_GATE_TEST_LOG -Value "verify $(Split-Path -Leaf $Apk) keep=$([bool]$KeepIn) slot=$([bool]$env:BUILD_QUEUE_TICKET)"
$result = Join-Path $WorkDir 'verify-all-result-standin.json'
Set-Content -LiteralPath $result -Value '{"appliedPatches":[]}'
Set-Content -LiteralPath (Join-Path $WorkDir 'verify-all-registers-standin.txt') -Value 'registers'
if ($env:HUSHGRAM_GATE_TEST_FAIL -eq 'verify') { exit 1 }
if ($KeepIn) {
    . (Join-Path $PSScriptRoot 'gate-evidence.ps1')
    $patched = Join-Path $WorkDir 'patched.apk'
    $merged = Join-Path $WorkDir 'merged.apk'
    Set-Content -LiteralPath $patched -Value 'patched'
    Set-Content -LiteralPath $merged -Value 'merged'
    Write-GateKeptRun -KeepIn $KeepIn -PatchedApk $patched -Result $result -MergedApk $merged -Apk $Apk -Bundle $Bundle `
        -PatchList $PatchList -DesktopJar $DesktopJar -VersionName '450.0.0.50.77' -VersionCode '385611438' -Forced $false
}
exit 0
'@
    Write-FixtureFile 'gradle.properties' "version = 0.0.1`n"
    Write-FixtureFile 'patches-list.json' (@{
        version = 'v0.0.1'
        patches = @(@{ name = 'Stand-in patch'; compatiblePackages = @{ 'com.instagram.android' = @($version) }
            compatibility = @(@{ packageName = 'com.instagram.android'
                targets = @(@{ version = $version; versionCodes = @{ ARM64_V8A = 385611438 } }) }) })
    } | ConvertTo-Json -Depth 10)
    Write-FixtureFile 'patches/src/main/kotlin/StandIn.kt' "// first`n"
    $base = New-FixtureCommit 'base'
    Write-FixtureFile 'patches/src/main/kotlin/StandIn.kt' "// second`n"
    $tip = New-FixtureCommit 'change a patch'
    $short = $tip.Substring(0, 12)

    # Cheap checks first, in one build, then the patch tests and the bundle, all in one slot.
    $run = Invoke-Push -Tip $tip -Base $base
    $builds = Get-GradleRuns
    Assert-True ($run.Exit -eq 0) "The gate refused a clean change: $($run.Output)"
    Assert-True ($builds.Count -eq 2) "The gate ran $($builds.Count) builds, not two: $($builds -join '; ')"
    Assert-True ($builds[0] -like '*:patches:generatePatchesList*' -and $builds[0] -like '*:extensions:instagram:lint*' -and
        $builds[0] -like '*:extensions:shared:library:lint*' -and $builds[0] -like '*:extensions:instagram:testDebugUnitTest*' -and
        $builds[0] -like '*:extensions:instagram:verifyAndroidBoundaries*' -and $builds[0] -notlike '*:patches:test*' -and
        $builds[0] -notlike '*:patches:buildAndroid*') "The first build isn't the quick one: $($builds[0])"
    Assert-True ($builds[1] -like '*:patches:test*' -and $builds[1] -like '*:patches:buildAndroid*') `
        "The second build isn't the patch tests and the bundle: $($builds[1])"
    $queueLines = @(Read-Log | Where-Object { $_ -like 'queue *' })
    Assert-True (($queueLines -join ',') -eq "queue enter hushgram gate $short normal,queue exit" -and
        @($builds | Where-Object { $_ -like '*slot=True' }).Count -eq 2) `
        "The gate didn't build inside one everyday slot: $((Read-Log) -join '; ')"
    Assert-True ($run.Output -like '*HUSHGRAM_FIXTURE_DIR is not set*') "The gate didn't say it patched nothing: $($run.Output)"
    # It kept its results by commit, outside the worktree it deleted, and says it patched nothing.
    $manifestPath = Join-Path $script:gateCache "$tip/manifest.json"
    function Read-Manifest { Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json }
    $manifest = Read-Manifest
    Assert-True ($manifest.commit -eq $tip -and $manifest.passed -eq $true -and $manifest.fixturesPatched -eq $false -and
        $manifest.tests.runtime.tests -eq 3 -and $manifest.tests.patches.tests -eq 2 -and $manifest.bundle.file -eq 'patches-0.0.1.mpp' -and
        $manifest.tree -eq "$(Invoke-FixtureGit rev-parse "$tip^{tree}")".Trim()) `
        "The passing gate's manifest isn't what it ran: $($manifest | ConvertTo-Json -Depth 6 -Compress)"
    Assert-True ((Test-Path -LiteralPath (Join-Path $script:gateCache "$tip/release/patches-0.0.1.cdx.json")) -and
        (Test-Path -LiteralPath (Join-Path $script:gateCache "$tip/test-results/testDebugUnitTest/TEST-app.hushgram.RuntimeTest.xml"))) `
        "The gate didn't keep its SBOM and test results."

    # A lint that fails stops the push before the quarter hour of patch tests.
    $run = Invoke-Push -Tip $tip -Base $base -Environment @{ HUSHGRAM_GATE_TEST_FAIL = ':extensions:instagram:lint' }
    Assert-True ($run.Exit -ne 0 -and (Get-GradleRuns).Count -eq 1 -and $run.Output -like '*the runtime tests, a lint or the catalog failed*') `
        "A failed quick build didn't stop the gate before the patch tests: $((Get-GradleRuns) -join '; ') $($run.Output)"
    Assert-True ((@(Read-Log | Where-Object { $_ -like 'queue *' }) -join ',') -eq "queue enter hushgram gate $short normal,queue exit") `
        'A refused gate did not give its slot back.'
    # Its results are kept too, marked failed, so they outlive the worktree and nothing reuses them.
    $manifest = Read-Manifest
    Assert-True ($manifest.passed -eq $false -and $manifest.stage -eq 'quick' -and $manifest.reason -like '*a lint*' -and
        $manifest.tests.runtime.tests -eq 3 -and $null -eq $manifest.bundle) `
        "The refused gate's manifest doesn't say where it stopped: $($manifest | ConvertTo-Json -Depth 6 -Compress)"

    # So does a catalog that doesn't match the patches.
    $run = Invoke-Push -Tip $tip -Base $base -Environment @{ HUSHGRAM_GATE_TEST_STALE = '1' }
    Assert-True ($run.Exit -ne 0 -and (Get-GradleRuns).Count -eq 1 -and $run.Output -like '*patches-list.json is stale*') `
        "A stale catalog didn't stop the gate before the patch tests: $($run.Output)"

    # A catalog that differs only in letter case is just as stale.
    $run = Invoke-Push -Tip $tip -Base $base -Environment @{ HUSHGRAM_GATE_TEST_STALE = 'case' }
    Assert-True ($run.Exit -ne 0 -and (Get-GradleRuns).Count -eq 1 -and $run.Output -like '*patches-list.json is stale*') `
        "A catalog regenerated with other letter case didn't stop the gate: $($run.Output)"

    # A failed patch test or bundle is still a refusal.
    $run = Invoke-Push -Tip $tip -Base $base -Environment @{ HUSHGRAM_GATE_TEST_FAIL = ':patches:test' }
    Assert-True ($run.Exit -ne 0 -and (Get-GradleRuns).Count -eq 2 -and $run.Output -like '*the patch tests failed or the bundle did not build*') `
        "A failed patch test didn't stop the gate: $($run.Output)"

    # A declared build with no fixture stops the push before anything is built.
    $fixtureDir = Join-Path $scratch 'fixtures'
    New-Item -ItemType Directory -Path $fixtureDir | Out-Null
    $desktop = Join-Path $scratch 'morphe-desktop-stand-in.jar'
    Set-Content -LiteralPath $desktop -Value 'jar'
    $run = Invoke-Push -Tip $tip -Base $base -Environment @{ HUSHGRAM_FIXTURE_DIR = $fixtureDir; HUSHGRAM_DESKTOP_JAR = $desktop }
    Assert-True ($run.Exit -ne 0 -and (Get-GradleRuns).Count -eq 0 -and $run.Output -like "*no fixture for the declared build $version*") `
        "A missing fixture didn't stop the gate before the build: $((Get-GradleRuns) -join '; ') $($run.Output)"

    # Another build of the declared version (a full bundle that sorts first) is not the declared one.
    $otherBuild = Join-Path $fixtureDir "instagram-$version-385611395.apkm"
    Set-Content -LiteralPath $otherBuild -Value 'bundle of another build'
    $run = Invoke-Push -Tip $tip -Base $base -Environment @{ HUSHGRAM_FIXTURE_DIR = $fixtureDir; HUSHGRAM_DESKTOP_JAR = $desktop }
    Assert-True ($run.Exit -ne 0 -and (Get-GradleRuns).Count -eq 0 -and $run.Output -like "*no fixture for the declared build $version*") `
        "A fixture of another build of the version was taken for the declared one: $((Get-GradleRuns) -join '; ') $($run.Output)"

    # A push made for a release is first in line, and its builds know it.
    $run = Invoke-Push -Tip $tip -Base $base -Environment @{ HUSHGRAM_ALLOW_RELEASE = '1' }
    Assert-True ($run.Exit -eq 0 -and @(Get-GradleRuns | Where-Object { $_ -like '*priority=release slot=True' }).Count -eq 2 -and
        (Read-Log) -contains "queue enter hushgram gate $short release") `
        "A release push didn't take its slot at release priority: $((Read-Log) -join '; ')"

    # A patch run that fails is kept with its reports, marked failed, and nothing patched is kept.
    $fixtureApk = Join-Path $fixtureDir "instagram-$version-385611438.apks"
    Set-Content -LiteralPath $fixtureApk -Value 'bundle of splits'
    . (Join-Path $PSScriptRoot 'patch-target.ps1')
    $picked = Find-DeclaredFixture -Target (Get-PatchTarget -PatchList ((Get-Content -LiteralPath (Join-Path $script:repo 'patches-list.json') -Raw) | ConvertFrom-Json)) `
        -Version $version -Folder $fixtureDir
    Assert-True ($null -ne $picked -and $picked.Name -eq (Split-Path -Leaf $fixtureApk)) "The declared fixture wasn't picked over a file that sorts first: $($picked.Name)"
    $withFixtures = @{ HUSHGRAM_FIXTURE_DIR = $fixtureDir; HUSHGRAM_DESKTOP_JAR = $desktop }
    $run = Invoke-Push -Tip $tip -Base $base -Environment ($withFixtures + @{ HUSHGRAM_GATE_TEST_FAIL = 'verify' })
    $manifest = Read-Manifest
    Assert-True ($run.Exit -ne 0 -and $manifest.passed -eq $false -and $manifest.stage -eq 'fixtures' -and
        $manifest.fixtures[0].reports -eq 2 -and $manifest.fixtures[0].kept -eq $false -and $manifest.fixturesPatched -eq $false) `
        "A failed patch run wasn't kept as a failure with its reports: $($manifest | ConvertTo-Json -Depth 6 -Compress) $($run.Output)"

    # A passing one is kept with the stamped run, made inside the gate's slot.
    $run = Invoke-Push -Tip $tip -Base $base -Environment $withFixtures
    $manifest = Read-Manifest
    Assert-True ($run.Exit -eq 0 -and (Read-Log) -contains "verify $(Split-Path -Leaf $fixtureApk) keep=True slot=True") `
        "The gate didn't patch the declared build with -KeepIn inside its slot: $((Read-Log) -join '; ') $($run.Output)"
    Assert-True ($manifest.passed -eq $true -and $manifest.stage -eq 'done' -and $manifest.fixturesPatched -eq $true -and
        $manifest.fixtures[0].version -eq $version -and $manifest.fixtures[0].kept -eq $true -and $manifest.fixtures[0].reports -eq 2) `
        "The passing gate's manifest doesn't record its patch run: $($manifest | ConvertTo-Json -Depth 6 -Compress)"

    # What the release scripts read back, and every way it stops standing for HEAD.
    $env:HUSHGRAM_GATE_CACHE = $script:gateCache
    . (Join-Path $PSScriptRoot 'gate-evidence.ps1')
    $evidence = Find-GateEvidence -Root $script:repo
    Assert-True ($null -ne $evidence -and $evidence.Commit -eq $tip -and -not $evidence.IndexOnly -and
        (Test-Path -LiteralPath $evidence.Bundle) -and (Test-Path -LiteralPath $evidence.Sbom)) 'The passing gate run was not found for HEAD.'
    $bundleHash = Get-EvidenceHash -Path $evidence.Bundle
    $catalogFile = Join-Path $script:repo 'patches-list.json'
    $why = ''
    $kept = Get-GateKeptRun -Evidence $evidence -VersionName $version -Apk $fixtureApk -BundleSha256 $bundleHash `
        -PatchList $catalogFile -DesktopJar $desktop -Forced $false -Why ([ref]$why)
    Assert-True ($null -ne $kept -and (Test-Path -LiteralPath $kept.PatchedApk) -and (Test-Path -LiteralPath $kept.MergedApk) -and
        (Test-Path -LiteralPath $kept.Result)) "The kept patch run wasn't handed back: $why"
    # Kept patch output that no longer hashes to its stamp is not handed back.
    foreach ($case in @(@{ Name = 'patched.apk'; Path = $kept.PatchedApk }, @{ Name = 'result.json'; Path = $kept.Result },
            @{ Name = 'stock-merged.apk'; Path = $kept.MergedApk })) {
        $keptBytes = [IO.File]::ReadAllBytes($case.Path)
        [IO.File]::WriteAllBytes($case.Path, $keptBytes + [byte]0)
        $why = ''
        $refused = Get-GateKeptRun -Evidence $evidence -VersionName $version -Apk $fixtureApk -BundleSha256 $bundleHash `
            -PatchList $catalogFile -DesktopJar $desktop -Forced $false -Why ([ref]$why)
        [IO.File]::WriteAllBytes($case.Path, $keptBytes)
        Assert-True ($null -eq $refused -and $why -like "*$($case.Name)*isn't the one it stamped*") "A changed $($case.Name) was handed back: $why"
    }
    $otherCli = Join-Path $scratch 'morphe-desktop-other.jar'
    Set-Content -LiteralPath $otherCli -Value 'another jar'
    foreach ($case in @(
            @{ Why = 'CLI'; Cli = $otherCli; Forced = $false; Bundle = $bundleHash },
            @{ Why = 'forcing'; Cli = $desktop; Forced = $true; Bundle = $bundleHash },
            @{ Why = 'bundle'; Cli = $desktop; Forced = $false; Bundle = ('0' * 64) })) {
        $why = ''
        $refused = Get-GateKeptRun -Evidence $evidence -VersionName $version -Apk $fixtureApk -BundleSha256 $case.Bundle `
            -PatchList $catalogFile -DesktopJar $case.Cli -Forced $case.Forced -Why ([ref]$why)
        Assert-True ($null -eq $refused -and $why -like "*another*$($case.Why)*") "A kept run made with another $($case.Why) was handed back: $why"
    }
    $why = ''
    Assert-True ($null -eq (Get-GateKeptRun -Evidence $evidence -VersionName '1.2.3' -Apk $fixtureApk -BundleSha256 $bundleHash `
        -PatchList $catalogFile -DesktopJar $desktop -Forced $false -Why ([ref]$why)) -and $why -like '*no run of 1.2.3*') `
        "A build the gate never patched was handed a kept run: $why"

    function Assert-NotFound([string]$Case, [string]$Reason) {
        $said = @(Find-GateEvidence -Root $script:repo 6>&1)
        $found = @($said | Where-Object { $null -ne $_ -and $_ -isnot [System.Management.Automation.InformationRecord] })
        $text = @($said | Where-Object { $_ -is [System.Management.Automation.InformationRecord] }) -join ' '
        Assert-True ($found.Count -eq 0 -and $text -like "*$Reason*") "The gate run stood for HEAD with $Case`: $text"
    }
    $keptBundle = $evidence.Bundle
    $original = [IO.File]::ReadAllBytes($keptBundle)
    [IO.File]::WriteAllBytes($keptBundle, $original + [byte]0)
    Assert-NotFound 'its bundle changed' "isn't the patches-0.0.1.mpp the manifest hashes"
    [IO.File]::WriteAllBytes($keptBundle, $original)
    $keptResults = Join-Path $script:gateCache "$tip/test-results/test/TEST-app.morphe.PatchTest.xml"
    $resultsText = [IO.File]::ReadAllText($keptResults)
    [IO.File]::WriteAllText($keptResults, $resultsText.Replace('<testcase name="case2" classname="app.morphe.PatchTest"/>', ''))
    Assert-NotFound 'a test result removed' 'read 1 tests'
    [IO.File]::WriteAllText($keptResults, $resultsText)
    $manifestText = [IO.File]::ReadAllText($manifestPath)
    [IO.File]::WriteAllText($manifestPath, $manifestText.Replace('"passed": true', '"passed": false'))
    Assert-NotFound 'a failed run' "didn't pass"
    [IO.File]::WriteAllText($manifestPath, $manifestText)
    Write-FixtureFile 'patches/src/main/kotlin/StandIn.kt' "// edited, not committed`n"
    Assert-NotFound 'an uncommitted change' 'uncommitted changes'
    Invoke-FixtureGit checkout -q -- patches/src/main/kotlin/StandIn.kt | Out-Null
    Write-FixtureFile 'patches/src/main/kotlin/StandIn.kt' "// third`n"
    $later = New-FixtureCommit 'another change'
    Assert-NotFound 'another commit checked out' 'no gate run is kept'
    Invoke-FixtureGit reset -q --hard $tip | Out-Null
    Assert-True ($null -ne (Find-GateEvidence -Root $script:repo)) 'The restored gate run was not found again.'

    # The index push. A release source commit with the README sentence, the bug form placeholder
    # and an index, gated with the declared build patched, and then an index commit over it. The
    # release check and the suites a README change starts are stand-ins that log their calls.
    Write-FixtureFile 'scripts/validate-release-facts.ps1' @'
Add-Content -LiteralPath $env:HUSHGRAM_GATE_TEST_LOG -Value "facts $($args -join ' ')"
exit [int]$env:HUSHGRAM_GATE_TEST_FACTS_EXIT
'@
    foreach ($suite in @('test-release-tooling.ps1', 'test-carried-licenses.ps1')) {
        Write-FixtureFile "scripts/$suite" @'
param([string]$Root)
Add-Content -LiteralPath $env:HUSHGRAM_GATE_TEST_LOG -Value "suite $(Split-Path -Leaf $PSCommandPath)"
exit 0
'@
    }
    function Write-IndexFiles([string]$Release, [int]$Patches, [string]$Extra = '') {
        Write-FixtureFile 'README.md' ("# HushGram`n`nThe latest release is [v$Release](https://github.com/SysAdminDoc/HushGram/releases/tag/v$Release), " +
            "with $Patches patches. Add it to Morphe Manager.`n`nWhat the patches do.$Extra`n")
        Write-FixtureFile '.github/ISSUE_TEMPLATE/bug_report.yml' "body:`n  - type: input`n    attributes:`n      placeholder: HushGram $Release`n"
        Write-FixtureFile 'patches-bundle.json' "{`"version`": `"$Release`"}`n"
    }
    Write-IndexFiles '0.0.1' 1
    $source = New-FixtureCommit 'release source'
    $release = @{ HUSHGRAM_ALLOW_RELEASE = '1' }
    $run = Invoke-Push -Tip $source -Base $tip -Environment ($withFixtures + $release)
    Assert-True ($run.Exit -eq 0 -and (Get-GradleRuns).Count -eq 2 -and
        (Test-Path -LiteralPath (Join-Path $script:gateCache "$source/fixtures/$version/kept/stamp.json"))) `
        "The release source push didn't gate and keep its patch run: $($run.Output)"
    Write-IndexFiles '0.0.2' 2
    $index = New-FixtureCommit 'index'
    $sourceManifest = Join-Path $script:gateCache "$source/manifest.json"
    $sourceManifestText = [IO.File]::ReadAllText($sourceManifest)
    function Clear-IndexRun { Remove-Item -LiteralPath (Join-Path $script:gateCache $index) -Recurse -Force -ErrorAction SilentlyContinue }

    # Over the gated source, the index push isn't built or tested again, and everything else still
    # runs: the release switch, the suites and the release check against the published release.
    $run = Invoke-Push -Tip $index -Base $source -Environment ($withFixtures + $release)
    $facts = @(Read-Log | Where-Object { $_ -like 'facts *' })
    Assert-True ($run.Exit -eq 0 -and (Get-GradleRuns).Count -eq 0 -and @(Read-Log | Where-Object { $_ -like 'queue *' -or $_ -like 'verify *' }).Count -eq 0 -and
        $run.Output -like "*everything since $($source.Substring(0, 12)) is the index*isn't built and tested again*") `
        "An index push over a gated commit was built again: $((Read-Log) -join '; ') $($run.Output)"
    Assert-True ($facts.Count -eq 1 -and $facts[0] -like '*-VerifyPublishedAsset*' -and $facts[0] -like '*-FromGate*' -and
        (Read-Log) -contains 'suite test-release-tooling.ps1' -and (Read-Log) -contains 'suite test-carried-licenses.ps1') `
        "The skipped index push didn't run the release check against the published release, or the suites: $((Read-Log) -join '; ')"
    # With no fixture folder, a gate here wouldn't patch either, so a run that patched nothing does.
    [IO.File]::WriteAllText($sourceManifest, ($sourceManifestText -replace '"fixturesPatched":\s*true', '"fixturesPatched": false'))
    $run = Invoke-Push -Tip $index -Base $source -Environment $release
    Assert-True ($run.Exit -eq 0 -and (Get-GradleRuns).Count -eq 0) "An index push with nothing to patch was built again: $($run.Output)"
    [IO.File]::WriteAllText($sourceManifest, $sourceManifestText)

    # The release protections hold: no release switch, or a release check that refuses, stops it.
    $run = Invoke-Push -Tip $index -Base $source -Environment $withFixtures
    Assert-True ($run.Exit -ne 0 -and $run.Output -like '*this push is a release*' -and (Get-GradleRuns).Count -eq 0) `
        "An index push went out without the release switch: $($run.Output)"
    $run = Invoke-Push -Tip $index -Base $source -Environment ($withFixtures + $release + @{ HUSHGRAM_GATE_TEST_FACTS_EXIT = '1' })
    Assert-True ($run.Exit -ne 0 -and $run.Output -like '*the published release does not agree with the index*' -and (Get-GradleRuns).Count -eq 0) `
        "An index push the release check refused went out: $($run.Output)"

    # A build added to the fixture folder after the gate is something the patch tests would read.
    $addedBuild = Join-Path $fixtureDir "instagram-$version-385611999"
    New-Item -ItemType Directory -Path $addedBuild | Out-Null
    Set-Content -LiteralPath (Join-Path $addedBuild 'base.apk') -Value 'another build'
    try {
        $run = Invoke-Push -Tip $index -Base $source -Environment ($withFixtures + $release)
        Assert-True ($run.Exit -eq 0 -and (Get-GradleRuns).Count -eq 2 -and $run.Output -like '*the fixture folder changed since that gate ran*') `
            "An index push after a build joined the fixture folder wasn't gated in full: $((Get-GradleRuns) -join '; ') $($run.Output)"
    } finally {
        Remove-Item -LiteralPath $addedBuild -Recurse -Force
    }
    Clear-IndexRun

    # Each run that doesn't cover the push is gated in full: one that didn't pass, one that patched
    # no declared build when a gate here would, and one made with another desktop CLI.
    foreach ($case in @(
            @{ Name = 'a failed gate run'; Said = "*isn't used: that gate didn't pass*"; Environment = $withFixtures
                Spoil = { [IO.File]::WriteAllText($sourceManifest, ($sourceManifestText -replace '"passed":\s*true', '"passed": false')) } },
            @{ Name = 'a gate run that patched nothing'; Said = "*doesn't cover this push: that gate patched no declared build*"; Environment = $withFixtures
                Spoil = { [IO.File]::WriteAllText($sourceManifest, ($sourceManifestText -replace '"fixturesPatched":\s*true', '"fixturesPatched": false')) } },
            @{ Name = 'gate logic that has changed'; Said = "*isn't used: it was made by other gate logic*"; Environment = $withFixtures
                Spoil = { [IO.File]::WriteAllText($sourceManifest, ($sourceManifestText -replace '"logicSha256":\s*"[0-9A-F]+"', '"logicSha256": "0000"')) } },
            @{ Name = 'a gate run with no fixture fingerprint'; Said = "*doesn't cover this push: that gate recorded no fingerprint*"; Environment = $withFixtures
                Spoil = { [IO.File]::WriteAllText($sourceManifest, ($sourceManifestText -replace '"fixtureFingerprint":\s*"[0-9A-F]+"', '"fixtureFingerprint": null')) } },
            @{ Name = 'another desktop CLI'; Said = "*doesn't cover this push: that gate patched $version with another desktop CLI*"
                Environment = @{ HUSHGRAM_FIXTURE_DIR = $fixtureDir; HUSHGRAM_DESKTOP_JAR = $otherCli }; Spoil = {} })) {
        & $case.Spoil
        try {
            $run = Invoke-Push -Tip $index -Base $source -Environment ($case.Environment + $release)
            Assert-True ($run.Exit -eq 0 -and (Get-GradleRuns).Count -eq 2 -and $run.Output -like $case.Said) `
                "An index push over $($case.Name) wasn't gated in full: $((Get-GradleRuns) -join '; ') $($run.Output)"
        } finally {
            [IO.File]::WriteAllText($sourceManifest, $sourceManifestText)
        }
        if ($case.Name -ne 'another desktop CLI') { Clear-IndexRun }
    }
    # That last gate passed this very commit with the other CLI, so pushing it again with that CLI
    # needs no build.
    $run = Invoke-Push -Tip $index -Base $source -Environment @{ HUSHGRAM_FIXTURE_DIR = $fixtureDir; HUSHGRAM_DESKTOP_JAR = $otherCli; HUSHGRAM_ALLOW_RELEASE = '1' }
    Assert-True ($run.Exit -eq 0 -and (Get-GradleRuns).Count -eq 0 -and $run.Output -like "*the gate passed this commit*") `
        "A push of a commit the gate already passed was built again: $($run.Output)"
    Clear-IndexRun

    # And a push that changes more than the index lines, or another file with them, is gated.
    foreach ($case in @(
            @{ Name = 'another README line'; Write = { Write-IndexFiles '0.0.2' 2 ' And one more thing.' }; Said = "*a line of README.md other than the index's changed*" },
            @{ Name = 'another file'; Write = { Write-IndexFiles '0.0.2' 2; Write-FixtureFile 'docs/notes.md' "notes`n" }; Said = '*' })) {
        Invoke-FixtureGit reset -q --hard $source | Out-Null
        & $case.Write
        $wider = New-FixtureCommit "index with $($case.Name)"
        $run = Invoke-Push -Tip $wider -Base $source -Environment ($withFixtures + $release)
        Assert-True ($run.Exit -eq 0 -and (Get-GradleRuns).Count -eq 2 -and $run.Output -like $case.Said -and
            $run.Output -notlike "*isn't built and tested again*") "An index push with $($case.Name) wasn't gated in full: $($run.Output)"
    }
    Invoke-FixtureGit reset -q --hard $tip | Out-Null

    # A push that moves a PowerShell file needs every tracked one to parse: a helper no suite or
    # build reads, with a "$name:" PowerShell takes for a scoped variable, stops it before anything
    # is built, and the same push with the name delimited goes through.
    Write-FixtureFile 'scripts/stand-in-helper.ps1' ('param([string]$Label)' + "`n" + 'Write-Host "$Label: done"' + "`n")
    $unparsed = New-FixtureCommit 'add a helper that does not parse'
    $run = Invoke-Push -Tip $unparsed -Base $tip
    Assert-True ($run.Exit -ne 0 -and $run.Output -like '*scripts/stand-in-helper.ps1:2 *' -and
        $run.Output -like '*refused: 1 parse error(s) in the tracked PowerShell files*' -and (Get-GradleRuns).Count -eq 0) `
        "A push with a script that doesn't parse went through: $($run.Output)"
    Write-FixtureFile 'scripts/stand-in-helper.ps1' ('param([string]$Label)' + "`n" + 'Write-Host "${Label}: done"' + "`n")
    $mended = New-FixtureCommit 'delimit the helper''s label'
    $run = Invoke-Push -Tip $mended -Base $tip
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*tracked PowerShell files parse*') `
        "A push whose scripts all parse was refused: $($run.Output)"
    Invoke-FixtureGit reset -q --hard $tip | Out-Null

    # The parse check reads the pushed commit, not the working tree: a file committed broken stops
    # the push even when the checkout has since been mended without a commit.
    Write-FixtureFile 'scripts/stand-in-helper.ps1' ('param([string]$Label)' + "`n" + 'Write-Host "$Label: done"' + "`n")
    $brokenTip = New-FixtureCommit 'commit a helper that does not parse'
    Write-FixtureFile 'scripts/stand-in-helper.ps1' ('param([string]$Label)' + "`n" + 'Write-Host "${Label}: done"' + "`n")
    $run = Invoke-Push -Tip $brokenTip -Base $tip
    Assert-True ($run.Exit -ne 0 -and $run.Output -like '*scripts/stand-in-helper.ps1:2 *' -and (Get-GradleRuns).Count -eq 0) `
        "A committed script that doesn't parse went through because the working tree's copy did: $($run.Output)"
    Invoke-FixtureGit checkout -q -- scripts/stand-in-helper.ps1 | Out-Null
    Invoke-FixtureGit reset -q --hard $tip | Out-Null

    # Only the newest runs are kept, and a folder still being written is left alone for a while.
    $pruneCache = Join-Path $scratch 'prune-cache'
    $env:HUSHGRAM_GATE_CACHE = $pruneCache
    $ages = @(5, 4, 3, 2, 1)
    $names = @($ages | ForEach-Object { '{0:x40}' -f $_ })
    for ($i = 0; $i -lt $ages.Count; $i++) {
        $dir = Join-Path $pruneCache $names[$i]
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        Set-Content -LiteralPath (Join-Path $dir 'manifest.json') -Value '{}'
        (Get-Item -LiteralPath (Join-Path $dir 'manifest.json')).LastWriteTimeUtc = [DateTime]::UtcNow.AddHours(-$ages[$i])
    }
    $running = Join-Path $pruneCache ('b' * 40)
    $abandoned = Join-Path $pruneCache ('c' * 40)
    New-Item -ItemType Directory -Force -Path $running, $abandoned | Out-Null
    (Get-Item -LiteralPath $abandoned).LastWriteTimeUtc = [DateTime]::UtcNow.AddDays(-3)
    Remove-StaleGateEvidence
    $left = @(Get-ChildItem -LiteralPath $pruneCache -Directory | ForEach-Object Name | Sort-Object)
    $expected = @(@($names[2], $names[3], $names[4], ('b' * 40)) | Sort-Object)
    Assert-True (($left -join ',') -eq ($expected -join ',')) "Pruning left $($left -join ', '), not the newest three and the running one."

    # Failed runs go before passed ones, so a release's passed run survives a drain of failures.
    $failFirst = Join-Path $scratch 'prune-failed-cache'
    $env:HUSHGRAM_GATE_CACHE = $failFirst
    $mix = @(@{ Name = '{0:x40}' -f 11; Passed = 'true'; Hours = 9 }, @{ Name = '{0:x40}' -f 12; Passed = 'true'; Hours = 8 },
        @{ Name = '{0:x40}' -f 13; Passed = 'false'; Hours = 7 }, @{ Name = '{0:x40}' -f 14; Passed = 'false'; Hours = 6 },
        @{ Name = '{0:x40}' -f 15; Passed = 'false'; Hours = 5 })
    foreach ($entry in $mix) {
        $dir = Join-Path $failFirst $entry.Name
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        Set-Content -LiteralPath (Join-Path $dir 'manifest.json') -Value "{ `"passed`": $($entry.Passed) }"
        (Get-Item -LiteralPath (Join-Path $dir 'manifest.json')).LastWriteTimeUtc = [DateTime]::UtcNow.AddHours(-$entry.Hours)
    }
    Remove-StaleGateEvidence
    $left = @(Get-ChildItem -LiteralPath $failFirst -Directory | ForEach-Object Name | Sort-Object)
    $expected = @(@($mix[0].Name, $mix[1].Name, $mix[4].Name) | Sort-Object)
    Assert-True (($left -join ',') -eq ($expected -join ',')) "Pruning left $($left -join ', '), not both passed runs and the newest failed one."

    # Two gates of one commit: the second stops while the first holds it, and goes ahead once it's done.
    $lockCommit = 'd' * 40
    $null = Start-GateEvidence -Commit $lockCommit
    $second = ". '$(Join-Path $Root 'scripts/gate-evidence.ps1')'; Start-GateEvidence -Commit '$lockCommit' | Out-Null; 'started'"
    $said = @(& pwsh -NoProfile -Command $second 2>&1 | ForEach-Object { "$_" }) -join ' '
    Assert-True ($LASTEXITCODE -ne 0 -and $said -like '*Another gate is keeping a run of*' -and $said -notlike '*started*') `
        "A second gate of the same commit replaced the first one's folder: $said"
    Unlock-GateEvidence -Commit $lockCommit
    $said = @(& pwsh -NoProfile -Command $second 2>&1 | ForEach-Object { "$_" }) -join ' '
    Assert-True ($LASTEXITCODE -eq 0 -and $said -like '*started*') "A gate was refused after the first let go of the commit: $said"

    Assert-True (@(Invoke-FixtureGit worktree list).Count -eq 1) 'A gate left its worktree behind.'
    . (Join-Path $PSScriptRoot 'script-wiring.ps1')
    Assert-True (Test-PushGateRunsSuite (Join-Path $Root 'scripts/pre-push.ps1') 'scripts/test-push-gate.ps1') `
        'The push gate does not run this suite when the hook changes.'
    Write-Host "[push-gate] $passed checks passed"
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], [EnvironmentVariableTarget]::Process) }
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $scratch.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe push gate fixture cleanup path.' }
    if (Test-Path -LiteralPath $scratch) {
        if (Test-Path -LiteralPath (Join-Path $scratch 'repo/.git')) { & git -C (Join-Path $scratch 'repo') worktree prune 2>$null | Out-Null }
        Remove-Item -LiteralPath $scratch -Recurse -Force
    }
}
