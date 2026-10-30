<#
.SYNOPSIS
    The native feature runner's pieces: its instrumentation APK, the facts a target APK is held
    to, and a run of cases on a leased device inside a throwaway Android user.

.DESCRIPTION
    Dot-sourced by native-runner.ps1 and test-native-runner.ps1. The runner (native-runner/) is an
    instrumentation package, so Android runs it inside the target's own process, with the target's
    class loader, and only when both carry the same signing key. Nothing of the target is copied
    into it: every class a case touches comes from the installed patched APK.

    The target's loader reads the runner APK ahead of the target's own (LoadedApk.makePaths puts
    the instrumentation first), so a runner class named like one of the target's would stand in for
    it. The build refuses any class outside the runner's own package for that reason.

    The runner skips the target's Application.onCreate, but Android still creates the target's
    Application (attachBaseContext) and starts its content providers before any case runs. Runs
    are therefore emulator-only for now, with the network off and checked off from the host and
    from inside the target's process. A phone's network can't be cut for one Android user from
    the shell: cmd connectivity's per-package switch resolves a single uid and needs a global
    debug firewall chain.

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
#>

. (Join-Path $PSScriptRoot 'injected-register-device.ps1')
. (Join-Path $PSScriptRoot 'apk-facts.ps1')
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'patch-record.ps1')
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')

$NativeRunnerPackage = 'app.hushgram.nativerunner'
$NativeRunnerUserName = 'hushgram-native-runner'
$NativeRunnerAbsentHook = 'app.hushgram.extension.nativerunner.AbsentHook'
# Every run proves the controls first.
$NativeRunnerControlCases = @('bind', 'control-positive', 'control-negative')
# Then, only once they hold, the feature cases in this order, each with the case it builds on:
# presence-paused needs the settings presence saves, so it runs only after presence passed, and
# visual-paused the same for visual.
$NativeRunnerFeatureCases = [ordered]@{ 'presence' = $null; 'presence-paused' = 'presence'; 'visual' = $null; 'visual-paused' = 'visual'
    'notifications' = $null; 'share' = $null }
# What every result carries. Anything else a case reports is what it observed.
$NativeRunnerResultKeys = @('case', 'verdict', 'package', 'versionName', 'versionCode', 'signerSha256', 'baseApkSha256', 'user',
    'process', 'network', 'missingHook', 'prerequisite', 'buildIdentity', 'error', 'detail')

function Find-NativeRunnerSdkTool {
    param([string]$Directory, [string]$Name)
    foreach ($leaf in @("$Name.exe", $Name)) {
        $path = Join-Path $Directory $leaf
        if (Test-Path -LiteralPath $path -PathType Leaf) { return $path }
    }
    throw "No $Name in $Directory."
}

function Resolve-NativeRunnerTools {
    param([string]$Sdk, [string]$Java)
    $root = $null
    foreach ($candidate in @($Sdk, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) { $root = $candidate; break }
    }
    if (-not $root) { throw 'No Android SDK: pass -Sdk or set ANDROID_HOME.' }
    # By version number, not text: as strings 9.0.0 sorts above 37.0.0.
    $buildTools = Get-ChildItem -LiteralPath (Join-Path $root 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (-not $buildTools) { throw "No versioned build-tools under $root." }
    $platform = Get-ChildItem -LiteralPath (Join-Path $root 'platforms') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^android-\d+(\.\d+)?$' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'android.jar') -PathType Leaf) } |
        Sort-Object { $level = $_.Name.Substring(8); if (-not $level.Contains('.')) { $level += '.0' }; [version]$level } -Descending |
        Select-Object -First 1
    if (-not $platform) { throw "No platform android.jar under $root." }
    $javaCommand = Get-Command (Resolve-Java -Explicit $Java) -CommandType Application -ErrorAction Stop | Select-Object -First 1
    $javac = Join-Path (Split-Path -Parent $javaCommand.Source) 'javac.exe'
    if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) { $javac = Join-Path (Split-Path -Parent $javaCommand.Source) 'javac' }
    if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) { throw "The Java at $($javaCommand.Source) has no javac. Point -Java at a JDK." }
    $tools = [pscustomobject]@{
        AndroidJar = Join-Path $platform.FullName 'android.jar'
        Java = $javaCommand.Source
        Javac = $javac
        D8Jar = Join-Path $buildTools.FullName 'lib/d8.jar'
        ApkSignerJar = Join-Path $buildTools.FullName 'lib/apksigner.jar'
        Aapt2 = Find-NativeRunnerSdkTool $buildTools.FullName 'aapt2'
        Zipalign = Find-NativeRunnerSdkTool $buildTools.FullName 'zipalign'
        Adb = Find-NativeRunnerSdkTool (Join-Path $root 'platform-tools') 'adb'
    }
    foreach ($jar in @($tools.D8Jar, $tools.ApkSignerJar)) {
        if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "build-tools $($buildTools.Name) has no $(Split-Path -Leaf $jar)." }
    }
    $tools
}

function Invoke-NativeRunnerTool {
    param([string]$Executable, [string[]]$Arguments, [string]$Failure)
    # Continue for the call alone: Windows PowerShell turns a tool's stderr into a terminating
    # error under Stop. The exit code decides.
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $Executable @Arguments 2>&1 | ForEach-Object { "$_" })
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $preference }
    if ($exitCode -ne 0) { throw "$Failure (exit $exitCode): $((@($output) | Select-Object -Last 5) -join '; ')" }
    $output
}

function Get-NativeRunnerSha256Text {
    param([string]$Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { -join ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)) | ForEach-Object { $_.ToString('x2') }) }
    finally { $sha.Dispose() }
}

function Get-ApkSignerDigest {
    param($Tools, [string]$Apk)
    $lines = Invoke-NativeRunnerTool -Executable $Tools.Java -Arguments @('-cp', $Tools.ApkSignerJar,
        'com.android.apksigner.ApkSignerTool', 'verify', '--print-certs', $Apk) -Failure "apksigner could not verify $(Split-Path -Leaf $Apk)"
    # build-tools 37 labels a v3 signer "V3.0 Signer:" and older ones "Signer #1", and every scheme
    # repeats the same certificate, so any label counts and each digest once. A source stamp
    # isn't a signer Android reports to the app.
    [string[]]$digests = @($lines | Where-Object { $_ -notmatch '^Source Stamp' } |
        ForEach-Object { [regex]::Match($_, 'certificate SHA-256 digest: ([0-9a-f]{64})$') } |
        Where-Object Success | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
    if ($digests.Count -eq 0) { throw "apksigner named no signer for $(Split-Path -Leaf $Apk)." }
    # The app sees only its current signer (getApkContentsSigners), so a rotated key or a
    # multi-signer APK could never bind.
    if ($digests.Count -ne 1) {
        throw "$(Split-Path -Leaf $Apk) carries $($digests.Count) signing certificates (a rotated key, or several signers). The runner binds to one."
    }
    $digests[0]
}

function Get-ApkBuildIdentity {
    <#
    .SYNOPSIS
        The hg1: build identity patching wrote into the APK's dex, the answer the injected
        extension has to give on the device.
    #>
    param([string]$Apk)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $found = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $zip = [IO.Compression.ZipFile]::OpenRead($Apk)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -notmatch '^classes\d*\.dex$') { continue }
            $buffer = [IO.MemoryStream]::new()
            $stream = $entry.Open()
            try { $stream.CopyTo($buffer) } finally { $stream.Dispose() }
            # The identity is ASCII, and dex keeps ASCII strings byte for byte.
            $text = [Text.Encoding]::GetEncoding(28591).GetString($buffer.GetBuffer(), 0, [int]$buffer.Length)
            $buffer.Dispose()
            foreach ($match in [regex]::Matches($text, 'hg1:[0-9a-f]{64}(?![0-9a-f])')) { [void]$found.Add($match.Value) }
        }
    } finally { $zip.Dispose() }
    if ($found.Count -ne 1) {
        throw "$(Split-Path -Leaf $Apk) carries $($found.Count) HushGram build identities, not one. Patch it from a release bundle with the shared extension."
    }
    @($found)[0]
}

function Get-NativeRunnerTarget {
    <#
    .SYNOPSIS
        What the target APK is: package, version, signer, file hash, build identity and the
        patch selection its patch result records. Every case verdict is held to these.
    .DESCRIPTION
        The result counts only through the patch record patch-for-device.ps1 wrote beside it,
        which ties that result to this APK's content. A result from another run of the same
        version would otherwise name the wrong selection.
    #>
    param($Tools, [string]$Apk, [string]$Report, [string]$Record)
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "No target APK at $Apk." }
    if (-not $Report) { $Report = Join-Path (Split-Path -Parent $Apk) 'result.json' }
    if (-not (Test-Path -LiteralPath $Report -PathType Leaf)) {
        throw "No patch result at $Report. Each verdict names the patch selection, which that file records; pass -Report."
    }
    if (-not $Record) { $Record = Join-Path (Split-Path -Parent $Report) 'patch-record.json' }
    $facts = Get-ApkManifestFacts -Apk $Apk -Aapt2 $Tools.Aapt2
    try { $result = ConvertFrom-PatchJson (Get-Content -LiteralPath $Report -Raw) }
    catch { throw "The patch result at $Report doesn't parse: $($_.Exception.Message)" }
    if ([string]$result.packageName -cne $facts.package -or [string]$result.packageVersion -cne [string]$facts.versionName) {
        throw "The patch result is for $($result.packageName) $($result.packageVersion), not the target's $($facts.package) $($facts.versionName)."
    }
    $untied = Test-PatchRecord -Apk $Apk -Result $Report -Record $Record
    if ($untied) { throw "The patch result can't vouch for this APK's selection: $untied. Patch with scripts/patch-for-device.ps1, which writes the record." }
    [string[]]$applied = @(Get-ReportPatchNames $result.appliedPatches)
    if ($applied.Count -eq 0) { throw 'The patch result lists no applied patches.' }
    [Array]::Sort($applied, [StringComparer]::Ordinal)
    [pscustomobject]@{
        Package = [string]$facts.package
        VersionName = [string]$facts.versionName
        VersionCode = [string]$facts.versionCode
        Sha256 = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
        SignerSha256 = Get-ApkSignerDigest -Tools $Tools -Apk $Apk
        BuildIdentity = Get-ApkBuildIdentity -Apk $Apk
        SelectionSha256 = Get-NativeRunnerSha256Text ($applied -join "`n")
        AppliedPatches = $applied.Count
    }
}

function New-NativeRunnerApk {
    <#
    .SYNOPSIS
        Compile, dex, link, align and sign the runner, pointed at the target package.
    .DESCRIPTION
        Passwords reach apksigner through environment variables, never its command line. An empty
        password has nothing to hide and goes as pass:.
    #>
    param(
        $Tools,
        [string]$TargetPackage,
        [string]$WorkDirectory,
        [string]$Keystore,
        [string]$KeyAlias,
        [AllowEmptyString()][string]$KeystorePassword,
        [AllowEmptyString()][string]$KeyPassword,
        [string]$KeystoreType,
        [string]$ProviderClass,
        [string]$ProviderJar,
        [string]$Source = (Join-Path $PSScriptRoot 'native-runner')
    )
    # Java resolves relative paths against its own working directory, not this session's location.
    $Keystore = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Keystore)
    if (-not (Test-Path -LiteralPath $Keystore -PathType Leaf)) { throw "No keystore at $Keystore." }
    if ($ProviderClass -and -not $ProviderJar) { throw '-ProviderClass needs -ProviderJar, the jar that holds it.' }
    if ($ProviderJar) {
        $ProviderJar = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ProviderJar)
        if (-not (Test-Path -LiteralPath $ProviderJar -PathType Leaf)) { throw "No provider jar at $ProviderJar." }
    }
    $classes = Join-Path $WorkDirectory 'classes'
    $dex = Join-Path $WorkDirectory 'dex'
    New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $Source 'src') -Recurse -File -Filter '*.java' | ForEach-Object FullName)
    if ($sources.Count -eq 0) { throw "No runner sources under $Source." }
    [void](Invoke-NativeRunnerTool -Executable $Tools.Javac -Arguments (@('-source', '8', '-target', '8', '-Xlint:-options',
        '-encoding', 'UTF-8', '-bootclasspath', $Tools.AndroidJar, '-d', $classes) + $sources) -Failure 'javac could not compile the runner')
    $classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -File -Filter '*.class' | ForEach-Object FullName)
    $prefix = [IO.Path]::GetFullPath($classes).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $foreign = @($classFiles | Where-Object { -not $_.Substring($prefix.Length).Replace('\', '/').StartsWith('app/hushgram/nativerunner/') })
    if ($foreign.Count) { throw "The runner may only hold its own package, and it compiled $($foreign.Count) class(es) outside it." }
    [void](Invoke-NativeRunnerTool -Executable $Tools.Java -Arguments (@('-cp', $Tools.D8Jar, 'com.android.tools.r8.D8',
        '--release', '--min-api', '28', '--lib', $Tools.AndroidJar, '--output', $dex) + $classFiles) -Failure 'd8 could not dex the runner')
    $dexFiles = @(Get-ChildItem -LiteralPath $dex -File -Filter '*.dex')
    if ($dexFiles.Count -ne 1 -or $dexFiles[0].Name -cne 'classes.dex') { throw 'd8 did not produce exactly one classes.dex.' }

    $unsigned = Join-Path $WorkDirectory 'runner-unsigned.apk'
    $aligned = Join-Path $WorkDirectory 'runner-aligned.apk'
    $signed = Join-Path $WorkDirectory 'runner.apk'
    $link = @('link', '-o', $unsigned, '-I', $Tools.AndroidJar, '--manifest', (Join-Path $Source 'AndroidManifest.xml'),
        '--min-sdk-version', '28', '--target-sdk-version', '34', '--version-code', '1', '--version-name', '1')
    if ($TargetPackage -cne 'com.instagram.android') { $link += @('--rename-instrumentation-target-package', $TargetPackage) }
    [void](Invoke-NativeRunnerTool -Executable $Tools.Aapt2 -Arguments $link -Failure 'aapt2 could not link the runner')
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
    try { [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dexFiles[0].FullName, 'classes.dex') }
    finally { $zip.Dispose() }
    [void](Invoke-NativeRunnerTool -Executable $Tools.Zipalign -Arguments @('-f', '-p', '4', $unsigned, $aligned) -Failure 'zipalign failed')

    $classPath = if ($ProviderJar) { $Tools.ApkSignerJar + [IO.Path]::PathSeparator + $ProviderJar } else { $Tools.ApkSignerJar }
    $sign = @('-cp', $classPath, 'com.android.apksigner.ApkSignerTool', 'sign', '--ks', $Keystore, '--ks-key-alias', $KeyAlias)
    if ($KeystoreType) { $sign += @('--ks-type', $KeystoreType) }
    if ($ProviderClass) { $sign += @('--ks-provider-class', $ProviderClass) }
    $storeVariable = 'HUSHGRAM_NATIVE_RUNNER_STORE_PASSWORD'
    $keyVariable = 'HUSHGRAM_NATIVE_RUNNER_KEY_PASSWORD'
    try {
        if ([string]::IsNullOrEmpty($KeystorePassword)) { $sign += @('--ks-pass', 'pass:') }
        else { [Environment]::SetEnvironmentVariable($storeVariable, $KeystorePassword, 'Process'); $sign += @('--ks-pass', "env:$storeVariable") }
        if ([string]::IsNullOrEmpty($KeyPassword)) { $sign += @('--key-pass', 'pass:') }
        else { [Environment]::SetEnvironmentVariable($keyVariable, $KeyPassword, 'Process'); $sign += @('--key-pass', "env:$keyVariable") }
        $sign += @('--out', $signed, $aligned)
        [void](Invoke-NativeRunnerTool -Executable $Tools.Java -Arguments $sign -Failure 'apksigner could not sign the runner')
    } finally {
        [Environment]::SetEnvironmentVariable($storeVariable, $null, 'Process')
        [Environment]::SetEnvironmentVariable($keyVariable, $null, 'Process')
    }
    $signed
}

function ConvertFrom-InstrumentOutput {
    <#
    .SYNOPSIS
        am instrument -r output as its result and status bundles, result code and start failure.
    .DESCRIPTION
        A value with a newline continues on lines that carry no INSTRUMENTATION_ prefix, so those
        are joined back onto the key before them. Carriage returns and the blank lines adb can end
        its output with never become part of a value.
    #>
    param([string[]]$Lines)
    $results = [ordered]@{}
    $status = [ordered]@{}
    $code = $null
    $failed = $null
    $openTable = $null
    $openKey = $null
    foreach ($raw in @($Lines)) {
        $line = ([string]$raw).TrimEnd("`r")
        if ($line.Length -eq 0) { continue }
        $pair = [regex]::Match($line, '^INSTRUMENTATION_(RESULT|STATUS): ([^=]+)=(.*)$')
        if ($pair.Success) {
            $openTable = if ($pair.Groups[1].Value -ceq 'RESULT') { $results } else { $status }
            $openKey = $pair.Groups[2].Value
            $openTable[$openKey] = $pair.Groups[3].Value
            continue
        }
        $resultCode = [regex]::Match($line, '^INSTRUMENTATION_CODE: (-?\d+)$')
        $failure = [regex]::Match($line, '^INSTRUMENTATION_FAILED: (.*)$')
        if ($resultCode.Success) { $code = [int]$resultCode.Groups[1].Value }
        elseif ($failure.Success) { $failed = $failure.Groups[1].Value }
        elseif ($null -ne $openTable -and $line -cnotlike 'INSTRUMENTATION_*') {
            $openTable[$openKey] = [string]$openTable[$openKey] + "`n" + $line
            continue
        }
        $openTable = $null
    }
    [pscustomobject]@{ Code = $code; Results = $results; Status = $status; Failed = $failed }
}

function Test-NativeRunnerBinding {
    <#
    .SYNOPSIS
        Every difference between what the runner measured in the target's process and the target
        APK it was given, as sentences. None means the case ran against that APK.
    #>
    param($Target, $Results, [string]$User)
    $expected = [ordered]@{
        package = $Target.Package
        versionName = $Target.VersionName
        versionCode = $Target.VersionCode
        signerSha256 = $Target.SignerSha256
        baseApkSha256 = $Target.Sha256
        user = $User
        process = $Target.Package
        # Measured in the target's own uid, after its providers started.
        network = 'none'
    }
    foreach ($key in $expected.Keys) {
        $actual = [string]$Results["hushgram.$key"]
        if ($actual -cne [string]$expected[$key]) { "the process reported $key '$actual', the target is '$($expected[$key])'" }
    }
}

function Get-NativeRunnerCaseVerdict {
    <#
    .SYNOPSIS
        One case's verdict: pass, fail, needs-session or error, and why.
    .DESCRIPTION
        A start failure, a crash, a result for another case or any binding difference is an error,
        whatever the runner said. Only then does the runner's own verdict count, and it has to agree
        with the result code. The positive control also has to answer the target's build identity.
        needs-session is a case that can only be judged signed in. It names that prerequisite and
        never counts as a pass.
    #>
    param($Parsed, $Target, [string]$User, [string]$Case, [int]$ExitCode, [string[]]$Output, [int]$TimeoutSeconds)
    $results = $Parsed.Results
    $problems = [System.Collections.Generic.List[string]]::new()
    if ($Parsed.Failed) {
        $problems.Add("the instrumentation did not start ($($Parsed.Failed)): $($Parsed.Status['Error'])")
    } elseif ($TimeoutSeconds -gt 0 -and $ExitCode -eq 124) {
        $problems.Add("the case was still running after $TimeoutSeconds seconds and was stopped")
    } elseif ($null -eq $Parsed.Code) {
        $problems.Add("the instrumentation ended without a result code (exit $ExitCode): $((@($Output) | Select-Object -Last 3) -join '; ')")
    } elseif ($results['shortMsg']) {
        $problems.Add("the target process failed: $($results['shortMsg'])")
    } else {
        if ([string]$results['hushgram.case'] -cne $Case) { $problems.Add("the runner answered for case '$($results['hushgram.case'])'") }
        foreach ($difference in @(Test-NativeRunnerBinding -Target $Target -Results $results -User $User)) { $problems.Add($difference) }
    }
    $verdict = [string]$results['hushgram.verdict']
    if ($problems.Count -eq 0 -and $verdict -cnotin @('pass', 'fail', 'needs-session', 'error')) { $problems.Add("the runner gave no known verdict ('$verdict')") }
    if ($problems.Count -eq 0 -and $verdict -ceq 'needs-session' -and [string]::IsNullOrWhiteSpace([string]$results['hushgram.prerequisite'])) {
        $problems.Add('the runner said a session is needed without naming what')
    }
    if ($problems.Count -eq 0 -and (($verdict -ceq 'pass') -ne ($Parsed.Code -eq -1))) {
        $problems.Add("the verdict '$verdict' disagrees with result code $($Parsed.Code)")
    }
    if ($problems.Count) {
        $verdict = 'error'
    } elseif ($Case -ceq 'control-positive' -and $verdict -ceq 'pass' -and [string]$results['hushgram.buildIdentity'] -cne $Target.BuildIdentity) {
        $verdict = 'fail'
        $problems.Add("the extension answered build identity '$($results['hushgram.buildIdentity'])', the target APK carries '$($Target.BuildIdentity)'")
    }
    $observed = [ordered]@{}
    foreach ($key in @($results.Keys)) {
        $name = [string]$key
        if ($name.StartsWith('hushgram.', [StringComparison]::Ordinal) -and $name.Substring(9) -cnotin $NativeRunnerResultKeys) {
            $observed[$name.Substring(9)] = [string]$results[$key]
        }
    }
    [pscustomobject]@{
        Case = $Case
        Verdict = $verdict
        MissingHook = [string]$results['hushgram.missingHook']
        Prerequisite = [string]$results['hushgram.prerequisite']
        BuildIdentity = [string]$results['hushgram.buildIdentity']
        Error = [string]$results['hushgram.error']
        Detail = [string]$results['hushgram.detail']
        Observed = $observed
        Problems = @($problems)
    }
}

function Test-NativeRunnerControls {
    <#
    .SYNOPSIS
        Whether the verdicts prove both controls: the positive one passed and the negative one
        failed on the absent hook alone.
    #>
    param($Verdicts)
    $positive = @($Verdicts | Where-Object Case -ceq 'control-positive')
    $negative = @($Verdicts | Where-Object Case -ceq 'control-negative')
    $positive.Count -eq 1 -and $positive[0].Verdict -ceq 'pass' -and
        $negative.Count -eq 1 -and $negative[0].Verdict -ceq 'fail' -and $negative[0].MissingHook -ceq $NativeRunnerAbsentHook
}

function Invoke-NativeRunnerAdb {
    param([string]$Adb, $DeviceLease, [scriptblock]$AdbInvoker, [string[]]$Arguments, [string]$Failure)
    $result = Invoke-HushgramAdbCommand -Adb $Adb -Arguments (@('-s', $DeviceLease.Record.serial) + $Arguments) `
        -Invoker $AdbInvoker -DeviceLease $DeviceLease
    if ($Failure -and $result.ExitCode -ne 0) { throw (Format-HushgramAdbFailure -Message $Failure -Result $result) }
    $result
}

function Get-NativeRunnerPhoneRefusal {
    param([string]$Serial)
    "$Serial is not an emulator. Instagram's providers start before the runner does, and a phone's network can't be cut for the test user alone, so the runner only runs on an emulator with the network off."
}

function Invoke-NativeRunnerSession {
    <#
    .SYNOPSIS
        Run the controls on a verified, leased device, then the feature cases once both controls
        hold, and remove everything the run created.
    .DESCRIPTION
        Before anything is created, the installed target must be one APK whose bytes hash to the
        target's, and no runner package or test user may be left from another run. Cases run in a
        new Android user that has never signed in, which takes the installed target with
        install-existing. Only that user and the runner package are removed afterwards; the target
        and its data are never touched. The network is off, and the host has seen the default
        network go away, before that user exists, so nothing the target's providers or the new
        user's system apps start can send. Each case has CaseTimeoutSeconds to finish, then the
        target is stopped in that user and no later case runs. Background ANRs are shown for the
        run and put back as they were afterwards.

        Emulators only: a phone's network can't be cut for one user, and the target's providers
        run before the runner does.
    #>
    param([string]$Adb, $DeviceLease, [string]$RunnerApk, $Target, [scriptblock]$AdbInvoker,
        [int]$CaseTimeoutSeconds = 120, [int]$NetworkWaitSeconds = 15)
    $call = @{ Adb = $Adb; DeviceLease = $DeviceLease; AdbInvoker = $AdbInvoker }
    $serial = $DeviceLease.Record.serial
    if (-not $serial.StartsWith('emulator-', [StringComparison]::Ordinal)) { throw (Get-NativeRunnerPhoneRefusal $serial) }

    $earlier = Invoke-NativeRunnerAdb @call -Arguments @('shell', 'dumpsys', 'package', $NativeRunnerPackage) -Failure 'Could not check for an earlier runner package'
    if (@($earlier.Stdout | Where-Object { $_ -match "^\s*Package \[$([regex]::Escape($NativeRunnerPackage))\]" }).Count) {
        throw "$NativeRunnerPackage is already on $serial, maybe from another run. Once nothing is using it, remove it with: adb -s $serial uninstall $NativeRunnerPackage"
    }
    $users = Invoke-NativeRunnerAdb @call -Arguments @('shell', 'pm', 'list', 'users') -Failure 'Could not list the Android users'
    $leftover = @($users.Stdout | ForEach-Object { [regex]::Match($_, "UserInfo\{(\d+):$([regex]::Escape($NativeRunnerUserName)):") } | Where-Object Success)
    if ($leftover.Count) {
        throw "User $($leftover[0].Groups[1].Value) ($NativeRunnerUserName) is already on $serial, maybe from another run. Once nothing is using it, remove it with: adb -s $serial shell pm remove-user $($leftover[0].Groups[1].Value)"
    }
    $paths = Invoke-NativeRunnerAdb @call -Arguments @('shell', 'pm', 'path', $Target.Package) -Failure "Could not find $($Target.Package) on $serial"
    $apkPaths = @($paths.Stdout | Where-Object { $_ -clike 'package:*' } | ForEach-Object { $_.Substring(8).Trim() })
    if ($apkPaths.Count -ne 1 -or $apkPaths[0] -notlike '*/base.apk') {
        throw "$($Target.Package) on $serial is installed as $($apkPaths.Count) APK file(s), not one patched APK."
    }
    $hash = Invoke-NativeRunnerAdb @call -Arguments @('shell', 'sha256sum', $apkPaths[0]) -Failure 'Could not hash the installed APK'
    $installedSha = ((@($hash.Stdout) -join ' ').Trim() -split '\s+')[0]
    if ($installedSha -cne $Target.Sha256) {
        throw "The $($Target.Package) installed on $serial isn't the target APK: it hashes to $installedSha, the target to $($Target.Sha256)."
    }
    $limit = Invoke-NativeRunnerAdb @call -Arguments @('shell', 'pm', 'get-max-users') -Failure 'Could not read the Android user limit'
    # Android 17 words it "Maximum supported switchable users: 4".
    $maximum = [regex]::Match((@($limit.Stdout) -join ' '), 'Maximum supported (?:switchable )?users: (\d+)')
    if (-not ($maximum.Success -and [int]$maximum.Groups[1].Value -ge 2)) {
        throw "$serial supports one Android user, so there is no signed-out user to run in."
    }

    $airplaneBefore = $null
    $anrBefore = $null
    $anrRaised = $false
    $networkOff = $false
    $createdUser = $null
    $runnerAttempted = $false
    $runnerInstalled = $false
    $user = $null
    $verdicts = [System.Collections.Generic.List[object]]::new()
    $primary = $null
    try {
        $airplane = Invoke-NativeRunnerAdb @call -Arguments @('shell', 'cmd', 'connectivity', 'airplane-mode') -Failure 'Could not read airplane mode'
        $airplaneBefore = (@($airplane.Stdout) -join '').Trim()
        if ($airplaneBefore -cnotin @('enabled', 'disabled')) { throw "Airplane mode answered '$airplaneBefore'." }
        if ($airplaneBefore -ceq 'disabled') {
            [void](Invoke-NativeRunnerAdb @call -Arguments @('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable') -Failure 'Could not turn the network off')
        }
        # Airplane mode can leave a network up (an emulator's wifi can stay on), so the system's own
        # default network has to be gone before anything of the target starts.
        $deadline = [DateTime]::UtcNow.AddSeconds($NetworkWaitSeconds)
        $defaultNetwork = $null
        while ($true) {
            $connectivity = Invoke-NativeRunnerAdb @call -Arguments @('shell', 'dumpsys', 'connectivity') -Failure 'Could not read the network state'
            $line = [regex]::Match((@($connectivity.Stdout) -join "`n"), '(?m)^\s*Active default network: (\S+)')
            $defaultNetwork = if ($line.Success) { $line.Groups[1].Value } else { '(not shown)' }
            if ($defaultNetwork -ceq 'none' -or [DateTime]::UtcNow -ge $deadline) { break }
            Start-Sleep -Milliseconds 500
        }
        if ($defaultNetwork -cne 'none') {
            throw "The network on $serial is still up with airplane mode on (default network $defaultNetwork), so the runner won't start the target there."
        }
        $networkOff = $true

        # The new user's first target start unpacks Instagram's native libraries on the main thread,
        # which under ARM translation can outlast the bind's startup timeout. With background ANRs
        # silent the system kills that process and the case reads "Process crashed"; shown, the
        # process waits behind the ANR and the case runs.
        $anr = Invoke-NativeRunnerAdb @call -Arguments @('shell', 'settings', 'get', 'secure', 'anr_show_background') -Failure 'Could not read whether background ANRs are shown'
        $anrBefore = (@($anr.Stdout) -join '').Trim()
        if ($anrBefore -cnotmatch '^(null|-?\d{1,9})$') { throw "Showing background ANRs answered '$anrBefore'." }
        if ($anrBefore -ceq 'null' -or [int]$anrBefore -eq 0) {
            $anrRaised = $true
            [void](Invoke-NativeRunnerAdb @call -Arguments @('shell', 'settings', 'put', 'secure', 'anr_show_background', '1') -Failure 'Could not show background ANRs')
        }

        $created = Invoke-NativeRunnerAdb @call -Arguments @('shell', 'pm', 'create-user', $NativeRunnerUserName)
        $newUser = [regex]::Match((@($created.Stdout) -join ' '), 'Success: created user id (\d+)')
        if ($newUser.Success) {
            $createdUser = $newUser.Groups[1].Value
        } else {
            # A user can exist even when pm's answer doesn't say so. Find it so cleanup removes it.
            $notCreated = Format-HushgramAdbFailure -Message 'The test user was not created' -Result $created
            try {
                $after = Invoke-NativeRunnerAdb @call -Arguments @('shell', 'pm', 'list', 'users')
                $made = @($after.Stdout | ForEach-Object { [regex]::Match($_, "UserInfo\{(\d+):$([regex]::Escape($NativeRunnerUserName)):") } | Where-Object Success)
                if ($made.Count) { $createdUser = $made[0].Groups[1].Value }
            } catch {
                $notCreated += " Listing the users afterwards failed too ($($_.Exception.Message)), so check for a '$NativeRunnerUserName' user on $serial."
            }
            throw $notCreated
        }
        $user = $createdUser
        [void](Invoke-NativeRunnerAdb @call -Arguments @('shell', 'am', 'start-user', '-w', $user) -Failure "Could not start user $user")
        $existing = Invoke-NativeRunnerAdb @call -Arguments @('shell', 'pm', 'install-existing', '--user', $user, $Target.Package) -Failure "Could not add $($Target.Package) to user $user"
        if (-not ((@($existing.Stdout) -join ' ') -match 'installed for user')) { throw (Format-HushgramAdbFailure -Message "$($Target.Package) was not added to user $user" -Result $existing) }
        # Android 13 and later post nothing for an app without this, and the new user never granted it.
        # Older releases have no such permission, so pm would refuse the grant.
        $sdk = Invoke-NativeRunnerAdb @call -Arguments @('shell', 'getprop', 'ro.build.version.sdk') -Failure "Could not read the Android version of $serial"
        $sdkLevel = 0
        if (-not [int]::TryParse((@($sdk.Stdout) -join '').Trim(), [ref]$sdkLevel)) { throw (Format-HushgramAdbFailure -Message "$serial gave no Android version" -Result $sdk) }
        if ($sdkLevel -ge 33) {
            [void](Invoke-NativeRunnerAdb @call -Arguments @('shell', 'pm', 'grant', '--user', $user, $Target.Package, 'android.permission.POST_NOTIFICATIONS') `
                -Failure "Could not let $($Target.Package) post notifications in user $user")
        }
        $runnerAttempted = $true
        [void](Invoke-NativeRunnerAdb @call -Arguments @('install', '--user', $user, $RunnerApk) -Failure 'Could not install the runner')
        $runnerInstalled = $true
        # One case in a new target process. False once a case ran out of time, which ends the run.
        $runCase = {
            param([string]$Case)
            $run = Invoke-NativeRunnerAdb @call -Arguments @('shell', 'timeout', [string]$CaseTimeoutSeconds, 'am', 'instrument', '-w', '-r',
                '--user', $user, '-e', 'case', $Case, "$NativeRunnerPackage/.Runner")
            $verdicts.Add((Get-NativeRunnerCaseVerdict -Parsed (ConvertFrom-InstrumentOutput $run.Stdout) -Target $Target -User $user -Case $Case `
                -ExitCode $run.ExitCode -Output @($run.Stdout) -TimeoutSeconds $CaseTimeoutSeconds))
            if ($run.ExitCode -ne 124) { return $true }
            # timeout ends am's wait, not the instrumentation, which still runs in the target.
            [void](Invoke-NativeRunnerAdb @call -Arguments @('shell', 'am', 'force-stop', '--user', $user, $Target.Package) -Failure "Could not stop $($Target.Package) in user $user")
            $false
        }
        $going = $true
        foreach ($case in $NativeRunnerControlCases) {
            $going = & $runCase $case
            # A binding that doesn't hold makes every later verdict meaningless.
            if ($going -and $case -ceq 'bind' -and $verdicts[-1].Verdict -cne 'pass') { $going = $false }
            if (-not $going) { break }
        }
        if ($going -and (Test-NativeRunnerControls $verdicts)) {
            foreach ($case in $NativeRunnerFeatureCases.Keys) {
                $basis = $NativeRunnerFeatureCases[$case]
                if ($basis -and @($verdicts | Where-Object { $_.Case -ceq $basis -and $_.Verdict -ceq 'pass' }).Count -eq 0) {
                    $verdicts.Add([pscustomobject]@{ Case = $case; Verdict = 'skipped'; MissingHook = ''; Prerequisite = ''; BuildIdentity = ''
                        Error = ''; Detail = ''; Observed = [ordered]@{}; Problems = @("it builds on $basis, which did not pass") })
                    continue
                }
                if (-not (& $runCase $case)) { break }
            }
        }
    } catch {
        $primary = $_
        throw
    } finally {
        $cleanupErrors = [System.Collections.Generic.List[string]]::new()
        $cleanup = @()
        # The runner went into the test user alone, so it comes out of that user alone.
        if ($runnerAttempted) { $cleanup += , @(@('shell', 'pm', 'uninstall', '--user', $createdUser, $NativeRunnerPackage), "remove $NativeRunnerPackage", $runnerInstalled) }
        if ($createdUser) { $cleanup += , @(@('shell', 'pm', 'remove-user', $createdUser), "remove user $createdUser", $true) }
        if ($anrRaised) {
            $anrBack = if ($anrBefore -ceq 'null') { @('shell', 'settings', 'delete', 'secure', 'anr_show_background') } else { @('shell', 'settings', 'put', 'secure', 'anr_show_background', $anrBefore) }
            $cleanup += , @($anrBack, 'put back whether background ANRs are shown', $true)
        }
        if ($airplaneBefore -ceq 'disabled') { $cleanup += , @(@('shell', 'cmd', 'connectivity', 'airplane-mode', 'disable'), 'turn the network back on', $true) }
        foreach ($step in $cleanup) {
            try {
                $done = Invoke-NativeRunnerAdb @call -Arguments $step[0]
                if ($done.ExitCode -ne 0 -and $step[2]) { $cleanupErrors.Add((Format-HushgramAdbFailure -Message "Could not $($step[1]) on $serial" -Result $done)) }
            } catch { $cleanupErrors.Add("Could not $($step[1]) on ${serial}: $($_.Exception.Message)") }
        }
        if ($cleanupErrors.Count) {
            if ($primary) { Write-Warning ("Runner cleanup also failed: " + ($cleanupErrors -join ' ')) }
            else { throw ("Runner cleanup failed: " + ($cleanupErrors -join ' ')) }
        }
    }
    $controlsProven = Test-NativeRunnerControls $verdicts
    $features = @($verdicts | Where-Object { $NativeRunnerFeatureCases.Contains($_.Case) })
    [pscustomobject]@{
        Serial = $serial
        User = $user
        IsolatedUser = [bool]$createdUser
        NetworkOff = $networkOff
        Target = $Target
        Cases = @($verdicts)
        ControlsProven = $controlsProven
        # Every feature case ran and passed. needs-session counts against it: it was never judged.
        FeaturesPassed = $controlsProven -and $features.Count -eq $NativeRunnerFeatureCases.Count -and
            @($features | Where-Object Verdict -cne 'pass').Count -eq 0
    }
}

function Invoke-NativeRunner {
    <#
    .SYNOPSIS
        A whole run: read the target, build and check the runner, gate an install, take the
        device, run the cases and write the verdict file.
    .DESCRIPTION
        A verdict file already at -Verdict is removed before anything else, so a run that stops
        early never leaves an older verdict standing for this target. The runner's signer is
        checked, and with -Install the target held to injected-mutation-contracts.txt, before the
        device is leased. The target installs as an update (adb install -r), so Android refuses a
        different signer, and nothing is ever uninstalled to get past that.

        Signing holds New-NativeRunnerApk's key parameters. TargetReader, RunnerBuilder,
        RegisterGate and AdbInvoker stand in for the real steps in tests.
    #>
    param(
        $Tools,
        [string]$TargetApk,
        [string]$Report,
        [string]$Record,
        [string]$Verdict,
        [string]$Serial,
        [string]$ExpectedModel,
        [string]$ExpectedAvd,
        [switch]$Install,
        [hashtable]$Signing = @{},
        [string]$LeaseDirectory,
        [scriptblock]$TargetReader,
        [scriptblock]$RunnerBuilder,
        [scriptblock]$RegisterGate,
        [scriptblock]$AdbInvoker
    )
    $TargetApk = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($TargetApk)
    if (-not $Verdict) { $Verdict = Join-Path (Split-Path -Parent $TargetApk) 'native-runner-verdict.json' }
    $Verdict = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Verdict)
    if (Test-Path -LiteralPath $Verdict) { Remove-Item -LiteralPath $Verdict -Force }
    # Before the lease and any install: a phone never gets the target or the runner.
    if (-not $Serial.StartsWith('emulator-', [StringComparison]::Ordinal)) { throw (Get-NativeRunnerPhoneRefusal $Serial) }
    if (-not $TargetReader) {
        $TargetReader = { param($Tools, $Apk, $Report, $Record) Get-NativeRunnerTarget -Tools $Tools -Apk $Apk -Report $Report -Record $Record }
    }
    if (-not $RunnerBuilder) {
        $RunnerBuilder = { param($Tools, $Target, $WorkDirectory, $Signing) New-NativeRunnerApk -Tools $Tools -TargetPackage $Target.Package -WorkDirectory $WorkDirectory @Signing }
    }
    if (-not $RegisterGate) {
        $gateScript = Join-Path $PSScriptRoot 'verify-injected-registers.ps1'
        $RegisterGate = {
            param($Tools, $Apk)
            $global:LASTEXITCODE = -1
            & $gateScript -PatchedApk $Apk -Java $Tools.Java -Aapt2 $Tools.Aapt2 | Out-Host
            $LASTEXITCODE -eq 0
        }.GetNewClosure()
    }

    $target = & $TargetReader $Tools $TargetApk $Report $Record
    Write-Host "[native] target $($target.Package) $($target.VersionName) ($($target.VersionCode)), $($target.AppliedPatches) patches, $($target.BuildIdentity)"
    $work = Join-Path ([IO.Path]::GetTempPath()) ('hushgram-native-runner-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    try {
        $runnerApk = & $RunnerBuilder $Tools $target $work $Signing
        $runnerSigner = Get-ApkSignerDigest -Tools $Tools -Apk $runnerApk
        if ($runnerSigner -cne $target.SignerSha256) {
            throw "The runner is signed by $runnerSigner and the target by $($target.SignerSha256). Android runs an instrumentation only inside an app with its signer, so sign the runner with the target's key."
        }
        if ($Install) {
            Write-Host '[native] holding the target to the injected mutation contracts before it is installed'
            if (-not (& $RegisterGate $Tools $TargetApk)) { throw 'verify-injected-registers.ps1 failed, so the target was not installed.' }
        }
        $leaseArguments = @{ Serial = $Serial; ExpectedModel = $ExpectedModel; ExpectedAvd = $ExpectedAvd }
        if ($LeaseDirectory) { $leaseArguments.Directory = $LeaseDirectory }
        $deviceLease = Enter-HushgramDeviceLease @leaseArguments
        try {
            Confirm-HushgramDevice -Adb $Tools.Adb -DeviceLease $deviceLease -AdbInvoker $AdbInvoker
            if ($Install) {
                $installed = Invoke-NativeRunnerAdb -Adb $Tools.Adb -DeviceLease $deviceLease -AdbInvoker $AdbInvoker -Arguments @('install', '-r', $TargetApk)
                if ($installed.ExitCode -ne 0) {
                    throw (Format-HushgramAdbFailure -Message "The target did not install on $Serial, and the installed app and its data were kept" -Result $installed)
                }
            }
            $session = Invoke-NativeRunnerSession -Adb $Tools.Adb -DeviceLease $deviceLease -RunnerApk $runnerApk -Target $target -AdbInvoker $AdbInvoker
        } finally {
            Exit-HushgramDeviceLease $deviceLease
        }
    } finally {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }
    $written = [ordered]@{
        timeUtc = [DateTime]::UtcNow.ToString('o')
        serial = $session.Serial
        user = $session.User
        isolatedUser = $session.IsolatedUser
        networkOff = $session.NetworkOff
        target = $session.Target
        cases = $session.Cases
        controlsProven = $session.ControlsProven
        featuresPassed = $session.FeaturesPassed
    }
    [IO.File]::WriteAllText($Verdict, ($written | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    $session | Add-Member -NotePropertyName VerdictPath -NotePropertyValue $Verdict -PassThru
}
