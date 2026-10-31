<#
.SYNOPSIS
    Exercises the release helpers in scripts/release: the notes builder, the test counter, the
    all-builds patch run, the preflight and the publish step's walk check.

.DESCRIPTION
    Nothing is built, patched or published. The JDK, aapt2, the desktop CLI, the build queue, the
    Gradle wrapper and gh are stand-ins that log what they were asked, the fixtures are small zip files carrying
    a manifest dump, and git runs in a throwaway repository in the temporary folder. The pre-push
    hook runs this suite when a release helper or a script they load changes.

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
$scratch = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('hushgram-release-helpers-' + [guid]::NewGuid().ToString('N'))))
$passed = 0
$variables = @('HUSHGRAM_FIXTURE_DIR', 'HUSHGRAM_DESKTOP_JAR', 'HUSHGRAM_WORKDIR', 'HUSHGRAM_JAVA', 'HUSHGRAM_AAPT2',
    'HUSHGRAM_ALLOW_RELEASE', 'HUSHGRAM_BUILD_WRAPPER', 'HUSHGRAM_GATE_CACHE', 'BUILD_QUEUE_SCRIPT', 'BUILD_QUEUE_PRIORITY',
    'BUILD_QUEUE_TICKET', 'HUSHGRAM_HELPERS_LOG', 'HUSHGRAM_HELPERS_STALE', 'HUSHGRAM_HELPERS_GRADLE_EXIT',
    'HUSHGRAM_HELPERS_NO_BUNDLE', 'HUSHGRAM_HELPERS_VERIFY_EXIT', 'PATH')
$saved = @{}
foreach ($name in $variables) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, [EnvironmentVariableTarget]::Process) }
# Run from the machine's queue, this process inherits that slot's ticket, and the all-builds checks
# would read it as a slot already held and never take the stand-in queue's.
Remove-Item Env:\BUILD_QUEUE_TICKET, Env:\BUILD_QUEUE_PRIORITY -ErrorAction SilentlyContinue

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:passed++
}

function Invoke-Python {
    <# A helper under py -3.13 -I, its output and exit code. #>
    $pythonArguments = @('-3.13', '-I') + $args
    $output = @(& py @pythonArguments 2>&1 | ForEach-Object { "$_" })
    return [pscustomobject]@{ Exit = $LASTEXITCODE; Output = $output -join "`n" }
}

function Invoke-Script {
    <# A script in a pwsh of its own, its output and exit code. #>
    param([string]$Path, [string[]]$Arguments = @())
    # Under Git's sh (TERM=xterm-256color, as in the pre-push hook) pwsh colors its error view, and the
    # escapes between a wrapped line's break and its "| " would keep the callers from joining the text.
    $output = @(& pwsh -NoProfile -File $Path @Arguments 2>&1 | ForEach-Object { "$_" -replace "`e\[[0-9;]*m", '' })
    return [pscustomobject]@{ Exit = $LASTEXITCODE; Output = $output -join "`n" }
}

function Write-Text([string]$Path, [string]$Text) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    [IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
function New-Zip([string]$Path, [System.Collections.Specialized.OrderedDictionary]$Entries) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $archive = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $Entries.Keys) {
            $writer = New-Object System.IO.StreamWriter($archive.CreateEntry($name).Open())
            try { $writer.Write($Entries[$name]) } finally { $writer.Dispose() }
        }
    } finally { $archive.Dispose() }
}

function Get-ManifestDump([string]$Version, [string]$Code) {
    # What aapt2 dump xmltree prints for the lines the scripts read.
    return (@(
        'N: android=http://schemas.android.com/apk/res/android (line=1)',
        '  E: manifest (line=1)',
        "    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=$Code",
        "    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)=`"$Version`" (Raw: `"$Version`")",
        '    A: package="com.instagram.android" (Raw: "com.instagram.android")',
        '      E: application (line=20)') -join "`n") + "`n"
}

try {
    New-Item -ItemType Directory -Path $scratch | Out-Null
    $log = Join-Path $scratch 'calls.log'
    $env:HUSHGRAM_HELPERS_LOG = $log
    function Read-Log { if (Test-Path -LiteralPath $log) { @(Get-Content -LiteralPath $log) } else { @() } }
    function Clear-Log { Remove-Item -LiteralPath $log -ErrorAction SilentlyContinue }
    $releaseDir = Join-Path $Root 'scripts/release'

    # --- release_notes.py ------------------------------------------------------------------------

    $notesDir = Join-Path $scratch 'notes'
    $changelog = Join-Path $notesDir 'CHANGELOG.md'
    $goodChangelog = "# Changelog`n`n## Unreleased`n`n### Improvements`n`n* **Tooling:** A script change.`n`n### Features`n`n* **Instagram:** A patch change.`n`n" +
        "## 0.0.2 (2026-10-01)`n`n* **Instagram:** A short summary.`n`n### Improvements`n`n* **Tooling:** The tooling line.`n`n### Features`n`n* **Instagram:** First patch line.`n`n" +
        "* **Instagram - Hide Meta AI:** Second patch line,`n  carried on.`n`n## 0.0.1 (2026-09-01)`n`n### Features`n`n* **Instagram:** The first release.`n"
    Write-Text $changelog $goodChangelog
    # The builder checks each scoped bullet's patch against the catalog beside the CHANGELOG.
    Write-Text (Join-Path $notesDir 'patches-list.json') ([IO.File]::ReadAllText((Join-Path $Root 'patches-list.json')))
    Write-Text (Join-Path $notesDir 'libs.versions.toml') "[versions]`nmanager-floor = `"9.9.9`"`n"
    Write-Text (Join-Path $notesDir 'validation.md') 'Validation: 5 runtime tests passed locally. All 6 patch tests passed too.'
    $notesOut = Join-Path $notesDir 'notes.md'
    $descriptionOut = Join-Path $notesDir 'description.json'
    $notesTool = Join-Path $releaseDir 'release_notes.py'
    function Invoke-Notes([string[]]$Extra) {
        Invoke-Python $notesTool '--changelog' $changelog @Extra
    }
    $build = @('--version', '0.0.2', '--validation', (Join-Path $notesDir 'validation.md'), '--out', $notesOut, '--description-out', $descriptionOut, '--catalog', (Join-Path $notesDir 'libs.versions.toml'))
    $run = Invoke-Notes $build
    $notes = if (Test-Path -LiteralPath $notesOut) { [IO.File]::ReadAllText($notesOut) } else { '' }
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*3 CHANGELOG bullets*') "The notes builder refused a clean section: $($run.Output)"
    $heading = '## [0.0.2](https://github.com/SysAdminDoc/HushGram/compare/v0.0.1...v0.0.2) (2026-10-01)'
    $target = (Get-Content -Raw (Join-Path $notesDir 'patches-list.json') | ConvertFrom-Json).patches.compatiblePackages.'com.instagram.android' |
        Sort-Object -Unique { [string]::Join('.', ($_ -split '\.' | ForEach-Object { $_.PadLeft(10, '0') })) } | Select-Object -Last 1
    $repo = 'https://github.com/SysAdminDoc/HushGram'
    $expected = (@('A short summary.', 'New in this release', '- First patch line.', '- `Hide Meta AI`: Second patch line, carried on.',
            'Behind the scenes', '- The tooling line.', "The [changelog]($repo/blob/main/CHANGELOG.md) has every earlier release too.", 'How to update',
            ("1. Update Morphe Manager to 9.9.9 or newer, then refresh the HushGram source.`n2. Patch Instagram ``$target``. [Supported Instagram builds]($repo#supported-instagram-builds) lists the builds that work.`n" +
            "3. Install over your current patched Instagram with the manager's existing signing key. Keep that key so updates keep working."),
            "The ``.mpp`` download is a patch bundle, not an Instagram APK. [Installation instructions]($repo#install).", 'What was checked',
            '5 runtime tests passed locally. All 6 patch tests passed too.') -join "`n`n") + "`n"
    Assert-True ($notes -ceq $expected) "The GitHub notes aren't Hushfacebook's layout with every bullet once: $notes"
    $description = if (Test-Path -LiteralPath $descriptionOut) { [IO.File]::ReadAllText($descriptionOut) | ConvertFrom-Json } else { '' }
    Assert-True ($description -ceq ("$heading`n`n### Features`n`n* **Instagram:** First patch line.`n* **Instagram - Hide Meta AI:** Second patch line, carried on.`n`n" +
            "### Improvements`n`n* **Tooling:** The tooling line.`n`n### Validation`n`nValidation: 5 runtime tests passed locally. All 6 patch tests passed too.")) `
        "The bundle description isn't the version heading, the typed sections and the validation line: $description"
    $run = Invoke-Notes @('--check')
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*Unreleased: 2 bullets (1 Features, 1 Improvements)*') "The Unreleased check didn't pass: $($run.Output)"
    foreach ($case in @(
            @{ Name = 'an unscoped bullet'; Text = $goodChangelog.Replace('* **Tooling:** The tooling line.', '* The tooling line.'); Said = '*has no scope*' },
            @{ Name = 'an unknown scope'; Text = $goodChangelog.Replace('**Tooling:** The tooling', '**Docs:** The tooling'); Said = '*unknown scope Docs*' },
            @{ Name = 'a bare patch name as scope'; Text = $goodChangelog.Replace('**Instagram - Hide Meta AI:**', '**Hide Meta AI:**'); Said = '*unknown scope Hide Meta AI*' },
            @{ Name = 'a bullet outside any type heading'; Text = $goodChangelog.Replace("### Improvements`n`n", ''); Said = '*outside a type sub-heading*' },
            @{ Name = 'an unknown sub-heading'; Text = $goodChangelog.Replace('### Improvements', '### Changes'); Said = '*unknown sub-heading*' },
            @{ Name = 'an em dash'; Text = $goodChangelog.Replace('First patch line.', "First patch line $([char]0x2014) with a dash."); Said = '*dash*' },
            @{ Name = 'a spaced hyphen'; Text = $goodChangelog.Replace('First patch line.', 'First patch line - with a dash.'); Said = '*dash*' })) {
        Write-Text $changelog $case.Text
        Remove-Item -LiteralPath $notesOut -ErrorAction SilentlyContinue
        $run = Invoke-Notes $build
        Assert-True ($run.Exit -eq 1 -and $run.Output -like $case.Said -and -not (Test-Path -LiteralPath $notesOut)) `
            "The notes builder went ahead with $($case.Name): $($run.Output)"
        $run = Invoke-Notes @('--check', '--version', '0.0.2')
        Assert-True ($run.Exit -eq 1 -and $run.Output -like $case.Said) "The check let through $($case.Name): $($run.Output)"
    }
    Write-Text $changelog $goodChangelog
    $run = Invoke-Notes @('--check', '--version', '0.0.9')
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*has no dated section for 0.0.9*') "A missing section passed the check: $($run.Output)"
    $run = Invoke-Notes @('--version', '0.0.2', '--out', $notesOut)
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*needs --validation*') "The notes builder went ahead without its pieces: $($run.Output)"
    Write-Text (Join-Path $notesDir 'validation.md') "Validation: 5 runtime tests passed locally.`nA second paragraph."
    $run = Invoke-Notes $build
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*wants the one sentence*') "A two-line validation went into the notes: $($run.Output)"
    Write-Text (Join-Path $notesDir 'validation.md') "Validation: 5 runtime tests passed locally $([char]0x2013) all of them."
    $run = Invoke-Notes $build
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*a dash*in the notes*') "A validation line with a dash went into the notes: $($run.Output)"
    $run = Invoke-Python (Join-Path $Root 'scripts/test-release-notes.py')
    Assert-True ($run.Exit -eq 0) "The release notes tests against Manager's parser failed: $($run.Output)"
    Write-Host '[release-helpers] notes builder passed'

    # --- count_tests.py --------------------------------------------------------------------------

    $countRoot = Join-Path $scratch 'count'
    function Write-Results([string]$Folder, [int]$Cases, [int]$Skipped = 0, [int]$Claimed = -1) {
        New-Item -ItemType Directory -Force -Path $Folder | Out-Null
        $body = (1..$Cases | ForEach-Object { "<testcase name=`"t$_`" classname=`"fixture.Suite`"/>" }) -join ''
        $claimedTests = if ($Claimed -ge 0) { $Claimed } else { $Cases }
        Set-Content -LiteralPath (Join-Path $Folder 'TEST-fixture.Suite.xml') -Encoding UTF8 -Value (
            "<?xml version=`"1.0`"?><testsuite name=`"fixture.Suite`" tests=`"$claimedTests`" failures=`"0`" errors=`"0`" skipped=`"$Skipped`">$body</testsuite>")
    }
    $countTool = Join-Path $releaseDir 'count_tests.py'
    Write-Results (Join-Path $countRoot 'extensions/instagram/build/test-results/testDebugUnitTest') 5 -Claimed 9
    Write-Results (Join-Path $countRoot 'patches/build/test-results/test/direct') 7
    $run = Invoke-Python $countTool '--root' $countRoot '--description'
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*Validation: 5 runtime tests passed locally. All 7 patch tests passed too.*') `
        "The counter didn't count test cases the way the release check does: $($run.Output)"
    # What the notes builder's --validation file holds is stdout, and it wants the one sentence.
    $sentenceLines = @(& py -3.13 -I $countTool --root $countRoot --description 2>$null | ForEach-Object { "$_" })
    Assert-True ($sentenceLines.Count -eq 1 -and $sentenceLines[0] -ceq 'Validation: 5 runtime tests passed locally. All 7 patch tests passed too.') `
        "--description's stdout isn't the one sentence: $($sentenceLines -join ' / ')"
    Write-Results (Join-Path $countRoot 'patches/build/test-results/test/direct') 7 -Skipped 1
    $run = Invoke-Python $countTool '--root' $countRoot
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*skipped=1*' -and $run.Output -like '*no release can quote it*') `
        "A run with a skip was counted as quotable: $($run.Output)"
    $run = Invoke-Python $countTool (Join-Path $scratch 'no-results')
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*holds no results*') "A folder with no results was counted: $($run.Output)"
    # The gate's kept results for a commit, and none from a gate that didn't pass, that names another
    # commit or tree, or that belongs to a commit git can't find.
    $gateCache = Join-Path $scratch 'gate-cache'
    $countRepo = Join-Path $scratch 'count-repo'
    New-Item -ItemType Directory -Force -Path $countRepo | Out-Null
    function Invoke-CountGit { $gitArguments = $args; $out = & git -C $countRepo @gitArguments 2>&1; if ($LASTEXITCODE -ne 0) { throw "git $gitArguments failed: $out" }; $out }
    Invoke-CountGit init -q -b main | Out-Null
    Write-Text (Join-Path $countRepo 'a.txt') 'one'
    Invoke-CountGit add -A | Out-Null
    Invoke-CountGit -c user.name=SysAdminDoc -c user.email=matt_parker@outlook.com commit -q -m one | Out-Null
    $countCommit = "$(Invoke-CountGit rev-parse HEAD)".Trim()
    $countTree = "$(Invoke-CountGit rev-parse 'HEAD^{tree}')".Trim()
    Write-Results (Join-Path $gateCache "$countCommit/test-results/testDebugUnitTest") 4
    Write-Results (Join-Path $gateCache "$countCommit/test-results/test/feed") 6
    $countManifest = Join-Path $gateCache "$countCommit/manifest.json"
    function Write-CountManifest([string]$Passed = 'true', [string]$Commit = $countCommit, [string]$Tree = $countTree) {
        Write-Text $countManifest "{`"passed`": $Passed, `"stage`": `"done`", `"commit`": `"$Commit`", `"tree`": `"$Tree`"}"
    }
    Write-CountManifest
    $env:HUSHGRAM_GATE_CACHE = $gateCache
    $run = Invoke-Python $countTool '--root' $countRepo '--gate' $countCommit '--description'
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*4 runtime tests passed locally. All 6 patch tests*') "The gate's kept results weren't counted: $($run.Output)"
    $run = Invoke-Python $countTool '--root' $countRepo '--gate' '--description'
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*4 runtime tests passed locally. All 6 patch tests*') "The gate's results for HEAD weren't counted: $($run.Output)"
    Write-CountManifest -Passed 'false'
    $run = Invoke-Python $countTool '--root' $countRepo '--gate' $countCommit
    Assert-True ($run.Exit -ne 0 -and $run.Output -like "*didn't pass (it stopped at done)*") "A failed gate's results were counted: $($run.Output)"
    Write-CountManifest -Commit ('c' * 40)
    $run = Invoke-Python $countTool '--root' $countRepo '--gate' $countCommit
    Assert-True ($run.Exit -ne 0 -and $run.Output -like "*names commit $('c' * 40)*") "A manifest naming another commit was counted: $($run.Output)"
    Write-CountManifest -Tree ('d' * 40)
    $run = Invoke-Python $countTool '--root' $countRepo '--gate' $countCommit
    Assert-True ($run.Exit -ne 0 -and $run.Output -like "*names tree $('d' * 40) and git says $countTree*") "A manifest naming another tree was counted: $($run.Output)"
    Write-CountManifest
    Write-Text (Join-Path $countRepo 'a.txt') 'two'
    Invoke-CountGit -c user.name=SysAdminDoc -c user.email=matt_parker@outlook.com commit -q -am two | Out-Null
    $run = Invoke-Python $countTool '--root' $countRepo '--gate'
    Assert-True ($run.Exit -ne 0 -and $run.Output -like '*kept no run of*') "A commit the gate never ran was counted: $($run.Output)"
    $run = Invoke-Python $countTool '--root' $countRepo '--gate' ('b' * 40)
    Assert-True ($run.Exit -ne 0 -and $run.Output -like "*git can't say what*") `
        "A commit git doesn't know was counted: $($run.Output)"
    Remove-Item Env:\HUSHGRAM_GATE_CACHE
    Write-Host '[release-helpers] test counter passed'

    # --- stale_tests.py --------------------------------------------------------------------------

    # A checkout with one boundary class, one settings test and the sources and table it reads: a
    # format string, two joined literals and a translated row.
    $settingsPackage = 'app/hushgram/extension/instagram/settings'
    function Write-StaleFixture([string]$At) {
        Write-Text (Join-Path $At 'extensions/instagram/build.gradle.kts') (@(
            'tasks.register("verifyAndroidBoundaries") {', '    doLast {', '        val required = mapOf(',
            '            "app.hushgram.extension.instagram.settings.FixtureTest" to listOf(',
            '                "keepsItsName[28]", "keepsItsName[37]"),', '        )', '        val factory = null', '    }', '}') -join "`n")
        Write-Text (Join-Path $At "extensions/instagram/src/test/java/$settingsPackage/FixtureTest.java") (@(
            'class FixtureTest {', '    @Test public void keepsItsName() {',
            '        assertEquals("Stop after 20 reels", label(20));',
            '        assertEquals("a message no source has", "Hide the " + "row", row());',
            '        assertTrue(summary().contains("Verstecke die Zeile"));',
            '        assertEquals("Hide the row: on (hushgram_hide_row=on)", report());',
            '        assertTrue(LicenseNotice.TEXT.contains("Keep this notice"));', '    }', '}') -join "`n")
        Write-Text (Join-Path $At "extensions/instagram/src/main/java/$settingsPackage/Fixture.java") (@(
            'class Fixture {', '    static final String ANY = "%s";', '    static final String LIMIT = "Stop after %1$d reels";',
            '    static final String ROW = "Hide the "', '            + "row";', '}') -join "`n")
        Write-Text (Join-Path $At 'extensions/shared/library/src/main/l10n/de.tsv') "Hide the line`tVerstecke die Zeile`n"
        Write-Text (Join-Path $At 'NOTICE') "Keep this notice`n"
    }
    $staleRoot = Join-Path $scratch 'stale'
    Write-StaleFixture $staleRoot
    $staleTool = Join-Path $releaseDir 'stale_tests.py'
    $run = Invoke-Python $staleTool '--root' $staleRoot
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*every boundary test and settings text is still in the sources*') `
        "The stale check refused a checkout whose tests match its sources: $($run.Output)"
    # The build turns NOTICE into LicenseNotice.TEXT, so a test may expect its text, and only while NOTICE has it.
    $staleNotice = Join-Path $staleRoot 'NOTICE'
    Write-Text $staleNotice "Some other notice`n"
    $run = Invoke-Python $staleTool '--root' $staleRoot
    Assert-True ($run.Exit -eq 1 -and $run.Output -like "*FixtureTest.java:7 expects text no source or table has: 'Keep this notice'*") `
        "A notice text NOTICE no longer has passed the stale check: $($run.Output)"
    Write-Text $staleNotice "Keep this notice`n"
    $staleTest =Join-Path $staleRoot "extensions/instagram/src/test/java/$settingsPackage/FixtureTest.java"
    $staleSource = Join-Path $staleRoot "extensions/instagram/src/main/java/$settingsPackage/Fixture.java"
    $testText = [IO.File]::ReadAllText($staleTest)
    $sourceText = [IO.File]::ReadAllText($staleSource)
    Write-Text $staleTest $testText.Replace('void keepsItsName()', 'void keptItsName()')
    $run = Invoke-Python $staleTool '--root' $staleRoot
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*boundary FixtureTest.keepsItsName names no test method*') `
        "A renamed boundary case passed the stale check: $($run.Output)"
    Write-Text $staleTest $testText.Replace('@Test public void keepsItsName()', 'public void keepsItsName()')
    $run = Invoke-Python $staleTool '--root' $staleRoot
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*boundary FixtureTest.keepsItsName names no test method*') `
        "A boundary name left on a method JUnit doesn't run passed the stale check: $($run.Output)"
    Write-Text $staleTest $testText
    # The bare "%s" in the source mustn't stand in for a rewritten text.
    Write-Text $staleSource $sourceText.Replace('Stop after %1$d reels', 'Stop after %1$d videos')
    $run = Invoke-Python $staleTool '--root' $staleRoot
    Assert-True ($run.Exit -eq 1 -and $run.Output -like "*FixtureTest.java:3 expects text no source or table has: 'Stop after 20 reels'*") `
        "A rewritten format string passed the stale check: $($run.Output)"
    Write-Text $staleSource $sourceText.Replace('+ "row"', '+ "line"')
    $run = Invoke-Python $staleTool '--root' $staleRoot
    Assert-True ($run.Exit -eq 1 -and $run.Output -like "*FixtureTest.java:4 expects text no source or table has: 'Hide the row'*" -and
        $run.Output -notlike '*a message no source has*') "A rewritten joined string passed, or an assert message was read as text: $($run.Output)"
    # A report line is read piece by piece, so the patch name still has to be in the sources.
    Assert-True ($run.Output -like "*FixtureTest.java:6 expects text no source or table has: 'Hide the row: on (hushgram_hide_row=on)'*") `
        "A report line whose patch name was rewritten passed the stale check: $($run.Output)"
    Write-Host '[release-helpers] stale test check passed'

    # --- patch-all-builds.ps1 --------------------------------------------------------------------

    # The checkout the run reads: the real catalog, a version, and a git repository so the gate's
    # kept run can stand for its HEAD.
    $repo = Join-Path $scratch 'repo'
    Write-Text (Join-Path $repo 'patches-list.json') ([IO.File]::ReadAllText((Join-Path $Root 'patches-list.json')))
    Write-Text (Join-Path $repo 'gradle.properties') "version = 0.0.1`n"
    Write-Text (Join-Path $repo 'CHANGELOG.md') $goodChangelog
    Write-Text (Join-Path $repo '.gitignore') "build/`npatches/build/`n"
    $catalog = Get-Content -LiteralPath (Join-Path $repo 'patches-list.json') -Raw | ConvertFrom-Json
    . (Join-Path $Root 'scripts/patch-target.ps1')
    # The release runs patch Instagram as Instagram: a patch that makes a second app is left out.
    $split = Split-SecondAppPatches -PatchList $catalog
    Assert-True (@($split.SecondApp).Count -eq 1 -and $split.SecondApp[0] -ceq 'Install next to Instagram' -and
        @($split.SameApp).Count -eq @($catalog.patches).Count - 1 -and $split.SameApp -cnotcontains 'Install next to Instagram') `
        "The second-app split is wrong: $($split | ConvertTo-Json -Compress)"
    $patchNames = @($split.SameApp)
    function Invoke-RepoGit {
        $gitArguments = $args
        & git -C $repo @gitArguments 2>&1
        if ($LASTEXITCODE -ne 0) { throw "git $gitArguments failed" }
    }
    Invoke-RepoGit init -q -b main | Out-Null
    Invoke-RepoGit config core.autocrlf false | Out-Null
    Invoke-RepoGit add -A | Out-Null
    Invoke-RepoGit -c user.name=SysAdminDoc -c user.email=matt_parker@outlook.com commit -q -m base | Out-Null
    $repoHead = "$(Invoke-RepoGit rev-parse HEAD)".Trim()

    # The fixture folder as the maintainer keeps it: a bundle and its base split for the declared
    # build, a base split alone for another arm64 build, an older bundle, a folder with nothing in
    # it and a file that isn't a build.
    $fixtures = Join-Path $scratch 'fixtures'
    # The declared build is the catalog's, so a retarget moves it here too. The other build shares its
    # version name under a code the catalog doesn't pin, as APKMirror's other arm64 builds do.
    $catalogTarget = Get-PatchTarget -PatchList $catalog
    $declared = [string]@($catalogTarget.PackageVersions)[0]
    $declaredCode = [string]@($catalogTarget.PackageVersionCodes[$declared])[0]
    Assert-True ($declaredCode -match '^\d+$') "The catalog pins no version code to $declared."
    $otherCode = [string]([long]$declaredCode + 5)
    $declaredBundle = Join-Path $fixtures "instagram-$declared-$declaredCode.apks"
    New-Zip $declaredBundle ([ordered]@{ 'base.apk' = (Get-ManifestDump $declared $declaredCode); 'split_config.arm64_v8a.apk' = 'native' })
    Write-Text (Join-Path $fixtures "instagram-$declared-$declaredCode/base.apk") (Get-ManifestDump $declared $declaredCode)
    $otherSplit = Join-Path $fixtures "instagram-$declared-$otherCode/base.apk"
    Write-Text $otherSplit (Get-ManifestDump $declared $otherCode)
    $olderBundle = Join-Path $fixtures 'instagram-439.0.0.37.89-384510833-apkpure.xapk'
    New-Zip $olderBundle ([ordered]@{ 'com.instagram.android.apk' = (Get-ManifestDump '439.0.0.37.89' '384510833') })
    New-Item -ItemType Directory -Force -Path (Join-Path $fixtures 'instagram-449.0.0.52.84-385511871-s22') | Out-Null
    Write-Text (Join-Path $fixtures 'notes.txt') 'not a build'

    # Stand-ins. The JDK answers -version and plays the desktop CLI: it logs each patch run, says a
    # WARNING on stderr, and writes a report with every patch applied, one failed on the other build,
    # and nothing at all on 439 (the run breaks). aapt2 prints the APK, which is a manifest dump.
    $tools = Join-Path $scratch 'tools'
    $javaStub = Join-Path $tools 'java-stub.ps1'
    Write-Text $javaStub (@'
if ($args[0] -eq '-version') { 'openjdk version "21.0.5" 2024-10-15'; exit 0 }
$values = @{}
for ($i = 0; $i -lt $args.Count - 1; $i++) { if ($args[$i] -in '-o', '-r') { $values[$args[$i]] = $args[$i + 1] } }
$apkIn = $args[$args.Count - 1]
$forced = [int]($args -contains '-f')
Add-Content -LiteralPath $env:HUSHGRAM_HELPERS_LOG -Value "patch $(Split-Path -Leaf (Split-Path -Parent $apkIn))/$(Split-Path -Leaf $apkIn) forced=$forced"
[Console]::Error.WriteLine("WARNING: a stand-in warning for $(Split-Path -Leaf $apkIn)")
if ($apkIn -like '*384510833*') { exit 3 }
$names = @()
for ($i = 0; $i -lt $args.Count - 1; $i++) { if ($args[$i] -eq '-e') { $names += $args[$i + 1] } }
$failed = @()
if ($apkIn -like '*OTHER_CODE*') { $failed = @(@{ patch = @{ name = $names[0] }; reason = "Could not find the target.`nat stand-in" }); $names = @($names | Select-Object -Skip 1) }
Set-Content -LiteralPath $values['-r'] -Value (@{ appliedPatches = @($names | ForEach-Object { @{ name = $_ } }); failedPatches = $failed } | ConvertTo-Json -Depth 5)
Set-Content -LiteralPath $values['-o'] -Value 'patched'
exit 0
'@).Replace('OTHER_CODE', $otherCode)
    $java = Join-Path $tools 'java.cmd'
    Write-Text $java "@pwsh -NoProfile -File `"%~dp0java-stub.ps1`" %*`r`n@exit /b %errorlevel%`r`n"
    $aapt2 = Join-Path $tools 'aapt2.cmd'
    Write-Text $aapt2 ("@echo off`r`nset `"APK=`"`r`n:next`r`nif `"%~1`"==`"`" goto run`r`nset `"APK=%~1`"`r`nshift`r`ngoto next`r`n" +
        ":run`r`ntype `"%APK%`"`r`nexit /b %errorlevel%`r`n")
    $desktop = Join-Path $tools 'morphe-desktop-stand-in.jar'
    Write-Text $desktop 'jar'
    $bundle = Join-Path $tools 'patches-0.0.1.mpp'
    Write-Text $bundle 'bundle'
    $queue = Join-Path $tools 'queue.ps1'
    Write-Text $queue @'
param([switch]$Status, [string]$Label, [string]$Priority, [string]$Run)
function Enter-BuildQueue {
    param([string]$Label, [string]$Priority)
    Add-Content -LiteralPath $env:HUSHGRAM_HELPERS_LOG -Value "queue enter $Label $Priority"
    $env:BUILD_QUEUE_TICKET = 'stand-in'
    [pscustomobject]@{ slot = 0; path = 'stand-in' }
}
function Exit-BuildQueue {
    param($Ticket)
    Add-Content -LiteralPath $env:HUSHGRAM_HELPERS_LOG -Value 'queue exit'
    Remove-Item Env:\BUILD_QUEUE_TICKET -ErrorAction SilentlyContinue
}
function Get-BuildQueueMask { param([int]$Slot) [System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity }
'@
    $allBuilds = Join-Path $releaseDir 'patch-all-builds.ps1'
    $out = Join-Path $scratch 'all-builds'
    $common = @('-FixtureDir', $fixtures, '-Root', $repo, '-Bundle', $bundle, '-DesktopJar', $desktop, '-Java', $java, '-Aapt2', $aapt2)
    function Invoke-AllBuilds([string[]]$Extra) {
        Clear-Log
        Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
        Invoke-Script $allBuilds (@($common) + @('-OutDir', $out) + @($Extra))
    }
    function Read-Summary { Get-Content -LiteralPath (Join-Path $out 'summary.json') -Raw | ConvertFrom-Json }

    # Left to itself it lists the declared version's builds only and names the older one it skips.
    $run = Invoke-AllBuilds @('-ListOnly')
    $listed = @($run.Output -split "`n" | Where-Object { $_ -like '`[all-builds`] *: *' })
    Assert-True ($run.Exit -eq 0 -and $listed.Count -eq 2 -and $run.Output -notlike '*384510833: bundle*' -and
        $run.Output -like "*skipped 439.0.0.37.89-384510833, not the declared version $declared (name it with -Only to patch it)*") `
        "An older version's build was taken, or its skip wasn't named: $($run.Output)"
    # Listed by name: the declared build from its bundle, not its base split; the other build from
    # its base split, the only copy; the older one from its bundle; nothing for the empty folder.
    $allThree = @('-Only', "${declaredCode},${otherCode},384510833")
    $run = Invoke-AllBuilds (@('-ListOnly') + $allThree)
    $listed = @($run.Output -split "`n" | Where-Object { $_ -like '`[all-builds`] *: *' })
    Assert-True ($run.Exit -eq 0 -and $listed.Count -eq 3 -and
        $run.Output -like "*$declared-${declaredCode}: bundle instagram-$declared-${declaredCode}.apks*" -and
        $run.Output -like "*$declared-${otherCode}: base split base.apk*" -and
        $run.Output -like '*439.0.0.37.89-384510833: bundle instagram-439.0.0.37.89-384510833-apkpure.xapk*' -and
        $run.Output -notlike '*385511871*' -and (Read-Log).Count -eq 0) "The builds weren't found the way they're kept: $($run.Output)"
    $run = Invoke-AllBuilds @('-ListOnly', '-Only', $otherCode)
    Assert-True ($run.Exit -eq 0 -and $run.Output -like "*${otherCode}*" -and $run.Output -notlike "*${declaredCode}*") "-Only didn't narrow the builds: $($run.Output)"

    # Patched: each build in a slot of its own at release priority, forced where the catalog
    # doesn't declare it, with what applied, what failed and the WARNING lines reported, and the
    # patched APKs gone. The broken run fails the whole.
    $env:BUILD_QUEUE_SCRIPT = $queue
    $env:HUSHGRAM_ALLOW_RELEASE = '1'
    $run = Invoke-AllBuilds $allThree
    $summary = Read-Summary
    $byBuild = @{}
    foreach ($entry in @($summary.builds)) { $byBuild[$entry.build] = $entry }
    $patchRuns = @(Read-Log | Where-Object { $_ -like 'patch *' })
    Assert-True ($run.Exit -eq 1 -and $patchRuns.Count -eq 3 -and
        $patchRuns -contains "patch fixtures/instagram-$declared-${declaredCode}.apks forced=0" -and
        $patchRuns -contains "patch instagram-$declared-${otherCode}/base.apk forced=1" -and
        $patchRuns -contains 'patch fixtures/instagram-439.0.0.37.89-384510833-apkpure.xapk forced=1') `
        "The builds weren't patched from the right inputs, forced where undeclared: $($patchRuns -join '; ') $($run.Output)"
    $queueLines = @(Read-Log | Where-Object { $_ -like 'queue *' })
    Assert-True ($queueLines.Count -eq 6 -and @($queueLines | Where-Object { $_ -like 'queue enter hushgram all-builds * release' }).Count -eq 3) `
        "Each build didn't take a release slot of its own: $($queueLines -join '; ')"
    $declaredEntry = $byBuild["$declared-${declaredCode}"]
    $splitEntry = $byBuild["$declared-${otherCode}"]
    $olderEntry = $byBuild['439.0.0.37.89-384510833']
    Assert-True ($declaredEntry.ok -and $declaredEntry.applied -eq $patchNames.Count -and -not $declaredEntry.forced -and
        $declaredEntry.kind -eq 'bundle' -and @($declaredEntry.warnings).Count -eq 1 -and $declaredEntry.warnings[0] -like 'WARNING: a stand-in warning*') `
        "The declared build's report is wrong: $($declaredEntry | ConvertTo-Json -Compress -Depth 4)"
    Assert-True (-not $splitEntry.ok -and -not $splitEntry.broken -and $splitEntry.forced -and $splitEntry.applied -eq ($patchNames.Count - 1) -and
        @($splitEntry.failed).Count -eq 1 -and $splitEntry.failed[0].name -eq $patchNames[0] -and $splitEntry.failed[0].reason -eq 'Could not find the target.' -and
        $splitEntry.kind -eq 'base split') "The other build's failed patch isn't reported: $($splitEntry | ConvertTo-Json -Compress -Depth 4)"
    Assert-True ($olderEntry.broken -and $olderEntry.cliExit -eq 3 -and $run.Output -like '*refused: 439.0.0.37.89-384510833 did not finish*' -and
        $run.Output -like "*$declared-${otherCode} ($declared ${otherCode}, forced, from its base split alone): $($patchNames.Count - 1)/$($patchNames.Count) applied, 1 failed, 1 WARNING lines*") `
        "The broken run didn't fail the whole, or the lines don't say what happened: $($run.Output)"
    Assert-True (@(Get-ChildItem -LiteralPath $out -Recurse -Filter 'patched.apk').Count -eq 0 -and
        @(Get-ChildItem -LiteralPath $out -Recurse -Directory -Filter 'work').Count -eq 0 -and
        (Test-Path -LiteralPath (Join-Path $out "$declared-${declaredCode}/result.json")) -and (Test-Path -LiteralPath (Join-Path $out "$declared-${declaredCode}/patch.log"))) `
        'The patched APKs or working folders were left behind, or the reports were not kept.'
    # A forced build missing a patch is reported without failing the run, unless -RequireAll.
    $run = Invoke-AllBuilds @('-Only', "${declaredCode},${otherCode}")
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*1 of 2 builds took every patch*') "A forced build's missing patch failed the run: $($run.Output)"
    $run = Invoke-AllBuilds @('-Only', "${declaredCode},${otherCode}", '-RequireAll')
    Assert-True ($run.Exit -eq 1 -and $run.Output -like "*refused: $declared-${otherCode} missed a patch*") "-RequireAll let a missing patch through: $($run.Output)"

    # -FromGate: the declared build the gate patched for HEAD with this bundle, catalog, CLI and APK
    # is read back from its kept report, and with another CLI it is patched again.
    . (Join-Path $Root 'scripts/gate-evidence.ps1')
    $env:HUSHGRAM_GATE_CACHE = Join-Path $scratch 'gate-runs'
    $gateTree = Join-Path $scratch 'gate-tree'
    # The gate builds in a worktree at the pushed commit, and the manifest names that tree.
    Invoke-RepoGit worktree add --detach $gateTree HEAD | Out-Null
    Write-Results (Join-Path $gateTree 'extensions/instagram/build/test-results/testDebugUnitTest') 3
    Write-Results (Join-Path $gateTree 'patches/build/test-results/test/stories') 2
    Write-Text (Join-Path $gateTree 'patches/build/release/patches-0.0.1.mpp') 'bundle'
    Write-Text (Join-Path $gateTree 'patches/build/release/patches-0.0.1.cdx.json') '{}'
    $gateDir = Start-GateEvidence -Commit $repoHead
    $keptResult = Join-Path $scratch 'kept-result.json'
    Write-Text $keptResult (@{ appliedPatches = @($patchNames | ForEach-Object { @{ name = $_ } }); failedPatches = @() } | ConvertTo-Json -Depth 4)
    Write-Text (Join-Path $scratch 'kept-patched.apk') 'patched'
    Write-GateKeptRun -KeepIn (Join-Path $gateDir "fixtures/$declared/kept") -PatchedApk (Join-Path $scratch 'kept-patched.apk') -Result $keptResult `
        -Apk $declaredBundle -Bundle $bundle -PatchList (Join-Path $repo 'patches-list.json') -DesktopJar $desktop `
        -VersionName $declared -VersionCode $declaredCode -Forced $false
    Save-GateEvidence -Directory $gateDir -GateRoot $gateTree -Commit $repoHead -Passed $true -Stage 'done' -FixturesPatched $true | Out-Null
    Assert-True (Test-Path -LiteralPath (Join-Path $gateDir 'test-results/test/stories/TEST-fixture.Suite.xml')) `
        'The gate did not preserve a nested patch test report.'
    $run = Invoke-AllBuilds @('-Only', $declaredCode, '-FromGate')
    $entry = @((Read-Summary).builds)[0]
    Assert-True ($run.Exit -eq 0 -and @(Read-Log | Where-Object { $_ -like 'patch *' }).Count -eq 0 -and $entry.ok -and
        $entry.source -eq "the gate's run of $($repoHead.Substring(0, 12))") "The gate's kept run of the declared build wasn't read: $($run.Output)"
    $otherDesktop = Join-Path $tools 'morphe-desktop-other.jar'
    Write-Text $otherDesktop 'another jar'
    $run = Invoke-Script $allBuilds (@('-FixtureDir', $fixtures, '-Root', $repo, '-Bundle', $bundle, '-DesktopJar', $otherDesktop, '-Java', $java,
        '-Aapt2', $aapt2, '-OutDir', $out, '-Only', $declaredCode, '-FromGate'))
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*made with another CLI, so it is patched here*' -and
        (Read-Log) -contains "patch fixtures/instagram-$declared-${declaredCode}.apks forced=0") "A kept run made with another CLI was read: $($run.Output)"
    Remove-Item Env:\BUILD_QUEUE_SCRIPT, Env:\HUSHGRAM_ALLOW_RELEASE, Env:\HUSHGRAM_GATE_CACHE
    Write-Host '[release-helpers] all-builds run passed'

    # --- preflight.ps1 ---------------------------------------------------------------------------

    # The checkout holds copies of the preflight, the notes builder and the scripts they load, a
    # release check and a patch run that log what they were asked, and a Gradle wrapper that logs
    # its tasks and leaves a bundle where buildAndroid would. A case can have the wrapper fail,
    # leave no bundle, or rewrite the catalog the way a stale one comes out, or the patch run fail.
    foreach ($name in @('common.ps1', 'patch-target.ps1', 'build-jobs.ps1', 'gate-evidence.ps1', 'instagram-sources.ps1')) {
        Write-Text (Join-Path $repo "scripts/$name") ([IO.File]::ReadAllText((Join-Path $Root "scripts/$name")))
    }
    foreach ($name in @('preflight.ps1', 'release_notes.py', 'stale_tests.py')) {
        Write-Text (Join-Path $repo "scripts/release/$name") ([IO.File]::ReadAllText((Join-Path $releaseDir $name)))
    }
    Write-StaleFixture $repo
    $censusLedger = Join-Path $repo 'sources/instagram-sources.json'
    Write-Text $censusLedger "{`"census`":{`"checkedAt`":`"$([datetime]::UtcNow.ToString('yyyy-MM-dd'))`"}}"
    Write-Text (Join-Path $repo 'scripts/validate-release-facts.ps1') @'
Add-Content -LiteralPath $env:HUSHGRAM_HELPERS_LOG -Value "facts $($args -join ' ')"
exit 0
'@
    Write-Text (Join-Path $repo 'scripts/verify-all-patches.ps1') @'
param([string]$Apk, [string]$DesktopJar, [string]$WorkDir, [string]$Bundle, [string]$PatchList)
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
Set-Content -LiteralPath (Join-Path $WorkDir 'patched.apk') -Value 'stand-in'
Add-Content -LiteralPath $env:HUSHGRAM_HELPERS_LOG -Value ("verify $(Split-Path -Leaf $Apk) $(Split-Path -Leaf $Bundle) " +
    "$(Split-Path -Leaf $PatchList) $(Split-Path -Leaf $DesktopJar) bundle=$(Test-Path -LiteralPath $Bundle) $WorkDir")
exit [int]$env:HUSHGRAM_HELPERS_VERIFY_EXIT
'@
    $wrapper = Join-Path $tools 'wrapper.ps1'
    Write-Text $wrapper @'
param([string]$ProjectDir, [string[]]$Tasks)
Add-Content -LiteralPath $env:HUSHGRAM_HELPERS_LOG -Value "gradle $($Tasks -join ' ')"
if ($env:HUSHGRAM_HELPERS_STALE) { Add-Content -LiteralPath (Join-Path $ProjectDir 'patches-list.json') -Value ' ' }
$bundle = Join-Path $ProjectDir 'patches/build/release/patches-0.0.1.mpp'
Remove-Item -LiteralPath $bundle -ErrorAction SilentlyContinue
if ($Tasks -contains ':patches:buildAndroid' -and -not $env:HUSHGRAM_HELPERS_NO_BUNDLE) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $bundle) | Out-Null
    Set-Content -LiteralPath $bundle -Value 'stand-in bundle'
}
exit [int]$env:HUSHGRAM_HELPERS_GRADLE_EXIT
'@
    Invoke-RepoGit add -A | Out-Null
    Invoke-RepoGit -c user.name=SysAdminDoc -c user.email=matt_parker@outlook.com commit -q -m 'release scripts' | Out-Null
    $preflightHead = "$(Invoke-RepoGit rev-parse HEAD)".Trim()
    $preflight = Join-Path $repo 'scripts/release/preflight.ps1'
    function Invoke-Preflight([string[]]$Extra = @(), [hashtable]$Environment = @{}) {
        $env:HUSHGRAM_FIXTURE_DIR = $fixtures
        $env:HUSHGRAM_DESKTOP_JAR = $desktop
        $env:HUSHGRAM_BUILD_WRAPPER = $wrapper
        $env:BUILD_QUEUE_SCRIPT = 'none'
        foreach ($name in $Environment.Keys) { Set-Item -LiteralPath "Env:\$name" -Value $Environment[$name] }
        Clear-Log
        $result = Invoke-Script $preflight $Extra
        foreach ($name in @('HUSHGRAM_FIXTURE_DIR', 'HUSHGRAM_DESKTOP_JAR', 'HUSHGRAM_BUILD_WRAPPER', 'BUILD_QUEUE_SCRIPT',
                'HUSHGRAM_HELPERS_STALE', 'HUSHGRAM_HELPERS_GRADLE_EXIT', 'HUSHGRAM_HELPERS_NO_BUNDLE', 'HUSHGRAM_HELPERS_VERIFY_EXIT')) {
            Remove-Item -LiteralPath "Env:\$name" -ErrorAction SilentlyContinue
        }
        return $result
    }

    $run = Invoke-Preflight
    $facts = @(Read-Log | Where-Object { $_ -like 'facts *' })
    $gradle = @(Read-Log | Where-Object { $_ -like 'gradle *' })
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*every check passed in*' -and $run.Output -like "*$declared`: instagram-$declared-${declaredCode}.apks*" -and
        $run.Output -like '*Unreleased: 2 bullets*') "The preflight refused a clean checkout: $($run.Output)"
    Assert-True ($facts.Count -eq 1 -and $facts[0] -like '*-SkipDescriptionTestCount*' -and $facts[0] -like '*-AllowPublishedIndexLag*' -and
        $facts[0] -like '*-SkipLocalBuild*' -and $facts[0] -notlike '*-VerifyPublishedAsset*') "The preflight didn't run the ordinary push's release check: $($facts -join '; ')"
    Assert-True ($gradle.Count -eq 1 -and $gradle[0] -like '*:patches:generatePatchesList :patches:test*' -and
        ([regex]::Matches($gradle[0], '--tests app\.morphe\.\w+Test')).Count -eq 9 -and $gradle[0] -like '*--tests app.morphe.ReadmePatchNamesTest*' -and
        $gradle[0] -like '* :patches:buildAndroid') `
        "The preflight's Gradle step isn't the catalog, the fixture-free patch tests and the bundle: $($gradle -join '; ')"
    # Then every patch runs on each declared build with that bundle, in a work folder it removes.
    $verify = @(Read-Log | Where-Object { $_ -like 'verify *' })
    $verifyDir = if ($verify.Count -eq 1) { $verify[0].Substring($verify[0].IndexOf(' bundle=') + ' bundle=True '.Length) } else { '' }
    Assert-True ($verify.Count -eq 1 -and $verify[0] -like ("verify instagram-$declared-${declaredCode}.apks patches-0.0.1.mpp patches-list.json " +
            "$(Split-Path -Leaf $desktop) bundle=True *") -and $verifyDir -and -not (Test-Path -LiteralPath $verifyDir) -and
        $run.Output -like '*every patch on the declared build*') `
        "The preflight didn't run every patch on the declared build with the new bundle and clean up after: $($verify -join '; ') $($run.Output)"
    $run = Invoke-Preflight @('-Version', '0.0.2', '-SkipGradle')
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*0.0.2: 3 bullets*' -and $run.Output -like '*the source census passed*' -and
        @(Read-Log | Where-Object { $_ -like 'gradle *' -or $_ -like 'verify *' }).Count -eq 0) `
        "-Version or -SkipGradle didn't do what it says: $($run.Output)"
    # A release hears about a stale census here, before the gate and the walk. Other runs aren't held to it.
    Write-Text $censusLedger '{"census":{"checkedAt":"2026-01-01"}}'
    Invoke-RepoGit -c user.name=SysAdminDoc -c user.email=matt_parker@outlook.com commit -q -am 'old census' | Out-Null
    $run = Invoke-Preflight @('-Version', '0.0.2')
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*refused after*the source census*days old*audit-instagram-sources.ps1*' -and
        @(Read-Log | Where-Object { $_ -like 'gradle *' -or $_ -like 'verify *' }).Count -eq 0) `
        "A release's preflight went on with a stale source census: $($run.Output)"
    $run = Invoke-Preflight @('-SkipGradle')
    Assert-True ($run.Exit -eq 0 -and $run.Output -notlike '*the source census*') "A preflight that isn't a release's read the census: $($run.Output)"
    Write-Text $censusLedger 'not a ledger'
    Invoke-RepoGit -c user.name=SysAdminDoc -c user.email=matt_parker@outlook.com commit -q -am 'broken ledger' | Out-Null
    $run = Invoke-Preflight @('-Version', '0.0.2', '-SkipGradle')
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*refused after*the source census*not JSON*') "A release's preflight read a broken ledger: $($run.Output)"
    Invoke-RepoGit reset -q --hard $preflightHead | Out-Null

    # Each refusal stops the run there, so nothing after it runs.
    $emptyFixtures = Join-Path $scratch 'empty-fixtures'
    New-Item -ItemType Directory -Force -Path $emptyFixtures | Out-Null
    $run = Invoke-Preflight @() @{ HUSHGRAM_FIXTURE_DIR = $emptyFixtures }
    Assert-True ($run.Exit -eq 1 -and $run.Output -like "*refused after*no fixture of the declared build $declared*" -and (Read-Log).Count -eq 0) `
        "The preflight went on without a fixture of the declared build: $($run.Output)"
    $run = Invoke-Preflight @() @{ HUSHGRAM_HELPERS_STALE = '1' }
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*patches-list.json is stale*') "A stale catalog passed the preflight: $($run.Output)"
    Invoke-RepoGit checkout -q -- patches-list.json | Out-Null
    $run = Invoke-Preflight @() @{ HUSHGRAM_HELPERS_GRADLE_EXIT = '1' }
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*Gradle exited 1*' -and @(Read-Log | Where-Object { $_ -like 'verify *' }).Count -eq 0) `
        "A failed Gradle step passed the preflight: $($run.Output)"
    $run = Invoke-Preflight @() @{ HUSHGRAM_HELPERS_NO_BUNDLE = '1' }
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*left no bundle*' -and @(Read-Log | Where-Object { $_ -like 'verify *' }).Count -eq 0) `
        "A Gradle step that left no bundle passed the preflight: $($run.Output)"
    $run = Invoke-Preflight @() @{ HUSHGRAM_HELPERS_VERIFY_EXIT = '1' }
    $verify = @(Read-Log | Where-Object { $_ -like 'verify *' })
    $verifyDir = if ($verify.Count -eq 1) { $verify[0].Substring($verify[0].IndexOf(' bundle=') + ' bundle=True '.Length) } else { '' }
    Assert-True ($run.Exit -eq 1 -and $run.Output -like "*verify-all-patches.ps1 exited 1 on $declared*" -and
        $verifyDir -and -not (Test-Path -LiteralPath $verifyDir)) `
        "A failed patch run passed the preflight or left its work folder: $($run.Output)"
    Write-Text (Join-Path $repo 'CHANGELOG.md') $goodChangelog.Replace('* **Tooling:** A script change.', '* A script change.')
    $run = Invoke-Preflight @('-SkipGradle')
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*uncommitted changes to tracked files*' -and (Read-Log).Count -eq 0) `
        "The preflight ran on a checkout with uncommitted changes: $($run.Output)"
    Invoke-RepoGit -c user.name=SysAdminDoc -c user.email=matt_parker@outlook.com commit -q -am 'unscoped bullet' | Out-Null
    $run = Invoke-Preflight @('-SkipGradle')
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*has no scope*' -and $run.Output -like '*refused after*the CHANGELOG section for the notes*') `
        "An unscoped CHANGELOG bullet passed the preflight: $($run.Output)"
    Invoke-RepoGit reset -q --hard $preflightHead | Out-Null
    $repoTest = Join-Path $repo "extensions/instagram/src/test/java/$settingsPackage/FixtureTest.java"
    Write-Text $repoTest ([IO.File]::ReadAllText($repoTest).Replace('void keepsItsName()', 'void keptItsName()'))
    Invoke-RepoGit -c user.name=SysAdminDoc -c user.email=matt_parker@outlook.com commit -q -am 'renamed boundary case' | Out-Null
    $run = Invoke-Preflight
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*keepsItsName names no test method*' -and
        $run.Output -like '*refused after*the test names and texts the sources still have*' -and
        @(Read-Log | Where-Object { $_ -like 'gradle *' }).Count -eq 0) "A renamed boundary case passed the preflight: $($run.Output)"
    Invoke-RepoGit reset -q --hard $preflightHead | Out-Null
    Write-Host '[release-helpers] preflight passed'

    # --- publish-release.ps1 ---------------------------------------------------------------------

    # Invalid legacy receipts go through the real publisher and receipt verifier. Only command
    # construction uses an isolated verified-result stand-in. test-walk-app covers real proof bytes.
    $publishRoot = Join-Path $scratch 'publish'
    $publishRelease = Join-Path $publishRoot 'patches/build/release'
    $publishBundle = Join-Path $publishRelease 'patches-0.0.1.mpp'
    $publishFiles = @($publishBundle, (Join-Path $publishRelease 'patches-0.0.1.cdx.json'),
        (Join-Path $publishRoot 'release-receipt-0.0.1.json'), (Join-Path $publishRelease 'SHA256SUMS.txt'))
    foreach ($file in $publishFiles) { Write-Text $file "stand-in $(Split-Path -Leaf $file)" }
    Write-Text (Join-Path $publishRoot 'patches-list.json') ([IO.File]::ReadAllText((Join-Path $Root 'patches-list.json')))
    Write-Text (Join-Path $publishRoot '.gitignore') "patches/build/`nrelease-receipt-*.json`n"
    & git -C $publishRoot init -q -b main 2>&1 | Out-Null
    & git -C $publishRoot add -A 2>&1 | Out-Null
    & git -C $publishRoot -c user.name=SysAdminDoc -c user.email=matt_parker@outlook.com commit -q -m base 2>&1 | Out-Null
    $publishHead = "$(& git -C $publishRoot rev-parse HEAD)".Trim()
    $publishNotes = Join-Path $scratch 'notes.md'
    Write-Text $publishNotes "## 0.0.1`n"
    $ghTools = Join-Path $scratch 'gh-tools'
    Write-Text (Join-Path $ghTools 'gh.ps1') "Add-Content -LiteralPath `$env:HUSHGRAM_HELPERS_LOG -Value ('gh ' + (`$args -join ' '))`nexit 0`n"
    $env:PATH = "$ghTools$([IO.Path]::PathSeparator)$env:PATH"
    $publisher = Join-Path $releaseDir 'publish-release.ps1'
    $publisherText = [IO.File]::ReadAllText($publisher)
    Assert-True ($publisherText.Contains(". (Join-Path `$scripts 'walk-receipt.ps1')") -and
        (Test-Path -LiteralPath (Join-Path $Root 'scripts/walk-receipt.ps1') -PathType Leaf)) 'The main publisher does not load the real receipt verifier.'
    $bundleHash = (Get-FileHash -LiteralPath $publishBundle -Algorithm SHA256).Hash.ToLowerInvariant()
    function Write-UnboundWalk([string]$Name, [hashtable]$Change = @{}) {
        $fields = @{ schema=2; result='PASS'; full=$true; serial='TESTPHONE01'; device='SM-S908U1'; emulator=$false
            runId=('a' * 32); appVersion=$declared; bundleSha256=$bundleHash; installedApkSha256=''
            started=[DateTimeOffset]::UtcNow.AddMinutes(-20).UtcDateTime.ToString('o')
            finished=[DateTimeOffset]::UtcNow.AddMinutes(-1).UtcDateTime.ToString('o'); screens=40; crashes=0; problems=@() }
        foreach ($key in $Change.Keys) { $fields[$key] = $Change[$key] }
        $path = Join-Path $scratch "walk-$Name.json"
        Write-Text $path (ConvertTo-Json -InputObject $fields -Depth 8)
        return $path
    }
    function Invoke-Publish([string[]]$Extra = @(), [string]$Script = $publisher) {
        Clear-Log
        $run = Invoke-Script $Script (@('-Version', '0.0.1', '-Notes', $publishNotes, '-Root', $publishRoot) + $Extra)
        # pwsh wraps a long error at the console width behind "| ", which would split the text matched.
        return [pscustomobject]@{ Exit = $run.Exit; Output = $run.Output -replace '\s*\n\s*\|\s*', ' ' }
    }

    $plainWalk = Join-Path $scratch 'walk-plaintext.json'
    Write-Text $plainWalk 'PASS, full walk, 40 screens, no crash'
    $fakePass = Write-UnboundWalk 'fake-pass'
    $env:HUSHGRAM_ALLOW_RELEASE = '1'
    $refusals = @(
        @{ Name = 'no walk'; Extra = @(); Pattern = '*-Walk names the walk-receipt.json*' },
        @{ Name = 'a missing receipt'; Extra = @('-Walk', (Join-Path $scratch 'no-walk.json')); Pattern = '*no-walk.json*' },
        @{ Name = 'plaintext PASS'; Extra = @('-Walk', $plainWalk); Pattern = '*evidence could not be verified*' },
        @{ Name = 'a legacy phone PASS'; Extra = @('-Walk', (Write-UnboundWalk 'legacy-phone' @{ schema=1 })); Pattern = '*needs schema 2 installation and destination evidence*' },
        @{ Name = 'a legacy emulator PASS'; Extra = @('-Walk', (Write-UnboundWalk 'legacy-emulator' @{ schema=1; serial='emulator-5554'; device='sdk_gphone64_x86_64'; emulator=$true })); Pattern = '*needs schema 2 installation and destination evidence*' },
        @{ Name = 'a partial walk'; Extra = @('-Walk', (Write-UnboundWalk 'partial' @{ result='PARTIAL'; full=$false })); Pattern = '*incomplete, has a crash or records a problem*' },
        @{ Name = 'a pass that skipped screens'; Extra = @('-Walk', (Write-UnboundWalk 'skipped' @{ full=$false })); Pattern = '*incomplete, has a crash or records a problem*' },
        @{ Name = 'a crash'; Extra = @('-Walk', (Write-UnboundWalk 'crash' @{ crashes=1 })); Pattern = '*incomplete, has a crash or records a problem*' },
        @{ Name = 'another phone'; Extra = @('-Walk', (Write-UnboundWalk 'phone' @{ device='SM-A515F' })); Pattern = '*device is not a release phone or emulator*' },
        @{ Name = 'a phone posing as an emulator'; Extra = @('-Walk', (Write-UnboundWalk 'posing' @{ device='sdk_gphone64_x86_64'; emulator=$true })); Pattern = '*device is not a release phone or emulator*' },
        @{ Name = 'an unbound schema 2 fake PASS'; Extra = @('-Walk', $fakePass); Pattern = '*no candidate artifact binding*' },
        @{ Name = 'an empty artifact binding'; Extra = @('-Walk', (Write-UnboundWalk 'empty-binding' @{ binding=@{schema=1;target=@{}}; artifacts=@{} })); Pattern = '*no exact artifact digest*' })
    foreach ($case in $refusals) {
        $run = Invoke-Publish $case.Extra
        Assert-True ($run.Exit -ne 0 -and $run.Output -like $case.Pattern -and $run.Output -like '*nothing was published*' -and
            @(Read-Log | Where-Object { $_ -like 'gh *' }).Count -eq 0) "Publish went ahead with $($case.Name): $($run.Output)"
    }
    Remove-Item Env:\HUSHGRAM_ALLOW_RELEASE -ErrorAction SilentlyContinue
    $run = Invoke-Publish @('-Walk', $fakePass)
    Assert-True ($run.Exit -ne 0 -and $run.Output -like '*no candidate artifact binding*' -and
        $run.Output -notlike '*needs explicit authorization*' -and @(Read-Log).Count -eq 0) 'The publisher checked authorization before rejecting invalid proof.'
    Remove-Item -LiteralPath $publishFiles[1]
    $run = Invoke-Publish @('-Walk', $fakePass)
    Assert-True ($run.Exit -ne 0 -and $run.Output -like '*patches-0.0.1.cdx.json is missing, so nothing was published*' -and
        @(Read-Log | Where-Object { $_ -like 'gh *' }).Count -eq 0) "Publish went ahead without the SBOM: $($run.Output)"
    Write-Text $publishFiles[1] 'stand-in patches-0.0.1.cdx.json'

    $isolatedScripts = Join-Path $scratch 'publisher-command/scripts'
    $isolatedPublisher = Join-Path $isolatedScripts 'release/publish-release.ps1'
    Write-Text $isolatedPublisher $publisherText
    $verifiedWalk = Join-Path $scratch 'walk-verified-command.json'
    Write-Text $verifiedWalk '{"fixture":"verified publisher command result"}'
    $expectedWalkCall = "walk Path=$verifiedWalk BundlePath=$publishBundle CatalogPath=$(Join-Path $publishRoot 'patches-list.json')"
    Write-Text (Join-Path $isolatedScripts 'expected-walk-parameters.json') (ConvertTo-Json -InputObject @{
        Path=$verifiedWalk; BundlePath=$publishBundle; CatalogPath=(Join-Path $publishRoot 'patches-list.json') })
    Write-Text (Join-Path $isolatedScripts 'walk-receipt.ps1') @'
function Test-WalkReceipt {
    param([Parameter(Mandatory=$true)][string]$Path, [Parameter(Mandatory=$true)][string]$BundlePath,
        [Parameter(Mandatory=$true)][string]$CatalogPath)
    $expected = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'expected-walk-parameters.json') -Raw | ConvertFrom-Json
    foreach ($name in @('Path','BundlePath','CatalogPath')) {
        if ($PSBoundParameters[$name] -cne $expected.$name) { throw "The publisher passed the wrong $name." }
    }
    Add-Content -LiteralPath $env:HUSHGRAM_HELPERS_LOG -Value "walk Path=$Path BundlePath=$BundlePath CatalogPath=$CatalogPath"
    [pscustomobject]@{ Valid=$true; Reason=$null; Receipt=[pscustomobject]@{device='SM-S908U1';screens=41} }
}
'@
    foreach ($authorization in @($null, '0', 'true', '1 ')) {
        [Environment]::SetEnvironmentVariable('HUSHGRAM_ALLOW_RELEASE', $authorization, 'Process')
        $run = Invoke-Publish -Extra @('-Walk', $verifiedWalk) -Script $isolatedPublisher
        Assert-True ($run.Exit -ne 0 -and $run.Output -like '*needs explicit authorization and HUSHGRAM_ALLOW_RELEASE=1*' -and
            @(Read-Log).Count -eq 1 -and @(Read-Log) -ccontains $expectedWalkCall -and
            @(Read-Log | Where-Object { $_ -like 'gh *' }).Count -eq 0) 'A verified receipt bypassed the explicit manual release hold.'
    }
    $expected = "gh release create v0.0.1 --repo SysAdminDoc/HushGram --target $publishHead --title v0.0.1 --notes-file $publishNotes $($publishFiles -join ' ')"
    $env:HUSHGRAM_ALLOW_RELEASE = '1'
    $run = Invoke-Publish -Extra @('-Walk', $verifiedWalk) -Script $isolatedPublisher
    Assert-True ($run.Exit -eq 0 -and @(Read-Log).Count -eq 2 -and @(Read-Log)[0] -ceq $expectedWalkCall -and
        @(Read-Log)[1] -ceq $expected -and $run.Output -like '*41 screens, no crash*') `
        "The authorized publisher did not verify the root catalog and construct the expected four-file command: $($run.Output) / $(Read-Log)"
    Write-Host '[release-helpers] publish passed'

    . (Join-Path $PSScriptRoot 'script-wiring.ps1')
    Assert-True (Test-PushGateRunsSuite (Join-Path $Root 'scripts/pre-push.ps1') 'scripts/test-release-helpers.ps1') `
        'The push gate does not run this suite when a release helper changes.'
    Write-Host "[release-helpers] $passed checks passed"
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], [EnvironmentVariableTarget]::Process) }
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $scratch.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe release helper fixture cleanup path.' }
    if (Get-Command Unlock-GateEvidence -ErrorAction SilentlyContinue) {
        foreach ($commit in @($script:GateEvidenceLocks.Keys)) { Unlock-GateEvidence -Commit $commit }
    }
    if (Test-Path -LiteralPath $scratch) { Remove-Item -LiteralPath $scratch -Recurse -Force }
}
