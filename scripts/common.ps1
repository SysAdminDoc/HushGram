<#
.SYNOPSIS
    The helpers the release and verification scripts share.

.DESCRIPTION
    Dot-source this beside patch-target.ps1 and patch-report.ps1:

        . (Join-Path $PSScriptRoot 'common.ps1')

    Each of these existed in two to four copies that had already drifted apart. The path guard
    was identical in three scripts, but the cleanup helper recursed unconditionally in one and
    only on request in another. The version read appeared four times, twice without
    -LiteralPath. And the desktop CLI was looked up by two functions with different search
    orders, one returning $null and one throwing. Copies of a guard drift in the direction of whichever caller was
    edited last, which is the direction nobody checked.

    Assert-UrlReachable is taken from Hushfacebook's scripts/common.ps1
    (https://github.com/SysAdminDoc/Hushfacebook, commit 814acd23d7b70d5d23abce6cb6c97767e16a051e).
    GPL-3.0-only. Modified for HushGram (Instagram), 2026; its own header says how.
    Use-Utf8ConsoleOutput is taken from the same file at Hushfacebook commit
    3716793a4c7f0cda3595b021cff12316aafea41f, unchanged. The rest is HushGram's.
#>

function Resolve-WithinRoot {
    <#
    .SYNOPSIS
        A generated path, proved to be inside the work directory, or a throw.
    .DESCRIPTION
        Every path these scripts hand to the patcher or delete afterwards goes through this, so
        a run identifier that came out wrong cannot reach outside the directory the caller owns.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root
    )
    $candidate = [System.IO.Path]::GetFullPath($Path)
    $prefix = $Root.TrimEnd('\') + '\'
    if (-not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to use a generated path outside the work directory: $candidate"
    }
    return $candidate
}

function Remove-GeneratedPath {
    <#
    .SYNOPSIS
        Delete something this run generated, inside the work directory, warning rather than
        failing when it will not go.
    .DESCRIPTION
        Recursive by default, because every caller is deleting a run directory and the one copy
        that made it optional had its single caller pass -Recurse anyway. Pass -NoRecurse for a
        single file. A failure here is reported and swallowed on purpose: leaving scratch behind
        is not a reason to fail a run that has already produced its answer.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root,
        [switch]$NoRecurse
    )
    try {
        $safe = Resolve-WithinRoot -Path $Path -Root $Root
        if (-not (Test-Path -LiteralPath $safe)) { return }
        if ($NoRecurse) { Remove-Item -LiteralPath $safe -Force -ErrorAction Stop }
        else { Remove-Item -LiteralPath $safe -Recurse -Force -ErrorAction Stop }
    } catch {
        Write-Warning "Could not remove generated path: $($_.Exception.Message)"
    }
}

function Get-BundleVersion {
    <#
    .SYNOPSIS
        The version in gradle.properties, which every generated name follows.
    .DESCRIPTION
        -LiteralPath, which two of the four copies of this read were missing: a repository path
        holding a bracket is read as a wildcard otherwise, and the read silently finds nothing.
    #>
    param([Parameter(Mandatory = $true)][string]$Root)

    $path = Join-Path $Root 'gradle.properties'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "There is no gradle.properties at $path, so the bundle version is unknown."
    }
    $line = @(Get-Content -LiteralPath $path | Where-Object { $_ -match '^\s*version\s*=' }) |
        Select-Object -First 1
    if (-not $line) { throw "gradle.properties names no version: $path" }
    $version = ($line -replace '^\s*version\s*=\s*', '').Trim()
    if (-not $version) { throw "gradle.properties has an empty version: $path" }
    return $version
}

function Get-ReleaseBundlePath {
    <#
    .SYNOPSIS
        Where :patches:buildAndroid leaves the bundle a release publishes.
    .DESCRIPTION
        patches/build/release, never patches/build/libs. The Morphe plugin's buildAndroid adds the
        DEX payload to the jar task's own output in place, so any later task that reruns
        :patches:jar (:patches:test does) wrote the plain jar back over the finished bundle under
        the same name. v0.43.0 shipped that jar, and on 2026-09-21 it happened again between the
        build and the index push. buildAndroid now ends by copying the finished bundle here, where
        no other task writes, with bundle.sha256 beside it.
    #>
    param([Parameter(Mandatory = $true)][string]$Root, [string]$Version)

    if (-not $Version) { $Version = Get-BundleVersion -Root $Root }
    return Join-Path $Root "patches/build/release/patches-$Version.mpp"
}

function Get-ReleaseFactsArguments {
    <#
    .SYNOPSIS
        The switches the pre-push hook runs validate-release-facts.ps1 with, for one kind of push.
    .DESCRIPTION
        Index is the push that rewrites patches-bundle.json and hands a release to Manager users.
        It's held to everything: the published files, the network, the description's test counts,
        and the bundle this checkout built for the version the index publishes, or the hosted one
        on its own when there's none. When the push gate kept a run that stands for HEAD
        (gate-evidence.ps1), the set adds -FromGate: that run's test results are the ones the
        counts are read from, and its bundle is compared with the hosted one when none was built
        here.

        Ordinary is every other push that moves a file the check reads. The description stays
        with the release it describes, the index may lag a version still being prepared, and this
        checkout's build outputs (test results and patches/build/release) are left unread, since
        they can belong to another commit: a bundle built before the catalog's patcher pin moved
        refused every push after the move.

        The hook and scripts/test-release-tooling.ps1 both take their sets from here, so the suite
        runs the hook's own checks rather than a copy of them.

        -Script names the check the set is for when it isn't this checkout's own: the hook checks
        a pushed commit with that commit's copy, which can be older. Before 1787137 the check knew
        -SkipLocalBuild as -SkipTestResults, which left the test results unread but still read the
        bundle, so a check that only knows the old name gets that one. The hook's worktree is a
        fresh checkout with no bundle in it, so nothing is read there that -SkipLocalBuild would
        have left alone. A switch the check knows by neither name is refused here, before pwsh
        would refuse it with less to say.
    #>
    param(
        [Parameter(Mandatory = $true)][ValidateSet('Index', 'Ordinary')][string]$Push,
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Script
    )
    if ($Push -eq 'Ordinary') {
        $arguments = [ordered]@{ Root = $Root; SkipDescriptionTestCount = $true; AllowPublishedIndexLag = $true; SkipLocalBuild = $true }
    } else {
        $arguments = [ordered]@{ Root = $Root; VerifyPublishedAsset = $true }
        $indexVersion = [string](Get-Content -LiteralPath (Join-Path $Root 'patches-bundle.json') -Raw | ConvertFrom-Json).version
        $builtHere = Get-ReleaseBundlePath -Root $Root -Version $indexVersion
        # The push gate's run of the source commit, when HEAD is that commit or an index commit
        # over it (gate-evidence.ps1): its test results stand for the ones here, and its bundle for
        # one built here. Every check still runs on them.
        if (-not (Get-Command Find-GateEvidence -ErrorAction SilentlyContinue)) { . (Join-Path $PSScriptRoot 'gate-evidence.ps1') }
        $gateRun = Find-GateEvidence -Root $Root -AllowIndexCommits 6>$null
        if ($gateRun) { $arguments['FromGate'] = $true }
        if (Test-Path -LiteralPath $builtHere -PathType Leaf) { $arguments['ArtifactPath'] = $builtHere }
        elseif (-not $gateRun) { $arguments['ArtifactIsHosted'] = $true }
    }
    if (-not $Script) { return $arguments }

    # Read as UTF-8 first: Windows PowerShell's ParseFile reads a file with no byte order mark as ANSI.
    $paramBlock = [System.Management.Automation.Language.Parser]::ParseInput(
        [System.IO.File]::ReadAllText($Script), [ref]$null, [ref]$null).ParamBlock
    $known = @(if ($paramBlock) { $paramBlock.Parameters | ForEach-Object { $_.Name.VariablePath.UserPath } })
    $adapted = [ordered]@{}
    foreach ($key in $arguments.Keys) {
        # Reading the gate's run saves a rebuild and leaves no check out, so a check that predates
        # it just reads the build outputs here.
        if ($key -eq 'FromGate' -and $key -notin $known) { continue }
        $name = if ($key -eq 'SkipLocalBuild' -and $key -notin $known -and 'SkipTestResults' -in $known) { 'SkipTestResults' } else { $key }
        if ($name -notin $known) { throw "$Script takes no -$key, so it can't run the hook's $Push set." }
        $adapted[$name] = $arguments[$key]
    }
    return $adapted
}

function ConvertTo-ScriptArguments {
    <#
    .SYNOPSIS
        A set from Get-ReleaseFactsArguments as the words pwsh -File takes.
    #>
    param([Parameter(Mandatory = $true)][System.Collections.IDictionary]$Arguments)
    foreach ($key in $Arguments.Keys) {
        $value = $Arguments[$key]
        if ($value -is [bool]) {
            if ($value) { "-$key" }
        } else {
            "-$key"
            [string]$value
        }
    }
}

function Get-SourcesNewerThanBundle {
    <#
    .SYNOPSIS
        The source files written after the bundle was built, newest first.
    .DESCRIPTION
        Only :patches:buildAndroid writes the release bundle, and :patches:test rebuilds
        build/libs without it, so a device build made after a patch change and a test run
        patched with the previous hooks (2026-09-23, Swipe-left controls). Counted: the sources of
        the patches module and its submodules (patches/src/main, patches/<submodule>/src/main,
        the compile-only stubs among them, whose constants can be inlined into patch code), the
        sources of every extension module (extensions/<module>/src/main and
        extensions/<module>/<submodule>/src/main), the Gradle files that shape them, the R8
        rules (*.pro at extensions/ and in each module, extensions/proguard-rules.pro being the
        one every extension's R8 step reads), and NOTICE, which :extensions:instagram compiles
        into the payload for its Licenses row. Build output is never under src/main, so it is not
        walked. patch-for-device.ps1 refuses a bundle this finds anything newer than.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Bundle
    )

    $built = (Get-Item -LiteralPath $Bundle).LastWriteTimeUtc
    $sourceRoots = @()
    $gradleFiles = @('gradle.properties', 'settings.gradle.kts', 'build.gradle.kts', 'gradle/libs.versions.toml', 'NOTICE') |
        ForEach-Object { Join-Path $Root $_ }
    $ruleDirs = @()
    $patches = Join-Path $Root 'patches'
    $moduleDirs = @()
    if (Test-Path -LiteralPath $patches -PathType Container) {
        $moduleDirs += @(Get-Item -LiteralPath $patches) + @(Get-ChildItem -LiteralPath $patches -Directory |
            Where-Object { $_.Name -notin @('src', 'build') })
    }
    $extensions = Join-Path $Root 'extensions'
    if (Test-Path -LiteralPath $extensions -PathType Container) {
        $ruleDirs += $extensions
        foreach ($module in Get-ChildItem -LiteralPath $extensions -Directory) {
            $moduleDirs += @($module) + @(Get-ChildItem -LiteralPath $module.FullName -Directory |
                Where-Object { $_.Name -notin @('src', 'build') })
        }
    }
    foreach ($dir in $moduleDirs) {
        $sourceRoots += Join-Path $dir.FullName 'src/main'
        $gradleFiles += Join-Path $dir.FullName 'build.gradle.kts'
        $ruleDirs += $dir.FullName
    }
    foreach ($dir in $ruleDirs) {
        $gradleFiles += @(Get-ChildItem -LiteralPath $dir -File -Filter '*.pro' -ErrorAction SilentlyContinue |
            ForEach-Object FullName)
    }
    $newer = New-Object System.Collections.Generic.List[System.IO.FileInfo]
    foreach ($sourceRoot in $sourceRoots) {
        if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -File -Recurse) {
            if ($file.LastWriteTimeUtc -gt $built) { $newer.Add($file) }
        }
    }
    foreach ($path in $gradleFiles) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        $file = Get-Item -LiteralPath $path
        if ($file.LastWriteTimeUtc -gt $built) { $newer.Add($file) }
    }
    return @($newer | Sort-Object LastWriteTimeUtc -Descending)
}

function Resolve-DesktopCli {
    <#
    .SYNOPSIS
        The Morphe desktop CLI jar, or $null, or a throw when the caller cannot do without it.
    .DESCRIPTION
        One search order for both callers: -Explicit, HUSHGRAM_DESKTOP_JAR, HUSHGRAM_WORKDIR,
        then the repository's own build/morphe-tools. Newest by write time rather than by name,
        because the jar ships under its version and sorting those as text puts 1.9.0 above
        1.15.0. -Required turns "nothing found" into a throw naming what to set, which is what
        the second copy of this did and the first did not.
    #>
    param([string]$Explicit, [string]$Root, [switch]$Required)

    # Made absolute through PowerShell's location, the one Test-Path looked in. [IO.Path]::GetFullPath
    # reads the process directory, which Set-Location doesn't move: a relative -DesktopJar that
    # Test-Path found came back as a jar somewhere else, and DexDiff compiled without dexlib2.
    $found = $null
    if ($Explicit -and (Test-Path -LiteralPath $Explicit -PathType Leaf)) {
        $found = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Explicit)
    } elseif ($Explicit) {
        # Named and not there: say so rather than quietly searching somewhere else.
        throw "No Morphe desktop CLI at the path given: $Explicit"
    } elseif ($env:HUSHGRAM_DESKTOP_JAR -and
            (Test-Path -LiteralPath $env:HUSHGRAM_DESKTOP_JAR -PathType Leaf)) {
        $found = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($env:HUSHGRAM_DESKTOP_JAR)
    } else {
        $directories = @($env:HUSHGRAM_WORKDIR)
        if ($Root) { $directories += (Join-Path $Root 'build/morphe-tools') }
        foreach ($directory in $directories) {
            if (-not $directory -or -not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
            $candidate = @(Get-ChildItem -LiteralPath $directory -Filter 'morphe-desktop*.jar' -File `
                -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending |
                Select-Object -First 1)
            if ($candidate.Count -eq 1) { $found = $candidate[0].FullName; break }
        }
    }

    if (-not $found -and $Required) {
        throw ('No Morphe desktop CLI. Pass -DesktopJar, or set HUSHGRAM_DESKTOP_JAR or ' +
            'HUSHGRAM_WORKDIR, or put the jar in build/morphe-tools.')
    }
    return $found
}

function Get-BaseApk {
    <#
    .SYNOPSIS
        The APK whose manifest and resource table describe an app: the file itself, or the base
        APK a split bundle carries, copied out to -Destination.
    .DESCRIPTION
        Instagram's Play install is a split bundle (.apks), not one APK, and aapt2 reads an APK. The base APK holds the manifest and the app's own resource table; the splits
        hold densities, languages and feature modules. An .apks or .apkm names it base.apk, an
        .xapk names it after the package, so the largest APK is the fallback.

        Both paths are resolved against PowerShell's location before .NET sees them, and the
        answer is a full path. .NET reads a relative path against the process's own directory,
        which a hook, a scheduled task or a session that moved with Set-Location leaves somewhere
        else: `-Apk fixtures\instagram.apks` then named a file that wasn't there.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    $Apk = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk)
    $Destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)
    if ([System.IO.Path]::GetExtension($Apk).ToLowerInvariant() -eq '.apk') { return $Apk }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'base.apk' } | Select-Object -First 1
        if (-not $entry) {
            $entry = $zip.Entries | Where-Object { $_.FullName -like '*.apk' } |
                Sort-Object Length -Descending | Select-Object -First 1
        }
        if (-not $entry) { throw "$(Split-Path -Leaf $Apk) holds no APK." }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $Destination, $true)
    } finally {
        $zip.Dispose()
    }
    return $Destination
}

function Get-MergedApk {
    <#
    .SYNOPSIS
        The one APK the desktop CLI patches: a split bundle merged the way the CLI merges it,
        written to -Destination, or the APK itself when it isn't a bundle.
    .DESCRIPTION
        morphe-desktop merges an .apkm, .apks or .xapk into <name>-merged.apk beside its output,
        patches that, and since 1.17.0 deletes it on the way out. The patched APK's resource table
        and manifest were rebuilt from that merge, so the checks compared them with base.apk
        instead, which lacks every resource the splits carry: on 580 the patched table held 7,588
        resources base.apk doesn't, and none of them was compared. So the scripts merge first,
        with the CLI's own merger and the arguments it passes (MergeSplits.java), and hand the CLI
        the merged APK, which it patches as it is. There is nothing else to fall back to, so a
        merge that fails or writes no APK throws. The extensions are the CLI's own list
        (BundleFormats); anything else goes to the CLI as it is.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][string]$Java,
        [Parameter(Mandatory = $true)][string]$DesktopJar
    )

    $Apk = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk)
    $Destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)
    if ([System.IO.Path]::GetExtension($Apk).TrimStart('.').ToLowerInvariant() -notin @('apkm', 'apks', 'xapk')) {
        return $Apk
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
    if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Force }
    # Continue for the call alone: Windows PowerShell 5.1 turns a JDK warning on stderr into a
    # terminating error under Stop. The exit code and the file are what decide.
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = -1
        $output = @(& $Java '-Xmx6g' '-cp' $DesktopJar (Join-Path $PSScriptRoot 'MergeSplits.java') $Apk $Destination 2>&1 |
            ForEach-Object { "$_" })
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    # What the merger said, without the stack frames under an exception.
    $said = @($output | Where-Object { $_ -notmatch '^\s+at ' } | Select-Object -Last 3) -join ' '
    if ($exitCode -ne 0) {
        throw "Could not merge $(Split-Path -Leaf $Apk) into one APK (exit $exitCode): $said"
    }
    if (-not (Test-Path -LiteralPath $Destination -PathType Leaf) -or (Get-Item -LiteralPath $Destination).Length -eq 0) {
        throw ("The merge of $(Split-Path -Leaf $Apk) wrote no APK at $Destination, and base.apk is not what " +
            'the CLI patches, so there is nothing to hold the patched APK to.')
    }
    return $Destination
}

function Assert-UrlReachable {
    <#
    .SYNOPSIS
        A HEAD request that has to answer 200, or a throw naming the address and what it said.
    .DESCRIPTION
        validate-release-facts.ps1 fetches the indexed bundle and the Morphe add-source page
        with this on every run. It lives here so the contract tests can call it on its own:
        through the release check, an address has to pass the shape, scheme and host checks
        before it is fetched, and the one case that meant to try a dead link tripped the shape
        check first and never reached the fetch.

        Taken from Hushfacebook's scripts/common.ps1
        (https://github.com/SysAdminDoc/Hushfacebook, commit 814acd23d7b70d5d23abce6cb6c97767e16a051e).
        GPL-3.0-only. Modified for HushGram (Instagram), 2026: the network message names
        HushGram's skip variable.
    #>
    param(
        [Uri]$Uri,
        [string]$Description,
        [string]$FailureHint
    )
    # -SkipHttpErrorCheck is PowerShell 7 only, and the pre-push hook runs whichever shell it
    # found, so a 404 has to be read out of the thrown response instead. That is the answer this
    # check exists for: the index once named a tag that did not exist yet.
    $status = 0
    try {
        # -UseBasicParsing because Windows PowerShell otherwise hands the reply to the IE
        # parser, which throws a null reference on a HEAD with no body. PowerShell 7 accepts
        # the switch and ignores it.
        $response = Invoke-WebRequest -Uri $Uri -Method Head -MaximumRedirection 5 `
            -TimeoutSec 60 -UseBasicParsing
        $status = [int]$response.StatusCode
    } catch {
        $failed = $_.Exception.Response
        if ($failed -and $failed.StatusCode) {
            $status = [int]$failed.StatusCode
        } else {
            throw ("Could not reach the ${Description} ${Uri}: $($_.Exception.Message). " +
                'If the network is down, push with HUSHGRAM_SKIP_PRE_PUSH=1 and run this again later.')
        }
    }
    if ($status -ne 200) {
        throw ("The ${Description} ${Uri} answered HTTP ${status}. " + $FailureHint)
    }
    Write-Host ("[release] ${Description} answers 200: " + $Uri)
}

function Use-Utf8ConsoleOutput {
    <#
    .SYNOPSIS
        Runs a script block with PowerShell reading native commands' output as UTF-8, and puts the
        caller's encoding back afterwards.
    .DESCRIPTION
        git writes UTF-8, and PowerShell decodes a native command's output with
        [Console]::OutputEncoding, which is code page 437 for a hook's pwsh when the push starts in
        Git Bash. Setting it calls SetConsoleOutputCP, and in a process with no console (a detached
        start, a service) Windows PowerShell 5.1 turns that into "The handle is invalid." before git
        ever runs. pwsh 7 keeps the value without a console. So when the setter finds no console,
        the encoding goes into Console's own field instead, which the property hands back and 5.1
        decodes with.

        Taken from Hushfacebook's scripts/common.ps1
        (https://github.com/SysAdminDoc/Hushfacebook, commit 3716793a4c7f0cda3595b021cff12316aafea41f).
        GPL-3.0-only.
    #>
    param([Parameter(Mandatory = $true)][scriptblock]$Script)

    $utf8OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $utf8OutputBefore = [Console]::OutputEncoding
    $utf8OutputField = $null
    try {
        [Console]::OutputEncoding = $utf8OutputEncoding
    } catch {
        if ($_.Exception.InnerException -isnot [System.IO.IOException]) { throw }
        $utf8OutputField = [Console].GetField('_outputEncoding', [System.Reflection.BindingFlags]'NonPublic, Static')
        if (-not $utf8OutputField) { throw }
        $utf8OutputField.SetValue($null, $utf8OutputEncoding)
    }
    try {
        & $Script
    } finally {
        if ($utf8OutputField) {
            $utf8OutputField.SetValue($null, $utf8OutputBefore)
        } else {
            [Console]::OutputEncoding = $utf8OutputBefore
        }
    }
}

function Find-MachineNames {
    <#
    .SYNOPSIS
        The lines of tracked files that name the maintainer's machine or a phone.
    .DESCRIPTION
        The working-notes folder .gitignore keeps out, the backup folders on the maintainer's
        machine that share its name, and an adb serial, a Samsung one being R5C and eight more
        letters or digits. Four fixture tests once fell back to one of those folders, which
        skipped quietly on every other machine and published this one's layout, and five scripts
        carried the test phone's serial. .gitignore is the one file allowed to name what it keeps
        out. Both patterns are built from parts, so the file holding them can't match itself.

        git grep reads bytes, so text in UTF-16 has patterns of its own: every letter followed by a
        NUL for little-endian, the way Windows PowerShell's > writes a file, or preceded by one for
        big-endian. They go through git grep's Perl regexes, which read \x00. A git built without
        them can't search, which throws like any search that doesn't run.

        With -Commit the files are read out of those commits, which is what a push publishes, and
        no worktree is needed: git grep reads them all in one pass and puts the commit in front of
        each hit, <commit>:<path>:<line>:<text>. Without it, the tracked files as they stand in the
        working tree. GIT_* variables are cleared for the search, so it reads the repository -Root
        names, and a search that doesn't run throws rather than reading as a clean tree. Every hit
        comes back as git grep prints it; none at all comes back as nothing, so callers wrap it in @().
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string[]]$Commit,
        # A hundred commits to a git grep keeps its command line inside what Windows allows. The
        # contract tests pass a smaller one, to reach every batch with a handful of commits.
        [int]$BatchSize = 100
    )

    $commits = @($Commit | Where-Object { $_ })
    $batches = New-Object System.Collections.Generic.List[object]
    if ($commits.Count -eq 0) { $batches.Add(@()) }
    for ($i = 0; $i -lt $commits.Count; $i += $BatchSize) {
        $batches.Add(@($commits | Select-Object -Skip $i -First $BatchSize))
    }
    $hits = New-Object System.Collections.Generic.List[string]
    $saved = @{}
    foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
        $saved[$variable.Name] = $variable.Value
        Remove-Item -LiteralPath ('Env:\' + $variable.Name)
    }
    # Windows PowerShell 5.1 turns a native command's stderr into a terminating error under Stop,
    # even redirected, and git grep says why it could not search on stderr.
    $wideLe = { param([string[]]$Parts) ($Parts | ForEach-Object { "$_\x00" }) -join '' }
    $wideBe = { param([string[]]$Parts) ($Parts | ForEach-Object { "\x00$_" }) -join '' }
    $notes = @('c', 'l', 'a', 'u', 'd', 'e')
    $serial = @('R', '5', 'C')
    $scans = @(
        @{ Name = 'the notes folder'; Flags = @('-i', '-P')
            Pattern = @(($notes -join ''), (& $wideLe $notes), (& $wideBe $notes)) -join '|' }
        @{ Name = 'a phone serial'; Flags = @('-P')
            Pattern = @((($serial -join '') + '[A-Z0-9]{8}'), ((& $wideLe $serial) + '(?:[A-Z0-9]\x00){8}'),
                ((& $wideBe $serial) + '(?:\x00[A-Z0-9]){8}')) -join '|' }
    )
    # git's output is read as UTF-8 (Use-Utf8ConsoleOutput), so a hit is reported as the file has it.
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        foreach ($scan in $scans) {
            foreach ($batch in $batches) {
                $arguments = @('-C', $Root, 'grep', '-n', '-a') + $scan.Flags + @('-e', $scan.Pattern) + @($batch) +
                    @('--', '.', ':!.gitignore')
                $found = @(Use-Utf8ConsoleOutput { & git @arguments 2>$null })
                # 1 is git grep's "no match". Anything above it means the search did not run.
                if ($LASTEXITCODE -gt 1) {
                    $what = if ($batch.Count -gt 0) { "commit $($batch -join ', ')" } else { 'the tracked files' }
                    throw "git grep could not search $what in $Root for $($scan.Name)."
                }
                foreach ($line in $found) { $hits.Add([string]$line) }
            }
        }
    } finally {
        $ErrorActionPreference = $preference
        foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
    }
    $global:LASTEXITCODE = 0
    return $hits.ToArray()
}
