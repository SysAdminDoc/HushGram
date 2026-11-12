<#
.SYNOPSIS
    Patch the vendor APK with every HushGram patch, sign it, and optionally install it.

.DESCRIPTION
    verify-all-patches.ps1 answers whether the patches apply and throws its APK away. This
    keeps one, signed with the sideload keystore so it installs on a phone, and installs it
    over adb when a serial and expected device identity are given. Installs preserve app data,
    permissions and signing keys. An incompatible existing signer is refused without uninstalling.

    The store password comes from -KeystorePassword or HUSHGRAM_SIDELOAD_KEYSTORE_PASSWORD,
    falling back to the local test password, sideload. The key password comes from -KeyPassword
    or HUSHGRAM_SIDELOAD_KEY_PASSWORD, otherwise it uses the store password. Explicit parameters
    accept empty passwords. The Morphe arguments travel through a temporary Java argument file
    so passwords are not in the child process command line. The file is deleted when patching exits.

    The vendor APK defaults to the newest declared build in the folder HUSHGRAM_FIXTURE_DIR
    names. -Apk takes any build the catalog declares, and the result is held to that APK's own
    version, read with aapt2. A build the catalog doesn't declare is refused before anything is
    patched. The desktop CLI is found through -DesktopJar, HUSHGRAM_DESKTOP_JAR or
    HUSHGRAM_WORKDIR, Java through -Java, HUSHGRAM_JAVA or JAVA_HOME, and aapt2 through
    -Aapt2, HUSHGRAM_AAPT2 or the SDK. None of them has a machine-specific default.

.EXAMPLE
    scripts/patch-for-device.ps1 -Serial $env:HUSHGRAM_DEVICE_SERIAL -ExpectedModel $env:HUSHGRAM_EXPECTED_DEVICE_MODEL

.NOTES
    Taken from Hushfacebook's scripts/patch-for-device.ps1
    (https://github.com/SysAdminDoc/Hushfacebook, commit c15d4f7930505824789684d35039d3c78c8b0903).
    GPL-3.0-only. Modified for HushGram (Instagram), 2026.
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$ExpectedModel = $env:HUSHGRAM_EXPECTED_DEVICE_MODEL,
    [string]$ExpectedAvd = $env:HUSHGRAM_EXPECTED_AVD,
    [switch]$Replace,
    # Print every line the desktop CLI writes, not only errors.
    [switch]$ShowPatchLog,
    # Patch names to leave out of this build. The catalog applies everything, so a check of what
    # Instagram does without one of them needs a build that leaves it out.
    [string[]]$Exclude = @(),
    # Build the second copy Install next to Instagram makes, under its default package
    # com.instagram.android.hush. Without it that patch is left out, so the build installs over
    # the HushGram already on the phone.
    [switch]$Clone,
    # Patch with the release bundle even when a source file is newer than it, for replaying
    # an earlier build on purpose. Without it a stale bundle stops the run.
    [switch]$AllowStaleBundle,
    # After the install, run ART's verifier over the installed APK and fail on any HushGram class it
    # rejects. Takes a few minutes on the phone.
    [switch]$VerifyClasses,
    [string]$Apk,
    [string]$DesktopJar,
    [string]$Java,
    [string]$Aapt2,
    [string]$Keystore = "$HOME\.android\sideload-release.jks",
    [string]$KeyAlias = 'sideload',
    [AllowEmptyString()][string]$KeystorePassword,
    [AllowEmptyString()][string]$KeyPassword,
    [string]$OutDir = (Join-Path $env:TEMP 'hushgram-device'),
    # The checkout whose catalog and release bundle are used, the one holding this script unless
    # given. The contract tests point it at a fixture.
    [string]$Root
)

$ErrorActionPreference = 'Stop'
if ($Replace) { throw '-Replace is disabled. Keep the installed app and use its existing signing key for an update.' }
# Not a parameter default: Windows PowerShell leaves $PSScriptRoot empty while it evaluates the
# defaults of an advanced script started with -File. $root below is this same variable.
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
# The argument file is written with [IO.File], which reads a relative path against the process
# directory, while java is handed the same path from PowerShell's location. Absolute, they agree.
$OutDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'patch-record.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'apk-facts.ps1')
$Java = Resolve-Java -Explicit $Java
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $root -Required
$catalogPath = Join-Path $root 'patches-list.json'
if (-not (Test-Path -LiteralPath $catalogPath -PathType Leaf)) { throw "No patch list found: $catalogPath" }
try { $catalog = ConvertFrom-PatchJson (Get-Content -LiteralPath $catalogPath -Raw) }
catch { throw "Could not read patch list ${catalogPath}: $($_.Exception.Message)" }
$target = Get-PatchTarget -PatchList $catalog
if (-not $Apk -and $env:HUSHGRAM_FIXTURE_DIR -and (Test-Path -LiteralPath $env:HUSHGRAM_FIXTURE_DIR -PathType Container)) {
    # The fixture the gate and the receipt patch, pinned version code and .apks included. A name
    # match on the version alone skipped Instagram 453's .apks and could take another build's file.
    $fixtureFile = Find-DeclaredFixture -Target $target -Version $target.PackageVersion -Folder $env:HUSHGRAM_FIXTURE_DIR
    if ($fixtureFile) { $Apk = $fixtureFile.FullName }
}
if (-not $Apk -or -not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw ("No vendor APK. Pass -Apk with the $($target.PackageVersion) build, or set " +
        'HUSHGRAM_FIXTURE_DIR to the folder that holds it.')
}
# The version the result is held to: the vendor APK's own, read the way verify-all-patches.ps1
# and the receipt read it. The catalog declares more than one build and the CLI reports the
# version of the APK it was given, so holding every run to the newest build failed a 577 build
# after it had been patched. A build the catalog doesn't declare is refused here: without -f the
# CLI refuses it too, but only after unpacking it, and this is the APK that goes on a phone.
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $root
$stockBase = Join-Path $OutDir 'stock-base.apk'
try {
    $stock = Get-ApkManifestFacts -Apk (Get-BaseApk -Apk $Apk -Destination $stockBase) -Aapt2 $Aapt2
} finally {
    # Only the copy taken out of a bundle. A plain APK is read where it is and stays there.
    Remove-Item -LiteralPath $stockBase -Force -ErrorAction SilentlyContinue
}
if ($stock.package -ne $target.PackageName) {
    throw "$(Split-Path -Leaf $Apk) is $($stock.package), not the catalog's target $($target.PackageName)."
}
# Its version code as well: another arm64 build of a declared version has its own dex, and nothing
# proved the patches on it.
if (-not (Test-DeclaredBuild -Target $target -VersionName ([string]$stock.versionName) -VersionCode ([string]$stock.versionCode))) {
    throw ("$(Split-Path -Leaf $Apk) is $($stock.package) $($stock.versionName), which the bundle does not " +
        "declare, at version code $($stock.versionCode). It declares $(Format-DeclaredBuilds -Target $target). " +
        'Build for a phone from a declared build; scripts/verify-all-patches.ps1 -Force shows what still applies on another one.')
}
$storePassword = $KeystorePassword
if (-not $PSBoundParameters.ContainsKey('KeystorePassword')) {
    $passwordVariable = 'HUSHGRAM_SIDELOAD_KEYSTORE_PASSWORD'
    $storePassword = [Environment]::GetEnvironmentVariable(
        $passwordVariable, [EnvironmentVariableTarget]::Process)
    if ([string]::IsNullOrEmpty($storePassword)) {
        $storePassword = 'sideload'
        Write-Host "[device] $passwordVariable is unset; using the documented local test-key fallback"
    }
}
$entryPassword = $KeyPassword
if (-not $PSBoundParameters.ContainsKey('KeyPassword')) {
    $entryPassword = [Environment]::GetEnvironmentVariable(
        'HUSHGRAM_SIDELOAD_KEY_PASSWORD', [EnvironmentVariableTarget]::Process)
    if ([string]::IsNullOrEmpty($entryPassword)) { $entryPassword = $storePassword }
}
$version = Get-BundleVersion -Root $root
$bundle = Get-ReleaseBundlePath -Root $root -Version $version
if (-not (Test-Path $bundle)) { throw "No bundle at $bundle. Build it first: :patches:generatePatchesList then :patches:buildAndroid, through the governor." }
# Only buildAndroid writes this bundle; a test run after a patch change leaves it behind, and the
# phone would get the previous hooks while the result reads as a verdict on the new ones.
if (-not $AllowStaleBundle) {
    $newerSources = @(Get-SourcesNewerThanBundle -Root $root -Bundle $bundle)
    if ($newerSources.Count -gt 0) {
        throw ("The bundle at $bundle is older than $($newerSources.Count) source file(s), the newest " +
            "$($newerSources[0].FullName). Rebuild it first: :patches:buildAndroid through the governor " +
            '(or pass -AllowStaleBundle to patch with it as it is).')
    }
}
if (-not $Clone) {
    foreach ($secondCopy in (Split-SecondAppPatches -PatchList $catalog).SecondApp) {
        if ($secondCopy -notin $Exclude) { $Exclude += $secondCopy }
    }
}
$names = @($catalog.patches | ForEach-Object { $_.name } | Where-Object { $_ -notin $Exclude })
foreach ($excluded in $Exclude) {
    if ($excluded -notin ($catalog.patches | ForEach-Object { $_.name })) { throw "No patch named '$excluded' to exclude." }
}
if ($Exclude.Count -gt 0) { Write-Host "[device] leaving out: $($Exclude -join ', ')" }
$dependencyNames = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $names)

New-Item -ItemType Directory -Force $OutDir | Out-Null
$out = Join-Path $OutDir "hushgram-$version-signed.apk"
$temp = Join-Path $OutDir 'tmp'
$result = Join-Path $OutDir 'result.json'
if (Test-Path $out) { Remove-Item $out -Force }

Write-Host "[device] $($names.Count) patches from $(Split-Path -Leaf $bundle) onto $(Split-Path -Leaf $Apk)"
$enable = @()
foreach ($name in $names) { $enable += '-e'; $enable += $name }
$arguments = @('patch', '--exclusive', '-p', $bundle, '-o', $out, '-t', $temp, '-r', $result,
    '--keystore', $Keystore, '--keystore-password', $storePassword,
    '--keystore-entry-alias', $KeyAlias, '--keystore-entry-password', $entryPassword) + $enable + @($Apk)
$argumentFile = Join-Path $OutDir 'morphe-patch.args'
$argumentFileLines = @($arguments | ForEach-Object {
    $value = [string]$_
    if ($value.IndexOfAny([char[]]"`r`n") -ge 0) {
        throw 'A Morphe command argument contains a newline and cannot be written safely.'
    }
    '"' + $value.Replace('\', '\\').Replace('"', '\"') + '"'
})
[System.IO.File]::WriteAllLines(
    $argumentFile,
    $argumentFileLines,
    (New-Object System.Text.UTF8Encoding($false)))
try {
    # Continue for the call alone: the CLI logs WARNING and SEVERE on stderr, which Windows
    # PowerShell 5.1 turns into a terminating error under Stop. The exit code decides.
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = -1
        $dropped = [System.Collections.Generic.List[string]]::new()
        & $Java -jar $DesktopJar "@$argumentFile" 2>&1 | ForEach-Object {
            $line = [string]$_
            if (Select-DroppedSmali -Lines $line) { $dropped.Add($line) }
            if ($ShowPatchLog -or $line -match 'SEVERE|ERROR|WARNING|Exception|Saved to|^\[\d+,\d+\] ') { Write-Host "[device] $line" }
        }
        $cliExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    if ($cliExitCode -ne 0) { throw "The desktop CLI exited with $cliExitCode" }
    if ($dropped.Count) {
        throw "The inline assembler left $($dropped.Count) instruction(s) out, and ART would reject the class: $($dropped -join '; ')"
    }
} finally {
    Remove-Item -LiteralPath $argumentFile -Force -ErrorAction SilentlyContinue
    # The CLI unpacks the whole APK here and a run against Instagram leaves gigabytes behind.
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue }
}
# The same report check the throwaway verification applies: every requested patch, every
# step, the target, and a real APK. The build that goes onto a phone deserves no less.
$report = $null
if (Test-Path -LiteralPath $result -PathType Leaf) {
    try { $report = ConvertFrom-PatchJson (Get-Content -LiteralPath $result -Raw) }
    catch { throw "Patching did not produce a complete APK: the patch result doesn't parse ($($_.Exception.Message))" }
}
$validation = Test-PatchingReport -Report $report -ExpectedNames $names `
    -AllowedDependencyNames $dependencyNames -OutputPath $out `
    -ExpectedPackageName $target.PackageName -ExpectedPackageVersion $stock.versionName
if (-not $validation.Valid) { throw "Patching did not produce a complete APK: $($validation.Reason)" }
Assert-HushgramRuntimeFloor -Manifest (Get-ApkManifestFacts -Apk $out -Aapt2 $Aapt2) -Stock $stock
$coverage = @(Get-ApkTargetCoverage -Apk $out -Java $Java -DesktopJar $DesktopJar -Names $names)
$coverageVerdict = Test-TargetCoverage -Coverage $coverage -Names $names -Package $stock.package `
    -VersionName $stock.versionName -VersionCode $stock.versionCode
if (-not $coverageVerdict.Valid -or -not $coverageVerdict.Reviewed) { throw "Target coverage failed: $($coverageVerdict.Reason)" }
foreach ($entry in $coverage) {
    Write-Host "[device] $($entry.family): $($entry.matched)/$($entry.expected) targets; missing: $($entry.missing -join ', ')"
}
Write-Host "[device] applied $(@($report.appliedPatches).Count), failed $(@($report.failedPatches).Count), target $($report.packageName) $($report.packageVersion)"
# Ties result.json to this APK's content, which signing again with another key keeps. The native
# runner takes the patch selection from a result only through this record.
Write-PatchRecord -Apk $out -Result $result -Path (Join-Path $OutDir 'patch-record.json')
Write-Host "[device] $out"

if (-not $Serial) { return }
$adb = (Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $adb) { $adb = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName }
if (-not $adb) { throw 'No adb found. Put it on the PATH or install the platform tools.' }
. (Join-Path $PSScriptRoot 'injected-register-device.ps1')
$deviceLease = Enter-HushgramDeviceLease -Serial $Serial -ExpectedModel $ExpectedModel -ExpectedAvd $ExpectedAvd
try {
Confirm-HushgramDevice -Adb $adb -DeviceLease $deviceLease
Write-Host "[device] installing on $Serial"
# adb prints Failure [...] and exits non-zero on a refused install; without this the script
# went on to print the version of whatever was already on the phone, as if it were this build.
$installed = Invoke-HushgramAdbCommand -Adb $adb -Arguments @('-s', $Serial, 'install', '-r', $out) -DeviceLease $deviceLease
$installed.Output | Out-Host
if ($installed.ExitCode -ne 0) { throw "adb install failed on $Serial. The installed app and data were kept." }
$installedPackage = if ($Clone) { 'com.instagram.android.hush' } else { $target.PackageName }
$facts = Invoke-HushgramAdbCommand -Adb $adb -Arguments @('-s', $Serial, 'shell', 'dumpsys', 'package', $installedPackage) -DeviceLease $deviceLease
if ($facts.ExitCode -ne 0) { throw 'The install finished, but installed build verification failed.' }
$facts.Output | Select-String 'versionName' | Out-Host
if ($VerifyClasses) {
    # ART's own verifier over the installed APK, the one no JVM test runs. A class it rejects throws
    # VerifyError the first time Instagram touches it: a String stub returning a merged Object and a
    # move the inline assembler dropped each took the app down on a chat or profile that way.
    $paths = Invoke-HushgramAdbCommand -Adb $adb -Arguments @('-s', $Serial, 'shell', 'pm', 'path', $installedPackage) -DeviceLease $deviceLease
    $apkOnDevice = (@($paths.Output | ForEach-Object { [string]$_ } | Where-Object { $_ -like 'package:*base.apk' }) | Select-Object -First 1) -replace '^package:', ''
    if (-not $apkOnDevice) { throw "No installed APK path for $installedPackage." }
    Write-Host "[device] verifying every class of $apkOnDevice with dex2oat"
    [void](Invoke-HushgramAdbCommand -Adb $adb -Arguments @('-s', $Serial, 'logcat', '-G', '16M') -DeviceLease $deviceLease)
    [void](Invoke-HushgramAdbCommand -Adb $adb -Arguments @('-s', $Serial, 'logcat', '-c') -DeviceLease $deviceLease)
    $oat = '/data/local/tmp/hushgram-verify'
    $verify = Invoke-HushgramAdbCommand -Adb $adb -Arguments @('-s', $Serial, 'shell',
        "/apex/com.android.art/bin/dex2oat64 --dex-file=$apkOnDevice --dex-location=$apkOnDevice --oat-file=$oat.odex " +
        "--compiler-filter=verify -j2 --runtime-arg -verbose:verifier; status=`$?; rm -f $oat.odex $oat.vdex; exit `$status") -DeviceLease $deviceLease
    if ($verify.ExitCode -ne 0) { throw "dex2oat could not verify the APK on $Serial (exit $($verify.ExitCode))." }
    $log = Invoke-HushgramAdbCommand -Adb $adb -Arguments @('-s', $Serial, 'logcat', '-d') -DeviceLease $deviceLease
    $rejected = @($log.Output | ForEach-Object { [string]$_ } | Where-Object { $_ -match 'Verification failed on class|Verifier rejected class' })
    $ours = @($rejected | Where-Object { $_ -match 'app[./]hushgram' })
    Write-Host "[device] classes rejected: $($ours.Count) of HushGram's, $($rejected.Count - $ours.Count) of Instagram's"
    $ours | Select-Object -First 20 | ForEach-Object { Write-Host "[device] $_" }
    if ($ours.Count) { throw "ART rejects $($ours.Count) HushGram class(es), and the app crashes when it reaches one." }
}
} finally { Exit-HushgramDeviceLease $deviceLease }
