<#
.SYNOPSIS
    Checks a push before it leaves the machine.

.DESCRIPTION
    This project builds nothing on GitHub, so a push is the last point at which anything can be
    checked. Called by .git/hooks/pre-push with the remote name and URL, reading the pushed refs
    from standard input the way git supplies them. Run scripts/install-hooks.ps1 once to wire it up.

    Every push gets the commit checks: who committed each published commit, trailers or authors
    naming an AI tool, and tracked files that name the maintainer's machine or a phone. A tag, or
    a commit that adds or changes patches-bundle.json (the index Morphe Manager reads), is a
    release, and a release needs HUSHGRAM_ALLOW_RELEASE=1 set for that one push. A push that moves
    a PowerShell file has every tracked one parsed, and one that doesn't parse stops it.

    When a pushed commit changes the build, the patches, the extension or a root file their tests
    read, the tip is checked out into a clean worktree and built there: the unit tests, both lints,
    the catalog regenerated and compared, the bundle, and every declared build patched when
    HUSHGRAM_FIXTURE_DIR holds it. A change to the verification scripts or their contract and
    allowlist files counts as a build change too, so the declared builds are verified again with
    them. The ledger's rules run when the ledger or its scripts move, and the injected-register,
    device helper, resource table and release tooling suites when their own files move.

    The build waits for a slot in the machine's build queue and holds it to the end, and Gradle
    runs through the machine's wrapper (scripts/build-jobs.ps1 says how both are found). Without
    them it runs straight away with a warning. HUSHGRAM_ALLOW_RELEASE=1 puts the push ahead of
    everyday builds in the queue.

    Before the gate removes its worktree it keeps what a release needs, pass or fail, in a folder
    named for the commit (scripts/gate-evidence.ps1 says where): the test results, the bundle and
    its SBOM, each declared build's reports and passing patch run, and a manifest. The release
    receipt and the release check read them back with -FromGate rather than building, testing and
    patching the same commit again. A push whose files are all index files, over a commit the
    gate passed with nothing since but the index's own lines, isn't built or tested again. Its
    release check still runs, and a gate here that would patch the declared builds needs the kept
    run to have patched the same fixtures with the same desktop CLI.

    A push that changes a published file (README.md, CHANGELOG.md, the catalog, the version, the
    bug form, the index or the lists and scripts the release check reads) runs
    scripts/validate-release-facts.ps1 on the pushed tip: in place when the checkout is a clean
    copy of it, otherwise in a clean worktree. The index push that follows a release is held to the
    published release itself (-VerifyPublishedAsset), so it has to come from a clean checkout of
    the commit it pushes, with the receipt build-release-receipt.ps1 wrote beside it. Set
    HUSHGRAM_SKIP_PRE_PUSH=1 to push anyway.

.NOTES
    Taken from Hushfacebook's scripts/pre-push.ps1
    (https://github.com/SysAdminDoc/Hushfacebook, commit c15d4f7930505824789684d35039d3c78c8b0903).
    GPL-3.0-only. Modified for HushGram (Instagram), 2026.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$RemoteName,
    [Parameter(Position = 1)][string]$RemoteUrl,
    [string]$Root
)

$ErrorActionPreference = 'Stop'

# Not a parameter default: Windows PowerShell leaves $PSScriptRoot empty while it evaluates the
# defaults of an advanced script started with -File.
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
if ($env:HUSHGRAM_SKIP_PRE_PUSH -eq '1') {
    Write-Host '[pre-push] skipped by HUSHGRAM_SKIP_PRE_PUSH'
    exit 0
}
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'build-jobs.ps1')
. (Join-Path $PSScriptRoot 'gate-evidence.ps1')

# A hook runs with git's environment, which can miss user variables set after the shell started.
foreach ($envName in @('HUSHGRAM_DESKTOP_JAR', 'HUSHGRAM_FIXTURE_DIR', 'HUSHGRAM_WORKDIR')) {
    if (-not (Test-Path "Env:\$envName")) {
        $value = [Environment]::GetEnvironmentVariable($envName, [EnvironmentVariableTarget]::User)
        if ($value) { Set-Item -LiteralPath "Env:\$envName" -Value $value }
    }
}

$committer = 'matt_parker@outlook.com'
# Built from parts: Find-MachineNames refuses any tracked line that spells the notes folder's name.
$assistant = @('c', 'l', 'a', 'u', 'd', 'e') -join ''
$aiPattern = "(?i)\b($assistant|anthropic|openai|chatgpt|codex|copilot|gemini)\b"
$buildPaths = '^(extensions/|patches/|gradle/|build\.gradle\.kts$|settings\.gradle\.kts$|gradle\.properties$|' +
    'NOTICE$|provenance\.json$|README\.md$|patches-list\.json$|sources/|' +
    'scripts/(build-inputs\.gradle|canonical-build-inputs\.txt|build-identity\.ps1|test-build-identity\.ps1|DexDiff\.java|ResourceTableCheck\.java|MergeSplits\.java|injected-mutation-contracts\.txt|' +
    'injected-register-removal-allowlist\.txt|verify-all-patches\.ps1|verify-injected-registers\.ps1|' +
    'test-android-boundaries\.ps1|pre-push\.ps1|script-wiring\.ps1|build-jobs\.ps1|gate-evidence\.ps1)$)'
$ledgerPaths = '^(sources/|scripts/(instagram-sources|test-instagram-sources|audit-instagram-sources)\.ps1$|NOTICE$|provenance\.json$)'
$zero = '0' * 40

function Write-Step([string]$Message) { Write-Host "[pre-push] $Message" }
function Stop-Push([string]$Message) { $script:gateRefusal = $Message; Write-Host "[pre-push] refused: $Message"; exit 1 }
# The fixture a gate patches for each build the catalog declares: the instagram-<version>-<code>
# APK, bundle or split bundle of the declared version code in -Folder (Find-DeclaredFixture). Apk is
# $null for a declared build with none there.
function Get-DeclaredFixtures([string]$CatalogText, [string]$Folder) {
    $target = Get-PatchTarget -PatchList ($CatalogText | ConvertFrom-Json)
    foreach ($version in @($target.PackageVersions)) {
        $apk = Find-DeclaredFixture -Target $target -Version $version -Folder $Folder
        [pscustomobject]@{ Version = $version; Apk = $apk }
    }
}
function Invoke-Git {
    # git's output is read as UTF-8 (Use-Utf8ConsoleOutput), so a non-ASCII path or message comes
    # back whole when the push starts in Git Bash. $args is copied first, since inside the block
    # $args is the block's own.
    $gitArguments = $args
    $output = Use-Utf8ConsoleOutput { & git -C $Root @gitArguments }
    if ($LASTEXITCODE -ne 0) { throw "git $($args -join ' ') failed with exit $LASTEXITCODE" }
    return $output
}

$published = New-Object System.Collections.Generic.List[string]
$tips = New-Object System.Collections.Generic.List[string]
$release = $false
foreach ($line in @([Console]::In.ReadToEnd() -split "`n" | Where-Object { $_.Trim() })) {
    $localRef, $localSha, $remoteRef, $remoteSha = $line.Trim() -split '\s+'
    if ($localSha -eq $zero) { continue }
    if ($remoteRef -like 'refs/tags/*') { $release = $true }
    # Typed: a one-element result unwraps to a string, and splatting a string passes it a character at a time.
    [string[]]$range = if ($remoteSha -eq $zero) { $localSha, '--not', '--remotes' } else { "$remoteSha..$localSha" }
    foreach ($commit in @(Invoke-Git rev-list @range)) { if ($commit -and -not $published.Contains($commit)) { $published.Add($commit) } }
    if ($remoteRef -like 'refs/heads/*') { $tips.Add($localSha) }
}
if ($published.Count -eq 0 -and $tips.Count -eq 0 -and -not $release) { Write-Step 'nothing to check'; exit 0 }

$changed = New-Object System.Collections.Generic.HashSet[string]
foreach ($commit in $published) {
    $who = @(Invoke-Git show -s --format='%ae%n%ce%n%an' $commit)
    if ($who[1] -ne $committer) { Stop-Push "$commit was committed by $($who[1]), not $committer" }
    if (($who -join ' ') -match $aiPattern) { Stop-Push "$commit names an AI tool as its author" }
    $message = (Invoke-Git show -s --format='%B' $commit) -join "`n"
    foreach ($trailer in @($message -split "`n" | Where-Object { $_ -match '^(Co-Authored-By|Signed-off-by|Generated-by):' })) {
        if ($trailer -match $aiPattern -or $trailer -match '(?i)noreply@') { Stop-Push "$commit carries the trailer '$trailer'" }
    }
    if ($message -match "(?i)generated with .*($assistant|codex|copilot)") { Stop-Push "$commit says a tool generated it" }
    foreach ($path in @(Invoke-Git diff-tree --no-commit-id --name-only -r --root $commit)) {
        if ($path) { [void]$changed.Add($path) }
    }
}
if ($changed.Contains('patches-bundle.json')) { $release = $true }
if ($release -and $env:HUSHGRAM_ALLOW_RELEASE -ne '1') {
    Stop-Push 'this push is a release (a tag or patches-bundle.json). Set HUSHGRAM_ALLOW_RELEASE=1 for the push once it has been approved.'
}
# A push made for a release, its source push included, goes ahead of everyday builds in the
# machine's queue (build-jobs.ps1).
if ($env:HUSHGRAM_ALLOW_RELEASE -eq '1') { $env:BUILD_QUEUE_PRIORITY = 'release' }

$hits = @(Find-MachineNames -Root $Root -Commit @($published))
if ($hits.Count -gt 0) {
    $hits | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" }
    Stop-Push "$($hits.Count) line(s) name this machine or a phone"
}
Write-Step "$($published.Count) commit(s): committer, trailers and machine names are clean"

# Every tracked PowerShell file parses, once a push moves one. Most of these scripts run only for a
# release or with a phone, and no suite reads them all, so a syntax slip would otherwise turn up
# there first. Read from the pushed tip, not the working tree, which can hold other edits, and
# parsed as text, since Windows PowerShell's ParseFile reads a file with no byte order mark as ANSI.
if (@($changed | Where-Object { $_ -like '*.ps1' }).Count -gt 0) {
    $unparsed = New-Object System.Collections.Generic.List[string]
    $parseTip = if ($tips.Count -gt 0) { $tips[$tips.Count - 1] } else { $published[0] }
    $trackedScripts = @(Invoke-Git -c core.quotepath=false ls-tree -r --name-only $parseTip | Where-Object { $_ -like '*.ps1' })
    foreach ($trackedScript in $trackedScripts) {
        $scriptText = (Invoke-Git show "${parseTip}:$trackedScript") -join "`n"
        $parseErrors = $null
        [void][System.Management.Automation.Language.Parser]::ParseInput(
            $scriptText, (Join-Path $Root $trackedScript), [ref]$null, [ref]$parseErrors)
        foreach ($parseError in @($parseErrors)) {
            $unparsed.Add("${trackedScript}:$($parseError.Extent.StartLineNumber) $($parseError.Message)")
        }
    }
    if ($unparsed.Count -gt 0) {
        $unparsed | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" }
        Stop-Push "$($unparsed.Count) parse error(s) in the tracked PowerShell files, and a script that doesn't parse stops wherever it's next run"
    }
    Write-Step "the $($trackedScripts.Count) tracked PowerShell files parse"
}

if (@($changed | Where-Object { $_ -match $ledgerPaths }).Count -gt 0) {
    Write-Step 'the source ledger or its rules changed, running them'
    & pwsh -NoProfile -File (Join-Path $Root 'scripts/test-instagram-sources.ps1')
    if ($LASTEXITCODE -ne 0) { Stop-Push 'scripts/test-instagram-sources.ps1 failed' }
}

# The verification gates' own suites, each when its files move. Each runs the working tree's copy,
# as the ledger's does, and a suite the gate expects that isn't there stops the push rather than
# being skipped with a note.
$injectedRegisterVerifierPaths = @(
    'scripts/BadDexFixture.java',
    'scripts/DexDiff.java',
    'scripts/injected-mutation-contracts.txt',
    'scripts/injected-register-contracts.ps1',
    'scripts/injected-register-removal-allowlist.txt',
    'scripts/script-wiring.ps1',
    'scripts/test-injected-registers.ps1',
    'scripts/verify-all-patches.ps1',
    'scripts/verify-injected-registers.ps1'
)
$touchesInjectedRegisterVerifier = @($changed | Where-Object { $_ -in $injectedRegisterVerifierPaths }).Count -gt 0
$resourceTableCheckPaths = @(
    'scripts/MergeSplits.java',
    'scripts/ResourceTableCheck.java',
    'scripts/test-resource-table-check.ps1',
    'scripts/verify-all-patches.ps1'
)
$touchesResourceTableCheck = @($changed | Where-Object { $_ -in $resourceTableCheckPaths }).Count -gt 0
$injectedRegisterDevicePaths = @(
    'scripts/device-install.ps1',
    'scripts/injected-register-device.ps1',
    'scripts/patch-for-device.ps1',
    'scripts/pre-push.ps1',
    'scripts/script-wiring.ps1',
    'scripts/test-injected-register-device.ps1',
    'scripts/verify-injected-registers.ps1'
)
$touchesInjectedRegisterDevice = @($changed | Where-Object { $_ -in $injectedRegisterDevicePaths }).Count -gt 0
# The release tooling's suite runs on copies of the files the release check reads, so it runs when
# they move as well as when the scripts do.
$releaseToolingPaths = @(
    '.github/ISSUE_TEMPLATE/bug_report.yml',
    'CHANGELOG.md',
    'README.md',
    'gradle.properties',
    'gradle/libs.versions.toml',
    'patches-list.json',
    'sources/instagram-sources.json',
    'sources/carried-library-licenses.json',
    'scripts/advisory-exceptions.txt',
    'scripts/audit-dependencies.ps1',
    'scripts/dependency-graphs.init.gradle',
    'scripts/build-inputs.gradle',
    'scripts/build-identity.ps1',
    'scripts/build-jobs.ps1',
    'scripts/gate-evidence.ps1',
    'scripts/canonical-build-inputs.txt',
    'scripts/test-build-identity.ps1',
    'scripts/dependency-advisory-exceptions.txt',
    'scripts/apk-facts.ps1',
    'scripts/build-release-receipt.ps1',
    'scripts/common.ps1',
    'scripts/instagram-sources.ps1',
    'scripts/manifest-delta-allowlist.txt',
    'scripts/patch-report.ps1',
    'scripts/patch-target.ps1',
    'scripts/pre-push.ps1',
    'scripts/release-advisories.ps1',
    'scripts/release-receipt.ps1',
    'scripts/script-wiring.ps1',
    'scripts/test-release-tooling.ps1',
    'scripts/test-carried-licenses.ps1',
    'scripts/PatchCoverage.java',
    'scripts/patch-coverage-expectations.json',
    'scripts/validate-release-facts.ps1'
)
$touchesReleaseTooling = @($changed | Where-Object { $_ -in $releaseToolingPaths }).Count -gt 0
$androidBoundaryPaths = @(
    'extensions/instagram/build.gradle.kts',
    'extensions/instagram/src/test/java/app/hushgram/extension/instagram/misc/SameKeyProviderCallerTest.java',
    'scripts/test-android-boundaries.ps1', 'scripts/pre-push.ps1', 'scripts/script-wiring.ps1'
)
$touchesAndroidBoundaries = @($changed | Where-Object { $_ -in $androidBoundaryPaths }).Count -gt 0
$suites = @()
if (@($changed | Where-Object {
    $_ -in @('gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
        'scripts/VerifyGradleWrapper.java', 'scripts/test-gradle-wrapper.ps1', 'scripts/pre-push.ps1')
}).Count -gt 0) {
    $suites += , @('scripts/test-gradle-wrapper.ps1', 'the Gradle wrapper changed, checking refusal before execution')
}
if ($touchesInjectedRegisterVerifier) {
    $suites += , @('scripts/test-injected-registers.ps1', 'the injected-register verifier changed, running its fixture tests')
}
if ($touchesResourceTableCheck) {
    $suites += , @('scripts/test-resource-table-check.ps1', 'the resource table check changed, running its fixture tests')
}
if ($touchesInjectedRegisterDevice) {
    $suites += , @('scripts/test-injected-register-device.ps1', 'the injected-register device helper changed, running its cleanup fixtures')
}
if ($touchesReleaseTooling) {
    $suites += , @('scripts/test-release-tooling.ps1', 'the release tooling or a file it reads changed, running its contract tests')
    $suites += , @('scripts/test-carried-licenses.ps1', 'checking reviewed carried-library license evidence')
}
if (@($changed | Where-Object {
    $_ -in @('scripts/InspectPatchBundle.java', 'scripts/CompositionFixture.java', 'scripts/CompositionInitializer.java',
        'scripts/patch-sources.ps1', 'scripts/patch-with-sources.ps1', 'scripts/test-source-composition.ps1',
        'scripts/patch-target.ps1', 'scripts/apk-facts.ps1', 'scripts/common.ps1', 'scripts/patch-report.ps1',
        'scripts/pre-push.ps1', 'scripts/script-wiring.ps1')
}).Count -gt 0) {
    $suites += , @('scripts/test-source-composition.ps1', 'selected-source tooling changed, checking targets and native dependency ownership')
}
if (@($changed | Where-Object {
    $_ -in @('scripts/build-inputs.gradle', 'scripts/canonical-build-inputs.txt', 'scripts/build-identity.ps1',
        'scripts/test-build-identity.ps1', 'scripts/release-receipt.ps1', 'patches/build.gradle.kts',
        'scripts/pre-push.ps1', 'scripts/script-wiring.ps1')
}).Count -gt 0) {
    $suites += , @('scripts/test-build-identity.ps1', 'the production identity boundary changed, checking canonical inputs')
}
if (@($changed | Where-Object {
    $_ -in @('scripts/gen-l10n.py', 'scripts/sync-l10n.py', 'scripts/crowdin-l10n.py',
        'scripts/test-l10n.py', 'scripts/test-crowdin-l10n.py', 'scripts/test-translations.ps1',
        'scripts/pre-push.ps1', 'scripts/script-wiring.ps1') -or
    $_ -like 'extensions/*/src/main/java/*' -or $_ -like 'extensions/shared/library/src/main/l10n/*'
}).Count -gt 0) {
    $suites += , @('scripts/test-translations.ps1', 'translation catalog or tooling changed, checking its tests')
}
if (@($changed | Where-Object {
    $_ -in @('scripts/build-jobs.ps1', 'scripts/test-build-jobs.ps1', 'scripts/audit-dependencies.ps1',
        'scripts/build-release-receipt.ps1', 'scripts/verify-all-patches.ps1', 'scripts/pre-push.ps1', 'scripts/script-wiring.ps1',
        'scripts/release/patch-all-builds.ps1', 'scripts/release/preflight.ps1')
}).Count -gt 0) {
    $suites += , @('scripts/test-build-jobs.ps1', 'the build queue wiring changed, checking its stand-ins')
}
if (@($changed | Where-Object {
    $_ -like 'scripts/release/*' -or $_ -in @('scripts/test-release-helpers.ps1', 'scripts/test-release-notes.py', 'scripts/common.ps1', 'scripts/patch-target.ps1',
        'scripts/patch-report.ps1', 'scripts/apk-facts.ps1', 'scripts/build-jobs.ps1', 'scripts/gate-evidence.ps1',
        'scripts/Resolve-Java.ps1', 'scripts/pre-push.ps1', 'scripts/script-wiring.ps1')
}).Count -gt 0) {
    $suites += , @('scripts/test-release-helpers.ps1', 'a release helper changed, running its stand-ins')
}
if (@($changed | Where-Object {
    $_ -in @('scripts/pre-push.ps1', 'scripts/test-push-gate.ps1', 'scripts/build-jobs.ps1', 'scripts/gate-evidence.ps1',
        'scripts/common.ps1', 'scripts/patch-target.ps1', 'scripts/script-wiring.ps1')
}).Count -gt 0) {
    $suites += , @('scripts/test-push-gate.ps1', 'the push gate changed, running it end to end on stand-ins')
}
foreach ($suite in $suites) {
    $suiteScript = Join-Path $Root $suite[0]
    if (-not (Test-Path -LiteralPath $suiteScript -PathType Leaf)) {
        Stop-Push "$($suite[0]) is missing. The gate expects it, so the push stops."
    }
    Write-Step $suite[1]
    & pwsh -NoProfile -File $suiteScript -Root $Root
    if ($LASTEXITCODE -ne 0) { Stop-Push "$($suite[0]) failed" }
}

# The release facts, when a published file or a list or script the check reads moves. Every push
# but the index push leaves the description's test counts and this checkout's build outputs alone
# and lets the index lag a version still being prepared. The index push, the one that hands a
# release to Manager users, is held to the published release: its bundle, SBOM, receipt and
# SHA256SUMS.txt, the tag, the census, and the test counts its description quotes. Both sets come
# from Get-ReleaseFactsArguments in common.ps1, which test-release-tooling.ps1 runs as well.
$releaseFactPaths = @(
    '.github/ISSUE_TEMPLATE/bug_report.yml',
    'CHANGELOG.md',
    'README.md',
    'gradle.properties',
    'gradle/libs.versions.toml',
    'patches-bundle.json',
    'patches-list.json',
    'sources/instagram-sources.json',
    'scripts/advisory-exceptions.txt',
    'scripts/apk-facts.ps1',
    'scripts/common.ps1',
    'scripts/instagram-sources.ps1',
    'scripts/manifest-delta-allowlist.txt',
    'scripts/patch-target.ps1',
    'scripts/release-advisories.ps1',
    'scripts/release-receipt.ps1',
    'scripts/validate-release-facts.ps1'
)
if (@($changed | Where-Object { $_ -in $releaseFactPaths }).Count -gt 0 -and $tips.Count -gt 0) {
    $factsTip = $tips[$tips.Count - 1]
    $inPlace = "$(Invoke-Git rev-parse HEAD)".Trim() -eq $factsTip -and
        @(Invoke-Git status --porcelain --untracked-files=no).Count -eq 0
    if ($changed.Contains('patches-bundle.json')) {
        if (-not $inPlace) {
            Stop-Push ("an index push checks the bundle and receipt this checkout built, so push it from a clean " +
                "checkout of $factsTip")
        }
        $indexFacts = Get-ReleaseFactsArguments -Push Index -Root $Root
        if ($indexFacts['ArtifactPath']) {
            Write-Step "a new index, checking the published release against $(Split-Path -Leaf $indexFacts['ArtifactPath']) built here"
        } elseif ($indexFacts['FromGate']) {
            Write-Step "a new index, checking the published release against the bundle the gate built for it"
        } else {
            Write-Step 'a new index and no bundle built here for the version it publishes, so the published asset is checked on its own'
        }
        & pwsh -NoProfile -File (Join-Path $Root 'scripts/validate-release-facts.ps1') `
            @(ConvertTo-ScriptArguments (Get-ReleaseFactsArguments -Push Index -Root $Root))
        if ($LASTEXITCODE -ne 0) { Stop-Push 'the published release does not agree with the index' }
    } elseif ($inPlace) {
        # The test results and the bundle here are whatever this checkout last built, often nothing
        # since a merge moved its sources, so they're left unread. The build below tests the tip.
        Write-Step 'a published file changed, checking the release facts'
        & pwsh -NoProfile -File (Join-Path $Root 'scripts/validate-release-facts.ps1') `
            @(ConvertTo-ScriptArguments (Get-ReleaseFactsArguments -Push Ordinary -Root $Root))
        if ($LASTEXITCODE -ne 0) { Stop-Push 'the release facts do not agree' }
    } else {
        # The pushed commit's own check, in a worktree of it. Its build folders can hold another
        # commit's outputs, so they're left unread. That check can be older than this hook, so the
        # set is spelled the way it knows (-Script).
        $factsTree = Join-Path ([System.IO.Path]::GetTempPath()) ('hushgram-facts-' + [guid]::NewGuid().ToString('N').Substring(0, 12))
        Write-Step "a published file changed, checking the release facts of $($factsTip.Substring(0, 12)) in a clean worktree"
        Invoke-Git worktree add --detach $factsTree $factsTip | Out-Null
        $factsExit = 0
        try {
            $factsCheck = Join-Path $factsTree 'scripts/validate-release-facts.ps1'
            if (Test-Path -LiteralPath $factsCheck -PathType Leaf) {
                & pwsh -NoProfile -File $factsCheck `
                    @(ConvertTo-ScriptArguments (Get-ReleaseFactsArguments -Push Ordinary -Root $factsTree -Script $factsCheck))
                $factsExit = $LASTEXITCODE
            } else {
                Write-Step "$($factsTip.Substring(0, 12)) has no release check of its own"
            }
        } finally {
            & git -C $Root worktree remove --force $factsTree 2>$null | Out-Null
            if (Test-Path -LiteralPath $factsTree) { Remove-Item -LiteralPath $factsTree -Recurse -Force -ErrorAction SilentlyContinue }
        }
        if ($factsExit -ne 0) { Stop-Push 'the release facts do not agree' }
    }
}

if (@($changed | Where-Object { $_ -match $buildPaths }).Count -eq 0 -or $tips.Count -eq 0) {
    Write-Step 'no build input changed'
    exit 0
}

# An index push over a commit the gate passed. Every file pushed is an index file, and everything
# since that commit is the index's own lines (gate-evidence.ps1 says which), which nothing the gate
# builds or tests reads, so the same sources aren't built and tested a second time. The release
# switch, the commit checks, the suites and the release facts above ran as on any push, the last
# against the published release. When a gate here would patch the declared builds, the kept run
# has to have patched the same fixtures with the same desktop CLI, or the gate runs.
if ($changed.Count -gt 0 -and @($changed | Where-Object { $script:GateIndexFiles -cnotcontains $_ }).Count -eq 0 -and
        "$(Invoke-Git rev-parse HEAD)".Trim() -eq $tips[$tips.Count - 1]) {
    $passedRun = Find-GateEvidence -Root $Root -AllowIndexCommits -Prefix '[pre-push]'
    if ($passedRun) {
        $gap = $null
        $wouldPatch = @()
        $fixtureFolder = $env:HUSHGRAM_FIXTURE_DIR
        if ($fixtureFolder -and (Test-Path -LiteralPath $fixtureFolder -PathType Container)) {
            $wouldPatch = @(Get-DeclaredFixtures -CatalogText (Get-Content -LiteralPath (Join-Path $Root 'patches-list.json') -Raw) `
                -Folder $fixtureFolder)
            $unfixed = @($wouldPatch | Where-Object { -not $_.Apk })
            if ($unfixed.Count -gt 0) { $gap = "no fixture for the declared build $($unfixed[0].Version) in $fixtureFolder" }
        }
        if (-not $gap) {
            $gap = Get-GateRunGap -Evidence $passedRun -DesktopJar $(if ($wouldPatch.Count -gt 0) { Resolve-DesktopCli -Root $Root }) `
                -FixtureFingerprint $(if ($fixtureFolder -and (Test-Path -LiteralPath $fixtureFolder -PathType Container)) { Get-FixtureFingerprint -Folder $fixtureFolder }) `
                -Fixtures @($wouldPatch | ForEach-Object { [pscustomobject]@{ Version = $_.Version; Apk = $_.Apk.FullName } })
        }
        if (-not $gap) {
            $since = if ($passedRun.IndexOnly) { "everything since $($passedRun.Commit.Substring(0, 12)) is the index" } else { 'the gate passed this commit' }
            Write-Step "$since, and the gate's run of it holds up, so it isn't built and tested again"
            exit 0
        }
        Write-Step "the gate's run of $($passedRun.Commit.Substring(0, 12)) doesn't cover this push: $gap"
    }
}

$tip = $tips[$tips.Count - 1]
$gate = Join-Path ([System.IO.Path]::GetTempPath()) ('hushgram-gate-' + [guid]::NewGuid().ToString('N').Substring(0, 12))
Write-Step "building $($tip.Substring(0, 12)) in a clean worktree"
Invoke-Git worktree add --detach $gate $tip | Out-Null
# The whole gate holds one slot in the machine's build queue, so another session's build can't
# take the cores between its steps. Gradle, the boundary self-tests and the patch runs started
# inside it see the slot and don't queue again.
$gateJob = $null
# What the gate leaves for the release scripts, pass or fail, kept by commit outside the worktree
# (gate-evidence.ps1): its test results, the bundle and SBOM, each declared build's reports and
# its patched APK, and a manifest saying how far it got.
$evidence = $null
$gateStage = 'setup'
$gatePassed = $false
$fixturesPatched = $false
$fixtureRuns = New-Object System.Collections.Generic.List[object]
try {
    $gateJob = Enter-HeavyJob -Label "gate $($tip.Substring(0, 12))"
    $evidence = Start-GateEvidence -Commit $tip
    $localProperties = Join-Path $Root 'local.properties'
    if (Test-Path -LiteralPath $localProperties) { Copy-Item -LiteralPath $localProperties -Destination $gate }

    # The declared builds' fixtures and the desktop CLI are found before anything is built, so a
    # missing one stops the push in seconds rather than after the tests.
    $catalog = Join-Path $gate 'patches-list.json'
    $before = Get-Content -LiteralPath $catalog -Raw
    $fixtures = $env:HUSHGRAM_FIXTURE_DIR
    $fixtureApks = @()
    $fixtureFingerprint = $null
    if ($fixtures -and (Test-Path -LiteralPath $fixtures -PathType Container)) {
        # Taken before anything is built: the patch tests read the whole folder.
        $fixtureFingerprint = Get-FixtureFingerprint -Folder $fixtures
        $desktop = Resolve-DesktopCli -Root $Root -Required
        $fixtureApks = @(foreach ($fixture in @(Get-DeclaredFixtures -CatalogText $before -Folder $fixtures)) {
            if (-not $fixture.Apk) { Stop-Push "no fixture for the declared build $($fixture.Version) in $fixtures" }
            $fixture
        })
    }

    # Cheap checks first: the catalog, both lints and the runtime tests take a few minutes, and
    # :patches:test reads every fixture build for most of a quarter hour. v0.0.7's gates ran the
    # lot as one build, so a lint slip or a stale catalog surfaced after the patch tests.
    $gateStage = 'quick'
    Invoke-GradleBuild -ProjectDir $gate -Tasks @('--console=plain', ':patches:generatePatchesList',
        ':extensions:instagram:testDebugUnitTest', ':extensions:instagram:verifyAndroidBoundaries',
        ':extensions:instagram:lint', ':extensions:shared:library:lint')
    if ($LASTEXITCODE -ne 0) { Stop-Push 'the runtime tests, a lint or the catalog failed' }
    if ((Get-Content -LiteralPath $catalog -Raw) -cne $before) {
        Stop-Push 'patches-list.json is stale: run :patches:generatePatchesList and commit it'
    }

    if ($touchesAndroidBoundaries) {
        $gateStage = 'boundaries'
        & pwsh -NoProfile -File (Join-Path $gate 'scripts/test-android-boundaries.ps1') -Root $gate
        if ($LASTEXITCODE -ne 0) { Stop-Push 'Android boundary gate self-tests failed' }
    }

    $gateStage = 'full'
    Invoke-GradleBuild -ProjectDir $gate -Tasks @('--console=plain', ':patches:test', ':patches:buildAndroid')
    if ($LASTEXITCODE -ne 0) { Stop-Push 'the patch tests failed or the bundle did not build' }

    $gateStage = 'fixtures'
    $bundle = Get-ChildItem -LiteralPath (Join-Path $gate 'patches/build/release') -Filter 'patches-*.mpp' | Select-Object -First 1
    if ($fixtureApks.Count -eq 0) {
        $gatePassed = $true
        Write-Step 'HUSHGRAM_FIXTURE_DIR is not set, so no build was patched'
        exit 0
    }
    # The pushed commit's own check, which keeps its passing run when it knows how (-KeepIn).
    $verifyScript = Join-Path $gate 'scripts/verify-all-patches.ps1'
    $verifyParameters = @([System.Management.Automation.Language.Parser]::ParseInput(
        [System.IO.File]::ReadAllText($verifyScript), [ref]$null, [ref]$null).ParamBlock.Parameters |
        ForEach-Object { $_.Name.VariablePath.UserPath })
    foreach ($fixture in $fixtureApks) {
        $version = $fixture.Version
        $apk = $fixture.Apk
        $work = Join-Path $gate "build/verify-$version"
        New-Item -ItemType Directory -Path $work -Force | Out-Null
        $fixtureRuns.Add([pscustomobject]@{ Version = $version; Apk = $apk.FullName; WorkDir = $work })
        $keep = if ($verifyParameters -contains 'KeepIn') { @('-KeepIn', (Join-Path $evidence "fixtures/$version/kept")) } else { @() }
        & pwsh -NoProfile -File $verifyScript -Apk $apk.FullName `
            -DesktopJar $desktop -WorkDir $work -Bundle $bundle.FullName -PatchList $catalog @keep
        if ($LASTEXITCODE -ne 0) { Stop-Push "patching Instagram $version failed" }
    }
    $fixturesPatched = $true
    $gateStage = 'done'
    $gatePassed = $true
    Write-Step 'every check passed'
} finally {
    if ($evidence) {
        # Kept even when the gate refused, so a failed run's test results outlive its worktree.
        try {
            Save-GateEvidence -Directory $evidence -GateRoot $gate -Commit $tip -Passed $gatePassed -Stage $gateStage `
                -Reason $script:gateRefusal -FixtureRuns $fixtureRuns.ToArray() -FixturesPatched $fixturesPatched -FixtureFingerprint $fixtureFingerprint | Out-Null
            Write-Step "kept this gate's results in $evidence"
        } catch {
            Write-Warning "[pre-push] the gate's results could not be kept: $($_.Exception.Message)"
        }
    }
    & git -C $Root worktree remove --force $gate 2>$null | Out-Null
    if (Test-Path -LiteralPath $gate) { Remove-Item -LiteralPath $gate -Recurse -Force -ErrorAction SilentlyContinue }
    Exit-HeavyJob $gateJob
}
exit 0
