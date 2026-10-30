<#
.SYNOPSIS
    Build and run a read-only active-window capture on an already leased device.

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
#>

. (Join-Path $PSScriptRoot 'native-runner-device.ps1')

$WalkCapturePackage = 'app.hushgram.walkcapture'
$WalkCaptureInstrumentation = 'app.hushgram.walkcapture/app.hushgram.nativerunner.WalkSnapshot'

function New-WalkCaptureApk {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Tools,
        [Parameter(Mandatory = $true)][string]$WorkDirectory,
        [Parameter(Mandatory = $true)][string]$Keystore,
        [Parameter(Mandatory = $true)][string]$KeyAlias,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$KeystorePassword,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$KeyPassword,
        [string]$KeystoreType,
        [string]$ProviderClass,
        [string]$ProviderJar
    )
    $workRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($WorkDirectory)
    # A fresh tree cannot retain a class or manifest from another instrumentation build.
    $buildDirectory = Join-Path $workRoot ('walkcapture-' + [guid]::NewGuid().ToString('N'))
    $sourceDirectory = Join-Path $buildDirectory 'source'
    $javaDirectory = Join-Path $sourceDirectory 'src/app/hushgram/nativerunner'
    [void][IO.Directory]::CreateDirectory($javaDirectory)
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'WalkSnapshot.java') -Destination (Join-Path $javaDirectory 'WalkSnapshot.java') -ErrorAction Stop
    $manifest = @'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.hushgram.walkcapture">
    <application android:label="HushGram walk capture" android:debuggable="true" android:testOnly="true" android:allowBackup="false" />
    <instrumentation android:name="app.hushgram.nativerunner.WalkSnapshot" android:targetPackage="app.hushgram.walkcapture" />
</manifest>
'@
    [IO.File]::WriteAllText((Join-Path $sourceDirectory 'AndroidManifest.xml'), $manifest, [Text.UTF8Encoding]::new($false))
    New-NativeRunnerApk -Tools $Tools -TargetPackage $WalkCapturePackage -WorkDirectory $buildDirectory -Source $sourceDirectory `
        -Keystore $Keystore -KeyAlias $KeyAlias -KeystorePassword $KeystorePassword -KeyPassword $KeyPassword `
        -KeystoreType $KeystoreType -ProviderClass $ProviderClass -ProviderJar $ProviderJar
}

function Install-WalkCaptureApk {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)]$DeviceLease,
        [scriptblock]$AdbInvoker
    )
    Assert-HushgramDeviceLease -DeviceLease $DeviceLease -Serial $Serial
    $apkPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk)
    if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf) -or (Get-Item -LiteralPath $apkPath).Length -eq 0) {
        throw "No nonempty walk capture APK at $apkPath."
    }
    $installed = Invoke-HushgramAdbCommand -Adb $Adb -Arguments @('-s', $Serial, 'install', '-t', '-r', $apkPath) `
        -DeviceLease $DeviceLease -Invoker $AdbInvoker
    if ($installed.ExitCode -ne 0 -or @($installed.Output | Where-Object { $_ -ceq 'Success' }).Count -ne 1 -or
        @($installed.Output | Where-Object { $_ -cmatch '\A(?:Failure|Error)(?:\b|:)'}).Count -ne 0) {
        throw (Format-HushgramAdbFailure -Message 'Could not update the walk capture APK. The installed apps and their data were preserved' -Result $installed)
    }
    $apkPath
}

function Assert-WalkCaptureIdentity {
    param([string]$RunId, [string]$CaptureId, [string]$Package)
    if ($RunId -cnotmatch '\A[a-f0-9]{32}\z' -or $CaptureId -cnotmatch '\A[A-Za-z0-9_-]{1,80}\z' -or
        $Package -cnotmatch '\A[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+\z') {
        throw 'A capture needs a lowercase 32-digit run ID, a safe capture ID and an exact target package.'
    }
}

function Get-WalkCaptureSha256 {
    param([byte[]]$Bytes)
    $digest = [Security.Cryptography.SHA256]::Create()
    try { -join ($digest.ComputeHash($Bytes) | ForEach-Object { $_.ToString('x2') }) }
    finally { $digest.Dispose() }
}

function Get-WalkCaptureRemoteHash {
    param([string]$Adb, [string]$Serial, [string]$RelativePath, $DeviceLease, [scriptblock]$AdbInvoker)
    $read = Invoke-HushgramAdbCommand -Adb $Adb -Arguments @('-s', $Serial, 'shell', 'run-as', $WalkCapturePackage, 'sha256sum', $RelativePath) `
        -DeviceLease $DeviceLease -Invoker $AdbInvoker
    if ($read.ExitCode -ne 0) { throw (Format-HushgramAdbFailure -Message "Could not hash $RelativePath" -Result $read) }
    $lines = @($read.Stdout)
    $match = if ($lines.Count -eq 1) { [regex]::Match($lines[0], '\A([a-f0-9]{64})  ' + [regex]::Escape($RelativePath) + '\z') } else { $null }
    if ($null -eq $match -or -not $match.Success) { throw "The device returned no exact SHA-256 for $RelativePath." }
    $match.Groups[1].Value
}

function Read-WalkCapturePayload {
    param([string]$Adb, [string]$Serial, [string]$RelativePath, [int]$MaxBytes, $DeviceLease, [scriptblock]$AdbInvoker)
    $beforeHash = Get-WalkCaptureRemoteHash -Adb $Adb -Serial $Serial -RelativePath $RelativePath -DeviceLease $DeviceLease -AdbInvoker $AdbInvoker
    # Only base64 travels through the text-only ADB wrapper. The PNG itself is always bytes.
    $read = Invoke-HushgramAdbCommand -Adb $Adb -Arguments @('-s', $Serial, 'shell', 'run-as', $WalkCapturePackage, 'base64', $RelativePath) `
        -DeviceLease $DeviceLease -Invoker $AdbInvoker
    if ($read.ExitCode -ne 0) { throw (Format-HushgramAdbFailure -Message "Could not read $RelativePath" -Result $read) }
    $encoded = @($read.Stdout) -join ''
    if ($encoded.Length -eq 0 -or $encoded.Length -gt (4 * [Math]::Ceiling($MaxBytes / 3.0)) -or
        $encoded.Length % 4 -ne 0 -or $encoded -cnotmatch '\A[A-Za-z0-9+/]*={0,2}\z') {
        throw "The device returned malformed or oversized base64 for $RelativePath."
    }
    try { [byte[]]$bytes = [Convert]::FromBase64String($encoded) }
    catch { throw "The device returned malformed base64 for $RelativePath." }
    if ($bytes.Length -eq 0 -or $bytes.Length -gt $MaxBytes -or [Convert]::ToBase64String($bytes) -cne $encoded) {
        throw "The device returned noncanonical or oversized base64 for $RelativePath."
    }
    $localHash = Get-WalkCaptureSha256 -Bytes $bytes
    $afterHash = Get-WalkCaptureRemoteHash -Adb $Adb -Serial $Serial -RelativePath $RelativePath -DeviceLease $DeviceLease -AdbInvoker $AdbInvoker
    if ($beforeHash -cne $afterHash -or $localHash -cne $beforeHash) { throw "The capture payload changed while reading $RelativePath." }
    [pscustomobject]@{ Bytes = $bytes; Sha256 = $localHash }
}

function ConvertFrom-WalkCaptureXml {
    param([byte[]]$Bytes, [string]$RunId, [string]$CaptureId, [string]$Package)
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $settings.MaxCharactersInDocument = 16MB
    $reader = $null
    $textReader = $null
    try {
        $text = [Text.UTF8Encoding]::new($false, $true).GetString($Bytes)
        $textReader = [IO.StringReader]::new($text)
        $reader = [Xml.XmlReader]::Create($textReader, $settings)
        $document = [Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($reader)
    } catch { throw "The capture XML is malformed: $($_.Exception.Message)" }
    finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $textReader) { $textReader.Dispose() }
    }
    $hierarchy = $document.DocumentElement
    $roots = @($document.SelectNodes('/hierarchy/node'))
    $windowId = 0
    $uptimeMs = 0L
    if ($null -eq $hierarchy -or $hierarchy.Name -cne 'hierarchy' -or $hierarchy.NamespaceURI -cne '' -or
        $hierarchy.GetAttribute('run-id') -cne $RunId -or $hierarchy.GetAttribute('capture-id') -cne $CaptureId -or
        $roots.Count -ne 1 -or $roots[0].GetAttribute('package') -cne $Package -or
        $roots[0].GetAttribute('visible-to-user') -cne 'true' -or
        $hierarchy.GetAttribute('uptime-ms') -cnotmatch '\A[1-9][0-9]{0,18}\z' -or
        -not [long]::TryParse($hierarchy.GetAttribute('uptime-ms'), [ref]$uptimeMs) -or
        $hierarchy.GetAttribute('window-id') -cnotmatch '\A(?:0|[1-9][0-9]{0,9})\z' -or
        -not [int]::TryParse($hierarchy.GetAttribute('window-id'), [ref]$windowId)) {
        throw 'The capture XML does not identify this run, capture and visible target window.'
    }
    return ,$document
}

function Get-WalkCapturePngSize {
    param([byte[]]$Bytes)
    if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PNG walkthrough proof requires PowerShell 7 (pwsh).' }
    $signature = [byte[]]@(137, 80, 78, 71, 13, 10, 26, 10)
    if ($Bytes.Length -lt 57) { throw 'The capture image is not a nonempty PNG.' }
    for ($index = 0; $index -lt $signature.Length; $index++) {
        if ($Bytes[$index] -ne $signature[$index]) { throw 'The capture image has no PNG signature.' }
    }
    if ([BitConverter]::ToString($Bytes, 8, 8) -cne '00-00-00-0D-49-48-44-52' -or
        [BitConverter]::ToString($Bytes, $Bytes.Length - 12, 12) -cne '00-00-00-00-49-45-4E-44-AE-42-60-82') {
        throw 'The capture image has no complete PNG header and end marker.'
    }
    $width = [long]$Bytes[16] * 16777216 + [long]$Bytes[17] * 65536 + [long]$Bytes[18] * 256 + $Bytes[19]
    $height = [long]$Bytes[20] * 16777216 + [long]$Bytes[21] * 65536 + [long]$Bytes[22] * 256 + $Bytes[23]
    if ($width -lt 1 -or $height -lt 1 -or $width -gt 16384 -or $height -gt 16384 -or $width * $height -gt 67108864) {
        throw 'The capture PNG dimensions are empty or exceed the screen capture bounds.'
    }
    # GDI+ accepts some damaged image streams. Verify every PNG chunk and the zlib payload too.
    if (-not ('HushGramWalkPngCrc32' -as [type])) {
        Add-Type -TypeDefinition @'
public static class HushGramWalkPngCrc32 {
    private static readonly uint[] Table = MakeTable();
    private static uint[] MakeTable() {
        var table = new uint[256];
        for (uint index = 0; index < table.Length; index++) {
            uint value = index;
            for (int bit = 0; bit < 8; bit++) value = (value >> 1) ^ ((value & 1) == 0 ? 0u : 0xedb88320u);
            table[index] = value;
        }
        return table;
    }
    public static uint Read(byte[] data, int offset) {
        return ((uint)data[offset] << 24) | ((uint)data[offset + 1] << 16) | ((uint)data[offset + 2] << 8) | data[offset + 3];
    }
    public static uint Compute(byte[] data, int offset, int length) {
        uint value = 0xffffffffu;
        for (int end = offset + length; offset < end; offset++) value = (value >> 8) ^ Table[(value ^ data[offset]) & 255];
        return value ^ 0xffffffffu;
    }
}
'@
    }
    $depth = [int]$Bytes[24]
    $color = [int]$Bytes[25]
    $channels = @{ 0 = 1; 2 = 3; 3 = 1; 4 = 2; 6 = 4 }[$color]
    $depths = if ($color -in @(0, 3)) { @(1, 2, 4, 8, 16) } else { @(8, 16) }
    if ($null -eq $channels -or $depth -notin $depths -or ($color -eq 3 -and $depth -eq 16) -or
        $Bytes[26] -ne 0 -or $Bytes[27] -ne 0 -or $Bytes[28] -notin @(0, 1)) {
        throw 'The capture PNG uses an invalid pixel format.'
    }
    $startX = @(0); $startY = @(0); $stepX = @(1); $stepY = @(1)
    if ($Bytes[28] -eq 1) { $startX = @(0, 4, 0, 2, 0, 1, 0); $startY = @(0, 0, 4, 0, 2, 0, 1); $stepX = @(8, 8, 4, 4, 2, 2, 1); $stepY = @(8, 8, 8, 4, 4, 2, 2) }
    $expectedInflated = 0L
    for ($pass = 0; $pass -lt $startX.Count; $pass++) {
        $passWidth = [Math]::Max(0, [Math]::Ceiling(($width - $startX[$pass]) / [double]$stepX[$pass]))
        $passHeight = [Math]::Max(0, [Math]::Ceiling(($height - $startY[$pass]) / [double]$stepY[$pass]))
        if ($passWidth -gt 0 -and $passHeight -gt 0) { $expectedInflated += [long]($passHeight * (1 + [Math]::Ceiling($passWidth * $channels * $depth / 8.0))) }
    }
    $compressed = [IO.MemoryStream]::new()
    $inflater = $null
    try {
        $offset = 8
        $chunks = 0
        while ($offset -lt $Bytes.Length) {
            if ($Bytes.Length - $offset -lt 12 -or ++$chunks -gt 10000) { throw 'The capture PNG has malformed or excessive chunks.' }
            $length = [HushGramWalkPngCrc32]::Read($Bytes, $offset)
            if ($length -gt $Bytes.Length - $offset - 12) { throw 'The capture PNG contains a truncated chunk.' }
            $chunk = [Text.Encoding]::ASCII.GetString($Bytes, $offset + 4, 4)
            if ($chunk -cnotmatch '\A[A-Za-z]{4}\z' -or
                [HushGramWalkPngCrc32]::Compute($Bytes, $offset + 4, [int]$length + 4) -ne [HushGramWalkPngCrc32]::Read($Bytes, $offset + 8 + [int]$length)) {
                throw 'The capture PNG contains a damaged chunk.'
            }
            if ($chunk -ceq 'IHDR' -and $offset -ne 8) { throw 'The capture PNG repeats its header.' }
            if ($chunk -ceq 'IEND' -and ($length -ne 0 -or $offset + 12 -ne $Bytes.Length)) { throw 'The capture PNG ends before its last chunk.' }
            if ($chunk -ceq 'IDAT') { $compressed.Write($Bytes, $offset + 8, [int]$length) }
            $offset += 12 + [int]$length
        }
        if ($compressed.Length -eq 0) { throw 'The capture PNG contains no image data.' }
        $compressed.Position = 0
        $inflater = [IO.Compression.ZLibStream]::new($compressed, [IO.Compression.CompressionMode]::Decompress, $true)
        $buffer = [byte[]]::new(32768)
        $inflated = 0L
        while (($count = $inflater.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $inflated += $count
            if ($inflated -gt $expectedInflated) { throw 'The capture PNG expands beyond its pixel dimensions.' }
        }
        if ($inflated -ne $expectedInflated) { throw 'The capture PNG does not contain all its pixel rows.' }
    } catch { throw "The capture PNG image data is invalid: $($_.Exception.Message)" }
    finally {
        if ($null -ne $inflater) { $inflater.Dispose() }
        $compressed.Dispose()
    }
    Add-Type -AssemblyName System.Drawing
    $stream = [IO.MemoryStream]::new($Bytes, $false)
    $image = $null
    try {
        $image = [Drawing.Image]::FromStream($stream, $false, $true)
        if ($image.RawFormat.Guid -ne [Drawing.Imaging.ImageFormat]::Png.Guid -or $image.Width -ne $width -or $image.Height -ne $height) {
            throw 'The capture PNG decoder disagrees with its header.'
        }
    } catch { throw "The capture PNG cannot be decoded: $($_.Exception.Message)" }
    finally {
        if ($null -ne $image) { $image.Dispose() }
        $stream.Dispose()
    }
    [pscustomobject]@{ Width = [int]$width; Height = [int]$height }
}

function Invoke-WalkCapture {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][string]$CaptureId,
        [Parameter(Mandatory = $true)][string]$Package,
        [Parameter(Mandatory = $true)][string]$OutDir,
        [Parameter(Mandatory = $true)]$DeviceLease,
        [scriptblock]$AdbInvoker
    )
    Assert-WalkCaptureIdentity -RunId $RunId -CaptureId $CaptureId -Package $Package
    Assert-HushgramDeviceLease -DeviceLease $DeviceLease -Serial $Serial
    $outputRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)
    $outputDirectory = Join-Path $outputRoot $RunId
    [void][IO.Directory]::CreateDirectory($outputDirectory)
    $instrumentationPath = Join-Path $outputDirectory "$CaptureId-instrumentation.txt"
    $diagnosticStream = [IO.File]::Open($instrumentationPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $startedUtc = [DateTime]::UtcNow.ToString('o')
    $instrumented = $null
    $invocationFailure = $null
    try {
        $instrumented = Invoke-HushgramAdbCommand -Adb $Adb -Arguments @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'run_id', $RunId, '-e', 'capture_id', $CaptureId, '-e', 'expected_package', $Package, $WalkCaptureInstrumentation) `
            -DeviceLease $DeviceLease -Invoker $AdbInvoker
    } catch { $invocationFailure = $_ }
    finally {
        $finishedUtc = [DateTime]::UtcNow.ToString('o')
        try {
            # Output is the lease wrapper's stdout followed by stderr. Capture it before parsing
            # and before any base64 file reads. At most 16K UTF-16 characters keep this below 64KB.
            $transcript = if ($null -ne $instrumented) { @($instrumented.Output) -join "`n" } else { $invocationFailure.Exception.Message }
            if ($transcript.Length -gt 16KB) { $transcript = $transcript.Substring(0, 8KB) + "`n[Instrumentation output truncated]`n" + $transcript.Substring($transcript.Length - 8KB) }
            $exitCodeText = if ($null -ne $instrumented) { [string]$instrumented.ExitCode } else { 'unavailable' }
            $diagnostic = "RunId=$RunId`nCaptureId=$CaptureId`nStartedUtc=$startedUtc`nFinishedUtc=$finishedUtc`nADB exit code=$exitCodeText`nInstrumentation stdout/stderr:`n$transcript`n"
            $diagnosticBytes = [Text.UTF8Encoding]::new($false).GetBytes($diagnostic)
            $diagnosticStream.Write($diagnosticBytes, 0, $diagnosticBytes.Length)
            $diagnosticStream.Flush($true)
        } finally { $diagnosticStream.Dispose() }
    }
    if ($null -ne $invocationFailure) { throw "The capture instrumentation invocation failed. Diagnostics: $instrumentationPath. Reason: $($invocationFailure.Exception.Message)" }
    try { $parsed = ConvertFrom-InstrumentOutput -Lines $instrumented.Output }
    catch { throw "The capture instrumentation output could not be parsed. Diagnostics: $instrumentationPath. Reason: $($_.Exception.Message)" }
    $resultCode = if ($null -ne $parsed.Code) { [string]$parsed.Code } else { 'missing' }
    $reason = @($parsed.Results['reason'], $parsed.Results['shortMsg'], $parsed.Failed) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $reasonText = if (@($reason).Count -gt 0) { (@($reason) -join ', ') -replace '[\r\n\t]+', ' ' } else { 'not reported' }
    if ($reasonText.Length -gt 512) { $reasonText = $reasonText.Substring(0, 512) + ' [truncated]' }
    $failureDetails = "ADB exit $($instrumented.ExitCode), result code $resultCode, reason $reasonText. Diagnostics: $instrumentationPath"
    $resultKeys = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $codes = 0
    foreach ($line in $instrumented.Output) {
        $pair = [regex]::Match($line, '\AINSTRUMENTATION_RESULT: ([^=]+)=')
        if ($pair.Success -and -not $resultKeys.Add($pair.Groups[1].Value)) { throw "The capture instrumentation repeated a result field. $failureDetails" }
        if ($line -cmatch '\AINSTRUMENTATION_CODE: ') { $codes++ }
    }
    $uptimeMs = 0L
    if ($instrumented.ExitCode -ne 0 -or $codes -ne 1 -or $parsed.Code -ne -1 -or $null -ne $parsed.Failed -or $parsed.Results.status -cne 'captured' -or
        $parsed.Results.run_id -cne $RunId -or $parsed.Results.capture_id -cne $CaptureId -or $parsed.Results.expected_package -cne $Package -or
        $parsed.Results.uptime_ms -cnotmatch '\A[1-9][0-9]{0,18}\z' -or
        -not [long]::TryParse($parsed.Results.uptime_ms, [ref]$uptimeMs)) {
        throw "The instrumentation did not explicitly capture this run, capture and target package successfully. $failureDetails"
    }
    $relativeRoot = "files/walk-captures/$RunId/$CaptureId"
    $relative = [ordered]@{ Ui = "$relativeRoot.xml"; AfterUi = "$relativeRoot-after.xml"; Image = "$relativeRoot.png" }
    $reported = @{ Ui = 'ui_path'; AfterUi = 'after_ui_path'; Image = 'image_path' }
    $dataRoot = $null
    foreach ($kind in $relative.Keys) {
        $reportedPath = [string]$parsed.Results[$reported[$kind]]
        $pathMatch = [regex]::Match($reportedPath, '\A(/data/(?:user/[0-9]+|data)/app\.hushgram\.walkcapture/)' + [regex]::Escape($relative[$kind]) + '\z')
        if (-not $pathMatch.Success -or ($null -ne $dataRoot -and $dataRoot -cne $pathMatch.Groups[1].Value)) {
            throw 'The instrumentation reported a path outside this capture app and run.'
        }
        $dataRoot = $pathMatch.Groups[1].Value
    }
    $payloads = [ordered]@{}
    foreach ($kind in $relative.Keys) {
        $limit = if ($kind -ceq 'Image') { 64MB } else { 16MB }
        $payloads[$kind] = Read-WalkCapturePayload -Adb $Adb -Serial $Serial -RelativePath $relative[$kind] -MaxBytes $limit `
            -DeviceLease $DeviceLease -AdbInvoker $AdbInvoker
    }
    $ui = ConvertFrom-WalkCaptureXml -Bytes $payloads.Ui.Bytes -RunId $RunId -CaptureId $CaptureId -Package $Package
    $afterUi = ConvertFrom-WalkCaptureXml -Bytes $payloads.AfterUi.Bytes -RunId $RunId -CaptureId $CaptureId -Package $Package
    if ($ui.DocumentElement.GetAttribute('uptime-ms') -cne $parsed.Results.uptime_ms -or
        [long]$afterUi.DocumentElement.GetAttribute('uptime-ms') -lt $uptimeMs -or
        $ui.DocumentElement.GetAttribute('window-id') -cne $afterUi.DocumentElement.GetAttribute('window-id')) {
        throw 'The captured window identity changed around the screenshot.'
    }
    $captureDuration = ([DateTimeOffset]::Parse($finishedUtc)-[DateTimeOffset]::Parse($startedUtc)).TotalMilliseconds
    # Uptime cannot grow beyond the whole instrumentation interval. Allow two seconds for
    # host scheduling/clock precision, without accepting a stale or contradictory device clock.
    if ($captureDuration -lt 0 -or [decimal]$afterUi.DocumentElement.GetAttribute('uptime-ms')-$uptimeMs -gt $captureDuration+2000) {
        throw 'The captured device uptime contradicts the instrumentation interval.'
    }
    $size = Get-WalkCapturePngSize -Bytes $payloads.Image.Bytes
    $paths = [ordered]@{ Ui = (Join-Path $outputDirectory "$CaptureId.xml"); AfterUi = (Join-Path $outputDirectory "$CaptureId-after.xml"); Image = (Join-Path $outputDirectory "$CaptureId.png") }
    $created = [Collections.Generic.List[string]]::new()
    try {
        foreach ($kind in $paths.Keys) {
            $stream = [IO.File]::Open($paths[$kind], [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
            $created.Add($paths[$kind])
            try { $stream.Write($payloads[$kind].Bytes, 0, $payloads[$kind].Bytes.Length); $stream.Flush($true) }
            finally { $stream.Dispose() }
            if ((Get-FileHash -LiteralPath $paths[$kind] -Algorithm SHA256).Hash.ToLowerInvariant() -cne $payloads[$kind].Sha256) {
                throw 'A saved capture differs from the verified device bytes.'
            }
        }
    } catch {
        foreach ($createdPath in $created) { [IO.File]::Delete($createdPath) }
        throw
    }
    [pscustomobject]@{
        RunId = $RunId; CaptureId = $CaptureId; Package = $Package
        InstrumentationPath = $instrumentationPath
        UiPath = $paths.Ui; AfterUiPath = $paths.AfterUi; ImagePath = $paths.Image
        UiSha256 = $payloads.Ui.Sha256; AfterUiSha256 = $payloads.AfterUi.Sha256; ImageSha256 = $payloads.Image.Sha256
        StartedUtc = $startedUtc; FinishedUtc = $finishedUtc; UptimeMs = $uptimeMs
        Width = $size.Width; Height = $size.Height; ParsedXml = $ui; AfterParsedXml = $afterUi
    }
}
