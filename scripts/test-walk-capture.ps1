<#
.SYNOPSIS
    Verify walk capture staging and leased ADB transport without a device.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    The push gate starts every suite with -Root; this one reads the scripts beside it.
#>
[CmdletBinding()]
param([switch]$Build, [string]$Sdk, [string]$Java, [string]$Root)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'walk-capture.ps1')
$script:checks = 0

function Assert-CaptureTest {
    param([bool]$Condition, [string]$Message)
    $script:checks++
    if (-not $Condition) { throw $Message }
}

function Assert-CaptureRejected {
    param([scriptblock]$Action, [string]$Expected)
    $failure = $null
    try { & $Action | Out-Null } catch { $failure = $_.Exception.Message }
    Assert-CaptureTest ($null -ne $failure -and $failure -like $Expected) "Expected '$Expected', received '$failure'."
}

$work = Join-Path ([IO.Path]::GetTempPath()) ('hushgram-walk-capture-' + [guid]::NewGuid().ToString('N'))
$lease = $null
$nativeBuilder = (Get-Command New-NativeRunnerApk).ScriptBlock
$run = 'abcdef0123456789abcdef0123456789'
$capture = 'home-01'
$package = 'com.instagram.android'
Add-Type -AssemblyName System.Drawing
$bitmap = [Drawing.Bitmap]::new(2, 3)
$pngStream = [IO.MemoryStream]::new()
try {
    $bitmap.SetPixel(0, 0, [Drawing.Color]::Red)
    $bitmap.SetPixel(1, 2, [Drawing.Color]::Blue)
    $bitmap.Save($pngStream, [Drawing.Imaging.ImageFormat]::Png)
    [byte[]]$pngBytes = $pngStream.ToArray()
} finally { $bitmap.Dispose(); $pngStream.Dispose() }

function New-CaptureFixture {
    $relative = "files/walk-captures/$run/$capture"
    $dataRoot = '/data/user/0/app.hushgram.walkcapture/'
    $xml = "<?xml version=`"1.0`" encoding=`"UTF-8`"?><hierarchy run-id=`"$run`" capture-id=`"$capture`" uptime-ms=`"1234`" window-id=`"42`"><node package=`"$package`" visible-to-user=`"true`" text=`"Capture &amp; verify`" /></hierarchy>`r`n"
    $after = $xml.Replace(' uptime-ms="1234"', ' uptime-ms="1235"')
    $state = [pscustomobject]@{
        Calls = [Collections.Generic.List[string]]::new()
        Files = @{ "$relative.xml" = [Text.Encoding]::UTF8.GetBytes($xml); "$relative-after.xml" = [Text.Encoding]::UTF8.GetBytes($after); "$relative.png" = $pngBytes.Clone() }
        Results = [ordered]@{ status = 'captured'; run_id = $run; capture_id = $capture; expected_package = $package
            ui_path = "$dataRoot$relative.xml"; after_ui_path = "$dataRoot$relative-after.xml"; image_path = "$dataRoot$relative.png"; uptime_ms = '1234' }
        Code = '-1'; InstrumentExit = 0; InstrumentFailure = $null; InstrumentStderr = @(); DuplicateField = $false; OmitCode = $false; LeaseToUnverify = $null
        HashReads = @{}; ChangedHash = $null; Transport = @{}; Base64 = @{}
        InstallOutput = @('Performing Streamed Install', 'Success'); InstallExit = 0
    }
    $invoker = {
        param([string]$Executable, [string[]]$Arguments)
        $command = @($Arguments[2..($Arguments.Count - 1)])
        $line = $command -join ' '
        $state.Calls.Add($line)
        $output = @()
        $stderr = @()
        $exitCode = 0
        if ($line.StartsWith('shell am instrument ')) {
            foreach ($key in $state.Results.Keys) { $output += "INSTRUMENTATION_RESULT: $key=$($state.Results[$key])" }
            if ($state.DuplicateField) { $output += "INSTRUMENTATION_RESULT: run_id=$run" }
            if ($null -ne $state.InstrumentFailure) { $output += "INSTRUMENTATION_FAILED: $($state.InstrumentFailure)" }
            if (-not $state.OmitCode) { $output += "INSTRUMENTATION_CODE: $($state.Code)" }
            $exitCode = $state.InstrumentExit
            $stderr = $state.InstrumentStderr
            if ($null -ne $state.LeaseToUnverify) { $state.LeaseToUnverify.Verified = $false }
        } elseif ($command.Count -eq 5 -and $command[0] -ceq 'shell' -and $command[1] -ceq 'run-as' -and $command[2] -ceq 'app.hushgram.walkcapture') {
            $file = $command[4]
            if (-not $state.Files.ContainsKey($file)) { throw "Unexpected capture filename $file." }
            if ($command[3] -ceq 'sha256sum') {
                $state.HashReads[$file] = [int]$state.HashReads[$file] + 1
                $hash = if ($state.ChangedHash -ceq $file -and $state.HashReads[$file] -gt 1) { 'f' * 64 } else { Get-WalkCaptureSha256 $state.Files[$file] }
                $output = @("$hash  $file")
            } elseif ($command[3] -ceq 'base64') {
                if ($state.Base64.ContainsKey($file)) { $output = @($state.Base64[$file]) }
                else {
                    $bytes = if ($state.Transport.ContainsKey($file)) { $state.Transport[$file] } else { $state.Files[$file] }
                    # Android's base64 wraps its output. Line joins must preserve the original bytes.
                    $encoded = [Convert]::ToBase64String($bytes)
                    $output = @([regex]::Matches($encoded, '.{1,76}') | ForEach-Object Value)
                }
            } else { throw "Unexpected run-as tool $line." }
        } elseif ($command[0] -ceq 'install') { $output = $state.InstallOutput; $exitCode = $state.InstallExit }
        else { throw "Unexpected or mutating ADB command $line." }
        [pscustomobject]@{ ExitCode = $exitCode; Output = @($output + $stderr); Stdout = $output }
    }.GetNewClosure()
    [pscustomobject]@{ State = $state; Invoker = $invoker; Relative = $relative }
}

function Invoke-CaptureFixture {
    param($Fixture, [string]$Suffix = 'result', $OwnedLease = $lease)
    Invoke-WalkCapture -Adb 'fixture-adb' -Serial 'fixture-capture' -RunId $run -CaptureId $capture -Package $package `
        -OutDir (Join-Path $work $Suffix) -DeviceLease $OwnedLease -AdbInvoker $Fixture.Invoker
}

try {
    [void][IO.Directory]::CreateDirectory($work)
    $lease = Enter-HushgramDeviceLease -Serial 'fixture-capture' -ExpectedModel 'FixtureCapture' -ChatIdentity 'HushGram/capture-tests' -Directory (Join-Path $work 'leases')
    $lease.Verified = $true

    # APK staging delegates every signing setting to the existing Java 8 / D8 builder.
    function New-NativeRunnerApk {
        param($Tools, $TargetPackage, $WorkDirectory, $Source, $Keystore, $KeyAlias, $KeystorePassword, $KeyPassword, $KeystoreType, $ProviderClass, $ProviderJar)
        $script:buildCall = @{} + $PSBoundParameters
        $apk = Join-Path $WorkDirectory 'runner.apk'
        [IO.File]::WriteAllBytes($apk, [byte[]]@(80, 75, 3, 4))
        $apk
    }
    $built = New-WalkCaptureApk -Tools ([pscustomobject]@{ Marker = 'sdk' }) -WorkDirectory (Join-Path $work 'staged') `
        -Keystore 'existing.p12' -KeyAlias 'capture' -KeystorePassword 'store-password' -KeyPassword 'entry-password' `
        -KeystoreType 'PKCS12' -ProviderClass 'example.Provider' -ProviderJar 'provider.jar'
    $staged = $script:buildCall
    $stagedJava = Join-Path $staged.Source 'src/app/hushgram/nativerunner/WalkSnapshot.java'
    Assert-CaptureTest ((Test-Path -LiteralPath $built) -and $staged.TargetPackage -ceq 'app.hushgram.walkcapture') 'The builder received the wrong target package.'
    Assert-CaptureTest ((Get-FileHash -LiteralPath $stagedJava).Hash -ceq (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'WalkSnapshot.java')).Hash) 'The builder did not receive the exact capture source.'
    Assert-CaptureTest ($staged.Keystore -ceq 'existing.p12' -and $staged.KeyAlias -ceq 'capture' -and $staged.KeystorePassword -ceq 'store-password' -and
        $staged.KeyPassword -ceq 'entry-password' -and $staged.KeystoreType -ceq 'PKCS12' -and $staged.ProviderClass -ceq 'example.Provider' -and $staged.ProviderJar -ceq 'provider.jar') 'A signing parameter was lost or substituted.'
    [xml]$manifest = Get-Content -LiteralPath (Join-Path $staged.Source 'AndroidManifest.xml') -Raw
    $android = 'http://schemas.android.com/apk/res/android'
    Assert-CaptureTest ($manifest.manifest.package -ceq 'app.hushgram.walkcapture' -and $manifest.manifest.application.GetAttribute('debuggable', $android) -ceq 'true' -and
        $manifest.manifest.application.GetAttribute('testOnly', $android) -ceq 'true') 'The capture app is not debuggable and test-only.'
    Assert-CaptureTest ($manifest.manifest.instrumentation.GetAttribute('name', $android) -ceq 'app.hushgram.nativerunner.WalkSnapshot' -and
        $manifest.manifest.instrumentation.GetAttribute('targetPackage', $android) -ceq 'app.hushgram.walkcapture' -and
        @($manifest.SelectNodes('//uses-permission|//activity|//service|//receiver|//provider|//intent-filter')).Count -eq 0) 'The capture manifest has a launcher, permission or foreign target.'
    Set-Item -LiteralPath Function:New-NativeRunnerApk -Value $nativeBuilder

    $valid = New-CaptureFixture
    $result = Invoke-CaptureFixture $valid
    Assert-CaptureTest ($result.RunId -ceq $run -and $result.CaptureId -ceq $capture -and $result.Package -ceq $package -and
        $result.Width -eq 2 -and $result.Height -eq 3 -and $result.UptimeMs -eq 1234) 'Capture identity, dimensions or uptime changed.'
    Assert-CaptureTest ($result.ParsedXml.DocumentElement.GetAttribute('run-id') -ceq $run -and $result.AfterParsedXml.DocumentElement.GetAttribute('capture-id') -ceq $capture) 'The parsed hierarchy was lost.'
    Assert-CaptureTest ($result.StartedUtc.EndsWith('Z') -and $result.FinishedUtc.EndsWith('Z') -and [DateTimeOffset]::Parse($result.FinishedUtc) -ge [DateTimeOffset]::Parse($result.StartedUtc)) 'Capture times are missing or out of order.'
    foreach ($kind in @('Ui', 'AfterUi', 'Image')) {
        $path = $result.PSObject.Properties[$kind + 'Path'].Value
        $hash = $result.PSObject.Properties[$kind + 'Sha256'].Value
        $extension = @{ Ui = '.xml'; AfterUi = '-after.xml'; Image = '.png' }[$kind]
        $original = $valid.State.Files[$valid.Relative + $extension]
        Assert-CaptureTest ([Convert]::ToBase64String([IO.File]::ReadAllBytes($path)) -ceq [Convert]::ToBase64String($original) -and $hash -ceq (Get-WalkCaptureSha256 $original)) "The exact $kind bytes and SHA-256 did not survive transport."
    }
    Assert-CaptureTest ($valid.State.Calls.Count -eq 10 -and $valid.State.Calls[0] -ceq "shell am instrument -w -r -e run_id $run -e capture_id $capture -e expected_package $package app.hushgram.walkcapture/app.hushgram.nativerunner.WalkSnapshot") 'The capture made unexpected ADB calls.'
    Assert-CaptureRejected { Invoke-CaptureFixture $valid } '*already exists*'
    Assert-CaptureTest ((Get-FileHash -LiteralPath $result.ImagePath).Hash.ToLowerInvariant() -ceq $result.ImageSha256) 'A repeated capture overwrote existing evidence.'

    # Exit zero alone, duplicated fields and mismatched result identities are refusals.
    $badResults = @(
        @{ Key = 'status'; Value = 'failed' }, @{ Key = 'status'; Value = '' },
        @{ Key = 'run_id'; Value = ('b' * 32) }, @{ Key = 'capture_id'; Value = 'other' },
        @{ Key = 'expected_package'; Value = 'com.example.other' }, @{ Key = 'uptime_ms'; Value = '-1' },
        @{ Key = 'uptime_ms'; Value = '9223372036854775808' },
        @{ Key = 'ui_path'; Value = '/sdcard/other.xml' },
        @{ Key = 'after_ui_path'; Value = "/data/user/10/app.hushgram.walkcapture/files/walk-captures/$run/$capture-after.xml" }
    )
    $caseIndex = 0
    foreach ($case in $badResults) {
        $fixture = New-CaptureFixture
        $fixture.State.Results[$case.Key] = $case.Value
        $caseIndex++
        Assert-CaptureRejected { Invoke-CaptureFixture $fixture "bad-result-$caseIndex" } '*'
        Assert-CaptureTest ($fixture.State.Calls.Count -eq 1) "A wrong $($case.Key) still read private payloads."
    }
    foreach ($failureCase in @('exit', 'code', 'missing-code', 'failed', 'duplicate')) {
        $fixture = New-CaptureFixture
        switch ($failureCase) {
            'exit' { $fixture.State.InstrumentExit = 1 }
            'code' { $fixture.State.Code = '0' }
            'missing-code' { $fixture.State.OmitCode = $true }
            'failed' { $fixture.State.InstrumentFailure = 'process crashed' }
            'duplicate' { $fixture.State.DuplicateField = $true }
        }
        Assert-CaptureRejected { Invoke-CaptureFixture $fixture "bad-instrument-$failureCase" } '*'
        Assert-CaptureTest ($fixture.State.Calls.Count -eq 1) "Failed instrumentation $failureCase still read payloads."
    }
    $fixture = New-CaptureFixture
    $fixture.State.Code = '0'
    $fixture.State.Results.status = 'failed'
    $fixture.State.Results.reason = 'IOException: The requested app is not the active visible window'
    $fixture.State.InstrumentStderr = @('fixture instrumentation stderr')
    Assert-CaptureRejected { Invoke-CaptureFixture $fixture 'failed-diagnostic' } '*result code 0*IOException*active visible window*Diagnostics:*'
    $diagnosticPath = Join-Path (Join-Path (Join-Path $work 'failed-diagnostic') $run) "$capture-instrumentation.txt"
    $diagnostic = [IO.File]::ReadAllText($diagnosticPath)
    Assert-CaptureTest ($diagnostic.Contains('INSTRUMENTATION_RESULT: reason=IOException: The requested app is not the active visible window') -and
        $diagnostic.Contains('INSTRUMENTATION_CODE: 0') -and $diagnostic.Contains('fixture instrumentation stderr') -and $fixture.State.Calls.Count -eq 1) 'Failed instrumentation did not preserve its raw stdout/stderr and reason.'
    Assert-CaptureRejected { Invoke-CaptureFixture $fixture 'failed-diagnostic' } '*already exists*'
    Assert-CaptureTest ([IO.File]::ReadAllText($diagnosticPath) -ceq $diagnostic -and $fixture.State.Calls.Count -eq 1) 'A repeated capture changed its diagnostic or reached ADB again.'
    $fixture.State.InstrumentStderr = @('x' * 100000)
    Assert-CaptureRejected { Invoke-CaptureFixture $fixture 'bounded-diagnostic' } '*result code 0*'
    $boundedPath = Join-Path (Join-Path (Join-Path $work 'bounded-diagnostic') $run) "$capture-instrumentation.txt"
    Assert-CaptureTest ((Get-Item -LiteralPath $boundedPath).Length -lt 64KB -and [IO.File]::ReadAllText($boundedPath).Contains('[Instrumentation output truncated]')) 'The instrumentation diagnostic was not bounded.'

    # Every payload is hashed before and after reading, and before any local file is written.
    foreach ($extension in @('.xml', '-after.xml', '.png')) {
        $fixture = New-CaptureFixture
        $fixture.State.ChangedHash = $fixture.Relative + $extension
        Assert-CaptureRejected { Invoke-CaptureFixture $fixture "changed-hash-$extension" } '*changed while reading*'
        $fixture = New-CaptureFixture
        $fixture.State.Transport[$fixture.Relative + $extension] = [byte[]]@(1, 2, 3)
        Assert-CaptureRejected { Invoke-CaptureFixture $fixture "changed-transport-$extension" } '*changed while reading*'
        $fixture = New-CaptureFixture
        $fixture.State.Base64[$fixture.Relative + $extension] = '***'
        Assert-CaptureRejected { Invoke-CaptureFixture $fixture "bad-base64-$extension" } '*malformed*base64*'
    }
    $badXml = @(
        @{ From = $run; To = ('b' * 32); Extension = '.xml' },
        @{ From = $capture; To = 'other'; Extension = '-after.xml' },
        @{ From = $package; To = 'com.example.other'; Extension = '.xml' },
        @{ From = 'window-id="42"'; To = 'window-id="43"'; Extension = '-after.xml' },
        @{ From = 'uptime-ms="1234"'; To = 'uptime-ms="1235"'; Extension = '.xml' },
        @{ From = 'uptime-ms="1235"'; To = 'uptime-ms="1233"'; Extension = '-after.xml' },
        @{ From = 'uptime-ms="1235"'; To = 'uptime-ms="601234"'; Extension = '-after.xml' },
        @{ From = 'uptime-ms="1235"'; To = ''; Extension = '-after.xml' },
        @{ From = 'visible-to-user="true"'; To = 'visible-to-user="false"'; Extension = '.xml' },
        @{ From = '</hierarchy>'; To = ''; Extension = '.xml' }
    )
    foreach ($case in $badXml) {
        $fixture = New-CaptureFixture
        $path = $fixture.Relative + $case.Extension
        $fixture.State.Files[$path] = [Text.Encoding]::UTF8.GetBytes([Text.Encoding]::UTF8.GetString($fixture.State.Files[$path]).Replace($case.From, $case.To))
        $caseIndex++
        Assert-CaptureRejected { Invoke-CaptureFixture $fixture "bad-xml-$caseIndex" } '*'
    }
    $fixture = New-CaptureFixture
    $fixture.State.Files[$fixture.Relative + '.xml'] = [Text.Encoding]::UTF8.GetBytes('<!DOCTYPE hierarchy [<!ENTITY bad SYSTEM "file:///missing">]><hierarchy>&bad;</hierarchy>')
    Assert-CaptureRejected { Invoke-CaptureFixture $fixture 'dtd' } '*XML is malformed*'
    foreach ($imageCase in @('empty', 'signature', 'dimension', 'truncated', 'crc', 'bad-data')) {
        $fixture = New-CaptureFixture
        $path = $fixture.Relative + '.png'
        switch ($imageCase) {
            'empty' { $fixture.State.Files[$path] = [byte[]]@() }
            'signature' { $fixture.State.Files[$path][0] = 0 }
            'dimension' { $fixture.State.Files[$path][16] = 127 }
            'truncated' { $fixture.State.Files[$path] = [byte[]]$fixture.State.Files[$path][0..55] }
            { $_ -in @('crc', 'bad-data') } {
                $idat = [Text.Encoding]::ASCII.GetString($fixture.State.Files[$path]).IndexOf('IDAT', [StringComparison]::Ordinal)
                if ($idat -lt 0) { throw 'The test PNG has no image data.' }
                $fixture.State.Files[$path][$idat + 4] = 0
                if ($imageCase -ceq 'bad-data') {
                    $length = [int][HushGramWalkPngCrc32]::Read($fixture.State.Files[$path], $idat - 4)
                    $crc = [HushGramWalkPngCrc32]::Compute($fixture.State.Files[$path], $idat, $length + 4)
                    for ($byteIndex = 0; $byteIndex -lt 4; $byteIndex++) {
                        $fixture.State.Files[$path][$idat + 4 + $length + $byteIndex] = [byte](($crc -shr (24 - 8 * $byteIndex)) -band 255)
                    }
                }
            }
        }
        Assert-CaptureRejected { Invoke-CaptureFixture $fixture "bad-image-$imageCase" } '*'
    }
    Assert-CaptureTest (@(Get-ChildItem -LiteralPath $work -Recurse -File | Where-Object { $_.Extension -in @('.xml', '.png') -and $_.FullName -match 'bad-|changed-|\\dtd\\' }).Count -eq 0) 'Rejected evidence left local capture files.'

    # The allowlist is case-sensitive and rejects separators, shell syntax and trailing newlines.
    foreach ($identity in @(
        @{ RunId = $run.ToUpperInvariant(); CaptureId = $capture; Package = $package },
        @{ RunId = "$run`n"; CaptureId = $capture; Package = $package },
        @{ RunId = $run; CaptureId = '../escape'; Package = $package },
        @{ RunId = $run; CaptureId = 'x;id'; Package = $package },
        @{ RunId = $run; CaptureId = $capture; Package = "$package`n" }
    )) {
        $fixture = New-CaptureFixture
        Assert-CaptureRejected { Invoke-WalkCapture -Adb 'fixture-adb' -Serial 'fixture-capture' @identity -OutDir $work -DeviceLease $lease -AdbInvoker $fixture.Invoker } '*capture needs*'
        Assert-CaptureTest ($fixture.State.Calls.Count -eq 0) 'An invalid identity reached ADB.'
    }
    $fixture = New-CaptureFixture
    Assert-CaptureRejected { Invoke-CaptureFixture $fixture -OwnedLease $null } '*DeviceLease*null*'
    Assert-CaptureTest ($fixture.State.Calls.Count -eq 0) 'An unleased capture reached ADB.'
    $lease.Verified = $false
    Assert-CaptureRejected { Invoke-CaptureFixture $fixture } '*Verify the leased device identity*'
    Assert-CaptureTest ($fixture.State.Calls.Count -eq 0) 'An unverified capture reached ADB.'
    $lease.Verified = $true
    $fixture = New-CaptureFixture
    $fixture.State.LeaseToUnverify = $lease
    Assert-CaptureRejected { Invoke-CaptureFixture $fixture 'lost-lease' } '*Verify the leased device identity*'
    Assert-CaptureTest ($fixture.State.Calls.Count -eq 1) 'A capture kept reading after its lease was no longer verified.'
    $lease.Verified = $true
    $fixture = New-CaptureFixture
    Assert-CaptureRejected { Invoke-WalkCapture -Adb 'fixture-adb' -Serial 'other-device' -RunId $run -CaptureId $capture -Package $package -OutDir $work -DeviceLease $lease -AdbInvoker $fixture.Invoker } '*owned lease*'
    Assert-CaptureTest ($fixture.State.Calls.Count -eq 0) 'A capture used the lease for another serial.'

    # Refused updates never uninstall, downgrade, clear data or launch either app.
    $fixture = New-CaptureFixture
    [void](Install-WalkCaptureApk -Adb 'fixture-adb' -Serial 'fixture-capture' -Apk $built -DeviceLease $lease -AdbInvoker $fixture.Invoker)
    Assert-CaptureTest ($fixture.State.Calls.Count -eq 1 -and $fixture.State.Calls[0] -ceq "install -t -r $built") 'Capture install did not use a test-only preserving update.'
    foreach ($installCase in @('failure', 'exit', 'missing-success')) {
        $fixture = New-CaptureFixture
        switch ($installCase) {
            'failure' { $fixture.State.InstallOutput = @('Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]') }
            'exit' { $fixture.State.InstallExit = 1 }
            'missing-success' { $fixture.State.InstallOutput = @('Performing Streamed Install') }
        }
        Assert-CaptureRejected { Install-WalkCaptureApk -Adb 'fixture-adb' -Serial 'fixture-capture' -Apk $built -DeviceLease $lease -AdbInvoker $fixture.Invoker } '*data were preserved*'
        Assert-CaptureTest ($fixture.State.Calls.Count -eq 1) 'A refused capture install attempted recovery mutations.'
    }

    if ($Build) {
        if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
        $tools = Resolve-NativeRunnerTools -Sdk $Sdk -Java $Java
        $keytool = Find-NativeRunnerSdkTool (Split-Path -Parent $tools.Java) 'keytool'
        $keystore = Join-Path $work 'capture-test.p12'
        [void](Invoke-NativeRunnerTool -Executable $keytool -Arguments @('-genkeypair', '-keystore', $keystore, '-storetype', 'PKCS12', '-storepass', 'capture-test',
            '-alias', 'capture', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-dname', 'CN=HushGram capture test') -Failure 'Could not make the temporary capture key')
        $actual = New-WalkCaptureApk -Tools $tools -WorkDirectory (Join-Path $work 'actual') -Keystore $keystore -KeyAlias 'capture' -KeystorePassword 'capture-test' -KeyPassword 'capture-test'
        $facts = Get-ApkManifestFacts -Apk $actual -Aapt2 $tools.Aapt2
        Assert-CaptureTest ($facts.package -ceq 'app.hushgram.walkcapture' -and $facts.minSdkVersion -eq 28) 'The real APK has the wrong package or minimum SDK.'
        Assert-CaptureTest ((Get-ApkSignerDigest -Tools $tools -Apk $actual) -cmatch '\A[0-9a-f]{64}\z') 'The real capture APK was not signed.'
        Write-Host "[capture] compiled and signed against $($tools.AndroidJar)"
    }
    Write-Host "[capture] $script:checks checks passed"
} finally {
    Set-Item -LiteralPath Function:New-NativeRunnerApk -Value $nativeBuilder
    if ($null -ne $lease) { Exit-HushgramDeviceLease $lease }
    if (Test-Path -LiteralPath $work) {
        $resolved = [IO.Path]::GetFullPath($work)
        $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (-not $resolved.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -cnotmatch '\Ahushgram-walk-capture-[a-f0-9]{32}\z') {
            throw 'Refusing to remove a test directory outside its owned temporary root.'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
