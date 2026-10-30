<#
.SYNOPSIS
    Exercise the native feature runner's driver against a stand-in device.

.DESCRIPTION
    A run has to refuse a phone, a network that stays up and a device whose installed target isn't
    the APK it was given before it creates anything, bind every verdict to that APK, stop a case
    that runs too long, and remove exactly what it created, the test user, the runner and the
    network switch, on every path. The stand-in records each ADB call so those calls can be
    checked one by one. The runner itself is built once for real, with a throwaway key, and once
    from a source tree holding a class outside its package, which has to be refused. That real
    build then stands in for a target to check the patch record and a whole run.

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'native-runner-device.ps1')
. (Join-Path $PSScriptRoot 'script-wiring.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$target = [pscustomobject]@{
    Package = 'com.instagram.android'; VersionName = '453.0.0.57.79'; VersionCode = '385914557'
    Sha256 = 'a' * 64; SignerSha256 = 'b' * 64; BuildIdentity = 'hg1:' + ('c' * 64)
    SelectionSha256 = 'd' * 64; AppliedPatches = 113
}

function New-FakeDevice {
    param(
        [string]$Serial = 'emulator-5554',
        [int]$MaxUsers = 4,
        # How pm get-max-users words its answer; Android 17 says "Maximum supported switchable users".
        [string]$MaxUsersWording = 'Maximum supported users',
        [string]$Airplane = 'disabled',
        [string]$InstalledSha = $target.Sha256,
        [string[]]$InstalledPaths = @('/data/app/~~x/com.instagram.android-y/base.apk'),
        [switch]$RunnerPresent,
        [switch]$LeftoverUser,
        [string]$ReportedSigner = $target.SignerSha256,
        [string]$ReportedIdentity = $target.BuildIdentity,
        [switch]$AbsentHookFound,
        [string]$MissingHook = 'app.hushgram.extension.nativerunner.AbsentHook',
        [switch]$Crash,
        # The default network outlives airplane mode, or the target's process still sees one.
        [switch]$DefaultNetworkStaysUp,
        [switch]$ProcessNetworkUp,
        # This case never finishes, so the timeout ends it.
        [string]$HangCase,
        # This feature case fails, saying why.
        [string]$FailCase,
        # pm creates the user but answers something unreadable.
        [switch]$CreateUserGarbled,
        # The device drops off right after pm makes the user, so the user list can't be read.
        [switch]$ListUsersFailsAfterCreate,
        # The Android API level the device reports.
        [string]$Sdk = '36',
        # What settings answers for anr_show_background before the run ('null' when never set).
        [string]$AnrShown = 'null',
        [string]$FailAt,
        # A parameter, so GetNewClosure captures it rather than the script's copy.
        $Fixture = $target
    )
    $state = [pscustomobject]@{
        Calls = [System.Collections.Generic.List[string]]::new()
        Users = [System.Collections.Generic.List[string]]::new()
        RunnerUsers = [System.Collections.Generic.List[string]]::new()
        TargetUsers = [System.Collections.Generic.List[string]]::new()
        TargetInstalls = [System.Collections.Generic.List[string]]::new()
        Stopped = [System.Collections.Generic.List[string]]::new()
        Airplane = $Airplane
        AnrShown = $AnrShown
        AnrStart = $AnrShown
        NextUser = 10
        Created = $false
    }
    $state.Users.Add('0:Owner')
    $state.TargetUsers.Add('0')
    if ($LeftoverUser) { $state.Users.Add('11:hushgram-native-runner') }
    if ($RunnerPresent) { $state.RunnerUsers.Add('0') }
    $invoker = {
        param([string]$Executable, [string[]]$Arguments)
        $serial = $Arguments[1]
        $command = @($Arguments[2..($Arguments.Count - 1)])
        $line = $command -join ' '
        $state.Calls.Add($line)
        $out = @()
        $exitCode = 0
        if ($FailAt -and $line -like $FailAt) {
            return [pscustomobject]@{ ExitCode = 1; Output = @("Failure [$line]"); Stdout = @("Failure [$line]") }
        }
        switch -Wildcard ($line) {
            'get-serialno' { $out = @($serial) }
            'shell getprop ro.product.model' { $out = @('FixtureDevice') }
            'shell getprop ro.product.cpu.abi' { $out = @('x86_64') }
            'shell getprop ro.build.version.sdk' { $out = @($Sdk) }
            'emu avd name' { $out = @('FixtureAvd', 'OK') }
            'shell dumpsys package app.hushgram.nativerunner' {
                $out = if ($state.RunnerUsers.Count) { @('Packages:', '  Package [app.hushgram.nativerunner] (1a2b):') } else { @('Unable to find package: app.hushgram.nativerunner') }
            }
            'shell pm list users' {
                if ($ListUsersFailsAfterCreate -and $state.Created) { throw 'adb: device offline' }
                $out = @('Users:') + @($state.Users | ForEach-Object { "`tUserInfo{${_}:4c13} running" })
            }
            'shell pm path com.instagram.android' { $out = @($InstalledPaths | ForEach-Object { "package:$_" }) }
            'shell sha256sum *' { $out = @("$InstalledSha  $($command[2])") }
            'shell pm get-max-users' { $out = @("${MaxUsersWording}: $MaxUsers") }
            'shell cmd connectivity airplane-mode' { $out = @($state.Airplane) }
            'shell cmd connectivity airplane-mode enable' { $state.Airplane = 'enabled' }
            'shell cmd connectivity airplane-mode disable' { $state.Airplane = 'disabled' }
            'shell settings get secure anr_show_background' { $out = @($state.AnrShown) }
            'shell settings put secure anr_show_background *' { $state.AnrShown = $command[5] }
            'shell settings delete secure anr_show_background' { $state.AnrShown = 'null'; $out = @('Deleted 1 rows') }
            'shell dumpsys connectivity' {
                $default = if ($state.Airplane -ceq 'enabled' -and -not $DefaultNetworkStaysUp) { 'none' } else { '103' }
                $out = @('NetworkProviders for:', "Active default network: $default", 'Current Networks:')
            }
            'shell pm create-user hushgram-native-runner' {
                $id = [string]$state.NextUser
                $state.Users.Add("${id}:hushgram-native-runner")
                $state.Created = $true
                $out = if ($CreateUserGarbled) { @('Error: couldn''t report the new user') } else { @("Success: created user id $id") }
            }
            'shell am start-user -w *' { $out = @('Success: user started') }
            'shell pm install-existing --user * com.instagram.android' {
                $state.TargetUsers.Add($command[4])
                $out = @("Package com.instagram.android installed for user: $($command[4])")
            }
            'shell pm grant --user * com.instagram.android android.permission.POST_NOTIFICATIONS' { }
            'install --user * *' { $state.RunnerUsers.Add($command[2]); $out = @('Performing Streamed Install', 'Success') }
            'install -r *' { $state.TargetInstalls.Add($command[2]); $out = @('Performing Streamed Install', 'Success') }
            'shell am force-stop --user * com.instagram.android' { $state.Stopped.Add($command[4]) }
            'shell pm uninstall --user * app.hushgram.nativerunner' { [void]$state.RunnerUsers.Remove($command[4]); $out = @('Success') }
            'shell pm remove-user *' {
                $gone = $command[3]
                foreach ($row in @($state.Users)) { if ($row.StartsWith("${gone}:")) { [void]$state.Users.Remove($row) } }
                [void]$state.TargetUsers.Remove($gone)
                $out = @("Success: removed user")
            }
            'shell timeout * am instrument -w -r --user * -e case * app.hushgram.nativerunner/.Runner' {
                $user = $command[8]
                $case = $command[11]
                if ($case -ceq $HangCase) {
                    $exitCode = 124
                    break
                }
                if ($Crash) {
                    $out = @('INSTRUMENTATION_RESULT: shortMsg=Process crashed.', 'INSTRUMENTATION_CODE: 0')
                    break
                }
                $out = @("INSTRUMENTATION_RESULT: hushgram.case=$case",
                    "INSTRUMENTATION_RESULT: hushgram.package=$($Fixture.Package)",
                    "INSTRUMENTATION_RESULT: hushgram.versionName=$($Fixture.VersionName)",
                    "INSTRUMENTATION_RESULT: hushgram.versionCode=$($Fixture.VersionCode)",
                    "INSTRUMENTATION_RESULT: hushgram.signerSha256=$ReportedSigner",
                    "INSTRUMENTATION_RESULT: hushgram.baseApkSha256=$InstalledSha",
                    "INSTRUMENTATION_RESULT: hushgram.user=$user",
                    "INSTRUMENTATION_RESULT: hushgram.process=$($Fixture.Package)",
                    "INSTRUMENTATION_RESULT: hushgram.network=$(if ($ProcessNetworkUp -or $state.Airplane -cne 'enabled') { 'up' } else { 'none' })")
                $pass = $true
                if ($case -ceq 'control-positive') {
                    $out += "INSTRUMENTATION_RESULT: hushgram.buildIdentity=$ReportedIdentity"
                } elseif ($case -ceq 'control-negative') {
                    $pass = [bool]$AbsentHookFound
                    if (-not $pass) { $out += "INSTRUMENTATION_RESULT: hushgram.missingHook=$MissingHook" }
                } elseif ($case -ceq 'presence') {
                    $out += @('INSTRUMENTATION_RESULT: hushgram.presence.off=ACTIVE>ACTIVE,IDLE>IDLE,OFFLINE>OFFLINE,DISABLED>DISABLED',
                        'INSTRUMENTATION_RESULT: hushgram.presence.on=ACTIVE>IDLE,IDLE>IDLE,OFFLINE>OFFLINE,DISABLED>DISABLED')
                } elseif ($case -ceq 'presence-paused') {
                    $out += 'INSTRUMENTATION_RESULT: hushgram.presence.paused=ACTIVE>ACTIVE'
                } elseif ($case -ceq 'visual') {
                    $out += @('INSTRUMENTATION_RESULT: hushgram.visual.off=native', 'INSTRUMENTATION_RESULT: hushgram.visual.on=held')
                } elseif ($case -ceq 'visual-paused') {
                    $out += 'INSTRUMENTATION_RESULT: hushgram.visual.paused=native'
                } elseif ($case -ceq 'notifications') {
                    $out += @('INSTRUMENTATION_RESULT: hushgram.notifications.muted=held,held', 'INSTRUMENTATION_RESULT: hushgram.notifications.unmuted=shown')
                } elseif ($case -ceq 'share') {
                    $out += @('INSTRUMENTATION_RESULT: hushgram.share.off=kept', 'INSTRUMENTATION_RESULT: hushgram.share.on=cleaned')
                }
                if ($case -ceq $FailCase) {
                    $pass = $false
                    $out += "INSTRUMENTATION_RESULT: hushgram.detail=on: ACTIVE was stored as ACTIVE, expected IDLE"
                }
                $out += @("INSTRUMENTATION_RESULT: hushgram.verdict=$(if ($pass) { 'pass' } else { 'fail' })",
                    "INSTRUMENTATION_CODE: $(if ($pass) { -1 } else { 0 })")
            }
            default { throw "Unexpected stand-in ADB call: $line" }
        }
        [pscustomobject]@{ ExitCode = $exitCode; Output = $out; Stdout = $out }
    }.GetNewClosure()
    [pscustomobject]@{ State = $state; Invoker = $invoker; Serial = $Serial }
}

$leaseDirectory = Join-Path ([IO.Path]::GetTempPath()) ('hushgram-native-tests-' + [guid]::NewGuid().ToString('N'))
$work = Join-Path ([IO.Path]::GetTempPath()) ('hushgram-native-build-' + [guid]::NewGuid().ToString('N'))

function Invoke-FakeSession {
    param($Device)
    $avd = if ($Device.Serial.StartsWith('emulator-')) { 'FixtureAvd' } else { $null }
    $lease = Enter-HushgramDeviceLease -Serial $Device.Serial -ExpectedModel 'FixtureDevice' -ExpectedAvd $avd -Directory $leaseDirectory
    try {
        Confirm-HushgramDevice -Adb 'fake-adb' -DeviceLease $lease -AdbInvoker $Device.Invoker
        $Device.State.Calls.Clear()
        # No wait for the network: the stand-in answers at once.
        $session = Invoke-NativeRunnerSession -Adb 'fake-adb' -DeviceLease $lease -RunnerApk 'runner.apk' -Target $target `
            -NetworkWaitSeconds 0 -AdbInvoker $Device.Invoker
        [pscustomobject]@{ Session = $session; Error = $null }
    } catch {
        [pscustomobject]@{ Session = $null; Error = $_.Exception.Message }
    } finally { Exit-HushgramDeviceLease $lease }
}

function Assert-NothingLeft {
    param($Device, [string]$Context)
    $state = $Device.State
    Assert-True ($state.RunnerUsers.Count -eq 0) "${Context}: the runner was left installed."
    Assert-True (@($state.Users | Where-Object { $_ -like '*:hushgram-native-runner' }).Count -eq 0) "${Context}: the test user was left behind."
    Assert-True ($state.Airplane -ceq 'disabled') "${Context}: the network was left off."
    Assert-True ($state.AnrShown -ceq $state.AnrStart) "${Context}: background ANRs were left at '$($state.AnrShown)', not '$($state.AnrStart)'."
    Assert-True ($state.TargetUsers.Contains('0')) "${Context}: the target was taken from the owner."
    foreach ($call in $state.Calls) {
        Assert-True ($call -notmatch 'uninstall (?!--user [1-9]\d* app\.hushgram\.nativerunner$)|pm clear|remove-user 0\b|install -r|input |am start(?!-user)') "${Context}: a call reached beyond the run's own state: $call"
    }
}

try {
    # The output parser: a value that spans lines keeps them, and a start failure is kept apart.
    $parsed = ConvertFrom-InstrumentOutput @('INSTRUMENTATION_RESULT: hushgram.error=java.lang.Exception: first', 'second line',
        'INSTRUMENTATION_RESULT: hushgram.verdict=error', 'INSTRUMENTATION_CODE: 0')
    Assert-True ($parsed.Results['hushgram.error'] -ceq "java.lang.Exception: first`nsecond line") 'A value on two lines lost its second line.'
    Assert-True ($parsed.Code -eq 0 -and $parsed.Results['hushgram.verdict'] -ceq 'error' -and $null -eq $parsed.Failed) 'The result code or verdict was misread.'
    $failedStart = ConvertFrom-InstrumentOutput @('INSTRUMENTATION_STATUS: id=ActivityManagerService',
        'INSTRUMENTATION_STATUS: Error=Permission Denial: starting instrumentation', 'INSTRUMENTATION_STATUS_CODE: -1',
        'android.util.AndroidException: INSTRUMENTATION_FAILED: app.hushgram.nativerunner/.Runner', 'INSTRUMENTATION_FAILED: app.hushgram.nativerunner/.Runner')
    Assert-True ($failedStart.Failed -ceq 'app.hushgram.nativerunner/.Runner' -and $null -eq $failedStart.Code) 'A refused start was not read as one.'
    Assert-True ($failedStart.Status['Error'] -ceq 'Permission Denial: starting instrumentation') 'A status line took the exception line after it.'
    $refused = Get-NativeRunnerCaseVerdict -Parsed $failedStart -Target $target -User '10' -Case 'bind'
    Assert-True ($refused.Verdict -ceq 'error' -and $refused.Problems[0] -like '*did not start*Permission Denial*') 'A refused start was not an error.'
    # CRLF output with a trailing blank line, the way adb can hand it back.
    $trailing = ConvertFrom-InstrumentOutput @("INSTRUMENTATION_RESULT: hushgram.verdict=pass`r", "INSTRUMENTATION_CODE: -1`r", '', "`r")
    Assert-True ($trailing.Results['hushgram.verdict'] -ceq 'pass' -and $trailing.Code -eq -1) 'A carriage return or trailing blank line changed a value or the result code.'
    $lastValue = ConvertFrom-InstrumentOutput @("INSTRUMENTATION_RESULT: hushgram.network=none`r", '', '')
    Assert-True ($lastValue.Results['hushgram.network'] -ceq 'none') 'Blank lines after the last value were joined onto it.'
    Write-Host '[native] instrument output: 8 checks passed'

    # Binding: every field the runner measures has to equal the target APK's.
    $good = [ordered]@{
        'hushgram.case' = 'control-positive'; 'hushgram.package' = $target.Package; 'hushgram.versionName' = $target.VersionName
        'hushgram.versionCode' = $target.VersionCode; 'hushgram.signerSha256' = $target.SignerSha256
        'hushgram.baseApkSha256' = $target.Sha256; 'hushgram.user' = '10'; 'hushgram.process' = $target.Package
        'hushgram.network' = 'none'; 'hushgram.buildIdentity' = $target.BuildIdentity; 'hushgram.verdict' = 'pass'
    }
    Assert-True (@(Test-NativeRunnerBinding -Target $target -Results $good -User '10').Count -eq 0) 'A matching binding reported a difference.'
    $bindingFields = @('package', 'versionName', 'versionCode', 'signerSha256', 'baseApkSha256', 'user', 'process', 'network')
    foreach ($field in $bindingFields) {
        $changed = [ordered]@{}
        foreach ($key in $good.Keys) { $changed[$key] = $good[$key] }
        $changed["hushgram.$field"] = 'other'
        $verdict = Get-NativeRunnerCaseVerdict -Parsed ([pscustomobject]@{ Code = -1; Results = $changed; Status = @{}; Failed = $null }) `
            -Target $target -User '10' -Case 'control-positive'
        Assert-True ($verdict.Verdict -ceq 'error' -and ($verdict.Problems -join ' ') -like "*$field*") "A different $field still passed."
    }
    $passing = Get-NativeRunnerCaseVerdict -Parsed ([pscustomobject]@{ Code = -1; Results = $good; Status = @{}; Failed = $null }) `
        -Target $target -User '10' -Case 'control-positive'
    Assert-True ($passing.Verdict -ceq 'pass' -and $passing.Problems.Count -eq 0) 'A bound positive control did not pass.'
    $disagreeing = Get-NativeRunnerCaseVerdict -Parsed ([pscustomobject]@{ Code = 0; Results = $good; Status = @{}; Failed = $null }) `
        -Target $target -User '10' -Case 'control-positive'
    Assert-True ($disagreeing.Verdict -ceq 'error') 'A pass with a cancelled result code was believed.'
    $otherCase = Get-NativeRunnerCaseVerdict -Parsed ([pscustomobject]@{ Code = -1; Results = $good; Status = @{}; Failed = $null }) `
        -Target $target -User '10' -Case 'control-negative'
    Assert-True ($otherCase.Verdict -ceq 'error') 'An answer for another case was taken.'
    $signedInOnly = [ordered]@{}
    foreach ($key in $good.Keys) { $signedInOnly[$key] = $good[$key] }
    $signedInOnly['hushgram.verdict'] = 'needs-session'
    $signedInOnly['hushgram.prerequisite'] = 'a signed-in account with a direct thread'
    $needed = Get-NativeRunnerCaseVerdict -Parsed ([pscustomobject]@{ Code = 0; Results = $signedInOnly; Status = @{}; Failed = $null }) `
        -Target $target -User '10' -Case 'control-positive'
    Assert-True ($needed.Verdict -ceq 'needs-session' -and $needed.Prerequisite -ceq 'a signed-in account with a direct thread' -and
        $needed.Problems.Count -eq 0) 'A case needing a session was not reported with its prerequisite.'
    $signedInOnly['hushgram.prerequisite'] = ''
    $unnamed = Get-NativeRunnerCaseVerdict -Parsed ([pscustomobject]@{ Code = 0; Results = $signedInOnly; Status = @{}; Failed = $null }) `
        -Target $target -User '10' -Case 'control-positive'
    Assert-True ($unnamed.Verdict -ceq 'error') 'A session need that names nothing was accepted.'
    $hung = Get-NativeRunnerCaseVerdict -Parsed (ConvertFrom-InstrumentOutput @()) -Target $target -User '10' -Case 'bind' `
        -ExitCode 124 -Output @() -TimeoutSeconds 120
    Assert-True ($hung.Verdict -ceq 'error' -and $hung.Problems[0] -like '*after 120 seconds*') "A case the timeout ended read as: $($hung.Problems -join '; ')"
    $cut = Get-NativeRunnerCaseVerdict -Parsed (ConvertFrom-InstrumentOutput @('INSTRUMENTATION_STATUS: id=x')) -Target $target -User '10' -Case 'bind' `
        -ExitCode 255 -Output @('first', 'second', 'adb: device offline') -TimeoutSeconds 120
    Assert-True ($cut.Verdict -ceq 'error' -and $cut.Problems[0] -like '*exit 255*adb: device offline*') "A run with no result code hid its exit and output: $($cut.Problems -join '; ')"
    Write-Host "[native] binding: $($bindingFields.Count + 7) checks passed"

    # A whole run on an emulator: a new user, the network off for the run, both controls, the
    # feature cases, then the user, the runner and the network switch put back.
    $device = New-FakeDevice
    $run = Invoke-FakeSession $device
    Assert-True ($null -eq $run.Error) "A clean run failed: $($run.Error)"
    $session = $run.Session
    Assert-True ($session.ControlsProven -and $session.FeaturesPassed -and $session.IsolatedUser -and $session.NetworkOff -and $session.User -ceq '10') 'A clean run did not prove the controls and pass the features in its own user.'
    Assert-True ((@($session.Cases | ForEach-Object { "$($_.Case)=$($_.Verdict)" }) -join ',') -ceq 'bind=pass,control-positive=pass,control-negative=fail,presence=pass,presence-paused=pass,visual=pass,visual-paused=pass,notifications=pass,share=pass') 'The cases ran out of order or with the wrong verdicts.'
    Assert-True ($session.Cases[2].MissingHook -ceq $NativeRunnerAbsentHook) 'The negative control did not name its missing hook.'
    Assert-True ($session.Cases[3].Observed['presence.on'] -ceq 'ACTIVE>IDLE,IDLE>IDLE,OFFLINE>OFFLINE,DISABLED>DISABLED' -and
        $session.Cases[3].Observed.Count -eq 2 -and $session.Cases[0].Observed.Count -eq 0) 'What a case observed was lost, or a bound fact was taken for an observation.'
    $calls = @($device.State.Calls)
    $created = [Array]::IndexOf($calls, 'shell pm create-user hushgram-native-runner')
    Assert-True ($created -gt [Array]::IndexOf($calls, 'shell sha256sum /data/app/~~x/com.instagram.android-y/base.apk')) 'A user was created before the installed target was checked.'
    $networkOffAt = [Array]::IndexOf($calls, 'shell cmd connectivity airplane-mode enable')
    $networkSeenAt = [Array]::IndexOf($calls, 'shell dumpsys connectivity')
    Assert-True ($networkOffAt -ge 0 -and $networkSeenAt -gt $networkOffAt -and $networkSeenAt -lt $created) 'The test user existed before the network was seen off.'
    $granted = [Array]::IndexOf($calls, 'shell pm grant --user 10 com.instagram.android android.permission.POST_NOTIFICATIONS')
    $firstCase = [Array]::IndexOf($calls, @($calls | Where-Object { $_ -like '*am instrument*' })[0])
    Assert-True ($granted -gt [Array]::IndexOf($calls, 'shell pm install-existing --user 10 com.instagram.android') -and $granted -lt $firstCase) 'The target could not post notifications when the first case ran.'
    # A slow first start in the new user has to survive its ANR rather than be killed silently.
    $shownAt = [Array]::IndexOf($calls, 'shell settings put secure anr_show_background 1')
    Assert-True ($shownAt -gt $networkSeenAt -and $shownAt -lt $created) 'Background ANRs were not shown before the test user existed.'
    # Before Android 13 there is no such permission to grant, so pm would refuse it and end the run.
    $older = New-FakeDevice -Sdk '32'
    $olderRun = Invoke-FakeSession $older
    Assert-True ($null -eq $olderRun.Error -and $olderRun.Session.FeaturesPassed) "A run on Android 12L failed: $($olderRun.Error)"
    Assert-True (@($older.State.Calls | Where-Object { $_ -like 'shell pm grant *' }).Count -eq 0) 'A run on Android 12L asked for a permission that release lacks.'
    Assert-NothingLeft $older 'A run on Android 12L'
    $android17 = New-FakeDevice -Sdk '37' -MaxUsersWording 'Maximum supported switchable users'
    $android17Run = Invoke-FakeSession $android17
    Assert-True ($null -eq $android17Run.Error -and $android17Run.Session.FeaturesPassed) "A run on Android 17 failed: $($android17Run.Error)"
    Assert-NothingLeft $android17 'A run on Android 17'
    Assert-True (@($calls | Where-Object { $_ -like 'shell timeout 120 am instrument *' }).Count -eq 9) 'A case ran without its time limit.'
    Assert-True ($device.State.Stopped.Count -eq 0) 'A run that finished in time stopped the target.'
    Assert-True ($calls[-4] -ceq 'shell pm uninstall --user 10 app.hushgram.nativerunner' -and $calls[-3] -ceq 'shell pm remove-user 10' -and
        $calls[-2] -ceq 'shell settings delete secure anr_show_background' -and
        $calls[-1] -ceq 'shell cmd connectivity airplane-mode disable') "The cleanup ran out of order: $($calls[-4..-1] -join '; ')"
    Assert-NothingLeft $device 'A clean run'
    # A value that was set goes back as it was, and one already on is left alone.
    $quiet = New-FakeDevice -AnrShown '0'
    $quietRun = Invoke-FakeSession $quiet
    Assert-True ($null -eq $quietRun.Error -and $quiet.State.Calls -contains 'shell settings put secure anr_show_background 0') "Background ANRs set to 0 were not put back: $($quietRun.Error)"
    Assert-NothingLeft $quiet 'A run with background ANRs set to 0'
    $shown = New-FakeDevice -AnrShown '1'
    $shownRun = Invoke-FakeSession $shown
    Assert-True ($null -eq $shownRun.Error -and @($shown.State.Calls | Where-Object { $_ -match '^shell settings (put|delete) ' }).Count -eq 0) "A run changed background ANRs that were already shown: $($shownRun.Error)"
    # An answer that isn't a number stops the run before any user exists, with the network put back.
    $odd = New-FakeDevice -AnrShown 'maybe'
    $oddRun = Invoke-FakeSession $odd
    Assert-True ($oddRun.Error -like "*background ANRs answered 'maybe'*") "An unreadable ANR setting was not refused: $($oddRun.Error)"
    Assert-True (@($odd.State.Calls | Where-Object { $_ -match 'create-user|^shell settings (put|delete) ' }).Count -eq 0) 'An unreadable ANR setting still changed it or made a user.'
    Assert-NothingLeft $odd 'An unreadable ANR setting'

    # Everything that has to stop a run before it creates anything.
    $refusals = @(
        @{ Name = 'a different installed target'; Device = (New-FakeDevice -InstalledSha ('e' * 64)); Want = "*isn't the target APK*" },
        @{ Name = 'a split install'; Device = (New-FakeDevice -InstalledPaths @('/data/app/x/base.apk', '/data/app/x/split_a.apk')); Want = '*2 APK file(s)*' },
        @{ Name = 'a runner from another run'; Device = (New-FakeDevice -RunnerPresent); Want = '*already on emulator-5554*' },
        @{ Name = 'a test user from another run'; Device = (New-FakeDevice -LeftoverUser); Want = '*User 11*already on*' },
        @{ Name = 'a single-user device'; Device = (New-FakeDevice -MaxUsers 1); Want = '*supports one Android user*' },
        @{ Name = 'a single-user Android 17 device'; Device = (New-FakeDevice -Sdk '37' -MaxUsers 1 -MaxUsersWording 'Maximum supported switchable users'); Want = '*supports one Android user*' },
        @{ Name = 'a phone'; Device = (New-FakeDevice -Serial 'PHONE0SERIAL'); Want = '*PHONE0SERIAL is not an emulator*' }
    )
    foreach ($refusal in $refusals) {
        $run = Invoke-FakeSession $refusal.Device
        Assert-True ($run.Error -like $refusal.Want) "$($refusal.Name) was not refused as expected: $($run.Error)"
        $writes = @($refusal.Device.State.Calls | Where-Object { $_ -match 'create-user|airplane-mode (enable|disable)|^install|uninstall|remove-user|^shell settings (put|delete) ' })
        Assert-True ($writes.Count -eq 0) "$($refusal.Name) changed the device before refusing: $($writes -join '; ')"
    }
    Assert-True ($refusals[-1].Device.State.Calls.Count -eq 0) 'A phone was asked anything past its identity.'

    # Airplane mode on but a network still up: refused before any user exists, network put back.
    $online = New-FakeDevice -DefaultNetworkStaysUp
    $run = Invoke-FakeSession $online
    Assert-True ($run.Error -like '*still up*default network 103*') "A network that stayed up was not refused: $($run.Error)"
    Assert-True (@($online.State.Calls | Where-Object { $_ -match 'create-user|install' }).Count -eq 0) 'A user or the runner went onto a device with the network up.'
    Assert-NothingLeft $online 'A network that stayed up'

    # pm makes the user but its answer can't be read: the user is found by name and removed.
    $garbled = New-FakeDevice -CreateUserGarbled
    $run = Invoke-FakeSession $garbled
    Assert-True ($run.Error -like '*test user was not created*') "An unreadable create-user answer was not reported: $($run.Error)"
    Assert-True ($garbled.State.Calls -contains 'shell pm remove-user 10') 'A user pm made without saying so was left on the device.'
    Assert-NothingLeft $garbled 'An unreadable create-user answer'

    # The device drops off before that lookup: the original failure still comes back, it says to
    # check for the user, and the network is still put back.
    $dropped = New-FakeDevice -CreateUserGarbled -ListUsersFailsAfterCreate
    $run = Invoke-FakeSession $dropped
    Assert-True ($run.Error -like '*test user was not created*Listing the users afterwards failed too*device offline*check for a*') "A failed user lookup hid the create-user failure: $($run.Error)"
    Assert-True ($dropped.State.Airplane -ceq 'disabled' -and $dropped.State.RunnerUsers.Count -eq 0) 'A failed user lookup left the network off or the runner on.'

    # A case that never finishes: the timeout ends it, the target is stopped in the test user and
    # no later case runs.
    $hanging = New-FakeDevice -HangCase 'control-positive'
    $run = Invoke-FakeSession $hanging
    Assert-True ($null -eq $run.Error) "A hung case threw instead of failing: $($run.Error)"
    $seen = (@($run.Session.Cases | ForEach-Object { "$($_.Case)=$($_.Verdict)" }) -join ',')
    Assert-True ($seen -ceq 'bind=pass,control-positive=error' -and -not $run.Session.ControlsProven) "A hung case gave $seen."
    Assert-True ($run.Session.Cases[1].Problems[0] -like '*after 120 seconds*') 'A hung case did not say it ran out of time.'
    Assert-True ((@($hanging.State.Stopped) -join ',') -ceq '10') 'The hung target was not stopped in the test user alone.'
    Assert-NothingLeft $hanging 'A hung case'
    $hangingFeature = New-FakeDevice -HangCase 'presence'
    $run = Invoke-FakeSession $hangingFeature
    $seen = (@($run.Session.Cases | ForEach-Object { "$($_.Case)=$($_.Verdict)" }) -join ',')
    Assert-True ($null -eq $run.Error -and $seen -ceq 'bind=pass,control-positive=pass,control-negative=fail,presence=error' -and
        $run.Session.ControlsProven -and -not $run.Session.FeaturesPassed) "A hung feature case gave $seen."
    Assert-True ((@($hangingFeature.State.Stopped) -join ',') -ceq '10') 'The hung feature case was not stopped.'
    Assert-NothingLeft $hangingFeature 'A hung feature case'

    # A feature case that fails: its reason comes back, and the case built on it is skipped.
    $failing = New-FakeDevice -FailCase 'presence'
    $run = Invoke-FakeSession $failing
    $seen = (@($run.Session.Cases | ForEach-Object { "$($_.Case)=$($_.Verdict)" }) -join ',')
    Assert-True ($null -eq $run.Error -and $seen -ceq 'bind=pass,control-positive=pass,control-negative=fail,presence=fail,presence-paused=skipped,visual=pass,visual-paused=pass,notifications=pass,share=pass' -and
        $run.Session.ControlsProven -and -not $run.Session.FeaturesPassed) "A failed feature case gave $seen."
    Assert-True ($run.Session.Cases[3].Detail -ceq 'on: ACTIVE was stored as ACTIVE, expected IDLE' -and
        $run.Session.Cases[4].Problems[0] -like '*builds on presence*') 'A failed feature case lost its reason, or the skip gave none.'
    Assert-True (@($failing.State.Calls | Where-Object { $_ -like '*-e case presence-paused *' }).Count -eq 0) 'A case ran on settings its basis never saved.'
    Assert-NothingLeft $failing 'A failed feature case'
    # A later case's basis is its own: visual failing skips only visual-paused.
    $failingLater = New-FakeDevice -FailCase 'visual'
    $run = Invoke-FakeSession $failingLater
    $seen = (@($run.Session.Cases | ForEach-Object { "$($_.Case)=$($_.Verdict)" }) -join ',')
    Assert-True ($null -eq $run.Error -and $seen -ceq 'bind=pass,control-positive=pass,control-negative=fail,presence=pass,presence-paused=pass,visual=fail,visual-paused=skipped,notifications=pass,share=pass' -and
        -not $run.Session.FeaturesPassed -and $run.Session.Cases[6].Problems[0] -like '*builds on visual*') "A failed later feature case gave $seen."
    Assert-True (@($failingLater.State.Calls | Where-Object { $_ -like '*-e case visual-paused *' }).Count -eq 0) 'visual-paused ran on settings visual never saved.'
    Assert-NothingLeft $failingLater 'A failed later feature case'

    # The network was already off: it stays off and nothing turns it on.
    $offline = New-FakeDevice -Airplane 'enabled'
    $run = Invoke-FakeSession $offline
    Assert-True ($null -eq $run.Error -and $offline.State.Airplane -ceq 'enabled' -and
        @($offline.State.Calls | Where-Object { $_ -like '*airplane-mode disable' -or $_ -like '*airplane-mode enable' }).Count -eq 0) 'A run changed a network that was already off.'

    # Controls that don't hold, and a binding that doesn't, each with the device put back.
    $controlCases = @(
        @{ Name = 'a wrong build identity'; Device = (New-FakeDevice -ReportedIdentity ('hg1:' + ('f' * 64))); Cases = 'bind=pass,control-positive=fail,control-negative=fail' },
        @{ Name = 'a lookup that cannot fail'; Device = (New-FakeDevice -AbsentHookFound); Cases = 'bind=pass,control-positive=pass,control-negative=pass' },
        @{ Name = 'a negative control missing another hook'; Device = (New-FakeDevice -MissingHook 'app.hushgram.extension.Other'); Cases = 'bind=pass,control-positive=pass,control-negative=fail' },
        @{ Name = 'a different signer in the process'; Device = (New-FakeDevice -ReportedSigner ('9' * 64)); Cases = 'bind=error' },
        @{ Name = 'a network inside the process'; Device = (New-FakeDevice -ProcessNetworkUp); Cases = 'bind=error' },
        @{ Name = 'a crashed target'; Device = (New-FakeDevice -Crash); Cases = 'bind=error' }
    )
    foreach ($case in $controlCases) {
        $run = Invoke-FakeSession $case.Device
        Assert-True ($null -eq $run.Error) "$($case.Name) threw instead of failing the controls: $($run.Error)"
        $seen = (@($run.Session.Cases | ForEach-Object { "$($_.Case)=$($_.Verdict)" }) -join ',')
        Assert-True (-not $run.Session.ControlsProven -and -not $run.Session.FeaturesPassed -and $seen -ceq $case.Cases) "$($case.Name) gave $seen."
        Assert-True (@($case.Device.State.Calls | Where-Object { $_ -match '-e case (\S+)' -and $NativeRunnerFeatureCases.Contains($Matches[1]) }).Count -eq 0) "$($case.Name) still ran a feature case."
        Assert-NothingLeft $case.Device $case.Name
    }

    # A failure partway through still removes what was created, and a failed cleanup is reported.
    $failedInstall = New-FakeDevice -FailAt 'install --user *'
    $run = Invoke-FakeSession $failedInstall
    Assert-True ($run.Error -like '*Could not install the runner*') "A refused runner install was not reported: $($run.Error)"
    Assert-True (@($failedInstall.State.Calls | Where-Object { $_ -like '*am instrument*' }).Count -eq 0) 'A case ran without its runner.'
    Assert-NothingLeft $failedInstall 'A refused runner install'
    $stuckUser = New-FakeDevice -FailAt 'shell pm remove-user *'
    $run = Invoke-FakeSession $stuckUser
    Assert-True ($run.Error -like '*cleanup failed*remove user 10*') "A user that could not be removed went unreported: $($run.Error)"
    Assert-True ($stuckUser.State.Airplane -ceq 'disabled' -and $stuckUser.State.RunnerUsers.Count -eq 0 -and $stuckUser.State.AnrShown -ceq 'null') 'One failed cleanup step skipped the others.'

    # No lease, no call.
    $unleased = New-FakeDevice
    $denied = $false
    try { [void](Invoke-NativeRunnerSession -Adb 'fake-adb' -DeviceLease $null -RunnerApk 'runner.apk' -Target $target -AdbInvoker $unleased.Invoker) }
    catch { $denied = $true }
    Assert-True ($denied -and $unleased.State.Calls.Count -eq 0) 'A run reached a device without a lease.'
    Write-Host "[native] device session: $($refusals.Count + $controlCases.Count + 15) cases passed"

    # The build identity read off the target APK's dex: exactly one, or a refusal.
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $identityCases = @(
        @{ Name = 'one'; Dex = @{ 'classes.dex' = "x$($target.BuildIdentity)x"; 'classes2.dex' = "y$($target.BuildIdentity)" }; Want = $target.BuildIdentity },
        @{ Name = 'none'; Dex = @{ 'classes.dex' = 'hg1:short' }; Want = $null },
        @{ Name = 'two'; Dex = @{ 'classes.dex' = $target.BuildIdentity; 'classes3.dex' = 'hg1:' + ('0' * 64) }; Want = $null },
        @{ Name = 'outside the dex'; Dex = @{ 'assets/identity.txt' = $target.BuildIdentity }; Want = $null }
    )
    foreach ($case in $identityCases) {
        $zipPath = Join-Path $work "identity-$($case.Name.Replace(' ', '-')).apk"
        $zip = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($entry in $case.Dex.Keys) {
                $writer = [IO.StreamWriter]::new($zip.CreateEntry($entry).Open(), [Text.Encoding]::ASCII)
                try { $writer.Write("dex`n035" + $case.Dex[$entry]) } finally { $writer.Dispose() }
            }
        } finally { $zip.Dispose() }
        $read = $null
        try { $read = Get-ApkBuildIdentity -Apk $zipPath } catch { $read = $null }
        Assert-True ($read -ceq $case.Want) "An APK with $($case.Name) identity read as '$read'."
    }
    Write-Host "[native] build identity: $($identityCases.Count) cases passed"

    # The runner keeps the target's startup out, and asks for nothing.
    $runnerSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'native-runner/src/app/hushgram/nativerunner/Runner.java') -Raw
    Assert-True ($runnerSource -match 'public void callApplicationOnCreate\(Application app\)\s*\{\s*\}') 'The runner calls the target Application.onCreate.'
    foreach ($featureCase in $NativeRunnerFeatureCases.Keys) {
        Assert-True ($runnerSource.Contains("case `"$featureCase`":")) "The runner has no $featureCase case to run."
    }
    $manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'native-runner/AndroidManifest.xml') -Raw
    Assert-True ($manifest -notmatch '<(uses-permission|activity|service|receiver|provider)\b') 'The runner manifest declares a permission or a component.'

    # A real build with a throwaway key, then a source tree that tries to put a class in the
    # target's own namespace.
    $tools = Resolve-NativeRunnerTools -Java $env:HUSHGRAM_JAVA
    $keystore = Join-Path $work 'runner-test.p12'
    $keytool = Find-NativeRunnerSdkTool (Split-Path -Parent $tools.Java) 'keytool'
    [void](Invoke-NativeRunnerTool -Executable $keytool -Arguments @('-genkeypair', '-keystore', $keystore, '-storetype', 'PKCS12',
        '-storepass', 'runner-test', '-alias', 'runner', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2',
        '-dname', 'CN=HushGram runner test') -Failure 'keytool could not make the test key')
    $built = New-NativeRunnerApk -Tools $tools -TargetPackage 'com.instagram.android' -WorkDirectory (Join-Path $work 'real') `
        -Keystore $keystore -KeyAlias 'runner' -KeystorePassword 'runner-test' -KeyPassword 'runner-test'
    $facts = Get-ApkManifestFacts -Apk $built -Aapt2 $tools.Aapt2
    Assert-True ($facts.package -ceq $NativeRunnerPackage) "The runner built as $($facts.package)."
    $zip = [IO.Compression.ZipFile]::OpenRead($built)
    try { $entries = @($zip.Entries | ForEach-Object FullName) } finally { $zip.Dispose() }
    Assert-True (@($entries | Where-Object { $_ -match '^classes\d*\.dex$' }).Count -eq 1) 'The runner carries other than one dex.'
    Assert-True ((Get-ApkSignerDigest -Tools $tools -Apk $built) -match '^[0-9a-f]{64}$') 'The runner was not signed.'
    $passwordsLeft = @('HUSHGRAM_NATIVE_RUNNER_STORE_PASSWORD', 'HUSHGRAM_NATIVE_RUNNER_KEY_PASSWORD' |
        Where-Object { [Environment]::GetEnvironmentVariable($_) })
    Assert-True ($passwordsLeft.Count -eq 0) 'The signing passwords outlived the build.'
    $intruder = Join-Path $work 'intruder'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'native-runner') -Destination $intruder -Recurse
    $foreignDirectory = Join-Path $intruder 'src/com/instagram/mainactivity'
    New-Item -ItemType Directory -Force -Path $foreignDirectory | Out-Null
    [IO.File]::WriteAllText((Join-Path $foreignDirectory 'InstagramMainActivity.java'),
        "package com.instagram.mainactivity;`npublic class InstagramMainActivity {}`n")
    $refusal = $null
    try {
        [void](New-NativeRunnerApk -Tools $tools -TargetPackage 'com.instagram.android' -WorkDirectory (Join-Path $work 'foreign') `
            -Keystore $keystore -KeyAlias 'runner' -KeystorePassword 'runner-test' -KeyPassword 'runner-test' -Source $intruder)
    } catch { $refusal = $_.Exception.Message }
    Assert-True ($refusal -like '*only hold its own package*') "A runner holding a class of the target's was built: $refusal"
    Write-Host '[native] runner build: 6 checks passed'

    # The patch record: an APK's content past its signing files, and a target whose selection
    # counts only through a record of its own patch run.
    function New-TestZip {
        param([string]$Path, [System.Collections.IDictionary]$Entries)
        $zip = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($name in $Entries.Keys) {
                $writer = [IO.StreamWriter]::new($zip.CreateEntry($name).Open(), [Text.Encoding]::ASCII)
                try { $writer.Write($Entries[$name]) } finally { $writer.Dispose() }
            }
        } finally { $zip.Dispose() }
        $Path
    }
    $signedOnce = New-TestZip (Join-Path $work 'record-a.apk') ([ordered]@{ 'classes.dex' = 'dex one'; 'res/' = ''; 'META-INF/CERT.SF' = 'first key'; 'stamp-cert-sha256' = 'stamp' })
    $signedAgain = New-TestZip (Join-Path $work 'record-b.apk') ([ordered]@{ 'META-INF/OTHER.RSA' = 'second key'; 'classes.dex' = 'dex one' })
    $otherDex = New-TestZip (Join-Path $work 'record-c.apk') ([ordered]@{ 'classes.dex' = 'dex two'; 'META-INF/CERT.SF' = 'first key' })
    $renamed = New-TestZip (Join-Path $work 'record-d.apk') ([ordered]@{ 'classes2.dex' = 'dex one' })
    $onlySigning = New-TestZip (Join-Path $work 'record-e.apk') ([ordered]@{ 'META-INF/MANIFEST.MF' = 'x' })
    $content = Get-ApkContentSha256 -Apk $signedOnce
    Assert-True ($content -match '^[0-9a-f]{64}$' -and $content -ceq (Get-ApkContentSha256 -Apk $signedAgain)) 'Signing again changed the content hash.'
    Assert-True ($content -cne (Get-ApkContentSha256 -Apk $otherDex)) 'Another dex kept the content hash.'
    Assert-True ($content -cne (Get-ApkContentSha256 -Apk $renamed)) 'A renamed entry kept the content hash.'
    $refusal = $null
    try { [void](Get-ApkContentSha256 -Apk $onlySigning) } catch { $refusal = $_.Exception.Message }
    Assert-True ($refusal -like '*no entries outside its signing files*') "An APK of signing files alone was hashed: $refusal"
    $recordResult = Join-Path $work 'record-result.json'
    [IO.File]::WriteAllText($recordResult, '{"packageName":"x"}')
    $recordFile = Join-Path $work 'record.json'
    Write-PatchRecord -Apk $signedOnce -Result $recordResult -Path $recordFile
    Assert-True ($null -eq (Test-PatchRecord -Apk $signedAgain -Result $recordResult -Record $recordFile)) 'A re-signed APK lost its patch record.'
    Assert-True ((Test-PatchRecord -Apk $otherDex -Result $recordResult -Record $recordFile) -like '*record-c.apk is not the APK*') 'Another APK passed for the patched one.'
    $otherResult = Join-Path $work 'record-result-2.json'
    [IO.File]::WriteAllText($otherResult, '{"packageName":"y"}')
    Assert-True ((Test-PatchRecord -Apk $signedOnce -Result $otherResult -Record $recordFile) -like '*not the one the record*') 'Another patch result passed for the recorded one.'
    Assert-True ((Test-PatchRecord -Apk $signedOnce -Result $recordResult -Record (Join-Path $work 'none.json')) -like '*no patch record*') 'A missing record passed.'
    # The real runner APK stands in for a target: past the record, it stops at the build identity.
    $runnerResult = Join-Path (Split-Path -Parent $built) 'result.json'
    [IO.File]::WriteAllText($runnerResult, '{"packageName":"app.hushgram.nativerunner","packageVersion":"1","appliedPatches":[{"name":"Hide ads"}]}')
    $runnerRecord = Join-Path (Split-Path -Parent $built) 'patch-record.json'
    $targetRefusals = [System.Collections.Generic.List[string]]::new()
    foreach ($recordFor in @($null, $signedOnce, $built)) {
        if ($recordFor) { Write-PatchRecord -Apk $recordFor -Result $runnerResult -Path $runnerRecord }
        try { [void](Get-NativeRunnerTarget -Tools $tools -Apk $built); $targetRefusals.Add('(read)') } catch { $targetRefusals.Add($_.Exception.Message) }
    }
    Assert-True ($targetRefusals[0] -like '*no patch record*') "A target with no patch record was read: $($targetRefusals[0])"
    Assert-True ($targetRefusals[1] -like '*runner.apk is not the APK that patch run produced*') "A record of another APK vouched for the target: $($targetRefusals[1])"
    Assert-True ($targetRefusals[2] -like '*0 HushGram build identities*') "A matching record did not get past the record check: $($targetRefusals[2])"
    # A record or result that repeats a field reads two ways, so it vouches for nothing, fresh hashes or not.
    $twiceRecord = Join-Path $work 'record-twice.json'
    [IO.File]::WriteAllText($twiceRecord, ((Get-Content -LiteralPath $recordFile -Raw) -replace '\A\s*\{', '{"schemaVersion":1,'))
    $twiceRefusal = Test-PatchRecord -Apk $signedAgain -Result $recordResult -Record $twiceRecord
    Assert-True ($twiceRefusal -like "*doesn't parse*repeats an object field*") "A record that repeats a field vouched for the APK: $twiceRefusal"
    [IO.File]::WriteAllText($runnerResult, '{"packageName":"app.hushgram.nativerunner","packageVersion":"1","appliedPatches":[{"name":"Hide ads"}],"appliedPatches":[]}')
    Write-PatchRecord -Apk $built -Result $runnerResult -Path $runnerRecord
    $twiceTarget = $null
    try { [void](Get-NativeRunnerTarget -Tools $tools -Apk $built) } catch { $twiceTarget = $_.Exception.Message }
    Assert-True ($twiceTarget -like "*doesn't parse*repeats an object field*") "A patch result that repeats a field named the target's selection: $twiceTarget"
    [IO.File]::WriteAllText($runnerResult, '{"packageName":"app.hushgram.nativerunner","packageVersion":"1","appliedPatches":[{"name":"Hide ads"}]}')
    Write-PatchRecord -Apk $built -Result $runnerResult -Path $runnerRecord
    Write-Host '[native] patch record: 14 checks passed'

    # A whole run: an old verdict goes first, the signer and the register gate come before the
    # lease, and the verdict file records the run.
    $runnerSignerDigest = Get-ApkSignerDigest -Tools $tools -Apk $built
    $boundTarget = $target.PSObject.Copy()
    $boundTarget.SignerSha256 = $runnerSignerDigest
    $wholeDirectory = Join-Path $work 'whole'
    New-Item -ItemType Directory -Force -Path $wholeDirectory | Out-Null
    $wholeApk = Join-Path $wholeDirectory 'target.apk'
    $wholeVerdict = Join-Path $wholeDirectory 'native-runner-verdict.json'
    $leaseFile = Join-Path $leaseDirectory 'emulator-5554.json'
    $runnerCopy = { param($Tools, $Target, $WorkDirectory, $Signing) $copy = Join-Path $WorkDirectory 'runner.apk'; Copy-Item -LiteralPath $built -Destination $copy; $copy }.GetNewClosure()
    function Invoke-WholeRun {
        param($Device, $RunTarget, [switch]$Install, [scriptblock]$Gate, [scriptblock]$Reader)
        if (-not $Reader) { $Reader = { $RunTarget }.GetNewClosure() }
        try {
            $whole = Invoke-NativeRunner -Tools $tools -TargetApk $wholeApk -Verdict $wholeVerdict -Serial $Device.Serial `
                -ExpectedModel 'FixtureDevice' -ExpectedAvd 'FixtureAvd' -Install:$Install -LeaseDirectory $leaseDirectory `
                -TargetReader $Reader -RunnerBuilder $runnerCopy -RegisterGate $Gate -AdbInvoker $Device.Invoker
            [pscustomobject]@{ Session = $whole; Error = $null }
        } catch { [pscustomobject]@{ Session = $null; Error = $_.Exception.Message } }
    }
    $gateSaw = [System.Collections.Generic.List[string]]::new()
    $passingGate = { param($Tools, $Apk) $gateSaw.Add("$Apk|$(Test-Path -LiteralPath $leaseFile)"); $true }.GetNewClosure()
    [IO.File]::WriteAllText($wholeVerdict, '{"controlsProven":true}')
    $device = New-FakeDevice -ReportedSigner $runnerSignerDigest -Fixture $boundTarget
    $whole = Invoke-WholeRun $device $boundTarget -Install -Gate $passingGate
    Assert-True ($null -eq $whole.Error -and $whole.Session.ControlsProven) "A whole run failed: $($whole.Error)"
    Assert-True ((@($gateSaw) -join ',') -ceq "$wholeApk|False") "The register gate ran with the device leased, or not once: $($gateSaw -join ',')"
    Assert-True ((@($device.State.TargetInstalls) -join ',') -ceq $wholeApk) 'The gated target was not installed once, as an update.'
    $calls = @($device.State.Calls)
    Assert-True ([Array]::IndexOf($calls, "install -r $wholeApk") -lt [Array]::IndexOf($calls, 'shell pm create-user hushgram-native-runner')) 'The target was installed after the test user existed.'
    $written = Get-Content -LiteralPath $wholeVerdict -Raw | ConvertFrom-Json
    Assert-True ($written.controlsProven -eq $true -and $written.networkOff -eq $true -and $written.isolatedUser -eq $true -and
        [string]$written.user -ceq '10' -and $written.serial -ceq 'emulator-5554' -and $written.target.Sha256 -ceq $boundTarget.Sha256 -and
        $written.target.SelectionSha256 -ceq $boundTarget.SelectionSha256 -and @($written.cases).Count -eq 9 -and $written.featuresPassed -eq $true -and
        $written.cases[3].Observed.'presence.paused' -ceq $null -and $written.cases[4].Observed.'presence.paused' -ceq 'ACTIVE>ACTIVE' -and
        $written.cases[5].Observed.'visual.on' -ceq 'held' -and $written.cases[6].Observed.'visual.paused' -ceq 'native' -and
        $written.cases[7].Observed.'notifications.muted' -ceq 'held,held' -and $written.cases[8].Observed.'share.on' -ceq 'cleaned') 'The verdict file does not record the run.'
    Assert-True ($device.State.RunnerUsers.Count -eq 0 -and $device.State.Airplane -ceq 'disabled' -and -not (Test-Path -LiteralPath $leaseFile)) 'A whole run left the runner, the network switch or its lease.'
    $device = New-FakeDevice -ReportedSigner $runnerSignerDigest -Fixture $boundTarget
    $whole = Invoke-WholeRun $device $boundTarget -Install -Gate { param($Tools, $Apk) $false }
    Assert-True ($whole.Error -like '*verify-injected-registers.ps1 failed*' -and $device.State.Calls.Count -eq 0) "A target that failed the register gate reached the device: $($whole.Error)"
    Assert-True (-not (Test-Path -LiteralPath $wholeVerdict)) 'A failed run left the earlier verdict standing.'
    $device = New-FakeDevice
    $whole = Invoke-WholeRun $device $target -Install -Gate $passingGate
    Assert-True ($whole.Error -like '*signed by*' -and $device.State.Calls.Count -eq 0 -and $gateSaw.Count -eq 1) "A runner with another signer reached the gate or the device: $($whole.Error)"
    [IO.File]::WriteAllText($wholeVerdict, '{"controlsProven":true}')
    $whole = Invoke-WholeRun (New-FakeDevice) $target -Reader { throw 'No target APK at x.' }
    Assert-True ($whole.Error -like '*No target APK*' -and -not (Test-Path -LiteralPath $wholeVerdict)) 'A run that stopped at the target left the earlier verdict standing.'
    # A phone with -Install: refused before the gate, the lease and any install.
    $phone = New-FakeDevice -Serial 'PHONE0SERIAL' -ReportedSigner $runnerSignerDigest -Fixture $boundTarget
    $whole = Invoke-WholeRun $phone $boundTarget -Install -Gate $passingGate
    Assert-True ($whole.Error -like '*PHONE0SERIAL is not an emulator*' -and $phone.State.Calls.Count -eq 0 -and $gateSaw.Count -eq 1) "A phone reached the gate or the device: $($whole.Error)"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $leaseDirectory 'PHONE0SERIAL.json'))) 'A phone was leased by a run that refuses phones.'
    Write-Host '[native] whole run: 12 checks passed'

    Assert-True (Test-PushGateRunsSuite (Join-Path $Root 'scripts/pre-push.ps1') 'scripts/test-native-runner.ps1') `
        'pre-push.ps1 no longer runs the native runner suite.'
} finally {
    foreach ($directory in @($leaseDirectory, $work)) {
        $resolved = [IO.Path]::GetFullPath($directory)
        if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) { throw 'A test directory left the temporary workspace.' }
        if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    }
}

$global:LASTEXITCODE = 0
Write-Host '[native] native runner contracts passed'
