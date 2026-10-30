<#
.SYNOPSIS
    Walk the whole patched app on a leased phone or an emulator and leave a walk receipt.

.DESCRIPTION
    release/publish-release.ps1 refuses to publish a bundle without a receipt that a whole-app
    walk of that exact bundle left (walk-receipt.ps1). This runs the walk: it patches Instagram
    from the repo's release bundle with patch-for-device.ps1, installs it on the device, opens the
    Hush settings screen and each patched screen, watches the crash buffer for an Instagram crash,
    and writes walk-receipt.json with New-WalkReceipt. The receipt names the SHA-256 of the .mpp the
    walked APK was patched with, which is what publish holds it to.

    The result the walk writes:
      PASS      the whole app was walked, every screen opened, no crash.
      PARTIAL   a screen or the settings page was skipped, so it was not the whole app.
      FAIL      an Instagram crash, or a screen that would not open.
    Only a PASS backs a release.

    Native tabs are opened from their visible resource IDs. Each destination needs useful content
    in active-window XML before and after its PNG. Settings are opened with the shortcut's intent.

    Device safety: the walk takes an exclusive serial lease and releases it when it's done. It never
    changes the screen-stay-awake setting and never sends sleep or power, on a phone or an emulator
    (the phones are set to stay awake for good). It never uninstalls or clears the installed app; an
    install that can't update the app in place is a problem the walk reports, not one it forces past.

.EXAMPLE
    scripts/walk-app.ps1 -Serial emulator-5590 -ExpectedModel sdk_gphone64_x86_64 -ExpectedAvd HushGram_API36

.EXAMPLE
    # Reuse a candidate only with its actual patch report and content record:
    scripts/walk-app.ps1 -Serial <serial> -ExpectedModel SM-S908U1 -SkipPatch `
        -SignedApk <signed.apk> -Report <result.json> -Record <patch-record.json>

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$ExpectedModel = $env:HUSHGRAM_EXPECTED_DEVICE_MODEL,
    [string]$ExpectedAvd = $env:HUSHGRAM_EXPECTED_AVD,
    [string]$Package = 'com.instagram.android',
    # A shorter visit is diagnostic only. It cannot earn a release PASS.
    [ValidateSet('home','explore','reels','inbox','profile')]
    [string[]]$Visit = @('home','explore','reels','inbox','profile'),
    [switch]$SkipPatch,
    [string]$SignedApk,
    [string]$Report,
    [string]$Record,
    # Leave the Hush settings page out of the walk. Makes the walk PARTIAL, not a release walk.
    [switch]$SkipSettings,
    [ValidateRange(0,30)][int]$SettleSeconds = 4,
    [string]$OutDir = (Join-Path $env:TEMP 'hushgram-walk'),
    # Passed through to patch-for-device.ps1 so the build is signed with the key the installed app
    # already uses (an emulator or the S25 carry the Manager key, not the sideload key).
    [string]$Keystore = "$HOME\.android\sideload-release.jks",
    [string]$KeyAlias = 'sideload',
    [AllowEmptyString()][string]$KeystorePassword,
    [AllowEmptyString()][string]$KeyPassword,
    [string]$Sdk,
    [string]$Java,
    # The checkout whose release bundle is walked, the one holding this script unless given.
    [string]$Root
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'native-runner-device.ps1')
. (Join-Path $PSScriptRoot 'injected-register-device.ps1')
. (Join-Path $PSScriptRoot 'walk-receipt.ps1')

$MainActivity = 'com.instagram.mainactivity.InstagramMainActivity'
$OpenSettingsExtra = 'app.hushgram.extension.instagram.OPEN_SETTINGS'

function Resolve-WalkOutcome {
    <#
    .SYNOPSIS
        The result a walk earns from what it saw. A crash or an unopened screen fails it, a skipped
        part makes it partial, and only a whole clean walk passes.
    #>
    param(
        [int]$Screens,
        [int]$Crashes,
        [string[]]$Problems = @(),
        [Parameter(Mandatory = $true)][bool]$Full,
        [string[]]$RequiredDestinations = @(),
        [object[]]$Observations = @()
    )
    if ($Crashes -ne 0 -or @($Problems).Count -gt 0) { return 'FAIL' }
    if (-not $Full -or $RequiredDestinations.Count -eq 0 -or $Screens -ne $RequiredDestinations.Count -or
        $Observations.Count -ne $RequiredDestinations.Count) { return 'PARTIAL' }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($observation in $Observations) {
        if ($observation.id -isnot [string] -or $observation.status -isnot [string] -or $observation.status -cne 'visited' -or
            $observation.id -cnotin $RequiredDestinations -or -not $seen.Add($observation.id)) { return 'PARTIAL' }
    }
    if (@($RequiredDestinations | Select-Object -Unique).Count -ne $seen.Count) { return 'PARTIAL' }
    return 'PASS'
}

function Get-WalkActionArea {
    param([xml]$Document, [string]$Package, [string]$ResourceId, [string]$Title, [switch]$Scroll)
    $nodes = @(Get-WalkVisibleNodes -Document $Document | Where-Object { $_.GetAttribute('package') -ceq $Package })
    if ($Scroll) {
        $matchesForAction = @($nodes | Where-Object { $_.GetAttribute('class') -ceq 'android.widget.ListView' -and
            $_.GetAttribute('resource-id') -ceq 'android:id/list' -and $_.GetAttribute('scrollable') -ceq 'true' })
    } else {
        $matchesForAction = @($nodes | Where-Object {
            if ($ResourceId) { $_.GetAttribute('resource-id') -ceq $ResourceId }
            else { $_.GetAttribute('text') -ieq $Title -or $_.GetAttribute('content-desc') -ieq $Title }
        })
    }
    if ($matchesForAction.Count -ne 1) { return $null }
    $actionNode = $matchesForAction[0]
    $tapNode = $actionNode
    if (-not $Scroll) {
        while ($null -ne $actionNode -and $actionNode.Name -ceq 'node' -and $actionNode.GetAttribute('clickable') -cne 'true') {
            $actionNode = $actionNode.ParentNode
        }
        if ($null -eq $actionNode -or $actionNode.Name -cne 'node' -or $actionNode.GetAttribute('enabled') -cne 'true') { return $null }
    }
    # A non-clickable title can delegate to a row. Its own bounds still locate the
    # intended control; a broader clickable ancestor's center could hit another row.
    $actionNode = $tapNode
    $leftEdge = [int]::MinValue; $topEdge = [int]::MinValue
    $rightEdge = [int]::MaxValue; $bottomEdge = [int]::MaxValue
    while ($null -ne $actionNode -and $actionNode.Name -ceq 'node') {
        $bounds = [regex]::Match($actionNode.GetAttribute('bounds'), '^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$')
        if (-not $bounds.Success -or $actionNode.GetAttribute('visible-to-user') -cne 'true' -or
            $actionNode.GetAttribute('enabled') -cne 'true') { return $null }
        $leftEdge = [Math]::Max($leftEdge, [int]$bounds.Groups[1].Value)
        $topEdge = [Math]::Max($topEdge, [int]$bounds.Groups[2].Value)
        $rightEdge = [Math]::Min($rightEdge, [int]$bounds.Groups[3].Value)
        $bottomEdge = [Math]::Min($bottomEdge, [int]$bounds.Groups[4].Value)
        $actionNode = $actionNode.ParentNode
    }
    if ($rightEdge - $leftEdge -lt 4 -or $bottomEdge - $topEdge -lt 4) { return $null }
    $x=[int](($leftEdge+$rightEdge)/2); $y=[int](($topEdge+$bottomEdge)/2)
    $geometryNodes=[Collections.Generic.HashSet[Xml.XmlNode]]::new()
    foreach($uiNode in @(Get-WalkVisibleNodes -Document $Document -GeometryOnly)) { [void]$geometryNodes.Add($uiNode) }
    $point=[pscustomobject]@{ Left=$x; Top=$y; Right=$x+1; Bottom=$y+1 }
    if (-not (Test-WalkNodeUncovered -Node $tapNode -Visible $geometryNodes `
        -Geometry ([Collections.Generic.Dictionary[Xml.XmlNode,object]]::new()) -TargetArea $point)) { return $null }
    [pscustomobject]@{ X=$x; Y=$y
        Top=$topEdge; Bottom=$bottomEdge; Left=$leftEdge; Right=$rightEdge }
}

function Test-WalkSearchFieldVisible {
    param([xml]$Document)
    @(Get-WalkVisibleNodes -Document $Document | Where-Object { $_.GetAttribute('class') -match 'EditText$' -and
        @($_.GetAttribute('text'),$_.GetAttribute('content-desc'),$_.GetAttribute('hint')) -icontains 'Search settings' }).Count -gt 0
}

function Get-WalkScreenSignature {
    param([xml]$Document)
    # What a person could see: a swipe that leaves all of it in place moved nothing.
    @(Get-WalkVisibleNodes -Document $Document | ForEach-Object {
        '{0}|{1}|{2}' -f $_.GetAttribute('text'),$_.GetAttribute('content-desc'),$_.GetAttribute('bounds') }) -join "`n"
}

function Test-WalkSettingsVisible {
    param([xml]$Document)
    if (Test-WalkSearchFieldVisible $Document) { return $true }
    $nodes = @(Get-WalkVisibleNodes -Document $Document)
    # SettingsDialog's fixed root bar stays visible when its search preference scrolls away.
    @($nodes | Where-Object { $_.GetAttribute('text') -ceq 'HushGram' }).Count -eq 1 -and
        @($nodes | Where-Object { $_.GetAttribute('resource-id') -ceq 'android:id/list' -and
            $_.GetAttribute('class') -ceq 'android.widget.ListView' }).Count -eq 1
}

function Get-WalkCaptureActionArea {
    param($Capture, [string]$Package, [string]$ResourceId, [string]$Title, [switch]$Scroll)
    $arguments=@{ Package=$Package; ResourceId=$ResourceId; Title=$Title; Scroll=$Scroll }
    $beforeArea=Get-WalkActionArea -Document $Capture.ParsedXml @arguments
    $afterArea=Get-WalkActionArea -Document $Capture.AfterParsedXml @arguments
    if ($null -eq $beforeArea -or $null -eq $afterArea) { return $null }
    foreach ($edge in @('X','Y','Top','Bottom','Left','Right')) {
        if ($beforeArea.$edge -ne $afterArea.$edge) { return $null }
    }
    $afterArea
}

function Invoke-WalkApp {
    param(
        [string]$Serial, [string]$ExpectedModel, [string]$ExpectedAvd, [string]$Package,
        [string[]]$Visit, [bool]$SkipPatch, [bool]$SkipSettings, [int]$SettleSeconds,
        [string]$SignedApk, [string]$Report, [string]$Record,
        [string]$OutDir, [hashtable]$Signing, [string]$Sdk, [string]$Java, [string]$Root
    )
    if ($PSVersionTable.PSVersion -lt [version]'7.5') { throw 'Run the walkthrough with PowerShell 7.5 or newer (pwsh).' }
    if (-not $Serial) { throw 'Name the device to walk with -Serial.' }
    if (-not $ExpectedModel) { throw 'Set -ExpectedModel so the walk confirms the device.' }
    if ($SettleSeconds -lt 0 -or $SettleSeconds -gt 30) { throw 'SettleSeconds must be between 0 and 30.' }
    if (-not $Visit.Count -or @($Visit | Where-Object { $_ -cnotin @('home','explore','reels','inbox','profile') }).Count -or
        @($Visit | Select-Object -Unique).Count -ne $Visit.Count) { throw 'Visit needs unique named native destinations.' }
    if ($SkipPatch -and (-not $SignedApk -or -not $Report -or -not $Record)) {
        throw '-SkipPatch needs -SignedApk, -Report and -Record from that actual patch run.'
    }
    if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
    $Root = [IO.Path]::GetFullPath($Root)
    $OutDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)
    $runId = [guid]::NewGuid().ToString('N')
    $runDirectory = Join-Path $OutDir $runId
    [void][IO.Directory]::CreateDirectory($runDirectory)
    $tools = Resolve-NativeRunnerTools -Sdk $Sdk -Java $Java
    $adb = $tools.Adb
    $version = Get-BundleVersion -Root $Root
    $bundle = Get-ReleaseBundlePath -Root $Root -Version $version
    $catalogPath = Join-Path $Root 'patches-list.json'
    if (-not (Test-Path -LiteralPath $bundle -PathType Leaf)) { throw "No bundle at $bundle. Build it as part of the release checks." }
    if (-not $SkipPatch) {
        $patchArguments = @{ OutDir=$runDirectory; Root=$Root; Java=$tools.Java; Aapt2=$tools.Aapt2 }
        foreach ($name in @('Keystore','KeyAlias','KeystorePassword','KeyPassword')) {
            if ($Signing.ContainsKey($name)) { $patchArguments[$name] = $Signing[$name] }
        }
        # No serial: patch-for-device builds the candidate, and this leased walk installs it below.
        & (Join-Path $PSScriptRoot 'patch-for-device.ps1') @patchArguments | Out-Host
        $SignedApk = Join-Path $runDirectory "hushgram-$version-signed.apk"
        $Report = Join-Path $runDirectory 'result.json'
        $Record = Join-Path $runDirectory 'patch-record.json'
    }
    $artifacts = [pscustomobject]@{ signedApk=[IO.Path]::GetFullPath($SignedApk)
        report=[IO.Path]::GetFullPath($Report); record=[IO.Path]::GetFullPath($Record) }
    $bindingArguments = @{ BundlePath=$bundle; CatalogPath=$catalogPath; SignedApk=$artifacts.signedApk
        Report=$artifacts.report; Record=$artifacts.record; Tools=$tools }
    $binding = Get-WalkArtifactBinding @bindingArguments
    if ($Package -cne $binding.target.package) { throw 'The requested package does not match the checked candidate.' }
    $destinations = @(Get-WalkRequiredDestinations -CatalogPath $catalogPath -SelectedPatches $binding.selectedPatches)
    foreach ($signingField in @('Keystore','KeyAlias','KeystorePassword','KeyPassword')) {
        if (-not $Signing.ContainsKey($signingField)) { throw "The capture APK needs the signing field $signingField." }
    }
    # The capture app is self-instrumented and never substitutes the target Application.
    $captureApk = New-WalkCaptureApk -Tools $tools -WorkDirectory $runDirectory @Signing
    $deviceLease = $null
    $problems = [Collections.Generic.List[string]]::new()
    $observations = [Collections.Generic.List[object]]::new()
    $captureState = @{ sequence=0 }
    try {
        $deviceLease = Enter-HushgramDeviceLease -Serial $Serial -ExpectedModel $ExpectedModel -ExpectedAvd $ExpectedAvd
        Confirm-HushgramDevice -Adb $adb -DeviceLease $deviceLease
        function Invoke-WalkAdb([string[]]$Arguments) {
            $answer = Invoke-HushgramAdbCommand -Adb $adb -Arguments (@('-s',$Serial) + $Arguments) -DeviceLease $deviceLease
            if ($answer.ExitCode -ne 0) { throw (Format-HushgramAdbFailure -Message 'A walkthrough device operation failed' -Result $answer) }
            $answer
        }
        function Read-WalkCapture {
            $captureState.sequence++
            Invoke-WalkCapture -Adb $adb -Serial $Serial -RunId $runId -CaptureId ('capture-{0:d4}' -f $captureState.sequence) `
                -Package $Package -OutDir $OutDir -DeviceLease $deviceLease
        }
        function Send-WalkTap($Bounds) {
            if ($null -eq $Bounds) { throw 'No unique visible navigation target. No tap was sent.' }
            [void](Invoke-WalkAdb @('shell','input','tap',[string]$Bounds.X,[string]$Bounds.Y))
            Start-Sleep -Seconds $SettleSeconds
        }
        function Send-WalkSettingsSwipe($Capture, [string]$Direction) {
            $listBounds = Get-WalkCaptureActionArea -Capture $Capture -Package $Package -Scroll
            if ($null -eq $listBounds) { return $false }
            $top = [int]($listBounds.Top + ($listBounds.Bottom-$listBounds.Top)*0.2)
            $bottom = [int]($listBounds.Top + ($listBounds.Bottom-$listBounds.Top)*0.8)
            $from = if ($Direction -ceq 'up') { $top } else { $bottom }
            $to = if ($Direction -ceq 'up') { $bottom } else { $top }
            [void](Invoke-WalkAdb @('shell','input','swipe',[string]$listBounds.X,[string]$from,[string]$listBounds.X,[string]$to,'300'))
            Start-Sleep -Milliseconds 400
            return $true
        }
        function Read-WalkSettingsCapture {
            $current = Read-WalkCapture
            if (-not (Test-WalkSettingsVisible $current.ParsedXml)) { throw 'The settings search field is not visible. No settings navigation was sent.' }
            return $current
        }
        function Get-WalkSettledArea($Capture, [string]$Title) {
            # A section still animating open or shut can hold its heading at zero bounds, or move it between the
            # two snapshots. A heading the hierarchy holds gets one more look after a short settle before it counts as missing.
            $area = Get-WalkCaptureActionArea -Capture $Capture -Package $Package -Title $Title
            $held = @(@($Capture.ParsedXml,$Capture.AfterParsedXml) | ForEach-Object { $_.SelectNodes('//node') } | Where-Object {
                $_.GetAttribute('package') -ceq $Package -and ($_.GetAttribute('text') -ieq $Title -or $_.GetAttribute('content-desc') -ieq $Title) }).Count
            if ($null -eq $area -and $held) {
                Start-Sleep -Milliseconds 400
                $Capture = Read-WalkSettingsCapture
                $area = Get-WalkCaptureActionArea -Capture $Capture -Package $Package -Title $Title
            }
            [pscustomobject]@{ Capture=$Capture; Area=$area }
        }
        function Find-WalkSettingsSection($Destination) {
            # Preferences can be reordered. Search both directions using only the verified settings list.
            # A category heading expands its rows in place under it, so it is tapped once, never Back
            # (Back closes settings), and the rows are then looked for further down.
            foreach ($direction in @('up','down')) {
                $signature = $null
                for ($scrollIndex=0; $scrollIndex -lt 24; $scrollIndex++) {
                    $current = Read-WalkSettingsCapture
                    $content = Test-WalkDestinationContent -Destination $Destination -Document $current.ParsedXml -Package $Package
                    if ($content.Valid) { return [pscustomobject]@{ Capture=$current; Expanded=$false } }
                    $section = $null
                    if ($Destination.page) {
                        $settled = Get-WalkSettledArea $current $Destination.title
                        # A section that finished opening during the settle already shows its rows; a tap would fold it.
                        if (-not [object]::ReferenceEquals($settled.Capture, $current)) {
                            $current = $settled.Capture
                            $content = Test-WalkDestinationContent -Destination $Destination -Document $current.ParsedXml -Package $Package
                            if ($content.Valid) { return [pscustomobject]@{ Capture=$current; Expanded=$false } }
                        }
                        $section = $settled.Area
                    }
                    if ($null -ne $section) {
                        # A heading at the foot of the list can sit in the system's bottom gesture area, where a
                        # tap or a swipe from it opens Recents or goes home. Lift it into the list first, slowly.
                        $listBounds = Get-WalkCaptureActionArea -Capture $current -Package $Package -Scroll
                        $safeLine = if ($null -ne $listBounds) { [int]($listBounds.Top + ($listBounds.Bottom-$listBounds.Top)*0.8) }
                        if ($null -ne $safeLine -and $section.Y -gt $safeLine) {
                            $lifted = [int]($listBounds.Top + ($listBounds.Bottom-$listBounds.Top)*0.4)
                            [void](Invoke-WalkAdb @('shell','input','swipe',[string]$listBounds.X,[string]$safeLine,[string]$listBounds.X,[string]$lifted,'1000'))
                            Start-Sleep -Milliseconds 400
                            $settled = Get-WalkSettledArea (Read-WalkSettingsCapture) $Destination.title
                            $current = $settled.Capture; $section = $settled.Area
                            if ($null -eq $section -or $section.Y -gt $safeLine) {
                                throw "The settings section '$($Destination.title)' stayed at the foot of the list, where a tap can reach the system's gestures. It wasn't tapped."
                            }
                        }
                        Send-WalkTap $section
                        $current = Read-WalkSettingsCapture
                        $content = Test-WalkDestinationContent -Destination $Destination -Document $current.ParsedXml -Package $Package
                        if (-not $content.Valid) {
                            # Rows still sliding open read at zero bounds. Look once more before lifting the heading.
                            Start-Sleep -Milliseconds 400
                            $current = Read-WalkSettingsCapture
                            $content = Test-WalkDestinationContent -Destination $Destination -Document $current.ParsedXml -Package $Package
                        }
                        if ($content.Valid) { return [pscustomobject]@{ Capture=$current; Expanded=$true } }
                        # Rows only count under a visible heading, so lift the heading to the top once, slowly enough not to fling.
                        $settled = Get-WalkSettledArea $current $Destination.title
                        $current = $settled.Capture; $heading = $settled.Area
                        $listBounds = Get-WalkCaptureActionArea -Capture $current -Package $Package -Scroll
                        $target = if ($null -ne $listBounds) { [int]($listBounds.Top + ($listBounds.Bottom-$listBounds.Top)*0.1) }
                        if ($null -ne $heading -and $null -ne $listBounds -and $heading.Y -gt $target) {
                            [void](Invoke-WalkAdb @('shell','input','swipe',[string]$listBounds.X,[string]$heading.Y,[string]$listBounds.X,[string]$target,'1000'))
                            Start-Sleep -Milliseconds 400
                            $current = Read-WalkSettingsCapture
                            $content = Test-WalkDestinationContent -Destination $Destination -Document $current.ParsedXml -Package $Package
                            if ($content.Valid) { return [pscustomobject]@{ Capture=$current; Expanded=$true } }
                        }
                        throw "The settings section '$($Destination.title)' expanded without a verified row within a screen of its heading. It was tapped once."
                    }
                    # At the list's top or foot the last swipe changed nothing, so another one won't either.
                    $seen = Get-WalkScreenSignature $current.ParsedXml
                    if ($seen -ceq $signature) { break }
                    $signature = $seen
                    # No category sits above the search row, so with it in view an upward swipe can't reveal one.
                    if ($direction -ceq 'up' -and (Test-WalkSearchFieldVisible $current.ParsedXml)) { break }
                    if (-not (Send-WalkSettingsSwipe $current $direction)) { break }
                }
            }
            throw "The required settings section '$($Destination.title)' was unavailable. No setting was changed."
        }
        function Add-WalkObservation($Destination, $Capture) {
            foreach ($document in @($Capture.ParsedXml,$Capture.AfterParsedXml)) {
                $content = Test-WalkDestinationContent -Destination $Destination -Document $document -Package $Package
                if (-not $content.Valid) { throw "$($Destination.title): $($content.Reason)" }
            }
            $observations.Add([pscustomobject]@{ id=$Destination.id; status='visited'; serial=$Serial
                runId=$runId; captureId=$Capture.CaptureId; startedUtc=$Capture.StartedUtc; finishedUtc=$Capture.FinishedUtc; uptimeMs=$Capture.UptimeMs
                uiPath=$Capture.UiPath; uiSha256=$Capture.UiSha256; afterUiPath=$Capture.AfterUiPath; afterUiSha256=$Capture.AfterUiSha256
                imagePath=$Capture.ImagePath; imageSha256=$Capture.ImageSha256 })
            Write-Host "[walk] verified $($Destination.title)"
        }
        $deviceModel = (@((Invoke-WalkAdb @('shell','getprop','ro.product.model')).Stdout) -join '').Trim()
        if (-not $SkipPatch) {
            $installed = Invoke-WalkAdb @('install','-r',$artifacts.signedApk)
            if (@($installed.Stdout | Where-Object { $_ -ceq 'Success' }).Count -ne 1) { throw 'The candidate update did not report Success. The installed data was preserved.' }
        }
        $started = [DateTimeOffset]::UtcNow
        $startedEpochText = (@((Invoke-WalkAdb @('shell','date','+%s')).Stdout) -join '').Trim()
        if ($startedEpochText -cnotmatch '^[1-9][0-9]*$') { throw 'The device returned no exact walk start time.' }
        $startedEpoch = [long]$startedEpochText
        $before = Get-WalkInstalledState -Adb $adb -Serial $Serial -Package $Package -RunId $runId -DeviceLease $deviceLease
        $installProblem = Test-WalkInstalledState -State $before -Binding $binding -RunId $runId -Serial $Serial -Started $started -Finished ([DateTimeOffset]::UtcNow)
        if ($installProblem) { throw $installProblem }
        [void](Install-WalkCaptureApk -Adb $adb -Serial $Serial -Apk $captureApk -DeviceLease $deviceLease)
        try {
            [void](Invoke-WalkAdb @('shell','input','keyevent','KEYCODE_WAKEUP'))
            [void](Invoke-WalkAdb @('shell','am','force-stop',$Package))
            $launch = Invoke-WalkAdb @('shell','am','start','-W','-n',"$Package/$MainActivity")
            if ((@($launch.Stdout) -join "`n") -notmatch '(?m)^Status:\s*ok\r?$') { throw 'The app launch did not report Status: ok.' }
            Start-Sleep -Seconds $SettleSeconds
            foreach ($destination in @($destinations | Where-Object { $_.kind -ceq 'native' -and $_.id -cin $Visit })) {
                $navigation = Read-WalkCapture
                for ($backIndex=0; (Test-WalkSettingsVisible $navigation.ParsedXml) -and $backIndex -lt 3; $backIndex++) {
                    [void](Invoke-WalkAdb @('shell','input','keyevent','KEYCODE_BACK'))
                    Start-Sleep -Seconds $SettleSeconds
                    $navigation = Read-WalkCapture
                }
                if (Test-WalkSettingsVisible $navigation.ParsedXml) { throw 'Settings still cover the native tabs.' }
                Send-WalkTap (Get-WalkCaptureActionArea -Capture $navigation -Package $Package -ResourceId "${Package}:id/$($destination.tab)")
                Add-WalkObservation $destination (Read-WalkCapture)
            }
            if (-not $SkipSettings -and @($destinations | Where-Object { $_.kind -ceq 'settings-root' }).Count) {
                $launch = Invoke-WalkAdb @('shell','am','start','-W','-n',"$Package/$MainActivity",'--ez',$OpenSettingsExtra,'true')
                if ((@($launch.Stdout) -join "`n") -notmatch '(?m)^Status:\s*ok\r?$') { throw 'The settings launch did not report Status: ok.' }
                Start-Sleep -Seconds $SettleSeconds
                $overview = Read-WalkCapture
                $rootDestination = @($destinations | Where-Object { $_.kind -ceq 'settings-root' })[0]
                Add-WalkObservation $rootDestination $overview
                $pages = @((Get-WalkVisibleNodes $overview.ParsedXml) | Where-Object { $_.GetAttribute('text') -ieq 'Show all settings as a list' }).Count -gt 0
                foreach ($destination in @($destinations | Where-Object { $_.kind -ceq 'settings' })) {
                    # In the saved long-list mode, observe headings and rows without changing its mode.
                    $navigationDestination = [pscustomobject]@{ id=$destination.id; title=$destination.title; kind=$destination.kind
                        page=($pages -and $destination.page); anchors=$destination.anchors; headings=$destination.headings }
                    $found = Find-WalkSettingsSection $navigationDestination
                    Add-WalkObservation $destination $found.Capture
                    if ($found.Expanded) {
                        # Fold it again from a verified heading, so a still-open section can't lend its rows to a later one.
                        $heading = (Get-WalkSettledArea $found.Capture $destination.title).Area
                        if ($null -eq $heading) { throw "The settings section '$($destination.title)' could not be folded. Its heading was not steady on screen." }
                        Send-WalkTap $heading
                        $folded = Get-WalkSettledArea (Read-WalkSettingsCapture) $destination.title
                        if ($null -eq $folded.Area -or
                            (Test-WalkDestinationContent -Destination $navigationDestination -Document $folded.Capture.ParsedXml -Package $Package).Valid) {
                            throw "The settings section '$($destination.title)' still showed its rows after the fold tap."
                        }
                    }
                }
            }
        } catch {
            # A refused active window can be an ANR or a system dialog. Do not tap through it or retry every destination.
            $problems.Add($_.Exception.Message)
        }
        $after = Get-WalkInstalledState -Adb $adb -Serial $Serial -Package $Package -RunId $runId -DeviceLease $deviceLease
        $installProblem = Test-WalkInstalledState -State $after -Binding $binding -RunId $runId -Serial $Serial -Started $started -Finished ([DateTimeOffset]::UtcNow)
        if ($installProblem) { $problems.Add($installProblem) }
        if ((ConvertTo-Json $binding -Depth 20 -Compress) -cne (ConvertTo-Json (Get-WalkArtifactBinding @bindingArguments) -Depth 20 -Compress)) {
            $problems.Add('The local candidate artifacts changed during the walkthrough.')
        }
        $crashLines = @((Invoke-WalkAdb @('logcat','-b','crash','-d','-v','epoch')).Stdout | Where-Object {
            $_ -match '^\s*(\d+)\.\d+\s' -and [long]$Matches[1] -ge $startedEpoch })
        $fatal = @($crashLines | Where-Object { $_ -match "Process:\s*$([regex]::Escape($Package))\b" })
        $anr = @((Invoke-WalkAdb @('logcat','-b','events','-d','-v','epoch')).Stdout | Where-Object {
            $_ -match '^\s*(\d+)\.\d+\s' -and [long]$Matches[1] -ge $startedEpoch -and
            $_ -match ('am_anr.*\b' + [regex]::Escape($Package) + '\b') })
        if ($fatal.Count -or $anr.Count) {
            [IO.File]::WriteAllLines((Join-Path $runDirectory 'crash-buffer.txt'), [string[]]@($crashLines+$anr))
            $problems.Add('Instagram crashed or stopped responding during the walkthrough.')
        }
        $full = -not $SkipSettings -and $observations.Count -eq $destinations.Count -and $problems.Count -eq 0
        $outcome = Resolve-WalkOutcome -Screens $observations.Count -Crashes $fatal.Count -Problems $problems.ToArray() -Full $full `
            -RequiredDestinations $destinations.id -Observations $observations.ToArray()
        $receiptArguments = @{ Result=$outcome; Full=$full; Serial=$Serial; Device=$deviceModel; AppVersion=$after.versionName
            BundleSha256=$binding.bundleSha256; InstalledApkSha256=$after.files[0].sha256; Started=$started; Finished=[DateTimeOffset]::UtcNow
            Screens=$observations.Count; Crashes=$fatal.Count; Problems=$problems.ToArray(); RunId=$runId; Binding=$binding; ArtifactPaths=$artifacts
            InstallationBefore=$before; InstallationAfter=$after; RequiredDestinations=$destinations.id; Observations=$observations.ToArray() }
        $receiptPath = Join-Path $runDirectory 'walk-receipt.json'
        $receipt = New-WalkReceipt @receiptArguments
        [IO.File]::WriteAllText($receiptPath, (ConvertTo-Json $receipt -Depth 30)+"`n", [Text.UTF8Encoding]::new($false))
        $verdict = Test-WalkReceipt -Path $receiptPath -BundlePath $bundle -CatalogPath $catalogPath -Tools $tools
        if ($outcome -ceq 'PASS' -and -not $verdict.Valid) {
            $receipt.result='FAIL'; $receipt.full=$false; $receipt.problems=@($receipt.problems)+$verdict.Reason
            [IO.File]::WriteAllText($receiptPath, (ConvertTo-Json $receipt -Depth 30)+"`n", [Text.UTF8Encoding]::new($false))
            $outcome='FAIL'
        }
        Write-Host "[walk] $outcome on $deviceModel. $($observations.Count) of $($destinations.Count) required destinations."
        foreach ($problem in $receipt.problems) { Write-Host "[walk] $problem" }
        Write-Host "[walk] receipt $receiptPath"
        $receiptPath
    } finally {
        if ($deviceLease) { Exit-HushgramDeviceLease $deviceLease }
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    if (-not $PSBoundParameters.ContainsKey('KeystorePassword')) {
        $KeystorePassword = [Environment]::GetEnvironmentVariable('HUSHGRAM_SIDELOAD_KEYSTORE_PASSWORD')
        if ([string]::IsNullOrEmpty($KeystorePassword)) { $KeystorePassword = 'sideload' }
    }
    if (-not $PSBoundParameters.ContainsKey('KeyPassword')) {
        $KeyPassword = [Environment]::GetEnvironmentVariable('HUSHGRAM_SIDELOAD_KEY_PASSWORD')
        if ([string]::IsNullOrEmpty($KeyPassword)) { $KeyPassword = $KeystorePassword }
    }
    $signing = @{ Keystore=$Keystore; KeyAlias=$KeyAlias; KeystorePassword=$KeystorePassword; KeyPassword=$KeyPassword }
    Invoke-WalkApp -Serial $Serial -ExpectedModel $ExpectedModel -ExpectedAvd $ExpectedAvd -Package $Package `
        -Visit $Visit -SkipPatch:$SkipPatch -SkipSettings:$SkipSettings -SettleSeconds $SettleSeconds `
        -SignedApk $SignedApk -Report $Report -Record $Record `
        -OutDir $OutDir -Signing $signing -Sdk $Sdk -Java $Java -Root $Root
}
