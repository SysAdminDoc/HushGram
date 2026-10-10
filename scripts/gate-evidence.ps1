<#
.SYNOPSIS
    What a pre-push gate run keeps when it ends, and how the release scripts find and trust it.

.DESCRIPTION
    Dot-source this after common.ps1:

        . (Join-Path $PSScriptRoot 'common.ps1')
        . (Join-Path $PSScriptRoot 'gate-evidence.ps1')

    The gate builds and tests a pushed commit in a worktree of its own and deletes it when it ends.
    Before that, it copies what a release needs out of it into a folder named for the commit, under
    HUSHGRAM_GATE_CACHE, or HushGram\gate in the local application data folder when that's unset:

        <commit>\test-results\testDebugUnitTest   the runtime tests' JUnit XML
        <commit>\test-results\test                the patch tests' JUnit XML
        <commit>\release                          the bundle, its SBOM and bundle.sha256
        <commit>\fixtures\<version>\reports       the reports verify-all-patches.ps1 wrote
        <commit>\fixtures\<version>\kept          the passing run of that build, stamped
        <commit>\receipt                          the receipt and SHA256SUMS.txt, once one is cut
        <commit>\manifest.json                    written last, so a copy cut short is never read

    The manifest names the commit, the tree git built it from, the bundle and SBOM hashes, the test
    counts, how far the gate got and whether it passed. A run is used only when all of it holds
    up again when it's read: the commit is the checkout's HEAD (or HEAD is an index commit over it,
    see Test-IndexOnlyChange), git gives the same tree, the copied files hash to what the manifest
    says, and the test counts read back the same. Anything else and the caller does the work
    itself, as it did before there was anything to reuse.

    The newest three runs are kept and older ones are deleted when a gate ends. A run holds a
    patched and a merged Instagram APK for each declared build, about half a gigabyte.

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>

# The files an index commit may change, and what each changed line has to be. The release facts
# check holds every one of them to the published release, and no test reads these lines.
$script:GateIndexFiles = @('patches-bundle.json', 'README.md', '.github/ISSUE_TEMPLATE/bug_report.yml')
$script:GateIndexLines = @{
    'README.md' = '^The latest release is \[v\d+\.\d+\.\d+\]\('
    '.github/ISSUE_TEMPLATE/bug_report.yml' = '^\s*placeholder:\s*HushGram \d+\.\d+\.\d+\b'
}
$script:GateEvidenceKeep = 3

function Get-EvidenceHash {
    <# A file's SHA-256 in upper-case hex, the form Get-Sha256Hex in release-receipt.ps1 gives. #>
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Cannot hash a file that is not there: $Path" }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Get-Sha256OfText {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Text))).Replace('-', '') }
    finally { $sha.Dispose() }
}

function Get-GateLogicHash {
    <#
    The SHA-256 of the gate logic that makes and reads a run: pre-push.ps1 and this file, as they
    are in this checkout. A manifest stamped with another value was made by a gate that decided
    differently, so it isn't taken for this one's word. $null when either file isn't there.
    #>
    $parts = @()
    foreach ($name in @('pre-push.ps1', 'gate-evidence.ps1')) {
        $path = Join-Path $PSScriptRoot $name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
        $parts += Get-EvidenceHash -Path $path
    }
    return Get-Sha256OfText -Text ($parts -join ':')
}

function Get-FixtureFingerprint {
    <#
    .SYNOPSIS
        A fingerprint of everything :patches:test can read from the fixture folder.
    .DESCRIPTION
        patches/build.gradle.kts makes all of HUSHGRAM_FIXTURE_DIR an input to the patch tests, so a
        build added or replaced there changes what a gate would test. The fingerprint covers the
        top-level files and each folder's base.apk: sorted relative path, size and last-write time.
        Size plus time rather than a content hash, because a push would otherwise read several
        hundred megabytes of APKs to decide whether to skip a build, and the APK the gate patches
        is content-hashed in its stamp anyway.
    #>
    param([Parameter(Mandatory = $true)][string]$Folder)
    $root = (Resolve-Path -LiteralPath $Folder).ProviderPath.TrimEnd('\', '/')
    $files = @(Get-ChildItem -LiteralPath $root -File) +
        @(Get-ChildItem -LiteralPath $root -Directory | ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Filter 'base.apk' -File })
    $lines = [string[]]@($files | ForEach-Object {
        '{0}|{1}|{2}' -f $_.FullName.Substring($root.Length + 1).Replace('\', '/'), $_.Length, $_.LastWriteTimeUtc.Ticks
    })
    [Array]::Sort($lines, [StringComparer]::Ordinal)
    return Get-Sha256OfText -Text ($lines -join "`n")
}

function Get-GateEvidenceRoot {
    <# The folder the gate keeps its runs in. #>
    $configured = [Environment]::GetEnvironmentVariable('HUSHGRAM_GATE_CACHE', [EnvironmentVariableTarget]::Process)
    if (-not $configured -and ($IsWindows -or $env:OS -eq 'Windows_NT')) {
        $configured = [Environment]::GetEnvironmentVariable('HUSHGRAM_GATE_CACHE', [EnvironmentVariableTarget]::User)
    }
    if ($configured) { return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($configured) }
    $base = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    if (-not $base) { $base = Join-Path $HOME '.cache' }
    return Join-Path (Join-Path $base 'HushGram') 'gate'
}

function Get-GateEvidenceDirectory {
    param([Parameter(Mandatory = $true)][string]$Commit)
    if ($Commit -notmatch '^[0-9a-f]{40}$') { throw "Not a full commit hash: $Commit" }
    return Join-Path (Get-GateEvidenceRoot) $Commit
}

function Invoke-EvidenceGit {
    <#
    git against -Root with no inherited GIT_* variables: a hook runs with GIT_DIR set, and a
    -C path alone doesn't override it. Exit codes are left in $LASTEXITCODE.
    #>
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string[]]$Arguments)
    $saved = @{}
    foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
        $saved[$variable.Name] = $variable.Value
        Remove-Item -LiteralPath ('Env:\' + $variable.Name)
    }
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $gitArguments = @('-C', $Root) + $Arguments
        if (Get-Command Use-Utf8ConsoleOutput -ErrorAction SilentlyContinue) {
            Use-Utf8ConsoleOutput { & git @gitArguments 2>$null }
        } else {
            & git @gitArguments 2>$null
        }
    } finally {
        $ErrorActionPreference = $preference
        foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
    }
}

function Get-CommitTree {
    <# The tree git records for -Commit, or $null when it can't say. #>
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)][string]$Commit)
    # Collected before the first line is taken: a Select-Object -First right after git stops the
    # pipeline before git's exit code is set, and a fresh pwsh then reads $LASTEXITCODE as $null.
    $answer = @(Invoke-EvidenceGit -Root $Root -Arguments @('rev-parse', '--verify', '--quiet', "$Commit^{tree}"))
    $tree = "$($answer | Select-Object -First 1)".Trim()
    if ($LASTEXITCODE -ne 0 -or $tree -notmatch '^[0-9a-f]{40}$') { return $null }
    return $tree
}

function Get-JUnitCounts {
    <#
    .SYNOPSIS
        The test cases, failures, errors and skips in the TEST-*.xml files below one folder.
    .DESCRIPTION
        Test cases are counted the way validate-release-facts.ps1 counts them, one per testcase
        element, so a manifest's count is the number a release description quotes.
    #>
    param([Parameter(Mandatory = $true)][string]$Directory)
    $counts = [ordered]@{ files = 0; tests = 0; failures = 0; errors = 0; skipped = 0 }
    if (Test-Path -LiteralPath $Directory -PathType Container) {
        foreach ($file in @(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml' -File -Recurse)) {
            $document = [xml](Get-Content -LiteralPath $file.FullName -Raw)
            foreach ($suite in @($document.testsuite)) {
                $counts.failures += [int]$suite.failures
                $counts.errors += [int]$suite.errors
                $counts.skipped += [int]$suite.skipped
            }
            $counts.tests += @($document.testsuite.testcase).Count
            $counts.files++
        }
    }
    return [pscustomobject]$counts
}

$script:GateEvidenceLocks = @{}

function Lock-GateEvidence {
    <# Holds the commit's lock file open and exclusive, or throws when another gate has it. #>
    param([Parameter(Mandatory = $true)][string]$Commit)
    Unlock-GateEvidence -Commit $Commit
    $root = Get-GateEvidenceRoot
    New-Item -ItemType Directory -Force -Path $root | Out-Null
    $path = Join-Path $root "$Commit.lock"
    try { $stream = [System.IO.File]::Open($path, [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None) }
    catch { throw "Another gate is keeping a run of $Commit right now ($path is locked), so this one can't replace it. Push again when that gate ends." }
    $script:GateEvidenceLocks[$Commit] = [pscustomobject]@{ Stream = $stream; Path = $path }
}

function Unlock-GateEvidence {
    param([Parameter(Mandatory = $true)][string]$Commit)
    $held = $script:GateEvidenceLocks[$Commit]
    if (-not $held) { return }
    $script:GateEvidenceLocks.Remove($Commit)
    $held.Stream.Dispose()
    Remove-Item -LiteralPath $held.Path -Force -ErrorAction SilentlyContinue
}

function Start-GateEvidence {
    <#
    .SYNOPSIS
        The empty folder a gate of -Commit keeps its run in.
    .DESCRIPTION
        A gate of the same commit replaces what an earlier one kept. The manifest goes first, so
        nothing reads the old run's word for the new one while it's being replaced. Two gates of
        one commit (two worktrees) share the folder, so the second one stops here while the first
        holds the commit's lock, and Save-GateEvidence lets it go.
    #>
    param([Parameter(Mandatory = $true)][string]$Commit)
    $directory = Get-GateEvidenceDirectory -Commit $Commit
    Lock-GateEvidence -Commit $Commit
    if (Test-Path -LiteralPath $directory) {
        Remove-Item -LiteralPath (Join-Path $directory 'manifest.json') -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $directory -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    return $directory
}

function Copy-EvidenceFiles {
    param([string]$From, [string]$To, [string]$Filter)
    if (-not (Test-Path -LiteralPath $From -PathType Container)) { return 0 }
    $files = @(Get-ChildItem -LiteralPath $From -Filter $Filter -File -Recurse)
    if ($files.Count -eq 0) { return 0 }
    foreach ($file in $files) {
        $relative = [System.IO.Path]::GetRelativePath($From, $file.FullName)
        $destination = Join-Path $To $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $destination -Force
    }
    return $files.Count
}

function Save-GateEvidence {
    <#
    .SYNOPSIS
        Copies a gate's outputs out of its worktree and writes the manifest, pass or fail.
    .PARAMETER FixtureRuns
        One entry per declared build the gate patched: Version, Apk (the fixture) and WorkDir (where
        verify-all-patches.ps1 wrote its reports). Its kept run is already in fixtures\<Version>\kept.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$GateRoot,
        [Parameter(Mandatory = $true)][string]$Commit,
        [Parameter(Mandatory = $true)][bool]$Passed,
        [Parameter(Mandatory = $true)][string]$Stage,
        [string]$Reason,
        [object[]]$FixtureRuns = @(),
        [bool]$FixturesPatched,
        [string]$FixtureFingerprint
    )
    try {
    $runtimeDir = Join-Path $Directory 'test-results/testDebugUnitTest'
    $patchDir = Join-Path $Directory 'test-results/test'
    Copy-EvidenceFiles -From (Join-Path $GateRoot 'extensions/instagram/build/test-results/testDebugUnitTest') -To $runtimeDir -Filter '*.xml' | Out-Null
    Copy-EvidenceFiles -From (Join-Path $GateRoot 'patches/build/test-results/test') -To $patchDir -Filter '*.xml' | Out-Null

    $bundleFacts = $null
    $releaseFrom = Join-Path $GateRoot 'patches/build/release'
    $bundle = if (Test-Path -LiteralPath $releaseFrom) {
        Get-ChildItem -LiteralPath $releaseFrom -Filter 'patches-*.mpp' -File | Select-Object -First 1
    }
    if ($bundle) {
        $releaseTo = Join-Path $Directory 'release'
        New-Item -ItemType Directory -Force -Path $releaseTo | Out-Null
        $sbom = [System.IO.Path]::ChangeExtension($bundle.FullName, '.cdx.json')
        foreach ($file in @($bundle.FullName, $sbom, (Join-Path $releaseFrom 'bundle.sha256'))) {
            if (Test-Path -LiteralPath $file -PathType Leaf) { Copy-Item -LiteralPath $file -Destination $releaseTo -Force }
        }
        $keptSbom = Join-Path $releaseTo (Split-Path -Leaf $sbom)
        $bundleFacts = [ordered]@{
            file       = $bundle.Name
            sha256     = Get-EvidenceHash -Path (Join-Path $releaseTo $bundle.Name)
            sbom       = if (Test-Path -LiteralPath $keptSbom) { Split-Path -Leaf $sbom } else { $null }
            sbomSha256 = if (Test-Path -LiteralPath $keptSbom) { Get-EvidenceHash -Path $keptSbom } else { $null }
        }
    }

    $fixtures = foreach ($run in @($FixtureRuns | Where-Object { $_ })) {
        $versionDir = Join-Path $Directory "fixtures/$($run.Version)"
        $reports = 0
        foreach ($filter in @('*.json', '*.txt')) {
            $reports += Copy-EvidenceFiles -From $run.WorkDir -To (Join-Path $versionDir 'reports') -Filter $filter
        }
        $stamp = Join-Path $versionDir 'kept/stamp.json'
        [ordered]@{
            version = [string]$run.Version
            apk     = Split-Path -Leaf ([string]$run.Apk)
            reports = $reports
            kept    = Test-Path -LiteralPath $stamp -PathType Leaf
        }
    }

    $manifest = [ordered]@{
        schemaVersion   = 1
        commit          = $Commit
        tree            = Get-CommitTree -Root $GateRoot -Commit $Commit
        createdUtc      = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        logicSha256     = Get-GateLogicHash
        fixtureFingerprint = if ($FixtureFingerprint) { $FixtureFingerprint } else { $null }
        passed          = $Passed
        stage           = $Stage
        reason          = if ($Reason) { $Reason } else { $null }
        bundle          = $bundleFacts
        tests           = [ordered]@{ runtime = Get-JUnitCounts -Directory $runtimeDir; patches = Get-JUnitCounts -Directory $patchDir }
        fixturesPatched = $FixturesPatched
        fixtures        = @($fixtures)
    }
    $manifestPath = Join-Path $Directory 'manifest.json'
    $temporary = "$manifestPath.$PID.tmp"
    [System.IO.File]::WriteAllText($temporary, ($manifest | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $manifestPath -Force
    Remove-StaleGateEvidence
    return $manifestPath
    } finally { Unlock-GateEvidence -Commit $Commit }
}

function Remove-StaleGateEvidence {
    <#
    Keeps the newest runs and deletes the rest, failed ones first, so a release's passed run
    outlives a busy drain of failing pushes. A folder with no manifest is a gate still running
    or one that was cut short; it goes only once it's two days old.
    #>
    $root = Get-GateEvidenceRoot
    if (-not (Test-Path -LiteralPath $root -PathType Container)) { return }
    $runs = @(Get-ChildItem -LiteralPath $root -Directory | Where-Object { $_.Name -match '^[0-9a-f]{40}$' })
    $finished = @(foreach ($run in $runs) {
        $manifestFile = Join-Path $run.FullName 'manifest.json'
        if (-not (Test-Path -LiteralPath $manifestFile -PathType Leaf)) { continue }
        $passed = $false
        try { $passed = (Get-Content -LiteralPath $manifestFile -Raw | ConvertFrom-Json).passed -eq $true } catch { $passed = $false }
        [pscustomobject]@{ Run = $run; Passed = $passed; Written = (Get-Item -LiteralPath $manifestFile).LastWriteTimeUtc }
    })
    $excess = $finished.Count - $script:GateEvidenceKeep
    $stale = @(if ($excess -gt 0) {
        $finished | Sort-Object @{ Expression = 'Passed' }, @{ Expression = 'Written' } | Select-Object -First $excess | ForEach-Object { $_.Run }
    }) + @($runs | Where-Object { -not (Test-Path -LiteralPath (Join-Path $_.FullName 'manifest.json')) -and
            $_.LastWriteTimeUtc -lt [DateTime]::UtcNow.AddDays(-2) })
    foreach ($run in $stale) {
        try { Remove-Item -LiteralPath $run.FullName -Recurse -Force -ErrorAction Stop }
        catch { Write-Warning "Could not remove the old gate run $($run.FullName): $($_.Exception.Message)" }
    }
}

function Test-GateEvidence {
    <#
    .SYNOPSIS
        Why the kept run in -Directory can't stand for -Commit, or $null when it can.
    .DESCRIPTION
        Everything the manifest says is read again: the commit and its tree from git, the bundle
        and SBOM by hash, and the test counts out of the XML copied beside it.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$Commit,
        [Parameter(Mandatory = $true)][string]$Root
    )
    $manifestPath = Join-Path $Directory 'manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { return 'it has no manifest' }
    try { $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json }
    catch { return "its manifest doesn't parse: $($_.Exception.Message)" }
    if ([int]$manifest.schemaVersion -ne 1) { return "its manifest is schema $($manifest.schemaVersion), not 1" }
    if ([string]$manifest.commit -cne $Commit) { return "its manifest names commit $($manifest.commit)" }
    if ($manifest.passed -ne $true) { return "that gate didn't pass (it stopped at $($manifest.stage): $($manifest.reason))" }
    $tree = Get-CommitTree -Root $Root -Commit $Commit
    if (-not $tree -or [string]$manifest.tree -cne $tree) { return "its manifest names tree $($manifest.tree) and git says $tree" }
    $logic = Get-GateLogicHash
    if (-not $logic -or [string]$manifest.logicSha256 -cne $logic) {
        return "it was made by other gate logic than this checkout's (pre-push.ps1 or gate-evidence.ps1)"
    }
    if (-not $manifest.bundle -or -not $manifest.bundle.file) { return 'it kept no bundle' }
    $bundle = Join-Path $Directory "release/$($manifest.bundle.file)"
    if (-not (Test-Path -LiteralPath $bundle -PathType Leaf) -or (Get-EvidenceHash -Path $bundle) -cne [string]$manifest.bundle.sha256) {
        return "its bundle isn't the $($manifest.bundle.file) the manifest hashes"
    }
    if (-not $manifest.bundle.sbom) { return 'it kept no SBOM' }
    $sbom = Join-Path $Directory "release/$($manifest.bundle.sbom)"
    if (-not (Test-Path -LiteralPath $sbom -PathType Leaf) -or (Get-EvidenceHash -Path $sbom) -cne [string]$manifest.bundle.sbomSha256) {
        return "its SBOM isn't the $($manifest.bundle.sbom) the manifest hashes"
    }
    foreach ($kind in @(@{ Name = 'runtime'; Folder = 'testDebugUnitTest' }, @{ Name = 'patches'; Folder = 'test' })) {
        $said = $manifest.tests.($kind.Name)
        $read = Get-JUnitCounts -Directory (Join-Path $Directory "test-results/$($kind.Folder)")
        if ($read.files -eq 0) { return "it kept no $($kind.Name) test results" }
        foreach ($field in @('files', 'tests', 'failures', 'errors', 'skipped')) {
            if ([int]$said.$field -ne [int]$read.$field) {
                return "its $($kind.Name) test results read $($read.$field) $field, and the manifest says $($said.$field)"
            }
        }
    }
    return $null
}

function Test-IndexOnlyChange {
    <#
    .SYNOPSIS
        Whether -To differs from -From only where an index commit may: patches-bundle.json, the
        README's latest release sentence and the bug form's HushGram version placeholders.
    .DESCRIPTION
        The README and bug form lines have to be those lines before and after, the same once
        every version and count in them is set aside. A README edit anywhere else, a placeholder
        reworded, or any other file is a change the gate has to see. -From has to be an ancestor.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$From,
        [Parameter(Mandatory = $true)][string]$To
    )
    $fail = { param([string]$Why) [pscustomobject]@{ Valid = $false; Reason = $Why } }
    Invoke-EvidenceGit -Root $Root -Arguments @('merge-base', '--is-ancestor', $From, $To) | Out-Null
    if ($LASTEXITCODE -ne 0) { return & $fail "$($From.Substring(0, 12)) isn't an ancestor of $($To.Substring(0, 12))" }
    $paths = @(Invoke-EvidenceGit -Root $Root -Arguments @('-c', 'core.quotepath=false', 'diff', '--name-only', '--no-renames', $From, $To) |
        Where-Object { "$_".Trim() } | ForEach-Object { "$_".Trim() })
    if ($LASTEXITCODE -ne 0) { return & $fail 'git could not compare them' }
    $other = @($paths | Where-Object { $script:GateIndexFiles -cnotcontains $_ })
    if ($other.Count -gt 0) { return & $fail "$($other[0]) changed too" }
    foreach ($path in @($paths | Where-Object { $script:GateIndexLines.ContainsKey($_) })) {
        $diff = @(Invoke-EvidenceGit -Root $Root -Arguments @('diff', '-U0', '--no-color', '--no-ext-diff', $From, $To, '--', $path))
        $removed = @($diff | Where-Object { $_ -like '-*' -and $_ -notlike '---*' } | ForEach-Object { $_.Substring(1).TrimEnd("`r") })
        $added = @($diff | Where-Object { $_ -like '+*' -and $_ -notlike '+++*' } | ForEach-Object { $_.Substring(1).TrimEnd("`r") })
        foreach ($line in @($removed) + @($added)) {
            if ($line -notmatch $script:GateIndexLines[$path]) { return & $fail "a line of $path other than the index's changed: $line" }
        }
        $shape = { param([string[]]$Lines) (@($Lines | ForEach-Object { $_ -replace '\d+(?:\.\d+)*', '#' } | Sort-Object) -join "`n") }
        if ((& $shape $removed) -cne (& $shape $added)) {
            return & $fail "$path changed more than the versions and counts on its index lines"
        }
    }
    return [pscustomobject]@{ Valid = $true; Reason = $null }
}

function Find-GateEvidence {
    <#
    .SYNOPSIS
        The kept gate run that stands for this checkout's HEAD, or $null, saying why either way.
    .DESCRIPTION
        HEAD's own run, or with -AllowIndexCommits the nearest run on HEAD's first-parent line
        (ten commits back at most) when everything since it is an index change. The nearest kept
        run decides: one that doesn't hold up stops the search rather than handing over an older
        one. A checkout with uncommitted changes to tracked files has no run standing for it.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [switch]$AllowIndexCommits,
        [string]$Prefix = '[gate]'
    )
    # Collected first for the same reason as in Get-CommitTree.
    $answer = @(Invoke-EvidenceGit -Root $Root -Arguments @('rev-parse', 'HEAD'))
    $head = "$($answer | Select-Object -First 1)".Trim()
    if ($LASTEXITCODE -ne 0 -or $head -notmatch '^[0-9a-f]{40}$') { Write-Host "$Prefix git could not say what HEAD is, so no gate run is read"; return $null }
    $dirty = @(Invoke-EvidenceGit -Root $Root -Arguments @('status', '--porcelain', '--untracked-files=no') | Where-Object { "$_".Trim() })
    if ($dirty.Count -gt 0) {
        Write-Host "$Prefix the checkout has uncommitted changes ($("$($dirty[0])".Trim())), so no gate run stands for it"
        return $null
    }
    $candidates = if ($AllowIndexCommits) {
        @(Invoke-EvidenceGit -Root $Root -Arguments @('rev-list', '--first-parent', '--max-count=11', 'HEAD') | ForEach-Object { "$_".Trim() } | Where-Object { $_ })
    } else { @($head) }
    foreach ($candidate in $candidates) {
        $directory = Get-GateEvidenceDirectory -Commit $candidate
        if (-not (Test-Path -LiteralPath (Join-Path $directory 'manifest.json') -PathType Leaf)) { continue }
        $problem = Test-GateEvidence -Directory $directory -Commit $candidate -Root $Root
        if (-not $problem -and $candidate -ne $head) {
            $index = Test-IndexOnlyChange -Root $Root -From $candidate -To $head
            if (-not $index.Valid) { $problem = "HEAD isn't only an index change over it: $($index.Reason)" }
        }
        if ($problem) {
            Write-Host "$Prefix the gate's run of $($candidate.Substring(0, 12)) isn't used: $problem"
            return $null
        }
        $manifest = Get-Content -LiteralPath (Join-Path $directory 'manifest.json') -Raw | ConvertFrom-Json
        $over = if ($candidate -ne $head) { ", and HEAD $($head.Substring(0, 12)) changes only the index over it" } else { '' }
        # PowerShell 7 reads the ISO time back as a date; 5.1 leaves it a string.
        $created = $manifest.createdUtc
        if ($created -is [DateTime]) { $created = $created.ToUniversalTime().ToString('yyyy-MM-dd HH:mm') + ' UTC' }
        Write-Host ("$Prefix reading the gate's run of $($candidate.Substring(0, 12)) from $created " +
            "($($manifest.tests.runtime.tests) runtime and $($manifest.tests.patches.tests) patch tests, $($manifest.bundle.file))$over")
        return [pscustomobject]@{
            Directory = $directory
            Manifest  = $manifest
            Commit    = $candidate
            Head      = $head
            IndexOnly = $candidate -ne $head
            Bundle    = Join-Path $directory "release/$($manifest.bundle.file)"
            Sbom      = Join-Path $directory "release/$($manifest.bundle.sbom)"
        }
    }
    Write-Host "$Prefix no gate run is kept for $($head.Substring(0, 12)) in $(Get-GateEvidenceRoot)"
    return $null
}

function Get-GateKeptRun {
    <#
    .SYNOPSIS
        The gate's kept patch run of one build, when it was made with this bundle, APK, patch list
        and CLI and forced or not the same way, or $null with -Why saying what differs.
    .DESCRIPTION
        The kept run is the patched APK, the CLI's result report and, for a split bundle, the
        merge the CLI patched, written by verify-all-patches.ps1 -KeepIn with a stamp of what
        made them. The stamp is written last, so a keep cut short is never read.
    #>
    param(
        [Parameter(Mandatory = $true)]$Evidence,
        [Parameter(Mandatory = $true)][string]$VersionName,
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$BundleSha256,
        [Parameter(Mandatory = $true)][string]$PatchList,
        [Parameter(Mandatory = $true)][string]$DesktopJar,
        [Parameter(Mandatory = $true)][bool]$Forced,
        [ref]$Why
    )
    $kept = Join-Path $Evidence.Directory "fixtures/$VersionName/kept"
    $stampPath = Join-Path $kept 'stamp.json'
    $say = { param([string]$Text) if ($Why) { $Why.Value = $Text } }
    if (-not (Test-Path -LiteralPath $stampPath -PathType Leaf)) { & $say "the gate kept no run of $VersionName"; return $null }
    try { $stamp = Get-Content -LiteralPath $stampPath -Raw | ConvertFrom-Json }
    catch { & $say "the stamp of the gate's run of $VersionName doesn't parse"; return $null }
    $checks = [ordered]@{
        'bundle'     = [string]$stamp.bundleSha256 -ceq $BundleSha256
        'APK'        = [string]$stamp.apkSha256 -ceq (Get-EvidenceHash -Path $Apk)
        'patch list' = [string]$stamp.patchListSha256 -ceq (Get-EvidenceHash -Path $PatchList)
        'CLI'        = [string]$stamp.desktopJarSha256 -ceq (Get-EvidenceHash -Path $DesktopJar)
        'forcing'    = [bool]$stamp.forced -eq $Forced
    }
    $differs = @($checks.Keys | Where-Object { -not $checks[$_] })
    if ($differs.Count -gt 0) { & $say "the gate's run of $VersionName was made with another $($differs -join ', ')"; return $null }
    $patched = Join-Path $kept 'patched.apk'
    $result = Join-Path $kept 'result.json'
    $merged = if ($stamp.merged) { Join-Path $kept 'stock-merged.apk' } else { $null }
    foreach ($file in @($patched, $result, $merged) | Where-Object { $_ }) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { & $say "the gate's run of $VersionName is missing $(Split-Path -Leaf $file)"; return $null }
    }
    $stamped = [ordered]@{ 'patched.apk' = @($patched, $stamp.patchedSha256); 'result.json' = @($result, $stamp.resultSha256) }
    if ($merged) { $stamped['stock-merged.apk'] = @($merged, $stamp.mergedSha256) }
    foreach ($name in $stamped.Keys) {
        if ([string]$stamped[$name][1] -cne (Get-EvidenceHash -Path $stamped[$name][0])) {
            & $say "the $name of the gate's run of $VersionName isn't the one it stamped"
            return $null
        }
    }
    return [pscustomobject]@{ Directory = $kept; PatchedApk = $patched; Result = $result; MergedApk = $merged; Stamp = $stamp }
}

function Get-GateRunGap {
    <#
    .SYNOPSIS
        What a gate started here now would patch that the kept run didn't, or $null when it
        covers all of it.
    .DESCRIPTION
        -Fixtures are the declared builds a gate here would patch now, each with its Version and
        the Apk it would take, and none when HUSHGRAM_FIXTURE_DIR is unset. Each needs a passing
        run of that build in the kept one, stamped with the same APK, the same desktop CLI and the
        bundle the gate built.
    #>
    param(
        [Parameter(Mandatory = $true)]$Evidence,
        [object[]]$Fixtures = @(),
        [string]$DesktopJar,
        [string]$FixtureFingerprint
    )
    # The patch tests read the whole fixture folder, so a folder that isn't the one the gate saw
    # is a gap even where the declared builds' own APKs are unchanged.
    if ($FixtureFingerprint) {
        if (-not $Evidence.Manifest.fixtureFingerprint) { return 'that gate recorded no fingerprint of the fixture folder' }
        if ([string]$Evidence.Manifest.fixtureFingerprint -cne $FixtureFingerprint) { return 'the fixture folder changed since that gate ran' }
    }
    $wanted = @($Fixtures | Where-Object { $_ })
    if ($wanted.Count -eq 0) { return $null }
    if ($Evidence.Manifest.fixturesPatched -ne $true) {
        return "that gate patched no declared build, and one here would patch $(@($wanted | ForEach-Object { $_.Version }) -join ', ')"
    }
    foreach ($fixture in $wanted) {
        $stampPath = Join-Path $Evidence.Directory "fixtures/$($fixture.Version)/kept/stamp.json"
        if (-not (Test-Path -LiteralPath $stampPath -PathType Leaf)) { return "that gate kept no passing run of $($fixture.Version)" }
        try { $stamp = Get-Content -LiteralPath $stampPath -Raw | ConvertFrom-Json }
        catch { return "the stamp of its run of $($fixture.Version) doesn't parse" }
        if ([string]$stamp.apkSha256 -cne (Get-EvidenceHash -Path ([string]$fixture.Apk))) {
            return "that gate patched another APK of $($fixture.Version) than $(Split-Path -Leaf ([string]$fixture.Apk))"
        }
        if (-not $DesktopJar -or [string]$stamp.desktopJarSha256 -cne (Get-EvidenceHash -Path $DesktopJar)) {
            return "that gate patched $($fixture.Version) with another desktop CLI"
        }
        if ([string]$stamp.bundleSha256 -cne [string]$Evidence.Manifest.bundle.sha256) {
            return "that gate's run of $($fixture.Version) was made with another bundle"
        }
    }
    return $null
}

function Write-GateKeptRun {
    <#
    Keeps a passing patch run for the release scripts: the patched APK and the merge are moved in,
    the report copied, and the stamp written last. Called by verify-all-patches.ps1 -KeepIn.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$KeepIn,
        [Parameter(Mandatory = $true)][string]$PatchedApk,
        [Parameter(Mandatory = $true)][string]$Result,
        [string]$MergedApk,
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)][string]$PatchList,
        [Parameter(Mandatory = $true)][string]$DesktopJar,
        [Parameter(Mandatory = $true)][string]$VersionName,
        [string]$VersionCode,
        [Parameter(Mandatory = $true)][bool]$Forced
    )
    New-Item -ItemType Directory -Force -Path $KeepIn | Out-Null
    Move-Item -LiteralPath $PatchedApk -Destination (Join-Path $KeepIn 'patched.apk') -Force
    if ($MergedApk) { Move-Item -LiteralPath $MergedApk -Destination (Join-Path $KeepIn 'stock-merged.apk') -Force }
    Copy-Item -LiteralPath $Result -Destination (Join-Path $KeepIn 'result.json') -Force
    $stamp = [ordered]@{
        schemaVersion    = 1
        bundleSha256     = Get-EvidenceHash -Path $Bundle
        apkSha256        = Get-EvidenceHash -Path $Apk
        patchListSha256  = Get-EvidenceHash -Path $PatchList
        desktopJarSha256 = Get-EvidenceHash -Path $DesktopJar
        versionName      = $VersionName
        versionCode      = $VersionCode
        forced           = $Forced
        merged           = [bool]$MergedApk
        patchedSha256    = Get-EvidenceHash -Path (Join-Path $KeepIn 'patched.apk')
        resultSha256     = Get-EvidenceHash -Path (Join-Path $KeepIn 'result.json')
        mergedSha256     = if ($MergedApk) { Get-EvidenceHash -Path (Join-Path $KeepIn 'stock-merged.apk') } else { $null }
    }
    [System.IO.File]::WriteAllText((Join-Path $KeepIn 'stamp.json'), ($stamp | ConvertTo-Json),
        (New-Object System.Text.UTF8Encoding($false)))
}
