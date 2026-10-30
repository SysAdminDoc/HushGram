<#
.SYNOPSIS
    Installation and destination evidence required before publishing a bundle.
.DESCRIPTION
    Schema 2 binds the local bundle and candidate to the installed APK before and after
    a walk. Every required destination needs its own active-window capture. Historical
    schema 1 receipts remain historical evidence and cannot authorize another release.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>

. (Join-Path $PSScriptRoot 'build-identity.ps1')
. (Join-Path $PSScriptRoot 'native-runner-device.ps1')
. (Join-Path $PSScriptRoot 'walk-plan.ps1')
. (Join-Path $PSScriptRoot 'walk-capture.ps1')

$WalkReceiptPhones = @('SM-S908U1', 'SM-S938B')

function Get-WalkUtcTimestamp {
    param($Value)
    if ($Value -isnot [string] -or $Value -cnotmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?Z$') { return $null }
    $parsed = [DateTimeOffset]::MinValue
    [string[]]$formats = @("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.FFFFFFF'Z'")
    if (-not [DateTimeOffset]::TryParseExact($Value, $formats, [Globalization.CultureInfo]::InvariantCulture,
        ([Globalization.DateTimeStyles]::AssumeUniversal -bor [Globalization.DateTimeStyles]::AdjustToUniversal), [ref]$parsed)) { return $null }
    $parsed.ToUniversalTime()
}

function Read-WalkReceiptDocument {
    param([string]$Path)
    Read-PatchJsonObject -Path $Path
}

function Test-WalkProofString {
    param($Value, [string]$Pattern = '\A[^\x00-\x1f\x7f]{1,512}\z')
    $Value -is [string] -and -not [string]::IsNullOrWhiteSpace($Value) -and $Value -cmatch $Pattern
}

function Get-WalkInstalledState {
    param([string]$Adb, [string]$Serial, [string]$Package, [string]$RunId, $DeviceLease, [scriptblock]$AdbInvoker)
    Assert-WalkCaptureIdentity -RunId $RunId -CaptureId 'installation' -Package $Package
    $readContext = @{ Adb=$Adb; DeviceLease=$DeviceLease; Invoker=$AdbInvoker }
    function Read-InstalledWalkAdb([string[]]$Arguments) {
        $answer = Invoke-HushgramAdbCommand -Adb $readContext.Adb -Arguments (@('-s',$Serial) + $Arguments) `
            -DeviceLease $readContext.DeviceLease -Invoker $readContext.Invoker
        if ($answer.ExitCode -ne 0) { throw 'The installed walkthrough candidate could not be read.' }
        @($answer.Stdout) -join "`n"
    }
    $packageText = Read-InstalledWalkAdb @('shell','dumpsys','package',$Package)
    if ($packageText -notmatch ('(?m)^\s*Package \[' + [regex]::Escape($Package) + '\]')) { throw 'The installed package is missing.' }
    $values = @{}
    foreach ($field in @('versionName','versionCode','lastUpdateTime')) {
        $pattern = if ($field -ceq 'versionName' -or $field -ceq 'lastUpdateTime') { '(?m)^\s*' + $field + '=([^\r\n]+)' }
            else { '(?m)^\s*' + $field + '=([1-9][0-9]*)\b' }
        $matchesForField = [regex]::Matches($packageText, $pattern)
        if ($matchesForField.Count -ne 1) { throw "The installed package has no unique $field." }
        $values[$field] = $matchesForField[0].Groups[1].Value.Trim()
    }
    $applicationIds = @([regex]::Matches($packageText, '(?m)^\s*(?:appId|userId)=([1-9][0-9]*)\b') |
        ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
    if ($applicationIds.Count -ne 1) { throw 'The installed package has no unique application ID.' }
    $paths = (Read-InstalledWalkAdb @('shell','pm','path',$Package)) -split '\r?\n'
    $files = [Collections.Generic.List[object]]::new()
    foreach ($remoteEntry in $paths) {
        if ($remoteEntry -cnotmatch '^package:(/data/app/[A-Za-z0-9._~+=/@-]+\.apk)$' -or $Matches[1].Contains('/../')) {
            throw 'The installed package returned an unsupported APK path.'
        }
        $remoteApkPath = $Matches[1]
        $digestText = (Read-InstalledWalkAdb @('shell','sha256sum',$remoteApkPath)).Trim()
        if ($digestText -cnotmatch '^([0-9a-f]{64})\s+(.+)$' -or $Matches[2].Trim() -cne $remoteApkPath) {
            throw 'The installed APK has no exact SHA-256 result.'
        }
        $files.Add([pscustomobject]@{ name=[IO.Path]::GetFileName($remoteApkPath); path=$remoteApkPath; sha256=$Matches[1] })
    }
    if (-not $files.Count -or @($files.path | Select-Object -Unique).Count -ne $files.Count) { throw 'The installed APK paths are empty or duplicated.' }
    $fingerprint = (Read-InstalledWalkAdb @('shell','getprop','ro.build.fingerprint')).Trim()
    if (-not $fingerprint -or $fingerprint -match '[\x00-\x1f\x7f]') { throw 'The device has no build fingerprint.' }
    [pscustomobject]@{ runId=$RunId; serial=$Serial; package=$Package; versionName=$values.versionName; versionCode=$values.versionCode
        appId=$applicationIds[0]; lastUpdateTime=$values.lastUpdateTime; fingerprint=$fingerprint; files=$files.ToArray()
        observedUtc=[DateTime]::UtcNow.ToString('o') }
}

function Test-WalkInstalledState {
    param($State, $Binding, [string]$RunId, [string]$Serial, [DateTimeOffset]$Started, [DateTimeOffset]$Finished)
    if ($State -isnot [pscustomobject]) { return 'The walk has no installation observation.' }
    foreach ($field in @('runId','serial','package','versionName','versionCode')) {
        if (-not (Test-WalkProofString $State.$field)) { return 'The installation has malformed target fields.' }
    }
    if ($State.runId -cne $RunId -or $State.serial -cne $Serial -or
        $State.package -cne $Binding.target.package -or $State.versionName -cne $Binding.target.versionName -or
        $State.versionCode -cne $Binding.target.versionCode) { return 'The installation does not match the walk and candidate.' }
    $observed = Get-WalkUtcTimestamp $State.observedUtc
    if ($null -eq $observed -or $observed -lt $Started -or $observed -gt $Finished) { return 'The installation observation is outside this walk.' }
    $applicationId = 0
    $lastUpdated = [DateTime]::MinValue
    [string[]]$updateFormats = @('yyyy-MM-dd HH:mm:ss', 'yyyy-MM-dd HH:mm:ss.FFFFFFF')
    if (-not (Test-WalkProofString $State.appId '\A[1-9][0-9]{0,9}\z') -or
        -not [int]::TryParse($State.appId, [ref]$applicationId) -or
        -not (Test-WalkProofString $State.lastUpdateTime '\A\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d{1,7})?\z') -or
        -not [DateTime]::TryParseExact($State.lastUpdateTime, $updateFormats, [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::None, [ref]$lastUpdated) -or -not (Test-WalkProofString $State.fingerprint)) {
        return 'The installation has no package or device session identity.'
    }
    if ($State.files -isnot [array] -or $State.files.Count -ne 1 -or $State.files[0] -isnot [pscustomobject] -or
        $State.files[0].name -isnot [string] -or $State.files[0].name -cne 'base.apk' -or
        -not (Test-WalkProofString $State.files[0].sha256 '\A[0-9a-f]{64}\z') -or
        $State.files[0].sha256 -cne $Binding.apkSha256 -or $State.files[0].path -isnot [string] -or
        $State.files[0].path -cnotmatch '\A/data/app/[A-Za-z0-9._~+=/@-]+/base\.apk\z' -or $State.files[0].path.Contains('/../')) {
        return 'The installed base or splits are not the checked single-APK candidate.'
    }
    $null
}

function New-WalkReceipt {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('PASS','PARTIAL','FAIL')][string]$Result,
        [Parameter(Mandatory = $true)][bool]$Full,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$Device,
        [AllowEmptyString()][string]$AppVersion,
        [Parameter(Mandatory = $true)][string]$BundleSha256,
        [AllowEmptyString()][string]$InstalledApkSha256,
        [Parameter(Mandatory = $true)][DateTimeOffset]$Started,
        [Parameter(Mandatory = $true)][DateTimeOffset]$Finished,
        [int]$Screens, [int]$Crashes, [string[]]$Problems=@(), [string]$RunId,
        $Binding, $ArtifactPaths, $InstallationBefore, $InstallationAfter,
        [string[]]$RequiredDestinations=@(), [object[]]$Observations=@()
    )
    [ordered]@{
        schema=2; result=$Result; full=$Full; runId=$RunId
        serial=$Serial; device=$Device; emulator=$Serial.StartsWith('emulator-', [StringComparison]::Ordinal)
        appVersion=$AppVersion; bundleSha256=$BundleSha256.ToLowerInvariant(); installedApkSha256=$InstalledApkSha256
        started=$Started.UtcDateTime.ToString('o'); finished=$Finished.UtcDateTime.ToString('o')
        screens=$Screens; crashes=$Crashes; problems=@($Problems); binding=$Binding; artifacts=$ArtifactPaths
        policySha256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'walk-categories.json') -Algorithm SHA256).Hash.ToLowerInvariant()
        installationBefore=$InstallationBefore; installationAfter=$InstallationAfter
        requiredDestinations=@($RequiredDestinations); observations=@($Observations)
    }
}

function Test-WalkReceipt {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$BundlePath,
        [string]$CatalogPath=(Join-Path (Split-Path -Parent $PSScriptRoot) 'patches-list.json'), $Tools)
    function New-InvalidWalkReceipt([string]$Reason) { [pscustomobject]@{ Valid=$false; Reason=$Reason; Receipt=$null } }
    if ($PSVersionTable.PSVersion -lt [version]'7.5') { return New-InvalidWalkReceipt 'Walkthrough receipt verification requires PowerShell 7.5 or newer.' }
    try {
        $Path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
        $receipt = Read-WalkReceiptDocument -Path $Path
        if ($receipt -isnot [pscustomobject] -or ($receipt.schema -isnot [int] -and $receipt.schema -isnot [long]) -or $receipt.schema -ne 2) {
            return New-InvalidWalkReceipt 'The walkthrough needs schema 2 installation and destination evidence.'
        }
        if ($receipt.result -isnot [string] -or $receipt.result -cne 'PASS' -or $receipt.full -isnot [bool] -or -not $receipt.full -or
            ($receipt.crashes -isnot [int] -and $receipt.crashes -isnot [long]) -or $receipt.crashes -ne 0 -or
            $receipt.problems -isnot [array] -or $receipt.problems.Count -ne 0) { return New-InvalidWalkReceipt 'The walk is incomplete, has a crash or records a problem.' }
        if ($receipt.runId -isnot [string] -or $receipt.runId -cnotmatch '\A[a-f0-9]{32}\z' -or
            $receipt.serial -isnot [string] -or $receipt.serial -cnotmatch '\A[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\z' -or
            -not (Test-WalkProofString $receipt.device) -or $receipt.emulator -isnot [bool]) { return New-InvalidWalkReceipt 'The walk has no exact run and device identity.' }
        $isEmulator = $receipt.serial.StartsWith('emulator-', [StringComparison]::Ordinal)
        if ($receipt.emulator -ne $isEmulator -or ($isEmulator -and $receipt.serial -cnotmatch '\Aemulator-[1-9][0-9]{0,4}\z') -or
            (-not $isEmulator -and $WalkReceiptPhones -cnotcontains $receipt.device)) {
            return New-InvalidWalkReceipt 'The device is not a release phone or emulator.'
        }
        $started = Get-WalkUtcTimestamp $receipt.started
        $finished = Get-WalkUtcTimestamp $receipt.finished
        if ($null -eq $started -or $null -eq $finished -or $finished -le $started -or $finished -gt [DateTimeOffset]::UtcNow) {
            return New-InvalidWalkReceipt 'The walk has no consistent completed UTC interval.'
        }
        if ($receipt.binding -isnot [pscustomobject] -or $receipt.binding.target -isnot [pscustomobject] -or
            $receipt.artifacts -isnot [pscustomobject] -or
            ($receipt.binding.schema -isnot [int] -and $receipt.binding.schema -isnot [long]) -or $receipt.binding.schema -ne 1) {
            return New-InvalidWalkReceipt 'The walk has no candidate artifact binding.'
        }
        foreach ($field in @('bundleSha256','installedApkSha256','policySha256')) {
            if (-not (Test-WalkProofString $receipt.$field '\A[0-9a-f]{64}\z')) { return New-InvalidWalkReceipt 'The receipt has no exact artifact digest.' }
        }
        foreach ($field in @('bundleSha256','catalogSha256','apkSha256','reportSha256','recordSha256','apkContentSha256','selectionSha256')) {
            if (-not (Test-WalkProofString $receipt.binding.$field '\A[0-9a-f]{64}\z')) { return New-InvalidWalkReceipt 'The binding has a malformed digest.' }
        }
        if (-not (Test-WalkProofString $receipt.binding.sourceBuildIdentity '\Ahg1:[0-9a-f]{64}\z') -or
            -not (Test-WalkProofString $receipt.appVersion '\A[0-9]+(?:\.[0-9]+)+\z') -or
            $receipt.binding.selectedPatches -isnot [array] -or $receipt.binding.selectedPatches.Count -eq 0 -or
            @($receipt.binding.selectedPatches | Where-Object { -not (Test-WalkProofString $_) }).Count) {
            return New-InvalidWalkReceipt 'The binding has malformed source, version or selection fields.'
        }
        foreach ($field in @('package','versionName','versionCode','signerSha256')) {
            if (-not (Test-WalkProofString $receipt.binding.target.$field)) { return New-InvalidWalkReceipt 'The binding has malformed target facts.' }
        }
        foreach ($field in @('signedApk','report','record')) {
            if (-not (Test-WalkProofString $receipt.artifacts.$field '\A[^\x00-\x1f\x7f]{1,32767}\z') -or
                -not [IO.Path]::IsPathFullyQualified($receipt.artifacts.$field)) { return New-InvalidWalkReceipt 'The candidate has no absolute artifact paths.' }
        }
        if ($null -eq $Tools) { $Tools = Resolve-NativeRunnerTools }
        $binding = Get-WalkArtifactBinding -BundlePath $BundlePath -CatalogPath $CatalogPath -SignedApk $receipt.artifacts.signedApk `
            -Report $receipt.artifacts.report -Record $receipt.artifacts.record -Tools $Tools
        foreach ($field in @('schema','bundleSha256','catalogSha256','apkSha256','reportSha256','recordSha256','apkContentSha256','sourceBuildIdentity','selectionSha256')) {
            if ($receipt.binding.$field -cne $binding.$field) { return New-InvalidWalkReceipt "The checked artifact changed or disagrees at $field." }
        }
        foreach ($field in @('package','versionName','versionCode','signerSha256')) {
            if ($receipt.binding.target.$field -cne $binding.target.$field) { return New-InvalidWalkReceipt 'The candidate target facts disagree.' }
        }
        if ($receipt.bundleSha256 -cne $binding.bundleSha256 -or $receipt.installedApkSha256 -cne $binding.apkSha256 -or
            $receipt.appVersion -cne $binding.target.versionName -or $receipt.binding.selectedPatches -isnot [array] -or
            ($receipt.binding.selectedPatches -join "`n") -cne ($binding.selectedPatches -join "`n")) { return New-InvalidWalkReceipt 'The receipt does not describe the checked build and patch selection.' }
        $policyHash = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'walk-categories.json') -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($receipt.policySha256 -cne $policyHash) { return New-InvalidWalkReceipt 'The required settings policy changed after this walk.' }
        $required = @(Get-WalkRequiredDestinations -CatalogPath $CatalogPath -SelectedPatches $binding.selectedPatches)
        if ($required.Count -eq 0 -or $receipt.requiredDestinations -isnot [array] -or
            @($receipt.requiredDestinations | Where-Object { -not (Test-WalkProofString $_ '\A[a-z][a-z0-9-]{0,79}\z') }).Count -or
            ($receipt.requiredDestinations -join "`n") -cne ($required.id -join "`n") -or
            $receipt.observations -isnot [array] -or $receipt.observations.Count -ne $required.Count -or
            ($receipt.screens -isnot [int] -and $receipt.screens -isnot [long]) -or $receipt.screens -ne $required.Count) {
            return New-InvalidWalkReceipt 'The walk omitted or shortened its required destinations.'
        }
        foreach ($state in @($receipt.installationBefore, $receipt.installationAfter)) {
            $refusal = Test-WalkInstalledState -State $state -Binding $binding -RunId $receipt.runId -Serial $receipt.serial -Started $started -Finished $finished
            if ($refusal) { return New-InvalidWalkReceipt $refusal }
        }
        foreach ($field in @('appId','lastUpdateTime','fingerprint')) {
            if ($receipt.installationBefore.$field -cne $receipt.installationAfter.$field) { return New-InvalidWalkReceipt 'The installation or device changed during the walk.' }
        }
        if ($receipt.installationBefore.files[0].path -cne $receipt.installationAfter.files[0].path) { return New-InvalidWalkReceipt 'The installed APK location changed during the walk.' }
        $beforeTime = Get-WalkUtcTimestamp $receipt.installationBefore.observedUtc
        $afterTime = Get-WalkUtcTimestamp $receipt.installationAfter.observedUtc
        if ($afterTime -le $beforeTime) { return New-InvalidWalkReceipt 'The installation reads are not ordered around the walk.' }
        $captureIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $seenDestinations = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $evidencePaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $captureRoot = [IO.Path]::GetDirectoryName($Path).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
        $previousEnd = $beforeTime
        $previousUptime = 0L
        foreach ($observation in $receipt.observations) {
            if ($observation -isnot [pscustomobject]) { return New-InvalidWalkReceipt 'A destination has no observation object.' }
            foreach ($field in @('id','status','runId','serial','captureId')) {
                if (-not (Test-WalkProofString $observation.$field)) { return New-InvalidWalkReceipt 'A destination has malformed identity fields.' }
            }
            $destination = @($required | Where-Object { $_.id -ceq $observation.id })
            if ($destination.Count -ne 1 -or -not $seenDestinations.Add($observation.id) -or $observation.status -cne 'visited' -or
                $observation.runId -cne $receipt.runId -or $observation.serial -cne $receipt.serial -or
                $observation.captureId -isnot [string] -or $observation.captureId -cnotmatch '^[a-zA-Z0-9_-]{1,80}$' -or
                -not $captureIds.Add($observation.captureId)) { return New-InvalidWalkReceipt 'A destination is duplicated, unavailable or belongs to another capture.' }
            $captured = Get-WalkUtcTimestamp $observation.startedUtc
            $capturedEnd = Get-WalkUtcTimestamp $observation.finishedUtc
            if ($null -eq $captured -or $null -eq $capturedEnd -or $captured -lt $previousEnd -or
                $capturedEnd -le $captured -or $capturedEnd -gt $afterTime) {
                return New-InvalidWalkReceipt 'A destination observation is outside its installation interval or overlaps another capture.'
            }
            if (($observation.uptimeMs -isnot [int] -and $observation.uptimeMs -isnot [long]) -or
                $observation.uptimeMs -le $previousUptime) {
                return New-InvalidWalkReceipt 'A capture has no ordered device uptime.'
            }
            $payloads = [ordered]@{}
            foreach ($entry in @(@{path='uiPath';hash='uiSha256';limit=16MB},@{path='afterUiPath';hash='afterUiSha256';limit=16MB},
                    @{path='imagePath';hash='imageSha256';limit=64MB})) {
                $evidencePath = $observation.($entry.path)
                $evidenceHash = $observation.($entry.hash)
                if (-not (Test-WalkProofString $evidencePath '\A[^\x00-\x1f\x7f]{1,32767}\z') -or
                    -not [IO.Path]::IsPathFullyQualified($evidencePath) -or
                    -not (Test-WalkProofString $evidenceHash '\A[0-9a-f]{64}\z')) { return New-InvalidWalkReceipt 'A capture has no exact file and hash.' }
                $resolvedEvidence = [IO.Path]::GetFullPath($evidencePath)
                if (-not $resolvedEvidence.StartsWith($captureRoot, [StringComparison]::OrdinalIgnoreCase) -or
                    -not $evidencePaths.Add($resolvedEvidence) -or
                    -not (Test-Path -LiteralPath $resolvedEvidence -PathType Leaf) -or
                    (Get-Item -LiteralPath $resolvedEvidence).Length -gt $entry.limit) {
                    return New-InvalidWalkReceipt 'A capture is missing, duplicated, outside the walk or too large.'
                }
                $ancestor = Get-Item -LiteralPath $resolvedEvidence
                while ($null -ne $ancestor -and ($ancestor.FullName.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar).StartsWith($captureRoot, [StringComparison]::OrdinalIgnoreCase)) {
                    if ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) { return New-InvalidWalkReceipt 'A capture path is linked outside its evidence directory.' }
                    $ancestor = if ($ancestor -is [IO.DirectoryInfo]) { $ancestor.Parent } else { $ancestor.Directory }
                }
                [byte[]]$bytes = [IO.File]::ReadAllBytes($resolvedEvidence)
                if ($bytes.Length -gt $entry.limit -or (Get-WalkCaptureSha256 -Bytes $bytes) -cne $evidenceHash) { return New-InvalidWalkReceipt 'A capture changed after its observation.' }
                $payloads[$entry.path] = $bytes
            }
            $image = Get-WalkCapturePngSize -Bytes $payloads.imagePath
            $windowId = $null
            $beforeUptime = $null
            foreach ($uiKind in @('uiPath','afterUiPath')) {
                $ui = ConvertFrom-WalkCaptureXml -Bytes $payloads[$uiKind] -RunId $receipt.runId -CaptureId $observation.captureId -Package $binding.target.package
                $uptime = 0L
                if ($ui.DocumentElement.GetAttribute('uptime-ms') -cnotmatch '\A[1-9][0-9]{0,18}\z' -or
                    -not [long]::TryParse($ui.DocumentElement.GetAttribute('uptime-ms'), [ref]$uptime)) {
                    return New-InvalidWalkReceipt 'The active UI capture has no valid device uptime.'
                }
                $currentWindow = $ui.DocumentElement.GetAttribute('window-id')
                if ($uiKind -ceq 'uiPath') {
                    if ($uptime -ne $observation.uptimeMs) { return New-InvalidWalkReceipt 'The observation and captured device uptimes disagree.' }
                    $windowId = $currentWindow
                    $beforeUptime = $uptime
                } elseif ($currentWindow -cne $windowId -or $uptime -le $beforeUptime) {
                    return New-InvalidWalkReceipt 'The captured window changed or the before and after snapshots are not ordered.'
                } elseif (([decimal]$uptime - [decimal]$beforeUptime) -gt ($capturedEnd - $captured).TotalMilliseconds + 2000) {
                    # The host interval brackets instrumentation. Allow two seconds for host
                    # scheduling and clock granularity, not an unbounded later device snapshot.
                    return New-InvalidWalkReceipt 'The device snapshot interval exceeds its host capture interval.'
                }
                $rootBounds = [regex]::Match($ui.SelectSingleNode('/hierarchy/node').GetAttribute('bounds'), '\A\[(\d+),(\d+)\]\[(\d+),(\d+)\]\z')
                if (-not $rootBounds.Success -or [int]$rootBounds.Groups[3].Value -gt $image.Width -or
                    [int]$rootBounds.Groups[4].Value -gt $image.Height) { return New-InvalidWalkReceipt 'The hierarchy does not fit its screenshot.' }
                $content = Test-WalkDestinationContent -Destination $destination[0] -Document $ui -Package $binding.target.package
                if (-not $content.Valid) { return New-InvalidWalkReceipt ("The $($destination[0].title) capture is invalid: " + $content.Reason) }
                $previousUptime = $uptime
            }
            $previousEnd = $capturedEnd
        }
        [pscustomobject]@{ Valid=$true; Reason=$null; Receipt=$receipt }
    } catch { return New-InvalidWalkReceipt ("The walkthrough evidence could not be verified: " + $_.Exception.Message) }
}
