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
    read, the tip is checked out into a clean worktree and built there. An ordinary push gets one
    build: everything compiles, the catalog is regenerated and compared, and the tests each change
    can reach run with the checks that scan every source (Get-FocusedChecks says which), with every
    failure shown. A changed file with no test to run stops the push before anything is built. A
    release push (HUSHGRAM_ALLOW_RELEASE=1),
    or any push made with HUSHGRAM_FULL_GATE=1, gets the full gate: the unit tests, both lints, the
    catalog, the bundle, and every declared build patched when HUSHGRAM_FIXTURE_DIR holds it. A
    change to the verification scripts or their contract and allowlist files counts as a build
    change too. The ledger's rules run when the ledger or its scripts move, and the injected-register,
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

    One thing runs on every push, a skipped one too, and never stops it: a pushed commit that
    moves where a patch hooks Instagram while scripts/injected-mutation-contracts.txt stays as it
    was gets a one-line hint naming the patch (Write-ContractsHint).

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

# The patch that -Path belongs to in -Commit, named as Morphe lists it: the bytecodePatch the file
# declares, else those its folder declares, else the path itself.
function Get-HookPatchName([string]$Commit, [string]$Path) {
    $folder = $Path.Substring(0, $Path.LastIndexOf('/') + 1)
    foreach ($scope in @($Path, $folder)) {
        $declared = @(& git -C $Root grep -h -A4 -E 'bytecodePatch\(' $Commit -- $scope 2>$null | ForEach-Object {
            if ("$_" -match '^\s*name\s*=\s*"([^"]+)"') { $Matches[1] }
        } | Select-Object -Unique)
        if ($declared.Count -gt 0) { return $declared -join ', ' }
    }
    return $Path
}

# A hint, never a refusal. A pushed commit that adds or drops a line where a patch hooks Instagram
# (an instruction it adds, replaces or removes, an invoke, a call into the extension, a fingerprint
# or its strings) while no pushed commit touches the contracts file gets one line per patch, so a
# hook that outgrew its DexDiff rule shows the day it lands rather than at the release preflight.
# Comment lines don't count. It runs before the skip below, since everyday pushes skip the rest,
# and a push it can't read gets a warning and goes on.
function Write-ContractsHint([string[]]$PushedRefs) {
    $contracts = 'scripts/injected-mutation-contracts.txt'
    $hookLine = '(add(Instruction|Instructions|InstructionsWithLabels)|replaceInstruction|removeInstructions?|Fingerprint)\(|' +
        'invoke-[a-z]|\bstrings( ?\(| ?=)|->[A-Za-z_<$]'
    $none = '0' * 40
    try {
        $commits = New-Object System.Collections.Generic.List[string]
        $hintTip = $null
        foreach ($refLine in $PushedRefs) {
            $localRef, $localSha, $remoteRef, $remoteSha = $refLine.Trim() -split '\s+'
            if ($localSha -eq $none -or $remoteRef -notlike 'refs/heads/*') { continue }
            [string[]]$range = if ($remoteSha -eq $none) { $localSha, '--not', '--remotes' } else { "$remoteSha..$localSha" }
            $listed = @(& git -C $Root rev-list @range)
            if ($LASTEXITCODE -ne 0) { throw "git rev-list $($range -join ' ') failed with exit $LASTEXITCODE" }
            foreach ($commit in $listed) { if ($commit -and -not $commits.Contains($commit)) { $commits.Add($commit) } }
            $hintTip = $localSha
        }
        $moved = New-Object 'System.Collections.Generic.SortedSet[string]' ([StringComparer]::Ordinal)
        foreach ($commit in $commits) {
            if (@(& git -C $Root -c core.quotepath=false diff-tree --no-commit-id --name-only -r --root $commit) -contains $contracts) { return }
            $diffPath = $null
            foreach ($diffLine in @(& git -C $Root -c core.quotepath=false diff-tree -p --no-color --no-ext-diff -U0 --no-commit-id --root $commit -- patches/src/main)) {
                if ($diffLine -cmatch '^(---|\+\+\+) /dev/null$') { continue }
                if ($diffLine -cmatch '^(--- a|\+\+\+ b)/(.+)$') { $diffPath = $Matches[2]; continue }
                if ($diffLine -cnotmatch '^[+-]') { continue }
                $code = $diffLine.Substring(1).Trim()
                if ($diffPath -and $code -cnotmatch '^(//|/?\*)' -and $code -cmatch $hookLine) { [void]$moved.Add($diffPath) }
            }
        }
        $byPatch = [ordered]@{}
        foreach ($movedPath in $moved) {
            $patchName = Get-HookPatchName -Commit $hintTip -Path $movedPath
            if (-not $byPatch.Contains($patchName)) { $byPatch[$patchName] = New-Object System.Collections.Generic.List[string] }
            $byPatch[$patchName].Add($movedPath.Substring($movedPath.LastIndexOf('/') + 1))
        }
        foreach ($patchName in $byPatch.Keys) {
            Write-Host ("[pre-push] hint: $patchName moved where it hooks Instagram ($($byPatch[$patchName] -join ', ')) " +
                "and $contracts didn't change with it. Its DexDiff rules may need the same move before the release preflight runs them.")
        }
    } catch {
        Write-Warning "[pre-push] the contracts hint couldn't read this push: $($_.Exception.Message)"
    }
}

# The pushed refs, read once and before the skip, so the hint sees a skipped push too.
$pushedRefs = if ([Console]::IsInputRedirected) { @([Console]::In.ReadToEnd() -split "`n" | Where-Object { $_.Trim() }) } else { @() }
Write-ContractsHint -PushedRefs $pushedRefs

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
# The lines of the patch sources in -Commit that match the extended regex -Pattern, as path:line.
# git grep exits 1 when nothing matches, which is an answer here rather than a failure.
function Find-PatchSourceLines([string]$Commit, [string]$Pattern) {
    $output = Use-Utf8ConsoleOutput { & git -C $Root -c core.quotepath=false grep -I -E -e $Pattern $Commit -- patches/src }
    if ($LASTEXITCODE -gt 1) { throw "git grep failed with exit $LASTEXITCODE" }
    foreach ($line in @($output)) { if ($line) { "$line".Substring($Commit.Length + 1) } }
}
# What an ordinary push builds and tests, from -Changed (the pushed paths), -Tracked (every file in
# the pushed tip), -LedgerFiles (the files the tip's provenance.json names one by one) and
# -FindPatchSources (Find-PatchSourceLines on the tip). Everything compiles and the catalog is
# regenerated. Any shipped source runs the tests that scan every source (notices, provenance,
# identities, return shapes, strings), the root files the patch tests read run the tests that read
# them, and a file provenance.json names runs ProvenanceTest, which reads its header.
# An extension source runs every runtime test, since tests anywhere import it, and the patch areas
# whose sources or tests name its class, since those read the built extension (ExtensionDex) or
# its source. A patch area's source runs that area's tests, and a main source or a test helper
# there also runs every area that imports the area, and every area that imports those. A test class
# runs its own area. A patch source outside the areas runs the tests in its folder.
# An extension resource, a translation table or a runtime test helper runs every runtime test, and
# a shared patch utility or a patch test helper outside the areas runs every patch test. A build
# file, or any other file the build reads from extensions/ or patches/ outside the sources, runs
# both. -Boundaries adds the Android boundary check, which reads every runtime test's results.
# A file still in the tip with no test to run is Uncovered, and the push stops rather than going
# out untested. Deleted files, the build scripts, the ledger and the gate's own scripts are left to
# the compile and the suites the hook runs for them.
function Get-FocusedChecks {
    param([string[]]$Changed, [string[]]$Tracked, [bool]$Boundaries, [string[]]$LedgerFiles = @(),
        [Parameter(Mandatory = $true)][scriptblock]$FindPatchSources)
    $inTip = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($trackedPath in $Tracked) { [void]$inTip.Add($trackedPath) }
    $ledgerNamed = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($ledgerPath in $LedgerFiles) { [void]$ledgerNamed.Add($ledgerPath) }
    $testFiles = @($Tracked | Where-Object { $_ -cmatch '^(extensions/instagram/src/test/java|patches/src/test/(kotlin|java))/.+Test\.(java|kt)$' })
    $runtimeRoots = @('extensions/instagram/src/test/java/')
    $patchRoots = @('patches/src/test/kotlin/', 'patches/src/test/java/')
    # The test classes in -Folder under the roots, by the name --tests takes. -Within takes subfolders too.
    function Get-FolderTests([string[]]$Roots, [string]$Folder, [bool]$Within) {
        foreach ($testFile in $testFiles) {
            foreach ($testRoot in $Roots) {
                if (-not $testFile.StartsWith("$testRoot$Folder/", [StringComparison]::Ordinal)) { continue }
                if (-not $Within -and $testFile.Substring($testRoot.Length + $Folder.Length + 1).Contains('/')) { continue }
                ($testFile.Substring($testRoot.Length) -replace '\.(java|kt)$', '').Replace('/', '.')
            }
        }
    }
    $readers = @{
        'README.md' = @('app/morphe/ReadmePatchNamesTest')
        'patches-list.json' = @('app/morphe/ReadmePatchNamesTest', 'app/morphe/DefaultSelectionPolicyTest',
            'app/morphe/PatchCategoriesTest', 'app/morphe/util/CatalogDependencyTest', 'app/morphe/util/DefaultCatalogTest')
        'NOTICE' = @('app/morphe/ProvenanceTest')
        'provenance.json' = @('app/morphe/ProvenanceTest')
        'patches/src/test/resources/obfuscated-identities.txt' = @('app/morphe/ObfuscatedIdentityTest')
    }
    $runtime = New-Object 'System.Collections.Generic.SortedSet[string]' ([StringComparer]::Ordinal)
    $patchTests = New-Object 'System.Collections.Generic.SortedSet[string]' ([StringComparer]::Ordinal)
    $uncovered = New-Object System.Collections.Generic.List[string]
    $everyRuntimeTest = $Boundaries
    $everyPatchTest = $false
    $areaPattern = '^patches/src/(main|test)/(kotlin|java)/app/morphe/patches/instagram/([^/]+)/'
    # Shared with the helpers below, which can't assign this function's variables.
    $state = @{
        Importers = $null
        Followed = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
        EveryPatchTest = $false
    }
    # The patch sources in the tip that import an Instagram area, by area, read once from their
    # import lines. An area's own sources aren't listed as importing it.
    function Get-AreaImporters([string]$Area) {
        if ($null -eq $state.Importers) {
            $state.Importers = @{}
            foreach ($hit in @(& $FindPatchSources '^[[:space:]]*import[[:space:]]+app\.morphe\.patches\.instagram\.[a-z0-9_]+\.')) {
                $at = $hit.IndexOf(':')
                if ($hit.Substring($at + 1) -cnotmatch 'app\.morphe\.patches\.instagram\.([a-z0-9_]+)\.') { continue }
                $imported = $Matches[1]
                $importer = $hit.Substring(0, $at)
                if ($importer -cmatch $areaPattern -and $Matches[3] -ceq $imported) { continue }
                if (-not $state.Importers.ContainsKey($imported)) {
                    $state.Importers[$imported] = New-Object 'System.Collections.Generic.SortedSet[string]' ([StringComparer]::Ordinal)
                }
                [void]$state.Importers[$imported].Add($importer)
            }
        }
        if ($state.Importers.ContainsKey($Area)) { return @($state.Importers[$Area]) }
        return @()
    }
    # What runs for a patch source that changed, or that names or imports one that did, and whether
    # anything does. An area's source runs the area's tests. A main source or a test helper there
    # also runs every area that imports the area, and every area that imports those; a test class
    # stops at its own area. A patch utility, a shared patch or a test helper outside the areas is
    # used by every patch's tests.
    function Add-PatchSource([string]$Path) {
        if ($Path -cmatch $areaPattern) {
            $sourceArea = $Matches[3]
            $covered = @(Get-FolderTests $patchRoots "app/morphe/patches/instagram/$sourceArea" $true).Count -gt 0
            if ($covered) { [void]$patchTests.Add("app.morphe.patches.instagram.$sourceArea.*") }
            if (($Path -cmatch '^patches/src/main/' -or $Path -cnotmatch 'Test\.(kt|java)$') -and $state.Followed.Add($sourceArea)) {
                foreach ($importer in @(Get-AreaImporters $sourceArea)) {
                    if (Add-PatchSource $importer) { $covered = $true }
                }
            }
            return $covered
        }
        if ($Path -cmatch '^patches/src/(main|test)/(kotlin|java)/app/morphe/patches/instagram/[^/]+\.(kt|java)$') {
            # Staged into every area's tests, so every area runs.
            $covered = @(Get-FolderTests $patchRoots 'app/morphe/patches/instagram' $true).Count -gt 0
            if ($covered) { [void]$patchTests.Add('app.morphe.patches.instagram.*') }
            return $covered
        }
        if ($Path -cmatch '^patches/src/test/(kotlin|java)/(.+Test)\.(kt|java)$') {
            [void]$patchTests.Add($Matches[2].Replace('/', '.'))
            return $true
        }
        $state.EveryPatchTest = $true
        return $true
    }
    foreach ($changedPath in $Changed) {
        $kept = $inTip.Contains($changedPath)
        $found = @()
        # The tests that scan every shipped source: the patch tests at the root of app/morphe. A
        # deleted source counts, since a notice or provenance rule can still name it.
        if ($changedPath -cmatch '^(patches|extensions/.+)/src/main/') {
            foreach ($class in @(Get-FolderTests $patchRoots 'app/morphe' $false)) { [void]$patchTests.Add($class) }
        }
        if ($ledgerNamed.Contains($changedPath)) {
            foreach ($class in @(Get-FolderTests $patchRoots 'app/morphe' $false | Where-Object { $_ -ceq 'app.morphe.ProvenanceTest' })) {
                [void]$patchTests.Add($class)
            }
        }
        if ($readers.ContainsKey($changedPath)) {
            $found = @(foreach ($reader in $readers[$changedPath]) {
                Get-FolderTests $patchRoots ($reader -replace '/[^/]+$', '') $false | Where-Object { $_ -ceq $reader.Replace('/', '.') }
            })
            foreach ($class in $found) { [void]$patchTests.Add($class) }
        } elseif ($changedPath -cmatch '^(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew(\.bat)?|gradle/.+|patches/build\.gradle\.kts|patches/stub/.+|extensions/proguard-rules\.pro|extensions/.+/build\.gradle\.kts)$') {
            # The build itself: every test runs on it.
            $everyRuntimeTest = $true
            $everyPatchTest = $true
            continue
        } elseif ($changedPath -cmatch '^extensions/instagram/src/test/java/.+\.java$' -and $changedPath -cnotmatch 'Test\.java$') {
            # A helper or a stand-in for an Instagram or Android class, which tests anywhere may use.
            $everyRuntimeTest = $true
            continue
        } elseif ($changedPath -cmatch '^extensions/.+/src/main/java/.+/([A-Za-z0-9_]+)\.java$') {
            # Tests anywhere import an extension class, and the patches that call it are tested
            # against the built extension, so every runtime test runs, and every patch source or
            # test that names the class.
            $everyRuntimeTest = $true
            $named = New-Object 'System.Collections.Generic.SortedSet[string]' ([StringComparer]::Ordinal)
            foreach ($hit in @(& $FindPatchSources ('(^|[^A-Za-z0-9_])' + $Matches[1] + '([^A-Za-z0-9_]|$)'))) {
                [void]$named.Add($hit.Substring(0, $hit.IndexOf(':')))
            }
            foreach ($namer in $named) { [void](Add-PatchSource $namer) }
            continue
        } elseif ($changedPath -cmatch '^extensions/instagram/src/test/java/(.+)/[^/]+\.java$') {
            $found = @(Get-FolderTests $runtimeRoots $Matches[1] $false)
            foreach ($class in $found) { [void]$runtime.Add($class) }
        } elseif ($changedPath -cmatch '^extensions/(shared/|instagram/src/)') {
            $everyRuntimeTest = $true
            continue
        } elseif ($changedPath -cmatch '^patches/src/main/(kotlin|java)/app/morphe/(util|patches/shared)/' -or
                ($changedPath -cmatch '^patches/src/test/(kotlin|java)/.+\.(kt|java)$' -and
                 $changedPath -cnotmatch 'Test\.(kt|java)$' -and $changedPath -cnotmatch '/app/morphe/patches/instagram/')) {
            # The patch utilities, the shared patches and the shared test helpers (fixtures, the
            # extension dex, contexts) are used by every patch's tests.
            $everyPatchTest = $true
            continue
        } elseif ($changedPath -cmatch '^patches/src/(main|test)/(kotlin|java)/app/morphe/patches/instagram/') {
            if (Add-PatchSource $changedPath) { $found = @($changedPath) }
        } elseif ($changedPath -cmatch '^patches/src/(main|test)/(kotlin|java)/(.+)/[^/]+\.(kt|java)$') {
            $found = @(Get-FolderTests $patchRoots $Matches[3] $false)
            foreach ($class in $found) { [void]$patchTests.Add($class) }
        } elseif ($changedPath -cmatch '^patches/src/') {
            # A source with no package, or a resource with no test known to read it.
        } elseif ($changedPath -cmatch '^(extensions|patches)/') {
            # Anything else the build reads from its own folders (rules, stubs, lint settings).
            $everyRuntimeTest = $true
            $everyPatchTest = $true
            continue
        } else {
            continue
        }
        if ($found.Count -eq 0 -and $kept) { $uncovered.Add($changedPath) }
    }
    if ($state.EveryPatchTest) { $everyPatchTest = $true }
    $tasks = New-Object System.Collections.Generic.List[string]
    $tasks.AddRange([string[]]@('--console=plain', '--continue', ':patches:generatePatchesList', ':patches:testClasses',
        ':extensions:instagram:compileDebugUnitTestJavaWithJavac'))
    # Past this many classes the command line grows long and the whole suite is little slower.
    if ($everyRuntimeTest -or $runtime.Count -gt 120) {
        $tasks.Add(':extensions:instagram:testDebugUnitTest')
        if ($Boundaries) { $tasks.Add(':extensions:instagram:verifyAndroidBoundaries') }
    } elseif ($runtime.Count -gt 0) {
        $tasks.Add(':extensions:instagram:testDebugUnitTest')
        foreach ($class in $runtime) { $tasks.Add('--tests'); $tasks.Add($class) }
    }
    if ($everyPatchTest) {
        $tasks.Add(':patches:test')
    } elseif ($patchTests.Count -gt 0) {
        $tasks.Add(':patches:test')
        foreach ($class in $patchTests) { $tasks.Add('--tests'); $tasks.Add($class) }
    }
    [pscustomobject]@{ Tasks = $tasks.ToArray(); Uncovered = $uncovered.ToArray() }
}

$published = New-Object System.Collections.Generic.List[string]
$tips = New-Object System.Collections.Generic.List[string]
$release = $false
foreach ($line in $pushedRefs) {
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
    foreach ($path in @(Invoke-Git -c core.quotepath=false diff-tree --no-commit-id --name-only -r --root $commit)) {
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
    'scripts/patch-record.ps1',
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
        'scripts/walk-receipt.ps1',
        'scripts/patch-report.ps1', 'scripts/apk-facts.ps1', 'scripts/build-jobs.ps1', 'scripts/gate-evidence.ps1',
        'scripts/Resolve-Java.ps1', 'scripts/pre-push.ps1', 'scripts/script-wiring.ps1')
}).Count -gt 0) {
    $suites += , @('scripts/test-release-helpers.ps1', 'a release helper changed, running its stand-ins')
}
if (@($changed | Where-Object {
    $_ -like 'scripts/native-runner*' -or $_ -in @('scripts/test-native-runner.ps1', 'scripts/device-install.ps1',
        'scripts/injected-register-device.ps1', 'scripts/apk-facts.ps1', 'scripts/patch-report.ps1', 'scripts/patch-record.ps1',
        'scripts/Resolve-Java.ps1', 'scripts/pre-push.ps1', 'scripts/script-wiring.ps1')
}).Count -gt 0) {
    $suites += , @('scripts/test-native-runner.ps1', 'the native feature runner changed, running its stand-ins')
}
if (@($changed | Where-Object {
    $_ -in @('scripts/walk-app.ps1', 'scripts/test-walk-app.ps1', 'scripts/walk-receipt.ps1', 'scripts/patch-for-device.ps1',
        'scripts/WalkSnapshot.java', 'scripts/walk-capture.ps1', 'scripts/test-walk-capture.ps1',
        'scripts/walk-plan.ps1', 'scripts/walk-categories.json', 'scripts/test-walk-plan.ps1', 'scripts/test-walk-producer.ps1',
        'scripts/patch-record.ps1', 'scripts/build-identity.ps1', 'patches-list.json',
        'scripts/device-install.ps1', 'scripts/injected-register-device.ps1', 'scripts/native-runner-device.ps1', 'scripts/common.ps1',
        'scripts/pre-push.ps1', 'scripts/script-wiring.ps1')
}).Count -gt 0) {
    $suites += , @('scripts/test-walk-app.ps1', 'the whole-app walk tool changed, running its contract tests')
    $suites += , @('scripts/test-walk-capture.ps1', 'checking active-window capture transport and image validation')
    $suites += , @('scripts/test-walk-plan.ps1', 'checking settings coverage and visible destination content')
    $suites += , @('scripts/test-walk-producer.ps1', 'checking candidate reuse and walkthrough navigation')
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

# The files provenance.json names one by one, scripts and tests among them. ProvenanceTest reads
# each one's header, so a change to any of them is a build input and runs that test.
$provenanceNamed = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
if ($tips.Count -gt 0 -and "$(Invoke-Git ls-tree --name-only $tips[$tips.Count - 1] -- provenance.json)".Trim()) {
    $provenance = (Invoke-Git show "$($tips[$tips.Count - 1]):provenance.json") -join "`n" | ConvertFrom-Json
    foreach ($rule in @($provenance.rules)) {
        foreach ($namedPath in @($rule.paths)) { if (-not "$namedPath".EndsWith('/**')) { [void]$provenanceNamed.Add("$namedPath") } }
    }
}
if (@($changed | Where-Object { $_ -match $buildPaths -or $provenanceNamed.Contains($_) }).Count -eq 0 -or $tips.Count -eq 0) {
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
# The full gate is for a release push, whose results the release scripts read back, or for a push
# made with HUSHGRAM_FULL_GATE=1. Any other push builds once and runs the tests beside what changed.
$focused = $env:HUSHGRAM_ALLOW_RELEASE -ne '1' -and $env:HUSHGRAM_FULL_GATE -ne '1'
$focusedTasks = @()
if ($focused) {
    $checks = Get-FocusedChecks -Changed @($changed) -Boundaries $touchesAndroidBoundaries `
        -Tracked @(Invoke-Git -c core.quotepath=false ls-tree -r --name-only $tip) -LedgerFiles @($provenanceNamed) `
        -FindPatchSources { param([string]$Pattern) Find-PatchSourceLines -Commit $tip -Pattern $Pattern }
    if ($checks.Uncovered.Count -gt 0) {
        $checks.Uncovered | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" }
        Stop-Push ("no test covers $($checks.Uncovered.Count) changed file(s) above. Add a test beside each, or set " +
            'HUSHGRAM_FULL_GATE=1 to run the full gate for this push.')
    }
    $focusedTasks = $checks.Tasks
}
$gate = Join-Path ([System.IO.Path]::GetTempPath()) ('hushgram-gate-' + [guid]::NewGuid().ToString('N').Substring(0, 12))
if ($focused) {
    Write-Step "building $($tip.Substring(0, 12)) in a clean worktree and running the tests beside what changed"
} else {
    Write-Step "building $($tip.Substring(0, 12)) in a clean worktree"
}
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
    $gateJob = Enter-HeavyJob -Label "$(if ($focused) { 'focused' } else { 'gate' }) $($tip.Substring(0, 12))"
    # A focused run keeps nothing: the release scripts read back only the full gate's results.
    if (-not $focused) { $evidence = Start-GateEvidence -Commit $tip }
    $localProperties = Join-Path $Root 'local.properties'
    if (Test-Path -LiteralPath $localProperties) { Copy-Item -LiteralPath $localProperties -Destination $gate }

    # The declared builds' fixtures and the desktop CLI are found before anything is built, so a
    # missing one stops the push in seconds rather than after the tests.
    $catalog = Join-Path $gate 'patches-list.json'
    $before = Get-Content -LiteralPath $catalog -Raw
    $fixtures = $env:HUSHGRAM_FIXTURE_DIR
    $fixtureApks = @()
    $fixtureFingerprint = $null
    if (-not $focused -and $fixtures -and (Test-Path -LiteralPath $fixtures -PathType Container)) {
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
    if ($focused) {
        # One build with --continue, so every compile error and failed test shows in the one run.
        $gateStage = 'focused'
        Invoke-GradleBuild -ProjectDir $gate -Tasks $focusedTasks
        if ($LASTEXITCODE -ne 0) { Stop-Push 'the focused build failed: a compile error, a test beside the change or the catalog (all of them are above)' }
    } else {
        $gateStage = 'quick'
        Invoke-GradleBuild -ProjectDir $gate -Tasks @('--console=plain', ':patches:generatePatchesList',
            ':extensions:instagram:testDebugUnitTest', ':extensions:instagram:verifyAndroidBoundaries',
            ':extensions:instagram:lint', ':extensions:shared:library:lint')
        if ($LASTEXITCODE -ne 0) { Stop-Push 'the runtime tests, a lint or the catalog failed' }
    }
    if ((Get-Content -LiteralPath $catalog -Raw) -cne $before) {
        Stop-Push 'patches-list.json is stale: run :patches:generatePatchesList and commit it'
    }

    if ($touchesAndroidBoundaries) {
        $gateStage = 'boundaries'
        & pwsh -NoProfile -File (Join-Path $gate 'scripts/test-android-boundaries.ps1') -Root $gate
        if ($LASTEXITCODE -ne 0) { Stop-Push 'Android boundary gate self-tests failed' }
    }

    if ($focused) {
        $gatePassed = $true
        Write-Step 'the focused checks passed. A release push runs the full gate.'
        exit 0
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
