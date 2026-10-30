<#
.SYNOPSIS
    Run the native feature runner's cases inside a patched Instagram on a leased emulator.

.DESCRIPTION
    The cases run in the installed target's own process and class loader through an
    instrumentation package (scripts/native-runner/), so what they see is the patched APK's code
    and injected extension, never a copy of it. Each verdict is bound to the target APK given
    here: its package, version, signer, file hash, build identity, patch selection and the
    Android user the case ran as. A run against any other install fails before a case starts.

    Every run proves two controls first. The positive one loads Instagram's main activity and the
    extension's Utils from the target's loader and asks the extension for its build identity,
    which has to equal the one patching wrote into the target APK. The negative one asks for a
    hook no patch defines and has to fail with that hook named as missing. Feature cases build on
    these, so a run whose controls don't hold fails as a whole.

    The feature cases run next, each in a new target process. presence builds Instagram's own
    presence write requests for Active, Idle, Offline and Disabled and reads back the status each
    keeps: before the settings are ready, then with Hide your active status off and on. Only
    Active may become Idle, and only while the switch is on. presence-paused builds one in a start
    the Pause switch paused. visual calls Instagram's view-once receipt handlers with a receipt
    that carries no message, so no request can be built: switched on, View DM photos and videos
    anonymously completes the visual receipt on the phone, and off, not ready or for the voice
    receipt, Instagram's own guard is reached. visual-paused does the same in a paused start.
    notifications posts test notifications through the stand-in every Instagram notify call goes
    to, on two of Instagram's channel ids: with one muted, its posts never reach the manager and
    are logged once while the other arrives, and with the list emptied the next one arrives in
    the same process. It then does the same with Group notifications on: two kept posts and a
    muted one give one summary counting 2, the kept posts show as they were built, and once the
    list is emptied the next post joins the group and the summary counts 3. share hands a reel link with its share id, next to a link to another site,
    to the stand-in for the share sheet that also takes the sender the sheet answers through: only
    with Sanitize sharing links on does the reel link lose its keys, the other link keeps its own,
    and the chooser always keeps the share and that sender, in the extra Android's own chooser
    keeps it in (Android 17 moved it). Nothing is sent. The run fails unless every feature case
    passed.

    The cases run in a new Android user created for the run, which has never signed in and is
    allowed to post notifications, and that user is removed afterwards along with the runner. The target app and its data in every other
    user are left as they were. Instagram's Application.onCreate never runs, but Android still
    starts its content providers, so runs are emulator-only: the network is off, and seen off
    from the host and from inside the target's process, for the whole run, then turned back on.

    The patch selection comes from the patch result beside the target APK, and only through the
    patch-record.json scripts/patch-for-device.ps1 writes with it. Re-signing the patched APK
    with another key keeps that record valid.

    The runner is signed with the target's key (Android runs an instrumentation only inside an app
    with the same signer), and the run stops before taking the device if the two differ. Passwords
    default the way patch-for-device.ps1's do.

    -Install puts the target APK on the device first, after scripts/verify-injected-registers.ps1
    has held it to injected-mutation-contracts.txt. It installs as an update (adb install -r), so
    Android refuses a different signer, and nothing is ever uninstalled to get past that.

.EXAMPLE
    scripts/native-runner.ps1 -Serial emulator-5590 -ExpectedModel sdk_gphone64_x86_64 `
        -ExpectedAvd HushGram_API36 -TargetApk C:\work\hushgram-0.0.14-signed.apk

    result.json and patch-record.json beside the target APK supply the patch selection. The
    verdict JSON is written beside the target APK unless -Verdict names another file, and any
    verdict already there is removed when the run starts.

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$ExpectedModel = $env:HUSHGRAM_EXPECTED_DEVICE_MODEL,
    [string]$ExpectedAvd = $env:HUSHGRAM_EXPECTED_AVD,
    [Parameter(Mandatory = $true)][string]$TargetApk,
    [string]$Report,
    [string]$Record,
    [switch]$Install,
    [string]$Keystore = "$HOME\.android\sideload-release.jks",
    [string]$KeyAlias = 'sideload',
    [AllowEmptyString()][string]$KeystorePassword,
    [AllowEmptyString()][string]$KeyPassword,
    # For a keystore Java can't read on its own, such as a BKS one: its type, the provider class
    # that reads it and the jar holding that class.
    [string]$KeystoreType,
    [string]$ProviderClass,
    [string]$ProviderJar,
    [string]$Sdk,
    [string]$Java,
    [string]$Verdict
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'native-runner-device.ps1')

$storePassword = $KeystorePassword
if (-not $PSBoundParameters.ContainsKey('KeystorePassword')) {
    $storePassword = [Environment]::GetEnvironmentVariable('HUSHGRAM_SIDELOAD_KEYSTORE_PASSWORD')
    if ([string]::IsNullOrEmpty($storePassword)) { $storePassword = 'sideload' }
}
$entryPassword = $KeyPassword
if (-not $PSBoundParameters.ContainsKey('KeyPassword')) {
    $entryPassword = [Environment]::GetEnvironmentVariable('HUSHGRAM_SIDELOAD_KEY_PASSWORD')
    if ([string]::IsNullOrEmpty($entryPassword)) { $entryPassword = $storePassword }
}
$signing = @{
    Keystore = $Keystore; KeyAlias = $KeyAlias; KeystorePassword = $storePassword; KeyPassword = $entryPassword
    KeystoreType = $KeystoreType; ProviderClass = $ProviderClass; ProviderJar = $ProviderJar
}

$tools = Resolve-NativeRunnerTools -Sdk $Sdk -Java $Java
$session = Invoke-NativeRunner -Tools $tools -TargetApk $TargetApk -Report $Report -Record $Record -Verdict $Verdict `
    -Serial $Serial -ExpectedModel $ExpectedModel -ExpectedAvd $ExpectedAvd -Install:$Install -Signing $signing

foreach ($case in $session.Cases) {
    $why = if ($case.Problems.Count) { ': ' + ($case.Problems -join '; ') } elseif ($case.Prerequisite) { " ($($case.Prerequisite))" }
        elseif ($case.Detail) { ": $($case.Detail)" } elseif ($case.Error) { ": $($case.Error)" }
        elseif ($case.MissingHook) { " (missing $($case.MissingHook))" } else { '' }
    Write-Host "[native] $($case.Case) $($case.Verdict)$why"
    foreach ($key in $case.Observed.Keys) { Write-Host "[native]   $key = $($case.Observed[$key])" }
}
Write-Host "[native] verdict written to $($session.VerdictPath)"
if (-not $session.ControlsProven) { throw 'The native controls did not hold, so no feature case can be trusted on this target.' }
Write-Host "[native] controls proven on $($session.Serial), user $($session.User), network off"
if (-not $session.FeaturesPassed) { throw 'Not every feature case passed.' }
Write-Host "[native] all $($NativeRunnerFeatureCases.Count) feature cases passed"
