<#
.SYNOPSIS
    The canonical production inputs behind the identity embedded in the bundle.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
#>

function Get-BuildIdentityHash {
    param([string]$Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return -join ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)) | ForEach-Object { $_.ToString('x2') }) }
    finally { $sha.Dispose() }
}

function Get-MppBuildIdentity {
    <# The hg1 identity in a bundle's manifest, or $null when the bundle has none. #>
    param([Parameter(Mandatory = $true)][string]$Path)
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $zip.GetEntry('META-INF/MANIFEST.MF')
        if (-not $entry) { return $null }
        $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.UTF8Encoding]::new($false))
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally {
        $zip.Dispose()
    }
    # Unfold the manifest's continuation lines (a CRLF and one leading space), then read the entry.
    $unfolded = $text -replace "\r\n ", ''
    $match = [regex]::Match($unfolded, '(?m)^HushGram-Build-Identity:\s*(hg1:[0-9a-f]{64})\s*$')
    if ($match.Success) { return $match.Groups[1].Value }
    return $null
}

function Assert-CanonicalBuildIdentity {
    param($Identity)
    if ($null -eq $Identity -or ($Identity.schemaVersion -isnot [int] -and $Identity.schemaVersion -isnot [long]) -or $Identity.schemaVersion -ne 1) {
        throw 'The canonical build identity has no supported schema.'
    }
    $expected = @('schemaVersion', 'sourceSha256', 'catalogSha256', 'toolchainSha256', 'id')
    if (@($Identity.PSObject.Properties.Name).Count -ne $expected.Count -or
        @($Identity.PSObject.Properties.Name | Where-Object { $_ -cnotin $expected }).Count) {
        throw 'The canonical build identity contains unsupported fields.'
    }
    $text = "hushgram-production-inputs-v1`n"
    foreach ($category in @('source', 'catalog', 'toolchain')) {
        $value = $Identity.($category + 'Sha256')
        if ($value -isnot [string] -or $value -cnotmatch '^[0-9a-f]{64}$') {
            throw "The canonical build identity has no $category SHA-256."
        }
        $text += "${category}:$value`n"
    }
    if ($Identity.id -isnot [string] -or $Identity.id -cne ('hg1:' + (Get-BuildIdentityHash $text))) {
        throw 'The canonical build identity does not match its input digests.'
    }
}

function Test-CanonicalInputSelection {
    param([string]$Name, [string]$Kind, [string]$Path)
    switch ($Kind) {
        file { return $Name -ceq $Path }
        tree { return $Name.StartsWith($Path + '/', [StringComparison]::Ordinal) }
        production { return $Name.StartsWith($Path + '/', [StringComparison]::Ordinal) -and
            $Name.Substring($Path.Length + 1) -cmatch '^(?:(?!src/)[^/]+/)+src/(main|release)/.+$' }
        module { return $Name.StartsWith($Path + '/', [StringComparison]::Ordinal) -and
            -not $Name.Contains('/src/') -and $Name -cmatch '/build\.gradle(\.kts)?$' }
    }
    return $false
}

function Assert-NoIgnoredCanonicalProductionInputs {
    param([Parameter(Mandatory = $true)][string]$Root)
    # Git's normal untracked list hides these, but compilers and Android packaging don't.
    # Pathspecs avoid enumerating ordinary build output; the first source set excludes fixtures.
    $ignored = @(Invoke-RepoGit -Root $Root -Arguments @('-c', 'core.quotepath=false', 'ls-files',
        '--others', '--ignored', '--exclude-standard', '--', ':(glob)**/src/main/**', ':(glob)**/src/release/**'))
    $candidates = @($ignored | Where-Object {
        $_ -cmatch '^(?<module>(?:(?!src/)[^/]+/)+)src/(main|release)/(AndroidManifest\.xml|(?:java|kotlin)/.+\.(?:java|kt)|(?:res|resources|assets|aidl|rs|shaders|jni|jniLibs)/.+|l10n/.+\.tsv)$' -and
            $Matches.module -notmatch '(^|/)(build|\.gradle)(/|$)'
    })
    if (-not $candidates.Count) { return }
    $policy = Join-Path $Root 'scripts/canonical-build-inputs.txt'
    if (-not (Test-Path -LiteralPath $policy -PathType Leaf)) { throw 'The canonical input boundary is missing.' }
    foreach ($rule in Get-Content -LiteralPath $policy | Where-Object { $_ -notmatch '^\s*(#|$)' }) {
        if ($rule -cnotmatch '^(source|catalog|toolchain) (file|tree|production|module) ([A-Za-z0-9_./-]+)$') {
            throw 'The canonical input boundary has a malformed rule.'
        }
        $category, $kind, $path = $Matches[1], $Matches[2], $Matches[3]
        if ($category -cne 'source') { continue }
        foreach ($candidate in $candidates) {
            if (Test-CanonicalInputSelection -Name $candidate -Kind $kind -Path $path) {
                throw "An ignored canonical production input must be tracked before building or auditing: $candidate"
            }
        }
    }
}

function Get-CanonicalBuildIdentity {
    param([Parameter(Mandatory = $true)][string]$Root)
    $names = @(Invoke-RepoGit -Root $Root -Arguments @('-c', 'core.quotepath=false', 'ls-files', '--cached') |
        Where-Object { $_ })
    if (-not $names.Count) { throw 'Cannot enumerate canonical tracked build inputs.' }
    $names = [string[]]$names
    [Array]::Sort($names, [StringComparer]::Ordinal)
    $untracked = @(Invoke-RepoGit -Root $Root -Arguments @('-c', 'core.quotepath=false', 'ls-files', '--others', '--exclude-standard') |
        Where-Object { $_ })
    $policyPath = 'scripts/canonical-build-inputs.txt'
    if ($names -cnotcontains $policyPath) { throw 'The canonical input boundary is not tracked.' }
    Assert-NoIgnoredCanonicalProductionInputs -Root $Root
    $groups = [ordered]@{ source = [Collections.Generic.List[string]]::new()
        catalog = [Collections.Generic.List[string]]::new(); toolchain = [Collections.Generic.List[string]]::new() }
    $owners = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    $rules = @(Get-Content -LiteralPath (Join-Path $Root $policyPath) | Where-Object { $_ -notmatch '^\s*(#|$)' })
    foreach ($rule in $rules) {
        if ($rule -cnotmatch '^(source|catalog|toolchain) (file|tree|production|module) ([A-Za-z0-9_./-]+)$') {
            throw 'The canonical input boundary has a malformed rule.'
        }
        $category, $kind, $path = $Matches[1], $Matches[2], $Matches[3]
        if ($path -match '(^|/)\.\.(/|$)' -or $path.StartsWith('/') -or
            $path -match '(^|/)(build|\.gradle)(/|$)') {
            throw 'The canonical input boundary includes an output or escaping path.'
        }
        $matched = @($names | Where-Object { Test-CanonicalInputSelection -Name $_ -Kind $kind -Path $path })
        foreach ($candidate in $untracked) {
            $included = Test-CanonicalInputSelection -Name $candidate -Kind $kind -Path $path
            if ($included) { throw "An untracked canonical production input must be staged first: $candidate" }
        }
        if (-not $matched.Count) { throw "A required canonical input is missing: $category $kind $path" }
        foreach ($name in $matched) {
            if ($owners.ContainsKey($name) -and $owners[$name] -cne $category) {
                throw "The canonical input boundary gives $name two categories."
            }
            $owners[$name] = $category
        }
    }
    foreach ($category in $groups.Keys) {
        if ($category -cnotin $owners.Values) { throw "The canonical input boundary omits $category." }
    }
    foreach ($name in $names) {
        if (-not $owners.ContainsKey($name)) { continue }
        if ($name -match '[\r\n\t]' -or $name -match '(^|/)(build|\.gradle|src/debug|src/test|src/androidTest)(/|$)') {
            throw "A canonical input has an unsupported path: $name"
        }
        $file = Join-Path $Root $name
        if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or
            ((Get-Item -LiteralPath $file).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "A canonical build input is missing or linked: $name"
        }
        $digest = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
        $groups[$owners[$name]].Add("$name`t$digest`n")
    }
    $identity = [ordered]@{ schemaVersion = 1 }
    $joined = "hushgram-production-inputs-v1`n"
    foreach ($category in $groups.Keys) {
        $identity[$category + 'Sha256'] = Get-BuildIdentityHash ($groups[$category] -join '')
        $joined += "${category}:$($identity[$category + 'Sha256'])`n"
    }
    $identity.id = 'hg1:' + (Get-BuildIdentityHash $joined)
    Assert-CanonicalBuildIdentity ([pscustomobject]$identity)
    return [pscustomobject]$identity
}

function Read-SbomCanonicalBuildIdentity {
    param([Parameter(Mandatory = $true)][string]$Path)
    $document = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    $properties = @($document.metadata.properties | Where-Object { $_.name -ceq 'hushgram:canonical-build-identity' })
    if ($properties.Count -ne 1 -or $properties[0].value -isnot [string]) {
        throw 'The SBOM has no unique canonical production build identity.'
    }
    $identity = $properties[0].value | ConvertFrom-Json
    Assert-CanonicalBuildIdentity $identity
    return $identity
}
