<#
.SYNOPSIS
    Offline contracts for walkthrough artifact, installation and destination proof.
.DESCRIPTION
    Real ZIP inputs and decoded PNG/XML captures exercise production proof readers. Only the
    external Android SDK reads are mocked. ArtifactOnly also runs on Windows PowerShell 5.1.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>
[CmdletBinding()]
param([string]$Root, [switch]$ArtifactOnly)
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -eq 5) {
    Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -Force
}
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
$testRepo = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Root)
. (Join-Path $PSScriptRoot 'walk-app.ps1')
. (Join-Path $testRepo 'scripts/patch-target.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$script:passed = 0
function Assert-WalkTest([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:passed++
}
function Assert-WalkRefused([scriptblock]$Action, [string]$Pattern) {
    try { & $Action | Out-Null } catch {
        Assert-WalkTest ($_.Exception.Message -like $Pattern) "Unexpected refusal: $($_.Exception.Message)"
        return
    }
    throw "Expected refusal matching $Pattern."
}
function Write-WalkFixtureJson([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 32), [Text.UTF8Encoding]::new($false))
}
function New-WalkFixtureArchive([string]$Path, $Entries) {
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
    $archive = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $Entries.Keys) {
            $stream = $archive.CreateEntry($name).Open()
            try {
                [byte[]]$bytes = if ($Entries[$name] -is [byte[]]) { $Entries[$name] } else { [Text.Encoding]::UTF8.GetBytes($Entries[$name]) }
                $stream.Write($bytes, 0, $bytes.Length)
            } finally { $stream.Dispose() }
        }
    } finally { $archive.Dispose() }
}
function Read-WalkFixtureArchive([string]$Path, [string]$Name) {
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $archive.GetEntry($Name)
        if (-not $entry) { throw 'The synthetic SDK read found no requested archive entry.' }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
}
# Only the external SDK boundaries are mocked. Shared hashing and proof readers stay real.
function Get-ApkManifestFacts {
    param([string]$Apk, [string]$Aapt2)
    $script:fixtureSdkReads++
    if ($Aapt2 -cne 'synthetic-aapt2') { throw 'Unexpected synthetic manifest tool.' }
    if ($script:onManifestRead) {
        $callback = $script:onManifestRead; $script:onManifestRead = $null
        & $callback
    }
    (Read-WalkFixtureArchive $Apk 'AndroidManifest.xml') | ConvertFrom-Json
}
function Invoke-NativeRunnerTool {
    param([string]$Executable, [string[]]$Arguments, [string]$Failure)
    $script:fixtureSdkReads++
    if ($Executable -cne 'synthetic-java' -or $Arguments[2] -cne 'com.android.apksigner.ApkSignerTool' -or $Arguments[3] -cne 'verify') {
        throw "Unexpected synthetic SDK command: $Failure"
    }
    'Signer #1 certificate SHA-256 digest: ' + (Read-WalkFixtureArchive $Arguments[-1] 'META-INF/FIXTURE.SHA256')
}
function Set-WalkFixtureBundle([string]$Identity = $script:fixtureIdentity, [string]$Payload = 'original bundle bytecode') {
    $line = "HushGram-Build-Identity: $Identity"
    $folded = $line.Substring(0, 70) + "`r`n " + $line.Substring(70)
    New-WalkFixtureArchive $script:fixtureBundle ([ordered]@{
        'META-INF/MANIFEST.MF' = "Manifest-Version: 1.0`r`nName: HushGram`r`nVersion: 0.0.99`r`nPatcher-Version: 2.0.1`r`nTimestamp: 1`r`n$folded`r`n`r`n"
        'app/morphe/patches/instagram/FixturePatch.class' = $Payload
    })
}
function Set-WalkFixtureApk([string]$Identity = $script:fixtureIdentity, [string]$Payload = 'original APK resources',
    [string]$Package = 'com.instagram.android', [string]$VersionName = '453.0.0.57.79', [string]$VersionCode = '385914557') {
    $facts = @{ package = $Package; versionName = $VersionName; versionCode = $VersionCode } | ConvertTo-Json -Compress
    [byte[]]$dex = @([byte]0xff, [byte]0, [byte]0x80) + [Text.Encoding]::ASCII.GetBytes("dex`n035`0$Identity`0")
    New-WalkFixtureArchive $script:fixtureApk ([ordered]@{ 'AndroidManifest.xml' = $facts; 'classes.dex' = $dex
        'resources.arsc' = $Payload; 'META-INF/FIXTURE.SHA256' = ('a' * 64) })
}
function Set-WalkFixtureCatalog([switch]$ChangeVersionCode,
    [string[]]$Names = @('Hide ads', 'HushGram settings', 'Install next to Instagram')) {
    if ($ChangeVersionCode) { $names += 'Change version code' }
    $script:fixtureCatalogDocument = [pscustomobject]@{ version = 'v0.0.99'; patches = @(
        foreach ($name in $names) {
            [pscustomobject]@{ name = $name; dependencies = @('BytecodePatch')
                compatiblePackages = [pscustomobject]@{ 'com.instagram.android' = @('453.0.0.57.79') }
                compatibility = @([pscustomobject]@{ packageName = 'com.instagram.android'; targets = @(
                    [pscustomobject]@{ version = '453.0.0.57.79'; versionCodes = [pscustomobject]@{ ARM64_V8A = 385914557 } }) }) }
        }) }
    Write-WalkFixtureJson $script:fixtureCatalog $script:fixtureCatalogDocument
}
function Set-WalkFixtureReport([object[]]$Names = @('Hide ads', 'HushGram settings', 'BytecodePatch')) {
    $script:fixtureReportDocument = [pscustomobject]@{ success = $true; packageName = 'com.instagram.android'; packageVersion = '453.0.0.57.79'
        appliedPatches = @($Names | ForEach-Object { [pscustomobject]@{ name = $_ } }); failedPatches = @()
        stepResults = @([pscustomobject]@{ name = 'patch'; success = $true }, [pscustomobject]@{ name = 'write'; success = $true }) }
    Write-WalkFixtureJson $script:fixtureReport $script:fixtureReportDocument
}
function Set-WalkFixtureRecord { Write-PatchRecord -Apk $script:fixtureApk -Result $script:fixtureReport -Path $script:fixtureRecord }
function Get-WalkFixtureBinding {
    Get-WalkArtifactBinding -BundlePath $script:fixtureBundle -CatalogPath $script:fixtureCatalog -SignedApk $script:fixtureApk `
        -Report $script:fixtureReport -Record $script:fixtureRecord -Tools $script:fixtureTools
}
function Reset-WalkArtifactFixture {
    $script:onManifestRead = $null
    $script:fixtureSdkReads = 0
    Set-WalkFixtureBundle; Set-WalkFixtureApk; Set-WalkFixtureCatalog; Set-WalkFixtureReport; Set-WalkFixtureRecord
}
function Test-WalkArtifactFixture {
    Assert-WalkTest ((Get-NativeRunnerSha256Text 'abc') -ceq 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad') 'ASCII SHA-256 vector failed.'
    Assert-WalkTest ((Get-NativeRunnerSha256Text ([string][char]0xe9)) -ceq '4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c') 'UTF-8 SHA-256 vector failed.'
    Reset-WalkArtifactFixture
    $binding = Get-WalkFixtureBinding
    Assert-WalkTest ($binding.sourceBuildIdentity -ceq $script:fixtureIdentity) 'The actual DEX source was lost.'
    Assert-WalkTest (($binding.selectedPatches -join '|') -ceq 'Hide ads|HushGram settings') 'The public catalog selection is wrong.'
    Assert-WalkTest ($binding.target.signerSha256 -ceq ('a' * 64)) 'The actual signer reader was bypassed.'
    foreach ($key in @('bundleSha256','catalogSha256','apkSha256','reportSha256','recordSha256','apkContentSha256','selectionSha256')) {
        Assert-WalkTest ($binding.$key -cmatch '\A[0-9a-f]{64}\z') "The binding has no strict $key."
    }
    Set-WalkFixtureBundle ('hg1:' + ('c' * 64))
    Assert-WalkRefused { Get-WalkFixtureBinding } '*source identity does not match*'
    Reset-WalkArtifactFixture
    $script:onManifestRead = { Set-WalkFixtureBundle -Payload 'mutated payload, same claimed hg1' }
    Assert-WalkRefused { Get-WalkFixtureBinding } '*artifact changed*'
    Reset-WalkArtifactFixture
    Set-WalkFixtureApk -Payload 'changed resource bytes'
    Assert-WalkRefused { Get-WalkFixtureBinding } '*not the APK that patch run produced*'
    Reset-WalkArtifactFixture
    Set-WalkFixtureApk -Identity ('hg1:' + ('c' * 64)); Set-WalkFixtureRecord
    Assert-WalkRefused { Get-WalkFixtureBinding } '*source identity does not match*'
    Reset-WalkArtifactFixture
    [IO.File]::AppendAllText($script:fixtureReport, "`n", [Text.UTF8Encoding]::new($false))
    Assert-WalkRefused { Get-WalkFixtureBinding } '*result is not the one the record was written with*'
    foreach ($names in @(@('Hide ads'), @('Hide ads','HushGram settings','Hide ads'), @('Hide ads','HushGram settings','Unknown patch'),
            @('Hide ads','HushGram settings','Install next to Instagram'))) {
        Reset-WalkArtifactFixture
        Set-WalkFixtureReport -Names $names; Set-WalkFixtureRecord
        Assert-WalkRefused { Get-WalkFixtureBinding } '*selection*'
    }
    Reset-WalkArtifactFixture
    $script:fixtureReportDocument.stepResults[1].success = $false
    Write-WalkFixtureJson $script:fixtureReport $script:fixtureReportDocument; Set-WalkFixtureRecord
    Assert-WalkRefused { Get-WalkFixtureBinding } '*successful patching step*'
    Reset-WalkArtifactFixture
    $script:fixtureReportDocument.success = $false
    Write-WalkFixtureJson $script:fixtureReport $script:fixtureReportDocument; Set-WalkFixtureRecord
    Assert-WalkRefused { Get-WalkFixtureBinding } '*cannot prove the full selection*'
    Reset-WalkArtifactFixture
    $script:fixtureReportDocument.appliedPatches[0].name = 1
    Write-WalkFixtureJson $script:fixtureReport $script:fixtureReportDocument; Set-WalkFixtureRecord
    Assert-WalkRefused { Get-WalkFixtureBinding } '*selection is malformed*'
    foreach ($code in @('385914558','2147483647')) {
        Reset-WalkArtifactFixture
        Set-WalkFixtureApk -VersionCode $code; Set-WalkFixtureRecord
        Assert-WalkRefused { Get-WalkFixtureBinding } '*not an exact package and build*'
    }
    Reset-WalkArtifactFixture
    Set-WalkFixtureCatalog -ChangeVersionCode
    Set-WalkFixtureReport -Names @('Hide ads','HushGram settings','BytecodePatch','Change version code')
    Set-WalkFixtureApk -VersionCode '2147483647'; Set-WalkFixtureRecord
    Assert-WalkTest ((Get-WalkFixtureBinding).target.versionCode -ceq '2147483647') 'The authorized changed output code was refused.'
    foreach ($code in @('2147483646','385914557')) {
        Set-WalkFixtureApk -VersionCode $code; Set-WalkFixtureRecord
        Assert-WalkRefused { Get-WalkFixtureBinding } '*not an exact package and build*'
    }
    Set-WalkFixtureApk -VersionName '453.0.0.57.80' -VersionCode '2147483647'
    $script:fixtureReportDocument.packageVersion = '453.0.0.57.80'
    Write-WalkFixtureJson $script:fixtureReport $script:fixtureReportDocument; Set-WalkFixtureRecord
    Assert-WalkRefused { Get-WalkFixtureBinding } '*not an exact package and build*'
    Reset-WalkArtifactFixture
    $script:fixtureCatalogDocument.patches += $script:fixtureCatalogDocument.patches[0]
    Write-WalkFixtureJson $script:fixtureCatalog $script:fixtureCatalogDocument
    Assert-WalkRefused { Get-WalkFixtureBinding } '*duplicate patch name*'
    Reset-WalkArtifactFixture
    $script:fixtureCatalogDocument.version = 'v0.0.98'
    Write-WalkFixtureJson $script:fixtureCatalog $script:fixtureCatalogDocument
    Assert-WalkRefused { Get-WalkFixtureBinding } '*does not match the bundle version*'
    Reset-WalkArtifactFixture
    [IO.File]::WriteAllText($script:fixtureRecord, '{bad JSON', [Text.UTF8Encoding]::new($false))
    Assert-WalkRefused { Get-WalkFixtureBinding } '*not valid JSON*'
    Reset-WalkArtifactFixture
    $stamp = Get-Content -LiteralPath $script:fixtureRecord -Raw | ConvertFrom-Json
    $stamp | Add-Member -NotePropertyName sourceIdentity -NotePropertyValue $script:fixtureIdentity
    Write-WalkFixtureJson $script:fixtureRecord $stamp
    Assert-WalkRefused { Get-WalkFixtureBinding } '*no supported, strict content binding*'
    Reset-WalkArtifactFixture
    $manifest = (Read-WalkFixtureArchive $script:fixtureBundle 'META-INF/MANIFEST.MF').Replace("`r`n`r`n", "`r`nHushGram-Build-Identity: $script:fixtureIdentity`r`n`r`n")
    New-WalkFixtureArchive $script:fixtureBundle ([ordered]@{ 'META-INF/MANIFEST.MF' = $manifest })
    Assert-WalkRefused { Get-WalkFixtureBinding } '*malformed or duplicate attribute*'
    Reset-WalkArtifactFixture
    Remove-Item -LiteralPath $script:fixtureReport -Force
    Assert-WalkRefused { Get-WalkFixtureBinding } '*artifact is missing*'
    foreach ($artifact in @('fixtureCatalog','fixtureReport','fixtureRecord')) {
        foreach ($mutation in @('duplicate-key','object-array')) {
            Reset-WalkArtifactFixture
            $artifactPath = Get-Variable -Name $artifact -Scope Script -ValueOnly
            $json = [IO.File]::ReadAllText($artifactPath)
            if ($mutation -ceq 'object-array') { $json = '[' + $json + ']' }
            elseif ($artifact -ceq 'fixtureCatalog') { $json = $json.Replace('"version": "v0.0.99"', '"version": "v0.0.98", "version": "v0.0.99"') }
            elseif ($artifact -ceq 'fixtureReport') { $json = $json.Replace('"success": true,', '"success": false, "success": true,') }
            else { $json = $json.Replace('"schemaVersion": 1,', '"schemaVersion": 0, "schemaVersion": 1,') }
            [IO.File]::WriteAllText($artifactPath, $json, [Text.UTF8Encoding]::new($false))
            # A changed report gets a fresh matching record. The strict JSON parser must be
            # the refusal, rather than the original report digest left in an old record.
            if ($artifact -ceq 'fixtureReport') { Set-WalkFixtureRecord }
            Assert-WalkRefused { Get-WalkFixtureBinding } '*not valid JSON*'
            Assert-WalkTest ($script:fixtureSdkReads -eq 0) "The SDK was read before rejecting $artifact $mutation."
        }
    }
    Reset-WalkArtifactFixture
    $json = [IO.File]::ReadAllText($script:fixtureReport).TrimEnd()
    $json = $json.Substring(0, $json.Length - 1) + ', "ignored": {"success": false, "success": true}}'
    [IO.File]::WriteAllText($script:fixtureReport, $json, [Text.UTF8Encoding]::new($false))
    Set-WalkFixtureRecord
    Assert-WalkRefused { Get-WalkFixtureBinding } '*not valid JSON*'
    Assert-WalkTest ($script:fixtureSdkReads -eq 0) 'A duplicate in an otherwise ignored JSON field reached the SDK.'
    Reset-WalkArtifactFixture
}
function New-WalkFixtureUi($Destination, [string]$CaptureId, [long]$Uptime) {
    $nodes = [Collections.Generic.List[string]]::new()
    function ConvertTo-FixtureUiNode([string]$Text, [string]$Id = '', [string]$Selected = 'false', [string]$Children = '',
        [string]$Class = 'android.widget.TextView', [string]$Description = '', [string]$Bounds = '[20,120][380,160]') {
        $safeText = [Security.SecurityElement]::Escape($Text)
        "<node package=`"com.instagram.android`" class=`"$Class`" enabled=`"true`" visible-to-user=`"true`" selected=`"$Selected`" bounds=`"$Bounds`" resource-id=`"$Id`" text=`"$safeText`" content-desc=`"$Description`">$Children</node>"
    }
    if ($Destination.kind -ceq 'native') {
        $nodes.Add((ConvertTo-FixtureUiNode $Destination.title "com.instagram.android:id/$($Destination.tab)" 'true' -Bounds '[0,744][400,800]'))
        foreach ($id in $Destination.requiredIds) { $nodes.Add((ConvertTo-FixtureUiNode $Destination.title "com.instagram.android:id/$id" -Bounds '[0,0][400,64]')) }
        $emptyLabels = (ConvertTo-FixtureUiNode 'No posts yet' 'com.instagram.android:id/igds_empty_state_title') +
            (ConvertTo-FixtureUiNode 'New posts will appear here.' 'com.instagram.android:id/igds_empty_state_body_text' -Bounds '[30,180][370,240]')
        $emptyState = ConvertTo-FixtureUiNode '' 'com.instagram.android:id/empty_state_view_root' 'false' $emptyLabels 'android.widget.LinearLayout' -Bounds '[20,100][380,300]'
        $scopeId = switch -CaseSensitive ($Destination.id) {
            'home' { 'android:id/list' }; 'explore' { 'com.instagram.android:id/recycler_view' }
            'reels' { 'com.instagram.android:id/clips_viewer_view_pager' }; 'inbox' { 'com.instagram.android:id/inbox_refreshable_thread_list_recyclerview' }
            'profile' { 'com.instagram.android:id/user_detail_fragment' }
        }
        $nodes.Add((ConvertTo-FixtureUiNode '' $scopeId 'false' $emptyState 'android.widget.FrameLayout' -Bounds '[0,64][400,744]'))
    } else {
        $title = if ($Destination.kind -ceq 'settings' -and $Destination.page) { $Destination.title } else { 'HushGram' }
        $toolbar = (ConvertTo-FixtureUiNode '' 'android:id/home' 'false' '' 'android.widget.Button' 'Back' '[0,0][64,64]') +
            (ConvertTo-FixtureUiNode $title -Bounds '[72,0][350,64]')
        $nodes.Add((ConvertTo-FixtureUiNode '' 'com.instagram.android:id/action_bar' 'false' $toolbar 'android.widget.LinearLayout' -Bounds '[0,0][400,64]'))
        $rows = ConvertTo-FixtureUiNode $Destination.title 'android:id/title'
        $rowTop = 180
        foreach ($anchor in $Destination.anchors) {
            $rows += ConvertTo-FixtureUiNode $anchor 'android:id/title' -Bounds "[20,$rowTop][380,$($rowTop + 16)]"
            $rowTop += 20
        }
        if ($Destination.kind -ceq 'settings-root') {
            $rows += ConvertTo-FixtureUiNode 'Show all settings as a list' 'android:id/title' -Bounds '[20,160][380,176]'
            $nodes.Add((ConvertTo-FixtureUiNode 'Search settings' '' 'false' '' 'android.widget.EditText' -Bounds '[20,68][380,108]'))
        }
        $nodes.Add((ConvertTo-FixtureUiNode '' 'android:id/list' 'false' $rows 'android.widget.ListView' -Bounds '[0,112][400,800]'))
    }
    "<hierarchy run-id=`"$script:fixtureRunId`" capture-id=`"$CaptureId`" uptime-ms=`"$Uptime`" window-id=`"42`"><node package=`"com.instagram.android`" class=`"android.widget.FrameLayout`" enabled=`"true`" visible-to-user=`"true`" bounds=`"[0,0][400,800]`">$($nodes -join '')</node></hierarchy>"
}
function New-WalkFixturePng([string]$Path, [string]$Title) {
    $bitmap = [Drawing.Bitmap]::new(400, 800)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $font = [Drawing.Font]::new('Segoe UI', 14)
    try {
        $graphics.Clear([Drawing.Color]::FromArgb(20, 20, 28))
        $graphics.DrawString($Title, $font, [Drawing.Brushes]::White, 20, 40)
        $graphics.DrawString('A visible fixture post', $font, [Drawing.Brushes]::White, 20, 100)
        $bitmap.Save($Path, [Drawing.Imaging.ImageFormat]::Png)
    } finally { $font.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }
}
function New-WalkReceiptFixture {
    Add-Type -AssemblyName System.Drawing
    $catalogNames = @((Get-Content -LiteralPath (Join-Path $testRepo 'patches-list.json') -Raw | ConvertFrom-Json).patches | ForEach-Object name)
    Set-WalkFixtureCatalog -Names $catalogNames
    $selection = @((Split-SecondAppPatches -PatchList $script:fixtureCatalogDocument).SameApp)
    Set-WalkFixtureReport -Names @($selection + 'BytecodePatch')
    Set-WalkFixtureApk -VersionCode '2147483647'
    Set-WalkFixtureRecord
    $binding = Get-WalkFixtureBinding
    $required = @(Get-WalkRequiredDestinations -CatalogPath $script:fixtureCatalog -SelectedPatches $binding.selectedPatches)
    $script:fixtureRunId = 'd' * 32
    $script:fixtureStarted = [DateTimeOffset]::UtcNow.AddMinutes(-10)
    $observations = @(
        for ($index = 0; $index -lt $required.Count; $index++) {
            $captureId = 'capture-' + $index
            $uiPath = Join-Path $script:scratch "$captureId.xml"
            $afterPath = Join-Path $script:scratch "$captureId-after.xml"
            $imagePath = Join-Path $script:scratch "$captureId.png"
            $uptime = 10000L + $index * 1000
            [IO.File]::WriteAllText($uiPath, (New-WalkFixtureUi $required[$index] $captureId $uptime), [Text.UTF8Encoding]::new($false))
            [IO.File]::WriteAllText($afterPath, (New-WalkFixtureUi $required[$index] $captureId ($uptime + 100)), [Text.UTF8Encoding]::new($false))
            New-WalkFixturePng $imagePath $required[$index].title
            [pscustomobject]@{ id=$required[$index].id; status='visited'; runId=$script:fixtureRunId; serial='emulator-5554'; captureId=$captureId
                startedUtc=$script:fixtureStarted.AddSeconds(5 + $index * 3).UtcDateTime.ToString('o')
                finishedUtc=$script:fixtureStarted.AddSeconds(6 + $index * 3).UtcDateTime.ToString('o'); uptimeMs=$uptime
                uiPath=$uiPath; uiSha256=(Get-FileHash -LiteralPath $uiPath -Algorithm SHA256).Hash.ToLowerInvariant()
                afterUiPath=$afterPath; afterUiSha256=(Get-FileHash -LiteralPath $afterPath -Algorithm SHA256).Hash.ToLowerInvariant()
                imagePath=$imagePath; imageSha256=(Get-FileHash -LiteralPath $imagePath -Algorithm SHA256).Hash.ToLowerInvariant() }
        })
    $installed = [pscustomobject]@{ runId=$script:fixtureRunId; serial='emulator-5554'; package=$binding.target.package
        versionName=$binding.target.versionName; versionCode=$binding.target.versionCode; appId='10216'
        lastUpdateTime='2026-10-21 00:00:00.000'; fingerprint='synthetic/device/build:16/test:user/release-keys'
        files=@([pscustomobject]@{ name='base.apk'; path='/data/app/~~fixture/com.instagram.android-fixture/base.apk'; sha256=$binding.apkSha256 })
        observedUtc=$script:fixtureStarted.AddSeconds(1).UtcDateTime.ToString('o') }
    $after = $installed | ConvertTo-Json -Depth 8 | ConvertFrom-Json
    $after.observedUtc = $script:fixtureStarted.AddSeconds(119).UtcDateTime.ToString('o')
    $receipt = New-WalkReceipt -Result 'PASS' -Full $true -Serial 'emulator-5554' -Device 'sdk_gphone64_x86_64' -AppVersion $binding.target.versionName `
        -BundleSha256 $binding.bundleSha256 -InstalledApkSha256 $binding.apkSha256 -Started $script:fixtureStarted -Finished $script:fixtureStarted.AddMinutes(2) `
        -Screens $required.Count -Crashes 0 -Problems @() -RunId $script:fixtureRunId -Binding $binding `
        -ArtifactPaths ([pscustomobject]@{ signedApk=$script:fixtureApk; report=$script:fixtureReport; record=$script:fixtureRecord }) `
        -InstallationBefore $installed -InstallationAfter $after -RequiredDestinations $required.id -Observations $observations
    $script:validReceiptPath = Join-Path $script:scratch 'valid.json'
    Write-WalkFixtureJson $script:validReceiptPath $receipt
    $script:evidenceOriginals = @{}
    foreach ($observation in $observations) {
        foreach ($field in @('uiPath','afterUiPath','imagePath')) { $script:evidenceOriginals[$observation.$field] = [IO.File]::ReadAllBytes($observation.$field) }
    }
}
function Restore-WalkFixtureCapture {
    foreach ($path in $script:evidenceOriginals.Keys) { [IO.File]::WriteAllBytes($path, $script:evidenceOriginals[$path]) }
}
function Test-WalkFixtureReceipt([string]$Path = $script:validReceiptPath) {
    Test-WalkReceipt -Path $Path -BundlePath $script:fixtureBundle -CatalogPath $script:fixtureCatalog -Tools $script:fixtureTools
}
function Assert-WalkReceiptRejected([string]$Label, [scriptblock]$Mutation) {
    Restore-WalkFixtureCapture
    $candidate = Read-WalkReceiptDocument -Path $script:validReceiptPath
    & $Mutation $candidate
    $candidatePath = Join-Path $script:scratch 'mutated.json'
    Write-WalkFixtureJson $candidatePath $candidate
    $checked = Test-WalkFixtureReceipt $candidatePath
    Assert-WalkTest (-not $checked.Valid) "The receipt gate accepted $Label."
}
function Set-WalkFixtureXml($Receipt, [string]$Kind, [scriptblock]$Mutation, [int]$Index = 0) {
    $observation = $Receipt.observations[$Index]
    $path = $observation.$Kind
    $text = & $Mutation ([IO.File]::ReadAllText($path))
    [IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false))
    $hashField = if ($Kind -ceq 'uiPath') { 'uiSha256' } else { 'afterUiSha256' }
    $observation.$hashField = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
}
$script:scratch = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('hushgram-walk-app-' + [guid]::NewGuid().ToString('N'))))
New-Item -ItemType Directory -Path $script:scratch | Out-Null
$script:fixtureIdentity = 'hg1:' + ('b' * 64)
$script:fixtureBundle = Join-Path $script:scratch 'patches-0.0.99.mpp'
$script:fixtureCatalog = Join-Path $script:scratch 'patches-list.json'
$script:fixtureApk = Join-Path $script:scratch 'instagram-signed.apk'
$script:fixtureReport = Join-Path $script:scratch 'result.json'
$script:fixtureRecord = Join-Path $script:scratch 'patch-record.json'
$script:fixtureTools = [pscustomobject]@{ Aapt2='synthetic-aapt2'; Java='synthetic-java'; ApkSignerJar='synthetic-apksigner.jar' }
try {
    # A publisher imports the receipt helper in a fresh process. Loading the target helpers
    # explicitly above must not hide an incomplete production import chain.
    $importProbe = Join-Path $script:scratch 'receipt-import.ps1'
    [IO.File]::WriteAllText($importProbe, @'
param([string]$Helper)
$ErrorActionPreference='Stop'
. $Helper
foreach ($name in @('Get-MppBuildIdentity','Split-SecondAppPatches','Get-PatchTarget','Test-DeclaredBuild',
    'Get-PatchDependencyNames','Test-PatchingReport','Get-NativeRunnerTarget','Get-WalkArtifactBinding')) {
    if (-not (Get-Command $name -CommandType Function -ErrorAction SilentlyContinue)) { throw "Missing receipt dependency $name" }
}
'@, [Text.UTF8Encoding]::new($false))
    $importOutput = @(& (Get-Process -Id $PID).Path -NoProfile -File $importProbe (Join-Path $testRepo 'scripts/walk-receipt.ps1') 2>&1)
    Assert-WalkTest ($LASTEXITCODE -eq 0) "The fresh release validator lacks its own dependencies: $($importOutput -join ' ')"
    if ($PSVersionTable.PSVersion -lt [version]'7.5') {
        Assert-WalkTest ((Get-NativeRunnerSha256Text 'abc') -ceq 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad') 'Legacy ASCII SHA-256 vector failed.'
        Assert-WalkTest ((Get-NativeRunnerSha256Text ([string][char]0xe9)) -ceq '4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c') 'Legacy UTF-8 SHA-256 vector failed.'
        Reset-WalkArtifactFixture
        $legacyTarget = Get-NativeRunnerTarget -Tools $script:fixtureTools -Apk $script:fixtureApk -Report $script:fixtureReport -Record $script:fixtureRecord
        Assert-WalkTest ($legacyTarget.BuildIdentity -ceq $script:fixtureIdentity) 'Legacy native source reading changed.'
        Assert-WalkTest ($legacyTarget.Sha256 -ceq (Get-FileHash -LiteralPath $script:fixtureApk -Algorithm SHA256).Hash.ToLowerInvariant()) 'Legacy native APK hashing changed.'
        Assert-WalkTest ($legacyTarget.SignerSha256 -ceq ('a' * 64)) 'Legacy native signer reading changed.'
        Assert-WalkTest ($null -eq (Test-PatchRecord -Apk $script:fixtureApk -Result $script:fixtureReport -Record $script:fixtureRecord)) 'Legacy content-record verification changed.'
        $script:fixtureSdkReads = 0
        Assert-WalkRefused { Get-WalkFixtureBinding } '*requires PowerShell 7*'
        Assert-WalkTest ($script:fixtureSdkReads -eq 0) 'Unsupported strict artifact proof reached the SDK.'
        $unsupported = Test-WalkFixtureReceipt -Path (Join-Path $script:scratch 'missing.json')
        Assert-WalkTest (-not $unsupported.Valid -and $unsupported.Reason -like '*PowerShell 7*') 'Unsupported shells need a helpful, closed receipt result.'
        Write-Output "[walk-app] $script:passed compatibility checks passed on PowerShell $($PSVersionTable.PSVersion)"
        return
    }
    Test-WalkArtifactFixture
    if ($ArtifactOnly) {
        Write-Output "[walk-app] $script:passed artifact checks passed on PowerShell $($PSVersionTable.PSVersion)"
        return
    }
    New-WalkReceiptFixture
    $valid = Test-WalkFixtureReceipt
    Assert-WalkTest $valid.Valid "The real synthetic walkthrough was refused: $($valid.Reason)"
    $badJsonPath = Join-Path $script:scratch 'bad-shape.json'
    $validText = [IO.File]::ReadAllText($script:validReceiptPath)
    [IO.File]::WriteAllText($badJsonPath, ('[' + $validText + ']'), [Text.UTF8Encoding]::new($false))
    Assert-WalkTest (-not (Test-WalkFixtureReceipt $badJsonPath).Valid) 'A one-element JSON array cannot become the receipt object.'
    [IO.File]::WriteAllText($badJsonPath, $validText.Replace('"schema": 2,', '"schema": 1, "schema": 2,'), [Text.UTF8Encoding]::new($false))
    Assert-WalkTest (-not (Test-WalkFixtureReceipt $badJsonPath).Valid) 'Repeated JSON object fields cannot be overwritten into a passing receipt.'
    $requiredIds = @('home','explore')
    $visited = @([pscustomobject]@{ id='home'; status='visited' }, [pscustomobject]@{ id='explore'; status='visited' })
    Assert-WalkTest ((Resolve-WalkOutcome -Screens 2 -Crashes 0 -Problems @() -Full $true -RequiredDestinations $requiredIds -Observations $visited) -ceq 'PASS') 'Named complete coverage should pass.'
    Assert-WalkTest ((Resolve-WalkOutcome -Screens 12 -Crashes 0 -Problems @() -Full $true) -ceq 'PARTIAL') 'An arbitrary screen count cannot prove named coverage.'
    Assert-WalkTest ((Resolve-WalkOutcome -Screens 2 -Crashes 1 -Problems @() -Full $true -RequiredDestinations $requiredIds -Observations $visited) -ceq 'FAIL') 'A crash must fail.'
    Assert-WalkTest ((Resolve-WalkOutcome -Screens 2 -Crashes 0 -Problems @('destination covered') -Full $true -RequiredDestinations $requiredIds -Observations $visited) -ceq 'FAIL') 'A reported problem must fail.'
    Assert-WalkTest ((Resolve-WalkOutcome -Screens 2 -Crashes 0 -Problems @() -Full $false -RequiredDestinations $requiredIds -Observations $visited) -ceq 'PARTIAL') 'An incomplete walk must remain partial.'
    Assert-WalkReceiptRejected 'one screen' { param($r) $r.screens = 1 }
    Assert-WalkReceiptRejected 'a reported problem' { param($r) $r.problems = @('Hush settings covered the destination') }
    Assert-WalkReceiptRejected 'a missing installed hash' { param($r) $r.installedApkSha256 = '' }
    Assert-WalkReceiptRejected 'the wrong app version' { param($r) $r.appVersion = '0.0.0' }
    Assert-WalkReceiptRejected 'a reversed interval' { param($r) $r.finished = $script:fixtureStarted.AddSeconds(-1).UtcDateTime.ToString('o') }
    Assert-WalkReceiptRejected 'no destinations' { param($r) $r.observations = @() }
    Assert-WalkReceiptRejected 'no installation binding' { param($r) $r.installationBefore = $null; $r.installationAfter = $null }
    foreach ($field in @('schema','result','full','crashes','screens','runId','serial','device','emulator','appVersion','bundleSha256','installedApkSha256','started','finished','policySha256')) {
        Assert-WalkReceiptRejected "an array-valued $field" { param($r) $r.$field = @($r.$field) }
    }
    foreach ($field in @('schema','bundleSha256','catalogSha256','apkSha256','reportSha256','recordSha256','apkContentSha256','sourceBuildIdentity','selectionSha256')) {
        Assert-WalkReceiptRejected "an array-valued binding $field" { param($r) $r.binding.$field = @($r.binding.$field) }
    }
    foreach ($field in @('package','versionName','versionCode','signerSha256')) {
        Assert-WalkReceiptRejected "an array-valued target $field" { param($r) $r.binding.target.$field = @($r.binding.target.$field) }
    }
    foreach ($field in @('runId','serial','package','versionName','versionCode','appId','lastUpdateTime','fingerprint','observedUtc')) {
        Assert-WalkReceiptRejected "an array-valued installation $field" { param($r) $r.installationBefore.$field = @($r.installationBefore.$field) }
    }
    foreach ($field in @('id','status','serial','runId','captureId','startedUtc','finishedUtc','uptimeMs','uiPath','uiSha256','afterUiPath','afterUiSha256','imagePath','imageSha256')) {
        Assert-WalkReceiptRejected "an array-valued observation $field" { param($r) $r.observations[0].$field = @($r.observations[0].$field) }
    }
    Assert-WalkReceiptRejected 'a numeric application ID' { param($r) $r.installationBefore.appId = 10216 }
    Assert-WalkReceiptRejected 'a non-date update time' { param($r) $r.installationBefore.lastUpdateTime = 'unknown' }
    Assert-WalkReceiptRejected 'an impossible UTC date' { param($r) $r.started = '2026-02-30T00:00:00Z' }
    Assert-WalkReceiptRejected 'a local timestamp' { param($r) $r.started = '2026-10-21T00:00:00' }
    Assert-WalkReceiptRejected 'a future finish' { param($r) $r.finished = [DateTime]::UtcNow.AddMinutes(5).ToString('o') }
    Assert-WalkReceiptRejected 'a zero-length interval' { param($r) $r.finished = $r.started }
    Assert-WalkReceiptRejected 'an old receipt schema' { param($r) $r.schema = 1 }
    Assert-WalkReceiptRejected 'a string-valued schema' { param($r) $r.schema = '2' }
    Assert-WalkReceiptRejected 'a string-valued whole-walk flag' { param($r) $r.full = 'true' }
    Assert-WalkReceiptRejected 'a string-valued screen count' { param($r) $r.screens = [string]$r.screens }
    Assert-WalkReceiptRejected 'a string-valued crash count' { param($r) $r.crashes = '0' }
    Assert-WalkReceiptRejected 'a null problem list' { param($r) $r.problems = $null }
    Assert-WalkReceiptRejected 'a missing selection list' { param($r) $r.binding.selectedPatches = $null }
    Assert-WalkReceiptRejected 'a missing target object' { param($r) $r.binding.target = 'com.instagram.android' }
    Assert-WalkReceiptRejected 'a non-array destination list' { param($r) $r.requiredDestinations = 'home' }
    Assert-WalkReceiptRejected 'a duplicate required destination' { param($r) $r.requiredDestinations[1] = $r.requiredDestinations[0] }
    Assert-WalkReceiptRejected 'a duplicate observation destination' { param($r) $r.observations[1].id = $r.observations[0].id }
    Assert-WalkReceiptRejected 'a duplicate capture ID' { param($r) $r.observations[1].captureId = $r.observations[0].captureId }
    Assert-WalkReceiptRejected 'a stale source identity' { param($r) $r.binding.sourceBuildIdentity = 'hg1:' + ('c' * 64) }
    Assert-WalkReceiptRejected 'a changed before-install hash' { param($r) $r.installationBefore.files[0].sha256 = 'c' * 64 }
    Assert-WalkReceiptRejected 'a changed after-install hash' { param($r) $r.installationAfter.files[0].sha256 = 'c' * 64 }
    foreach ($field in @('appId','lastUpdateTime','fingerprint','versionName','versionCode','serial','runId')) {
        Assert-WalkReceiptRejected "installation drift at $field" { param($r) $r.installationAfter.$field += '1' }
    }
    Assert-WalkReceiptRejected 'a changed installed path' { param($r) $r.installationAfter.files[0].path = '/data/app/another/base.apk' }
    Assert-WalkReceiptRejected 'unbound installed splits' { param($r) $r.installationAfter.files += $r.installationAfter.files[0] }
    Assert-WalkReceiptRejected 'installation reads in reverse order' { param($r) $r.installationAfter.observedUtc = $r.installationBefore.observedUtc }
    Assert-WalkReceiptRejected 'an installation read before the walk' { param($r) $r.installationBefore.observedUtc = $script:fixtureStarted.AddSeconds(-1).UtcDateTime.ToString('o') }
    Assert-WalkReceiptRejected 'a capture before installation proof' { param($r) $r.observations[0].startedUtc = $r.started }
    Assert-WalkReceiptRejected 'a capture after installation proof' { param($r) $r.observations[0].finishedUtc = $r.finished }
    Assert-WalkReceiptRejected 'a zero-length capture' { param($r) $r.observations[0].finishedUtc = $r.observations[0].startedUtc }
    Assert-WalkReceiptRejected 'overlapping captures' { param($r) $r.observations[1].startedUtc = $r.observations[0].startedUtc }
    Assert-WalkReceiptRejected 'a duplicate active root' { param($r) Set-WalkFixtureXml $r 'uiPath' { param($text) $doc = [xml]$text; [void]$doc.DocumentElement.AppendChild($doc.DocumentElement.FirstChild.CloneNode($true)); $doc.OuterXml } }
    Assert-WalkReceiptRejected 'a missing active root' { param($r) Set-WalkFixtureXml $r 'uiPath' { param($text) $doc = [xml]$text; [void]$doc.DocumentElement.RemoveChild($doc.DocumentElement.FirstChild); $doc.OuterXml } }
    Assert-WalkReceiptRejected 'a missing window ID' { param($r) Set-WalkFixtureXml $r 'uiPath' { param($text) $text.Replace(' window-id="42"','') } }
    Assert-WalkReceiptRejected 'duplicate window ID attributes' { param($r) Set-WalkFixtureXml $r 'uiPath' { param($text) $text.Replace('window-id="42"','window-id="42" window-id="43"') } }
    Assert-WalkReceiptRejected 'a changed window around the screenshot' { param($r) Set-WalkFixtureXml $r 'afterUiPath' { param($text) $text.Replace('window-id="42"','window-id="43"') } }
    Assert-WalkReceiptRejected 'a malformed window ID' { param($r) Set-WalkFixtureXml $r 'uiPath' { param($text) $text.Replace('window-id="42"','window-id="042"') } }
    Assert-WalkReceiptRejected 'a stale XML run' { param($r) Set-WalkFixtureXml $r 'uiPath' { param($text) $text.Replace($script:fixtureRunId, ('e' * 32)) } }
    Assert-WalkReceiptRejected 'a missing XML uptime' { param($r) Set-WalkFixtureXml $r 'uiPath' { param($text) $text.Replace(' uptime-ms="10000"','') } }
    Assert-WalkReceiptRejected 'an incorrect observation uptime' { param($r) $r.observations[0].uptimeMs++ }
    Assert-WalkReceiptRejected 'reversed device uptimes' { param($r) Set-WalkFixtureXml $r 'afterUiPath' { param($text) $text.Replace('uptime-ms="10100"','uptime-ms="9999"') } }
    Assert-WalkReceiptRejected 'a final snapshot ten minutes beyond its one-second host capture' {
        param($r)
        Set-WalkFixtureXml $r 'afterUiPath' {
            param($text)
            $document = [xml]$text
            $inflated = [long]$document.DocumentElement.GetAttribute('uptime-ms') + 600000
            $document.DocumentElement.SetAttribute('uptime-ms', $inflated.ToString([Globalization.CultureInfo]::InvariantCulture))
            $document.OuterXml
        } ($r.observations.Count - 1)
    }
    Restore-WalkFixtureCapture
    $tolerated = Read-WalkReceiptDocument -Path $script:validReceiptPath
    $lastIndex = $tolerated.observations.Count - 1
    $toleratedUptime = $tolerated.observations[$lastIndex].uptimeMs + 3000
    Set-WalkFixtureXml $tolerated 'afterUiPath' {
        param($text)
        $document = [xml]$text
        $document.DocumentElement.SetAttribute('uptime-ms', $toleratedUptime.ToString([Globalization.CultureInfo]::InvariantCulture))
        $document.OuterXml
    } $lastIndex
    $toleratedPath = Join-Path $script:scratch 'tolerated-timing.json'
    Write-WalkFixtureJson $toleratedPath $tolerated
    $timingControl = Test-WalkFixtureReceipt $toleratedPath
    Assert-WalkTest $timingControl.Valid "The documented two-second scheduling tolerance was refused: $($timingControl.Reason)"
    Assert-WalkReceiptRejected 'identical before and after snapshots' { param($r) $r.observations[0].afterUiPath = $r.observations[0].uiPath; $r.observations[0].afterUiSha256 = $r.observations[0].uiSha256 }
    Assert-WalkReceiptRejected 'a damaged PNG with a matching recorded hash' { param($r) $path=$r.observations[0].imagePath; [IO.File]::WriteAllBytes($path, [byte[]]@(1,2,3)); $r.observations[0].imageSha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
    Restore-WalkFixtureCapture
    foreach ($artifact in @('fixtureBundle','fixtureCatalog','fixtureApk','fixtureReport','fixtureRecord')) {
        $artifactPath = Get-Variable -Name $artifact -Scope Script -ValueOnly
        $originalBytes = [IO.File]::ReadAllBytes($artifactPath)
        try {
            if ($artifact -ceq 'fixtureBundle') { Set-WalkFixtureBundle -Payload 'changed bundle payload, unchanged source claim' }
            elseif ($artifact -ceq 'fixtureApk') { Set-WalkFixtureApk -Identity ('hg1:' + ('c' * 64)) -VersionCode '2147483647' }
            else { [IO.File]::AppendAllText($artifactPath, "`n", [Text.UTF8Encoding]::new($false)) }
            $changed = Test-WalkFixtureReceipt
            Assert-WalkTest (-not $changed.Valid) "The receipt accepted post-walk $artifact drift."
        } finally { [IO.File]::WriteAllBytes($artifactPath, $originalBytes) }
    }
    $valid = Test-WalkFixtureReceipt
    Assert-WalkTest $valid.Valid "The valid control was damaged by a negative test: $($valid.Reason)"
    . (Join-Path $PSScriptRoot 'script-wiring.ps1')
    Assert-WalkTest (Test-PushGateRunsSuite (Join-Path $PSScriptRoot 'pre-push.ps1') 'scripts/test-walk-app.ps1') 'The push gate does not run walkthrough tests.'
    Write-Output "[walk-app] $script:passed checks passed on PowerShell $($PSVersionTable.PSVersion)"
} finally {
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (-not $script:scratch.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe walkthrough fixture cleanup path.' }
    if (Test-Path -LiteralPath $script:scratch) { Remove-Item -LiteralPath $script:scratch -Recurse -Force }
}
