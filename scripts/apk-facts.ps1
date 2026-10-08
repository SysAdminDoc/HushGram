<#
.SYNOPSIS
    What an APK's manifest says, read with aapt2, and how patching changed it.
.DESCRIPTION
    Taken from Hushfacebook's release-receipt.ps1: the aapt2 lookup, the manifest reader and the
    manifest delta with its allowlist. verify-all-patches.ps1 and patch-for-device.ps1 load it.

.NOTES
    Taken from Hushfacebook's scripts/release-receipt.ps1
    (https://github.com/SysAdminDoc/Hushfacebook, commit c15d4f7930505824789684d35039d3c78c8b0903).
    GPL-3.0-only. Modified for HushGram (Instagram), 2026.
#>

function Resolve-Aapt2 {
    <#
    .SYNOPSIS
        The aapt2 that reads a binary AndroidManifest.xml, or a throw saying where to get one.
    .DESCRIPTION
        Taken in order from -Explicit, HUSHGRAM_AAPT2, and the newest build-tools directory of
        the SDK named in local.properties or ANDROID_HOME. Newest by version number, not by text:
        sorting build-tools as strings puts 9.0.0 above 37.0.0.
    #>
    param([string]$Explicit, [string]$Root)

    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit -PathType Leaf)) {
            throw "No aapt2 at the path given: $Explicit"
        }
        return $Explicit
    }
    if ($env:HUSHGRAM_AAPT2 -and (Test-Path -LiteralPath $env:HUSHGRAM_AAPT2 -PathType Leaf)) {
        return $env:HUSHGRAM_AAPT2
    }

    $sdk = $env:ANDROID_HOME
    $localProperties = if ($Root) { Join-Path $Root 'local.properties' } else { $null }
    if ($localProperties -and (Test-Path -LiteralPath $localProperties -PathType Leaf)) {
        $line = @(Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' }) | Select-Object -First 1
        if ($line) { $sdk = ($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\\\', '\' }
    }
    if (-not $sdk) { throw 'No Android SDK: set ANDROID_HOME, sdk.dir or HUSHGRAM_AAPT2.' }

    $buildTools = Join-Path $sdk 'build-tools'
    if (-not (Test-Path -LiteralPath $buildTools -PathType Container)) {
        throw "The SDK has no build-tools directory: $buildTools"
    }
    $candidate = Get-ChildItem -LiteralPath $buildTools -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
    if (-not $candidate) { throw "No versioned build-tools under $buildTools." }

    $aapt2 = Join-Path $candidate.FullName 'aapt2.exe'
    if (-not (Test-Path -LiteralPath $aapt2 -PathType Leaf)) {
        $aapt2 = Join-Path $candidate.FullName 'aapt2'
    }
    if (-not (Test-Path -LiteralPath $aapt2 -PathType Leaf)) {
        throw "build-tools $($candidate.Name) carries no aapt2."
    }
    return $aapt2
}

function ConvertFrom-XmlTreeValue {
    <#
    .SYNOPSIS
        The value of one aapt2 xmltree attribute, without its decoration.
    .DESCRIPTION
        aapt2 prints a string as `="text" (Raw: "text")` and everything else bare. The quoted
        half is taken when it is there, because the raw half repeats it and an attribute whose
        value contains a quote would otherwise be cut at the wrong place.
    #>
    param([string]$Text)

    if ($null -eq $Text) { return $null }
    $value = $Text.Trim()
    $rawAt = $value.IndexOf(' (Raw: ')
    if ($rawAt -ge 0) { $value = $value.Substring(0, $rawAt).Trim() }
    if ($value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')) {
        return $value.Substring(1, $value.Length - 2)
    }
    return $value
}

function Get-ApkManifestFacts {
    <#
    .SYNOPSIS
        Package identity, requested permissions and exported components, read off an APK.
    .DESCRIPTION
        Components count as exported only when the manifest says so. Every target this project
        patches is above API 31, where an intent filter without an explicit android:exported is
        refused at install time, so there is no implicit case left to infer.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Aapt2
    )

    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }

    $dump = @(& $Aapt2 dump xmltree --file AndroidManifest.xml $Apk 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "aapt2 could not read the manifest of ${Apk}: $($dump -join ' ')"
    }
    return ConvertFrom-ManifestXmlTree -Lines $dump -Source (Split-Path -Leaf $Apk)
}

function ConvertFrom-ManifestXmlTree {
    <#
    .SYNOPSIS
        The same reading, from lines already in hand, so the parser can be tested without an APK.
    #>
    param([string[]]$Lines, [string]$Source)

    $componentElements = @('activity', 'activity-alias', 'service', 'receiver', 'provider')
    $packageName = $null
    $versionName = $null
    $versionCode = $null
    $permissions = New-Object System.Collections.Generic.List[string]
    $exported = New-Object System.Collections.Generic.List[string]

    $element = $null
    $componentName = $null
    $componentExported = $false

    function Complete-Component {
        param($Name, $Kind, $IsExported, $List)
        if ($Kind -and $IsExported -and $Name) { $List.Add("${Kind}:${Name}") }
    }

    foreach ($raw in $Lines) {
        $line = [string]$raw
        $elementMatch = [regex]::Match($line, '^\s*E:\s*([A-Za-z0-9_\-]+)\s*\(line=')
        if ($elementMatch.Success) {
            Complete-Component -Name $componentName -Kind $element -IsExported $componentExported -List $exported
            $element = $elementMatch.Groups[1].Value
            $componentName = $null
            $componentExported = $false
            continue
        }

        $attributeMatch = [regex]::Match($line,
            '^\s*A:\s*(?:http://schemas\.android\.com/apk/res/android:)?([A-Za-z0-9_\-]+)(?:\(0x[0-9a-fA-F]+\))?=(.*)$')
        if (-not $attributeMatch.Success) { continue }
        $name = $attributeMatch.Groups[1].Value
        $value = ConvertFrom-XmlTreeValue -Text $attributeMatch.Groups[2].Value

        switch ($element) {
            'manifest' {
                if ($name -eq 'package') { $packageName = $value }
                elseif ($name -eq 'versionName') { $versionName = $value }
                elseif ($name -eq 'versionCode') { $versionCode = $value }
            }
            'uses-permission' {
                if ($name -eq 'name' -and $value) { $permissions.Add($value) }
            }
            'uses-permission-sdk-23' {
                if ($name -eq 'name' -and $value) { $permissions.Add($value) }
            }
            default {
                if ($componentElements -contains $element) {
                    if ($name -eq 'name') { $componentName = $value }
                    elseif ($name -eq 'exported') { $componentExported = ($value -eq 'true') }
                }
            }
        }
    }
    Complete-Component -Name $componentName -Kind $element -IsExported $componentExported -List $exported

    if (-not $packageName) { throw "No package name in the manifest of $Source." }

    # A component written as .Name is inside the package, and the two APKs have to spell the
    # same component the same way or every one of them reads as both added and removed.
    $qualified = New-Object System.Collections.Generic.List[string]
    foreach ($entry in $exported) {
        $kind, $name = $entry -split ':', 2
        if ($name.StartsWith('.')) { $name = $packageName + $name }
        elseif ($name -notmatch '\.') { $name = $packageName + '.' + $name }
        $qualified.Add("${kind}:${name}")
    }

    return [pscustomobject]@{
        package     = $packageName
        versionName = $versionName
        versionCode = $versionCode
        permissions = @($permissions | Sort-Object -Unique -CaseSensitive)
        exported    = @($qualified | Sort-Object -Unique -CaseSensitive)
    }
}

function Get-ManifestDelta {
    <#
    .SYNOPSIS
        What patching did to the manifest, as five sorted lists.
    .DESCRIPTION
        versionCodeChanged holds the patched build's version code when patching changed it (Change
        version code raises it), and nothing otherwise.
    #>
    param([Parameter(Mandatory = $true)]$Stock, [Parameter(Mandatory = $true)]$Patched)

    return [pscustomobject]@{
        permissionsAdded          = @(Compare-Sets -Left $Patched.permissions -Right $Stock.permissions)
        permissionsRemoved        = @(Compare-Sets -Left $Stock.permissions -Right $Patched.permissions)
        exportedComponentsAdded   = @(Compare-Sets -Left $Patched.exported -Right $Stock.exported)
        exportedComponentsRemoved = @(Compare-Sets -Left $Stock.exported -Right $Patched.exported)
        versionCodeChanged        = @(if ([string]$Patched.versionCode -cne [string]$Stock.versionCode) { [string]$Patched.versionCode })
    }
}

function Compare-Sets {
    <#
    .SYNOPSIS
        Everything in Left that Right does not have, sorted.
    #>
    param([string[]]$Left, [string[]]$Right)

    $other = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]@($Right), [System.StringComparer]::Ordinal)
    return @(@($Left) | Where-Object { -not $other.Contains($_) } | Sort-Object -Unique -CaseSensitive)
}

function ConvertTo-ManifestDeltaEntries {
    <#
    .SYNOPSIS
        One delta as the flat `kind value` lines the allowlist is written in.
    #>
    param([Parameter(Mandatory = $true)]$Delta)

    $entries = New-Object System.Collections.Generic.List[string]
    foreach ($value in @($Delta.permissionsAdded)) { $entries.Add("permission-added $value") }
    foreach ($value in @($Delta.permissionsRemoved)) { $entries.Add("permission-removed $value") }
    foreach ($value in @($Delta.exportedComponentsAdded)) { $entries.Add("exported-added $value") }
    foreach ($value in @($Delta.exportedComponentsRemoved)) { $entries.Add("exported-removed $value") }
    # A receipt written before the version code was part of the delta has no such field.
    foreach ($value in @($Delta.versionCodeChanged | Where-Object { $_ })) { $entries.Add("version-code $value") }
    return @($entries | Sort-Object -Unique -CaseSensitive)
}

function Read-ManifestDeltaAllowlist {
    <#
    .SYNOPSIS
        The reviewed manifest changes. Blank lines and # comments are ignored.
    #>
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "The manifest delta allowlist is missing: $Path"
    }
    return ConvertFrom-ManifestDeltaAllowlist -Lines @(Get-Content -LiteralPath $Path)
}

function ConvertFrom-ManifestDeltaAllowlist {
    <#
    .SYNOPSIS
        The reviewed manifest changes out of an allowlist's lines, wherever they were read from.
    .DESCRIPTION
        -Source says where, for a failure: " at <commit>" for one read out of git.
    #>
    param([AllowEmptyCollection()][string[]]$Lines = @(), [string]$Source = '')

    $entries = New-Object System.Collections.Generic.List[string]
    foreach ($line in @($Lines)) {
        # A byte order mark is read as text when the lines come out of git rather than Get-Content.
        $text = ([string]$line).TrimStart([char]0xFEFF).Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        if ($text -notmatch '^(permission-added|permission-removed|exported-added|exported-removed|version-code) \S+$') {
            throw "The manifest delta allowlist$Source has a line that is not `"<kind> <value>`": $text"
        }
        $entries.Add($text)
    }
    # An allowlist with no entries comes back as $null, not an empty array: a PowerShell function
    # returning @() hands back nothing. Comma wrapping it would fix that and break every
    # `@(Read-ManifestDeltaAllowlist ...)` call site instead, which would then see one array
    # inside an array. verify-all-patches.ps1 drops the null on the way in.
    return @($entries | Sort-Object -Unique -CaseSensitive)
}

