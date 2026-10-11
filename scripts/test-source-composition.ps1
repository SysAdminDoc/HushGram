<#
.SYNOPSIS
    Exercise source ownership, target refusals and native post-merge initialization.
.NOTES
    https://github.com/SysAdminDoc/HushGram
    Original HushGram tooling. GPL-3.0-only. Does not install or operate devices.
#>
[CmdletBinding()]
param([string]$Root, [string]$DesktopJar, [string]$Java, [string]$Aapt2, [string]$AndroidJar,
    [string]$HushBundle, [string]$PikoBundle, [string]$FailureLog, [string]$OriginalApk,
    [string]$WorkDir = (Join-Path $env:TEMP 'hushgram-composition-tests'))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'apk-facts.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'patch-sources.ps1')
. (Join-Path $PSScriptRoot 'script-wiring.ps1')
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
$Java = Resolve-Java -Explicit $Java
$Java = (Get-Command -Name $Java -ErrorAction Stop | Select-Object -First 1).Source
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $root -Required
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $root
if (-not $AndroidJar) {
    $sdk = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $Aapt2))
    $AndroidJar = Get-ChildItem -LiteralPath (Join-Path $sdk 'platforms') -Directory |
        Where-Object { $_.Name -match '^android-\d+$' } |
        Sort-Object { [int]($_.Name -replace '^android-', '') } -Descending |
        ForEach-Object { Join-Path $_.FullName 'android.jar' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}
if (-not $AndroidJar) { throw 'A local Android platform jar is required for native composition fixtures.' }
$shell = (Get-Process -Id $PID).Path
New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null
$scratch = Join-Path (Resolve-Path -LiteralPath $WorkDir).Path ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
$classes = Join-Path $scratch 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
$javac = Join-Path (Split-Path -Parent $Java) 'javac.exe'
& $javac '-cp' $DesktopJar '-d' $classes (Join-Path $PSScriptRoot 'CompositionFixture.java') `
    (Join-Path $PSScriptRoot 'CompositionInitializer.java')
if ($LASTEXITCODE -ne 0) { throw 'Composition fixtures did not compile.' }
& $Java '-cp' "$DesktopJar$([IO.Path]::PathSeparator)$classes" 'CompositionFixture' $classes $scratch
if ($LASTEXITCODE -ne 0) { throw 'Composition fixture creation failed.' }
$manifest = Join-Path $scratch 'AndroidManifest.xml'
[IO.File]::WriteAllText($manifest, '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.instagram.android" android:versionCode="385611438" android:versionName="450.0.0.50.77"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36"/><application android:label="Composition fixture"/></manifest>')
$apk = Join-Path $scratch 'stock.apk'
& $Aapt2 'link' '-o' $apk '--manifest' $manifest '-I' $AndroidJar
if ($LASTEXITCODE -ne 0) { throw 'Native fixture manifest did not compile.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$zip = [IO.Compression.ZipFile]::Open($apk, [IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $scratch 'stock.dex'), 'classes.dex') | Out-Null }
finally { $zip.Dispose() }
$originalHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
$script:passed = 0
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Check([string]$Name, [scriptblock]$Body) {
    & $Body
    $script:passed++
    Write-Host "PASS $Name"
}
function Selection([string]$Bundle, [string[]]$Patches) {
    return [ordered]@{ bundle = (Resolve-Path -LiteralPath $Bundle).Path; patches = $Patches }
}
function Run-Sources([object[]]$Items, [switch]$InspectOnly, [string]$InputApk = $apk, [string]$Log) {
    $case = Join-Path $scratch ([guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $case | Out-Null
    $selection = Join-Path $case 'selected.json'
    [IO.File]::WriteAllText($selection, ([ordered]@{ schemaVersion = 1; sources = $Items } | ConvertTo-Json -Depth 5))
    $arguments = @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'patch-with-sources.ps1'),
        '-Apk', $InputApk, '-Selections', $selection, '-DesktopJar', $DesktopJar,
        '-Java', $Java, '-Aapt2', $Aapt2, '-WorkDir', $case)
    if ($InspectOnly) { $arguments += '-InspectOnly' }
    if ($Log) { $arguments += @('-FailureLog', $Log) }
    $previous = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; $output = @(& $shell @arguments 2>&1); $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $previous }
    $path = Get-ChildItem -LiteralPath $case -Filter 'source-diagnostic.json' -Recurse -File | Select-Object -First 1
    $report = if ($path) { [IO.File]::ReadAllText($path.FullName) | ConvertFrom-Json } else { $null }
    return [pscustomobject]@{ Exit = $code; Report = $report; Directory = $case; Text = ($output -join "`n") }
}
function Refused($Result, [string]$Reason) {
    Assert ($Result.Exit -ne 0) 'A refused composition returned success.'
    Assert ($null -ne $Result.Report -and -not $Result.Report.preflight.valid) 'Refusal lacks its ownership report.'
    Assert ($Result.Report.preflight.reason -like $Reason) "Wrong refusal: $($Result.Report.preflight.reason)"
    Assert (-not $Result.Report.patching.started) 'A refused composition started patching.'
    Assert (@(Get-ChildItem -LiteralPath $Result.Directory -Recurse -File | Where-Object {
        $_.Name -in 'patched-unsigned.apk', 'patch-result.json', 'stock-merged.apk' }).Count -eq 0) 'Refusal merged or patched APK bytes.'
}
Check 'composition regressions are dispatched by the actual push gate' {
    Assert (Test-PushGateRunsSuite (Join-Path $Root 'scripts/pre-push.ps1') 'scripts/test-source-composition.ps1') 'The push gate does not dispatch the composition suite.'
}
$base = Selection (Join-Path $scratch 'base.mpp') @('Fixture base')
$addon = Selection (Join-Path $scratch 'addon.mpp') @('Compatible addon')
Check 'declared compatible sources pass preflight without patching' {
    $r = Run-Sources @($base, $addon) -InspectOnly
    Assert ($r.Exit -eq 0) $r.Text
    Assert ($r.Report.sources.Count -eq 2 -and $r.Report.preflight.valid -and -not $r.Report.patching.started) 'Incomplete selection report.'
    Assert ($r.Report.failureEvidence.kind -ceq 'not-run') 'Inspection was mislabeled as native failure evidence.'
    Assert (@($r.Report.sources[0].patches | Where-Object { $_.name -ceq 'Fixture settings' }).Count -eq 1) 'Named dependency was lost.'
}
Check 'relative bundle paths belong to the selection file directory' {
    $r = Run-Sources @([ordered]@{ bundle = '../base.mpp'; patches = @('Fixture base') }) -InspectOnly
    Assert ($r.Exit -eq 0 -and $r.Report.sources[0].identity.name -ceq 'Fixture base') $r.Text
}
Check 'incompatible version is attributed before any merge or patch' {
    Refused (Run-Sources @($base, (Selection (Join-Path $scratch 'wrong-version.mpp') @('Compatible addon')))) '*Fixture wrong-version*439.0.0.37.89*not 449.0.0.52.84*'
}
Check 'different code of the same version is refused' {
    Refused (Run-Sources @($base, (Selection (Join-Path $scratch 'wrong-code.mpp') @('Compatible addon')))) '*385511870*not*385511871*'
}
Check 'unrestricted native declarations stay allowed but missing targets stay unknown' {
    $source = [pscustomobject]@{ identity = [pscustomobject]@{ name = 'Metadata fixture'; version = '1'; sha256 = ('a' * 64) }
        patches = @([pscustomobject]@{ name = 'Example'; compatibility = $null }) }
    Assert (Test-SelectedPatchTargets @($source) 'com.instagram.android' '449.0.0.52.84' '385511871').Valid 'A universal dependency was refused.'
    $source.patches[0].compatibility = @([pscustomobject]@{ packageName = 'com.instagram.android'; targets = @(
        [pscustomobject]@{ version = $null; versionCodes = [pscustomobject]@{} }) })
    Assert (Test-SelectedPatchTargets @($source) 'com.instagram.android' '449.0.0.52.84' '385511871').Valid 'A native unrestricted AppTarget was refused.'
    $source.patches[0].compatibility[0].targets = @()
    $verdict = Test-SelectedPatchTargets @($source) 'com.instagram.android' '449.0.0.52.84' '385511871'
    Assert (-not $verdict.Valid -and $verdict.Reason -like '*unknown declared targets*') 'Missing targets were invented as version compatibility.'
}
Check 'a dependency cannot silently declare another host version' {
    Refused (Run-Sources @($base, (Selection (Join-Path $scratch 'dependency-target.mpp') @('Compatible addon')))) '*Fixture settings*439.0.0.37.89*'
}
Check 'conflicting selected extension definitions identify both owners' {
    Refused (Run-Sources @($base, (Selection (Join-Path $scratch 'conflict.mpp') @('Compatible addon')))) '*Conflicting selected extension definition*Fixture base*Fixture conflict*'
}
Check 'identical definitions are not mislabeled as conflicting' {
    $r = Run-Sources @($base, (Selection (Join-Path $scratch 'identical.mpp') @('Compatible addon'))) -InspectOnly
    Assert ($r.Exit -eq 0 -and $r.Report.preflight.identicalSharedDefinitions -eq 1) $r.Text
}
Check 'debug parameter names do not create definition conflicts' {
    $r = Run-Sources @($base, (Selection (Join-Path $scratch 'identical-debug.mpp') @('Compatible addon'))) -InspectOnly
    Assert ($r.Exit -eq 0 -and $r.Report.preflight.identicalSharedDefinitions -eq 1) $r.Text
}
Check 'unselected incompatible patches and extensions do not block an addon' {
    $r = Run-Sources @($base, (Selection (Join-Path $scratch 'mixed.mpp') @('Compatible addon'))) -InspectOnly
    Assert ($r.Exit -eq 0 -and $r.Report.preflight.valid) $r.Text
    Assert (@($r.Report.sources[1].patches | Where-Object { $_.name -ceq 'Unselected old patch' }).Count -eq 0) 'Unselected patch entered the closure.'
}
Check 'selecting that incompatible patch explicitly is refused' {
    Refused (Run-Sources @($base, (Selection (Join-Path $scratch 'mixed.mpp') @('*')))) '*Unselected old patch*439.0.0.37.89*'
}
Check 'native compatible addon retains merge, initialization and finalization' {
    $r = Run-Sources @($base, $addon)
    Assert ($r.Exit -eq 0 -and $r.Report.patching.valid) $r.Text
    $out = Get-ChildItem -LiteralPath $r.Directory -Recurse -Filter 'patched-unsigned.apk' -File | Select-Object -First 1
    & $Java '-cp' "$DesktopJar$([IO.Path]::PathSeparator)$classes" 'CompositionFixture' 'assert' $out.FullName `
        'Lapp/hushgram/fixture/base/Bridge;' 'Lapp/hushgram/fixture/addon/Bridge;' 'Lapp/hushgram/fixture/Stock;'
    Assert ($LASTEXITCODE -eq 0) 'Native merge/initializer/finalizer assertion failed.'
}
Check 'a native foreign initializer failure is attributed to its bundle' {
    $r = Run-Sources @($base, (Selection (Join-Path $scratch 'failure.mpp') @('Compatible addon')))
    Assert ($r.Exit -ne 0 -and $r.Report.patching.started -and -not $r.Report.patching.valid) 'Native initializer failure was hidden.'
    Assert ($r.Report.failures.Count -ge 1) 'Native failure lacks ownership.'
    Assert ($r.Report.failures[0].dependencyOwner.name -ceq 'Fixture failure') 'Foreign initializer was assigned to the base bundle.'
    Assert ($r.Report.failures[0].initializer -ceq 'CompositionInitializer') 'Initializer identity was lost.'
}
Check 'source reports contain no raw private input paths' {
    $r = Run-Sources @($base, $addon) -InspectOnly
    $text = $r.Report | ConvertTo-Json -Depth 24
    Assert (-not $text.Contains($scratch) -and -not $text.Contains($root) -and -not $text.Contains($DesktopJar)) 'A private path entered the diagnostic.'
}
Check 'unknown and ambiguous class owners stay unknown' {
    $a = [pscustomobject]@{ identity = [pscustomobject]@{ name = 'One'; sha256 = ('a' * 64) }; classes = @('test.Initializer'); patches = @() }
    $b = [pscustomobject]@{ identity = [pscustomobject]@{ name = 'Two'; sha256 = ('b' * 64) }; classes = @('test.Initializer'); patches = @() }
    $log = "Patch 'Example' failed:`nFailed to match the fingerprint: test.Initializer@123`nprivate-path"
    $r = @(Get-PatchFailureOwnership -Sources @($a, $b) -Text $log)
    Assert ($r.Count -eq 1 -and $null -eq $r[0].dependencyOwner -and $r[0].attribution -ceq 'unknown-or-ambiguous') 'Ambiguous owner was guessed.'
    $r = @(Get-PatchFailureOwnership -Sources @($a) -Text $log.Replace('test.Initializer', 'foreign.Initializer'))
    Assert ($null -eq $r[0].dependencyOwner) 'Unknown initializer was assigned to a known source.'
    Assert (-not ($r | ConvertTo-Json -Depth 8).Contains('private-path')) 'Raw error text entered the report.'
}
Check 'missing selected patch is refused without starting patching' {
    $r = Run-Sources @((Selection (Join-Path $scratch 'addon.mpp') @('Not in this bundle')))
    Refused $r '*Unknown selected-source compatibility*Fixture addon*'
    Assert (@(Get-ChildItem -LiteralPath $r.Directory -Recurse -Filter '*.apk' -File).Count -eq 0) 'Missing selection started mutation.'
}
Check 'unreadable bundle receives an exact hash and refuses before mutation' {
    $broken = Join-Path $scratch 'broken.mpp'
    [IO.File]::WriteAllText($broken, 'Not a patch bundle')
    $r = Run-Sources @((Selection $broken @('Missing')))
    Refused $r '*Unknown selected-source compatibility*Unknown bundle*'
    Assert ($r.Report.sources[0].identity.sha256 -ceq (Get-FileHash -LiteralPath $broken -Algorithm SHA256).Hash.ToLowerInvariant()) 'Unreadable source lost its byte identity.'
}
Check 'schema identifiers must be integer versions' {
    foreach ($version in @('1', $true, 1.5)) {
        $path = Join-Path $scratch ('invalid-' + [guid]::NewGuid().ToString('N') + '.json')
        [IO.File]::WriteAllText($path, ([ordered]@{ schemaVersion = $version; sources = @($base) } | ConvertTo-Json -Depth 5))
        $refused = $false
        try { Read-PatchSourceSelection -Path $path | Out-Null } catch { $refused = $true }
        Assert $refused 'A coerced schema identifier was accepted.'
    }
}
Check 'selected bundles are immutable local snapshots' {
    $r = Run-Sources @($base, $addon) -InspectOnly
    $copy = Get-ChildItem -LiteralPath $r.Directory -Filter 'bundle.mpp' -Recurse -File | Select-Object -First 1
    Assert ($copy -and $copy.FullName -ne $base.bundle) 'Inspector retained the live build path.'
    Assert ((Get-FileHash -LiteralPath $copy.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $r.Report.sources[0].identity.sha256) 'Snapshot identity differs from the selected bytes.'
}
if ($HushBundle -and $PikoBundle -and $FailureLog -and $OriginalApk) {
    Check 'actual Piko439 and HushGram450 are refused before original APK mutation' {
        $before = (Get-FileHash -LiteralPath $OriginalApk -Algorithm SHA256).Hash
        $names = @('Disable analytics', 'Remove build expired popup', 'View stories anonymously')
        $r = Run-Sources @((Selection $HushBundle $names), (Selection $PikoBundle $names)) -InputApk $OriginalApk -Log $FailureLog
        Refused $r '*Piko*439.0.0.37.89*384510827*not 450.0.0.50.77*385611438*'
        Assert ($r.Report.failures.Count -eq 3) 'The three reported failures were not inspected.'
        foreach ($failure in $r.Report.failures) {
            Assert ($failure.dependencyOwner.name -ceq 'Piko' -and $failure.initializer -like '*NativeSwitchInitializer') 'A reported Piko initializer was mislabeled.'
            Assert ($failure.dependency.Implementation -like '*NativeSettingsSwitchStylePatchKt') 'Piko dependency implementation was lost.'
        }
        Assert ($r.Report.failureEvidence.kind -ceq 'external-log' -and
            $r.Report.failureEvidence.scope -ceq 'selected-bundle-inventory') 'Historical attachment was mislabeled as a current run.'
        Assert ((Get-FileHash -LiteralPath $OriginalApk -Algorithm SHA256).Hash -ceq $before) 'Original fixture changed.'
    }
}
if ($HushBundle -and $OriginalApk) {
    Check 'all actual HushGram patches allow a compatible native addon on original450' {
        $before = (Get-FileHash -LiteralPath $OriginalApk -Algorithm SHA256).Hash
        $r = Run-Sources @((Selection $HushBundle @('*')), $addon) -InputApk $OriginalApk
        Assert ($r.Exit -eq 0 -and $r.Report.patching.valid) $r.Text
        $selectedCount = @($r.Report.sources[0].patches | Where-Object { $_.selected }).Count
        # Desktop reports selected roots. Dependency execution is independently proved below.
        Assert ($selectedCount -eq 88 -and $r.Report.patching.applied -eq 89) 'Actual composition did not apply all 88 HushGram patches and the addon root.'
        Assert (@($r.Report.sources[1].patches | Where-Object { -not $_.selected -and $_.name -ceq 'Fixture settings' }).Count -eq 1) 'Actual composition lost the addon dependency.'
        $out = Get-ChildItem -LiteralPath $r.Directory -Recurse -Filter 'patched-unsigned.apk' -File | Select-Object -First 1
        & $Java '-cp' "$DesktopJar$([IO.Path]::PathSeparator)$classes" 'CompositionFixture' 'assert' $out.FullName 'Lapp/hushgram/fixture/addon/Bridge;'
        Assert ($LASTEXITCODE -eq 0) 'Actual HushGram composition skipped native addon merge/initializer/finalizer.'
        Assert ((Get-FileHash -LiteralPath $OriginalApk -Algorithm SHA256).Hash -ceq $before) 'Actual composition changed original450.'
    }
}
Assert ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ceq $originalHash) 'A native composition changed its input fixture.'
Write-Host "Passed $script:passed source composition scenarios. Native fixture artifacts retained at $scratch"
