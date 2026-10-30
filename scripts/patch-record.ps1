<#
.SYNOPSIS
    The record that ties a patch run's result.json to the APK the run produced.

.DESCRIPTION
    Dot-sourced by patch-for-device.ps1, which writes patch-record.json next to its result, and by
    native-runner-device.ps1, which takes a patch selection from a result only through it. The
    CLI's result names the package and version, never the file, so without the record a result
    from another run of the same version would vouch for this APK.

    The record keys on the APK's content: every entry except the signing files, each by name, size
    and bytes. Signing the APK again with another key keeps that content, and any other patch
    selection changes it.

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
#>

. (Join-Path $PSScriptRoot 'build-identity.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')

function Read-PatchJsonObject {
    param([string]$Path)
    if ($PSVersionTable.PSVersion -lt [version]'7.5') { throw 'Strict artifact JSON verification requires PowerShell 7.5 or newer.' }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or (Get-Item -LiteralPath $Path).Length -gt 16MB -or
        ((Get-Item -LiteralPath $Path).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "The JSON document is missing, linked or too large: $Path" }
    $text = Get-Content -LiteralPath $Path -Raw
    if ($text -isnot [string] -or $text -cnotmatch '\A\s*\{') { throw 'The JSON root must be one object.' }
    function Assert-PatchJsonKey($Element) {
        if ($Element.ValueKind -eq [Text.Json.JsonValueKind]::Object) {
            $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
            foreach ($property in $Element.EnumerateObject()) {
                if (-not $names.Add($property.Name)) { throw 'The JSON document repeats an object field.' }
                Assert-PatchJsonKey $property.Value
            }
        } elseif ($Element.ValueKind -eq [Text.Json.JsonValueKind]::Array) {
            foreach ($entry in $Element.EnumerateArray()) { Assert-PatchJsonKey $entry }
        }
    }
    $document = [Text.Json.JsonDocument]::Parse($text)
    try { Assert-PatchJsonKey $document.RootElement } finally { $document.Dispose() }
    ConvertFrom-Json -InputObject $text -DateKind String
}

function Get-ApkContentSha256 {
    <#
    .SYNOPSIS
        One SHA-256 over every entry of the APK outside META-INF/ and the source stamp, in
        ordinal name order, each as its name, its size and its uncompressed bytes.
    #>
    param([string]$Apk)
    $Apk = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Apk)
    $hash = [Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
    try {
        # apksigner rewrites META-INF/ and drops stamp-cert-sha256 when it signs again.
        [string[]]$names = @($zip.Entries | ForEach-Object FullName |
            Where-Object { -not $_.StartsWith('META-INF/', [StringComparison]::Ordinal) -and $_ -cne 'stamp-cert-sha256' -and -not $_.EndsWith('/') })
        if ($names.Count -eq 0) { throw "$(Split-Path -Leaf $Apk) has no entries outside its signing files." }
        if (@($names | Select-Object -Unique).Count -ne $names.Count) { throw "$(Split-Path -Leaf $Apk) names an entry twice." }
        [Array]::Sort($names, [StringComparer]::Ordinal)
        $buffer = [byte[]]::new(1 -shl 16)
        foreach ($name in $names) {
            $entry = $zip.GetEntry($name)
            $header = [Text.Encoding]::UTF8.GetBytes("$name`n$($entry.Length)`n")
            $hash.AppendData($header)
            $stream = $entry.Open()
            try {
                while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) { $hash.AppendData($buffer, 0, $read) }
            } finally { $stream.Dispose() }
        }
        ([BitConverter]::ToString($hash.GetHashAndReset())).Replace('-', '').ToLowerInvariant()
    } finally {
        $hash.Dispose()
        $zip.Dispose()
    }
}

function Write-PatchRecord {
    param([string]$Apk, [string]$Result, [string]$Path)
    $Path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    $record = [ordered]@{
        schemaVersion = 1
        resultSha256 = (Get-FileHash -LiteralPath $Result -Algorithm SHA256).Hash.ToLowerInvariant()
        contentSha256 = Get-ApkContentSha256 -Apk $Apk
    }
    [IO.File]::WriteAllText($Path, ($record | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
}

function Test-PatchRecord {
    <#
    .SYNOPSIS
        Why the record doesn't tie this result to this APK, or $null when it does.
    #>
    param([string]$Apk, [string]$Result, [string]$Record)
    if (-not (Test-Path -LiteralPath $Record -PathType Leaf)) { return "there is no patch record at $Record" }
    # ConvertFrom-PatchJson comes from patch-report.ps1, which every loader of this file loads first.
    try { $stamp = ConvertFrom-PatchJson (Get-Content -LiteralPath $Record -Raw) }
    catch { return "the patch record at $Record doesn't parse ($($_.Exception.Message))" }
    if ($stamp.schemaVersion -ne 1) { return "the patch record at $Record has schema $($stamp.schemaVersion), not 1" }
    if ([string]$stamp.resultSha256 -cne (Get-FileHash -LiteralPath $Result -Algorithm SHA256).Hash.ToLowerInvariant()) {
        return 'the patch result is not the one the record was written with'
    }
    if ([string]$stamp.contentSha256 -cne (Get-ApkContentSha256 -Apk $Apk)) {
        return "$(Split-Path -Leaf $Apk) is not the APK that patch run produced"
    }
    $null
}

# Requires build-identity.ps1, patch-target.ps1 and native-runner-device.ps1.

function Get-WalkBundleFacts {
    param([string]$Path)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($entry in $archive.Entries) {
            if (-not $names.Add($entry.FullName)) { throw 'The walk bundle repeats an archive entry.' }
        }
        $manifest = $archive.GetEntry('META-INF/MANIFEST.MF')
        if ($null -eq $manifest -or $manifest.Length -gt 65536) { throw 'The walk bundle has no bounded manifest.' }
        $reader = [IO.StreamReader]::new($manifest.Open(), [Text.UTF8Encoding]::new($false, $true))
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
    $main = (($text -replace "\r\n ", '') -split '\r?\n\r?\n', 2)[0]
    $attributes = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($line in @($main -split '\r?\n')) {
        if ($line -cnotmatch '^([A-Za-z0-9][A-Za-z0-9_-]*): (.*)$' -or $attributes.ContainsKey($Matches[1])) {
            throw 'The walk bundle manifest contains a malformed or duplicate attribute.'
        }
        $attributes.Add($Matches[1], $Matches[2])
    }
    if (-not $attributes.ContainsKey('HushGram-Build-Identity') -or
        $attributes['HushGram-Build-Identity'] -cnotmatch '^hg1:[0-9a-f]{64}$' -or
        -not $attributes.ContainsKey('Version') -or $attributes['Version'] -cnotmatch '^\d+\.\d+\.\d+$') {
        throw 'The walk bundle manifest has no valid source identity and version.'
    }
    $identity = Get-MppBuildIdentity -Path $Path
    if ($identity -cne $attributes['HushGram-Build-Identity']) { throw 'The walk bundle source identity is ambiguous.' }
    [pscustomobject]@{ buildIdentity = $identity; version = $attributes['Version'] }
}

function Get-WalkArtifactBinding {
    <# The exact local inputs a walk can vouch for. Recheck these hashes when the walk ends. #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$BundlePath,
        [Parameter(Mandatory = $true)][string]$CatalogPath,
        [Parameter(Mandatory = $true)][string]$SignedApk,
        [Parameter(Mandatory = $true)][string]$Report,
        [Parameter(Mandatory = $true)][string]$Record,
        [Parameter(Mandatory = $true)]$Tools
    )
    $files = [ordered]@{ bundleSha256 = $BundlePath; catalogSha256 = $CatalogPath
        apkSha256 = $SignedApk; reportSha256 = $Report; recordSha256 = $Record }
    $hashes = [ordered]@{}
    foreach ($key in @($files.Keys)) {
        $path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($files[$key])
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
            ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "A required walk artifact is missing or linked: $key."
        }
        $files[$key] = $path
        $hashes[$key] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hashes[$key] -cnotmatch '^[0-9a-f]{64}$') { throw "A walk artifact has no SHA-256: $key." }
    }
    $bundle = Get-WalkBundleFacts -Path $files.bundleSha256
    try {
        $catalog = Read-PatchJsonObject -Path $files.catalogSha256
        $result = Read-PatchJsonObject -Path $files.reportSha256
        $stamp = Read-PatchJsonObject -Path $files.recordSha256
    } catch { throw ('A walk catalog, patch report or patch record is not valid JSON: ' + $_.Exception.Message) }
    if ($null -eq $catalog -or $catalog.patches -isnot [array] -or $catalog.patches.Count -eq 0 -or
        $catalog.version -isnot [string] -or $catalog.version -cne ('v' + $bundle.version)) {
        throw 'The walk catalog has no patch array or does not match the bundle version.'
    }
    $known = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($patch in $catalog.patches) {
        if ($null -eq $patch -or $patch.name -isnot [string] -or [string]::IsNullOrWhiteSpace($patch.name) -or
            $patch.name -match '[\x00-\x1f\x7f]' -or -not $known.Add($patch.name)) {
            throw 'The walk catalog has a missing, malformed or duplicate patch name.'
        }
        if ($null -ne $patch.dependencies -and ($patch.dependencies -isnot [array] -or
            @($patch.dependencies | Where-Object { $_ -isnot [string] -or [string]::IsNullOrWhiteSpace($_) -or $_ -match '[\x00-\x1f\x7f]' }).Count)) {
            throw 'The walk catalog has a malformed dependency list.'
        }
    }
    $split = Split-SecondAppPatches -PatchList $catalog
    [string[]]$selected = @($split.SameApp)
    if ($selected.Count -eq 0) { throw 'The walk catalog has no public patches for the same app.' }
    [Array]::Sort($selected, [StringComparer]::Ordinal)
    [string[]]$dependencies = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $selected)
    $declared = Get-PatchTarget -PatchList $catalog
    if ($null -eq $result -or $result.packageName -isnot [string] -or $result.packageVersion -isnot [string] -or
        $result.appliedPatches -isnot [array] -or $result.failedPatches -isnot [array]) {
        throw 'The walk patch report has malformed target or selection fields.'
    }
    $stepKeys = @('stepResults', 'patchingSteps' | Where-Object { $null -ne $result.PSObject.Properties[$_] })
    if ($stepKeys.Count -ne 1 -or $result.($stepKeys[0]) -isnot [array] -or $result.($stepKeys[0]).Count -eq 0 -or
        @($result.($stepKeys[0]) | Where-Object { $null -eq $_ -or $_.success -isnot [bool] -or -not $_.success }).Count) {
        throw 'The walk patch report has no unique, successful patching step list.'
    }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $result.appliedPatches) {
        $name = if ($entry -is [string]) { $entry } elseif ($null -ne $entry -and $entry.name -is [string]) { $entry.name } else { $null }
        if ([string]::IsNullOrWhiteSpace($name) -or -not $seen.Add($name) -or $name -cin @($split.SecondApp) -or
            ($name -cnotin $selected -and $name -cnotin $dependencies)) {
            throw 'The walk patch selection is malformed, duplicated, unknown or contains a clone-only patch.'
        }
    }
    $checked = Test-PatchingReport -Report $result -ExpectedNames $selected -AllowedDependencyNames $dependencies `
        -OutputPath $files.apkSha256 -ExpectedPackageName $declared.PackageName -ExpectedPackageVersion $result.packageVersion
    if (-not $checked.Valid) { throw "The walk patch report cannot prove the full selection: $($checked.Reason)." }
    $recordKeys = @('schemaVersion', 'resultSha256', 'contentSha256')
    if ($null -eq $stamp -or ($stamp.schemaVersion -isnot [int] -and $stamp.schemaVersion -isnot [long]) -or
        $stamp.schemaVersion -ne 1 -or @($stamp.PSObject.Properties.Name).Count -ne $recordKeys.Count -or
        @($stamp.PSObject.Properties.Name | Where-Object { $_ -cnotin $recordKeys }).Count -or
        $stamp.resultSha256 -isnot [string] -or $stamp.resultSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        $stamp.contentSha256 -isnot [string] -or $stamp.contentSha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw 'The walk patch record has no supported, strict content binding.'
    }
    # The shared target reader calls Test-PatchRecord, reads the actual manifest and signer,
    # and extracts the source identity from the APK DEX rather than trusting the report.
    $target = Get-NativeRunnerTarget -Tools $Tools -Apk $files.apkSha256 -Report $files.reportSha256 -Record $files.recordSha256
    # Change version code writes Int.MAX_VALUE to the output manifest. That output code
    # cannot identify the original input build, whose codes the catalog declares.
    $outputBuildMatches = if ($selected -ccontains 'Change version code') {
        (@($declared.PackageVersions) -ccontains $target.VersionName) -and $target.VersionCode -ceq '2147483647'
    } else {
        Test-DeclaredBuild -Target $declared -VersionName $target.VersionName -VersionCode $target.VersionCode
    }
    if ($target.Package -cne $declared.PackageName -or -not $outputBuildMatches) {
        throw 'The walk APK is not an exact package and build declared by the catalog.'
    }
    if ($target.BuildIdentity -cne $bundle.buildIdentity) { throw 'The walk APK source identity does not match the actual bundle manifest.' }
    if ($target.Sha256 -cne $hashes.apkSha256 -or $target.SignerSha256 -isnot [string] -or
        $target.SignerSha256 -cnotmatch '^[0-9a-f]{64}$' -or $target.SelectionSha256 -isnot [string] -or
        $target.SelectionSha256 -cnotmatch '^[0-9a-f]{64}$' -or $target.VersionCode -cnotmatch '^[1-9][0-9]*$') {
        throw 'The walk APK has malformed or changing target facts.'
    }
    foreach ($key in $files.Keys) {
        if ((Get-FileHash -LiteralPath $files[$key] -Algorithm SHA256).Hash.ToLowerInvariant() -cne $hashes[$key]) {
            throw "A walk artifact changed while its binding was read: $key."
        }
    }
    [pscustomobject][ordered]@{
        schema = 1
        bundleSha256 = $hashes.bundleSha256; catalogSha256 = $hashes.catalogSha256; apkSha256 = $hashes.apkSha256
        reportSha256 = $hashes.reportSha256; recordSha256 = $hashes.recordSha256; apkContentSha256 = $stamp.contentSha256
        sourceBuildIdentity = $target.BuildIdentity; selectionSha256 = $target.SelectionSha256
        selectedPatches = [string[]]$selected
        target = [pscustomobject][ordered]@{ package = $target.Package; versionName = $target.VersionName
            versionCode = $target.VersionCode; signerSha256 = $target.SignerSha256 }
    }
}
