<#
.SYNOPSIS
    Exercise ADB failure cleanup for injected-register verification.

.DESCRIPTION
    Every stage of a device verify can fail, and each failure has to be named and still remove
    what was pushed. Two of them are about evidence rather than errors: a stock build can raise no
    verifier message at all, so a tally only counts once dex2oat has read a file of the pushed size, and
    dex2oat exits 0 while logging that the file it was given doesn't exist.

.NOTES
    Taken from Hushfacebook's scripts/test-injected-register-device.ps1
    (https://github.com/SysAdminDoc/Hushfacebook, commit 3a47363954eea853357e71e7cf3951a5ee984cee).
    GPL-3.0-only. Modified for HushGram (Instagram), 2026.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'injected-register-device.ps1')
. (Join-Path $PSScriptRoot 'script-wiring.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Read-LeaseBytes {
    param([string]$Path)
    # A reader must share the existing writer's access while its exclusive lease handle is held.
    $file = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    $bytes = [IO.MemoryStream]::new()
    try { $file.CopyTo($bytes); return ,$bytes.ToArray() }
    finally { $file.Dispose(); $bytes.Dispose() }
}

# The helper reads the local file's size, so the fixture is a real file.
$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ("hushgram-device-fixture-" + [guid]::NewGuid().ToString('N') + '.apk')
[System.IO.File]::WriteAllBytes($fixture, [byte[]](1..200 | ForEach-Object { $_ % 256 }))
$fixtureSize = (Get-Item -LiteralPath $fixture).Length
$leaseDirectory = Join-Path ([IO.Path]::GetTempPath()) ('hushgram-lease-tests-' + [guid]::NewGuid().ToString('N'))
$deviceLease = $null
try {
    function New-FakeAdb {
        param(
            [string]$FailureStage,
            [int]$CleanupFailureNumber = 0,
            [int]$CleanupThrowNumber = 0,
            # A parameter, so GetNewClosure captures it: the closure sees this function's own
            # variables, not the script's, once the suite runs nested inside the push gate.
            [long]$Size = $fixtureSize
        )

        $state = [pscustomobject]@{
            Calls = [System.Collections.Generic.List[string]]::new()
            RemotePaths = [System.Collections.Generic.HashSet[string]]::new(
                [System.StringComparer]::Ordinal)
            CleanupCalls = 0
            Marker = $null
        }
        $invoker = {
            param([string]$Executable, [string[]]$Arguments)
            $line = $Arguments -join ' '
            [void]$state.Calls.Add($line)
            $exitCode = 0
            $output = @()
            $operation = $Arguments[2]

            if ($operation -eq 'push') {
                [void]$state.RemotePaths.Add($Arguments[4])
                if ($FailureStage -eq 'push') { $exitCode = 11 }
            } elseif ($operation -eq 'shell' -and $Arguments[3] -like '*&& mkdir -p*') {
                $directory = ($Arguments[3] -split 'mkdir -p ', 2)[1]
                [void]$state.RemotePaths.Add($directory)
                if ($FailureStage -eq 'setup') { $exitCode = 12 }
            } elseif ($operation -eq 'logcat' -and $Arguments[3] -eq '-c') {
                throw 'The verifier cleared the log buffer of a shared phone.'
            } elseif ($operation -eq 'shell' -and $Arguments[3] -like 'log -t HushGramVerify *') {
                $state.Marker = $Arguments[3].Substring('log -t HushGramVerify '.Length)
                if ($FailureStage -eq 'mark') { $exitCode = 13 }
            } elseif ($operation -eq 'shell' -and $Arguments[3] -like 'dex2oat* --dex-file=*') {
                $read = if ($FailureStage -eq 'short-read') { 5 } else { $Size }
                $output = if ($FailureStage -eq 'dex2oat') { @('exit=14', "size=$read") } else { @('exit=0', "size=$read") }
            } elseif ($operation -eq 'logcat' -and $Arguments[3] -eq '-d') {
                if ($FailureStage -eq 'log-read-throw') {
                    throw 'fake ADB threw while reading logcat'
                } elseif ($FailureStage -eq 'log-read') {
                    $exitCode = 15
                } else {
                    # Another session's lines before this run's marker are left alone and not counted.
                    $before = @(
                        'I dex2oat64: Verification error in Lfixture/Host;',
                        "W dex2oat64: Skipping non-existent dex file '/data/local/tmp/other.apk'",
                        "I HushGramVerify: hushgram-verify-case-$('0' * 32)"
                    )
                    $run = if ($FailureStage -eq 'unread') {
                        @("W dex2oat64: Skipping non-existent dex file '/data/local/tmp/hushgram-verify-case.apk'")
                    } else {
                        @(
                            'I dex2oat64: Verification error in Lfixture/Host;',
                            'I dex2oat64: Verification error in Lfixture/Host;',
                            'W dex2oat: VerifyError in Lfixture/Other;'
                        )
                    }
                    $marked = if ($FailureStage -eq 'rotated') { @() } else { @("I HushGramVerify: $($state.Marker)") }
                    $output = @($before) + @($marked) + @($run)
                }
            } elseif ($operation -eq 'shell' -and $Arguments[3] -like 'rm -rf *') {
                $state.CleanupCalls++
                $path = $Arguments[3].Substring('rm -rf '.Length)
                if ($CleanupThrowNumber -eq $state.CleanupCalls) {
                    throw "fake ADB threw while removing $path"
                } elseif ($CleanupFailureNumber -eq $state.CleanupCalls) {
                    $exitCode = 91
                } else {
                    [void]$state.RemotePaths.Remove($path)
                }
            } else {
                throw "Unexpected fake ADB call through ${Executable}: $line"
            }
            [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
        }.GetNewClosure()
        [pscustomobject]@{ State = $state; Invoker = $invoker }
    }

    function Assert-BothCleanupCalls {
        param($State, [string]$Context)
        $expected = @(
            '-s SERIAL shell rm -rf /data/local/tmp/hushgram-verify-case',
            '-s SERIAL shell rm -rf /data/local/tmp/hushgram-verify-case.apk'
        )
        foreach ($call in $expected) {
            Assert-True (@($State.Calls | Where-Object { $_ -eq $call }).Count -eq 1) `
                "$Context did not attempt cleanup exactly once: $call"
        }
    }

    $identityInvoker = {
        param($executable, $arguments)
        $answer = switch ($arguments[-1]) {
            'get-serialno' { 'SERIAL' }
            'ro.product.model' { 'FixtureDevice' }
            'ro.product.cpu.abi' { 'arm64-v8a' }
            default { throw 'Unexpected identity query' }
        }
        [pscustomobject]@{ExitCode=0;Output=@($answer);Stdout=@($answer)}
    }
    $deviceLease = Enter-HushgramDeviceLease -Serial 'SERIAL' -ExpectedModel 'FixtureDevice' -Directory $leaseDirectory
    $leasePath = Join-Path $leaseDirectory 'SERIAL.json'
    $before = Read-LeaseBytes $leasePath
    $occupied = $false
    try { [void](Enter-HushgramDeviceLease -Serial 'SERIAL' -ExpectedModel 'FixtureDevice' -Directory $leaseDirectory) }
    catch { $occupied = $true }
    Assert-True $occupied 'A second test acquired the same whole-device lease.'
    Assert-True ([Convert]::ToBase64String($before) -ceq [Convert]::ToBase64String((Read-LeaseBytes $leasePath))) `
        'Lease contention changed the active owner record.'
    $unleased = New-FakeAdb
    $denied = $false
    try { [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' -Local $fixture -Label 'case' -AdbInvoker $unleased.Invoker) }
    catch { $denied = $true }
    Assert-True ($denied -and $unleased.State.Calls.Count -eq 0) 'The verifier touched a device without a lease.'
    $denied = $false
    try { [void](Invoke-HushgramAdbCommand -Adb 'fake-adb' -Arguments @('-s','SERIAL','install','fixture.apk') -Invoker $unleased.Invoker -DeviceLease $deviceLease) }
    catch { $denied = $true }
    Assert-True ($denied -and $unleased.State.Calls.Count -eq 0) 'Unverified device identity allowed an install.'
    Confirm-HushgramDevice -Adb 'fake-adb' -DeviceLease $deviceLease -AdbInvoker $identityInvoker
    Assert-True ($deviceLease.Verified -and $deviceLease.InstructionSet -ceq 'arm64') 'Device identity was not verified.'
    $denied = $false
    try { [void](Invoke-HushgramAdbCommand -Adb 'fake-adb' -Arguments @('-s','OTHER','install','fixture.apk') -Invoker $unleased.Invoker -DeviceLease $deviceLease) }
    catch { $denied = $true }
    Assert-True ($denied -and $unleased.State.Calls.Count -eq 0) 'A lease permitted a different device serial.'
    $denied = $false
    try { Remove-AndroidPackageIfInstalled -Adb 'fake-adb' -Serial 'SERIAL' -PackageName 'com.instagram.android' }
    catch { $denied = $true }
    Assert-True $denied 'The obsolete replacement helper could uninstall preserved data.'
    $expired = Join-Path $leaseDirectory 'EXPIRED.json'
    [IO.File]::WriteAllText($expired, '{"ownershipToken":"foreign","expiresUtc":"2026-01-01T00:00:00Z"}')
    $denied = $false
    try { [void](Enter-HushgramDeviceLease -Serial 'EXPIRED' -ExpectedModel 'FixtureDevice' -Directory $leaseDirectory) }
    catch { $denied = $true }
    Assert-True ($denied -and [IO.File]::ReadAllText($expired).Contains('foreign')) 'An expired foreign lease was reclaimed without confirming its test stopped.'

    # Contention must hold across processes, not only another call in this PowerShell scope.
    $before = Read-LeaseBytes $leasePath
    $helper = (Join-Path $PSScriptRoot 'device-install.ps1').Replace("'", "''")
    $directoryArgument = $leaseDirectory.Replace("'", "''")
    $contenderCode = ". '$helper'; try { `$lease = Enter-HushgramDeviceLease -Serial SERIAL -ExpectedModel FixtureDevice -Directory '$directoryArgument'; Exit-HushgramDeviceLease `$lease; exit 23 } catch { exit 0 }"
    $contender = [Diagnostics.Process]::new()
    try {
        $contender.StartInfo = [Diagnostics.ProcessStartInfo]::new((Get-Process -Id $PID).Path)
        $contender.StartInfo.UseShellExecute = $false
        $contender.StartInfo.CreateNoWindow = $true
        foreach ($argument in @('-NoProfile','-EncodedCommand',[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($contenderCode)))) {
            $contender.StartInfo.ArgumentList.Add($argument)
        }
        Assert-True ($contender.Start()) 'The separate lease contender did not start.'
        $contender.WaitForExit()
        Assert-True ($contender.ExitCode -eq 0) 'A separate process acquired an active whole-device lease.'
        Assert-True ([Convert]::ToBase64String($before) -ceq [Convert]::ToBase64String((Read-LeaseBytes $leasePath))) `
            'A separate lease contender changed the owner record.'
    } finally { $contender.Dispose() }

    function New-IdentityInvoker {
        param([string]$Serial = 'SERIAL', [string]$Model = 'FixtureDevice', [string]$Abi = 'arm64-v8a', [string]$Avd = 'FixtureAvd')
        { param($executable, $arguments)
            $answer = switch ($arguments[-1]) {
                'get-serialno' { @($Serial) }
                'ro.product.model' { @($Model) }
                'ro.product.cpu.abi' { @($Abi) }
                'name' { @($Avd,'OK') }
                default { throw 'Unexpected identity query' }
            }
            [pscustomobject]@{ExitCode=0;Output=@($answer);Stdout=@($answer)}
        }.GetNewClosure()
    }
    foreach ($invalid in @(@{Serial='OTHER'}, @{Model='OtherDevice'}, @{Abi='unsupported'})) {
        $denied = $false
        try { Confirm-HushgramDevice -Adb fake-adb -DeviceLease $deviceLease -AdbInvoker (New-IdentityInvoker @invalid) }
        catch { $denied = $true }
        Assert-True ($denied -and -not $deviceLease.Verified -and $null -eq $deviceLease.InstructionSet) 'A failed identity recheck retained verification.'
        $denied = $false
        try { [void](Invoke-HushgramAdbCommand -Adb fake-adb -Arguments @('-s','SERIAL','install','fixture.apk') -Invoker $unleased.Invoker -DeviceLease $deviceLease) }
        catch { $denied = $true }
        Assert-True ($denied -and $unleased.State.Calls.Count -eq 0) 'A failed identity recheck permitted an install.'
    }
    foreach ($abiCase in @(@{Abi='armeabi-v7a';Isa='arm';Binary='dex2oat'}, @{Abi='x86';Isa='x86';Binary='dex2oat'}, @{Abi='x86_64';Isa='x86_64';Binary='dex2oat64'})) {
        Confirm-HushgramDevice -Adb fake-adb -DeviceLease $deviceLease -AdbInvoker (New-IdentityInvoker -Abi $abiCase.Abi)
        $abiFake = New-FakeAdb
        [void](Invoke-AndroidVerifierTally -Adb fake-adb -Serial SERIAL -Local $fixture -Label case -DeviceLease $deviceLease -AdbInvoker $abiFake.Invoker)
        Assert-True (@($abiFake.State.Calls | Where-Object { $_ -like "-s SERIAL shell $($abiCase.Binary) --dex-file=*" -and $_.Contains("--instruction-set=$($abiCase.Isa) ") }).Count -eq 1) 'The verifier did not use the verified ABI.'
    }
    Confirm-HushgramDevice -Adb fake-adb -DeviceLease $deviceLease -AdbInvoker $identityInvoker
    $deviceLease.Record.expiresUtc = '2026-01-01T00:00:00Z'
    Update-HushgramDeviceLease $deviceLease
    Assert-True ([DateTimeOffset]::Parse($deviceLease.Record.expiresUtc) -gt [DateTimeOffset]::UtcNow.AddMinutes(10)) 'An owned lease was not renewed.'
    # Read back the way another session reads it: ConvertFrom-Json turns an offset time into a
    # local DateTime, which compared with UTC looks hours away from its real expiry.
    $written = [Text.Encoding]::UTF8.GetString((Read-LeaseBytes $leasePath)) | ConvertFrom-Json
    Assert-True ($written.expiresUtc -is [DateTime] -and $written.expiresUtc.Kind -eq [DateTimeKind]::Utc -and
        $written.acquiredUtc.Kind -eq [DateTimeKind]::Utc) 'The lease times read back as local times.'
    $denied = $false
    try { [void](Invoke-HushgramAdbCommand -Adb (Join-Path $leaseDirectory 'missing-adb.exe') -Arguments @('-s','SERIAL','get-state') -DeviceLease $deviceLease) }
    catch { $denied = $true }
    Assert-True ($denied -and $deviceLease.File.CanWrite) 'A failed native process start lost the owned lease.'
    # Exercise the real process/stream path. Windows ADB's emulator console writes CR CR LF,
    # which the injected invoker fixtures cannot reproduce at the byte boundary.
    $nativeSource = Join-Path $leaseDirectory 'FakeAdb.cs'
    $nativeAdb = Join-Path $leaseDirectory 'FakeAdb.exe'
    $nativeMode = Join-Path $leaseDirectory 'FakeAdb.mode'
    [IO.File]::WriteAllText($nativeSource, @'
class FakeAdb {
    static int Main(string[] args) {
        if (args.Length < 3 || args[0] != "-s" || (args[1] != "SERIAL" && args[1] != "emulator-9998")) return 92;
        string command = string.Join(" ", args, 2, args.Length - 2);
        if (command == "get-state") { System.Console.Write("FixtureAvd\r\r\nOK\r\r\n"); return 0; }
        string modePath = System.IO.Path.ChangeExtension(System.Reflection.Assembly.GetExecutingAssembly().Location, ".mode");
        string mode = System.IO.File.Exists(modePath) ? System.IO.File.ReadAllText(modePath) : "";
        if (mode == "failure") {
            System.Console.Write("offline\r\n");
            System.Console.Error.Write("error: device offline\r\n");
            return 93;
        }
        string answer;
        switch (command) {
            case "get-serialno": answer = mode == "serial" ? "OTHER" : args[1]; break;
            case "shell getprop ro.product.model": answer = mode == "model" ? "OtherDevice" : "FixtureDevice"; break;
            case "shell getprop ro.product.cpu.abi":
                answer = mode == "abi" ? "unsupported" : args[1] == "SERIAL" ? "arm64-v8a" : "x86_64"; break;
            case "emu avd name": answer = (mode == "avd" ? "OtherAvd" : "FixtureAvd") + "\r\r\nOK"; break;
            default: return 92;
        }
        System.Console.Write(answer + "\r\r\n");
        System.Console.Error.Write("* daemon not running; starting now at tcp:5037\r\r\n* daemon started successfully\r\r\n");
        return 0;
    }
}
'@)
    $compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    & $compiler /nologo /target:exe "/out:$nativeAdb" $nativeSource
    Assert-True ($LASTEXITCODE -eq 0) 'The native ADB-output fixture did not compile.'
    $nativeResult = Invoke-HushgramAdbCommand -Adb $nativeAdb -Arguments @('-s','SERIAL','get-state') -DeviceLease $deviceLease
    Assert-True ($nativeResult.ExitCode -eq 0 -and $nativeResult.Output.Count -eq 2 -and $nativeResult.Output[0] -ceq 'FixtureAvd' -and $nativeResult.Output[1] -ceq 'OK') `
        'Native Windows ADB line endings corrupted the emulator identity response.'

    # A daemon startup is successful, but its stderr notices are not identity answers.
    Confirm-HushgramDevice -Adb $nativeAdb -DeviceLease $deviceLease
    Assert-True ($deviceLease.Verified -and $deviceLease.InstructionSet -ceq 'arm64') 'Native identity with daemon startup notices was refused.'
    $nativeIdentity = Invoke-HushgramAdbCommand -Adb $nativeAdb -Arguments @('-s','SERIAL','get-serialno') -DeviceLease $deviceLease
    Assert-True ($nativeIdentity.Stdout.Count -eq 1 -and $nativeIdentity.Stdout[0] -ceq 'SERIAL') 'Native stderr contaminated the separate identity output.'
    Assert-True (($nativeIdentity.Output -join "`n") -ceq "SERIAL`n* daemon not running; starting now at tcp:5037`n* daemon started successfully") `
        'Separating identity stdout dropped combined ADB diagnostics.'

    function Assert-NativeIdentityRefused {
        param($Lease, [string]$Mode)
        [IO.File]::WriteAllText($nativeMode, $Mode)
        try {
            $denied = $false
            try { Confirm-HushgramDevice -Adb $nativeAdb -DeviceLease $Lease }
            catch { $denied = $true }
            Assert-True ($denied -and -not $Lease.Verified -and $null -eq $Lease.InstructionSet) "Native $Mode identity failure retained verification."
            $denied = $false
            try { [void](Invoke-HushgramAdbCommand -Adb fake-adb -Arguments @('-s',$Lease.Record.serial,'install','fixture.apk') -Invoker $unleased.Invoker -DeviceLease $Lease) }
            catch { $denied = $true }
            Assert-True ($denied -and $unleased.State.Calls.Count -eq 0) "Native $Mode identity failure permitted an install."
        } finally { [IO.File]::Delete($nativeMode) }
    }
    foreach ($mode in @('serial','model','abi')) { Assert-NativeIdentityRefused -Lease $deviceLease -Mode $mode }
    Assert-NativeIdentityRefused -Lease $deviceLease -Mode failure
    [IO.File]::WriteAllText($nativeMode, 'failure')
    try {
        $nativeFailure = Invoke-HushgramAdbCommand -Adb $nativeAdb -Arguments @('-s','SERIAL','get-serialno') -DeviceLease $deviceLease
        Assert-True ($nativeFailure.ExitCode -eq 93 -and ($nativeFailure.Stdout -join "`n") -ceq 'offline' -and
            ($nativeFailure.Output -join "`n") -ceq "offline`nerror: device offline") 'Native failure lost its exit code or stdout/stderr diagnostics.'
        Assert-True ((Format-HushgramAdbFailure -Message 'Could not query fixture' -Result $nativeFailure) -ceq
            'Could not query fixture (ADB exit 93). Output: offline; error: device offline') 'Native failure formatting lost combined diagnostics.'
    } finally { [IO.File]::Delete($nativeMode) }
    Confirm-HushgramDevice -Adb $nativeAdb -DeviceLease $deviceLease

    $emulatorLease = Enter-HushgramDeviceLease -Serial emulator-9998 -ExpectedModel FixtureDevice -ExpectedAvd FixtureAvd -Directory $leaseDirectory
    try {
        $denied = $false
        try { Confirm-HushgramDevice -Adb fake-adb -DeviceLease $emulatorLease -AdbInvoker (New-IdentityInvoker -Serial emulator-9998 -Avd OtherAvd) }
        catch { $denied = $true }
        Assert-True ($denied -and -not $emulatorLease.Verified) 'The wrong emulator profile was accepted.'
        Confirm-HushgramDevice -Adb fake-adb -DeviceLease $emulatorLease -AdbInvoker (New-IdentityInvoker -Serial emulator-9998 -Abi x86_64)
        Assert-True ($emulatorLease.Verified -and $emulatorLease.InstructionSet -ceq 'x86_64') 'The correct emulator profile was refused.'
        Confirm-HushgramDevice -Adb $nativeAdb -DeviceLease $emulatorLease
        Assert-True ($emulatorLease.Verified -and $emulatorLease.InstructionSet -ceq 'x86_64') 'Native emulator identity with daemon startup notices was refused.'
        Assert-NativeIdentityRefused -Lease $emulatorLease -Mode avd
    } finally { Exit-HushgramDeviceLease $emulatorLease }
    $wrongOwner = Enter-HushgramDeviceLease -Serial OWNER -ExpectedModel FixtureDevice -Directory $leaseDirectory
    $ownerPath = $wrongOwner.Path
    $wrongOwner.Record.ownershipToken = 'not-the-owner'
    $denied = $false
    try { Exit-HushgramDeviceLease $wrongOwner }
    catch { $denied = $true }
    Assert-True ($denied -and (Test-Path -LiteralPath $ownerPath)) 'Release removed a marker with different ownership.'
    $denied = $false
    try { Assert-HushgramDeviceLease -DeviceLease $wrongOwner -Serial OWNER }
    catch { $denied = $true }
    Assert-True $denied 'A disposed lease allowed further device work.'

    $failures = [ordered]@{
        push = 'Could not push case to SERIAL (ADB exit 11).'
        setup = 'Could not prepare the verifier output directory for case on SERIAL (ADB exit 12).'
        mark = 'Could not mark logcat on SERIAL before verifying case (ADB exit 13).'
        dex2oat = 'dex2oat on case exited 14.'
        'log-read' = 'Could not read logcat on SERIAL after verifying case (ADB exit 15).'
        rotated = "The log on SERIAL no longer holds the start of the case run, so its verifier messages can't be counted. Retry when the phone is quieter."
        'short-read' = "dex2oat on case read a file of 5 bytes on SERIAL, not the $fixtureSize bytes pushed."
        unread = "dex2oat did not read case on SERIAL: Skipping non-existent dex file '/data/local/tmp/hushgram-verify-case.apk'"
    }
    foreach ($stage in $failures.Keys) {
        $fake = New-FakeAdb -FailureStage $stage
        $caught = $null
        try {
            [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
                -Local $fixture -Label 'case' -DeviceLease $deviceLease -AdbInvoker $fake.Invoker)
        } catch {
            $caught = $_
        }
        Assert-True ($null -ne $caught) "$stage failure was accepted."
        Assert-True ($caught.Exception.Message -eq $failures[$stage]) `
            "$stage returned a different failure: $($caught.Exception.Message)"
        Assert-BothCleanupCalls -State $fake.State -Context $stage
        Assert-True ($fake.State.RemotePaths.Count -eq 0) "$stage left fake remote files behind."
    }

    $thrownFailure = New-FakeAdb -FailureStage 'log-read-throw'
    $caught = $null
    try {
        [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
            -Local $fixture -Label 'case' -DeviceLease $deviceLease -AdbInvoker $thrownFailure.Invoker)
    } catch {
        $caught = $_
    }
    Assert-True ($caught.Exception.Message -eq 'fake ADB threw while reading logcat') `
        'A thrown ADB error was replaced or accepted.'
    Assert-BothCleanupCalls -State $thrownFailure.State -Context 'thrown log retrieval failure'
    Assert-True ($thrownFailure.State.RemotePaths.Count -eq 0) `
        'A thrown ADB error left fake remote files behind.'

    $primaryWithCleanupFailure = New-FakeAdb -FailureStage 'mark' -CleanupThrowNumber 1
    $warnings = @()
    $caught = $null
    try {
        [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
            -Local $fixture -Label 'case' -DeviceLease $deviceLease -AdbInvoker $primaryWithCleanupFailure.Invoker `
            -WarningVariable warnings)
    } catch {
        $caught = $_
    }
    Assert-True ($caught.Exception.Message -eq $failures.mark) `
        'A cleanup failure replaced the original verification failure.'
    Assert-BothCleanupCalls -State $primaryWithCleanupFailure.State -Context 'secondary cleanup failure'
    Assert-True (($warnings | ForEach-Object { "$_" }) -join "`n" -match 'cleanup also failed') `
        'A cleanup failure beside a primary failure produced no secondary diagnostic.'

    $success = New-FakeAdb
    $tally = Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
        -Local $fixture -Label 'case' -DeviceLease $deviceLease -AdbInvoker $success.Invoker
    Assert-True ($tally.Count -eq 2 -and $tally['Verification error in Lfixture/Host;'] -eq 2 -and
        $tally['VerifyError in Lfixture/Other;'] -eq 1) 'The successful fake verifier tally was wrong.'
    Assert-BothCleanupCalls -State $success.State -Context 'success'
    Assert-True ($success.State.RemotePaths.Count -eq 0) 'The successful run left fake remote files behind.'

    $successWithCleanupFailure = New-FakeAdb -CleanupFailureNumber 1
    $caught = $null
    try {
        [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
            -Local $fixture -Label 'case' -DeviceLease $deviceLease -AdbInvoker $successWithCleanupFailure.Invoker)
    } catch {
        $caught = $_
    }
    Assert-True ($null -ne $caught -and $caught.Exception.Message -like 'Verifier cleanup failed.*') `
        'A cleanup failure after successful verification was accepted.'
    Assert-BothCleanupCalls -State $successWithCleanupFailure.State -Context 'success cleanup failure'

    # As the verifier runs them (script-wiring.ps1), so help text, log lines, functions nothing
    # calls and dead branches can't stand in for the calls: the helper dot-sourced, and a tally of
    # the clean APK and one of the patched APK, compared with each other.
    $verifier = Join-Path $PSScriptRoot 'verify-injected-registers.ps1'
    $prePush = Join-Path $PSScriptRoot 'pre-push.ps1'
    $watchAssignments = @((Get-ScriptAst $prePush).FindAll({ param($Node)
        $Node -is [Management.Automation.Language.AssignmentStatementAst] -and
        $Node.Left.Extent.Text -eq '$injectedRegisterDevicePaths'
    }, $true))
    Assert-True ($watchAssignments.Count -eq 1) 'The device-suite changed-path gate is missing or ambiguous.'
    $watched = @($watchAssignments[0].Right.FindAll({ param($Node)
        $Node -is [Management.Automation.Language.StringConstantExpressionAst]
    }, $true) | ForEach-Object Value)
    foreach ($path in @('scripts/device-install.ps1','scripts/injected-register-device.ps1','scripts/patch-for-device.ps1','scripts/pre-push.ps1','scripts/test-injected-register-device.ps1','scripts/verify-injected-registers.ps1')) {
        Assert-True ($watched -ccontains $path) "The device-suite gate does not watch $path."
    }
    Assert-True ((Test-DotSourcesFile $verifier 'injected-register-device.ps1') -and (Test-TalliesBothSides $verifier)) `
        'The device verifier does not use the cleanup helper for both sides.'
    # The line that adds the suite to the run, not the path list that only decides when to run it.
    Assert-True (Test-PushGateRunsSuite $prePush 'scripts/test-injected-register-device.ps1') `
        'The push gate does not run the verifier cleanup fixtures.'

    # The checks themselves, on copies with the wiring taken out in ways that leave its text
    # behind. Each copy has to fail the check it was made for, and an untouched copy has to pass.
    $wiringCopy = Join-Path ([System.IO.Path]::GetTempPath()) `
        ("hushgram-device-wiring-" + [guid]::NewGuid().ToString('N') + '.ps1')
    try {
        $verifierText = [System.IO.File]::ReadAllText($verifier)
        $prePushSource = [System.IO.File]::ReadAllText($prePush)
        $dotSourcesDevice = { param($Path) Test-DotSourcesFile $Path 'injected-register-device.ps1' }
        $talliesBothSides = { param($Path) Test-TalliesBothSides $Path }
        $gateRunsSuite = { param($Path) Test-PushGateRunsSuite $Path 'scripts/test-injected-register-device.ps1' }
        $deviceDotSource = { param($Node)
            $Node -is [System.Management.Automation.Language.CommandAst] -and
            $Node.InvocationOperator -eq [System.Management.Automation.Language.TokenKind]::Dot -and
            $Node.Extent.Text -like '*injected-register-device.ps1*' }
        # The device half's first statement, which the copies below put a statement in front of.
        $deviceHalfStart = { param($Node)
            $Node -is [System.Management.Automation.Language.CommandAst] -and
            $Node.Extent.Text -like '*running the device verifier on*' }
        $beforeDeviceHalf = { param([string]$Statement)
            Edit-ScriptNode $verifierText $deviceHalfStart { param($Text) "$Statement`n    $Text" }.GetNewClosure() }
        # And the other places the tally copies put a statement: before the compare, and after
        # the helpers are dot-sourced.
        $compareStatement = { param($Node)
            $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
            $Node.Extent.Text -like '$comparison = Compare-VerifierTallies*' }
        $beforeCompare = { param([string]$Statement)
            Edit-ScriptNode $verifierText $compareStatement { param($Text) "$Statement`n    $Text" }.GetNewClosure() }
        $afterHelpers = { param([string]$Statement)
            Edit-ScriptNode $verifierText $deviceDotSource { param($Text) "$Text`n$Statement" }.GetNewClosure() }
        $approveAll = '[pscustomobject]@{ Valid = $true; CleanTotal = 0; PatchedTotal = 0; Deltas = @() }'
        $wiringCases = @(
            @{ Name = 'the untouched verifier'; Check = $dotSourcesDevice; Expect = $true; Text = $verifierText }
            @{ Name = 'the untouched verifier'; Check = $talliesBothSides; Expect = $true; Text = $verifierText }
            @{ Name = 'the untouched pre-push.ps1'; Check = $gateRunsSuite; Expect = $true; Text = $prePushSource }
            @{ Name = 'both tallies taken of the patched APK'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.CommandAst] -and
                    $Node.GetCommandName() -eq 'Invoke-AndroidVerifierTally' -and $Node.Extent.Text -like '*$cleanBase*'
                } { param($Text) $Text.Replace('$cleanBase', '$PatchedApk') } }
            @{ Name = 'both tallies taken into one variable'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.IfStatementAst] -and
                    $Node.Clauses[0].Item1.Extent.Text -eq '$Serial' } { param($Text)
                    $Text.Replace('$cleanTally', '$tally').Replace('$patchedTally', '$tally') } }
            @{ Name = 'the clean tally written over with the patched one before the compare'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Extent.Text -like '$comparison = Compare-VerifierTallies*' } { param($Text)
                    "`$cleanTally = `$patchedTally`n    $Text" } }
            @{ Name = 'Compare-VerifierTallies defined in the verifier itself'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText $deviceDotSource { param($Text)
                    "$Text`nfunction Compare-VerifierTallies { param(`$Clean, `$Patched)`n" +
                    "    [pscustomobject]@{ Valid = `$true; CleanTotal = 0; PatchedTotal = 0; Deltas = @() } }" } }
            @{ Name = 'Invoke-AndroidVerifierTally defined in the verifier itself'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText $deviceDotSource { param($Text)
                    "$Text`nfunction Invoke-AndroidVerifierTally { @{} }" } }
            @{ Name = 'the device half in a function nothing calls'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.IfStatementAst] -and
                    $Node.Clauses[0].Item1.Extent.Text -eq '$Serial' } { param($Text) "function Invoke-DeviceHalf {`n$Text`n}" } }
            @{ Name = 'the device helper dot-sourced in a dead branch'; Check = $dotSourcesDevice
                Text = Edit-ScriptNode $verifierText $deviceDotSource { param($Text) "if (`$false) { $Text }" } }
            # Statements that end their block with the exit nested inside them. First in the device
            # half, each leaves every -Serial run passing with no tally taken. The copies expected
            # to pass keep those rules off statements that can still carry on: a catch that
            # finishes, a clause that matches and finishes, a break out of the do, and a return
            # out of the script block.
            @{ Name = 'the device half after try { exit 0 } finally { }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'try { exit 0 } finally { }' }
            @{ Name = 'the device half after try { exit 0 } catch { }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'try { exit 0 } catch { }' }
            @{ Name = "the device half after try { throw 'skip' } catch { exit 0 }"; Check = $talliesBothSides
                Text = & $beforeDeviceHalf "try { throw 'skip' } catch { exit 0 }" }
            @{ Name = 'the device half after try { } finally { exit 0 }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'try { } finally { exit 0 }' }
            @{ Name = "the device half after try { throw 'skip' } catch { }"; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf "try { throw 'skip' } catch { }" }
            @{ Name = 'the device half after switch (1) { default { exit 0 } }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'switch (1) { default { exit 0 } }' }
            @{ Name = 'the device half after switch (1) { 1 { exit 0 } }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'switch (1) { 1 { exit 0 } }' }
            @{ Name = "the device half after switch (2) { 1 { 'one' } default { exit 0 } }"; Check = $talliesBothSides
                Text = & $beforeDeviceHalf "switch (2) { 1 { 'one' } default { exit 0 } }" }
            @{ Name = "the device half after switch (1) { 1 { 'one' } default { exit 0 } }"; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf "switch (1) { 1 { 'one' } default { exit 0 } }" }
            @{ Name = 'the device half after a switch with no default'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf 'switch (1) { 2 { exit 0 } }' }
            @{ Name = 'the device half after a switch a break can leave'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf 'switch (1) { default { if ($Serial) { break }; exit 0 } }' }
            @{ Name = 'the device half after a switch over @()'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf 'switch (@()) { default { exit 0 } }' }
            @{ Name = 'the device half after do { exit 0 } while ($false)'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'do { exit 0 } while ($false)' }
            @{ Name = 'the device half after do { exit 0 } until ($true)'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'do { exit 0 } until ($true)' }
            @{ Name = 'the device half after a do a break can leave'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf 'do { if ($Serial) { break }; exit 0 } while ($false)' }
            @{ Name = 'the device half after & { exit 0 }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '& { exit 0 }' }
            @{ Name = "the device half after . { throw 'skip' }"; Check = $talliesBothSides
                Text = & $beforeDeviceHalf ". { throw 'skip' }" }
            @{ Name = 'the device half after & { begin { exit 0 } }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '& { begin { exit 0 } }' }
            @{ Name = 'the device half after & { process { exit 0 } }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '& { process { exit 0 } }' }
            @{ Name = 'the device half after @() | & { process { exit 0 } }, which runs no process block'
                Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf '@() | & { process { exit 0 } }' }
            @{ Name = 'the device half after a script block a return can leave'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf '& { if ($Serial) { return }; exit 0 }' }
            # A tally input or a tally written in a way that isn't an assignment to the tally
            # variable, or a helper replaced without a function statement. Each leaves every
            # -Serial run comparing a build with itself, or not comparing at all. The copies
            # expected to pass write other names the same ways.
            @{ Name = 'the clean input set to the patched APK in the device half'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '$cleanBase = $PatchedApk' }
            @{ Name = 'the patched input set to the clean one in the device half'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '$PatchedApk = $cleanBase' }
            @{ Name = 'the clean input taken from the patched APK instead of Get-BaseApk'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Extent.Text -like '$cleanBase = Get-BaseApk*' } { param($Text) '$cleanBase = $PatchedApk' } }
            @{ Name = 'the clean input taken again, from the patched APK, before the DexDiff run'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Extent.Text -eq '$diff = Invoke-DexDiff' } { param($Text)
                    "`$cleanBase = Get-BaseApk -Apk `$PatchedApk -Destination (Join-Path `$work 'again.apk')`n$Text" } }
            @{ Name = 'the clean input taken from a command other than Get-BaseApk'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Extent.Text -like '$cleanBase = Get-BaseApk*' } { param($Text) '$cleanBase = Write-Output $PatchedApk' } }
            @{ Name = 'the patched input set to the clean one by a function the device half calls'; Check = $talliesBothSides
                Text = Edit-ScriptNode (& $afterHelpers 'function Reset-Input { $script:PatchedApk = $script:cleanBase }') `
                    $deviceHalfStart { param($Text) "Reset-Input`n    $Text" } }
            @{ Name = 'the clean input written with -OutVariable in the device half'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'Write-Output $PatchedApk -OutVariable cleanBase | Out-Null' }
            @{ Name = 'the clean tally written over with Set-Variable'; Check = $talliesBothSides
                Text = & $beforeCompare 'Set-Variable -Name cleanTally -Value $patchedTally' }
            @{ Name = 'the clean tally written over with sv'; Check = $talliesBothSides
                Text = & $beforeCompare 'sv cleanTally $patchedTally' }
            @{ Name = 'the clean tally written over with Microsoft.PowerShell.Utility\Set-Variable'; Check = $talliesBothSides
                Text = & $beforeCompare 'Microsoft.PowerShell.Utility\Set-Variable -Name cleanTally -Value $patchedTally' }
            @{ Name = 'the clean tally written over with New-Variable -Force'; Check = $talliesBothSides
                Text = & $beforeCompare 'New-Variable -Name cleanTally -Value $patchedTally -Force' }
            @{ Name = 'the clean tally emptied with Clear-Variable'; Check = $talliesBothSides
                Text = & $beforeCompare 'Clear-Variable -Name cleanTally' }
            @{ Name = 'the clean tally written over through the variable: drive'; Check = $talliesBothSides
                Text = & $beforeCompare 'Set-Item -Path variable:cleanTally -Value $patchedTally' }
            @{ Name = 'the clean tally written over by a foreach over the patched one'; Check = $talliesBothSides
                Text = & $beforeCompare 'foreach ($cleanTally in @($patchedTally)) { }' }
            @{ Name = 'a tally written over with Set-Variable of a name built at run time'; Check = $talliesBothSides
                Text = & $beforeCompare "Set-Variable -Name ('clean' + 'Tally') -Value `$patchedTally" }
            @{ Name = 'Compare-VerifierTallies replaced through ${function:}'; Check = $talliesBothSides
                Text = & $afterHelpers "`${function:Compare-VerifierTallies} = { param(`$Clean, `$Patched) $approveAll }" }
            @{ Name = 'Compare-VerifierTallies replaced with Set-Item function:'; Check = $talliesBothSides
                Text = & $afterHelpers "Set-Item -Path function:Compare-VerifierTallies -Value { param(`$Clean, `$Patched) $approveAll }" }
            @{ Name = 'Invoke-AndroidVerifierTally replaced with New-Item function:'; Check = $talliesBothSides
                Text = & $afterHelpers 'New-Item -Path function: -Name Invoke-AndroidVerifierTally -Value { @{} } -Force | Out-Null' }
            @{ Name = 'Compare-VerifierTallies defined in the verifier as script:Compare-VerifierTallies'; Check = $talliesBothSides
                Text = & $afterHelpers "function script:Compare-VerifierTallies { param(`$Clean, `$Patched) $approveAll }" }
            @{ Name = 'the clean tally written over through $script:cleanTally'; Check = $talliesBothSides
                Text = & $beforeCompare '$script:cleanTally = $patchedTally' }
            @{ Name = 'Compare-VerifierTallies made an alias of a function of the script'; Check = $talliesBothSides
                Text = & $afterHelpers "function Approve-Tallies { param(`$Clean, `$Patched) $approveAll }`nSet-Alias -Name Compare-VerifierTallies -Value Approve-Tallies" }
            @{ Name = 'another variable set with Set-Variable before the compare'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeCompare 'Set-Variable -Name note -Value $patchedTally' }
            @{ Name = 'another function set up through the function: drive'; Check = $talliesBothSides; Expect = $true
                Text = & $afterHelpers "Set-Item -Path function:Show-Note -Value { 'note' }" }
            @{ Name = 'an alias of another name'; Check = $talliesBothSides; Expect = $true
                Text = & $afterHelpers 'Set-Alias -Name Show-Note -Value Write-Host' }
            @{ Name = 'the suite line in a block comment'; Check = $gateRunsSuite
                Text = Edit-ScriptNode $prePushSource { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Left.Extent.Text -eq '$suites' -and
                    $Node.Extent.Text -like "*'scripts/test-injected-register-device.ps1'*" } { param($Text) "<#`n$Text`n#>" } }
        )
        $wiringFailures = @()
        foreach ($case in $wiringCases) {
            [System.IO.File]::WriteAllText($wiringCopy, $case.Text)
            $expect = $case.ContainsKey('Expect') -and $case.Expect
            if ((& $case.Check $wiringCopy) -ne $expect) {
                $wiringFailures += "$($case.Name): the check said $(-not $expect)"
            }
        }
        if ($wiringFailures.Count -ne 0) { throw ("The wiring checks misjudged:`n" + ($wiringFailures -join "`n")) }
    } finally {
        [System.IO.File]::Delete($wiringCopy)
    }

    # Exercise the real device builder up to a deliberately refused CLI run. These stand-ins
    # capture its argument file without reading a signing key or contacting a device.
    $signingRoot = Join-Path $leaseDirectory 'signing'
    $release = Join-Path $signingRoot 'patches/build/release'
    New-Item -ItemType Directory -Path $release -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $signingRoot 'gradle.properties'), "version = 1.2.3`n")
    [IO.File]::WriteAllText((Join-Path $signingRoot 'patches-list.json'), @'
{"version":"1.2.3","patches":[{"name":"Fixture patch","compatiblePackages":{"com.instagram.android":["1.0"]},"dependencies":[]}]}
'@)
    $stock = Join-Path $signingRoot 'stock.apk'
    $desktop = Join-Path $signingRoot 'desktop.jar'
    [IO.File]::WriteAllText($stock, 'fixture')
    [IO.File]::WriteAllText($desktop, 'fixture')
    [IO.File]::WriteAllText((Join-Path $release 'patches-1.2.3.mpp'), 'fixture')
    $java = Join-Path $signingRoot 'java.ps1'
    $aapt = Join-Path $signingRoot 'aapt.ps1'
    $captured = Join-Path $signingRoot 'captured.json'
    [IO.File]::WriteAllText($java, @'
if ($args[0] -eq '-version') { 'openjdk version "21.0.5"'; exit 0 }
if ($args.Count -ne 3 -or $args[0] -ne '-jar' -or -not $args[2].StartsWith('@')) { exit 18 }
$file = $args[2].Substring(1)
$values = @([IO.File]::ReadAllLines($file) | ForEach-Object { ConvertFrom-Json -InputObject $_ })
$capture = @{ invocation = @($args); arguments = $values }
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'captured.json'), ($capture | ConvertTo-Json -Depth 4))
exit 17
'@)
    [IO.File]::WriteAllText($aapt, @'
'E: manifest (line=1)'
'  A: package="com.instagram.android"'
'  A: versionName="1.0"'
'  A: versionCode="1"'
exit 0
'@)
    $storeVariable = 'HUSHGRAM_SIDELOAD_KEYSTORE_PASSWORD'
    $entryVariable = 'HUSHGRAM_SIDELOAD_KEY_PASSWORD'
    $oldStore = [Environment]::GetEnvironmentVariable($storeVariable, 'Process')
    $oldEntry = [Environment]::GetEnvironmentVariable($entryVariable, 'Process')
    $special = 'entry \' + [char]0x03bb + '" quoted'
    $signingCases = @(
        @{ Name='defaults'; Store=$null; Entry=$null; Parameters=@{}; WantStore='sideload'; WantEntry='sideload' },
        @{ Name='shared-environment'; Store='store value'; Entry=$null; Parameters=@{}; WantStore='store value'; WantEntry='store value' },
        @{ Name='separate-environment'; Store='store value'; Entry='entry value'; Parameters=@{}; WantStore='store value'; WantEntry='entry value' },
        @{ Name='blank-store'; Store=$null; Entry=$null; Parameters=@{KeystorePassword=''; KeyPassword='entry value'}; WantStore=''; WantEntry='entry value' },
        @{ Name='blank-entry'; Store=$null; Entry=$null; Parameters=@{KeystorePassword='store value'; KeyPassword=''}; WantStore='store value'; WantEntry='' },
        @{ Name='quoted-entry'; Store=$null; Entry=$null; Parameters=@{KeystorePassword='store value'; KeyPassword=$special}; WantStore='store value'; WantEntry=$special },
        @{ Name='blank-store-environment-entry'; Store='ignored store'; Entry='entry value'; Parameters=@{KeystorePassword=''}; WantStore=''; WantEntry='entry value' },
        @{ Name='explicit-precedence'; Store='ignored store'; Entry='ignored entry'; Parameters=@{KeystorePassword='chosen store'; KeyPassword='chosen entry'}; WantStore='chosen store'; WantEntry='chosen entry' }
    )
    try {
        foreach ($case in $signingCases) {
            [Environment]::SetEnvironmentVariable($storeVariable, $case.Store, 'Process')
            [Environment]::SetEnvironmentVariable($entryVariable, $case.Entry, 'Process')
            [IO.File]::Delete($captured)
            $out = Join-Path $signingRoot $case.Name
            $parameters = $case.Parameters
            $refusal = $null
            try {
                & (Join-Path $Root 'scripts/patch-for-device.ps1') -Root $signingRoot -Apk $stock `
                    -DesktopJar $desktop -Java $java -Aapt2 $aapt -OutDir $out `
                    -Keystore (Join-Path $signingRoot 'fixture.keystore') @parameters
            } catch { $refusal = $_.Exception.Message }
            Assert-True ($refusal -ceq 'The desktop CLI exited with 17') "$($case.Name): the fixture did not reach the CLI refusal."
            $record = Get-Content -LiteralPath $captured -Raw | ConvertFrom-Json
            $storeIndex = [Array]::IndexOf([object[]]$record.arguments, '--keystore-password')
            $entryIndex = [Array]::IndexOf([object[]]$record.arguments, '--keystore-entry-password')
            Assert-True ($storeIndex -ge 0 -and $record.arguments[$storeIndex + 1] -ceq $case.WantStore) "$($case.Name): store password changed."
            Assert-True ($entryIndex -ge 0 -and $record.arguments[$entryIndex + 1] -ceq $case.WantEntry) "$($case.Name): entry password changed."
            Assert-True ($record.invocation.Count -eq 3 -and $record.invocation[2].StartsWith('@')) "$($case.Name): credentials left the argument file."
            Assert-True (-not (Test-Path -LiteralPath (Join-Path $out 'morphe-patch.args'))) "$($case.Name): the refused run left its credential file."
        }
    } finally {
        [Environment]::SetEnvironmentVariable($storeVariable, $oldStore, 'Process')
        [Environment]::SetEnvironmentVariable($entryVariable, $oldEntry, 'Process')
    }
    Write-Host "[scripts] device signing arguments: $($signingCases.Count) cases passed"

    # With no -Apk, the declared build's fixture comes from HUSHGRAM_FIXTURE_DIR, a split .apks
    # included, found the way the gate and the receipt find it. 0.0.17's .apks was skipped.
    $fixtureFolder = Join-Path $signingRoot 'fixtures'
    New-Item -ItemType Directory -Path $fixtureFolder -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $fixtureFolder 'notes-1.0.apk'), 'fixture')
    $splitFixture = Join-Path $fixtureFolder 'instagram-1.0-1-arm64-v8a.apks'
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
    $splitZip = [IO.Compression.ZipFile]::Open($splitFixture, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $writer = [IO.StreamWriter]::new($splitZip.CreateEntry('base.apk').Open())
        try { $writer.Write('fixture') } finally { $writer.Dispose() }
    } finally { $splitZip.Dispose() }
    $oldFixtureDir = [Environment]::GetEnvironmentVariable('HUSHGRAM_FIXTURE_DIR', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('HUSHGRAM_FIXTURE_DIR', $fixtureFolder, 'Process')
        [IO.File]::Delete($captured)
        $refusal = $null
        try {
            & (Join-Path $Root 'scripts/patch-for-device.ps1') -Root $signingRoot `
                -DesktopJar $desktop -Java $java -Aapt2 $aapt -OutDir (Join-Path $signingRoot 'from-fixtures') `
                -Keystore (Join-Path $signingRoot 'fixture.keystore')
        } catch { $refusal = $_.Exception.Message }
        Assert-True ($refusal -ceq 'The desktop CLI exited with 17') "The fixture folder's .apks wasn't patched: $refusal"
        $record = Get-Content -LiteralPath $captured -Raw | ConvertFrom-Json
        Assert-True ([string]$record.arguments[-1] -ceq $splitFixture) "The fixture folder gave the CLI $($record.arguments[-1]), not the declared build's .apks."
    } finally {
        [Environment]::SetEnvironmentVariable('HUSHGRAM_FIXTURE_DIR', $oldFixtureDir, 'Process')
    }
    Write-Host '[scripts] device fixture lookup: the declared .apks is patched'

} finally {
    if ($deviceLease -and $deviceLease.File.CanWrite) { Exit-HushgramDeviceLease $deviceLease }
    $resolvedLeaseDirectory = [IO.Path]::GetFullPath($leaseDirectory)
    if (-not $resolvedLeaseDirectory.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) { throw 'Lease test directory left the temporary workspace.' }
    if (Test-Path -LiteralPath $resolvedLeaseDirectory) { Remove-Item -LiteralPath $resolvedLeaseDirectory -Recurse -Force }
    [System.IO.File]::Delete($fixture)
}

$global:LASTEXITCODE = 0
Write-Host '[scripts] injected-register device cleanup contracts passed'
