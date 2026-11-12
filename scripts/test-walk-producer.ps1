<#
.SYNOPSIS
    Exercise walkthrough navigation and candidate reuse with device and artifact-reader stand-ins.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only. The push gate starts every suite with -Root; this one reads the scripts beside it.
#>
[CmdletBinding()]
param([string]$Root)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'walk-app.ps1')
$checks=0
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('hushgram-walk-producer-'+[guid]::NewGuid().ToString('N'))

function Assert-Producer([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:checks++
}
function Assert-ProducerRefused([scriptblock]$Action, [string]$Pattern) {
    $reason=$null
    try { & $Action | Out-Null } catch { $reason=$_.Exception.Message }
    Assert-Producer ($null -ne $reason -and $reason -like $Pattern) "Expected '$Pattern', received '$reason'."
}
function Reset-Producer {
    $script:state=@{ calls=[Collections.Generic.List[string]]::new(); captures=0; leases=0; releases=0; reads=0; bindings=0
        current='home'; refuseAt=0; wrongBefore=$false; wrongAfter=$false; artifactDrift=$false; rejectProof=$false; wrongPackage=$false }
    $script:state.settingsMode='none'
    $script:state.sectionOpen=$false; $script:state.sectionOpenBroken=$false; $script:state.currentSection=''; $script:state.sectionTaps=0
    $script:state.firstRowTop=300; $script:state.footStuck=$false; $script:state.sectionTapYs=[Collections.Generic.List[int]]::new()
    $script:state.animateFor=0; $script:state.animating=0; $script:state.tapCaptures=0
    $script:state.sectionRowAbsent=$false; $script:state.searchScrolledAway=$false
    $script:state.listDepth=0; $script:state.listOffset=0
}
# Strict APK/report/PNG validation has its own real-byte fixtures. These tests cover the
# producer's choice of operations and its propagation of each reader's refusal.
function Resolve-NativeRunnerTools { [pscustomobject]@{ Adb='adb-fixture'; Java='java-fixture'; Aapt2='aapt-fixture' } }
function Get-BundleVersion { '0.0.0' }
function Get-ReleaseBundlePath { Join-Path $scratch 'bundle.mpp' }
function Get-WalkArtifactBinding {
    $state.bindings++
    [pscustomobject]@{ bundleSha256=('a'*64); apkSha256=('b'*64); selectedPatches=@('fixture')
        sourceBuildIdentity=$(if ($state.artifactDrift -and $state.bindings -gt 1) { 'changed' } else { 'hg1:fixture' })
        target=[pscustomobject]@{ package=$(if ($state.wrongPackage) { 'com.example.other' } else { 'com.instagram.android' })
            versionName='fixture'; versionCode='2147483647' } }
}
function Get-WalkRequiredDestinations {
    foreach ($entry in @(@('home','feed_tab'),@('explore','search_tab'),@('reels','clips_tab'),@('inbox','direct_tab'),@('profile','profile_tab'))) {
        [pscustomobject]@{ id=$entry[0]; title=$entry[0]; kind='native'; tab=$entry[1] }
    }
    if ($state.settingsMode -cne 'none') {
        [pscustomobject]@{ id='settings-root'; title='HushGram settings'; kind='settings-root'; page=$false; anchors=@() }
        [pscustomobject]@{ id='settings-fixture'; title='Fixture category'; kind='settings'; page=$true; anchors=@('Known row') }
        [pscustomobject]@{ id='settings-pause'; title='Pause and diagnostics'; kind='settings'; page=$true; anchors=@('Pause HushGram') }
    }
}
function New-WalkCaptureApk { 'capture-fixture.apk' }
function Enter-HushgramDeviceLease { $state.leases++; [pscustomobject]@{ Serial='emulator-5554' } }
function Confirm-HushgramDevice { }
function Exit-HushgramDeviceLease { $state.releases++ }
function Install-WalkCaptureApk { $state.calls.Add('install capture'); 'capture-fixture.apk' }
function Get-WalkInstalledState {
    $state.reads++
    [pscustomobject]@{ versionName='fixture'; files=@([pscustomobject]@{ sha256=('b'*64) }) }
}
function Test-WalkInstalledState {
    if (($state.reads -eq 1 -and $state.wrongBefore) -or ($state.reads -eq 2 -and $state.wrongAfter)) { 'The installed candidate differs.' }
}
function Invoke-HushgramAdbCommand {
    param($Adb,[string[]]$Arguments,$DeviceLease)
    if ($Adb -cne 'adb-fixture' -or $DeviceLease.Serial -cne 'emulator-5554') { throw 'The producer lost its leased ADB identity.' }
    $command=$Arguments[2..($Arguments.Count-1)] -join ' '
    $state.calls.Add($command)
    $output=@()
    if ($command -ceq 'shell getprop ro.product.model') { $output=@('sdk_gphone64_x86_64') }
    elseif ($command -ceq 'shell date +%s') { $output=@([string][DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) }
    elseif ($command -like 'shell am start *') {
        $output=@('Status: ok')
        if ($command -like '*OPEN_SETTINGS true') { $state.current='settings-root' }
    }
    # A section is a page within the settings dialog. Back returns to the overview first;
    # a second Back would dismiss the dialog.
    elseif ($command -ceq 'shell input keyevent KEYCODE_BACK') {
        if ($state.current -ceq 'settings-root' -and $state.sectionOpen) {
            $state.sectionOpen=$false; $state.currentSection=''; $state.animating=0; $state.listOffset=0
        } elseif ($state.current -ceq 'settings-root') { $state.current='home' }
        else { $state.current='home' }
    }
    elseif ($command -like 'shell input tap *') {
        if ($state.current -ceq 'settings-root') {
            $state.sectionTaps++
            $tapY=[int]$Arguments[6]
            $state.sectionTapYs.Add($tapY)
            if (-not $state.sectionOpenBroken) {
                $state.currentSection=if($tapY -lt ($state.firstRowTop + 115)) { 'Fixture category' } else { 'Pause and diagnostics' }
                $state.sectionOpen=$true; $state.animating=$state.animateFor
            }
        }
        else {
            $index=[int]([int]$Arguments[5]/100)
            $state.current=@('home','explore','reels','inbox','profile')[$index]
        }
    }
    # The overview scrolls by each stroke. Fast strokes fling it farther than the row under test.
    elseif ($command -like 'shell input swipe *' -and $state.current -ceq 'settings-root' -and $state.listDepth -gt 0) {
        $travel=[int]$Arguments[6]-[int]$Arguments[8]
        if ([int]$Arguments[9] -lt 1000) { $travel*=2 }
        $state.listOffset=[Math]::Max(0,[Math]::Min($state.listDepth+1500,$state.listOffset+$travel))
    }
    # A slow lift from the list's 80% line brings a section row at its foot into the safe tap area.
    elseif ($command -like 'shell input swipe *' -and $state.current -ceq 'settings-root' -and [int]$Arguments[6] -le 800 -and -not $state.footStuck) {
        $state.firstRowTop=[Math]::Min($state.firstRowTop, 300)
    }
    [pscustomobject]@{ ExitCode=0; Stdout=$output; Output=$output }
}
function New-ProducerUi([switch]$Animating) {
    $children=''
    if ($state.current -ceq 'settings-root') {
        $toolbar="<node package='com.instagram.android' class='android.widget.LinearLayout' resource-id='com.instagram.android:id/action_bar' enabled='true' visible-to-user='true' bounds='[0,0][700,80]'><node package='com.instagram.android' class='android.widget.Button' content-desc='Back' clickable='true' enabled='true' visible-to-user='true' bounds='[0,0][64,80]' /><node package='com.instagram.android' class='android.widget.TextView' text='HushGram' enabled='true' visible-to-user='true' bounds='[72,0][350,80]' /></node>"
        $children += $toolbar
        if (-not $state.sectionOpen -and -not $state.searchScrolledAway) {
            $children += "<node package='com.instagram.android' class='android.widget.EditText' hint='Search settings' enabled='true' visible-to-user='true' bounds='[0,100][700,150]' />"
        }
        $listChildren=''
        if (-not $state.sectionOpen) {
            $fixtureTop=$state.firstRowTop + $state.listDepth - $state.listOffset
            $pauseTop=$fixtureTop + 130
            if (-not $state.sectionRowAbsent) {
                $bounds=if($Animating) { '[0,0][0,0]' } else { "[0,$fixtureTop][700,$($fixtureTop+80)]" }
                $listChildren += "<node package='com.instagram.android' class='android.widget.TextView' text='Fixture category' clickable='true' enabled='true' visible-to-user='true' bounds='$bounds' />"
            }
            $pauseBounds=if($Animating) { '[0,0][0,0]' } else { "[0,$pauseTop][700,$($pauseTop+80)]" }
            $listChildren += "<node package='com.instagram.android' class='android.widget.TextView' text='Pause and diagnostics' clickable='true' enabled='true' visible-to-user='true' bounds='$pauseBounds' />"
        } else {
            $title=$state.currentSection
            $headingBounds=if($Animating) { '[0,0][0,0]' } else { '[0,220][700,300]' }
            $listChildren += "<node package='com.instagram.android' class='android.widget.TextView' text='$title' enabled='true' visible-to-user='true' bounds='$headingBounds' />"
            if ($state.currentSection -ceq 'Fixture category') {
                $rowBounds=if($Animating) { '[0,0][0,0]' } else { '[0,310][700,390]' }
                $listChildren += "<node package='com.instagram.android' class='android.widget.TextView' text='Known row' enabled='true' visible-to-user='true' bounds='$rowBounds' />"
            } elseif ($state.currentSection -ceq 'Pause and diagnostics') {
                $rowBounds=if($Animating) { '[0,0][0,0]' } else { '[0,310][700,390]' }
                $listChildren += "<node package='com.instagram.android' class='android.widget.TextView' text='Pause HushGram' enabled='true' visible-to-user='true' bounds='$rowBounds' />"
            }
        }
        $children += "<node package='com.instagram.android' class='android.widget.ListView' resource-id='android:id/list' scrollable='true' enabled='true' visible-to-user='true' bounds='[0,180][700,950]'>$listChildren</node>"
        return [xml]"<hierarchy><node package='com.instagram.android' class='android.widget.FrameLayout' enabled='true' visible-to-user='true' bounds='[0,0][720,1000]'>$children</node></hierarchy>"
    }
    $tabs=@('feed_tab','search_tab','clips_tab','direct_tab','profile_tab')
    for($index=0;$index -lt $tabs.Count;$index++) {
        $left=$index*100; $right=$left+90
        $children+="<node package='com.instagram.android' class='android.widget.FrameLayout' resource-id='com.instagram.android:id/$($tabs[$index])' clickable='true' enabled='true' visible-to-user='true' bounds='[$left,900][$right,999]' />"
    }
    [xml]"<hierarchy><node package='com.instagram.android' class='android.widget.FrameLayout' enabled='true' visible-to-user='true' bounds='[0,0][720,1000]'>$children</node></hierarchy>"
}
function Invoke-WalkCapture {
    param($CaptureId,$RunId)
    $state.captures++
    if ($state.refuseAt -eq $state.captures) { throw 'The active window is a system ANR dialog.' }
    if ($state.sectionTaps -gt 0) { $state.tapCaptures++ }
    $animating=$state.current -ceq 'settings-root' -and $state.animating -gt 0
    if ($animating) { $state.animating-- }
    $ui=New-ProducerUi -Animating:$animating
    [pscustomobject]@{ CaptureId=$CaptureId; RunId=$RunId; ParsedXml=$ui; AfterParsedXml=$ui; UptimeMs=$state.captures
        StartedUtc=[DateTime]::UtcNow.ToString('o'); FinishedUtc=[DateTime]::UtcNow.ToString('o')
        UiPath='fixture.xml'; AfterUiPath='fixture-after.xml'; ImagePath='fixture.png'
        UiSha256=('d'*64); AfterUiSha256=('e'*64); ImageSha256=('f'*64) }
}
function Test-WalkDestinationContent {
    param($Destination, $Document)
    # Zero bounds mean Android is still laying out the new section page.
    $landed=$null -eq $Document -or -not $Document.OuterXml.Contains('[0,0][0,0]')
    $valid=$false
    if ($Destination.id -ceq 'settings-root') {
        $valid=$state.current -ceq 'settings-root' -and -not $state.sectionOpen
    } elseif ($Destination.id -ceq 'settings-fixture') {
        $valid=$state.current -ceq 'settings-root' -and $state.sectionOpen -and
            $state.currentSection -ceq 'Fixture category' -and $landed
    } elseif ($Destination.id -ceq 'settings-pause') {
        $valid=$state.current -ceq 'settings-root' -and $state.sectionOpen -and
            $state.currentSection -ceq 'Pause and diagnostics' -and $landed
    }
    if ($Destination.kind -ceq 'native') { $valid=$Destination.id -ceq $state.current }
    [pscustomobject]@{ Valid=$valid; Reason='The requested destination is not active.' }
}
function Test-WalkReceipt { [pscustomobject]@{ Valid=(-not $state.rejectProof); Reason='The complete proof was refused.' } }

try {
    [void][IO.Directory]::CreateDirectory($scratch)
    [IO.File]::WriteAllText((Join-Path $scratch 'bundle.mpp'),'fixture')
    $arguments=@{ Serial='emulator-5554'; ExpectedModel='sdk_gphone64_x86_64'; ExpectedAvd='fixture'; Package='com.instagram.android'
        Visit=@('home','explore','reels','inbox','profile'); SkipPatch=$true; SkipSettings=$false; SettleSeconds=0
        SignedApk=(Join-Path $scratch 'candidate.apk'); Report=(Join-Path $scratch 'result.json'); Record=(Join-Path $scratch 'record.json')
        Root=$scratch; OutDir=$scratch; Signing=@{ Keystore='fixture'; KeyAlias='fixture'; KeystorePassword=''; KeyPassword='' } }
    Reset-Producer
    $missing=$arguments.Clone(); $missing.Record=''
    Assert-ProducerRefused { Invoke-WalkApp @missing } '*SkipPatch needs*'
    Assert-Producer ($state.leases -eq 0 -and $state.calls.Count -eq 0) 'Missing artifact proof touched a device.'
    Reset-Producer; $state.wrongPackage=$true
    Assert-ProducerRefused { Invoke-WalkApp @arguments } '*requested package*'
    Assert-Producer ($state.leases -eq 0) 'A wrong package acquired a device.'
    Reset-Producer; $state.wrongBefore=$true
    Assert-ProducerRefused { Invoke-WalkApp @arguments } '*installed candidate differs*'
    Assert-Producer ($state.leases -eq 1 -and $state.releases -eq 1 -and
        @($state.calls | Where-Object { $_ -like '*input*' -or $_ -like '*force-stop*' -or $_ -like '*install*' }).Count -eq 0) 'A mismatched installed APK received input, or leaked its lease.'

    Reset-Producer
    $path=Invoke-WalkApp @arguments
    $receipt=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    Assert-Producer ($receipt.result -ceq 'PASS' -and $receipt.screens -eq 5 -and $receipt.observations.id -ccontains 'profile') 'Named native coverage was lost.'
    Assert-Producer (@($receipt.observations.captureId | Select-Object -Unique).Count -eq 5) 'Capture IDs were reused.'
    Assert-Producer ($state.bindings -eq 2 -and $state.reads -eq 2 -and $state.releases -eq 1) 'The producer omitted a binding/install read or lease release.'
    Assert-Producer (@($state.calls | Where-Object { $_ -like '*input tap*' }).Count -eq 5 -and
        @($state.calls | Where-Object { $_ -like '*deep*' -or $_ -like '*settings*' -or $_ -match 'uninstall|clear|SLEEP|KEYCODE_POWER' }).Count -eq 0) 'Unexpected navigation or destructive input.'
    $firstPath=$path

    Reset-Producer; $short=$arguments.Clone(); $short.Visit=@('home')
    $path=Invoke-WalkApp @short
    $receipt=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    Assert-Producer ($receipt.result -ceq 'PARTIAL' -and -not $receipt.full -and $receipt.requiredDestinations.Count -eq 5) 'A shortened visit earned full PASS.'
    Assert-Producer ($path -cne $firstPath -and (Test-Path -LiteralPath $firstPath)) 'A later run overwrote an earlier receipt.'

    Reset-Producer; $state.refuseAt=1
    $path=Invoke-WalkApp @arguments
    $receipt=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    Assert-Producer ($receipt.result -ceq 'FAIL' -and $state.captures -eq 1 -and $state.releases -eq 1 -and
        @($state.calls | Where-Object { $_ -like '*input tap*' }).Count -eq 0) 'A refused active window caused repeated probes, a tap or a leaked lease.'

    Reset-Producer; $state.wrongAfter=$true
    $path=Invoke-WalkApp @arguments
    Assert-Producer ((Get-Content -LiteralPath $path -Raw | ConvertFrom-Json).result -ceq 'FAIL') 'Installation drift earned PASS.'
    Reset-Producer; $state.artifactDrift=$true
    $path=Invoke-WalkApp @arguments
    Assert-Producer ((Get-Content -LiteralPath $path -Raw | ConvertFrom-Json).result -ceq 'FAIL') 'Local artifact drift earned PASS.'
    Reset-Producer; $state.rejectProof=$true
    $path=Invoke-WalkApp @arguments
    $receipt=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    Assert-Producer ($receipt.result -ceq 'FAIL' -and -not $receipt.full -and $receipt.problems -contains 'The complete proof was refused.') 'Final validation refusal was discarded.'

    Reset-Producer; $state.settingsMode='pages'
    $path=Invoke-WalkApp @arguments
    $receipt=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    Assert-Producer ($receipt.result -ceq 'PASS' -and $receipt.screens -eq 8 -and
        $receipt.observations.id -ccontains 'settings-root' -and $receipt.observations.id -ccontains 'settings-pause') 'Settings overview and section-page coverage was incomplete.'
    Assert-Producer (@($state.calls | Where-Object { $_ -like '*input tap*' }).Count -eq 7 -and
        @($state.calls | Where-Object { $_ -like '*KEYCODE_BACK*' }).Count -eq 2 -and
        -not $state.sectionOpen -and $state.current -ceq 'settings-root') 'A section was not opened once and closed with Back to the overview.'
    Assert-Producer (@($state.calls | Where-Object { $_ -like '*Show all settings as a list*' -or $_ -like '*Show settings by category*' }).Count -eq 0) 'The walkthrough used a removed layout toggle.'

    Reset-Producer; $state.settingsMode='pages'; $state.sectionOpenBroken=$true
    $path=Invoke-WalkApp @arguments
    $receipt=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    Assert-Producer ($receipt.result -ceq 'FAIL' -and $state.sectionTaps -eq 1 -and
        @($receipt.problems | Where-Object { $_ -like '*did not show its expected content after one tap*' }).Count -eq 1) 'A section that failed to open earned PASS or was tapped repeatedly.'

    # A category near the bottom of the list must be lifted above the gesture zone before a tap.
    Reset-Producer; $state.settingsMode='pages'; $state.firstRowTop=850
    $path=Invoke-WalkApp @arguments
    $receipt=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    Assert-Producer ($receipt.result -ceq 'PASS' -and $state.sectionTaps -eq 2 -and
        @($state.sectionTapYs | Where-Object { $_ -gt 800 }).Count -eq 0 -and
        @($state.calls | Where-Object { $_ -like 'shell input swipe *' }).Count -ge 1) 'A section row at the foot of the list was tapped from the system gesture zone.'

    Reset-Producer; $state.settingsMode='pages'; $state.firstRowTop=850; $state.footStuck=$true
    $path=Invoke-WalkApp @arguments
    $receipt=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    Assert-Producer ($receipt.result -ceq 'FAIL' -and $state.sectionTaps -eq 0 -and
        @($receipt.problems | Where-Object { $_ -like '*stayed at the foot of the list*' }).Count -eq 1) 'A section row that could not be lifted was tapped or earned PASS.'

    # A row that settles after the first capture is accepted after one extra look, not a second tap.
    Reset-Producer; $state.settingsMode='pages'; $state.animateFor=1
    $path=Invoke-WalkApp @arguments
    $receipt=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    Assert-Producer ($receipt.result -ceq 'PASS' -and $state.sectionTaps -eq 2 -and -not $state.sectionOpen) "A section page that settled after the tap was not verified: $($receipt.problems -join '; ')"

    Reset-Producer; $state.settingsMode='pages'; $state.animateFor=99
    $path=Invoke-WalkApp @arguments
    $receipt=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    Assert-Producer ($receipt.result -ceq 'FAIL' -and $state.sectionTaps -eq 1 -and
        @($receipt.problems | Where-Object { $_ -like '*did not show its expected content after one tap*' }).Count -eq 1) 'A section that never settled earned PASS or was tapped a second time.'

    # Reopening a restored section must return to the overview before the walkthrough starts.
    Reset-Producer; $state.settingsMode='pages'; $state.sectionOpen=$true; $state.currentSection='Fixture category'
    $path=Invoke-WalkApp @arguments
    $receipt=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    Assert-Producer ($receipt.result -ceq 'PASS' -and $state.sectionTaps -eq 2 -and
        @($state.calls | Where-Object { $_ -like '*KEYCODE_BACK*' }).Count -eq 3 -and -not $state.sectionOpen) 'A restored section was not returned to the overview before checking other sections.'

    foreach ($scrolledAway in @($false,$true)) {
        Reset-Producer; $state.settingsMode='pages'; $state.sectionRowAbsent=$true; $state.searchScrolledAway=$scrolledAway
        $path=Invoke-WalkApp @arguments
        $receipt=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        Assert-Producer ($receipt.result -ceq 'FAIL' -and $state.sectionTaps -eq 0 -and
            @($receipt.problems | Where-Object { $_ -like '*was unavailable*' }).Count -eq 1) "A missing section row was tapped or the walkthrough reported success (search scrolled away: $scrolledAway)."
    }

    # The overview can scroll across multiple screens of section rows. A deep row is found with
    # slow strokes, and the scroll resets to the overview after Back.
    Reset-Producer; $state.settingsMode='pages'; $state.listDepth=2400
    $path=Invoke-WalkApp @arguments
    $receipt=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    $swipes=@($state.calls | Where-Object { $_ -like 'shell input swipe *' } | ForEach-Object { ,@($_ -split ' ') })
    Assert-Producer ($receipt.result -ceq 'PASS' -and $swipes.Count -gt 3 -and
        @($swipes | Where-Object { [int]$_[7] -lt 1000 -or [Math]::Abs([int]$_[4]-[int]$_[6]) -gt 375 }).Count -eq 0) "A section deep in the overview was flung past or missed: $($receipt.problems -join '; ')"

    # Coordinates come from one visible, enabled native target and all ancestor clipping.
    Reset-Producer
    $ui=New-ProducerUi
    $area=Get-WalkActionArea -Document $ui -Package 'com.instagram.android' -ResourceId 'com.instagram.android:id/feed_tab'
    Assert-Producer ($area.X -eq 45 -and $area.Y -eq 950) 'Tab coordinates were not derived from the current hierarchy.'
    $ui.SelectSingleNode('//node[@resource-id="com.instagram.android:id/feed_tab"]').SetAttribute('enabled','false')
    Assert-Producer ($null -eq (Get-WalkActionArea -Document $ui -Package 'com.instagram.android' -ResourceId 'com.instagram.android:id/feed_tab')) 'A disabled target could be tapped.'
    $ui=New-ProducerUi
    $ui.SelectSingleNode('/hierarchy/node').SetAttribute('visible-to-user','false')
    Assert-Producer ($null -eq (Get-WalkActionArea -Document $ui -Package 'com.instagram.android' -ResourceId 'com.instagram.android:id/feed_tab')) 'A hidden ancestor could be tapped.'
    $beforeUi=New-ProducerUi; $afterUi=New-ProducerUi
    $afterUi.SelectSingleNode('//node[@resource-id="com.instagram.android:id/feed_tab"]').SetAttribute('bounds','[0,850][90,949]')
    Assert-Producer ($null -eq (Get-WalkCaptureActionArea -Capture ([pscustomobject]@{ ParsedXml=$beforeUi; AfterParsedXml=$afterUi }) `
        -Package 'com.instagram.android' -ResourceId 'com.instagram.android:id/feed_tab')) 'A navigation target that moved during capture could be tapped.'
    $ui=New-ProducerUi
    $clone=$ui.SelectSingleNode('//node[@resource-id="com.instagram.android:id/feed_tab"]').CloneNode($true)
    [void]$ui.SelectSingleNode('/hierarchy/node').AppendChild($clone)
    Assert-Producer ($null -eq (Get-WalkActionArea -Document $ui -Package 'com.instagram.android' -ResourceId 'com.instagram.android:id/feed_tab')) 'An ambiguous target could be tapped.'
    $ui=New-ProducerUi
    $cover=$ui.CreateElement('node')
    foreach($pair in @(@('package','com.instagram.android'),@('class','android.widget.FrameLayout'),@('visible-to-user','true'),
        @('enabled','true'),@('bounds','[0,0][720,1000]'),@('text','Screen unavailable'))) { $cover.SetAttribute($pair[0],$pair[1]) }
    [void]$ui.DocumentElement.FirstChild.AppendChild($cover)
    Assert-Producer ($null -eq (Get-WalkActionArea -Document $ui -Package 'com.instagram.android' -ResourceId 'com.instagram.android:id/feed_tab')) 'An obscured tab could be tapped through an overlay.'
    $cover.SetAttribute('visible-to-user','false')
    Assert-Producer ($null -ne (Get-WalkActionArea -Document $ui -Package 'com.instagram.android' -ResourceId 'com.instagram.android:id/feed_tab')) 'A hidden overlay prevented safe tab navigation.'
    $cover.SetAttribute('visible-to-user','true'); $cover.SetAttribute('bounds','[0,0][45,1000]')
    $otherCover=$cover.CloneNode($true); $otherCover.SetAttribute('bounds','[45,0][720,1000]')
    [void]$ui.DocumentElement.FirstChild.AppendChild($otherCover)
    Assert-Producer ($null -eq (Get-WalkActionArea -Document $ui -Package 'com.instagram.android' -ResourceId 'com.instagram.android:id/feed_tab')) 'Two covering siblings admitted a hidden tap target.'
    $ui=New-ProducerUi
    $ui.SelectSingleNode('//node[@resource-id="com.instagram.android:id/feed_tab"]').SetAttribute('drawing-order','1')
    $cover=$ui.CreateElement('node')
    foreach($pair in @(@('package','com.instagram.android'),@('class','android.widget.Button'),@('visible-to-user','true'),
        @('enabled','true'),@('bounds','[40,850][60,1000]'),@('text','Cover'),@('drawing-order','2'))) { $cover.SetAttribute($pair[0],$pair[1]) }
    [void]$ui.DocumentElement.FirstChild.AppendChild($cover)
    Assert-Producer ($null -eq (Get-WalkActionArea -Document $ui -Package 'com.instagram.android' -ResourceId 'com.instagram.android:id/feed_tab')) 'A partly visible tab admitted a tap on its covered center.'
    $cover.SetAttribute('bounds','[40,930][60,970]'); $cover.SetAttribute('drawing-order','0')
    Assert-Producer ($null -eq (Get-WalkActionArea -Document $ui -Package 'com.instagram.android' -ResourceId 'com.instagram.android:id/feed_tab')) 'A blocker omitted by whole-control visibility was omitted from the point check.'
    $cover.SetAttribute('visible-to-user','false')
    Assert-Producer ($null -ne (Get-WalkActionArea -Document $ui -Package 'com.instagram.android' -ResourceId 'com.instagram.android:id/feed_tab')) 'A hidden center cover blocked navigation.'
    $cover.SetAttribute('visible-to-user','true'); $cover.SetAttribute('drawing-order','1')
    $ui.SelectSingleNode('//node[@resource-id="com.instagram.android:id/feed_tab"]').SetAttribute('drawing-order','2')
    Assert-Producer ($null -ne (Get-WalkActionArea -Document $ui -Package 'com.instagram.android' -ResourceId 'com.instagram.android:id/feed_tab')) 'A lower center cover blocked navigation.'
    $cover.SetAttribute('drawing-order','3'); $cover.SetAttribute('bounds','[0,930][20,970]')
    Assert-Producer ($null -ne (Get-WalkActionArea -Document $ui -Package 'com.instagram.android' -ResourceId 'com.instagram.android:id/feed_tab')) 'A side cover blocked an unobstructed center.'
    [xml]$delegated=@'
<hierarchy><node package="com.instagram.android" enabled="true" visible-to-user="true" bounds="[0,0][720,1000]">
<node package="com.instagram.android" class="android.widget.LinearLayout" enabled="true" clickable="true" visible-to-user="true" bounds="[100,200][600,500]">
<node package="com.instagram.android" class="android.widget.TextView" text="Known category" enabled="true" clickable="false" visible-to-user="true" bounds="[120,220][220,260]" />
</node></node></hierarchy>
'@
    $area=Get-WalkActionArea -Document $delegated -Package 'com.instagram.android' -Title 'Known category'
    Assert-Producer ($null -ne $area -and $area.X -eq 170 -and $area.Y -eq 240) 'A delegated title used its broad ancestor center.'
    Write-Host "[walk-producer] $checks checks passed"
} finally {
    $tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    $resolved=[IO.Path]::GetFullPath($scratch)
    if (-not $resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe producer fixture cleanup.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
