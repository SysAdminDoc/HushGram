<#
.SYNOPSIS
    The release provenance receipt: how it is built, and what makes one valid.

.DESCRIPTION
    Taken from Hushfacebook's scripts/release-receipt.ps1
    (https://github.com/SysAdminDoc/Hushfacebook, commit 814acd23d7b70d5d23abce6cb6c97767e16a051e),
    which came from Hushfeed (https://github.com/SysAdminDoc/hushfeed). GPL-3.0-only.
    Modified for HushGram (Instagram), 2026: the aapt2 lookup, the manifest reader and the
    manifest delta with its allowlist live in apk-facts.ps1 here, which this file dot-sources, the
    SBOM's first-party mark is HushGram's, and Morphe Manager's changelog scope is Instagram.
    Invoke-RepoGit's read of git's output follows Hushfacebook commit
    3716793a4c7f0cda3595b021cff12316aafea41f (Use-Utf8ConsoleOutput, in common.ps1).

    Dot-sourced by build-release-receipt.ps1 and validate-release-facts.ps1. A checksum on its
    own says a file has not changed since somebody hashed it. It cannot say which APK the patches
    were proved against, which commit built the bundle, which toolchain stamped it, or what the
    patches did to the Android manifest, and those are the facts that decide whether a local
    patch run is the one the release describes.

    Everything here is read back out of files rather than taken on trust: the APK hashes off the
    fixtures, the bundle hash and size off the .mpp, the patch verdicts out of the desktop CLI's
    own result report, and the manifest delta out of aapt2's reading of both APKs.

    The manifest delta is the part that needs a human. A patch that adds a permission or exports
    a component changes what the patched app can do and what other apps can reach, so every entry
    has to be written down in scripts/manifest-delta-allowlist.txt and stays until somebody takes
    it out. An entry nothing produces any more fails the run too: an allowlist that outlives its
    reason stops being a review.

    The SBOM :patches:releaseSbom writes beside the bundle says what the bundle carries: every
    library and the version it resolved to. The receipt records its hash, and the SBOM records the
    bundle's, so neither can be swapped for another build's without the pair coming apart.
#>

. (Join-Path $PSScriptRoot 'apk-facts.ps1')
. (Join-Path $PSScriptRoot 'patch-report.ps1')

. (Join-Path $PSScriptRoot 'build-identity.ps1')

function Get-ReleaseReceiptSchemaVersion {
    <#
    .SYNOPSIS
        Bumped when the shape changes in a way a reader has to know about.
    .DESCRIPTION
        Validation refuses a receipt written to a different version rather than guessing which
        fields moved. A function rather than a variable because this file is dot-sourced into
        several scripts, and a script-scoped variable in a dot-sourced file belongs to whichever
        one sourced it. Resolve-ReceiptSchema reads the number out of this function's body at an
        older commit, so it stays a bare return.

        2 added sbom: the file name, SHA-256 and component count of the release SBOM.
        3 added input-derived per-target coverage and exact-fixture coverage review.
        4 adds the canonical production identity and verifies its exact extension payload bytes.
    #>
    return 4
}

function Resolve-ReceiptSchema {
    <#
    .SYNOPSIS
        The schema a receipt should be held to: the one its own commit's builder wrote.
    .DESCRIPTION
        A receipt describes a release that has shipped, and one cut before schema 2 has no SBOM
        to name. Holding it to schema 2 would refuse every later push from the checkout that cut
        it, which is the trap Resolve-ReceiptToolchain describes for the patcher pin. So the
        number is read out of scripts/release-receipt.ps1 at the receipt's commit. On a release
        push the receipt's commit is the commit being released, whose builder writes this
        checkout's schema, so nothing is relaxed for a new release.

        A commit with no receipt script this can read is held to this checkout's schema, and one
        whose script writes a newer schema than this checkout reads throws. Answers
        @{ Version; Note }, where Note is a line worth printing or $null.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Commit
    )

    $current = Get-ReleaseReceiptSchemaVersion
    if ($Commit -notmatch '^[0-9a-f]{40}$') { return [pscustomobject]@{ Version = $current; Note = $null } }
    $script = (Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:scripts/release-receipt.ps1")) -join "`n"
    $written = [regex]::Match($script, '(?s)function\s+Get-ReleaseReceiptSchemaVersion\b.*?#>\s*return\s+(\d+)\s*\}')
    if (-not $written.Success) { return [pscustomobject]@{ Version = $current; Note = $null } }
    $version = [int]$written.Groups[1].Value
    $short = $Commit.Substring(0, 8)
    if ($version -gt $current) {
        throw "Commit $short writes receipt schema $version, newer than the $current this checkout reads."
    }
    if ($version -eq $current) { return [pscustomobject]@{ Version = $version; Note = $null } }
    return [pscustomobject]@{
        Version = $version
        Note = if ($version -lt 2) {
            "the receipt is held to schema $version, which its own commit $short wrote, so it names no SBOM and none is checked for its release"
        } else {
            "the receipt keeps historical schema $version from its own commit $short, without requiring newer metadata"
        }
    }
}

function ConvertTo-UtcStamp {
    <#
    .SYNOPSIS
        A date read out of JSON, as yyyy-MM-ddTHH:mm:ssZ in either shell.
    .DESCRIPTION
        PowerShell 7's ConvertFrom-Json turns an ISO 8601 string into a DateTime and Windows
        PowerShell leaves it a string, so a date is compared in this one form.
    #>
    param($Value)
    if ($Value -is [datetime]) {
        return $Value.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture)
    }
    return [string]$Value
}

function Get-SbomSha256 {
    <#
    .SYNOPSIS
        The SHA-256 a CycloneDX component records for itself, in lower case, or $null.
    #>
    param($Component, [string]$Label)

    $recorded = @(@($Component.hashes) | Where-Object { $null -ne $_ -and "$($_.alg)" -eq 'SHA-256' })
    if ($recorded.Count -gt 1) { throw "$Label records two SHA-256 hashes." }
    if ($recorded.Count -eq 0) { return $null }
    $value = "$($recorded[0].content)".ToLowerInvariant()
    if ($value -notmatch '^[0-9a-f]{64}$') { throw "$Label records a SHA-256 that isn't one: $($recorded[0].content)" }
    return $value
}

function Assert-SbomCarriedLicenses {
    param($Document, [switch]$RequireCurrent, [string]$LedgerPath)
    $policy = @($Document.metadata.properties | Where-Object { $_.name -ceq 'hushgram:license-policy' })
    if ($policy.Count -eq 0 -and -not $RequireCurrent) { return } # Historical published SBOM.
    if ($policy.Count -ne 1 -or $policy[0].value -cne 'reviewed-artifacts-v1') {
        throw 'The SBOM lacks the reviewed carried-library license policy.'
    }
    $stamp = @($Document.metadata.properties | Where-Object { $_.name -ceq 'hushgram:license-ledger' })
    if ($stamp.Count -ne 1 -or $stamp[0].value -cnotmatch '^[0-9a-f]{64}$') {
        throw 'The SBOM lacks one valid license ledger SHA-256.'
    }
    $approved = @()
    $reviewedInventory = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    if ($RequireCurrent) {
        if (-not (Test-Path -LiteralPath $LedgerPath -PathType Leaf)) { throw 'The reviewed license ledger is missing.' }
        if ($stamp[0].value -cne (Get-FileHash -LiteralPath $LedgerPath -Algorithm SHA256).Hash.ToLowerInvariant()) {
            throw 'The SBOM names another reviewed license ledger.'
        }
        $ledger = Get-Content -LiteralPath $LedgerPath -Raw | ConvertFrom-Json
        if ($ledger.schemaVersion -ne 1 -or @($ledger.artifacts).Count -eq 0) { throw 'The license ledger schema is invalid.' }
        $approved = @($ledger.artifacts)
    }
    foreach ($component in @($Document.components | Where-Object { $_.type -eq 'library' -and $_.purl })) {
        $choices = @($component.licenses | Where-Object { $null -ne $_ })
        if ($choices.Count -ne 1 -or $choices[0].license.id -cne 'Apache-2.0' -or
                $choices[0].license.url -cne 'https://www.apache.org/licenses/LICENSE-2.0.txt') {
            throw "The carried library $($component.purl) lacks its reviewed license."
        }
        $artifacts = @($component.properties | Where-Object { $_.name -ceq 'hushgram:artifact' })
        $evidence = @($component.properties | Where-Object { $_.name -ceq 'hushgram:license-evidence' })
        if ($artifacts.Count -eq 0 -or $evidence.Count -ne $artifacts.Count) {
            throw "The carried library $($component.purl) lacks exact artifact license evidence."
        }
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($item in $evidence) {
            try { $record = $item.value | ConvertFrom-Json } catch { throw 'The license evidence is not JSON.' }
            $pom = 'https://repo.maven.apache.org/maven2/' + $component.group.Replace('.', '/') + '/' +
                $component.name + '/' + $component.version + '/' + $component.name + '-' + $component.version + '.pom'
            $artifact = "$($record.file) sha256:$($record.sha256)"
            if ($record.purl -cne $component.purl -or $record.file -cnotmatch '^[A-Za-z0-9_.+\-]+\.jar$' -or
                    $record.sha256 -cnotmatch '^[0-9a-f]{64}$' -or -not $seen.Add($record.file) -or
                    @($artifacts | Where-Object { $_.value -ceq $artifact }).Count -ne 1 -or
                    $record.license.id -cne $choices[0].license.id -or $record.license.url -cne $choices[0].license.url -or
                    $record.evidence.url -cne $pom -or $record.evidence.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
                    -not $record.evidence.declaredLicense) {
                throw "The carried library $($component.purl) has unbound or duplicate license evidence."
            }
            if ($artifacts.Count -eq 1 -and (Get-SbomSha256 -Component $component -Label 'licensed artifact') -cne $record.sha256) {
                throw 'The licensed artifact hash does not match the component hash.'
            }
            if ($RequireCurrent) {
                $matches = @($approved | Where-Object { $_.purl -ceq $record.purl -and $_.file -ceq $record.file })
                if ($matches.Count -ne 1 -or $matches[0].sha256 -cne $record.sha256 -or
                        $matches[0].license.id -cne $record.license.id -or $matches[0].license.url -cne $record.license.url -or
                        $matches[0].evidence.url -cne $record.evidence.url -or $matches[0].evidence.sha256 -cne $record.evidence.sha256 -or
                        $matches[0].evidence.declaredLicense -cne $record.evidence.declaredLicense) {
                    throw "The carried artifact $($record.file) has no matching reviewed license record."
                }
                if (-not $reviewedInventory.Add($record.purl + "`t" + $record.file)) {
                    throw 'The SBOM repeats a reviewed carried license artifact.'
                }
            }
        }
    }
    if ($RequireCurrent) {
        foreach ($record in $approved) {
            if (-not $reviewedInventory.Contains($record.purl + "`t" + $record.file)) {
                throw "The SBOM omits the reviewed carried license artifact $($record.purl)/$($record.file)."
            }
        }
    }
}

function Read-ReleaseSbom {
    <#
    .SYNOPSIS
        The CycloneDX SBOM :patches:releaseSbom writes beside the bundle, read into what the receipt
        and the advisory gate need.
    .DESCRIPTION
        Answers @{ Path; Sha256; BundleName; BundleVersion; BundleSha256; Timestamp; Components;
        Libraries; Payloads }. Libraries are the components with a package URL, the ones OSV is
        asked about, and Payloads the extension payload files with their SHA-256.

        Held to the shape the task writes, and refused where it differs rather than read around.
        A library whose package URL isn't a Maven one naming its own group, name and version
        would be asked about under a name OSV answers {} for, which reads as no advisories. So
        would a library with no package URL at all, which only the modules built from this
        repository may be, marked hushgram:first-party.
    #>
    param([Parameter(Mandatory = $true)][string]$Path, [switch]$RequireReviewedLicenses,
        [string]$LicenseLedger = (Join-Path (Split-Path -Parent $PSScriptRoot) 'sources/carried-library-licenses.json'))

    $Path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "There is no SBOM at $Path." }
    $name = Split-Path -Leaf $Path
    try {
        $document = [System.IO.File]::ReadAllText($Path) | ConvertFrom-Json
    } catch {
        throw "$name is not JSON: $($_.Exception.Message)"
    }
    if ("$($document.bomFormat)" -ne 'CycloneDX' -or "$($document.specVersion)" -ne '1.6') {
        throw "$name is not a CycloneDX 1.6 SBOM."
    }
    $subject = $document.metadata.component
    $subjectHash = if ($subject) { Get-SbomSha256 -Component $subject -Label "$name's bundle" } else { $null }
    if (-not $subject -or -not $subject.name -or -not $subject.version -or -not $subjectHash) {
        throw "$name does not name the bundle it describes, its version and its SHA-256."
    }

    $components = New-Object System.Collections.Generic.List[object]
    $refs = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($component in @($document.components | Where-Object { $null -ne $_ })) {
        $ref = [string]$component.'bom-ref'
        if (-not $ref) { throw "$name lists a component with no bom-ref." }
        if (-not $refs.Add($ref)) { throw "$name lists $ref twice." }
        $entry = [pscustomobject]@{
            Ref        = $ref
            Type       = [string]$component.type
            Group      = [string]$component.group
            Name       = [string]$component.name
            Version    = [string]$component.version
            Purl       = [string]$component.purl
            FirstParty = @(@($component.properties) | Where-Object { $null -ne $_ -and "$($_.name)" -eq 'hushgram:first-party' }).Count -gt 0
            Sha256     = Get-SbomSha256 -Component $component -Label "$name's $ref"
        }
        switch ($entry.Type) {
            'library' {
                if ($entry.Purl) {
                    $purl = [regex]::Match($entry.Purl, '^pkg:maven/([^/@?#]+)/([^/@?#]+)@([^/@?#]+)$')
                    if (-not $purl.Success -or [Uri]::UnescapeDataString($purl.Groups[1].Value) -cne $entry.Group -or
                            [Uri]::UnescapeDataString($purl.Groups[2].Value) -cne $entry.Name -or
                            [Uri]::UnescapeDataString($purl.Groups[3].Value) -cne $entry.Version) {
                        throw ("$name lists $ref with the package URL $($entry.Purl), which doesn't name its own " +
                            'group, name and version, so OSV couldn''t be asked about it.')
                    }
                } elseif (-not $entry.FirstParty) {
                    throw "$name lists the library $ref with no package URL, so OSV couldn't be asked about it."
                }
            }
            'file' {
                if (-not $entry.Sha256) { throw "$name lists the file $ref with no SHA-256." }
            }
            default { throw "$name lists $ref as a $($entry.Type), which a release SBOM doesn't hold." }
        }
        $components.Add($entry)
    }
    if ($components.Count -eq 0) { throw "$name lists no component." }
    Assert-SbomCarriedLicenses -Document $document -RequireCurrent:$RequireReviewedLicenses -LedgerPath $LicenseLedger

    return [pscustomobject]@{
        Path          = $Path
        Sha256        = Get-Sha256Hex -Path $Path
        BundleName    = [string]$subject.name
        BundleVersion = [string]$subject.version
        BundleSha256  = $subjectHash
        Timestamp     = ConvertTo-UtcStamp $document.metadata.timestamp
        Components    = $components.ToArray()
        Libraries     = @($components | Where-Object { $_.Type -eq 'library' -and $_.Purl })
        Payloads      = @($components | Where-Object { $_.Type -eq 'file' })
    }
}

function Test-ReleaseSbom {
    <#
    .SYNOPSIS
        Whether an SBOM describes this bundle: @{ Valid; Reason }.
    .DESCRIPTION
        The bundle it names and that bundle's SHA-256, the version and pinned timestamp in the
        bundle's manifest, and every extension payload the bundle carries with the SHA-256 of its
        bytes. An SBOM left beside a newer build fails on the hash, and one dated from the clock
        rather than from the pinned stamp fails on the timestamp. -BundleName is the name the
        bundle is published under, since a downloaded copy has a temporary one.
    #>
    param(
        [Parameter(Mandatory = $true)]$Sbom,
        [Parameter(Mandatory = $true)][string]$BundlePath,
        [Parameter(Mandatory = $true)][string]$BundleName
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }

    $sbomName = Split-Path -Leaf $Sbom.Path
    if ($Sbom.BundleName -cne $BundleName) { return Fail "$sbomName describes $($Sbom.BundleName), not $BundleName." }
    $BundlePath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($BundlePath)
    $bundleHash = (Get-Sha256Hex -Path $BundlePath).ToLowerInvariant()
    if ($Sbom.BundleSha256 -ne $bundleHash) {
        return Fail ("$sbomName describes a $BundleName that hashes to $($Sbom.BundleSha256), and the bundle " +
            "hashes to $bundleHash. It was written for another build.")
    }
    $manifest = Get-BundleManifestFacts -BundlePath $BundlePath
    if ($Sbom.BundleVersion -ne $manifest.version) {
        return Fail "$sbomName says $BundleName is version $($Sbom.BundleVersion); its manifest says $($manifest.version)."
    }
    $stamp = [DateTimeOffset]::FromUnixTimeMilliseconds($manifest.timestamp).UtcDateTime.ToString(
        "yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture)
    if ($Sbom.Timestamp -ne $stamp) {
        return Fail ("$sbomName is dated $($Sbom.Timestamp), and $BundleName is stamped $stamp. The build " +
            'that pinned the bundle did not write it.')
    }

    $carried = [System.Collections.Generic.Dictionary[string, string]]::new([System.StringComparer]::Ordinal)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($BundlePath)
    try {
        foreach ($entry in @($archive.Entries | Where-Object { $_.FullName -like 'extensions/*.mpe' })) {
            $stream = $entry.Open()
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try {
                $carried[$entry.FullName] = ($sha.ComputeHash($stream) | ForEach-Object { '{0:x2}' -f $_ }) -join ''
            } finally {
                $sha.Dispose()
                $stream.Dispose()
            }
        }
    } finally {
        $archive.Dispose()
    }
    $described = [System.Collections.Generic.Dictionary[string, string]]::new([System.StringComparer]::Ordinal)
    foreach ($payload in @($Sbom.Payloads)) { $described[$payload.Ref] = $payload.Sha256 }
    foreach ($payload in @($carried.Keys | Sort-Object)) {
        if (-not $described.ContainsKey($payload)) { return Fail "$BundleName carries $payload, which $sbomName doesn't describe." }
        if ($described[$payload] -ne $carried[$payload]) {
            return Fail "$sbomName describes $payload hashing to $($described[$payload]); the one $BundleName carries hashes to $($carried[$payload])."
        }
    }
    foreach ($payload in @($described.Keys | Sort-Object)) {
        if (-not $carried.ContainsKey($payload)) { return Fail "$sbomName describes $payload, which $BundleName doesn't carry." }
    }
    return [pscustomobject]@{ Valid = $true; Reason = $null }
}

function Get-Sha256Hex {
    <#
    .SYNOPSIS
        A file's SHA-256 in upper-case hex, which is the form the release notes use.
    #>
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Cannot hash a file that is not there: $Path"
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Get-BundleManifestFacts {
    <#
    .SYNOPSIS
        Version, timestamp and patcher stamp out of a bundle's META-INF/MANIFEST.MF.
    .DESCRIPTION
        patches/build.gradle.kts pins the timestamp to the time of the commit it builds, in
        milliseconds, and to 0 when the tree had uncommitted changes as the build started. That's
        what makes a published hash reproducible from a tag. Read back here so a receipt cannot
        describe a bundle that was built from something other than the commit it names.
    #>
    param([Parameter(Mandatory = $true)][string]$BundlePath)

    # ZipFile reads a relative path against the process directory, not PowerShell's location.
    $BundlePath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($BundlePath)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $text = $null
    $archive = [System.IO.Compression.ZipFile]::OpenRead($BundlePath)
    try {
        $entry = $archive.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' }
        if (-not $entry) { throw "The bundle has no META-INF/MANIFEST.MF: $BundlePath" }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }

    # Manifest lines wrap at 72 characters with a leading space on the continuation.
    $text = $text -replace "\r?\n ", ''
    $timestamp = [regex]::Match($text, '(?m)^Timestamp:\s*(\d+)\s*$')
    $version = [regex]::Match($text, '(?m)^Version:\s*(\S+)\s*$')
    $patcher = [regex]::Match($text, '(?m)^Patcher-Version:\s*(\S+)\s*$')
    $mainAttributes = ($text -split '\r?\n\r?\n', 2)[0]
    $identities = [regex]::Matches($mainAttributes, '(?m)^HushGram-Build-Identity:\s*(\S+)\s*$')
    if ($identities.Count -gt 1) { throw 'The bundle manifest has duplicate production build identities.' }
    if (-not $timestamp.Success) { throw "The bundle manifest has no Timestamp: $BundlePath" }
    if (-not $version.Success) { throw "The bundle manifest has no Version: $BundlePath" }
    if (-not $patcher.Success) { throw "The bundle manifest has no Patcher-Version: $BundlePath" }

    return [pscustomobject]@{
        version        = $version.Groups[1].Value
        timestamp      = [long]$timestamp.Groups[1].Value
        patcherVersion = $patcher.Groups[1].Value
        buildIdentity  = if ($identities.Count) { $identities[0].Groups[1].Value } else { $null }
    }
}

function Get-ExtensionPayloads {
    <#
    .SYNOPSIS
        Names, sizes and hashes of the actual extension DEX bytes in a final bundle.
    #>
    param([string]$BundlePath)
    $BundlePath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($BundlePath)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $payloads = [Collections.Generic.List[object]]::new()
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $archive = [IO.Compression.ZipFile]::OpenRead($BundlePath)
    try {
        foreach ($entry in @($archive.Entries | Where-Object { $_.FullName -like 'extensions/*.mpe' } | Sort-Object FullName)) {
            if ($entry.FullName -cnotmatch '^extensions/[^/]+\.mpe$' -or -not $seen.Add($entry.FullName)) {
                throw 'The final bundle has a duplicate or invalid extension payload name.'
            }
            $stream = $entry.Open()
            $memory = [IO.MemoryStream]::new()
            try { $stream.CopyTo($memory); $bytes = $memory.ToArray() }
            finally { $stream.Dispose(); $memory.Dispose() }
            if ([Text.Encoding]::ASCII.GetString($bytes, 0, [Math]::Min(4, $bytes.Length)) -ne "dex`n") {
                throw "$($entry.FullName) in $BundlePath is not an Android DEX payload."
            }
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $hash = -join ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('X2') }) }
            finally { $sha.Dispose() }
            $payloads.Add([ordered]@{ name = $entry.FullName; sizeBytes = [long]$bytes.Length; sha256 = $hash })
        }
    } finally { $archive.Dispose() }
    if (-not $payloads.Count) { throw "The bundle carries no extension payload: $BundlePath" }
    return $payloads.ToArray()
}

function Resolve-ReceiptManifestAllowlist {
    <#
    .SYNOPSIS
        The reviewed manifest changes a receipt is held to: the allowlist its own commit carried.
    .DESCRIPTION
        The allowlist is the review of what a release's patches do to the manifest, so the one a
        receipt answers to is the one committed with it, for the reason Resolve-ReceiptToolchain
        gives. Read from the working tree, an entry added and never committed approved a change no
        commit ever reviewed, and one pruned after the release (an entry nothing produces fails the
        next receipt) refused the release that had needed it. On a release push the receipt's
        commit is the release commit, so the list is the one the tree carries and nothing is
        relaxed. A commit with no allowlist, and a receipt naming no commit, are held to the one at
        -WorkingPath, the first with a note. Answers @{ Entries; Note }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Commit,
        [Parameter(Mandatory = $true)][string]$WorkingPath
    )

    if ($Commit -notmatch '^[0-9a-f]{40}$') {
        return [pscustomobject]@{ Entries = @(Read-ManifestDeltaAllowlist -Path $WorkingPath); Note = $null }
    }
    $short = $Commit.Substring(0, 8)
    $path = 'scripts/manifest-delta-allowlist.txt'
    $kind = "$(Invoke-RepoGit -Root $Root -Arguments @('cat-file', '-t', "${Commit}:$path") | Select-Object -First 1)".Trim()
    if ($kind -ne 'blob') {
        return [pscustomobject]@{
            Entries = @(Read-ManifestDeltaAllowlist -Path $WorkingPath)
            Note = "commit $short has no manifest delta allowlist, so the receipt is held to the working one"
        }
    }
    $atCommit = @(ConvertFrom-ManifestDeltaAllowlist -Lines @(Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:$path")) `
        -Source " at $short")
    $working = @(if (Test-Path -LiteralPath $WorkingPath -PathType Leaf) { Read-ManifestDeltaAllowlist -Path $WorkingPath })
    $note = if ((@($atCommit) -join "`n") -ceq (@($working) -join "`n")) { $null } else {
        "the receipt is held to the $($atCommit.Count) manifest change(s) the allowlist at its own commit $short " +
            "reviews; the working allowlist has $($working.Count)"
    }
    return [pscustomobject]@{ Entries = $atCommit; Note = $note }
}

function Resolve-ReceiptCoveragePolicy {
    <#
    .SYNOPSIS
        The reviewed coverage census a receipt is held to: the one its own commit carried.
    .DESCRIPTION
        patch-coverage-expectations.json names the exact Instagram builds whose coverage was
        reviewed, and a retarget replaces the old build's entry. Read from the working tree, the
        published receipt for the replaced build read as uncertified on every push after the
        retarget. On a release push the receipt's commit is the release commit, so the census is
        the one the tree carries and nothing is relaxed. A commit with no census, and a receipt
        naming no commit, get $null, which Test-TargetCoverage reads as the working census, the
        first with a note. Answers @{ Policy; Note }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Commit
    )

    if ($Commit -notmatch '^[0-9a-f]{40}$') { return [pscustomobject]@{ Policy = $null; Note = $null } }
    $short = $Commit.Substring(0, 8)
    $path = 'scripts/patch-coverage-expectations.json'
    $kind = "$(Invoke-RepoGit -Root $Root -Arguments @('cat-file', '-t', "${Commit}:$path") | Select-Object -First 1)".Trim()
    if ($kind -ne 'blob') {
        return [pscustomobject]@{ Policy = $null
            Note = "commit $short has no reviewed coverage census, so the receipt is held to the working one" }
    }
    $atCommit = (@(Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:$path")) -join "`n").Trim()
    try { $policy = ConvertFrom-PatchJson $atCommit }
    catch { throw "The reviewed coverage census at $short is not strict JSON: $($_.Exception.Message)" }
    $workingPath = Join-Path $Root $path
    $working = if (Test-Path -LiteralPath $workingPath -PathType Leaf) {
        ((Get-Content -LiteralPath $workingPath -Raw) -replace '\r\n', "`n").Trim()
    }
    $note = if ($atCommit -ceq $working) { $null } else {
        "the receipt is held to the coverage census at its own commit $short, which differs from the working one"
    }
    return [pscustomobject]@{ Policy = $policy; Note = $note }
}

function Get-ChangelogVersions {
    <#
    .SYNOPSIS
        The versions a CHANGELOG names, in the order it names them.
    .DESCRIPTION
        Both shapes this file carries: a bare "## 0.32.0", a dated "## 0.14.0 (2026-09-05)", and
        the linked "## [0.1.5](compare/...) (2026-06-01)" the upstream generator wrote. Anything
        else under a level-two heading, "Unreleased" among them, is not a version and is ignored
        here; it is the absence of a version that this exists to notice.
    #>
    param([string]$Text)

    $found = New-Object System.Collections.Generic.List[string]
    foreach ($match in [regex]::Matches($Text, '(?m)^##\s+\[?v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)')) {
        $found.Add($match.Groups[1].Value)
    }
    # No comma wrap. Every caller writes @(Get-ChangelogVersions ...), and @(,$array) is an
    # array holding one array: the membership test then finds nothing and the first element
    # prints as the whole list.
    return $found.ToArray()
}

function Test-ChangelogVersions {
    <#
    .SYNOPSIS
        Whether the CHANGELOG still describes every version it described at the last release,
        and describes the version being released now.
    .DESCRIPTION
        A released version's heading is the only record a reader has that it shipped. In
        Hushfacebook on 2026-09-14 a post-release commit renamed "## 0.31.0" to "## Unreleased", so the file
        said that release never happened, and nothing noticed until the next release was cut by
        hand. No gate read this file at all.

        Held against the CHANGELOG as it stood at the last tag rather than against the tag list,
        so it needs no list of exceptions for versions that never had an entry: whatever was
        described then has to still be described now. Answers @{ Valid; Reason }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Current,
        [Parameter(Mandatory = $true)][string]$ExpectedVersion,
        # The same file at the last release tag. Absent on a checkout with no tag yet, in which
        # case only the version being released is checked.
        [string]$Previous,
        [string]$PreviousLabel = 'the last release'
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }

    $now = @(Get-ChangelogVersions -Text $Current)
    if ($now.Count -eq 0) { return Fail 'The CHANGELOG names no version at all.' }
    if ($now -notcontains $ExpectedVersion) {
        return Fail ("The CHANGELOG has no heading for $ExpectedVersion, the version this " +
            "checkout builds. It names $($now[0]) first.")
    }

    if ($PSBoundParameters.ContainsKey('Previous') -and $null -ne $Previous) {
        $then = @(Get-ChangelogVersions -Text $Previous)
        $present = [System.Collections.Generic.HashSet[string]]::new(
            [string[]]$now, [System.StringComparer]::Ordinal)
        $lost = @($then | Where-Object { -not $present.Contains($_) })
        if ($lost.Count -gt 0) {
            return Fail ("The CHANGELOG described " + ($lost -join ', ') + " at $PreviousLabel " +
                "and does not now. A shipped version cannot stop having an entry.")
        }
    }

    return [pscustomobject]@{ Valid = $true; Reason = 'ok' }
}

function Test-ChangelogManagerEntry {
    <#
    .SYNOPSIS
        Whether Morphe Manager can show the entry for the version being released.
    .DESCRIPTION
        Manager fetches this file from main and reads it with its own parser (ChangelogParser,
        Manager 1.30.0). A heading only counts when it ends in a date, "## 0.41.0 (2026-09-18)",
        and an app only gets its update badge when a bullet is scoped to it, "* **Instagram:** ...".
        Scoped lines are kept one line at a time, so a bullet wrapped onto a second line loses
        the rest. In Hushfacebook every heading from 0.23.0 to 0.40.0 was bare, Manager's list stopped at 0.22.0
        and every update showed nothing, and no gate noticed, because Test-ChangelogVersions
        reads its own heading pattern, which the bare headings matched.

        Only the section being released is held to this. Older sections are frozen as shipped.
        "* **Tooling:** ..." bullets, the development-only entries, are allowed and not counted.
        Answers @{ Valid; Reason; Date; Bullets }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Current,
        [Parameter(Mandatory = $true)][string]$ExpectedVersion,
        [string]$App = 'Instagram'
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason; Date = $null; Bullets = 0 } }

    # Manager's VERSION_HEADING and BULLET_SCOPE_RE, as its source spells them.
    $managerHeading = '^#{1,3}\s+(?:\S+\s+)?(?:\[([^\]]+)\]\([^)]*\)|([^\s\[(]+))\s+\((\d{4}-\d{2}-\d{2})\)'
    $managerScope = '^\* \*\*(.+?):\*\*'

    $lines = @($Current -split '\r?\n')
    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $version = [regex]::Match($lines[$i], '^##\s+\[?v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)')
        if ($version.Success -and $version.Groups[1].Value -eq $ExpectedVersion) { $start = $i; break }
    }
    if ($start -lt 0) { return Fail "The CHANGELOG has no heading for $ExpectedVersion." }

    $heading = [regex]::Match($lines[$start], $managerHeading)
    if (-not $heading.Success) {
        return Fail ("The $ExpectedVersion heading has no date, so Morphe Manager skips the whole " +
            "entry. Write it as ""## $ExpectedVersion (YYYY-MM-DD)"".")
    }
    $named = if ($heading.Groups[1].Success) { $heading.Groups[1].Value } else { $heading.Groups[2].Value }
    if ($named.TrimStart('v') -ne $ExpectedVersion) {
        return Fail ("Morphe Manager reads the $ExpectedVersion heading as version $named.")
    }

    $bullets = 0
    $previousWasBullet = $false
    for ($i = $start + 1; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        # The next level-one or level-two heading ends the section. "### Settings" inside it
        # groups bullets and is read by Manager as an ordinary line.
        if ($line -match '^#{1,2}(?!#)\s') { break }
        if ($line -match '^\s*[*+-]\s') {
            $scope = [regex]::Match($line, $managerScope)
            # A development-only change, the CHANGELOG's other scope. Manager shows a line only to
            # the app it's scoped to, so it shows these to nobody: allowed, and not counted.
            if ($scope.Success -and $scope.Groups[1].Value -ceq 'Tooling') {
                $previousWasBullet = $true
                continue
            }
            if (-not $scope.Success -or -not ($scope.Groups[1].Value -eq $App -or
                    $scope.Groups[1].Value.StartsWith("$App - "))) {
                return Fail ("Line $($i + 1) is a $ExpectedVersion bullet Morphe Manager does not " +
                    "scope to $App, so it is not counted as a $App change. Start it with " +
                    """* **${App}:** "": $line")
            }
            $bullets++
            $previousWasBullet = $true
            continue
        }
        if ($previousWasBullet -and -not [string]::IsNullOrWhiteSpace($line) -and $line -notmatch '^#') {
            return Fail ("Line $($i + 1) continues the bullet above it, and Morphe Manager keeps " +
                "scoped lines one at a time, so it would drop this text. Join it onto one line: $line")
        }
        $previousWasBullet = $false
    }
    if ($bullets -eq 0) {
        return Fail ("The $ExpectedVersion entry has no ""* **${App}:** "" bullet, so Morphe " +
            "Manager shows no update for $App.")
    }

    return [pscustomobject]@{ Valid = $true; Reason = 'ok'; Date = $heading.Groups[3].Value; Bullets = $bullets }
}

function Invoke-RepoGit {
    <#
    .SYNOPSIS
        git against the repository a caller names, with no inherited git environment.
    .DESCRIPTION
        `git -C <path>` sets the working directory and does not override GIT_DIR. Every script
        here runs from the pre-push hook, and a hook is a git child process with GIT_DIR and
        GIT_WORK_TREE already in its environment, so without this a -Root parameter is a
        suggestion rather than an instruction: git reads whichever repository the hook came from.
        In Hushfacebook on 2026-09-15 that turned a temporary fixture into three commits on the
        real branch.

        git writes what it shows as UTF-8, and PowerShell reads a native command's output in the
        console's code page, which is 437 in a hook started from Git Bash. A file read out of a
        commit came back with its byte order mark as three characters, and any other character
        past ASCII changed the same way, so Use-Utf8ConsoleOutput (common.ps1) has PowerShell read
        UTF-8 for the call, even in a process with no console.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $saved = @{}
    foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
        $saved[$variable.Name] = $variable.Value
        Remove-Item -LiteralPath ('Env:\' + $variable.Name) -ErrorAction SilentlyContinue
    }
    # Windows PowerShell 5.1 turns a native command's stderr into a terminating error under
    # Stop even when it is redirected. Relax for the call and restore afterwards.
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        return Use-Utf8ConsoleOutput { & git -C $Root @Arguments 2>$null }
    } finally {
        $ErrorActionPreference = $preference
        foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
    }
}

function Read-CatalogToolchain {
    <#
    .SYNOPSIS
        The patcher pin and Manager floor out of a version catalog's text.
    .DESCRIPTION
        Takes text rather than a path, so the same reading applies to the catalog in the working
        tree and to the one at an older commit. Every failure names where the text came from,
        because "does not pin morphe-patcher" means something different in the working tree than
        at a commit from a release that shipped months ago.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Source
    )

    $patcherMatch = [regex]::Match($Text, '(?m)^\s*morphe-patcher\s*=\s*"([^"]+)"')
    if (-not $patcherMatch.Success) { throw "$Source does not pin morphe-patcher." }
    $floorMatch = [regex]::Match($Text, '(?m)^\s*manager-floor\s*=\s*"([^"]+)"')
    if (-not $floorMatch.Success) {
        throw "$Source does not pin manager-floor beside morphe-patcher."
    }
    $floor = $floorMatch.Groups[1].Value
    if ($floor -notmatch '^\d+\.\d+\.\d+$') {
        throw "$Source has an invalid manager-floor: $floor"
    }
    return [pscustomobject]@{
        PatcherVersion = $patcherMatch.Groups[1].Value
        ManagerFloor   = $floor
    }
}

function Resolve-ReceiptToolchain {
    <#
    .SYNOPSIS
        The toolchain a receipt should be held to: the one its own commit pinned.
    .DESCRIPTION
        A receipt describes a release that has already shipped. Moving the patcher pin afterwards
        does not make it wrong, but holding it to the working catalog said it was: in Hushfacebook,
        after the pin moved to 1.13.0 while the tree still carried the receipt for 0.32.0, every
        source push failed with "The receipt was stamped by patcher 1.12.0; the catalog pins
        1.13.0", with both files correct. Only the checkout that cut the release has a receipt at
        all, so the gate stopped exactly the machine that had done the work, and the way through
        was to move the receipt out of the tree, which turns the check off altogether.

        On a release push the receipt's commit is the release commit, so this reads the same
        catalog the working tree has and nothing is relaxed.

        Answers @{ Toolchain; Note }, where Note is a line worth printing or $null when the
        answer is simply the working catalog.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Commit,
        [Parameter(Mandatory = $true)]$WorkingToolchain
    )

    if ($Commit -notmatch '^[0-9a-f]{40}$') {
        return [pscustomobject]@{ Toolchain = $WorkingToolchain; Note = $null }
    }

    # Stderr is dropped inside the helper, because git writes "path does not exist in commit"
    # there and an empty result here has to mean "no catalog at that commit", not "git said
    # something".
    $catalogAtCommit = (Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:gradle/libs.versions.toml")) -join "`n"
    $short = $Commit.Substring(0, 8)
    if ([string]::IsNullOrWhiteSpace($catalogAtCommit)) {
        return [pscustomobject]@{
            Toolchain = $WorkingToolchain
            Note = "commit $short has no version catalog, so the receipt is held to the working one"
        }
    }

    $atCommit = Read-CatalogToolchain -Text $catalogAtCommit `
        -Source "gradle/libs.versions.toml at $short"
    if ($atCommit.PatcherVersion -eq $WorkingToolchain.PatcherVersion -and
        $atCommit.ManagerFloor -eq $WorkingToolchain.ManagerFloor) {
        return [pscustomobject]@{ Toolchain = $atCommit; Note = $null }
    }
    return [pscustomobject]@{
        Toolchain = $atCommit
        Note = ("the receipt is held to patcher $($atCommit.PatcherVersion) and Manager floor " +
            "$($atCommit.ManagerFloor), which its own commit $short pinned; the catalog now pins " +
            "$($WorkingToolchain.PatcherVersion) and $($WorkingToolchain.ManagerFloor)")
    }
}

function Resolve-IndexManagerFloor {
    <#
    .SYNOPSIS
        The Manager floor the index description has to name: the one the published release's own
        commit pinned, found through its tag.
    .DESCRIPTION
        The index describes a release that has shipped, and that release needs the Manager its own
        commit pinned, whatever the catalog pins since. Hushfacebook 0.1.1 was stamped by patcher
        1.14.0 and needs Manager 1.31.0 after the catalog moved on to 1.14.1 and 1.32.0 for the next release,
        so neither the working catalog nor the index version alone gives the answer. The tag
        v<version> says which commit it was.

        -Commit is that commit when the caller has read the published tag already, as the release
        check's published asset run does. Without it, -RemoteUrl is asked first: a local tag can
        name another commit than GitHub's after a release is re-cut there, and git fetch won't move
        it, and `gh release create` makes the tag on GitHub only, so the push that writes a new
        description comes from a clone without one. The clone's own tag answers when no remote is
        named, or when the remote doesn't have the tag or can't be asked. A tag found nowhere, a
        commit this clone doesn't hold, or one with no catalog can't tell, and answers Floor $null
        with a Note saying the check didn't run.

        Answers @{ Floor; Source; Note }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Version,
        [string]$Commit,
        [string]$RemoteUrl
    )

    $tag = "v$Version"
    $source = "tag $tag"
    $unchecked = 'so the Manager floor the index names wasn''t checked'
    $remoteSaid = ''
    if ($Commit -notmatch '^[0-9a-f]{40}$' -and $RemoteUrl) {
        $advertised = @(Invoke-RepoGit -Root $Root -Arguments @('ls-remote', $RemoteUrl, "refs/tags/$tag", "refs/tags/$tag^{}"))
        $asked = $LASTEXITCODE -eq 0
        # An annotated tag's own line names the tag object; the peeled line names its commit.
        $escaped = [regex]::Escape("refs/tags/$tag")
        $line = @(@($advertised | Where-Object { "$_" -match "^[0-9a-f]{40}\s+$escaped\^\{\}$" }) +
            @($advertised | Where-Object { "$_" -match "^[0-9a-f]{40}\s+$escaped$" })) | Select-Object -First 1
        if (-not $asked) {
            $remoteSaid = " and $RemoteUrl couldn't be asked for it"
        } elseif (-not $line) {
            $remoteSaid = " or on $RemoteUrl"
        } else {
            $Commit = ([string]$line).Substring(0, 40)
            $source = "tag $tag on $RemoteUrl"
        }
    }
    if ($Commit -notmatch '^[0-9a-f]{40}$') {
        $Commit = "$(Invoke-RepoGit -Root $Root -Arguments @('rev-parse', '--verify', '--quiet', "refs/tags/$tag^{commit}") |
            Select-Object -First 1)".Trim()
    }
    if ($Commit -notmatch '^[0-9a-f]{40}$') {
        return [pscustomobject]@{ Floor = $null; Source = $null
            Note = "tag $tag isn't in this clone$remoteSaid, $unchecked" }
    }
    $held = "$(Invoke-RepoGit -Root $Root -Arguments @('rev-parse', '--verify', '--quiet', "$Commit^{commit}") |
        Select-Object -First 1)".Trim()
    if ($held -notmatch '^[0-9a-f]{40}$') {
        return [pscustomobject]@{ Floor = $null; Source = $null
            Note = "$source names commit $($Commit.Substring(0, 8)), which this clone doesn't have, $unchecked" }
    }
    $catalogAtTag = (Invoke-RepoGit -Root $Root -Arguments @('show', "${held}:gradle/libs.versions.toml")) -join "`n"
    if ([string]::IsNullOrWhiteSpace($catalogAtTag)) {
        return [pscustomobject]@{ Floor = $null; Source = $null; Note = "$source has no version catalog, $unchecked" }
    }
    $atTag = Read-CatalogToolchain -Text $catalogAtTag -Source "gradle/libs.versions.toml at $source"
    return [pscustomobject]@{ Floor = $atTag.ManagerFloor; Source = $source; Note = $null }
}

function Resolve-ReceiptCatalog {
    <#
    .SYNOPSIS
        The patch list a receipt should be held to: the one its own commit carried.
    .DESCRIPTION
        The same reasoning as Resolve-ReceiptToolchain, for the patch names and the target. A patch
        added or renamed after a release doesn't make that release's receipt wrong, but holding
        the receipt to the working patch list said it was. While the source version stays on the
        released one, as it did through Hushfeed's hold after 0.58.0, every such push failed on the one
        machine that has a receipt. On Hushfeed that read: "The receipt reports com.zhiliaoapp.musically
        46.2.3 patch Hide quick comment reactions, which the catalog does not list."

        On a release push the receipt's commit is the release commit, so this reads the list the
        working tree has and nothing is relaxed.

        Answers @{ PatchList; Note }: the parsed patches-list.json to hold the receipt to, and a
        line worth printing, or $null when the answer is simply the working list.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Commit,
        [Parameter(Mandatory = $true)]$WorkingPatchList
    )

    if ($Commit -notmatch '^[0-9a-f]{40}$') {
        return [pscustomobject]@{ PatchList = $WorkingPatchList; Note = $null }
    }
    $listAtCommit = (Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:patches-list.json")) -join "`n"
    $short = $Commit.Substring(0, 8)
    if ([string]::IsNullOrWhiteSpace($listAtCommit)) {
        return [pscustomobject]@{
            PatchList = $WorkingPatchList
            Note = "commit $short has no patch list, so the receipt is held to the working one"
        }
    }
    try {
        $atCommit = $listAtCommit | ConvertFrom-Json
    } catch {
        throw "patches-list.json at $short is not readable: $($_.Exception.Message)"
    }
    $namesThen = @($atCommit.patches | ForEach-Object { [string]$_.name } | Sort-Object) -join "`n"
    $namesNow = @($WorkingPatchList.patches | ForEach-Object { [string]$_.name } | Sort-Object) -join "`n"
    $targetsThen = ($atCommit.patches | ForEach-Object { $_.compatiblePackages | ConvertTo-Json -Compress -Depth 4 } | Sort-Object -Unique) -join "`n"
    $targetsNow = ($WorkingPatchList.patches | ForEach-Object { $_.compatiblePackages | ConvertTo-Json -Compress -Depth 4 } | Sort-Object -Unique) -join "`n"
    if ($namesThen -eq $namesNow -and $targetsThen -eq $targetsNow) {
        return [pscustomobject]@{ PatchList = $atCommit; Note = $null }
    }
    return [pscustomobject]@{
        PatchList = $atCommit
        Note = ("the receipt is held to the $(@($atCommit.patches).Count) patches and the target its " +
            "own commit $short carried; the working list has $(@($WorkingPatchList.patches).Count)")
    }
}

function Test-ReleaseReceipt {
    <#
    .SYNOPSIS
        Whether a receipt describes this checkout's release, and whether its manifest changes
        were reviewed.
    .DESCRIPTION
        Answers @{ Valid; Reason }. Every failure names the one fact that did not line up,
        because the point of the receipt is to say which input was wrong, not that something was.
    #>
    param(
        [Parameter(Mandatory = $true)]$Receipt,
        [Parameter(Mandatory = $true)][string]$ExpectedVersion,
        [Parameter(Mandatory = $true)][string[]]$ExpectedPatchNames,
        [Parameter(Mandatory = $true)][string]$ExpectedPatcherVersion,
        [Parameter(Mandatory = $true)][string]$ExpectedManagerFloor,
        # The package and every version the catalog declares. Without them a receipt built only
        # from forced runs against newer builds reads as proof of the release, when nothing in it
        # was patched the way a user's Manager patches it. Each declared version needs its own
        # run: the README says the patches were checked on every one of them.
        [Parameter(Mandatory = $true)][string]$ExpectedPackageName,
        [Parameter(Mandatory = $true)][string[]]$ExpectedPackageVersions,
        # The version codes the catalog pins those versions to (Get-PatchTarget's PackageVersionCodes).
        # Another build of a declared version isn't the declared build, so a run of it proves nothing
        # without -f. A version pinned to no code is matched by its name.
        [System.Collections.IDictionary]$ExpectedPackageVersionCodes,
        [string]$BundlePath,
        [string[]]$ApprovedManifestDelta = @(),
        # The reviewed coverage census its own commit carried (Resolve-ReceiptCoveragePolicy).
        # Without it the working census is read.
        [object]$CoveragePolicy,
        # When the commit the receipt names was made, read out of git by the caller. Without it
        # the receipt's commit and its timestamp are only checked against each other, which any
        # receipt agrees with, including one left over from an earlier release.
        [long]$ActualCommitTimestamp = 0,
        # The commit this run is about. Only a release is held to it: on an ordinary push the
        # receipt legitimately describes the commit it was generated at, not HEAD.
        [string]$ExpectedCommit,
        # The schema the receipt's own commit writes (Resolve-ReceiptSchema). From 2 a receipt
        # names the release SBOM.
        [int]$ExpectedSchemaVersion = (Get-ReleaseReceiptSchemaVersion),
        # The SBOM itself, when the caller has it: its hash and component count have to be what
        # the receipt records, and with -BundlePath it has to describe that bundle.
        [string]$SbomPath
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }

    if ($null -eq $Receipt) { return Fail 'There is no receipt to check.' }
    if ([int]$Receipt.schemaVersion -ne $ExpectedSchemaVersion) {
        return Fail ("The receipt is schema version $($Receipt.schemaVersion); its release is read at " +
            "version $ExpectedSchemaVersion.")
    }
    if ($ExpectedSchemaVersion -ge 4) {
        try { Assert-CanonicalBuildIdentity -Identity $Receipt.buildIdentity }
        catch { return Fail $_.Exception.Message }
    }
    if ($Receipt.release.version -ne $ExpectedVersion) {
        return Fail "The receipt is for $($Receipt.release.version), not $ExpectedVersion."
    }
    if ($Receipt.release.tag -ne "v$ExpectedVersion") {
        return Fail "The receipt names tag $($Receipt.release.tag) for version $ExpectedVersion."
    }
    if ($Receipt.release.commit -notmatch '^[0-9a-f]{40}$') {
        return Fail "The receipt has no full commit: $($Receipt.release.commit)"
    }
    if ([long]$Receipt.release.commitTimestamp -le 0) {
        return Fail 'The receipt does not say when the commit it names was made.'
    }
    if ($ExpectedCommit -and $Receipt.release.commit -ne $ExpectedCommit) {
        return Fail ("The receipt describes commit $($Receipt.release.commit); this release is " +
            "$ExpectedCommit.")
    }
    # Against git, not against the receipt's own other field. The bundle stamp check below
    # compares two numbers that both came out of this document, so on its own it proves only
    # that the document agrees with itself.
    if ($ActualCommitTimestamp -gt 0 -and
            [long]$Receipt.release.commitTimestamp -ne $ActualCommitTimestamp) {
        return Fail ("The receipt says commit $($Receipt.release.commit) was made at " +
            "$($Receipt.release.commitTimestamp); git says $ActualCommitTimestamp.")
    }
    if ([int]$Receipt.release.patchCount -ne $ExpectedPatchNames.Count) {
        return Fail ("The receipt counts $($Receipt.release.patchCount) patches; the catalog " +
            "has $($ExpectedPatchNames.Count).")
    }
    if ($Receipt.toolchain.patcherVersion -ne $ExpectedPatcherVersion) {
        return Fail ("The receipt was stamped by patcher $($Receipt.toolchain.patcherVersion); " +
            "the catalog pins $ExpectedPatcherVersion.")
    }
    if ($Receipt.toolchain.managerFloor -ne $ExpectedManagerFloor) {
        return Fail ("The receipt names Manager floor $($Receipt.toolchain.managerFloor); " +
            "the catalog pins $ExpectedManagerFloor.")
    }
    if ($ExpectedSchemaVersion -ge 2) {
        $sbom = $Receipt.sbom
        if ($null -eq $sbom) { return Fail 'The receipt names no SBOM, so nothing records what the bundle carries.' }
        if ([string]$sbom.file -cne "patches-$ExpectedVersion.cdx.json") {
            return Fail ("The receipt names the SBOM $($sbom.file); the one for $ExpectedVersion is " +
                "patches-$ExpectedVersion.cdx.json.")
        }
        if ([string]$sbom.sha256 -cnotmatch '^[0-9A-F]{64}$') {
            return Fail "The receipt records no SHA-256 for $($sbom.file)."
        }
        $sbomCount = 0L
        if (-not [long]::TryParse("$($sbom.components)", [ref]$sbomCount) -or $sbomCount -le 0) {
            return Fail "The receipt counts no component in $($sbom.file)."
        }
    }

    if ($BundlePath) {
        if (-not (Test-Path -LiteralPath $BundlePath -PathType Leaf)) {
            return Fail "The receipt cannot be checked against a bundle that is not there: $BundlePath"
        }
        $actualSize = (Get-Item -LiteralPath $BundlePath).Length
        if ([long]$Receipt.bundle.sizeBytes -ne $actualSize) {
            return Fail ("The receipt says the bundle is $($Receipt.bundle.sizeBytes) bytes; " +
                "$BundlePath is $actualSize.")
        }
        $actualHash = Get-Sha256Hex -Path $BundlePath
        if ([string]$Receipt.bundle.sha256 -ne $actualHash) {
            return Fail ("The receipt says the bundle hashes to $($Receipt.bundle.sha256); " +
                "$BundlePath hashes to $actualHash.")
        }

        $manifest = Get-BundleManifestFacts -BundlePath $BundlePath
        if ($ExpectedSchemaVersion -ge 4 -and $manifest.buildIdentity -cne $Receipt.buildIdentity.id) {
            return Fail 'The receipt identity differs from the bundle production build identity.'
        }
        if ($manifest.version -ne $ExpectedVersion) {
            return Fail "The bundle's manifest says version $($manifest.version), not $ExpectedVersion."
        }
        if ($manifest.patcherVersion -ne $ExpectedPatcherVersion) {
            return Fail ("The bundle was stamped by patcher $($manifest.patcherVersion); the " +
                "catalog pins $ExpectedPatcherVersion.")
        }
        if ([long]$Receipt.bundle.timestamp -ne $manifest.timestamp) {
            return Fail ("The receipt says the bundle is stamped $($Receipt.bundle.timestamp); " +
                "$BundlePath is stamped $($manifest.timestamp).")
        }
        # The one fact that makes a published hash reproducible from a tag. patches/build.gradle.kts
        # pins this to the time of the commit it builds, and to 0 when the tree had uncommitted
        # changes as the build started, so anything else means the bundle was built from a
        # different commit, or from a tree with uncommitted changes in it, and nobody can rebuild
        # it from the source the receipt names. Hushfeed v0.28.0 shipped exactly that way. Until 2026-09-26
        # a tree with changes got HEAD's time as well, so a match didn't rule them out. It still
        # can't for an edit made after the build started, which build-release-receipt.ps1 catches
        # by refusing a bundle older than any of its sources.
        $expectedStamp = [long]$Receipt.release.commitTimestamp * 1000
        if ($manifest.timestamp -ne $expectedStamp) {
            return Fail ("The bundle is stamped $($manifest.timestamp) but the commit it is " +
                "attributed to was made at $expectedStamp. It was built from a different " +
                "commit, or from a tree that had uncommitted changes.")
        }
    }

    if ($SbomPath) {
        if ($ExpectedSchemaVersion -lt 2) { return Fail "A schema $ExpectedSchemaVersion receipt names no SBOM to hold $SbomPath to." }
        if (-not (Test-Path -LiteralPath $SbomPath -PathType Leaf)) {
            return Fail "The receipt cannot be checked against an SBOM that is not there: $SbomPath"
        }
        $actualSbomHash = Get-Sha256Hex -Path $SbomPath
        if ([string]$Receipt.sbom.sha256 -ne $actualSbomHash) {
            return Fail ("The receipt says $($Receipt.sbom.file) hashes to $($Receipt.sbom.sha256); " +
                "$SbomPath hashes to $actualSbomHash.")
        }
        try {
            $document = Read-ReleaseSbom -Path $SbomPath
        } catch {
            return Fail "The SBOM the receipt names can't be read: $($_.Exception.Message)"
        }
        if ($document.Components.Count -ne [long]$Receipt.sbom.components) {
            return Fail ("The receipt counts $($Receipt.sbom.components) components in $($Receipt.sbom.file); " +
                "it lists $($document.Components.Count).")
        }
        if ($BundlePath) {
            $bound = Test-ReleaseSbom -Sbom $document -BundlePath $BundlePath -BundleName ([string]$Receipt.bundle.file)
            if (-not $bound.Valid) { return Fail $bound.Reason }
        }
        if ($ExpectedSchemaVersion -ge 4) {
            try { $sbomIdentity = Read-SbomCanonicalBuildIdentity -Path $SbomPath }
            catch { return Fail $_.Exception.Message }
            if ($sbomIdentity.id -cne $Receipt.buildIdentity.id) { return Fail 'The receipt identity differs from its SBOM production identity.' }
        }
    }

    # Nulls dropped first. A receipt with the key missing altogether gives $null here, and
    # @($null) is an array holding one null, so the count guard below would walk past a truncated
    # document and then blame the nameless entry it found instead of the missing section.
    $payloads = @(@($Receipt.extension.dexPayloads) | Where-Object { $null -ne $_ })
    if ($payloads.Count -eq 0) {
        return Fail 'The receipt records no extension DEX payload, so it identifies no extension.'
    }
    foreach ($payload in $payloads) {
        if (-not $payload.name) { return Fail 'A recorded DEX payload has no name.' }
        if ([long]$payload.sizeBytes -le 0) {
            return Fail "The recorded DEX payload $($payload.name) is empty."
        }
        if ([string]$payload.sha256 -notmatch '^[0-9A-F]{64}$') {
            return Fail "The recorded DEX payload $($payload.name) has no SHA-256."
        }
    }
    if ($ExpectedSchemaVersion -ge 4 -and $BundlePath) {
        try { $actualPayloads = @(Get-ExtensionPayloads -BundlePath $BundlePath) }
        catch { return Fail $_.Exception.Message }
        if ($payloads.Count -ne $actualPayloads.Count -or @($payloads.name | Sort-Object -Unique).Count -ne $payloads.Count) {
            return Fail 'The receipt does not map each final extension payload exactly once.'
        }
        foreach ($actual in $actualPayloads) {
            $recorded = @($payloads | Where-Object { $_.name -ceq $actual.name })
            if ($recorded.Count -ne 1 -or $recorded[0].sizeBytes -ne $actual.sizeBytes -or
                $recorded[0].sha256 -cne $actual.sha256) {
                return Fail "The receipt bytes differ from the final extension payload $($actual.name)."
            }
        }
    }

    $targets = @(@($Receipt.targets) | Where-Object { $null -ne $_ })
    if ($targets.Count -eq 0) { return Fail 'The receipt records no target, so nothing was proved.' }

    $expected = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]$ExpectedPatchNames, [System.StringComparer]::Ordinal)
    $produced = New-Object System.Collections.Generic.List[string]
    $provedVersions = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    # What Test-DeclaredBuild (patch-target.ps1) reads.
    $declaredTarget = [pscustomobject]@{
        PackageVersions = $ExpectedPackageVersions
        PackageVersionCodes = if ($null -ne $ExpectedPackageVersionCodes) { $ExpectedPackageVersionCodes } else { @{} }
    }
    foreach ($target in $targets) {
        $label = "$($target.source.package) $($target.source.versionName)"
        if ([string]$target.source.sha256 -notmatch '^[0-9A-F]{64}$') {
            return Fail "The receipt records no source APK hash for $label."
        }
        if ([string]$target.source.package -ne $ExpectedPackageName) {
            return Fail ("The receipt records a run against $($target.source.package); the " +
                "catalog targets $ExpectedPackageName.")
        }
        if ([string]::IsNullOrWhiteSpace([string]$target.source.versionName)) {
            return Fail "The receipt records a $ExpectedPackageName run with no version name."
        }
        # Whether the CLI was told to ignore the declared version. Recorded as a boolean by the
        # builder; a receipt that leaves it out cannot say which of its runs were the real one.
        $forcedProperty = $target.source.PSObject.Properties['forced']
        if ($null -eq $forcedProperty -or $forcedProperty.Value -isnot [bool]) {
            return Fail "The receipt does not say whether $label was patched under -f."
        }
        $atDeclaredVersion = Test-DeclaredBuild -Target $declaredTarget -VersionName ([string]$target.source.versionName) `
            -VersionCode ([string]$target.source.versionCode)
        if ($forcedProperty.Value -eq $atDeclaredVersion) {
            return Fail ("The receipt says $label (version code $($target.source.versionCode)) was " +
                $(if ($forcedProperty.Value) { 'forced past' } else { 'patched without -f at' }) +
                " a declared build, but the catalog declares $(Format-DeclaredBuilds -Target $declaredTarget).")
        }
        if ($atDeclaredVersion) { [void]$provedVersions.Add([string]$target.source.versionName) }
        $verdicts = @($target.patches)
        if ($verdicts.Count -ne $ExpectedPatchNames.Count) {
            return Fail ("The receipt records $($verdicts.Count) patch verdicts for $label; " +
                "the catalog has $($ExpectedPatchNames.Count).")
        }
        $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
        foreach ($verdict in $verdicts) {
            $name = [string]$verdict.name
            if (-not $expected.Contains($name)) {
                return Fail "The receipt reports $label patch $name, which the catalog does not list."
            }
            if (-not $seen.Add($name)) {
                return Fail "The receipt reports $label patch $name twice."
            }
            if (-not $verdict.applied) {
                return Fail "The receipt reports $label patch $name as not applied."
            }
        }
        if ($ExpectedSchemaVersion -ge 3) {
            $coverageProperty = $target.PSObject.Properties['coverage']
            $reviewedProperty = $target.PSObject.Properties['coverageReviewed']
            if ($null -eq $coverageProperty -or $coverageProperty.Value -isnot [array] -or
                $null -eq $reviewedProperty -or $reviewedProperty.Value -isnot [bool]) {
                return Fail "The receipt records no typed target coverage for $label."
            }
            $coverageVerdict = Test-TargetCoverage -Coverage @($coverageProperty.Value) -Names $ExpectedPatchNames `
                -Package $target.source.package -VersionName $target.source.versionName -VersionCode $target.source.versionCode `
                -Policy $CoveragePolicy
            if (-not $coverageVerdict.Valid -or $reviewedProperty.Value -ne $coverageVerdict.Reviewed -or
                ($atDeclaredVersion -and -not $coverageVerdict.Reviewed)) {
                return Fail "The receipt target coverage for $label is not certified: $($coverageVerdict.Reason)"
            }
        }
        foreach ($entry in ConvertTo-ManifestDeltaEntries -Delta $target.manifestDelta) {
            $produced.Add($entry)
        }
        if (@($ApprovedManifestDelta) -ccontains 'min-sdk 28') {
            $stockMinimum = $target.source.PSObject.Properties['minSdkVersion']
            if (-not $stockMinimum -or ($stockMinimum.Value -isnot [int] -and $stockMinimum.Value -isnot [long]) -or
                $stockMinimum.Value -lt 1) {
                return Fail "The receipt has no verified vendor minimum Android API for $label."
            }
            try { Assert-HushgramRuntimeFloor -Manifest $target -Stock $target.source }
            catch { return Fail "The receipt has an invalid runtime floor for ${label}: $($_.Exception.Message)" }
        }
    }
    # Every declared version, not just one of them. With two declared, a receipt that ran only
    # the newest still found one declared target, and the release would claim a build nothing
    # in it had patched.
    $unproved = @($ExpectedPackageVersions | Where-Object { -not $provedVersions.Contains([string]$_) })
    if ($unproved.Count -gt 0) {
        $ran = @($targets | ForEach-Object { [string]$_.source.versionName }) -join ', '
        return Fail ("No target in the receipt is the declared $ExpectedPackageName " +
            "$($unproved -join ', ') patched without -f; it only records $ran.")
    }

    # A PowerShell function that returns an empty array hands back nothing, so an allowlist with
    # no entries arrives here as $null, and @($null) is an array holding one null. Left alone,
    # that null is an approved entry no delta produces and every clean run fails on it.
    $approvedEntries = @(@($ApprovedManifestDelta) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $approved = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]$approvedEntries, [System.StringComparer]::Ordinal)
    $unapproved = @(@($produced | Sort-Object -Unique -CaseSensitive) | Where-Object { -not $approved.Contains($_) })
    if ($unapproved.Count -gt 0) {
        return Fail ('The patched manifest changed in ways nobody reviewed: ' +
            ($unapproved -join ', '))
    }
    $seenEntries = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]@($produced), [System.StringComparer]::Ordinal)
    $stale = @($approvedEntries | Where-Object { $_ -cne 'min-sdk 28' -and -not $seenEntries.Contains($_) })
    if ($stale.Count -gt 0) {
        return Fail ('The manifest delta allowlist approves changes no patch makes any more: ' +
            ($stale -join ', '))
    }

    return [pscustomobject]@{ Valid = $true; Reason = $null }
}
