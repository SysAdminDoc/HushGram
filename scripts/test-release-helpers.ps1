<#
.SYNOPSIS
    Exercises the release helpers in scripts/release: the notes builder, the test counter, the
    all-builds patch run and the preflight.

.DESCRIPTION
    Nothing is built or patched. The JDK, aapt2, the desktop CLI, the build queue and the Gradle
    wrapper are stand-ins that log what they were asked, the fixtures are small zip files carrying
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
    'BUILD_QUEUE_TICKET', 'HUSHGRAM_HELPERS_LOG', 'HUSHGRAM_HELPERS_STALE', 'HUSHGRAM_HELPERS_GRADLE_EXIT')
$saved = @{}
foreach ($name in $variables) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, [EnvironmentVariableTarget]::Process) }

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
    $output = @(& pwsh -NoProfile -File $Path @Arguments 2>&1 | ForEach-Object { "$_" })
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
    $goodChangelog = "# Changelog`n`n## Unreleased`n`n* **Tooling:** A script change.`n`n* **Instagram:** A patch change.`n`n" +
        "## 0.0.2 (2026-10-01)`n`n* **Instagram:** First patch line.`n`n* **Tooling:** The tooling line.`n`n" +
        "* **Instagram:** Second patch line,`n  carried on.`n`n## 0.0.1 (2026-09-01)`n`n* **Instagram:** The first release.`n"
    Write-Text $changelog $goodChangelog
    $highlights = "* One highlight.`n* Another.`n* A third.`n* A fourth.`n`n* A fifth."
    foreach ($piece in @(@('intro.md', 'HushGram 0.0.2 is out.'), @('highlights.md', $highlights),
            @('install.md', "* Add the source.`n* Update."), @('validation.md', 'Every test passed.'))) {
        Write-Text (Join-Path $notesDir $piece[0]) $piece[1]
    }
    $notesOut = Join-Path $notesDir 'notes.md'
    $notesTool = Join-Path $releaseDir 'release_notes.py'
    function Invoke-Notes([string[]]$Extra) {
        Invoke-Python $notesTool '--changelog' $changelog @Extra
    }
    $build = @('--version', '0.0.2', '--intro', (Join-Path $notesDir 'intro.md'), '--highlights', (Join-Path $notesDir 'highlights.md'),
        '--install', (Join-Path $notesDir 'install.md'), '--validation', (Join-Path $notesDir 'validation.md'), '--out', $notesOut)
    $run = Invoke-Notes $build
    $notes = if (Test-Path -LiteralPath $notesOut) { [IO.File]::ReadAllText($notesOut) } else { '' }
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*3 CHANGELOG bullets*') "The notes builder refused a clean section: $($run.Output)"
    $instagramAt = $notes.IndexOf('## Instagram')
    Assert-True ($instagramAt -gt $notes.IndexOf("## What's new") -and $notes.IndexOf('## Tooling') -gt $instagramAt -and
        $notes.IndexOf('## Install or update') -gt $notes.IndexOf('## Tooling') -and $notes.StartsWith('HushGram 0.0.2 is out.') -and
        $notes.Contains("## What's new`n`n* One highlight.`n* Another.`n* A third.`n* A fourth.`n* A fifth.`n`n## Instagram") -and
        $notes.Contains("* First patch line.`n* Second patch line, carried on.") -and $notes.Contains('* The tooling line.') -and
        -not $notes.Contains('The first release') -and -not $notes.Contains('A script change')) `
        "The notes don't carry the section's bullets by scope, in order, and nothing else: $notes"
    $run = Invoke-Notes @('--check')
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*Unreleased: 2 bullets (1 Instagram, 1 Tooling)*') "The Unreleased check didn't pass: $($run.Output)"
    foreach ($case in @(
            @{ Name = 'an unscoped bullet'; Text = $goodChangelog.Replace('* **Tooling:** The tooling line.', '* The tooling line.'); Said = '*has no scope*' },
            @{ Name = 'an unknown scope'; Text = $goodChangelog.Replace('**Tooling:** The tooling', '**Docs:** The tooling'); Said = '*unknown scope Docs*' },
            @{ Name = 'an em dash'; Text = $goodChangelog.Replace('First patch line.', "First patch line $([char]0x2014) with a dash."); Said = '*dash*' },
            @{ Name = 'a spaced hyphen'; Text = $goodChangelog.Replace('First patch line.', 'First patch line - with a dash.'); Said = '*dash*' })) {
        Write-Text $changelog $case.Text
        Remove-Item -LiteralPath $notesOut -ErrorAction SilentlyContinue
        $run = Invoke-Notes $build
        Assert-True ($run.Exit -eq 1 -and $run.Output -like $case.Said -and -not (Test-Path -LiteralPath $notesOut)) `
            "The notes builder went ahead with $($case.Name): $($run.Output)"
    }
    Write-Text $changelog $goodChangelog
    $run = Invoke-Notes @('--check', '--version', '0.0.9')
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*has no dated section for 0.0.9*') "A missing section passed the check: $($run.Output)"
    $run = Invoke-Notes @('--version', '0.0.2', '--out', $notesOut)
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*missing --intro, --highlights, --install, --validation*') `
        "The notes builder went ahead without its pieces: $($run.Output)"
    Write-Text (Join-Path $notesDir 'intro.md') "HushGram 0.0.2 $([char]0x2013) out now."
    $run = Invoke-Notes $build
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*a dash*in the notes*') "An intro with a dash went into the notes: $($run.Output)"
    Write-Text (Join-Path $notesDir 'intro.md') 'HushGram 0.0.2 is out.'
    $five = "* One.`n* Two.`n* Three.`n* Four.`n* Five."
    foreach ($case in @(
            @{ Name = 'two highlights'; Text = "* One.`n* Two."; Said = '*has 2 lines, it wants 5 to 8*' },
            @{ Name = 'nine highlights'; Text = "$five`n* Six.`n* Seven.`n* Eight.`n* Nine."; Said = '*has 9 lines, it wants 5 to 8*' },
            @{ Name = 'a highlight that isn''t a bullet'; Text = "$five`nAnd a paragraph."; Said = "*isn't a bullet of its own*" },
            @{ Name = 'a long highlight'; Text = "$five`n* $('word ' * 40)"; Said = '*runs over 160 characters*' })) {
        Write-Text (Join-Path $notesDir 'highlights.md') $case.Text
        Remove-Item -LiteralPath $notesOut -ErrorAction SilentlyContinue
        $run = Invoke-Notes $build
        Assert-True ($run.Exit -eq 1 -and $run.Output -like $case.Said -and -not (Test-Path -LiteralPath $notesOut)) `
            "The notes builder went ahead with $($case.Name): $($run.Output)"
    }
    Write-Text (Join-Path $notesDir 'highlights.md') $highlights
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

    # --- patch-all-builds.ps1 --------------------------------------------------------------------

    # The checkout the run reads: the real catalog, a version, and a git repository so the gate's
    # kept run can stand for its HEAD.
    $repo = Join-Path $scratch 'repo'
    Write-Text (Join-Path $repo 'patches-list.json') ([IO.File]::ReadAllText((Join-Path $Root 'patches-list.json')))
    Write-Text (Join-Path $repo 'gradle.properties') "version = 0.0.1`n"
    Write-Text (Join-Path $repo 'CHANGELOG.md') $goodChangelog
    Write-Text (Join-Path $repo '.gitignore') "build/`npatches/build/`n"
    $catalog = Get-Content -LiteralPath (Join-Path $repo 'patches-list.json') -Raw | ConvertFrom-Json
    $patchNames = @($catalog.patches | ForEach-Object { [string]$_.name })
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
    $declared = '450.0.0.50.77'
    $declaredBundle = Join-Path $fixtures "instagram-$declared-385611438.apks"
    New-Zip $declaredBundle ([ordered]@{ 'base.apk' = (Get-ManifestDump $declared '385611438'); 'split_config.arm64_v8a.apk' = 'native' })
    Write-Text (Join-Path $fixtures "instagram-$declared-385611438/base.apk") (Get-ManifestDump $declared '385611438')
    $otherSplit = Join-Path $fixtures "instagram-$declared-385611395/base.apk"
    Write-Text $otherSplit (Get-ManifestDump $declared '385611395')
    $olderBundle = Join-Path $fixtures 'instagram-439.0.0.37.89-384510833-apkpure.xapk'
    New-Zip $olderBundle ([ordered]@{ 'com.instagram.android.apk' = (Get-ManifestDump '439.0.0.37.89' '384510833') })
    New-Item -ItemType Directory -Force -Path (Join-Path $fixtures 'instagram-449.0.0.52.84-385511871-s22') | Out-Null
    Write-Text (Join-Path $fixtures 'notes.txt') 'not a build'

    # Stand-ins. The JDK answers -version and plays the desktop CLI: it logs each patch run, says a
    # WARNING on stderr, and writes a report with every patch applied, one failed on 385611395,
    # and nothing at all on 439 (the run breaks). aapt2 prints the APK, which is a manifest dump.
    $tools = Join-Path $scratch 'tools'
    $javaStub = Join-Path $tools 'java-stub.ps1'
    Write-Text $javaStub @'
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
if ($apkIn -like '*385611395*') { $failed = @(@{ patch = @{ name = $names[0] }; reason = "Could not find the target.`nat stand-in" }); $names = @($names | Select-Object -Skip 1) }
Set-Content -LiteralPath $values['-r'] -Value (@{ appliedPatches = @($names | ForEach-Object { @{ name = $_ } }); failedPatches = $failed } | ConvertTo-Json -Depth 5)
Set-Content -LiteralPath $values['-o'] -Value 'patched'
exit 0
'@
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

    # Listed: the declared build from its bundle, not its base split; the other build from its
    # base split, the only copy; the older one from its bundle; nothing for the empty folder.
    $run = Invoke-AllBuilds @('-ListOnly')
    $listed = @($run.Output -split "`n" | Where-Object { $_ -like '`[all-builds`] *: *' })
    Assert-True ($run.Exit -eq 0 -and $listed.Count -eq 3 -and
        $run.Output -like "*$declared-385611438: bundle instagram-$declared-385611438.apks*" -and
        $run.Output -like "*$declared-385611395: base split base.apk*" -and
        $run.Output -like '*439.0.0.37.89-384510833: bundle instagram-439.0.0.37.89-384510833-apkpure.xapk*' -and
        $run.Output -notlike '*385511871*' -and (Read-Log).Count -eq 0) "The builds weren't found the way they're kept: $($run.Output)"
    $run = Invoke-AllBuilds @('-ListOnly', '-Only', '385611395')
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*385611395*' -and $run.Output -notlike '*385611438*') "-Only didn't narrow the builds: $($run.Output)"

    # Patched: each build in a slot of its own at release priority, forced where the catalog
    # doesn't declare it, with what applied, what failed and the WARNING lines reported, and the
    # patched APKs gone. The broken run fails the whole.
    $env:BUILD_QUEUE_SCRIPT = $queue
    $env:HUSHGRAM_ALLOW_RELEASE = '1'
    $run = Invoke-AllBuilds @()
    $summary = Read-Summary
    $byBuild = @{}
    foreach ($entry in @($summary.builds)) { $byBuild[$entry.build] = $entry }
    $patchRuns = @(Read-Log | Where-Object { $_ -like 'patch *' })
    Assert-True ($run.Exit -eq 1 -and $patchRuns.Count -eq 3 -and
        $patchRuns -contains "patch fixtures/instagram-$declared-385611438.apks forced=0" -and
        $patchRuns -contains "patch instagram-$declared-385611395/base.apk forced=1" -and
        $patchRuns -contains 'patch fixtures/instagram-439.0.0.37.89-384510833-apkpure.xapk forced=1') `
        "The builds weren't patched from the right inputs, forced where undeclared: $($patchRuns -join '; ') $($run.Output)"
    $queueLines = @(Read-Log | Where-Object { $_ -like 'queue *' })
    Assert-True ($queueLines.Count -eq 6 -and @($queueLines | Where-Object { $_ -like 'queue enter hushgram all-builds * release' }).Count -eq 3) `
        "Each build didn't take a release slot of its own: $($queueLines -join '; ')"
    $declaredEntry = $byBuild["$declared-385611438"]
    $splitEntry = $byBuild["$declared-385611395"]
    $olderEntry = $byBuild['439.0.0.37.89-384510833']
    Assert-True ($declaredEntry.ok -and $declaredEntry.applied -eq $patchNames.Count -and -not $declaredEntry.forced -and
        $declaredEntry.kind -eq 'bundle' -and @($declaredEntry.warnings).Count -eq 1 -and $declaredEntry.warnings[0] -like 'WARNING: a stand-in warning*') `
        "The declared build's report is wrong: $($declaredEntry | ConvertTo-Json -Compress -Depth 4)"
    Assert-True (-not $splitEntry.ok -and -not $splitEntry.broken -and $splitEntry.forced -and $splitEntry.applied -eq ($patchNames.Count - 1) -and
        @($splitEntry.failed).Count -eq 1 -and $splitEntry.failed[0].name -eq $patchNames[0] -and $splitEntry.failed[0].reason -eq 'Could not find the target.' -and
        $splitEntry.kind -eq 'base split') "The other build's failed patch isn't reported: $($splitEntry | ConvertTo-Json -Compress -Depth 4)"
    Assert-True ($olderEntry.broken -and $olderEntry.cliExit -eq 3 -and $run.Output -like '*refused: 439.0.0.37.89-384510833 did not finish*' -and
        $run.Output -like "*$declared-385611395 ($declared 385611395, forced, from its base split alone): $($patchNames.Count - 1)/$($patchNames.Count) applied, 1 failed, 1 WARNING lines*") `
        "The broken run didn't fail the whole, or the lines don't say what happened: $($run.Output)"
    Assert-True (@(Get-ChildItem -LiteralPath $out -Recurse -Filter 'patched.apk').Count -eq 0 -and
        @(Get-ChildItem -LiteralPath $out -Recurse -Directory -Filter 'work').Count -eq 0 -and
        (Test-Path -LiteralPath (Join-Path $out "$declared-385611438/result.json")) -and (Test-Path -LiteralPath (Join-Path $out "$declared-385611438/patch.log"))) `
        'The patched APKs or working folders were left behind, or the reports were not kept.'
    # A forced build missing a patch is reported without failing the run, unless -RequireAll.
    $run = Invoke-AllBuilds @('-Only', '385611438,385611395')
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*1 of 2 builds took every patch*') "A forced build's missing patch failed the run: $($run.Output)"
    $run = Invoke-AllBuilds @('-Only', '385611438,385611395', '-RequireAll')
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*refused: 450.0.0.50.77-385611395 missed a patch*') "-RequireAll let a missing patch through: $($run.Output)"

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
        -VersionName $declared -VersionCode '385611438' -Forced $false
    Save-GateEvidence -Directory $gateDir -GateRoot $gateTree -Commit $repoHead -Passed $true -Stage 'done' -FixturesPatched $true | Out-Null
    Assert-True (Test-Path -LiteralPath (Join-Path $gateDir 'test-results/test/stories/TEST-fixture.Suite.xml')) `
        'The gate did not preserve a nested patch test report.'
    $run = Invoke-AllBuilds @('-Only', '385611438', '-FromGate')
    $entry = @((Read-Summary).builds)[0]
    Assert-True ($run.Exit -eq 0 -and @(Read-Log | Where-Object { $_ -like 'patch *' }).Count -eq 0 -and $entry.ok -and
        $entry.source -eq "the gate's run of $($repoHead.Substring(0, 12))") "The gate's kept run of the declared build wasn't read: $($run.Output)"
    $otherDesktop = Join-Path $tools 'morphe-desktop-other.jar'
    Write-Text $otherDesktop 'another jar'
    $run = Invoke-Script $allBuilds (@('-FixtureDir', $fixtures, '-Root', $repo, '-Bundle', $bundle, '-DesktopJar', $otherDesktop, '-Java', $java,
        '-Aapt2', $aapt2, '-OutDir', $out, '-Only', '385611438', '-FromGate'))
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*made with another CLI, so it is patched here*' -and
        (Read-Log) -contains "patch fixtures/instagram-$declared-385611438.apks forced=0") "A kept run made with another CLI was read: $($run.Output)"
    Remove-Item Env:\BUILD_QUEUE_SCRIPT, Env:\HUSHGRAM_ALLOW_RELEASE, Env:\HUSHGRAM_GATE_CACHE
    Write-Host '[release-helpers] all-builds run passed'

    # --- preflight.ps1 ---------------------------------------------------------------------------

    # The checkout holds copies of the preflight, the notes builder and the scripts they load, a
    # release check that logs what it was asked, and a Gradle wrapper that logs its tasks. A case
    # can have the wrapper fail, or rewrite the catalog the way a stale one comes out.
    foreach ($name in @('common.ps1', 'patch-target.ps1', 'build-jobs.ps1', 'gate-evidence.ps1')) {
        Write-Text (Join-Path $repo "scripts/$name") ([IO.File]::ReadAllText((Join-Path $Root "scripts/$name")))
    }
    foreach ($name in @('preflight.ps1', 'release_notes.py')) {
        Write-Text (Join-Path $repo "scripts/release/$name") ([IO.File]::ReadAllText((Join-Path $releaseDir $name)))
    }
    Write-Text (Join-Path $repo 'scripts/validate-release-facts.ps1') @'
Add-Content -LiteralPath $env:HUSHGRAM_HELPERS_LOG -Value "facts $($args -join ' ')"
exit 0
'@
    $wrapper = Join-Path $tools 'wrapper.ps1'
    Write-Text $wrapper @'
param([string]$ProjectDir, [string[]]$Tasks)
Add-Content -LiteralPath $env:HUSHGRAM_HELPERS_LOG -Value "gradle $($Tasks -join ' ')"
if ($env:HUSHGRAM_HELPERS_STALE) { Add-Content -LiteralPath (Join-Path $ProjectDir 'patches-list.json') -Value ' ' }
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
                'HUSHGRAM_HELPERS_STALE', 'HUSHGRAM_HELPERS_GRADLE_EXIT')) { Remove-Item -LiteralPath "Env:\$name" -ErrorAction SilentlyContinue }
        return $result
    }

    $run = Invoke-Preflight
    $facts = @(Read-Log | Where-Object { $_ -like 'facts *' })
    $gradle = @(Read-Log | Where-Object { $_ -like 'gradle *' })
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*every check passed in*' -and $run.Output -like "*$declared`: instagram-$declared-385611438.apks*" -and
        $run.Output -like '*Unreleased: 2 bullets*') "The preflight refused a clean checkout: $($run.Output)"
    Assert-True ($facts.Count -eq 1 -and $facts[0] -like '*-SkipDescriptionTestCount*' -and $facts[0] -like '*-AllowPublishedIndexLag*' -and
        $facts[0] -like '*-SkipLocalBuild*' -and $facts[0] -notlike '*-VerifyPublishedAsset*') "The preflight didn't run the ordinary push's release check: $($facts -join '; ')"
    Assert-True ($gradle.Count -eq 1 -and $gradle[0] -like '*:patches:generatePatchesList :patches:test*' -and
        ([regex]::Matches($gradle[0], '--tests app\.morphe\.\w+Test')).Count -eq 9 -and $gradle[0] -like '*--tests app.morphe.ReadmePatchNamesTest*') `
        "The preflight's Gradle step isn't the catalog and the fixture-free patch tests: $($gradle -join '; ')"
    $run = Invoke-Preflight @('-Version', '0.0.2', '-SkipGradle')
    Assert-True ($run.Exit -eq 0 -and $run.Output -like '*0.0.2: 3 bullets*' -and @(Read-Log | Where-Object { $_ -like 'gradle *' }).Count -eq 0) `
        "-Version or -SkipGradle didn't do what it says: $($run.Output)"

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
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*Gradle exited 1*') "A failed Gradle step passed the preflight: $($run.Output)"
    Write-Text (Join-Path $repo 'CHANGELOG.md') $goodChangelog.Replace('* **Tooling:** A script change.', '* A script change.')
    $run = Invoke-Preflight @('-SkipGradle')
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*uncommitted changes to tracked files*' -and (Read-Log).Count -eq 0) `
        "The preflight ran on a checkout with uncommitted changes: $($run.Output)"
    Invoke-RepoGit -c user.name=SysAdminDoc -c user.email=matt_parker@outlook.com commit -q -am 'unscoped bullet' | Out-Null
    $run = Invoke-Preflight @('-SkipGradle')
    Assert-True ($run.Exit -eq 1 -and $run.Output -like '*has no scope*' -and $run.Output -like '*refused after*the CHANGELOG section for the notes*') `
        "An unscoped CHANGELOG bullet passed the preflight: $($run.Output)"
    Invoke-RepoGit reset -q --hard $preflightHead | Out-Null
    Write-Host '[release-helpers] preflight passed'

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
