<#
.SYNOPSIS
    Exercise the release tooling: receipts and their SBOM, the resolvers that read a receipt's own
    commit, the OSV advisory gate, the CHANGELOG checks, validate-release-facts.ps1 and
    build-release-receipt.ps1.
.DESCRIPTION
    Nothing here needs the network, a phone or an Instagram APK. OSV, GitHub, the JDK, aapt2 and
    the desktop CLI are stand-ins, and git runs against fixture repositories in the temporary
    folder. The pre-push hook runs this suite when a release script, one of the lists they read
    or this file changes, and a missing suite stops the push.

    Taken from the release sections of Hushfacebook's scripts/test-script-contracts.ps1
    (https://github.com/SysAdminDoc/Hushfacebook, commit 814acd23d7b70d5d23abce6cb6c97767e16a051e),
    which came from Hushfeed (https://github.com/SysAdminDoc/hushfeed). GPL-3.0-only.
    Modified for HushGram (Instagram), 2026: held to Instagram's one declared build and the
    manifest removals HushGram's allowlist approves, and extended to the release check's
    pre-release mode, the fixture folder the receipt builder reads by default and the
    SHA256SUMS.txt it writes.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'script-wiring.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Pattern, [string]$Message)
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -like $Pattern) { return }
        throw "$Message Unexpected error: $($_.Exception.Message)"
    }
    throw "$Message No error was raised."
}

function New-NotFoundAnswer {
    # What Invoke-WebRequest raises for a 404 in both shells, as far as Assert-UrlReachable reads
    # it: an error whose Response carries the status. A function named Invoke-WebRequest that
    # throws this stands in for the request, so no case here needs the network.
    $answer = New-Object System.Exception 'Response status code does not indicate success: 404 (Not Found).'
    Add-Member -InputObject $answer -NotePropertyName Response -NotePropertyValue ([pscustomobject]@{ StatusCode = 404 })
    return $answer
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
function New-TestBundleArchive {
    param([string]$Path, [System.Collections.Specialized.OrderedDictionary]$Entries)
    $archive = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $Entries.Keys) {
            $writer = New-Object System.IO.StreamWriter($archive.CreateEntry($name).Open())
            try { $writer.Write($Entries[$name]) } finally { $writer.Dispose() }
        }
    } finally { $archive.Dispose() }
}

# The release scripts read HUSHGRAM_* variables, and a maintainer's own would steer the stand-ins
# below to real tools and fixtures. The suite runs in a pwsh of its own, so clearing them here
# touches nothing outside it.
foreach ($name in @('HUSHGRAM_FIXTURE_DIR', 'HUSHGRAM_DESKTOP_JAR', 'HUSHGRAM_WORKDIR', 'HUSHGRAM_JAVA', 'HUSHGRAM_AAPT2')) {
    Remove-Item -LiteralPath "Env:\$name" -ErrorAction SilentlyContinue
}
# The stand-ins patch nothing heavy, so they don't wait for a slot in the machine's build queue.
# build-jobs.ps1 reads the user's environment too, so these are switched off rather than cleared.
$env:BUILD_QUEUE_SCRIPT = 'none'
$env:HUSHGRAM_BUILD_WRAPPER = 'none'
# The gate's kept runs (gate-evidence.ps1) in a folder of the suite's own, so a maintainer's real
# runs are neither read nor pruned. Nothing is written there before the release root below.
$env:HUSHGRAM_GATE_CACHE = Join-Path ([System.IO.Path]::GetTempPath()) ('hushgram-gate-cache-' + [guid]::NewGuid().ToString('N'))

# --- release receipt -------------------------------------------------------------------------

. (Join-Path $PSScriptRoot 'release-receipt.ps1')

$manifestLines = @(
    'N: android=http://schemas.android.com/apk/res/android (line=1)',
    '  E: manifest (line=1)',
    '    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=2024607030',
    '    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="46.7.3" (Raw: "46.7.3")',
    '    A: package="com.example.host" (Raw: "com.example.host")',
    '    A: platformBuildVersionCode=36',
    '      E: uses-permission (line=10)',
    '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")',
    '      E: uses-permission (line=11)',
    '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.CAMERA" (Raw: "android.permission.CAMERA")',
    '      E: application (line=20)',
    '        E: activity (line=21)',
    '          A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.host.Main" (Raw: "com.example.host.Main")',
    '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true',
    '            E: intent-filter (line=22)',
    '              E: action (line=23)',
    '                A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")',
    '        E: activity (line=30)',
    '          A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.host.Private" (Raw: "com.example.host.Private")',
    '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false',
    '        E: service (line=40)',
    '          A: http://schemas.android.com/apk/res/android:name(0x01010003)=".Sync" (Raw: ".Sync")',
    '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true'
)

$facts = ConvertFrom-ManifestXmlTree -Lines $manifestLines -Source 'fixture'
Assert-True ($facts.package -eq 'com.example.host') 'The manifest package was not read.'
Assert-True ($facts.versionName -eq '46.7.3') 'The manifest version name was not read.'
Assert-True ($facts.versionCode -eq '2024607030') 'The manifest version code was not read.'
Assert-True (($facts.permissions -join ',') -eq 'android.permission.CAMERA,android.permission.INTERNET') `
    'The requested permissions were not read and sorted.'
Assert-True (($facts.exported -join ',') -eq 'activity:com.example.host.Main,service:com.example.host.Sync') `
    "Exported components were misread: $($facts.exported -join ',')"
Assert-True ($facts.exported -notcontains 'activity:com.example.host.Private') `
    'A component marked exported=false was reported as exported.'
Assert-True ($facts.exported -notcontains 'action:android.intent.action.MAIN') `
    'An intent-filter action was counted as an exported component.'

Assert-Throws {
    ConvertFrom-ManifestXmlTree -Lines @('  E: manifest (line=1)') -Source 'nameless'
} '*no package name*' 'A manifest with no package was accepted.'

$patchedFacts = ConvertFrom-ManifestXmlTree -Source 'patched' -Lines (
    @($manifestLines | Where-Object { $_ -notlike '*android.permission.CAMERA*' }) + @(
        '      E: uses-permission (line=12)',
        '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.VIBRATE" (Raw: "android.permission.VIBRATE")',
        '      E: application (line=20)',
        '        E: receiver (line=50)',
        '          A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.host.Probe" (Raw: "com.example.host.Probe")',
        '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true'))
$delta = Get-ManifestDelta -Stock $facts -Patched $patchedFacts
Assert-True (($delta.permissionsAdded -join ',') -eq 'android.permission.VIBRATE') `
    'An added permission was not reported.'
Assert-True (($delta.permissionsRemoved -join ',') -eq 'android.permission.CAMERA') `
    'A removed permission was not reported.'
Assert-True (($delta.exportedComponentsAdded -join ',') -eq 'receiver:com.example.host.Probe') `
    'A newly exported component was not reported.'
Assert-True (@($delta.exportedComponentsRemoved).Count -eq 0) `
    'A component that stayed exported was reported as removed.'
$entries = ConvertTo-ManifestDeltaEntries -Delta $delta
Assert-True (($entries -join '; ') -eq (@(
    'exported-added receiver:com.example.host.Probe',
    'permission-added android.permission.VIBRATE',
    'permission-removed android.permission.CAMERA') -join '; ')) `
    "The delta did not flatten to allowlist lines: $($entries -join '; ')"

$unchanged = Get-ManifestDelta -Stock $facts -Patched $facts
Assert-True (@(ConvertTo-ManifestDeltaEntries -Delta $unchanged).Count -eq 0) `
    'An unchanged manifest produced a delta.'
Assert-True (@($unchanged.versionCodeChanged).Count -eq 0) 'An unchanged version code was reported as changed.'

# Change version code: the patched build's code is the change, so the allowlist names the one code
# that was reviewed.
$raisedFacts = $facts.PSObject.Copy()
$raisedFacts.versionCode = '2147483647'
$raisedDelta = Get-ManifestDelta -Stock $facts -Patched $raisedFacts
Assert-True ((@(ConvertTo-ManifestDeltaEntries -Delta $raisedDelta) -join '; ') -ceq 'version-code 2147483647') `
    "A raised version code did not flatten to its allowlist line: $(@(ConvertTo-ManifestDeltaEntries -Delta $raisedDelta) -join '; ')"
# A receipt written before the delta had a version code field still flattens.
$olderDelta = [pscustomobject]@{ permissionsAdded = @(); permissionsRemoved = @('android.permission.CAMERA')
    exportedComponentsAdded = @(); exportedComponentsRemoved = @() }
Assert-True ((@(ConvertTo-ManifestDeltaEntries -Delta $olderDelta) -join '; ') -ceq 'permission-removed android.permission.CAMERA') `
    'A delta without a version code field did not flatten.'

# The checked-in allowlist approves two changes and nothing else: Remove the advertising ID takes
# the three advertising ID permissions out of the build, which the receipt reads as three
# permissions no longer asked for, and Change version code raises the version code to the highest.
$checkedInAllowlist = @(Read-ManifestDeltaAllowlist -Path (Join-Path $PSScriptRoot 'manifest-delta-allowlist.txt') |
    Where-Object { $_ })
$removalEntries = @(
    'permission-removed android.permission.ACCESS_ADSERVICES_AD_ID',
    'permission-removed android.permission.ACCESS_ADSERVICES_ATTRIBUTION',
    'permission-removed com.google.android.gms.permission.AD_ID')
$removedPermissions = @($removalEntries | ForEach-Object { $_ -replace '^permission-removed ', '' })
$raisedVersionCode = '2147483647'
$approvedEntries = @($removalEntries) + "version-code $raisedVersionCode"
Assert-True ((@($checkedInAllowlist | Sort-Object -CaseSensitive) -join "`n") -ceq
        (@($approvedEntries | Sort-Object -CaseSensitive) -join "`n")) `
    ('The checked-in manifest delta allowlist approves something besides the advertising ID removals and the ' +
     "raised version code, or less than all of them: $($checkedInAllowlist -join ', ')")

$allowlistRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("receipt-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $allowlistRoot | Out-Null
try {
    $good = Join-Path $allowlistRoot 'good.txt'
    Set-Content -LiteralPath $good -Encoding UTF8 -Value @(
        '# a comment', '', 'permission-added android.permission.VIBRATE',
        'exported-added receiver:com.example.host.Probe', 'version-code 2147483647')
    Assert-True (@(Read-ManifestDeltaAllowlist -Path $good).Count -eq 3) `
        'The allowlist reader did not skip comments and blank lines.'

    $bad = Join-Path $allowlistRoot 'bad.txt'
    Set-Content -LiteralPath $bad -Encoding UTF8 -Value @('permission-added')
    Assert-Throws { Read-ManifestDeltaAllowlist -Path $bad } '*<kind> <value>*' `
        'A malformed allowlist line was accepted.'
    Assert-Throws { Read-ManifestDeltaAllowlist -Path (Join-Path $allowlistRoot 'absent.txt') } `
        '*allowlist is missing*' 'A missing allowlist was treated as an empty one.'

    # A stand-in bundle, so the size, hash and manifest checks compare against real bytes.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $commitSeconds = 1700000000L
    $testBuildIdentity = [pscustomobject]@{ schemaVersion = 1; sourceSha256 = ('0' * 64); catalogSha256 = ('1' * 64)
        toolchainSha256 = ('2' * 64); id = 'hg1:cd44116f6ee1923f60ea7b97e6f131740dd97f13e5e65330ba3242d39f94039b' }
    function New-TestBundle {
        param([string]$Path, [string]$Version = '9.9.9', [long]$Timestamp = 1700000000000L,
            [string]$Patcher = '1.12.0', [hashtable]$Entries = @{}, [string]$BuildIdentity = $testBuildIdentity.id)
        if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
        $archive = [System.IO.Compression.ZipFile]::Open(
            $Path, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            $entry = $archive.CreateEntry('META-INF/MANIFEST.MF')
            $writer = New-Object System.IO.StreamWriter($entry.Open())
            try {
                $writer.Write("Manifest-Version: 1.0`nVersion: $Version`n" +
                    "Timestamp: $Timestamp`nPatcher-Version: $Patcher`n" +
                    $(if ($BuildIdentity) { "HushGram-Build-Identity: $BuildIdentity`n" }) + "`n")
            } finally { $writer.Dispose() }
            foreach ($name in @($Entries.Keys | Sort-Object)) {
                $writer = New-Object System.IO.StreamWriter($archive.CreateEntry($name).Open())
                try { $writer.Write($Entries[$name]) } finally { $writer.Dispose() }
            }
        } finally { $archive.Dispose() }
    }

    # An SBOM in the shape :patches:releaseSbom writes, describing the bundle at -Bundle: its name,
    # hash, version and pinned stamp, the libraries given as package URLs, the patch module as the
    # first-party code, and each extension payload the bundle carries with its hash. -Mutate edits
    # the document before it's written.
    function New-TestSbom {
        param([string]$Path, [string]$Bundle,
            [string[]]$Libraries = @('pkg:maven/com.google.code.gson/gson@2.14.0'), [scriptblock]$Mutate,
            [string]$InputRoot, [string]$LicenseLedger, [object[]]$ReviewedLicenseRecords)
        $bundleName = Split-Path -Leaf $Bundle
        $facts = Get-BundleManifestFacts -BundlePath $Bundle
        $components = New-Object System.Collections.Generic.List[object]
        $selectedLicenses = New-Object System.Collections.Generic.List[object]
        $licenseRecords = if ($LicenseLedger) {
            if ($ReviewedLicenseRecords) { @($ReviewedLicenseRecords) }
            else { @((Get-Content -LiteralPath $LicenseLedger -Raw | ConvertFrom-Json).artifacts) }
        } else { @() }
        foreach ($purl in @($Libraries | Where-Object { $_ })) {
            $parts = [regex]::Match($purl, '^pkg:maven/([^/]+)/([^@]+)@(.+)$')
            $components.Add([ordered]@{ type = 'library'; 'bom-ref' = $purl; group = $parts.Groups[1].Value
                name = $parts.Groups[2].Value; version = $parts.Groups[3].Value; scope = 'required'; purl = $purl
                properties = @([ordered]@{ name = 'hushgram:carried-by'; value = $bundleName }) })
            if ($LicenseLedger) {
                $record = $licenseRecords | Where-Object { $_.purl -ceq $purl }
                if (@($record).Count -ne 1) { throw "No unique reviewed license fixture for $purl." }
                $selectedLicenses.Add($record)
                $component = $components[$components.Count - 1]
                $component.hashes = @(@{ alg = 'SHA-256'; content = $record.sha256 })
                $component.licenses = @(@{ license = $record.license })
                $component.properties += @(@{ name = 'hushgram:artifact'; value = "$($record.file) sha256:$($record.sha256)" },
                    @{ name = 'hushgram:license-evidence'; value = ($record | ConvertTo-Json -Depth 8 -Compress) })
            }
        }
        $components.Add([ordered]@{ type = 'library'; 'bom-ref' = 'project:patches'; name = ':patches'
            version = $facts.version; scope = 'required'
            properties = @([ordered]@{ name = 'hushgram:first-party'; value = 'built from this repository' }) })
        $archive = [System.IO.Compression.ZipFile]::OpenRead($Bundle)
        try {
            foreach ($entry in @($archive.Entries | Where-Object { $_.FullName -like 'extensions/*.mpe' } | Sort-Object FullName)) {
                $stream = $entry.Open()
                $sha = [System.Security.Cryptography.SHA256]::Create()
                try { $digest = ($sha.ComputeHash($stream) | ForEach-Object { '{0:x2}' -f $_ }) -join '' } finally { $sha.Dispose(); $stream.Dispose() }
                $components.Add([ordered]@{ type = 'file'; 'bom-ref' = $entry.FullName; name = $entry.FullName
                    version = $facts.version; scope = 'required'; hashes = @([ordered]@{ alg = 'SHA-256'; content = $digest }) })
            }
        } finally { $archive.Dispose() }
        if ($LicenseLedger) {
            # Each stand-in build uses the exact reviewed inventory that its SBOM declares.
            # Keep the full test evidence catalog separate from this current builder input.
            $selectedLedger = [ordered]@{ schemaVersion = 1; artifacts = $selectedLicenses.ToArray() }
            [IO.File]::WriteAllText($LicenseLedger, ($selectedLedger | ConvertTo-Json -Depth 12),
                [Text.UTF8Encoding]::new($false))
        }
        $document = [ordered]@{
            bomFormat = 'CycloneDX'; specVersion = '1.6'; serialNumber = "urn:uuid:$([guid]::NewGuid())"; version = 1
            metadata = [ordered]@{
                timestamp = [DateTimeOffset]::FromUnixTimeMilliseconds($facts.timestamp).UtcDateTime.ToString(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture)
                component = [ordered]@{ type = 'file'; 'bom-ref' = $bundleName; name = $bundleName; version = $facts.version
                    hashes = @([ordered]@{ alg = 'SHA-256'; content = (Get-Sha256Hex -Path $Bundle).ToLowerInvariant() }) }
            }
            components = $components.ToArray()
            dependencies = @()
        }
        $identityForSbom = if ($InputRoot -and (Test-Path -LiteralPath (Join-Path $InputRoot 'scripts/canonical-build-inputs.txt'))) {
            Get-CanonicalBuildIdentity -Root $InputRoot
        } else { $testBuildIdentity }
        $document.metadata['properties'] = @([ordered]@{ name = 'hushgram:canonical-build-identity'
            value = ($identityForSbom | ConvertTo-Json -Compress) })
        if ($InputRoot) {
            $document.metadata['properties'] += [ordered]@{ name = 'hushgram:build-inputs'
                value = (Get-DependencyAuditInputs -Root $InputRoot | ConvertTo-Json -Compress) }
        }
        if ($LicenseLedger) {
            $document.metadata.properties = @($document.metadata.properties) + @(@{ name = 'hushgram:license-policy'; value = 'reviewed-artifacts-v1' },
                @{ name = 'hushgram:license-ledger'; value = (Get-FileHash -LiteralPath $LicenseLedger).Hash.ToLowerInvariant() })
        }
        if ($Mutate) { & $Mutate $document }
        Set-Content -LiteralPath $Path -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 12)
    }

    $bundle = Join-Path $allowlistRoot 'patches-9.9.9.mpp'
    New-TestBundle -Path $bundle -Entries @{ 'extensions/instagram.mpe' = "dex`n035 payload" }
    $bundleHash = Get-Sha256Hex -Path $bundle
    $bundleSize = (Get-Item -LiteralPath $bundle).Length
    $sbomFile = Join-Path $allowlistRoot 'patches-9.9.9.cdx.json'
    New-TestSbom -Path $sbomFile -Bundle $bundle

    $goodSbom = [IO.File]::ReadAllBytes($sbomFile)
    try {
        foreach ($change in @(
                { param($d) $d.metadata.properties = @() },
                { param($d) $d.metadata.properties += $d.metadata.properties[0] },
                { param($d) $d.metadata.properties[0].value = 1 },
                { param($d) $i = $d.metadata.properties[0].value | ConvertFrom-Json
                    $i.toolchainSha256 = 'f' * 64; $d.metadata.properties[0].value = $i | ConvertTo-Json -Compress })) {
            New-TestSbom -Path $sbomFile -Bundle $bundle -Mutate $change
            Assert-Throws { Read-SbomCanonicalBuildIdentity -Path $sbomFile } '*identity*' `
                'An absent, duplicated, coerced or substituted SBOM production identity was accepted.'
        }
    } finally { [IO.File]::WriteAllBytes($sbomFile, $goodSbom) }

    $manifestFacts = Get-BundleManifestFacts -BundlePath $bundle
    Assert-True ($manifestFacts.version -eq '9.9.9') 'The bundle manifest version was not read.'
    Assert-True ($manifestFacts.timestamp -eq 1700000000000L) 'The bundle timestamp was not read.'
    Assert-True ($manifestFacts.patcherVersion -eq '1.12.0') 'The bundle patcher stamp was not read.'

    # Two declared builds, each pinned to its version code, and a run of each. HushGram declares
    # one Instagram build today, and the check reads any number of them.
    $declaredBuilds = @('46.7.3', '46.6.1')
    $declaredCodes = @{ '46.7.3' = [string[]]@('2024607030'); '46.6.1' = [string[]]@('2024606010') }
    $template = [ordered]@{
        schemaVersion = Get-ReleaseReceiptSchemaVersion
        buildIdentity = $testBuildIdentity
        release   = [ordered]@{ version = '9.9.9'; tag = 'v9.9.9'
            commit = '0123456789abcdef0123456789abcdef01234567'
            commitTimestamp = $commitSeconds; patchCount = 2 }
        bundle    = [ordered]@{ file = 'patches-9.9.9.mpp'; sizeBytes = $bundleSize
            sha256 = $bundleHash; timestamp = 1700000000000L }
        sbom      = [ordered]@{ file = 'patches-9.9.9.cdx.json'; sha256 = (Get-Sha256Hex -Path $sbomFile); components = 3 }
        toolchain = [ordered]@{ patcherVersion = '1.12.0'; managerFloor = '1.29.0' }
        extension = [ordered]@{ dexPayloads = @(Get-ExtensionPayloads -BundlePath $bundle) }
        targets   = @([ordered]@{
            source = [ordered]@{ file = 'stock.apkm'; package = 'com.example.host'
                versionName = '46.7.3'; versionCode = '2024607030'; sha256 = ('B' * 64)
                forced = $false }
            patches = @([ordered]@{ name = 'Alpha'; applied = $true; reason = $null },
                        [ordered]@{ name = 'Beta'; applied = $true; reason = $null })
            coverage = @(); coverageReviewed = $true
            manifestDelta = [ordered]@{ permissionsAdded = @(); permissionsRemoved = @()
                exportedComponentsAdded = @(); exportedComponentsRemoved = @(); versionCodeChanged = @() }
        }, [ordered]@{
            source = [ordered]@{ file = 'previous.apkm'; package = 'com.example.host'
                versionName = '46.6.1'; versionCode = '2024606010'; sha256 = ('D' * 64)
                forced = $false }
            patches = @([ordered]@{ name = 'Alpha'; applied = $true; reason = $null },
                        [ordered]@{ name = 'Beta'; applied = $true; reason = $null })
            coverage = @(); coverageReviewed = $true
            manifestDelta = [ordered]@{ permissionsAdded = @(); permissionsRemoved = @()
                exportedComponentsAdded = @(); exportedComponentsRemoved = @(); versionCodeChanged = @() }
        })
    }
    $templateJson = $template | ConvertTo-Json -Depth 12

    function New-TestReceipt {
        param([scriptblock]$Mutate)
        $copy = $templateJson | ConvertFrom-Json
        if ($Mutate) { & $Mutate $copy }
        return $copy
    }

    function Test-TestReceipt {
        param($Receipt, [string[]]$Approved = @())
        return Test-ReleaseReceipt -Receipt $Receipt -ExpectedVersion '9.9.9' `
            -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
            -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds `
            -ExpectedPackageVersionCodes $declaredCodes -BundlePath $bundle -ApprovedManifestDelta $Approved
    }

    $valid = Test-TestReceipt -Receipt (New-TestReceipt)
    Assert-True $valid.Valid "A complete receipt was refused: $($valid.Reason)"
    foreach ($identity in @('', ('hg1:' + ('f' * 64)))) {
        $oddBundle = Join-Path $allowlistRoot 'identity-substitution.mpp'
        New-TestBundle -Path $oddBundle -BuildIdentity $identity -Entries @{ 'extensions/instagram.mpe' = "dex`n035 payload" }
        $oddReceipt = New-TestReceipt -Mutate {
            param($r)
            $r.bundle.sha256 = Get-Sha256Hex -Path $oddBundle
            $r.bundle.sizeBytes = (Get-Item -LiteralPath $oddBundle).Length
        }
        $result = Test-ReleaseReceipt -Receipt $oddReceipt -ExpectedVersion '9.9.9' `
            -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
            -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' `
            -ExpectedPackageVersions $declaredBuilds -ExpectedPackageVersionCodes $declaredCodes -BundlePath $oddBundle
        Assert-True (-not $result.Valid -and $result.Reason -like '*identity*') `
            "A missing or different bundle identity was not specifically refused: $($result.Reason)"
    }
    foreach ($historical in @(1, 2, 3)) {
        $old = New-TestReceipt
        $old.schemaVersion = $historical
        $old.PSObject.Properties.Remove('buildIdentity')
        if ($historical -lt 2) { $old.PSObject.Properties.Remove('sbom') }
        $legacy = Test-ReleaseReceipt -Receipt $old -ExpectedVersion '9.9.9' -ExpectedPatchNames @('Alpha', 'Beta') `
            -ExpectedPatcherVersion '1.12.0' -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' `
            -ExpectedPackageVersions $declaredBuilds -ExpectedPackageVersionCodes $declaredCodes -BundlePath $bundle `
            -ExpectedSchemaVersion $historical
        Assert-True $legacy.Valid "Historical schema $historical was forced to supply a new identity: $($legacy.Reason)"
    }

    # Every fact the receipt exists to pin, put in front of the check one at a time. A gate that
    # has never been shown to fail is a gate nobody has tested.
    $mutations = [ordered]@{
        'a receipt from a different schema'     = { param($r) $r.schemaVersion = 99 }
        'no production identity'                = { param($r) $r.PSObject.Properties.Remove('buildIdentity') }
        'another production identity'          = { param($r) $r.buildIdentity.id = 'hg1:' + ('f' * 64) }
        'a changed code digest'                 = { param($r) $r.buildIdentity.sourceSha256 = 'f' * 64 }
        'a changed catalog digest'              = { param($r) $r.buildIdentity.catalogSha256 = 'f' * 64 }
        'a changed toolchain digest'            = { param($r) $r.buildIdentity.toolchainSha256 = 'f' * 64 }
        'a substituted payload hash'            = { param($r) $r.extension.dexPayloads[0].sha256 = 'C' * 64 }
        'a substituted payload name'            = { param($r) $r.extension.dexPayloads[0].name = 'extensions/foreign.mpe' }
        'a substituted payload size'            = { param($r) $r.extension.dexPayloads[0].sizeBytes += 1 }
        'a duplicate payload record'            = { param($r) $r.extension.dexPayloads += $r.extension.dexPayloads[0] }
        'a receipt for a different version'     = { param($r) $r.release.version = '9.9.8' }
        'a tag that does not match the version' = { param($r) $r.release.tag = 'v9.9.8' }
        'a short commit'                        = { param($r) $r.release.commit = '0123456' }
        'a patch count that is not the catalog' = { param($r) $r.release.patchCount = 3 }
        'a different patcher'                   = { param($r) $r.toolchain.patcherVersion = '1.13.0' }
        'a different Manager floor'             = { param($r) $r.toolchain.managerFloor = '1.30.0' }
        'a bundle size that is not the bundle'  = { param($r) $r.bundle.sizeBytes = 4 }
        'a bundle hash that is not the bundle'  = { param($r) $r.bundle.sha256 = ('C' * 64) }
        'an empty extension payload'            = { param($r) $r.extension.dexPayloads[0].sizeBytes = 0 }
        'an unhashed extension payload'         = { param($r) $r.extension.dexPayloads[0].sha256 = 'nope' }
        'no extension payload at all'           = { param($r) $r.extension.dexPayloads = @() }
        'no target at all'                      = { param($r) $r.targets = @() }
        'an unhashed source APK'                = { param($r) $r.targets[0].source.sha256 = '' }
        'fewer verdicts than patches'           = { param($r) $r.targets[0].patches = @($r.targets[0].patches[0]) }
        'a patch the catalog does not list'     = { param($r) $r.targets[0].patches[1].name = 'Gamma' }
        'the same patch reported twice'         = { param($r) $r.targets[0].patches[1].name = 'Alpha' }
        'a patch that did not apply'            = { param($r) $r.targets[0].patches[1].applied = $false }
        'a receipt with no commit time'         = { param($r) $r.release.commitTimestamp = 0 }
        'a stamp that is not the bundle stamp'  = { param($r) $r.bundle.timestamp = 1700000001000L }
        'a run against another package'         = { param($r) $r.targets[0].source.package = 'com.example.other' }
        'a run with no version name'            = { param($r) $r.targets[0].source.versionName = '' }
        'a receipt that omits the forced flag'  = { param($r) $r.targets[0].source.PSObject.Properties.Remove('forced') }
        'a forced flag that is not a boolean'   = { param($r) $r.targets[0].source.forced = 'false' }
        'a declared-version run marked forced'  = { param($r) $r.targets[0].source.forced = $true }
        'only forced runs past the target'      = { param($r) $r.targets[0].source.versionName = '46.8.3'; $r.targets[0].source.forced = $true; $r.targets = @($r.targets[0]) }
        'a newer build patched without -f'      = { param($r) $r.targets[0].source.versionName = '46.8.3' }
        'a run of the newest declared build only' = { param($r) $r.targets = @($r.targets[0]) }
        'the older declared build forced'       = { param($r) $r.targets[1].source.forced = $true }
        'both runs at the newest declared build' = { param($r) $r.targets[1].source.versionName = '46.7.3' }
        'another build of a declared version patched without -f' = { param($r) $r.targets[1].source.versionCode = '2024605949' }
        'a receipt that names no SBOM'          = { param($r) $r.PSObject.Properties.Remove('sbom') }
        'an SBOM named for another version'     = { param($r) $r.sbom.file = 'patches-9.9.8.cdx.json' }
        'an SBOM with no hash'                  = { param($r) $r.sbom.sha256 = 'nope' }
        'an SBOM hash in lower case'            = { param($r) $r.sbom.sha256 = ([string]$r.sbom.sha256).ToLowerInvariant() }
        'an SBOM counting no component'         = { param($r) $r.sbom.components = 0 }
        'no coverage field'                    = { param($r) $r.targets[0].PSObject.Properties.Remove('coverage') }
        'null coverage'                        = { param($r) $r.targets[0].coverage = $null }
        'no coverage review flag'              = { param($r) $r.targets[0].PSObject.Properties.Remove('coverageReviewed') }
        'an untyped coverage review flag'      = { param($r) $r.targets[0].coverageReviewed = 'true' }
        'forged coverage review'               = { param($r) $r.targets[0].coverageReviewed = $false }
    }
    foreach ($description in $mutations.Keys) {
        $result = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations[$description])
        Assert-True (-not $result.Valid) "Receipt validation accepted $description."
        Assert-True ([bool]$result.Reason) "Receipt validation refused $description without saying why."
    }

    # A receipt built only from forced runs against newer builds has to be refused for that
    # reason and name the target it is missing, not trip over some other field on the way.
    $onlyForced = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations['only forced runs past the target'])
    Assert-True ($onlyForced.Reason -like '*No target*46.7.3*without -f*46.8.3*') `
        "A forced-only receipt was refused for the wrong reason: $($onlyForced.Reason)"
    # A release that ran only the newest build names the declared build it never patched.
    $newestOnly = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations['a run of the newest declared build only'])
    Assert-True ($newestOnly.Reason -like '*No target*46.6.1*without -f*') `
        "A receipt missing a declared build was refused for the wrong reason: $($newestOnly.Reason)"
    $secondTarget = New-TestReceipt -Mutate {
        param($r)
        $newer = $r.targets[0] | ConvertTo-Json -Depth 8 | ConvertFrom-Json
        $newer.source.versionName = '46.8.3'
        $newer.source.forced = $true
        $r.targets = @($r.targets[0], $r.targets[1], $newer)
    }
    $twoTargets = Test-TestReceipt -Receipt $secondTarget
    Assert-True $twoTargets.Valid "A receipt with the declared target beside a forced run was refused: $($twoTargets.Reason)"
    # Another build of a declared version is a build of its own: patched without -f it is refused
    # for that, naming the code the catalog pins, and forced beside the declared runs it is fine.
    $otherBuild = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations['another build of a declared version patched without -f'])
    Assert-True ($otherBuild.Reason -like '*46.6.1 (version code 2024605949) was patched without -f at a declared build*46.6.1 (2024606010)*') `
        "Another build of a declared version was refused for the wrong reason: $($otherBuild.Reason)"
    $forcedOtherBuild = Test-TestReceipt -Receipt (New-TestReceipt -Mutate {
        param($r)
        $variant = $r.targets[1] | ConvertTo-Json -Depth 8 | ConvertFrom-Json
        $variant.source.versionCode = '2024605949'
        $variant.source.forced = $true
        $r.targets = @($r.targets[0], $r.targets[1], $variant)
    })
    Assert-True $forcedOtherBuild.Valid "Another build of a declared version, forced beside the declared runs, was refused: $($forcedOtherBuild.Reason)"

    # The bundle the receipt is about, gone. Every fact above is checked against a file, and a
    # missing file is the one case where there is nothing to disagree with, so an unguarded
    # check would read it as agreement and pass the release.
    $absent = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath (Join-Path $allowlistRoot 'not-built.mpp')
    Assert-True (-not $absent.Valid) 'A receipt was accepted against a bundle that is not there.'
    Assert-True ($absent.Reason -like '*not there*') `
        "The missing bundle was refused for the wrong reason: $($absent.Reason)"

    # The bundle itself disagreeing with the receipt, the failure Hushfeed's v0.28.0 shipped with: a bundle
    # built before its release commit existed carries the previous commit's pin, and nobody can
    # reproduce the published hash from the tag.
    $strayBundle = Join-Path $allowlistRoot 'patches-stray.mpp'
    New-TestBundle -Path $strayBundle -Timestamp 1699999999000L
    $strayReceipt = New-TestReceipt -Mutate {
        param($r)
        $r.bundle.sizeBytes = (Get-Item -LiteralPath $strayBundle).Length
        $r.bundle.sha256 = Get-Sha256Hex -Path $strayBundle
        $r.bundle.timestamp = 1699999999000L
    }
    $strayResult = Test-ReleaseReceipt -Receipt $strayReceipt -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $strayBundle
    Assert-True (-not $strayResult.Valid) 'A bundle built from another commit was accepted.'
    Assert-True ($strayResult.Reason -like '*different*commit*') `
        "The stale bundle pin was refused for the wrong reason: $($strayResult.Reason)"

    foreach ($wrong in @(
        @{ Name = 'a bundle stamped with another version'; Version = '9.9.8'; Patcher = '1.12.0'
            Pattern = '*manifest says version*' },
        @{ Name = 'a bundle stamped by another patcher'; Version = '9.9.9'; Patcher = '1.13.0'
            Pattern = '*stamped by patcher*' })) {
        $odd = Join-Path $allowlistRoot 'patches-odd.mpp'
        New-TestBundle -Path $odd -Version $wrong.Version -Patcher $wrong.Patcher
        $oddReceipt = New-TestReceipt -Mutate {
            param($r)
            $r.bundle.sizeBytes = (Get-Item -LiteralPath $odd).Length
            $r.bundle.sha256 = Get-Sha256Hex -Path $odd
        }
        $oddResult = Test-ReleaseReceipt -Receipt $oddReceipt -ExpectedVersion '9.9.9' `
            -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
            -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $odd
        Assert-True (-not $oddResult.Valid) "Receipt validation accepted $($wrong.Name)."
        Assert-True ($oddResult.Reason -like $wrong.Pattern) `
            "$($wrong.Name) was refused for the wrong reason: $($oddResult.Reason)"
    }

    # The commit the receipt names, checked against something outside the receipt. Its own
    # timestamp field and the bundle stamp both come from the same document, so a receipt kept
    # from an earlier release agrees with itself and passes on that pair alone.
    $sameCommit = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle `
        -ActualCommitTimestamp $commitSeconds
    Assert-True $sameCommit.Valid "A receipt matching git was refused: $($sameCommit.Reason)"

    $movedCommit = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle `
        -ActualCommitTimestamp ($commitSeconds + 60)
    Assert-True (-not $movedCommit.Valid) `
        'A receipt whose commit time git disagrees with was accepted.'
    Assert-True ($movedCommit.Reason -like '*git says*') `
        "The stale receipt was refused for the wrong reason: $($movedCommit.Reason)"

    $otherCommit = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle `
        -ExpectedCommit ('f' * 40)
    Assert-True (-not $otherCommit.Valid) 'A receipt for another commit was accepted on a release.'
    Assert-True ($otherCommit.Reason -like '*this release is*') `
        "The wrong-commit receipt was refused for the wrong reason: $($otherCommit.Reason)"

    # A truncated document, which is the shape @($null) turns into one null entry.
    foreach ($missing in @('targets', 'dexPayloads')) {
        $truncated = $templateJson | ConvertFrom-Json
        if ($missing -eq 'targets') {
            $truncated.PSObject.Properties.Remove('targets')
        } else {
            $truncated.extension.PSObject.Properties.Remove('dexPayloads')
        }
        $result = Test-TestReceipt -Receipt $truncated
        Assert-True (-not $result.Valid) "A receipt with no $missing was accepted."
        Assert-True ($result.Reason -like '*no target*' -or $result.Reason -like '*no extension*') `
            "A receipt with no $missing was refused for the wrong reason: $($result.Reason)"
    }

    $withDelta = New-TestReceipt -Mutate {
        param($r) $r.targets[0].manifestDelta.permissionsAdded = @('android.permission.VIBRATE')
    }
    $unreviewed = Test-TestReceipt -Receipt $withDelta
    Assert-True (-not $unreviewed.Valid) 'An unreviewed manifest change was accepted.'
    Assert-True ($unreviewed.Reason -like '*nobody reviewed*') `
        "The unreviewed manifest change was refused for the wrong reason: $($unreviewed.Reason)"

    $reviewed = Test-TestReceipt -Receipt $withDelta -Approved @('permission-added android.permission.VIBRATE')
    Assert-True $reviewed.Valid "A reviewed manifest change was refused: $($reviewed.Reason)"

    # The shape a real run hands over, rather than a literal @(). An allowlist file with no
    # entries reaches the validator as $null, and treating that null as an approved entry failed
    # every clean run with an empty list of changes nobody could read.
    $emptyFromFile = Join-Path $allowlistRoot 'empty.txt'
    Set-Content -LiteralPath $emptyFromFile -Encoding UTF8 -Value @('# nothing approved', '')
    $fromFile = Read-ManifestDeltaAllowlist -Path $emptyFromFile
    $clean = Test-TestReceipt -Receipt (New-TestReceipt) -Approved $fromFile
    Assert-True $clean.Valid `
        "A receipt with no manifest change failed against an empty allowlist: $($clean.Reason)"
    $cleanNull = Test-TestReceipt -Receipt (New-TestReceipt) -Approved $null
    Assert-True $cleanNull.Valid `
        "A receipt with no manifest change failed against a null allowlist: $($cleanNull.Reason)"

    $stale = Test-TestReceipt -Receipt (New-TestReceipt) -Approved @('permission-added android.permission.VIBRATE')
    Assert-True (-not $stale.Valid) 'An allowlist entry no patch produces was accepted.'
    Assert-True ($stale.Reason -like '*any more*') `
        "The stale allowlist entry was refused for the wrong reason: $($stale.Reason)"

    # The checked-in allowlist, against receipts that carry the three removals and the raised version
    # code on both builds. Those pass. They plus any other change are refused, naming only the other,
    # and a removal that stopped part way is refused for the part no patch makes any more.
    $withRemovals = {
        param($r)
        foreach ($target in $r.targets) {
            $target.manifestDelta.permissionsRemoved = @($removedPermissions)
            $target.manifestDelta.versionCodeChanged = @($raisedVersionCode)
        }
    }
    $removed = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $withRemovals) -Approved $checkedInAllowlist
    Assert-True $removed.Valid "A receipt carrying the approved advertising ID removals was refused: $($removed.Reason)"
    $unraised = Test-TestReceipt -Receipt (New-TestReceipt -Mutate {
            param($r)
            foreach ($target in $r.targets) { $target.manifestDelta.permissionsRemoved = @($removedPermissions) }
        }) -Approved $checkedInAllowlist
    Assert-True (-not $unraised.Valid -and $unraised.Reason -like "*any more: version-code $raisedVersionCode") `
        "A receipt whose version code was never raised passed the allowlist that approves raising it: $($unraised.Reason)"
    $unremoved = Test-TestReceipt -Receipt (New-TestReceipt) -Approved $checkedInAllowlist
    Assert-True (-not $unremoved.Valid -and $unremoved.Reason -like '*any more*') `
        "A receipt without the removals passed the allowlist that approves them: $($unremoved.Reason)"
    foreach ($other in @(
            @{ Name = 'another permission asked for'; Entry = 'permission-added android.permission.READ_SMS'
                Change = { param($t) $t.manifestDelta.permissionsAdded = @('android.permission.READ_SMS') } },
            @{ Name = 'another permission dropped'; Entry = 'permission-removed android.permission.CAMERA'
                Change = { param($t) $t.manifestDelta.permissionsRemoved = @($t.manifestDelta.permissionsRemoved) + 'android.permission.CAMERA' } },
            @{ Name = 'a component exported'; Entry = 'exported-added receiver:com.example.host.Probe'
                Change = { param($t) $t.manifestDelta.exportedComponentsAdded = @('receiver:com.example.host.Probe') } },
            @{ Name = 'a component no longer exported'; Entry = 'exported-removed activity:com.example.host.Main'
                Change = { param($t) $t.manifestDelta.exportedComponentsRemoved = @('activity:com.example.host.Main') } },
            @{ Name = 'another version code'; Entry = 'version-code 2147483646'
                Change = { param($t) $t.manifestDelta.versionCodeChanged = @('2147483646') } })) {
        $receipt = New-TestReceipt -Mutate { param($r) & $withRemovals $r; & $other.Change $r.targets[1] }
        $result = Test-TestReceipt -Receipt $receipt -Approved $checkedInAllowlist
        Assert-True (-not $result.Valid) "With the advertising ID removals approved, $($other.Name) was accepted."
        Assert-True ($result.Reason -like "*nobody reviewed: $($other.Entry)") `
            "With the advertising ID removals approved, $($other.Name) was refused for the wrong reason: $($result.Reason)"
    }
    $partWay = New-TestReceipt -Mutate {
        param($r)
        foreach ($target in $r.targets) {
            $target.manifestDelta.permissionsRemoved = @($removedPermissions | Select-Object -First 2)
            $target.manifestDelta.versionCodeChanged = @($raisedVersionCode)
        }
    }
    $part = Test-TestReceipt -Receipt $partWay -Approved $checkedInAllowlist
    Assert-True (-not $part.Valid -and $part.Reason -like '*any more*AD_ID*') `
        "Two removals of the three passed the allowlist of all three: $($part.Reason)"

    # The SBOM a receipt names. Each refusal has to name the SBOM fact that failed rather than trip
    # over the next field, or a check that went missing would pass unseen behind the one after it.
    foreach ($named in @(
            @{ Name = 'a receipt that names no SBOM'; Pattern = '*names no SBOM*' },
            @{ Name = 'an SBOM named for another version'; Pattern = '*names the SBOM patches-9.9.8.cdx.json; the one for 9.9.9 is patches-9.9.9.cdx.json*' },
            @{ Name = 'an SBOM with no hash'; Pattern = '*no SHA-256 for patches-9.9.9.cdx.json*' },
            @{ Name = 'an SBOM hash in lower case'; Pattern = '*no SHA-256 for patches-9.9.9.cdx.json*' },
            @{ Name = 'an SBOM counting no component'; Pattern = '*counts no component in patches-9.9.9.cdx.json*' })) {
        $result = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations[$named.Name])
        Assert-True ($result.Reason -like $named.Pattern) "$($named.Name) was refused for the wrong reason: $($result.Reason)"
    }

    # Read back, the SBOM gives what it was written with, and it describes the bundle beside it.
    $sbomRead = Read-ReleaseSbom -Path $sbomFile
    Assert-True ($sbomRead.BundleName -eq 'patches-9.9.9.mpp' -and $sbomRead.BundleVersion -eq '9.9.9' -and
        $sbomRead.BundleSha256 -ceq $bundleHash.ToLowerInvariant() -and $sbomRead.Timestamp -eq '2023-11-14T22:13:20Z' -and
        @($sbomRead.Components).Count -eq 3 -and @($sbomRead.Libraries).Count -eq 1 -and
        $sbomRead.Libraries[0].Purl -eq 'pkg:maven/com.google.code.gson/gson@2.14.0' -and $sbomRead.Libraries[0].Name -eq 'gson' -and
        @($sbomRead.Payloads).Count -eq 1 -and $sbomRead.Payloads[0].Ref -eq 'extensions/instagram.mpe' -and
        $sbomRead.Sha256 -eq (Get-Sha256Hex -Path $sbomFile)) `
        "The SBOM was misread: $($sbomRead.BundleName) $($sbomRead.BundleVersion) $($sbomRead.Timestamp), $(@($sbomRead.Components).Count) components"
    $bound = Test-ReleaseSbom -Sbom $sbomRead -BundlePath $bundle -BundleName 'patches-9.9.9.mpp'
    Assert-True $bound.Valid "An SBOM of the bundle was refused: $($bound.Reason)"

    # The receipt against the SBOM file itself: its hash and its count, and through it the bundle.
    function Test-ReceiptWithSbom($Receipt, [string]$Sbom = $sbomFile, [int]$Schema = (Get-ReleaseReceiptSchemaVersion)) {
        return Test-ReleaseReceipt -Receipt $Receipt -ExpectedVersion '9.9.9' -ExpectedPatchNames @('Alpha', 'Beta') `
            -ExpectedPatcherVersion '1.12.0' -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' `
            -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle -SbomPath $Sbom -ExpectedSchemaVersion $Schema
    }
    $withSbom = Test-ReceiptWithSbom (New-TestReceipt)
    Assert-True $withSbom.Valid "A receipt was refused against the SBOM it names: $($withSbom.Reason)"
    foreach ($wrong in @(
            @{ Name = 'an SBOM hash that is not the SBOM'; Mutate = { param($r) $r.sbom.sha256 = ('C' * 64) }; Pattern = '*says patches-9.9.9.cdx.json hashes to CCCC*' },
            @{ Name = 'a count that is not the SBOM''s'; Mutate = { param($r) $r.sbom.components = 4 }; Pattern = '*counts 4 components in patches-9.9.9.cdx.json; it lists 3*' })) {
        $result = Test-ReceiptWithSbom (New-TestReceipt -Mutate $wrong.Mutate)
        Assert-True (-not $result.Valid -and $result.Reason -like $wrong.Pattern) "A receipt with $($wrong.Name) was not refused for it: $($result.Reason)"
    }
    $missingSbom = Test-ReceiptWithSbom (New-TestReceipt) -Sbom (Join-Path $allowlistRoot 'absent.cdx.json')
    Assert-True ($missingSbom.Reason -like '*an SBOM that is not there*') "A receipt was checked against an SBOM that isn't there: $($missingSbom.Reason)"
    # An SBOM that isn't this bundle's, each way it can differ, with the receipt recording its hash
    # so that the difference is what refuses it.
    $variant = Join-Path $allowlistRoot 'variant\patches-9.9.9.cdx.json'
    New-Item -ItemType Directory -Path (Split-Path -Parent $variant) -Force | Out-Null
    foreach ($other in @(
            @{ Name = 'another bundle''s hash'; Mutate = { param($d) $d.metadata.component.hashes[0].content = ('0' * 64) }
                Pattern = '*describes a patches-9.9.9.mpp that hashes to 0000*written for another build*' },
            @{ Name = 'a date from the clock'; Mutate = { param($d) $d.metadata.timestamp = '2026-09-25T10:00:00Z' }
                Pattern = '*is dated 2026-09-25T10:00:00Z, and patches-9.9.9.mpp is stamped 2023-11-14T22:13:20Z*' },
            @{ Name = 'another version'; Mutate = { param($d) $d.metadata.component.version = '9.9.8' }; Pattern = '*says patches-9.9.9.mpp is version 9.9.8*' },
            @{ Name = 'another bundle''s name'; Mutate = { param($d) $d.metadata.component.name = 'patches-9.9.8.mpp' }; Pattern = '*describes patches-9.9.8.mpp, not patches-9.9.9.mpp*' },
            @{ Name = 'no payload'; Mutate = { param($d) $d.components = @($d.components | Where-Object { $_.type -ne 'file' }) }
                Pattern = '*carries extensions/instagram.mpe, which patches-9.9.9.cdx.json doesn''t describe*' },
            @{ Name = 'another payload hash'; Mutate = { param($d) @($d.components | Where-Object { $_.type -eq 'file' })[0].hashes[0].content = ('1' * 64) }
                Pattern = '*describes extensions/instagram.mpe hashing to 1111*' },
            @{ Name = 'a payload the bundle lacks'; Mutate = { param($d) $d.components = @($d.components) + @([ordered]@{ type = 'file'; 'bom-ref' = 'extensions/shared.mpe'
                    name = 'extensions/shared.mpe'; version = '9.9.9'; scope = 'required'; hashes = @([ordered]@{ alg = 'SHA-256'; content = ('2' * 64) }) }) }
                Pattern = '*describes extensions/shared.mpe, which patches-9.9.9.mpp doesn''t carry*' })) {
        New-TestSbom -Path $variant -Bundle $bundle -Mutate $other.Mutate
        $variantCount = @((Get-Content -LiteralPath $variant -Raw | ConvertFrom-Json).components).Count
        $result = Test-ReceiptWithSbom (New-TestReceipt -Mutate {
            param($r) $r.sbom.sha256 = Get-Sha256Hex -Path $variant; $r.sbom.components = $variantCount }) -Sbom $variant
        Assert-True (-not $result.Valid -and $result.Reason -like $other.Pattern) `
            "An SBOM with $($other.Name) was taken for the bundle's: $($result.Reason)"
    }

    # The SBOM's own shape. OSV answers {} for a package URL it can't read, so a library that isn't
    # asked about under its own group, name and version would read as having no advisory.
    foreach ($broken in @(
            @{ Name = 'another format'; Mutate = { param($d) $d.bomFormat = 'SPDX' }; Pattern = '*is not a CycloneDX 1.6 SBOM*' },
            @{ Name = 'another spec version'; Mutate = { param($d) $d.specVersion = '1.5' }; Pattern = '*is not a CycloneDX 1.6 SBOM*' },
            @{ Name = 'no bundle hash'; Mutate = { param($d) $d.metadata.component.Remove('hashes') }; Pattern = '*does not name the bundle it describes*' },
            @{ Name = 'a package URL for another version'; Mutate = { param($d) $d.components[0].purl = 'pkg:maven/com.google.code.gson/gson@2.8.8' }
                Pattern = '*with the package URL pkg:maven/com.google.code.gson/gson@2.8.8, which doesn''t name its own group, name and version*' },
            @{ Name = 'a package URL with no group'; Mutate = { param($d) $d.components[0].purl = 'pkg:maven/gson@2.14.0' }
                Pattern = '*with the package URL pkg:maven/gson@2.14.0, which doesn''t name*' },
            @{ Name = 'a package URL for another group'; Mutate = { param($d) $d.components[0].purl = 'pkg:maven/com.google.gson/gson@2.14.0' }
                Pattern = '*with the package URL pkg:maven/com.google.gson/gson@2.14.0, which doesn''t name*' },
            @{ Name = 'a package URL for another name'; Mutate = { param($d) $d.components[0].purl = 'pkg:maven/com.google.code.gson/gson-extras@2.14.0' }
                Pattern = '*with the package URL pkg:maven/com.google.code.gson/gson-extras@2.14.0, which doesn''t name*' },
            @{ Name = 'something that is not a package URL, beside empty fields'
                Mutate = { param($d) $d.components[0].purl = 'not-a-package-url'; $d.components[0].group = ''; $d.components[0].name = ''; $d.components[0].version = '' }
                Pattern = '*with the package URL not-a-package-url, which doesn''t name*' },
            @{ Name = 'a library with no package URL'; Mutate = { param($d) $d.components[0].Remove('purl') }; Pattern = '*the library pkg:maven/com.google.code.gson/gson@2.14.0 with no package URL*' },
            @{ Name = 'the same component twice'; Mutate = { param($d) $d.components = @($d.components) + @($d.components[0]) }; Pattern = '*lists pkg:maven/com.google.code.gson/gson@2.14.0 twice*' },
            @{ Name = 'a component of another type'; Mutate = { param($d) $d.components[0].type = 'framework' }; Pattern = '*as a framework, which a release SBOM doesn''t hold*' },
            @{ Name = 'a payload with no hash'; Mutate = { param($d) @($d.components | Where-Object { $_.type -eq 'file' })[0].Remove('hashes') }; Pattern = '*the file extensions/instagram.mpe with no SHA-256*' },
            @{ Name = 'no component'; Mutate = { param($d) $d.components = @() }; Pattern = '*lists no component*' })) {
        New-TestSbom -Path $variant -Bundle $bundle -Mutate $broken.Mutate
        Assert-Throws { Read-ReleaseSbom -Path $variant } $broken.Pattern "An SBOM with $($broken.Name) was read without complaint."
    }
    Set-Content -LiteralPath $variant -Encoding ASCII -Value 'not json'
    Assert-Throws { Read-ReleaseSbom -Path $variant } '*patches-9.9.9.cdx.json is not JSON*' 'Text that is not JSON was read as an SBOM.'

    # A receipt cut before schema 2 names no SBOM, and is read as its own commit wrote it; each
    # schema is refused where the other is expected.
    $schemaOne = New-TestReceipt -Mutate { param($r) $r.schemaVersion = 1; $r.PSObject.Properties.Remove('sbom') }
    $oneAtOne = Test-ReleaseReceipt -Receipt $schemaOne -ExpectedVersion '9.9.9' -ExpectedPatchNames @('Alpha', 'Beta') `
        -ExpectedPatcherVersion '1.12.0' -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' `
        -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle -ExpectedSchemaVersion 1
    Assert-True $oneAtOne.Valid "A schema 1 receipt was refused at schema 1: $($oneAtOne.Reason)"
    $oneAtTwo = Test-ReceiptWithSbom $schemaOne -Schema 2
    Assert-True ($oneAtTwo.Reason -like '*schema version 1; its release is read at version 2*') `
        "A schema 1 receipt was not refused where schema 2 is expected: $($oneAtTwo.Reason)"
    $schemaTwo = New-TestReceipt -Mutate { param($r) $r.schemaVersion = 2
        foreach ($target in $r.targets) { $target.PSObject.Properties.Remove('coverage'); $target.PSObject.Properties.Remove('coverageReviewed') } }
    $twoAtOne = Test-ReceiptWithSbom $schemaTwo -Schema 1
    Assert-True ($twoAtOne.Reason -like '*schema version 2; its release is read at version 1*') `
        "A schema 2 receipt was not refused where schema 1 is expected: $($twoAtOne.Reason)"
    $oneWithSbom = Test-ReceiptWithSbom $schemaOne -Schema 1
    Assert-True ($oneWithSbom.Reason -like '*schema 1 receipt names no SBOM to hold*') `
        "A schema 1 receipt was held to an SBOM it can't name: $($oneWithSbom.Reason)"
    $twoAtTwo = Test-ReceiptWithSbom $schemaTwo -Schema 2
    Assert-True $twoAtTwo.Valid "A historical schema 2 receipt was refused: $($twoAtTwo.Reason)"
    $twoAtThree = Test-TestReceipt $schemaTwo
    Assert-True (-not $twoAtThree.Valid) 'A historical receipt certified schema 3 coverage it never recorded.'

    # A family may apply yet miss a subtarget. Certification must reject a required absence,
    # preserve an explicitly optional absence, and never take a forged count as evidence.
    $partial = [pscustomobject]@{ family = 'disableAnalytics'; matched = 2; expected = 3
        targets = @('alpha', 'beta', 'gamma'); missing = @('gamma') }
    $policy = [pscustomobject]@{ schemaVersion = 1; fixtures = @([pscustomobject]@{
        package = 'com.example.host'; versionName = '46.7.3'; versionCode = '2024607030'
        families = @([pscustomobject]@{ family = 'disableAnalytics'; patch = 'Disable analytics'
            targets = @('alpha', 'beta', 'gamma'); optional = @('gamma') }) }) }
    $arguments = @{ Names = @('Disable analytics'); Package = 'com.example.host'; VersionName = '46.7.3'
        VersionCode = '2024607030'; Policy = $policy }
    $optional = Test-TargetCoverage -Coverage @($partial) @arguments
    Assert-True ($optional.Valid -and $optional.Reviewed -and $partial.missing[0] -ceq 'gamma') `
        'Optional partial coverage disappeared or was not certified against its reviewed rule.'
    $policy.fixtures[0].families[0].optional = @()
    $required = Test-TargetCoverage -Coverage @($partial) @arguments
    Assert-True (-not $required.Valid -and $required.Reason -like '*Required*gamma*') `
        'A successful family with a missing required target was certified.'
    $policy.fixtures[0].families[0].optional = @('gamma')
    foreach ($mutation in @(
        { param($e) $e.matched = 0; $e.missing = @('alpha', 'beta', 'gamma') },
        { param($e) $e.matched = 3 }, { param($e) $e.expected = 99 },
        { param($e) $e.matched = '2' }, { param($e) $e.missing = @('unknown') },
        { param($e) $e.targets = @('alpha', 'alpha', 'gamma') },
        { param($e) $e.targets = @('alpha', 'https://account/private', 'gamma') })) {
        $invalid = $partial | ConvertTo-Json -Depth 5 | ConvertFrom-Json
        & $mutation $invalid
        Assert-True (-not (Test-TargetCoverage -Coverage @($invalid) @arguments).Valid) 'Invalid coverage passed certification.'
    }
    Assert-True (-not (Test-TargetCoverage -Coverage @($partial, $partial) @arguments).Valid) 'Duplicate coverage passed.'
    Assert-True (-not (Test-TargetCoverage -Coverage @() @arguments).Valid) 'Absent selected-family metadata passed.'
    $arguments.VersionCode = 'different'
    $unknown = Test-TargetCoverage -Coverage @($partial) @arguments
    Assert-True ($unknown.Valid -and -not $unknown.Reviewed) 'Another version code claimed reviewed fixture coverage.'

    # Exercise the receipt validator itself with the real reviewed fixture and target labels. The
    # build comes from the expectations file, so moving the target moves this case with it.
    $reviewedFixture = @((Get-Content (Join-Path $PSScriptRoot 'patch-coverage-expectations.json') -Raw | ConvertFrom-Json).fixtures)[0]
    $reviewedName = [string]$reviewedFixture.versionName; $reviewedCode = [string]$reviewedFixture.versionCode
    $coverageReceipt = New-TestReceipt
    $coverageReceipt.release.patchCount = 1
    $coverageReceipt.targets = @($coverageReceipt.targets[0])
    $coverageTarget = $coverageReceipt.targets[0]
    $coverageTarget.source.package = 'com.instagram.android'
    $coverageTarget.source.versionName = $reviewedName
    $coverageTarget.source.versionCode = $reviewedCode
    $coverageTarget.patches = @([pscustomobject]@{ name = 'Disable analytics'; applied = $true; reason = $null })
    $coverageTarget.coverage = @([pscustomobject]@{ family = 'disableAnalytics'; matched = 7; expected = 7
        targets = @('builder', 'graph', 'mqtt', 'reports', 'pings', 'stream', 'setup'); missing = @() })
    $receiptArguments = @{ ExpectedVersion = '9.9.9'; ExpectedPatchNames = @('Disable analytics')
        ExpectedPatcherVersion = '1.12.0'; ExpectedManagerFloor = '1.29.0'; ExpectedPackageName = 'com.instagram.android'
        ExpectedPackageVersions = @($reviewedName); ExpectedPackageVersionCodes = @{ $reviewedName = @($reviewedCode) }
        BundlePath = $bundle }
    $certified = Test-ReleaseReceipt -Receipt $coverageReceipt @receiptArguments
    Assert-True $certified.Valid "The reviewed coverage receipt failed: $($certified.Reason)"
    $coverageTarget.coverage[0].matched = 6; $coverageTarget.coverage[0].missing = @('mqtt')
    $lost = Test-ReleaseReceipt -Receipt $coverageReceipt @receiptArguments
    Assert-True (-not $lost.Valid -and $lost.Reason -like '*Required*mqtt*') 'A receipt hid missing required coverage.'
    $coverageTarget.coverage[0].matched = 7
    Assert-True (-not (Test-ReleaseReceipt -Receipt $coverageReceipt @receiptArguments).Valid) 'Forged receipt counts passed.'
} finally {
    Remove-Item -LiteralPath $allowlistRoot -Recurse -Force -ErrorAction SilentlyContinue
}

# Which catalog a receipt is judged against. A receipt describes a release that has shipped, so
# moving the patcher pin afterwards must not turn it into a failure; a release, whose receipt is
# cut against the catalog it was built from, must still be held to that catalog exactly.
#
# Driven against a real two-commit repository, because the whole question is what `git show` says
# at a commit and a fake cannot answer that.
#
# Every git call below goes through Invoke-FixtureGit, and that is not tidiness. In Hushfacebook on 2026-09-15
# this block ran from inside the pre-push hook, which is a git child process, so GIT_DIR and
# GIT_WORK_TREE were in its environment. `git -C <tempdir>` changes the working directory and
# does not override GIT_DIR, so `init` reused the real repository, `add -A` read the two-file
# temp tree through it and staged every other tracked file as deleted, and three fixture commits
# authored by Contracts landed on the branch and were pushed to main, where the tip deleted all
# 703 files. Clearing the environment is the fix; the assertion after init is what would have
# stopped it in the second it happened.
$toolchainRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushgram-toolchain-" + [guid]::NewGuid().ToString('N'))
$toolchainRemote = "$toolchainRoot-published.git"

function Invoke-FixtureGit {
    <#
    .SYNOPSIS
        git against a fixture repository, with no inherited git environment.
    .DESCRIPTION
        Removes every GIT_* variable for the length of the call, so the repository git acts on is
        the one -C names and nothing else. Run from a hook, GIT_DIR alone is enough to point all
        of this at the real tree.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [long]$CommitEpoch
    )

    $saved = @{}
    foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
        $saved[$variable.Name] = $variable.Value
        Remove-Item -LiteralPath ('Env:\' + $variable.Name) -ErrorAction SilentlyContinue
    }
    try {
        if ($PSBoundParameters.ContainsKey('CommitEpoch')) {
            $env:GIT_AUTHOR_DATE = "@$CommitEpoch +0000"
            $env:GIT_COMMITTER_DATE = "@$CommitEpoch +0000"
        }
        return & git -C $Root @Arguments 2>&1
    } finally {
        if ($PSBoundParameters.ContainsKey('CommitEpoch')) {
            Remove-Item Env:GIT_AUTHOR_DATE, Env:GIT_COMMITTER_DATE -ErrorAction SilentlyContinue
        }
        foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
    }
}

try {
    New-Item -ItemType Directory -Path (Join-Path $toolchainRoot 'gradle') -Force | Out-Null
    $catalogFile = Join-Path $toolchainRoot 'gradle/libs.versions.toml'
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('init', '--quiet') | Out-Null

    # Where git says it will write, asked before anything is written. A fixture that has taken
    # hold of the real repository fails here instead of committing to it.
    $fixtureGitDir = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', '--absolute-git-dir') |
        Select-Object -First 1)".Trim()
    $expectedGitDir = (Join-Path $toolchainRoot '.git')
    Assert-True ($fixtureGitDir -and
        ([IO.Path]::GetFullPath($fixtureGitDir).TrimEnd('\', '/') -ieq [IO.Path]::GetFullPath($expectedGitDir).TrimEnd('\', '/'))) `
        ("The fixture repository resolved to $fixtureGitDir, not $expectedGitDir. Refusing to " +
            'write: this is the shape that put three fixture commits on the real branch.')

    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('config', 'user.email', 'contracts@example.invalid') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('config', 'user.name', 'Contracts') | Out-Null

    Set-Content -LiteralPath $catalogFile -Encoding UTF8 -Value @(
        '[versions]', 'morphe-patcher = "1.12.0"', 'manager-floor = "1.29.0"')
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'release', '--quiet') | Out-Null
    $releaseCommitSha = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') |
        Select-Object -First 1)".Trim()

    # One file in the fixture, so one file in its first commit. A commit that carries hundreds is
    # a commit against somebody else's repository.
    $firstCommitFiles = @(Invoke-FixtureGit -Root $toolchainRoot `
        -Arguments @('show', '--name-only', '--format=', 'HEAD') | Where-Object { "$_".Trim() })
    Assert-True ($firstCommitFiles.Count -eq 1 -and "$($firstCommitFiles[0])".Trim() -eq 'gradle/libs.versions.toml') `
        ("The fixture's first commit touched $($firstCommitFiles.Count) files: " +
            (($firstCommitFiles | Select-Object -First 5) -join ', '))

    Set-Content -LiteralPath $catalogFile -Encoding UTF8 -Value @(
        '[versions]', 'morphe-patcher = "1.13.0"', 'manager-floor = "1.30.0"')
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'move the pin', '--quiet') | Out-Null

    $workingToolchain = Read-CatalogToolchain -Text (Get-Content -LiteralPath $catalogFile -Raw) `
        -Source 'the working catalog'
    Assert-True ($workingToolchain.PatcherVersion -eq '1.13.0' -and $workingToolchain.ManagerFloor -eq '1.30.0') `
        "The working catalog was not read: $($workingToolchain.PatcherVersion), $($workingToolchain.ManagerFloor)"

    # The source push after the pin moved: the receipt's own commit still pinned 1.12.0, so that
    # is what it answers for, and the push this used to stop now goes through.
    $atRelease = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit $releaseCommitSha `
        -WorkingToolchain $workingToolchain
    Assert-True ($atRelease.Toolchain.PatcherVersion -eq '1.12.0') `
        "The receipt was not held to the patcher its own commit pinned: $($atRelease.Toolchain.PatcherVersion)"
    Assert-True ($atRelease.Toolchain.ManagerFloor -eq '1.29.0') `
        "The receipt was not held to the Manager floor its own commit pinned: $($atRelease.Toolchain.ManagerFloor)"
    Assert-True ($atRelease.Note -like '*1.12.0*' -and $atRelease.Note -like '*1.13.0*') `
        "The difference between the two catalogs was not reported: $($atRelease.Note)"

    # The release push: the receipt's commit is the commit being released, so the catalog it is
    # held to is the working one and the strict comparison is unchanged. Without this case the
    # one above would pass just as well if the check had been turned off.
    $head = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') |
        Select-Object -First 1)".Trim()
    $atHead = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit $head -WorkingToolchain $workingToolchain
    Assert-True ($atHead.Toolchain.PatcherVersion -eq '1.13.0' -and $atHead.Toolchain.ManagerFloor -eq '1.30.0') `
        "A receipt at the released commit was not held to that commit's catalog: $($atHead.Toolchain.PatcherVersion)"
    Assert-True ($null -eq $atHead.Note) "An unchanged catalog still reported a difference: $($atHead.Note)"

    # The floor the index description is held to: the published release's own, through its tag,
    # while the working catalog already pins the next one. An untagged version isn't guessed at.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('tag', 'v0.0.9', $releaseCommitSha) | Out-Null
    $indexFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.9'
    Assert-True ($indexFloor.Floor -eq '1.29.0' -and $indexFloor.Source -eq 'tag v0.0.9') `
        "The index floor was not read at the release tag: $($indexFloor.Floor) from $($indexFloor.Source)"
    $untagged = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.10'
    Assert-True ($null -eq $untagged.Floor -and $untagged.Note -like "*v0.0.10 isn't in this clone*") `
        "An untagged release was given a floor: $($untagged.Floor), $($untagged.Note)"
    # A commit the caller read off the remote wins over the clone's tag, which still names the old
    # commit after a release is re-cut on GitHub.
    $recutFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.9' -Commit $head
    Assert-True ($recutFloor.Floor -eq '1.30.0') "The clone's tag was read over the published commit: $($recutFloor.Floor)"
    # With no tag in the clone the remote is asked, since `gh release create` makes the tag on
    # GitHub only. A bare repository stands in for it: a lightweight tag, an annotated one read
    # through the commit it peels to, and one on a commit this clone doesn't have. The annotated
    # tag is made on the remote alone, so this clone has its commit and never saw the tag object:
    # only the peeled line leads to a commit here.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('init', '--bare', '--quiet', $toolchainRemote) | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('push', '--quiet', $toolchainRemote,
        "${releaseCommitSha}:refs/tags/v0.0.12", "${head}:refs/heads/main") | Out-Null
    Invoke-FixtureGit -Root $toolchainRemote -Arguments @('-c', 'user.name=Contracts', '-c', 'user.email=contracts@example.invalid',
        'tag', '-a', '-m', 'release', 'v0.0.13', $head) | Out-Null
    $onlyThere = "$(Invoke-FixtureGit -Root $toolchainRemote -Arguments @('-c', 'user.name=Contracts',
        '-c', 'user.email=contracts@example.invalid', 'commit-tree', "$head^{tree}", '-m', 'only there') |
        Select-Object -First 1)".Trim()
    Invoke-FixtureGit -Root $toolchainRemote -Arguments @('tag', 'v0.0.14', $onlyThere) | Out-Null
    $remoteFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.12' -RemoteUrl $toolchainRemote
    Assert-True ($remoteFloor.Floor -eq '1.29.0' -and $remoteFloor.Source -eq "tag v0.0.12 on $toolchainRemote") `
        "A tag only the remote has was not read there: $($remoteFloor.Floor) from $($remoteFloor.Source), $($remoteFloor.Note)"
    $peeledFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.13' -RemoteUrl $toolchainRemote
    Assert-True ($peeledFloor.Floor -eq '1.30.0') `
        "An annotated tag only the remote has was not read at its commit: $($peeledFloor.Floor), $($peeledFloor.Note)"
    foreach ($unread in @(
            @{ Version = '0.0.14'; Remote = $toolchainRemote; Note = "*names commit $($onlyThere.Substring(0, 8)), which this clone doesn't have*" },
            @{ Version = '0.0.15'; Remote = $toolchainRemote; Note = "*v0.0.15 isn't in this clone or on $toolchainRemote*" },
            @{ Version = '0.0.15'; Remote = (Join-Path $toolchainRoot 'missing.git'); Note = "*couldn't be asked for it*" })) {
        $unreadFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version $unread.Version -RemoteUrl $unread.Remote
        Assert-True ($null -eq $unreadFloor.Floor -and $unreadFloor.Note -like $unread.Note) `
            "A floor the remote couldn't give was not reported as unchecked: $($unreadFloor.Floor), $($unreadFloor.Note)"
    }
    # The clone's tag can be stale: a release re-cut on GitHub moves the tag there, and git fetch
    # won't move it here. With a remote named, the published tag is read first. The clone's v0.0.9
    # still names the release commit, and the remote's names the next one.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('push', '--quiet', $toolchainRemote, "${head}:refs/tags/v0.0.9") | Out-Null
    $publishedFirst = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.9' -RemoteUrl $toolchainRemote
    Assert-True ($publishedFirst.Floor -eq '1.30.0' -and $publishedFirst.Source -eq "tag v0.0.9 on $toolchainRemote") `
        "The clone's own tag was read over the published one: $($publishedFirst.Floor) from $($publishedFirst.Source)"

    # A commit with no catalog in it, and a receipt naming no commit at all. Both fall back to
    # the working catalog rather than throwing, and the first says so.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rm', '--quiet', '--', 'gradle/libs.versions.toml') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'no catalog', '--quiet') | Out-Null
    $bare = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') |
        Select-Object -First 1)".Trim()
    $atBare = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit $bare -WorkingToolchain $workingToolchain
    Assert-True ($atBare.Toolchain.PatcherVersion -eq '1.13.0') `
        'A commit with no catalog did not fall back to the working one.'
    Assert-True ($atBare.Note -like '*no version catalog*') `
        "The fallback was not reported: $($atBare.Note)"
    # The index floor doesn't fall back: the working catalog is the wrong answer for a release.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('tag', 'v0.0.11', $bare) | Out-Null
    $bareFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.11'
    Assert-True ($null -eq $bareFloor.Floor -and $bareFloor.Note -like '*no version catalog*') `
        "A tag with no catalog was given a floor: $($bareFloor.Floor), $($bareFloor.Note)"

    $noCommit = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit '' -WorkingToolchain $workingToolchain
    Assert-True ($noCommit.Toolchain.PatcherVersion -eq '1.13.0' -and $null -eq $noCommit.Note) `
        'A receipt naming no commit did not fall back quietly to the working catalog.'

    # The patch list, the same way: a patch renamed after the release is held to the name its own
    # commit carried, which is what a release hold needs; the released commit itself is
    # held to the working list; no list at that commit, or no commit, falls back to the working one.
    $listFile = Join-Path $toolchainRoot 'patches-list.json'
    function Save-FixtureList([string[]]$Names) {
        $list = @{ patches = @($Names | ForEach-Object { @{ name = $_; compatiblePackages = @{ 'com.example' = @('1.0.0') } } }) }
        Set-Content -LiteralPath $listFile -Encoding UTF8 -Value ($list | ConvertTo-Json -Depth 5)
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', "list $($Names -join ' ')", '--quiet') | Out-Null
        return "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    }
    $listReleased = Save-FixtureList @('Alpha', 'Beta')
    $listNow = Save-FixtureList @('Alpha', 'Gamma')
    $workingList = Get-Content -LiteralPath $listFile -Raw | ConvertFrom-Json
    $atListRelease = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit $listReleased -WorkingPatchList $workingList
    $namesAtRelease = @($atListRelease.PatchList.patches | ForEach-Object { [string]$_.name }) -join ','
    Assert-True ($namesAtRelease -eq 'Alpha,Beta' -and $atListRelease.Note -like '*2 patches*') `
        "The receipt was not held to the patch list its own commit carried: $namesAtRelease / $($atListRelease.Note)"
    $atListHead = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit $listNow -WorkingPatchList $workingList
    Assert-True ((@($atListHead.PatchList.patches | ForEach-Object { [string]$_.name }) -join ',') -eq 'Alpha,Gamma' -and
        $null -eq $atListHead.Note) "A receipt at the released commit was not held to the working patch list: $($atListHead.Note)"
    $atNoList = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit $releaseCommitSha -WorkingPatchList $workingList
    Assert-True ($atNoList.PatchList -eq $workingList -and $atNoList.Note -like '*no patch list*') `
        "A commit with no patch list did not fall back to the working one: $($atNoList.Note)"
    $atNoCommit = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit '' -WorkingPatchList $workingList
    Assert-True ($atNoCommit.PatchList -eq $workingList -and $null -eq $atNoCommit.Note) `
        'A receipt naming no commit did not fall back quietly to the working patch list.'
    # And the release check hands the receipt that list, names and target both, rather than the
    # working one. A helper nothing calls would pass every case above. Anchored at the end, since
    # the unanchored pattern also matched PackageVersions[0], the newest build alone; the release
    # root section below runs the check on a receipt of its own as well.
    $factsSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'validate-release-facts.ps1') -Raw
    Assert-True ($factsSource -match '-ExpectedPatchNames @\(\$resolvedList\.PatchList\.patches' -and
        $factsSource -match '\$receiptTarget = Get-PatchTarget -PatchList \$resolvedList\.PatchList' -and
        $factsSource -match '-ExpectedPackageName \$receiptTarget\.PackageName' -and
        $factsSource -match '-ExpectedPackageVersions \$receiptTarget\.PackageVersions(?![\w.\[])' -and
        $factsSource -match '-ExpectedPackageVersionCodes \$receiptTarget\.PackageVersionCodes(?![\w.\[])') `
        ('validate-release-facts.ps1 no longer holds the receipt to the patch list its own commit ' +
            'carried, or to every build that list declares, version codes and all.')

    # The manifest delta allowlist, the same way: the one the receipt's own commit carried. An entry
    # added in the working tree and never committed approved a change into a release no commit had
    # reviewed, and one pruned after a release refused the release that needed it. A commit with no
    # allowlist, or no commit, gets the working one, the first with a note, and a malformed line at
    # the commit says where it is. Written without a byte order mark, as the repository's is, and
    # then with one, which git hands back as text.
    $allowlistFile = Join-Path $toolchainRoot 'scripts/manifest-delta-allowlist.txt'
    New-Item -ItemType Directory -Path (Split-Path -Parent $allowlistFile) -Force | Out-Null
    function Save-FixtureAllowlist([string[]]$Lines, [switch]$Bom, [switch]$Commit) {
        [System.IO.File]::WriteAllText($allowlistFile, (($Lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($Bom.IsPresent)))
        if (-not $Commit) { return $null }
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'allowlist', '--quiet') | Out-Null
        return "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    }
    $reviewed = 'exported-added activity:com.example.Reviewed'
    $unreviewed = 'exported-added activity:com.example.Unreviewed'
    foreach ($bom in $false, $true) {
        $allowlistCommit = Save-FixtureAllowlist @('# reviewed at the release', $reviewed) -Bom:$bom -Commit
        Save-FixtureAllowlist @('# added since, never committed', $unreviewed) | Out-Null
        $atAllowlist = Resolve-ReceiptManifestAllowlist -Root $toolchainRoot -Commit $allowlistCommit -WorkingPath $allowlistFile
        Assert-True ((@($atAllowlist.Entries) -join ',') -ceq $reviewed -and
            $atAllowlist.Note -like "*allowlist at its own commit $($allowlistCommit.Substring(0, 8)) reviews*") `
            "The receipt was not held to the allowlist its own commit carried (byte order mark: $bom): $(@($atAllowlist.Entries) -join ', ') / $($atAllowlist.Note)"
    }
    # The one with a byte order mark again, under a console on code page 437, which a hook's pwsh
    # gets when the push starts in Git Bash. PowerShell decoded git's UTF-8 with it, the mark came
    # back as U+2229 U+2557 U+2510, and the allowlist's first line was refused as malformed. The
    # console's own encoding has to be back once the read is done. (Hushfacebook 007c32a6.)
    $consoleEncoding = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = [System.Text.Encoding]::GetEncoding(437)
        $allowlistCommit = Save-FixtureAllowlist @('# reviewed at the release', $reviewed) -Bom -Commit
        Save-FixtureAllowlist @('# added since, never committed', $unreviewed) | Out-Null
        $oemAllowlist = $null
        $oemFailure = $null
        try {
            $oemAllowlist = Resolve-ReceiptManifestAllowlist -Root $toolchainRoot -Commit $allowlistCommit -WorkingPath $allowlistFile
        } catch {
            $oemFailure = $_.Exception.Message
        }
        $oemAfter = [Console]::OutputEncoding.CodePage
    } finally {
        [Console]::OutputEncoding = $consoleEncoding
    }
    Assert-True ($null -eq $oemFailure -and (@($oemAllowlist.Entries) -join ',') -ceq $reviewed) `
        "An allowlist with a byte order mark was misread under a code page 437 console: $oemFailure $(@($oemAllowlist.Entries) -join ', ')"
    Assert-True ($oemAfter -eq 437) "Reading git's output left the console on code page $oemAfter instead of putting 437 back."
    # The same helper in a process with no console, a detached start or a service. There Windows
    # PowerShell 5.1's setter throws "The handle is invalid." before git runs, while pwsh 7 keeps
    # the value. Each edition installed here gets a child that detaches from its console and runs
    # the helper. The child starts no program of its own after that: Windows gives a console
    # program started from a process with no console a console window of its own. So it checks the
    # encoding PowerShell decodes a native command's output with, inside the block and after it.
    # (Hushfacebook 3716793a.)
    $noConsoleChild = Join-Path $toolchainRoot 'no-console-child.ps1'
    Set-Content -LiteralPath $noConsoleChild -Encoding UTF8 -Value @'
param([string]$Common, [string]$Result)
$ErrorActionPreference = 'Stop'
. $Common
Add-Type -Namespace ContractNoConsole -Name Kernel -MemberDefinition '[System.Runtime.InteropServices.DllImport("kernel32.dll")] public static extern bool FreeConsole();'
$lines = @('detached ' + [ContractNoConsole.Kernel]::FreeConsole())
$before = [Console]::OutputEncoding.CodePage
try {
    $inside = Use-Utf8ConsoleOutput { [Console]::OutputEncoding.CodePage }
    $lines += 'inside ' + $inside
} catch {
    $lines += 'threw ' + $_.Exception.Message
}
$lines += 'after ' + [Console]::OutputEncoding.CodePage + ' before ' + $before
Set-Content -LiteralPath $Result -Value $lines
'@
    $noConsoleShells = @((Get-Process -Id $PID).Path)
    $otherEdition = if ($PSVersionTable.PSEdition -eq 'Core') {
        Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    } else {
        (Get-Command pwsh -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    }
    if ($otherEdition -and (Test-Path -LiteralPath $otherEdition)) { $noConsoleShells += $otherEdition }
    foreach ($noConsoleShell in $noConsoleShells) {
        $noConsoleResult = Join-Path $toolchainRoot ('no-console-' + [guid]::NewGuid().ToString('N') + '.txt')
        $preference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & $noConsoleShell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $noConsoleChild `
                -Common (Join-Path $PSScriptRoot 'common.ps1') -Result $noConsoleResult 2>&1 | Out-Null
        } finally {
            $ErrorActionPreference = $preference
        }
        $noConsole = if (Test-Path -LiteralPath $noConsoleResult) { @(Get-Content -LiteralPath $noConsoleResult) } else { @() }
        $noConsoleBefore = if ($noConsole.Count -eq 3 -and $noConsole[2] -match ' before (\d+)$') { $Matches[1] } else { '?' }
        Assert-True ($noConsole.Count -eq 3 -and $noConsole[0] -eq 'detached True' -and $noConsole[1] -eq 'inside 65001' -and
            $noConsole[2] -eq "after $noConsoleBefore before $noConsoleBefore") `
            "Use-Utf8ConsoleOutput with no console under $noConsoleShell did not read UTF-8 and put the encoding back: $($noConsole -join ' / ')"
    }
    $atNoAllowlist =Resolve-ReceiptManifestAllowlist -Root $toolchainRoot -Commit $releaseCommitSha -WorkingPath $allowlistFile
    Assert-True ((@($atNoAllowlist.Entries) -join ',') -ceq $unreviewed -and $atNoAllowlist.Note -like '*has no manifest delta allowlist*') `
        "A commit with no allowlist did not fall back to the working one, saying so: $($atNoAllowlist.Note)"
    $noCommitAllowlist = Resolve-ReceiptManifestAllowlist -Root $toolchainRoot -Commit '' -WorkingPath $allowlistFile
    Assert-True ((@($noCommitAllowlist.Entries) -join ',') -ceq $unreviewed -and $null -eq $noCommitAllowlist.Note) `
        'A receipt naming no commit was not held quietly to the working allowlist.'
    $sameAllowlist = Resolve-ReceiptManifestAllowlist -Root $toolchainRoot -Commit (Save-FixtureAllowlist @($unreviewed) -Commit) `
        -WorkingPath $allowlistFile
    Assert-True ((@($sameAllowlist.Entries) -join ',') -ceq $unreviewed -and $null -eq $sameAllowlist.Note) `
        "An allowlist the working tree still holds as committed reported a difference: $($sameAllowlist.Note)"
    $brokenAllowlist = Save-FixtureAllowlist @('exported-added') -Commit
    Assert-Throws { Resolve-ReceiptManifestAllowlist -Root $toolchainRoot -Commit $brokenAllowlist -WorkingPath $allowlistFile } `
        "*allowlist at $($brokenAllowlist.Substring(0, 8)) has a line that is not*" `
        'A malformed allowlist at the receipt''s commit was read without complaint.'
    Assert-True ($factsSource -match '\$resolvedAllowlist = Resolve-ReceiptManifestAllowlist -Root \$rootPath -Commit \$receiptCommit' -and
        $factsSource -match '\$approvedDelta = @\(\$resolvedAllowlist\.Entries\)' -and
        $factsSource -match '-ApprovedManifestDelta \$approvedDelta' -and
        $factsSource -notmatch 'Read-ManifestDeltaAllowlist') `
        'validate-release-facts.ps1 no longer holds the receipt to the allowlist its own commit carried.'

    # The receipt schema, read the same way: out of scripts/release-receipt.ps1 at the receipt's
    # commit. A release cut before the SBOM is held to schema 1 and says so, one cut since to this
    # checkout's schema, one from a newer checkout is refused rather than misread, and a commit
    # with no receipt script, or none at all, gets this checkout's.
    $receiptScript = Join-Path $toolchainRoot 'scripts/release-receipt.ps1'
    New-Item -ItemType Directory -Path (Split-Path -Parent $receiptScript) -Force | Out-Null
    $currentSchema = Get-ReleaseReceiptSchemaVersion
    $schemaCommits = @{}
    foreach ($written in @(1, $currentSchema, ($currentSchema + 1))) {
        Set-Content -LiteralPath $receiptScript -Encoding UTF8 -Value @('function Get-ReleaseReceiptSchemaVersion {', '    <#',
            '    .SYNOPSIS', '        Bumped when the shape changes. A receipt at return 9 would be a surprise.', '    #>',
            "    return $written", '}')
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', "schema $written", '--quiet') | Out-Null
        $schemaCommits[$written] = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    }
    $atOne = Resolve-ReceiptSchema -Root $toolchainRoot -Commit $schemaCommits[1]
    Assert-True ($atOne.Version -eq 1 -and $atOne.Note -like "*schema 1, which its own commit $($schemaCommits[1].Substring(0, 8)) wrote*") `
        "A receipt cut at schema 1 was not held to it: $($atOne.Version), $($atOne.Note)"
    $atCurrent = Resolve-ReceiptSchema -Root $toolchainRoot -Commit $schemaCommits[$currentSchema]
    Assert-True ($atCurrent.Version -eq $currentSchema -and $null -eq $atCurrent.Note) `
        "A receipt cut at this checkout's schema was not held to it quietly: $($atCurrent.Version), $($atCurrent.Note)"
    Assert-Throws { Resolve-ReceiptSchema -Root $toolchainRoot -Commit $schemaCommits[$currentSchema + 1] } `
        "*writes receipt schema $($currentSchema + 1), newer than the $currentSchema this checkout reads*" `
        'A receipt schema newer than this checkout reads was read as if it were known.'
    foreach ($none in @($releaseCommitSha, '')) {
        $atNone = Resolve-ReceiptSchema -Root $toolchainRoot -Commit $none
        Assert-True ($atNone.Version -eq $currentSchema -and $null -eq $atNone.Note) `
            "A commit with no receipt script was not held to this checkout's schema: $($atNone.Version), $($atNone.Note)"
    }
    Assert-True ([System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'release-receipt.ps1')) -match
            '(?s)function\s+Get-ReleaseReceiptSchemaVersion\b.*?#>\s*return\s+(\d+)\s*\}' -and [int]$Matches[1] -eq $currentSchema) `
        'Resolve-ReceiptSchema can no longer read the schema out of this checkout''s own release-receipt.ps1.'

    # A catalog that pins nothing usable still stops the run, rather than being read as blank.
    foreach ($broken in @(
        @{ Name = 'no patcher pin'; Lines = @('[versions]', 'manager-floor = "1.29.0"') },
        @{ Name = 'no Manager floor'; Lines = @('[versions]', 'morphe-patcher = "1.12.0"') },
        @{ Name = 'an unusable Manager floor'; Lines = @('[versions]', 'morphe-patcher = "1.12.0"', 'manager-floor = "latest"') })) {
        Assert-Throws { Read-CatalogToolchain -Text ($broken.Lines -join "`n") -Source 'the test catalog' } `
            '*the test catalog*' "A catalog with $($broken.Name) was read without complaint."
    }
} finally {
    Remove-Item -LiteralPath $toolchainRoot, $toolchainRemote -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[release-tooling] release receipt schema, manifest reading and validation contracts passed'

# --- release-advisories.ps1 ------------------------------------------------------------------
#
# The gate that asks OSV about the libraries a release's SBOM lists and refuses a release carrying
# a high or critical advisory. OSV's answers are recorded here as it gave them on 2026-09-25,
# trimmed to the fields the gate reads, and a stand-in for Invoke-RestMethod answers from them by
# package URL, so no case needs the network. A package URL it has no answer for fails the way an
# OSV this machine can't reach does. gson 2.8.8 is the deliberately vulnerable library:
# GHSA-4jrv-ppp4-jm57 (CVE-2022-25647), which OSV and GitHub rate HIGH.

. (Join-Path $PSScriptRoot 'release-advisories.ps1')

$gsonAdvisory = '{"id":"GHSA-4jrv-ppp4-jm57","summary":"Deserialization of Untrusted Data in Gson","aliases":["CVE-2022-25647"],"modified":"2026-09-10T03:49:19.710819205Z","database_specific":{"severity":"HIGH"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:H/A:H"}]}'
$log4jCritical = '{"id":"GHSA-jfh8-c2jp-5v3q","summary":"Remote code injection in Log4j","aliases":["CVE-2021-44228"],"modified":"2025-10-22T19:37:02.616807Z","database_specific":{"severity":"CRITICAL"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H/E:H"}]}'
$log4jModerate = '{"id":"GHSA-8489-44mv-ggj8","summary":"Improper Input Validation and Injection in Apache Log4j2","aliases":["CVE-2021-44832"],"modified":"2026-06-09T10:45:14.253296471Z","database_specific":{"severity":"MODERATE"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:H/PR:H/UI:N/S:U/C:H/I:H/A:H"}]}'
# Rated only by a CVSS 4 vector once OSV's label is taken out, which this gate can't score.
$log4jVectorFour = '{"id":"GHSA-3pxv-7cmr-fjr4","summary":"Apache Log4j Core: Silent log event loss in XmlLayout due to unescaped XML 1.0 forbidden characters","aliases":["CVE-2026-34480"],"modified":"2026-09-10T03:50:42.492278990Z","severity":[{"type":"CVSS_V4","score":"CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:L/SA:N"}]}'
$guavaModerate = '{"id":"GHSA-7g45-4rm6-3mm3","summary":"Guava vulnerable to insecure use of temporary directory","aliases":["CVE-2023-2976"],"modified":"2026-09-10T03:49:53.859811124Z","database_specific":{"severity":"MODERATE"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N"}]}'
$guavaLow = '{"id":"GHSA-5mg8-w23w-74h3","summary":"Information Disclosure in Guava","aliases":["CVE-2020-8908"],"modified":"2026-09-10T03:49:26.651391253Z","database_specific":{"severity":"LOW"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N"}]}'
$gsonPurl = 'pkg:maven/com.google.code.gson/gson@2.8.8'
$cleanPurl = 'pkg:maven/com.google.code.gson/gson@2.14.0'
$osvRecorded = @{
    $gsonPurl = "{`"vulns`":[$gsonAdvisory]}"
    'pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1' = "{`"vulns`":[$log4jCritical,$log4jModerate]}"
    'pkg:maven/com.google.guava/guava@31.1-jre' = "{`"vulns`":[$guavaModerate,$guavaLow]}"
    $cleanPurl = '{}'
}
# What the stand-in serves, which a case replaces to try another shape, and what it was asked.
$osvAnswers = $osvRecorded
$osvAsked = New-Object System.Collections.Generic.List[string]
$osvStandIn = {
    function Invoke-RestMethod {
        param($Uri, $Method, $ContentType, $Body, $TimeoutSec)
        $query = $Body | ConvertFrom-Json
        $key = [string]$query.package.purl
        if ($query.page_token) { $key += " page $($query.page_token)" }
        $osvAsked.Add($key)
        if (-not $osvAnswers.ContainsKey($key)) {
            throw "Unable to connect to the remote server (a stand-in for api.osv.dev with no answer for $key)"
        }
        $answer = $osvAnswers[$key]
        if ($answer -is [string] -and $answer.StartsWith('{')) { return ($answer | ConvertFrom-Json) }
        return $answer
    }
}
$advisoryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushgram-advisories-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $advisoryRoot -Force | Out-Null
try {
    $today = [datetime]'2026-09-25'
    # What Read-ReleaseSbom hands the gate, for the libraries named.
    function New-GateSbom([string[]]$Purls) {
        $libraries = @(foreach ($purl in @($Purls | Where-Object { $_ })) {
            $parts = [regex]::Match($purl, '^pkg:maven/([^/]+)/([^@]+)@(.+)$')
            [pscustomobject]@{ Ref = $purl; Type = 'library'; Group = $parts.Groups[1].Value; Name = $parts.Groups[2].Value
                Version = $parts.Groups[3].Value; Purl = $purl }
        })
        return [pscustomobject]@{ Path = (Join-Path $advisoryRoot 'patches-9.9.9.cdx.json'); Libraries = $libraries }
    }
    # The gate on an SBOM of these libraries with these exception lines, and everything it said,
    # warnings included.
    function Invoke-Gate([string[]]$Purls, [string[]]$Exceptions = @(), [switch]$Skip) {
        $list = Join-Path $advisoryRoot 'advisory-exceptions.txt'
        Set-Content -LiteralPath $list -Encoding ASCII -Value (@('# exceptions for this case') + @($Exceptions))
        . $osvStandIn
        $osvAsked.Clear()
        return (@(Invoke-ReleaseAdvisoryGate -Sbom (New-GateSbom $Purls) -ExceptionsPath $list -Today $today `
            -SkipAdvisoryCheck:$Skip 3>&1 6>&1 | ForEach-Object { "$_" }) -join "`n")
    }
    $later = $today.AddDays(30).ToString('yyyy-MM-dd')
    $why = 'Only the build reads JSON with it, never input from outside.'

    # CVSS 3 base scores, against the numbers NVD and the specification's calculator give. The guava
    # vectors are the two that plain rounding gets wrong (5.4 and 3.2), and the one scored 8.6
    # rounds down to 8.5 that way; roundup as 3.1 defines it is the difference.
    foreach ($known in @(
            @{ Vector = 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H'; Score = 9.8 },
            @{ Vector = 'CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:H/A:H'; Score = 7.7 },
            @{ Vector = 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H/E:H'; Score = 10.0 },
            @{ Vector = 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:N/I:N/A:H'; Score = 8.6 },
            @{ Vector = 'CVSS:3.1/AV:N/AC:H/PR:H/UI:N/S:U/C:H/I:H/A:H'; Score = 6.6 },
            @{ Vector = 'CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N'; Score = 5.5 },
            @{ Vector = 'CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N'; Score = 3.3 },
            @{ Vector = 'CVSS:3.0/AV:N/AC:L/PR:L/UI:R/S:C/C:L/I:L/A:N'; Score = 5.4 },
            @{ Vector = 'CVSS:3.1/AV:P/AC:H/PR:H/UI:R/S:U/C:N/I:N/A:N'; Score = 0.0 })) {
        $scored = Get-Cvss3BaseScore -Vector $known.Vector
        Assert-True ($null -ne $scored -and [Math]::Abs($scored - $known.Score) -lt 0.001) `
            "$($known.Vector) scored $scored, not $($known.Score)."
    }
    foreach ($unscored in @('CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N',
            'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H', 'CVSS:3.1/AV:X/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H',
            'CVSS:3.1/AV:n/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H', 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:u/C:H/I:H/A:H')) {
        Assert-True ($null -eq (Get-Cvss3BaseScore -Vector $unscored)) "$unscored was given a CVSS 3 score."
    }

    # How serious each recorded advisory is: OSV's label and the vector's score, whichever is worse,
    # and unrated when neither can be read.
    foreach ($rated in @(
            @{ Name = 'the gson advisory'; Json = $gsonAdvisory; Level = 'HIGH'; Serious = $true; Why = 'OSV rates it HIGH' },
            @{ Name = 'the gson advisory with no vector'; Json = $gsonAdvisory.Replace(',"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:H/A:H"}]', '')
                Level = 'HIGH'; Serious = $true; Why = 'OSV rates it HIGH' },
            @{ Name = 'the gson advisory with no label'; Json = $gsonAdvisory.Replace('"database_specific":{"severity":"HIGH"},', '')
                Level = 'HIGH'; Serious = $true; Why = 'its CVSS 3 vector scores 7.7' },
            @{ Name = 'the gson advisory labelled LOW'; Json = $gsonAdvisory.Replace('"severity":"HIGH"', '"severity":"LOW"')
                Level = 'HIGH'; Serious = $true; Why = 'its CVSS 3 vector scores 7.7' },
            @{ Name = 'the log4j advisory'; Json = $log4jCritical; Level = 'CRITICAL'; Serious = $true; Why = 'OSV rates it CRITICAL' },
            @{ Name = 'a CVSS 4 vector alone'; Json = $log4jVectorFour; Level = 'UNRATED'; Serious = $true; Why = '*no severity*' },
            @{ Name = 'the guava temporary directory advisory'; Json = $guavaModerate; Level = 'MODERATE'; Serious = $false; Why = 'OSV rates it MODERATE' },
            @{ Name = 'a MEDIUM label'; Json = $guavaModerate.Replace('"MODERATE"', '"MEDIUM"'); Level = 'MODERATE'; Serious = $false; Why = 'OSV rates it MODERATE' },
            @{ Name = 'the guava disclosure advisory'; Json = $guavaLow; Level = 'LOW'; Serious = $false; Why = 'OSV rates it LOW' })) {
        $severity = Get-AdvisorySeverity -Advisory ($rated.Json | ConvertFrom-Json)
        Assert-True ($severity.Level -eq $rated.Level -and $severity.Serious -eq $rated.Serious -and $severity.Why -like $rated.Why) `
            "$($rated.Name) was rated $($severity.Level), serious $($severity.Serious), because $($severity.Why)."
    }

    # Labels alone used to hide unread vectors and affected-package severity. Exercise the
    # actual SBOM query path so the package/version context cannot be lost by the caller.
    $lowVector = @{ type = 'CVSS_V3'; score = 'CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N' }
    $criticalVector = @{ type = 'CVSS_V3'; score = 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H' }
    $v4Vector = @{ type = 'CVSS_V4'; score = 'CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N' }
    function New-AffectedRating([string]$Name, [string]$Version, [object[]]$Severity, [string]$Ecosystem = 'Maven') {
        return @{ package = @{ ecosystem = $Ecosystem; name = $Name }; versions = @($Version); severity = $Severity }
    }
    foreach ($case in @(
            @{ Name = 'low label and v4'; Fields = @{ severity = @($v4Vector) }; Level = 'UNRATED' },
            @{ Name = 'critical label and v4'; Fields = @{ database_specific = @{ severity = 'CRITICAL' }; severity = @($v4Vector) }; Level = 'CRITICAL' },
            @{ Name = 'high label and malformed vector'; Fields = @{ database_specific = @{ severity = 'HIGH' };
                severity = @(@{ type = 'CVSS_V3'; score = 'broken' }) }; Level = 'HIGH' },
            @{ Name = 'critical vector and v4'; Fields = @{ severity = @($criticalVector, $v4Vector) }; Level = 'CRITICAL' },
            @{ Name = 'optional metric trailing newline'; Fields = @{ severity = @(@{ type = 'CVSS_V3';
                score = $lowVector.score + "/E:X`n" }) }; Level = 'UNRATED' },
            @{ Name = 'low label and malformed v3'; Fields = @{ severity = @(@{ type = 'CVSS_V3'; score = 'CVSS:3.1/AV:N' }) }; Level = 'UNRATED' },
            @{ Name = 'low label and malformed v4'; Fields = @{ severity = @(@{ type = 'CVSS_V4'; score = 'broken' }) }; Level = 'UNRATED' },
            @{ Name = 'null severity'; Fields = @{ severity = @($null) }; Level = 'UNRATED' },
            @{ Name = 'non-array severity'; Fields = @{ severity = $lowVector }; Level = 'UNRATED' },
            @{ Name = 'numeric score'; Fields = @{ severity = @(@{ type = 'CVSS_V3'; score = 3.3 }) }; Level = 'UNRATED' },
            @{ Name = 'unsupported severity'; Fields = @{ severity = @(@{ type = 'CVSS_FUTURE'; score = 'low' }) }; Level = 'UNRATED' },
            @{ Name = 'duplicate metrics lower the score'; Fields = @{ severity = @(@{ type = 'CVSS_V3';
                score = $criticalVector.score + '/C:N/I:N/A:N' }) }; Level = 'UNRATED' },
            @{ Name = 'valid v3 and unread v4'; Fields = @{ severity = @($lowVector, $v4Vector) }; Level = 'UNRATED' },
            @{ Name = 'conflicting minor ratings'; Fields = @{ severity = @($lowVector, ($guavaModerate | ConvertFrom-Json).severity[0]) }; Level = 'UNRATED' },
            @{ Name = 'package critical'; Fields = @{ affected = @(
                (New-AffectedRating 'com.google.code.gson:gson' '2.8.8' @($criticalVector))) }; Level = 'CRITICAL' },
            @{ Name = 'another package cannot supply the score'; Fields = @{ affected = @(
                (New-AffectedRating 'com.google.code.gson:gson' '2.8.8' @($lowVector)),
                (New-AffectedRating 'com.google.code.gson:gson-extras' '2.8.8' @($criticalVector))) }; Level = 'LOW' },
            @{ Name = 'another ecosystem cannot supply the score'; Fields = @{ affected = @(
                (New-AffectedRating 'com.google.code.gson:gson' '2.8.8' @($lowVector)),
                (New-AffectedRating 'com.google.code.gson:gson' '2.8.8' @($criticalVector) 'NuGet')) }; Level = 'LOW' },
            @{ Name = 'another version cannot supply the score'; Fields = @{ affected = @(
                (New-AffectedRating 'com.google.code.gson:gson' '2.8.8' @($lowVector)),
                (New-AffectedRating 'com.google.code.gson:gson' '2.7' @($criticalVector))) }; Level = 'LOW' },
            @{ Name = 'no applicable package'; Fields = @{ affected = @(
                (New-AffectedRating 'com.google.code.gson:gson-extras' '2.8.8' @($lowVector))) }; Level = 'UNRATED' },
            @{ Name = 'no applicable version'; Fields = @{ affected = @(
                (New-AffectedRating 'com.google.code.gson:gson' '2.7' @($lowVector))) }; Level = 'UNRATED' },
            @{ Name = 'package v4'; Fields = @{ affected = @(
                (New-AffectedRating 'com.google.code.gson:gson' '2.8.8' @($v4Vector))) }; Level = 'UNRATED' },
            @{ Name = 'ambiguous package ranges'; Fields = @{ affected = @(
                @{ package = @{ ecosystem = 'Maven'; name = 'com.google.code.gson:gson' };
                    ranges = @(@{ type = 'ECOSYSTEM'; events = @(@{ introduced = '0'; fixed = '2.8' }) }); severity = @($lowVector) },
                @{ package = @{ ecosystem = 'Maven'; name = 'com.google.code.gson:gson' };
                    ranges = @(@{ type = 'ECOSYSTEM'; events = @(@{ introduced = '2.8' }) }); severity = @($criticalVector) }) }; Level = 'UNRATED' })) {
        $record = @{ id = 'GHSA-fixture-severity'; database_specific = @{ severity = 'LOW' } }
        foreach ($key in $case.Fields.Keys) { $record[$key] = $case.Fields[$key] }
        $osvAnswers = @{ $gsonPurl = (@{ vulns = @($record) } | ConvertTo-Json -Depth 12 -Compress) }
        . $osvStandIn
        $actual = @(Get-SbomAdvisories -Sbom (New-GateSbom @($gsonPurl)))
        Assert-True ($actual.Count -eq 1 -and $actual[0].Severity.Level -ceq $case.Level) `
            "$($case.Name) was classified $($actual[0].Severity.Level), not $($case.Level)."
        $verdict = Test-AdvisoryFindings -Findings $actual
        Assert-True ($verdict.Valid -eq ($case.Level -ceq 'LOW')) "$($case.Name) received the wrong release verdict."
    }
    $osvAnswers = $osvRecorded

    foreach ($conflict in @('package name', 'package version')) {
        $scoped = New-AffectedRating 'com.google.code.gson:gson' '2.8.8' @($criticalVector)
        $scoped.package.purl = if ($conflict -eq 'package name') {
            'pkg:maven/com.google.code.gson/gson-extras@2.8.8'
        } else { 'pkg:maven/com.google.code.gson/gson@2.7' }
        $record = @{ id = 'GHSA-fixture-scope'; database_specific = @{ severity = 'LOW' }; affected = @(
            $scoped, (New-AffectedRating 'com.google.code.gson:gson' '2.8.8' @($lowVector))) }
        $osvAnswers = @{ $gsonPurl = (@{ vulns = @($record) } | ConvertTo-Json -Depth 10 -Compress) }
        $actual = @(Get-SbomAdvisories -Sbom (New-GateSbom @($gsonPurl)))
        Assert-True ($actual[0].Severity.Level -ceq 'UNRATED') "A contradictory $conflict hid an affected rating."
    }
    $packageLabel = New-AffectedRating 'com.google.code.gson:gson' '2.8.8' @()
    $packageLabel.database_specific = @{ severity = 'HIGH' }
    $osvAnswers = @{ $gsonPurl = (@{ vulns = @(@{ id = 'GHSA-fixture-label';
        database_specific = @{ severity = 'LOW' }; affected = @($packageLabel) }) } | ConvertTo-Json -Depth 10 -Compress) }
    Assert-True ((Get-SbomAdvisories -Sbom (New-GateSbom @($gsonPurl))).Severity.Level -ceq 'HIGH') `
        'The applicable package label was ignored.'

    # A bridge joins aliases transitively. Every identity remains available for a scoped
    # exception, but neither a lower alias nor input order may downgrade the finding.
    $aliasRecords = @(
        @{ id = 'GHSA-fixture-first'; aliases = @('CVE-fixture-first'); database_specific = @{ severity = 'LOW' } },
        @{ id = 'GHSA-fixture-last'; aliases = @('CVE-fixture-last'); database_specific = @{ severity = 'CRITICAL' } },
        @{ id = 'GHSA-fixture-bridge'; aliases = @('CVE-fixture-first', 'CVE-fixture-last'); database_specific = @{ severity = 'LOW' } })
    foreach ($reverse in @($false, $true)) {
        $ordered = @($aliasRecords)
        if ($reverse) { [array]::Reverse($ordered) }
        $osvAnswers = @{ $gsonPurl = (@{ vulns = $ordered } | ConvertTo-Json -Depth 8 -Compress) }
        $actual = @(Get-SbomAdvisories -Sbom (New-GateSbom @($gsonPurl)))
        Assert-True ($actual.Count -eq 1 -and $actual[0].Severity.Level -ceq 'CRITICAL' -and $actual[0].Aliases.Count -eq 4) `
            'Alias merging lost the strongest rating or an identity.'
        Assert-True (-not (Test-AdvisoryFindings -Findings $actual).Valid) 'A lower alias allowed a critical finding.'
        $review = [pscustomobject]@{ Advisory = 'GHSA-fixture-first'; Package = 'com.google.code.gson:gson';
            Scope = 'shipped'; Expired = $false; Until = $today.AddDays(1); Reason = 'Reviewed controlled fixture only'; Line = 1 }
        Assert-True ((Test-AdvisoryFindings -Findings $actual -Exceptions @($review)).Valid -and
            -not (Test-AdvisoryFindings -Findings $actual -Exceptions @($review) -Scope test).Valid) `
            'An alias exception was lost or escaped its reviewed dependency scope.'
    }
    foreach ($known in @('HIGH', 'CRITICAL')) {
        foreach ($reverse in @($false, $true)) {
            $mixed = @(
                @{ id = 'GHSA-fixture-rated'; aliases = @('CVE-fixture-mixed'); database_specific = @{ severity = $known } },
                @{ id = 'GHSA-fixture-unread'; aliases = @('CVE-fixture-mixed'); severity = @($v4Vector) })
            if ($reverse) { [array]::Reverse($mixed) }
            $osvAnswers = @{ $gsonPurl = (@{ vulns = $mixed } | ConvertTo-Json -Depth 8 -Compress) }
            $actual = @(Get-SbomAdvisories -Sbom (New-GateSbom @($gsonPurl)))
            Assert-True ($actual.Count -eq 1 -and $actual[0].Severity.Level -ceq $known -and
                $actual[0].Severity.Why -match 'needs review' -and $actual[0].Aliases.Count -eq 2 -and
                -not (Test-AdvisoryFindings -Findings $actual).Valid) `
                'Unread alias evidence erased a known serious rating or its review requirement.'
        }
    }
    $aliasRecords[1].database_specific.severity = 'MODERATE'
    $osvAnswers = @{ $gsonPurl = (@{ vulns = $aliasRecords } | ConvertTo-Json -Depth 8 -Compress) }
    Assert-True ((Get-SbomAdvisories -Sbom (New-GateSbom @($gsonPurl))).Severity.Level -ceq 'UNRATED') `
        'Conflicting minor alias ratings were certified without review.'
    $osvAnswers = $osvRecorded

    foreach ($invalid in @(
            ($criticalVector.score + '/AV:P'),
            ($criticalVector.score + '/E:H/E:U'),
            ($criticalVector.score + '/UNKNOWN:H'),
            ($criticalVector.score + '/E:Z'),
            ($criticalVector.score + "/E:X`n"),
            ($criticalVector.score + "/E:X`r`n"),
            ($criticalVector.score + "/E:X`t"),
            ($criticalVector.score + '/'),
            $criticalVector.score.Replace('/AV:N', '/av:N'),
            $criticalVector.score.Replace('CVSS:', 'cvss:'))) {
        Assert-True ($null -eq (Get-Cvss3BaseScore $invalid)) "Malformed CVSS vector received a score: $invalid"
    }
    Assert-True ((Get-Cvss3BaseScore 'CVSS:3.1/S:U/AV:N/AC:L/PR:N/UI:N/C:H/I:H/A:H/E:F/RL:X/RC:C/CR:X/MAV:X') -eq 9.8) `
        'Valid reordered base metrics and optional metrics changed the base score.'

    # The exception list. Each broken line stops the read and names itself.
    $exceptionList = Join-Path $advisoryRoot 'read.txt'
    Set-Content -LiteralPath $exceptionList -Encoding ASCII -Value @('# accepted', '',
        "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $later $why",
        "CVE-2021-44228 org.apache.logging.log4j:log4j-core 2026-09-24 $why")
    $read = @(Read-AdvisoryExceptions -Path $exceptionList -Today $today)
    Assert-True ($read.Count -eq 2 -and $read[0].Advisory -eq 'GHSA-4jrv-ppp4-jm57' -and $read[0].Package -eq 'com.google.code.gson:gson' -and
        -not $read[0].Expired -and $read[1].Expired -and $read[0].Reason -eq $why -and $read[0].Line -eq 3) `
        "The exception list was misread: $(@($read | ForEach-Object { "$($_.Advisory) $($_.Package) $($_.Until) expired=$($_.Expired)" }) -join '; ')"
    Assert-True (@(Read-AdvisoryExceptions -Path $exceptionList -Today $today.AddDays(-60)).Count -eq 2) `
        'An exception 90 days out on the day it was read was refused.'
    foreach ($broken in @(
            @{ Name = 'no date'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $why"; Pattern = '*isn''t a yyyy-MM-dd date*' },
            @{ Name = 'no package'; Line = "GHSA-4jrv-ppp4-jm57 $later $why"; Pattern = '*is not "<advisory> <group>:<name>*' },
            @{ Name = 'a date that does not exist'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson 2026-02-30 $why"; Pattern = '*isn''t a yyyy-MM-dd date*' },
            @{ Name = 'a date 91 days out'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $($today.AddDays(91).ToString('yyyy-MM-dd')) $why"
                Pattern = '*more than 90 days out*' },
            @{ Name = 'a two-word reason'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $later Not reachable."; Pattern = '*without saying why*' },
            @{ Name = 'the same advisory twice'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $later $why"; Twice = $true; Pattern = '*a second time*' })) {
        $lines = @($broken.Line)
        if ($broken.Twice) { $lines += $broken.Line }
        Set-Content -LiteralPath $exceptionList -Encoding ASCII -Value $lines
        Assert-Throws { Read-AdvisoryExceptions -Path $exceptionList -Today $today } $broken.Pattern `
            "An exception list with $($broken.Name) was read without complaint."
    }
    Assert-Throws { Read-AdvisoryExceptions -Path (Join-Path $advisoryRoot 'absent.txt') -Today $today } '*list is missing*' `
        'A missing exception list was read as an empty one.'
    # The date in the list is a day on the maintainer's own calendar. Both functions defaulted to
    # the UTC date, so on the east coast an exception ran out at 19:00 on the last day it named
    # and refused a release it still covered. The clock can't be moved here, so the defaults are
    # read as written.
    foreach ($dated in 'Read-AdvisoryExceptions', 'Invoke-ReleaseAdvisoryGate') {
        $parameter = @((Get-Command $dated).ScriptBlock.Ast.Body.ParamBlock.Parameters |
            Where-Object { $_.Name.VariablePath.UserPath -eq 'Today' })
        $default = if ($parameter.Count -eq 1 -and $parameter[0].DefaultValue) { $parameter[0].DefaultValue.Extent.Text } else { '' }
        Assert-True ($default -eq '[datetime]::Today') "$dated takes today as $default, not the local calendar day."
    }
    $checkedIn = Join-Path $PSScriptRoot 'advisory-exceptions.txt'
    try {
        $null = @(Read-AdvisoryExceptions -Path $checkedIn)
    } catch {
        throw "The checked-in scripts/advisory-exceptions.txt does not read: $($_.Exception.Message)"
    }

    # The gate. A clean library is asked about once and passes.
    $said = Invoke-Gate @($cleanPurl)
    Assert-True ($said -like '*OSV has no advisory for the libraries patches-9.9.9.cdx.json lists: gson 2.14.0*' -and
        ($osvAsked -join ', ') -eq $cleanPurl) "The gate did not ask OSV about the clean library, or did not say so: $said"

    # The deliberately vulnerable library is refused, naming the advisory, its alias and the version.
    Assert-Throws { Invoke-Gate @($cleanPurl, $gsonPurl) } `
        '*high or critical*GHSA-4jrv-ppp4-jm57 (HIGH, CVE-2022-25647) in com.google.code.gson:gson 2.8.8*' `
        'The gate let a release carry gson 2.8.8.'
    Assert-Throws { Invoke-Gate @('pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1') } '*GHSA-jfh8-c2jp-5v3q (CRITICAL*' `
        'The gate let a release carry log4j-core 2.14.1.'
    # Moderate and low go through, and say so.
    $said = Invoke-Gate @('pkg:maven/com.google.guava/guava@31.1-jre')
    Assert-True ($said -like '*below high, let through: GHSA-7g45-4rm6-3mm3 (MODERATE*' -and
        $said -like '*below high, let through: GHSA-5mg8-w23w-74h3 (LOW*' -and
        $said -like '*GHSA-xxph-c9ww-hj94 (MODERATE*' -and $said -like '*3 advisories, none refused*') `
        "The gate refused, or said nothing of, moderate and low advisories: $said"
    # An advisory rated only by a vector this gate can't score counts as serious.
    $osvAnswers = @{ 'pkg:maven/org.apache.logging.log4j/log4j-core@2.26.0' = "{`"vulns`":[$log4jVectorFour]}" }
    try {
        Assert-Throws { Invoke-Gate @('pkg:maven/org.apache.logging.log4j/log4j-core@2.26.0') } '*GHSA-3pxv-7cmr-fjr4 (UNRATED*' `
            'The gate let an advisory through that no severity it can read describes.'
    } finally {
        $osvAnswers = $osvRecorded
    }

    # Exceptions: by OSV's id or an alias, for the one package, until the date.
    foreach ($named in @('GHSA-4jrv-ppp4-jm57', 'CVE-2022-25647', 'ghsa-4jrv-ppp4-jm57')) {
        $said = Invoke-Gate @($gsonPurl) @("$named com.google.code.gson:gson $later $why")
        Assert-True ($said -like "*accepted: GHSA-4jrv-ppp4-jm57 (HIGH*accepted until $later`: $why*") `
            "An exception naming $named did not accept the gson advisory: $said"
    }
    Assert-Throws { Invoke-Gate @($gsonPurl) @("GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson-extras $later $why") } `
        '*high or critical*GHSA-4jrv-ppp4-jm57*no longer reports*GHSA-4jrv-ppp4-jm57 for com.google.code.gson:gson-extras*' `
        'An exception for another package accepted the gson advisory, or was not called stale.'
    Assert-Throws { Invoke-Gate @($gsonPurl) @("GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson 2026-09-24 $why") } `
        '*GHSA-4jrv-ppp4-jm57 (HIGH*its exception ran out on 2026-09-24*' 'An exception past its date still accepted the gson advisory.'
    Assert-Throws { Invoke-Gate @($cleanPurl) @("GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $later $why") } `
        '*no longer reports*GHSA-4jrv-ppp4-jm57 for com.google.code.gson:gson (line 2)*' 'An exception nothing matches any more went unnoticed.'

    # OSV has to answer, and answer with something the gate can read.
    Assert-Throws { Invoke-Gate @('pkg:maven/com.example/unknown@1.0') } '*could not be asked about pkg:maven/com.example/unknown@1.0*fails closed*' `
        'The gate read an OSV it could not reach as no advisories.'
    $unreadable = @(
        @{ Name = 'a page of HTML'; Answer = '<html>Service Unavailable</html>'; Pattern = '*isn''t a query result*' },
        @{ Name = 'an advisory with no id'; Answer = '{"vulns":[{"summary":"no id"}]}'; Pattern = '*has no id*' })
    foreach ($odd in $unreadable) {
        $osvAnswers = @{ $cleanPurl = $odd.Answer }
        try {
            Assert-Throws { Invoke-Gate @($cleanPurl) } $odd.Pattern "The gate took $($odd.Name) from OSV for an answer."
        } finally {
            $osvAnswers = $osvRecorded
        }
    }
    # The second page of an answer is read, and an advisory OSV withdrew is not one.
    $osvAnswers = @{ $gsonPurl = '{"next_page_token":"p2"}'; "$gsonPurl page p2" = "{`"vulns`":[$gsonAdvisory]}" }
    try {
        Assert-Throws { Invoke-Gate @($gsonPurl) } '*GHSA-4jrv-ppp4-jm57 (HIGH*' 'The gate stopped at the first page of an answer.'
        Assert-True (($osvAsked -join ', ') -eq "$gsonPurl, $gsonPurl page p2") "The gate did not ask for the second page: $($osvAsked -join ', ')"
        $osvAnswers = @{ $gsonPurl = "{`"vulns`":[$($gsonAdvisory.Replace('{"id"', '{"withdrawn":"2026-09-01T00:00:00Z","id"'))]}" }
        $said = Invoke-Gate @($gsonPurl)
        Assert-True ($said -like '*OSV has no advisory*') "A withdrawn advisory refused the release: $said"
    } finally {
        $osvAnswers = $osvRecorded
    }

    # Offline work: the check is skipped with a warning and OSV isn't asked, but the exception list
    # is still read. And an SBOM with no library has nothing to ask about.
    $said = Invoke-Gate @('pkg:maven/com.example/unknown@1.0') -Skip
    Assert-True ($said -like '*-SkipAdvisoryCheck: OSV was not asked about the libraries patches-9.9.9.cdx.json lists*' -and
        $osvAsked.Count -eq 0) "The skipped check asked OSV, or did not say it was skipped: $said"
    Assert-Throws { Invoke-Gate @($cleanPurl) @('GHSA-4jrv-ppp4-jm57 soon') -Skip } '*is not "<advisory>*' `
        'A skipped check left a broken exception list unread.'
    $said = Invoke-Gate @()
    Assert-True ($said -like '*lists no library to ask OSV about*' -and $osvAsked.Count -eq 0) `
        "An SBOM with no library was not passed as one: $said"

    # These scopes used to be absent from the advisory gate altogether. Keep the actual
    # resolved versions separate from the shipped payload and from an installed host.
    $graphFile = Join-Path $advisoryRoot 'graphs.json'
    $fixtureInputs = [ordered]@{ schemaVersion = 1; version = '9.9.9'; sourceSha256 = ('a' * 64)
        catalogSha256 = ('b' * 64); toolchainSha256 = ('c' * 64); inputSha256 = ('d' * 64) }
    $graphDocument = [ordered]@{ schemaVersion = 2; inputs = $fixtureInputs; graphs = @(
        foreach ($scope in @('settings-plugin', 'project-plugin', 'build', 'test', 'host-contract')) {
            [ordered]@{ scope = $scope; owner = ':fixture'; configuration = 'runtimeClasspath'
                root = 'root'; libraries = @((New-GateSbom @($cleanPurl)).Libraries)
                dependencies = @([ordered]@{ ref = 'root'; dependsOn = @($cleanPurl) }
                    [ordered]@{ ref = $cleanPurl; dependsOn = @() }) }
        }
    ) }
    $graphJson = $graphDocument | ConvertTo-Json -Depth 8
    Set-Content -LiteralPath $graphFile -Encoding UTF8 -Value $graphJson

    foreach ($scope in @('settings-plugin', 'project-plugin', 'build', 'test', 'host-contract')) {
        $variant = $graphJson | ConvertFrom-Json
        $variant.graphs = @($variant.graphs | Where-Object { $_.scope -cne $scope })
        Set-Content -LiteralPath $graphFile -Encoding UTF8 -Value ($variant | ConvertTo-Json -Depth 8)
        Assert-Throws { Read-DependencyGraphs -Path $graphFile } "*omits the $scope scope*" `
            "An imported report without $scope passed the dependency gate."
    }
    Set-Content -LiteralPath $graphFile -Encoding UTF8 -Value $graphJson
    $graphs = Read-DependencyGraphs -Path $graphFile
    Assert-True ($graphs.Graphs.Count -eq 5 -and $graphs.Libraries.Count -eq 1) `
        'Resolved scopes were lost, or a package shared by graphs was queried repeatedly.'
    $emptyGraph = $graphJson | ConvertFrom-Json
    $emptyGraph.graphs[0].libraries = @()
    $emptyGraph.graphs[0].dependencies = @([ordered]@{ ref = 'root'; dependsOn = @() })
    $emptyGraph | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $graphFile -Encoding UTF8
    Assert-True ((Read-DependencyGraphs -Path $graphFile).Graphs.Count -eq 5) `
        'A genuinely empty resolved configuration was read as an unreachable null library.'
    Set-Content -LiteralPath $graphFile -Encoding UTF8 -Value $graphJson
    foreach ($broken in @(
        @{ Name = 'an unknown schema'; Change = { param($d) $d.schemaVersion = 1 } },
        @{ Name = 'an omitted settings scope'; Change = { param($d) $d.graphs = @($d.graphs | Where-Object scope -ne 'settings-plugin') } },
        @{ Name = 'an unrecognized scope'; Change = { param($d) $d.graphs[0].scope = 'clean' } },
        @{ Name = 'an unresolved library'; Change = { param($d) $d.graphs[0].libraries[0].version = '' } },
        @{ Name = 'a forged package URL'; Change = { param($d) $d.graphs[0].libraries[0].purl = 'pkg:maven/com.example/other@1.0' } },
        @{ Name = 'a duplicate graph'; Change = { param($d) $d.graphs += $d.graphs[0] } },
        @{ Name = 'an absent input identity'; Change = { param($d) $d.PSObject.Properties.Remove('inputs') } },
        @{ Name = 'missing root edges'; Change = { param($d) $d.graphs[0].dependencies = @() } },
        @{ Name = 'an unresolved edge'; Change = { param($d) $d.graphs[0].dependencies[0].dependsOn = @('absent') } },
        @{ Name = 'a disconnected module'; Change = { param($d) $d.graphs[0].dependencies[0].dependsOn = @() } },
        @{ Name = 'a duplicate node'; Change = { param($d) $d.graphs[0].dependencies += $d.graphs[0].dependencies[0] } },
        @{ Name = 'a duplicate library'; Change = { param($d) $d.graphs[0].libraries += $d.graphs[0].libraries[0] } })) {
        $variant = $graphJson | ConvertFrom-Json
        & $broken.Change $variant
        Set-Content -LiteralPath $graphFile -Encoding UTF8 -Value ($variant | ConvertTo-Json -Depth 8)
        Assert-Throws { Read-DependencyGraphs -Path $graphFile } '*dependency graph*' `
            "The dependency audit accepted $($broken.Name)."
    }
    Set-Content -LiteralPath $graphFile -Encoding UTF8 -Value $graphJson

    # A current report is about exact working inputs and exact carrier bytes. Old published
    # receipts keep their reader rules, but an unbound/stale SBOM can't certify today's build.
    $inputRoot = Join-Path $advisoryRoot 'current-source'
    New-Item -ItemType Directory -Path $inputRoot -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $inputRoot 'gradle.properties') -Encoding UTF8 -Value 'version=9.9.9'
    Set-Content -LiteralPath (Join-Path $inputRoot 'patches-list.json') -Encoding UTF8 -Value '{}'
    Set-Content -LiteralPath (Join-Path $inputRoot 'source.java') -Encoding UTF8 -Value 'class Source {}'
    Set-Content -LiteralPath (Join-Path $inputRoot '.gitignore') -Encoding UTF8 -Value @('*.mpp', '*.cdx.json')
    $boundLicenseLedger = Join-Path $inputRoot 'sources/carried-library-licenses.json'
    New-Item -ItemType Directory -Path (Split-Path -Parent $boundLicenseLedger) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) 'sources/carried-library-licenses.json') -Destination $boundLicenseLedger
    Invoke-FixtureGit -Root $inputRoot -Arguments @('init', '--quiet') | Out-Null
    Invoke-FixtureGit -Root $inputRoot -Arguments @('add', '-A') | Out-Null
    $boundBundle = Join-Path $inputRoot 'patches-9.9.9.mpp'
    New-TestBundle -Path $boundBundle -Entries @{ 'extensions/instagram.mpe' = "dex`n035 payload" }
    $boundSbomPath = Join-Path $inputRoot 'patches-9.9.9.cdx.json'
    New-TestSbom -Path $boundSbomPath -Bundle $boundBundle -InputRoot $inputRoot -LicenseLedger $boundLicenseLedger
    $boundGraphPath = Join-Path $advisoryRoot 'current-graphs.json'
    $boundGraph = $graphJson | ConvertFrom-Json
    $boundGraph.inputs = Get-DependencyAuditInputs -Root $inputRoot
    $boundGraph | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $boundGraphPath -Encoding UTF8
    $boundGraphs = Read-DependencyGraphs -Path $boundGraphPath
    $boundSbom = Read-ReleaseSbom -Path $boundSbomPath
    $subject = Get-CurrentDependencyAuditSubject -Root $inputRoot -Graphs $boundGraphs -Sbom $boundSbom -BundlePath $boundBundle
    Assert-True ($subject.sbom.sha256 -eq $boundSbom.Sha256 -and $subject.bundle.name -eq 'patches-9.9.9.mpp') `
        'The current audit lost the artifact identity.'
    $futureLedger = Join-Path $inputRoot 'future-inputs.json'
    try {
        Set-Content -LiteralPath $futureLedger -Encoding UTF8 -Value '{}'
        Assert-Throws { Get-DependencyAuditInputs -Root $inputRoot } '*Stage new repository inputs*' `
            'A new unstaged input disappeared from the audit identity.'
        Invoke-FixtureGit -Root $inputRoot -Arguments @('add', 'future-inputs.json') | Out-Null
        Assert-True ((Get-DependencyAuditInputs -Root $inputRoot).sourceSha256 -cne $subject.inputs.sourceSha256) `
            'A newly tracked ledger disappeared from the source digest.'
    } finally {
        Invoke-FixtureGit -Root $inputRoot -Arguments @('rm', '--cached', '--quiet', 'future-inputs.json') | Out-Null
        Remove-Item -LiteralPath $futureLedger -Force
    }
    foreach ($file in 'source.java', 'patches-list.json', 'gradle.properties') {
        $path = Join-Path $inputRoot $file
        $before = [IO.File]::ReadAllBytes($path)
        try {
            Add-Content -LiteralPath $path -Encoding UTF8 -Value '# changed'
            Assert-Throws { Get-CurrentDependencyAuditSubject -Root $inputRoot -Graphs $boundGraphs -Sbom $boundSbom -BundlePath $boundBundle } `
                '*stale or mismatched*build inputs*' "The current audit accepted changed $file inputs."
        } finally { [IO.File]::WriteAllBytes($path, $before) }
    }
    $sbomBytes = [IO.File]::ReadAllBytes($boundSbomPath)
    $bundleBytes = [IO.File]::ReadAllBytes($boundBundle)
    foreach ($bad in @(
        @{ Name = 'an absent producer identity'; Pattern = '*no unique build-input identity*'; Change = { param($d) $d.metadata.PSObject.Properties.Remove('properties') } },
        @{ Name = 'a duplicate producer identity'; Pattern = '*no unique build-input identity*'; Change = { param($d) $d.metadata.properties += @($d.metadata.properties | Where-Object { $_.name -ceq 'hushgram:build-inputs' }) } },
        @{ Name = 'a stale catalog identity'; Pattern = '*mismatched catalogSha256*'; Change = { param($d) $p = $d.metadata.properties | Where-Object { $_.name -ceq 'hushgram:build-inputs' }; $i = $p.value | ConvertFrom-Json; $i.catalogSha256 = '0' * 64; $p.value = $i | ConvertTo-Json -Compress } },
        @{ Name = 'a stale source identity'; Pattern = '*mismatched sourceSha256*'; Change = { param($d) $p = $d.metadata.properties | Where-Object { $_.name -ceq 'hushgram:build-inputs' }; $i = $p.value | ConvertFrom-Json; $i.sourceSha256 = '0' * 64; $p.value = $i | ConvertTo-Json -Compress } },
        @{ Name = 'a stale toolchain identity'; Pattern = '*mismatched toolchainSha256*'; Change = { param($d) $p = $d.metadata.properties | Where-Object { $_.name -ceq 'hushgram:build-inputs' }; $i = $p.value | ConvertFrom-Json; $i.toolchainSha256 = '0' * 64; $p.value = $i | ConvertTo-Json -Compress } },
        @{ Name = 'another bundle version'; Pattern = '*mismatched name or version*'; Change = { param($d) $d.metadata.component.version = '0.0.2' } },
        @{ Name = 'another bundle name'; Pattern = '*describes patches-0.0.2.mpp*'; Change = { param($d) $d.metadata.component.name = 'patches-0.0.2.mpp' } })) {
        try {
            $variant = [Text.Encoding]::UTF8.GetString($sbomBytes).TrimStart([char]0xfeff) | ConvertFrom-Json
            & $bad.Change $variant
            $variant | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $boundSbomPath -Encoding UTF8
            $changedSbom = Read-ReleaseSbom -Path $boundSbomPath
            Assert-Throws { Get-CurrentDependencyAuditSubject -Root $inputRoot -Graphs $boundGraphs -Sbom $changedSbom -BundlePath $boundBundle } `
                $bad.Pattern "The current audit accepted $($bad.Name)."
        } finally { [IO.File]::WriteAllBytes($boundSbomPath, $sbomBytes) }
    }
    try {
        Add-Content -LiteralPath $boundBundle -Encoding ASCII -Value 'substitution'
        Assert-Throws { Get-CurrentDependencyAuditSubject -Root $inputRoot -Graphs $boundGraphs -Sbom $boundSbom -BundlePath $boundBundle } `
            '*bundle hashes to*another build*' 'The current audit accepted substituted bundle bytes.'
    } finally { [IO.File]::WriteAllBytes($boundBundle, $bundleBytes) }
    try {
        Add-Content -LiteralPath $boundSbomPath -Encoding ASCII -Value ' '
        Assert-Throws { Get-CurrentDependencyAuditSubject -Root $inputRoot -Graphs $boundGraphs -Sbom $boundSbom -BundlePath $boundBundle } `
            '*SBOM bytes changed*' 'The current audit accepted an SBOM replaced after reading.'
    } finally { [IO.File]::WriteAllBytes($boundSbomPath, $sbomBytes) }
    foreach ($renamed in 'sbom', 'bundle') {
        $copy = Join-Path $advisoryRoot $(if ($renamed -eq 'sbom') { 'renamed.cdx.json' } else { 'renamed.mpp' })
        Copy-Item -LiteralPath $(if ($renamed -eq 'sbom') { $boundSbomPath } else { $boundBundle }) -Destination $copy
        $s = if ($renamed -eq 'sbom') { Read-ReleaseSbom -Path $copy } else { $boundSbom }
        $p = if ($renamed -eq 'bundle') { $copy } else { $boundBundle }
        Assert-Throws { Get-CurrentDependencyAuditSubject -Root $inputRoot -Graphs $boundGraphs -Sbom $s -BundlePath $p } `
            '*mismatched name or version*' "The current audit accepted a renamed $renamed."
    }
    . $osvStandIn
    $osvAnswers = $osvRecorded
    $boundReportPath = Join-Path $advisoryRoot 'current-advisories.json'
    & (Join-Path $PSScriptRoot 'audit-dependencies.ps1') -Root $inputRoot -GraphPath $boundGraphPath `
        -SbomPath $boundSbomPath -OutputPath $boundReportPath 6>$null
    $reportJson = Get-Content -LiteralPath $boundReportPath -Raw
    Assert-True ((Read-CurrentDependencyAdvisoryReport -Path $boundReportPath -Subject $subject).valid) `
        'The actual audit did not produce a bound current report.'
    $licensedSbomBytes = [IO.File]::ReadAllBytes($boundSbomPath)
    $certifiedReportHash = Get-Sha256Hex -Path $boundReportPath
    foreach ($case in @(
        @{ Name = 'omitted carried component'; Change = { param($d)
            $d.components = @($d.components | Where-Object { $_.purl -cne 'pkg:maven/com.google.code.gson/gson@2.14.0' })
        } },
        @{ Name = 'downgraded license policy'; Change = { param($d)
            $d.metadata.properties = @($d.metadata.properties | Where-Object { $_.name -cnotlike 'hushgram:license-*' })
            foreach ($component in @($d.components | Where-Object purl)) {
                $component.PSObject.Properties.Remove('licenses')
                $component.properties = @($component.properties | Where-Object { $_.name -cne 'hushgram:license-evidence' })
            }
        } },
        @{ Name = 'substituted publisher evidence'; Change = { param($d)
            $component = @($d.components | Where-Object purl)[0]
            $evidence = @($component.properties | Where-Object name -CEQ 'hushgram:license-evidence')[0]
            $record = $evidence.value | ConvertFrom-Json
            $record.evidence.sha256 = '0' * 64
            $evidence.value = $record | ConvertTo-Json -Depth 12 -Compress
        } })) {
        try {
            $variant = [Text.Encoding]::UTF8.GetString($licensedSbomBytes).TrimStart([char]0xfeff) | ConvertFrom-Json
            & $case.Change $variant
            $variant | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $boundSbomPath -Encoding utf8
            Assert-Throws { & (Join-Path $PSScriptRoot 'audit-dependencies.ps1') -Root $inputRoot -GraphPath $boundGraphPath `
                    -SbomPath $boundSbomPath -OutputPath $boundReportPath 6>$null } '*license*' `
                "The actual current audit certified $($case.Name)."
            Assert-True ((Get-Sha256Hex -Path $boundReportPath) -ceq $certifiedReportHash) `
                'A refused license audit replaced the previous certified report.'
        } finally { [IO.File]::WriteAllBytes($boundSbomPath, $licensedSbomBytes) }
    }
    $reportMutants = @(
        @{ Name = 'an old schema'; Change = { param($d) $d.schemaVersion = 1 } },
        @{ Name = 'an uncertified verdict'; Change = { param($d) $d.valid = $false } },
        @{ Name = 'a text verdict'; Change = { param($d) $d.valid = 'true' } },
        @{ Name = 'a text scope verdict'; Change = { param($d) $d.scopeVerdicts.shipped.Valid = 'true' } },
        @{ Name = 'changed graph contents'; Change = { param($d) $d.graphs[0].libraries = @() } },
        @{ Name = 'a stale date'; Change = { param($d) $d.checkedAt = [datetime]::UtcNow.AddDays(-2).ToString('o') } },
        @{ Name = 'a future date'; Change = { param($d) $d.checkedAt = [datetime]::UtcNow.AddDays(1).ToString('o') } })
    foreach ($part in 'bundle', 'sbom', 'graphReport') {
        foreach ($field in @($subject[$part].Keys)) {
            $reportMutants += @{ Name = "$part $field substitution"; Part = $part; Field = $field }
        }
    }
    foreach ($field in 'version', 'sourceSha256', 'catalogSha256', 'toolchainSha256', 'inputSha256') {
        $reportMutants += @{ Name = "$field input substitution"; Field = $field; Part = 'inputs' }
    }
    foreach ($scope in 'shipped', 'settings-plugin', 'project-plugin', 'build', 'test', 'host-contract') {
        $reportMutants += @{ Name = "a missing $scope verdict"; Scope = $scope }
        $reportMutants += @{ Name = "a refused $scope verdict"; Scope = $scope; Refused = $true }
    }
    foreach ($bad in $reportMutants) {
        $variant = $reportJson | ConvertFrom-Json
        if ($bad.Change) { & $bad.Change $variant }
        elseif ($bad.Scope) {
            if ($bad.Refused) { $variant.scopeVerdicts.($bad.Scope).Valid = $false }
            else { $variant.scopeVerdicts.PSObject.Properties.Remove($bad.Scope) }
        }
        else { $variant.subject.($bad.Part).($bad.Field) = 'substitution' }
        $variant | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $boundReportPath -Encoding UTF8
        Assert-Throws { Read-CurrentDependencyAdvisoryReport -Path $boundReportPath -Subject $subject } '*' `
            "The current advisory report accepted $($bad.Name)."
    }
    Set-Content -LiteralPath $boundReportPath -Encoding UTF8 -Value $reportJson
    # Invoke the public entrypoint in a fresh PowerShell process. The suite's already-loaded
    # helpers must not conceal a missing import. Refuse changed inputs before any network call.
    $standaloneSource = Join-Path $inputRoot 'source.java'
    $standaloneBytes = [IO.File]::ReadAllBytes($standaloneSource)
    $standalonePreference = $ErrorActionPreference
    try {
        Add-Content -LiteralPath $standaloneSource -Encoding ASCII -Value 'changed'
        $ErrorActionPreference = 'Continue'
        $shell = (Get-Process -Id $PID).Path
        $standaloneOutput = @(& $shell -NoProfile -NonInteractive -File (Join-Path $PSScriptRoot 'audit-dependencies.ps1') `
            -Root $inputRoot -GraphPath $boundGraphPath -SbomPath $boundSbomPath -OutputPath $boundReportPath 2>&1) -join "`n"
        $standaloneExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $standalonePreference
        [IO.File]::WriteAllBytes($standaloneSource, $standaloneBytes)
    }
    Assert-True ($standaloneExit -ne 0 -and $standaloneOutput -like '*stale or mismatched sourceSha256*') `
        "The standalone audit didn't refuse changed inputs through its own imports: $standaloneOutput"
    Write-Host '[OK] Current advisory reports refuse stale inputs, edges, omitted scopes and artifact substitutions.'

    # The publisher's Guava advisory was absent from OSV on 2026-10-01. An empty database
    # result must not erase the known affected range, including both published editions.
    foreach ($version in @('4.0', '33.7.1-jre', '33.7.1-android')) {
        $library = (New-GateSbom @("pkg:maven/com.google.guava/guava@$version")).Libraries[0]
        $vendor = @(Get-VendorAdvisories -Library $library)
        Assert-True ($vendor.Count -eq 1 -and $vendor[0].id -eq 'GHSA-xxph-c9ww-hj94') `
            "The publisher's Guava finding disappeared for $version."
    }
    foreach ($version in @('3.0', '33.7.2-jre', '33.7.2-android', '34.0-jre')) {
        $library = (New-GateSbom @("pkg:maven/com.google.guava/guava@$version")).Libraries[0]
        Assert-True (@(Get-VendorAdvisories -Library $library).Count -eq 0) `
            "The publisher's Guava range incorrectly includes $version."
    }
    . $osvStandIn
    $osvAnswers = @{ 'pkg:maven/com.google.guava/guava@33.7.1-jre' = '{}' }
    $vendorFindings = @(Get-SbomAdvisories -Sbom (New-GateSbom @('pkg:maven/com.google.guava/guava@33.7.1-jre')))
    Assert-True ($vendorFindings.Count -eq 1 -and $vendorFindings[0].Severity.Level -eq 'MODERATE') `
        'An empty OSV result hid the publisher finding or changed its severity.'
    $vendorJson = (Get-VendorAdvisories -Library (New-GateSbom @('pkg:maven/com.google.guava/guava@33.7.1-jre')).Libraries[0]) | ConvertTo-Json -Depth 5 -Compress
    $osvAnswers = @{ 'pkg:maven/com.google.guava/guava@33.7.1-jre' = "{`"vulns`":[$vendorJson]}" }
    Assert-True (@(Get-SbomAdvisories -Sbom (New-GateSbom @('pkg:maven/com.google.guava/guava@33.7.1-jre'))).Count -eq 1) `
        'The same publisher and OSV advisory was counted twice.'
    $osvOnlyLow = @{ id = 'GHSA-xxph-c9ww-hj94'; database_specific = @{ severity = 'LOW' } }
    $osvAnswers = @{ 'pkg:maven/com.google.guava/guava@33.7.1-jre' = (@{ vulns = @($osvOnlyLow) } | ConvertTo-Json -Depth 6 -Compress) }
    $merged = @(Get-SbomAdvisories -Sbom (New-GateSbom @('pkg:maven/com.google.guava/guava@33.7.1-jre')))
    Assert-True ($merged.Count -eq 1 -and $merged[0].Severity.Level -ceq 'UNRATED' -and $merged[0].Sources.Count -eq 2) `
        'A publisher/OSV disagreement was certified as minor or lost its sources.'

    # Actual OSV controls read on 2026-10-01, reduced to the fields this gate uses.
    $osvAnswers = @{
        'pkg:maven/org.jetbrains.kotlin/kotlin-gradle-plugin@2.4.10' = '{"vulns":[{"id":"GHSA-r937-wjx7-w2jp","aliases":["CVE-2026-53914"],"database_specific":{"severity":"MODERATE"}}]}'
        'pkg:maven/org.bouncycastle/bcprov-jdk18on@1.77' = '{"vulns":[{"id":"GHSA-qp49-qgx5-5m26","aliases":["CVE-2026-13506"],"database_specific":{"severity":"HIGH"}}]}'
    }
    $controls = @(Get-SbomAdvisories -Sbom (New-GateSbom @($osvAnswers.Keys)))
    Assert-True ($controls.Count -eq 2 -and @($controls | Where-Object Advisory -eq 'GHSA-r937-wjx7-w2jp').Count -eq 1) `
        'The affected Kotlin build-plugin control was lost.'
    Assert-True (-not (Test-AdvisoryFindings -Findings $controls).Valid) `
        'The affected Bouncy Castle control passed the existing high-severity policy.'
    $scoped = Test-AdvisoryFindings -Findings $controls -Subject 'the audited tooling scopes' `
        -ExceptionsLabel 'scripts/dependency-advisory-exceptions.txt'
    Assert-True ($scoped.Reason -like '*for the audited tooling scopes*' -and
        $scoped.Reason -like '*scripts/dependency-advisory-exceptions.txt*' -and
        $scoped.Reason -notlike '*what the bundle carries*') `
        'Tooling findings were described as shipped libraries or directed to payload exceptions.'

    $scopedList = Join-Path $advisoryRoot 'scoped-exceptions.txt'
    $line = 'GHSA-qp49-qgx5-5m26 org.bouncycastle:bcprov-jdk18on 2026-10-15 reviewed parser not reachable'
    Set-Content -LiteralPath $scopedList -Value "settings-plugin $line"
    $exceptions = @(Read-AdvisoryExceptions -Path $scopedList -Scoped -Today $today)
    Assert-True ((Test-AdvisoryFindings -Findings $controls -Exceptions $exceptions -Scope 'settings-plugin').Valid) `
        'The scope-specific exception did not cover its reviewed settings-plugin use.'
    foreach ($scope in @('project-plugin', 'build', 'test', 'host-contract', 'shipped')) {
        Assert-True (-not (Test-AdvisoryFindings -Findings $controls -Exceptions $exceptions -Scope $scope).Valid) `
            "A settings-plugin exception leaked into $scope."
    }
    $stale = Test-AdvisoryFindings -Findings @() -Exceptions $exceptions -Scope 'settings-plugin'
    Assert-True (-not $stale.Valid -and $stale.Stale.Count -eq 1) 'An unmatched scoped exception passed.'
    $expired = @(Read-AdvisoryExceptions -Path $scopedList -Scoped -Today ([datetime]'2026-10-16'))
    Assert-True (-not (Test-AdvisoryFindings -Findings $controls -Exceptions $expired -Scope 'settings-plugin').Valid) `
        'An expired scoped exception passed.'
    Set-Content -LiteralPath $scopedList -Value @("settings-plugin $line", "test $line")
    Assert-True (@(Read-AdvisoryExceptions -Path $scopedList -Scoped -Today $today).Count -eq 2) `
        'Independent reviews for the same advisory in two scopes were rejected as duplicates.'
    foreach ($prefix in @('', 'all ', 'shipped ')) {
        Set-Content -LiteralPath $scopedList -Value "$prefix$line"
        Assert-Throws { Read-AdvisoryExceptions -Path $scopedList -Scoped -Today $today } '*no reviewed tooling scope*' `
            'An unscoped or unknown-scope tooling exception was accepted.'
    }
    # The affected settings/UTP versions from the 2026-10-01 live audit must still fail.
    $osvAnswers = @{
        'pkg:maven/io.netty/netty-handler@4.1.110.Final' = '{"vulns":[{"id":"GHSA-c4c3-7fpv-j4q5","aliases":["CVE-2026-75595"],"database_specific":{"severity":"CRITICAL"}}]}'
        'pkg:maven/org.jdom/jdom2@2.0.6' = '{"vulns":[{"id":"GHSA-2363-cqg2-863c","aliases":["CVE-2021-33813"],"database_specific":{"severity":"HIGH"}}]}'
        'pkg:maven/org.bitbucket.b_c/jose4j@0.9.5' = '{"vulns":[{"id":"GHSA-3677-xxcr-wjqv","aliases":["CVE-2024-29371"],"database_specific":{"severity":"HIGH"}}]}'
    }
    foreach ($purl in $osvAnswers.Keys) {
        $control = @(Get-SbomAdvisories -Sbom (New-GateSbom @($purl)))
        Assert-True ($control.Count -eq 1 -and $control[0].Severity.Serious -and
            -not (Test-AdvisoryFindings -Findings $control).Valid) `
            "The affected host-tool control passed: $purl"
    }
    $osvAnswers = $osvRecorded
} finally {
    Remove-Item -LiteralPath $advisoryRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[release-tooling] advisory gate contracts passed'

# --- Test-ChangelogVersions ------------------------------------------------------------------
#
# A released version's heading is the only record a reader has that it shipped. In Hushfacebook on
# 2026-09-14 a post-release commit renamed "## 0.31.0" to "## Unreleased" and the file then said
# that release never happened; nothing read the file at all. Both directions are exercised here.

$headingShapes = @"
## 0.32.0
some text
## 0.31.0 (2026-09-14)
more text
## [0.1.5](https://example.invalid/compare/v0.1.4...v0.1.5) (2026-06-01)
older text
"@
$shapes = @(Get-ChangelogVersions -Text $headingShapes)
Assert-True (($shapes -join ',') -eq '0.32.0,0.31.0,0.1.5') `
    "The heading shapes this file uses were not all read: $($shapes -join ',')"
Assert-True (@(Get-ChangelogVersions -Text "## Unreleased`n## Notes").Count -eq 0) `
    'A heading that names no version was read as one.'
# HushGram's own sections under Unreleased name a version without being a release of it.
Assert-True (@(Get-ChangelogVersions -Text "## Unreleased`n`n### HushGram v0.0.2`n`n* a change").Count -eq 0) `
    'A "### HushGram v" section under Unreleased was read as a released version.'

$previousChangelog = "## 0.31.0`nshipped`n## 0.30.2`nshipped"
$goodChangelog = "## 0.32.0`nnew`n" + $previousChangelog
$good = Test-ChangelogVersions -Current $goodChangelog -ExpectedVersion '0.32.0' `
    -Previous $previousChangelog -PreviousLabel 'tag v0.31.0'
Assert-True $good.Valid "A CHANGELOG that kept every shipped version was refused: $($good.Reason)"

# The Hushfacebook defect, exactly: the previous version's heading renamed to Unreleased.
$renamed = Test-ChangelogVersions -Current ("## 0.32.0`nnew`n## Unreleased`nshipped`n## 0.30.2`nshipped") `
    -ExpectedVersion '0.32.0' -Previous $previousChangelog -PreviousLabel 'tag v0.31.0'
Assert-True (-not $renamed.Valid) 'A released version renamed to Unreleased was accepted.'
Assert-True ($renamed.Reason -like '*0.31.0*tag v0.31.0*') `
    "The renamed heading was refused for the wrong reason: $($renamed.Reason)"

$dropped = Test-ChangelogVersions -Current "## 0.32.0`nnew`n## 0.31.0`nshipped" `
    -ExpectedVersion '0.32.0' -Previous $previousChangelog
Assert-True (-not $dropped.Valid) 'A shipped version deleted from the CHANGELOG was accepted.'

$noHeading = Test-ChangelogVersions -Current $previousChangelog -ExpectedVersion '0.32.0' `
    -Previous $previousChangelog
Assert-True (-not $noHeading.Valid) 'A CHANGELOG with no heading for the built version was accepted.'
Assert-True ($noHeading.Reason -like '*no heading for 0.32.0*') `
    "The missing heading was refused for the wrong reason: $($noHeading.Reason)"

Assert-True (-not (Test-ChangelogVersions -Current "# Changelog`nnothing here" -ExpectedVersion '0.32.0').Valid) `
    'A CHANGELOG naming no version at all was accepted.'

# With no earlier file to compare against, the version being built is still required and a
# CHANGELOG that has it is still accepted. A first release has no tag behind it.
Assert-True (Test-ChangelogVersions -Current $goodChangelog -ExpectedVersion '0.32.0').Valid `
    'A checkout with no earlier tag was refused.'

Write-Host '[release-tooling] changelog history contracts passed'

# --- Test-ChangelogManagerEntry --------------------------------------------------------------
#
# Morphe Manager skips a heading with no date and flags an app only for bullets scoped to it. In
# Hushfacebook every heading from 0.23.0 to 0.40.0 was bare and Manager's list stopped at 0.22.0,
# while the history check above passed them all. Each refusal below is the readable entry with one
# thing taken away.

$readable = @"
## Unreleased

* not held to anything yet

## 0.42.0 (2026-09-20)

A sentence about the release.

### Settings

* **Instagram:** one change.
* **Instagram:** another change.

### On the video

* **Instagram:** a third.

## 0.41.0

* an older entry, frozen as it shipped
"@
$entry = Test-ChangelogManagerEntry -Current $readable -ExpectedVersion '0.42.0'
Assert-True $entry.Valid "A dated entry with scoped bullets was refused: $($entry.Reason)"
Assert-True ($entry.Date -eq '2026-09-20' -and $entry.Bullets -eq 3) `
    "The readable entry was misread: date $($entry.Date), $($entry.Bullets) bullets"
$crlf = Test-ChangelogManagerEntry -Current ($readable -replace "`r?`n", "`r`n") -ExpectedVersion '0.42.0'
Assert-True ($crlf.Valid -and $crlf.Bullets -eq 3) "CRLF line endings changed the reading: $($crlf.Reason)"

$undated = Test-ChangelogManagerEntry -Current ($readable -replace '## 0\.42\.0 \(2026-09-20\)', '## 0.42.0') `
    -ExpectedVersion '0.42.0'
Assert-True (-not $undated.Valid) 'An undated heading, which Manager skips, was accepted.'
Assert-True ($undated.Reason -like '*no date*') "The undated heading was refused for the wrong reason: $($undated.Reason)"

$unscoped = Test-ChangelogManagerEntry -Current ($readable -replace '\* \*\*Instagram:\*\* another', '* another') `
    -ExpectedVersion '0.42.0'
Assert-True (-not $unscoped.Valid) 'A bullet Manager does not scope to Instagram was accepted.'
Assert-True ($unscoped.Reason -like 'Line 12 *') "The unscoped bullet was refused for the wrong reason: $($unscoped.Reason)"

$wrapped = Test-ChangelogManagerEntry -Current ($readable -replace 'one change\.', "one`n  change.") `
    -ExpectedVersion '0.42.0'
Assert-True (-not $wrapped.Valid) 'A wrapped bullet, whose second line Manager drops, was accepted.'
Assert-True ($wrapped.Reason -like '*continues the bullet*') "The wrapped bullet was refused for the wrong reason: $($wrapped.Reason)"

$noBullets = Test-ChangelogManagerEntry -Current "## 0.42.0 (2026-09-20)`n`nOnly prose.`n" -ExpectedVersion '0.42.0'
Assert-True (-not $noBullets.Valid) 'An entry with no scoped bullet, which gets no update badge, was accepted.'

# Hushfacebook's scope is the one a copied bullet would carry.
$otherScope = Test-ChangelogManagerEntry -Current ($readable -replace '\*\*Instagram:\*\* a third', '**Facebook:** a third') `
    -ExpectedVersion '0.42.0'
Assert-True (-not $otherScope.Valid) 'A bullet scoped to another app was accepted.'

# A development-only change is written "* **Tooling:** ...". Manager shows a line only to the app
# it's scoped to, so it shows these to nobody, which is what they're for: allowed, and not counted
# as Instagram changes.
$withTooling = Test-ChangelogManagerEntry -Current ($readable -replace '(\* \*\*Instagram:\*\* a third\.)',
    "`$1`n* **Tooling:** a development-only change.") -ExpectedVersion '0.42.0'
Assert-True ($withTooling.Valid -and $withTooling.Bullets -eq 3) `
    "A Tooling bullet in the released entry was refused or counted: $($withTooling.Reason), $($withTooling.Bullets) bullets"
$toolingOnly = Test-ChangelogManagerEntry -Current "## 0.42.0 (2026-09-20)`n`n* **Tooling:** only this.`n" -ExpectedVersion '0.42.0'
Assert-True (-not $toolingOnly.Valid -and $toolingOnly.Reason -like '*no "* **Instagram:** " bullet*') `
    "An entry with Tooling bullets alone, which gets no update badge, was not refused for that: $($toolingOnly.Reason)"
$wrappedTooling = Test-ChangelogManagerEntry -Current ($readable -replace '(\* \*\*Instagram:\*\* a third\.)',
    "`$1`n* **Tooling:** a development-only`n  change.") -ExpectedVersion '0.42.0'
Assert-True ($wrappedTooling.Reason -like '*continues the bullet*') `
    "A wrapped Tooling bullet was not held to one line like the others: $($wrappedTooling.Reason)"

Assert-True (-not (Test-ChangelogManagerEntry -Current $readable -ExpectedVersion '0.43.0').Valid) `
    'An entry for a version the CHANGELOG does not name was accepted.'

# The control. HushGram describes the version it's building under "## Unreleased" until it's
# released, so the real file either gives this version a dated heading Manager can read, or a
# "### HushGram v<version>" section under Unreleased. The release check below runs on a copy of it.
$realVersion = Get-BundleVersion -Root $Root
$realChangelog = Get-Content -LiteralPath (Join-Path $Root 'CHANGELOG.md') -Raw
if (@(Get-ChangelogVersions -Text $realChangelog) -contains $realVersion) {
    $realTag = "$(& git -C $Root describe --tags --abbrev=0 HEAD 2>$null | Select-Object -First 1)".Trim()
    $realPrevious = if ($realTag) { (& git -C $Root show "${realTag}:CHANGELOG.md" 2>$null) -join "`n" } else { '' }
    $realCheck = if ([string]::IsNullOrWhiteSpace($realPrevious)) {
        Test-ChangelogVersions -Current $realChangelog -ExpectedVersion $realVersion
    } else {
        Test-ChangelogVersions -Current $realChangelog -ExpectedVersion $realVersion -Previous $realPrevious
    }
    Assert-True $realCheck.Valid "This repository's own CHANGELOG was refused: $($realCheck.Reason)"
    $realEntry = Test-ChangelogManagerEntry -Current $realChangelog -ExpectedVersion $realVersion
    Assert-True $realEntry.Valid "Morphe Manager could not read this repository's own $realVersion entry: $($realEntry.Reason)"
} else {
    Assert-True ($realChangelog -match ("(?ms)^##\s+Unreleased\b(?:(?!^##\s).)*?^###\s+HushGram\s+v" +
            [regex]::Escape($realVersion) + '\s*$')) `
        "This repository's CHANGELOG describes $realVersion neither under a dated heading nor under Unreleased."
}

Write-Host '[release-tooling] changelog Manager contracts passed'

# --- validate-release-facts.ps1 -------------------------------------------------------------
#
# The check the pre-push hook runs on every push that touches a published file, and the one a
# release can't go out without. Driven against a copy of this checkout rather than a hand-built
# tree: a fixture assembled by hand is a second opinion about what the release files look like,
# and the thing worth catching is a real file drifting from the real catalog. One fact is moved
# per case.
#
# HushGram has no published release yet, so the copy is checked twice: as it is, with no
# patches-bundle.json, and then with the files the first release push would carry written in (an
# index, the README sentence naming the release and the CHANGELOG's dated heading).

$factsScript = Join-Path $PSScriptRoot 'validate-release-facts.ps1'
$bugFormRelative = '.github/ISSUE_TEMPLATE/bug_report.yml'
$factsRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushgram-facts-" + [guid]::NewGuid().ToString('N'))
$factsFiles = @('patches-list.json', 'gradle.properties', 'README.md', 'CHANGELOG.md', 'gradle/libs.versions.toml', $bugFormRelative)
# Both copies start from before the first release: no index, since patches-bundle.json is never
# copied, a README that says there's no release yet, and the version being built described under
# Unreleased. Once HushGram has a release, this checkout's README names it and its CHANGELOG
# dates it, and a copy naming a release with no index is refused for exactly that, so both go
# back to what a release replaces. Until 2026-09-30 the suite asserted the checkout had no index
# instead, and the hook runs it on every README change, so the index push and every push after
# it would have been refused.
$preReleaseVersion = Get-BundleVersion -Root $Root
$preReleaseSentence = "There's no release yet. Version $preReleaseVersion is the current build, and until " +
    'a release is published you build the bundle yourself (see [Building from source](#building-from-source)).'
function ConvertTo-PreReleaseText([string]$Relative, [string]$Text) {
    if ($Relative -eq 'CHANGELOG.md') {
        $version = [regex]::Escape($preReleaseVersion)
        if ($Text -match "(?ms)^##\s+Unreleased\b(?:(?!^##\s).)*?^###\s+HushGram\s+v$version\s*$") { return $Text }
        # The version's dated section, bullets unscoped again, as the section Unreleased held. Any
        # heading Test-ChangelogManagerEntry takes: "## 0.1.0 (date)", a v, a link, a word before.
        $dated = [regex]::Match($Text, ("(?ms)^##\s+(?:\S+\s+)?(?:\[v?$version\]\([^)]*\)|v?$version)\s+" +
                '\(\d{4}-\d{2}-\d{2}\)[^\r\n]*(?<body>.*?)(?=^##\s|\z)'))
        Assert-True $dated.Success ("This checkout's CHANGELOG describes $preReleaseVersion neither under Unreleased nor " +
            'under a dated heading, so the release cases have nothing to start from.')
        $section = "### HushGram v$preReleaseVersion" + ($dated.Groups['body'].Value -replace '(?m)^\* \*\*Instagram:\*\* ', '* ')
        $Text = $Text.Remove($dated.Index, $dated.Length)
        $unreleased = [regex]::Match($Text, '(?m)^##\s+Unreleased[^\r\n]*\r?\n')
        if ($unreleased.Success) { return $Text.Insert($unreleased.Index + $unreleased.Length, "`n$section") }
        return $Text.Insert($dated.Index, "## Unreleased`n`n$section")
    }
    # The bug form names the version the index publishes, which is the source's own only while no
    # newer version is being prepared. With no index the check wants the source's, so a release's
    # source commit (version bumped, index still on the last release) was refused here.
    if ($Relative -eq $bugFormRelative) {
        return [regex]::Replace($Text, '(?m)^(\s*placeholder:\s*HushGram )\d+(?:\.\d+)+( on Instagram )',
            ('${1}' + $preReleaseVersion + '${2}'))
    }
    if ($Relative -ne 'README.md' -or $Text -match "(?i)\bThere's no release yet\b") { return $Text }
    $released = '(?im)^[^\r\n]*\blatest (?:published )?release is (?:still )?\[?v\d[^\r\n]*(?=\r?$)'
    Assert-True ($Text -match $released) ("This checkout's README says neither that there's no release yet nor " +
        'which release is the latest, so the release cases have nothing to start from.')
    return [regex]::Replace($Text, $released, $preReleaseSentence.Replace('$', '$$'))
}
function Copy-PreReleaseFile([string]$Relative, [string]$To) {
    $destination = Join-Path $To $Relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    if ($Relative -notin @('CHANGELOG.md', 'README.md', $bugFormRelative)) {
        Copy-Item -LiteralPath (Join-Path $Root $Relative) -Destination $destination
        return
    }
    Set-Content -LiteralPath $destination -Encoding UTF8 -NoNewline -Value (
        ConvertTo-PreReleaseText $Relative (Get-Content -LiteralPath (Join-Path $Root $Relative) -Raw))
}
# The shapes a release could leave behind: every heading form Manager reads, and CRLF endings.
foreach ($heading in @("## $preReleaseVersion (2026-10-01)", "## v$preReleaseVersion (2026-10-01)",
        "## [$preReleaseVersion](https://github.com/SysAdminDoc/HushGram/releases) (2026-10-01)", "## HushGram $preReleaseVersion (2026-10-01)")) {
    $turned = ConvertTo-PreReleaseText 'CHANGELOG.md' "# Changelog`r`n`r`n$heading`r`n`r`n* **Instagram:** A fix.`r`n`r`n## 0.0.0 (2026-01-01)`r`n"
    Assert-True ($turned -match "(?ms)^## Unreleased\s+### HushGram v$([regex]::Escape($preReleaseVersion))\s+\* A fix\.\s+## 0\.0\.0 ") `
        "A CHANGELOG released under ""$heading"" wasn't turned back to its Unreleased shape: $turned"
}
$turned = ConvertTo-PreReleaseText 'README.md' "# HushGram`r`n`r`nThe latest release is [v$preReleaseVersion](https://x).`r`n`r`nMore.`r`n"
Assert-True ($turned -eq "# HushGram`r`n`r`n$preReleaseSentence`r`n`r`nMore.`r`n") "A README with CRLF endings wasn't turned back: $turned"
$turned = ConvertTo-PreReleaseText $bugFormRelative "    attributes:`r`n      placeholder: HushGram 0.0.0 on Instagram 1.2.3`r`n"
Assert-True ($turned -ceq "    attributes:`r`n      placeholder: HushGram $preReleaseVersion on Instagram 1.2.3`r`n") `
    "A bug form naming the published version wasn't turned to the version being built: $turned"
try {
    foreach ($relative in $factsFiles) { Copy-PreReleaseFile $relative $factsRoot }

    # What the check says, warnings and notes included. 6>&1, not *>: the notes are Write-Host, and
    # redirecting every stream also swallows the terminating error, so each refusal would pass unseen.
    # Every run but -Strict leaves the description's test counts alone, as the pre-push hook does
    # on any push that doesn't rewrite the index. Before the first release there's no description,
    # and the check leaves them alone anyway.
    function Invoke-Facts {
        param([hashtable]$Extra = @{}, [switch]$WithUrls, [switch]$Strict)
        $arguments = @{ Root = $factsRoot }
        if (-not $WithUrls) { $arguments['SkipUrlCheck'] = $true }
        if (-not $Strict) { $arguments['SkipDescriptionTestCount'] = $true }
        foreach ($key in $Extra.Keys) { $arguments[$key] = $Extra[$key] }
        $global:LASTEXITCODE = 0
        $said = @(& $factsScript @arguments 6>&1 | ForEach-Object { "$_" }) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "validate-release-facts.ps1 exited $LASTEXITCODE`: $said" }
        return $said
    }
    function Invoke-LenientFacts { Invoke-Facts @{ AllowPublishedIndexLag = $true } }
    function Set-FactsFile {
        param([string]$Name, [scriptblock]$Edit)
        $path = Join-Path $factsRoot $Name
        $text = Get-Content -LiteralPath $path -Raw
        $edited = & $Edit $text
        Assert-True ($edited -cne $text) "The edit to $Name changed nothing, so the case proves nothing."
        Set-Content -LiteralPath $path -Value $edited -Encoding UTF8 -NoNewline
    }
    # The copy's own file put back, which is this checkout's until the index cases below write theirs.
    $factsBaseline = @{}
    function Save-FactsBaseline {
        foreach ($relative in @($factsFiles) + @('patches-bundle.json')) {
            $path = Join-Path $factsRoot $relative
            $factsBaseline[$relative] = if (Test-Path -LiteralPath $path) { [System.IO.File]::ReadAllBytes($path) } else { $null }
        }
    }
    function Reset-FactsFile {
        param([string]$Name)
        $path = Join-Path $factsRoot $Name
        if ($null -eq $factsBaseline[$Name]) { Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue }
        else { [System.IO.File]::WriteAllBytes($path, $factsBaseline[$Name]) }
    }
    # One fact moved: the check refuses it, naming it, and the file is put back whatever happened.
    function Assert-FactRefused {
        param([string]$Name, [scriptblock]$Edit, [string]$Pattern, [string]$Case, [switch]$Lenient)
        Set-FactsFile $Name $Edit
        try {
            Assert-Throws { Invoke-Facts } $Pattern "The release check accepted $Case."
            if ($Lenient) {
                Assert-Throws { Invoke-LenientFacts } $Pattern "The lenient release check accepted $Case."
            }
        } finally {
            Reset-FactsFile $Name
        }
    }
    Save-FactsBaseline

    $catalogHere = Get-Content -LiteralPath (Join-Path $factsRoot 'patches-list.json') -Raw | ConvertFrom-Json
    $versionHere = ([string]$catalogHere.version).TrimStart('v')
    $countHere = @($catalogHere.patches).Count
    $targetHere = Get-PatchTarget -PatchList $catalogHere
    $buildHere = $targetHere.PackageVersion
    $codeHere = [string]@($targetHere.PackageVersionCodes[$buildHere])[0]
    $floorHere = (Read-CatalogToolchain -Source 'the copied catalog' `
        -Text (Get-Content -LiteralPath (Join-Path $factsRoot 'gradle/libs.versions.toml') -Raw)).ManagerFloor
    Assert-True ($codeHere -match '^\d+$') "The catalog pins no version code for Instagram $buildHere, so the build placeholder case proves nothing."

    # The control, before the first release: every fact the copy can be held to without an index
    # agrees, and the check says what it left out.
    $said = Invoke-Facts
    Assert-True ($said -like '*no patches-bundle.json yet, so HushGram has no published release*' -and
        $said -like "*README's version badge names $versionHere, and it names no release yet*" -and
        $said -like "*README requires Morphe Manager $floorHere or newer*" -and
        $said -like "*the CHANGELOG describes $versionHere under Unreleased*" -and
        $said -like "*v$versionHere`: $countHere patches for com.instagram.android $buildHere*") `
        "The release check did not pass this checkout's copy before the first release, or did not say what it left out: $said"
    # A release can't be checked against an index that isn't there.
    Assert-Throws { Invoke-Facts @{ VerifyPublishedAsset = $true } } '*no patches-bundle.json, so there is no published release to check*' `
        'A published asset check ran with no index.'

    # Each fact the pre-release check holds, moved one at a time.
    $noIndexCases = @(
        @{ File = 'gradle.properties'; Case = 'a Gradle version that is not the catalog''s'; Pattern = "*gradle.properties does not match v$versionHere*"
            Edit = { param($text) $text -replace '(?m)^(\s*version\s*=\s*)\S+', '${1}0.0.9' } },
        @{ File = 'README.md'; Case = 'a version badge picture naming another version'; Pattern = '*version badge names*0.1.0*'
            Edit = { param($text) $text -replace 'badge/version-\d+(?:\.\d+)+-', 'badge/version-0.1.0-' } },
        @{ File = 'README.md'; Case = 'a version badge alt text naming another version'; Pattern = '*version badge names*0.1.0*'
            Edit = { param($text) $text -replace 'alt="Version \d+(?:\.\d+)+"', 'alt="Version 0.1.0"' } },
        @{ File = 'README.md'; Case = 'no version badge'; Pattern = '*no version badge*'
            Edit = { param($text) $text -replace '<img src="https://img\.shields\.io/badge/version-[^>]*>', '' } },
        @{ File = 'README.md'; Case = 'a current build that is not the source'; Pattern = '*says version 0.1.0 is the current build*'
            Edit = { param($text) $text -replace '\bVersion \d+(?:\.\d+)+ is the current build\b', 'Version 0.1.0 is the current build' } },
        @{ File = 'README.md'; Case = 'a latest release with no index publishing one'
            Pattern = '*names v0.0.1 as the latest release, and there is no patches-bundle.json*'
            Edit = { param($text) $text -replace "There's no release yet\.",
                'The latest release is [v0.0.1](https://github.com/SysAdminDoc/HushGram/releases/tag/v0.0.1), with 5 patches.' } },
        @{ File = 'README.md'; Case = 'another Instagram build'; Pattern = '*README target version does not match*'
            Edit = { param($text) $text.Replace("Instagram $buildHere", 'Instagram 448.0.0.1.1') } },
        @{ File = 'README.md'; Case = 'no package name'; Pattern = '*README package name does not match*'
            Edit = { param($text) $text.Replace('com.instagram.android', 'com.example.app') } },
        @{ File = 'README.md'; Case = 'an install step naming an older Manager'; Pattern = '*names Morphe Manager 1.20.0 or newer*'
            Edit = { param($text) $text -replace ('(\[Morphe Manager\]\([^)\s]+\)\s+)' + [regex]::Escape($floorHere) + ' or newer'), '${1}1.20.0 or newer' } },
        @{ File = 'README.md'; Case = 'a badge picture showing an older Manager'; Pattern = '*README badge Manager floor*'
            Edit = { param($text) $text.Replace("Morphe%20Manager%20$floorHere%2B", 'Morphe%20Manager%201.20.0%2B') } },
        @{ File = 'README.md'; Case = 'a badge alt text naming no Manager'; Pattern = '*README badge alt Manager floor*'
            Edit = { param($text) $text.Replace("alt=`"For Morphe Manager $floorHere or newer`"", 'alt="For Morphe Manager"') } },
        @{ File = 'README.md'; Case = 'an install step reworded past the stale-floor check'; Pattern = '*README install step Manager floor*'
            Edit = { param($text) $text -replace ('(?m)^(\d+\.\s+Install \[Morphe Manager\]\([^)\s]+\))\s+' + [regex]::Escape($floorHere) +
                '\s+or newer\.'), '${1}, version 1.40.0 or later.' } },
        @{ File = 'gradle/libs.versions.toml'; Case = 'a README naming the floor the catalog pinned before'
            Pattern = "*README names Morphe Manager $floorHere or newer, but patcher*needs 1.99.0*"
            Edit = { param($text) $text -replace '(?m)^(\s*manager-floor\s*=\s*)"[^"]+"', '${1}"1.99.0"' } },
        @{ File = 'CHANGELOG.md'; Case = 'no section for the version being built'; Pattern = "*does not describe $versionHere, the version this checkout builds*"
            Edit = { param($text) $text -replace ('(?m)^###\s+HushGram\s+v' + [regex]::Escape($versionHere) + '\s*$'), '### HushGram v9.9.9' } },
        # The checkout may already scope its Unreleased bullets, so the case unscopes the first one
        # itself rather than relying on the section's wording.
        @{ File = 'CHANGELOG.md'; Case = 'a dated heading Manager can''t scope'; Pattern = "*Morphe Manager cannot show this release: Line * is a $versionHere bullet*"
            Edit = { param($text)
                $dated = $text -replace ('(?ms)^## Unreleased\s+###\s+HushGram\s+v' + [regex]::Escape($versionHere) + '\s*$'),
                    "## $versionHere (2026-09-30)"
                $at = $dated.IndexOf("## $versionHere (2026-09-30)")
                ([regex]'(?m)^\* \*\*(?:Instagram|Tooling):\*\* ').Replace($dated, '* ', 1, [Math]::Max(0, $at)) } },
        @{ File = $bugFormRelative; Case = 'a bug form naming another HushGram version'; Pattern = '*bug report form version placeholder*'
            Edit = { param($text) $text -replace '(placeholder:\s*HushGram )\S+', '${1}0.0.1' } },
        @{ File = $bugFormRelative; Case = 'a bug form naming another Instagram build'; Pattern = '*bug report form version placeholder*'
            Edit = { param($text) $text -replace '(placeholder:\s*HushGram \S+ on Instagram )\S+', '${1}448.0.0.1.1' } },
        @{ File = $bugFormRelative; Case = 'a bug form naming another version code'; Pattern = '*bug report form build placeholder*'
            Edit = { param($text) $text.Replace("build $codeHere", 'build 385511870') } },
        @{ File = $bugFormRelative; Case = 'a bug form naming a Manager below the floor'; Pattern = '*bug report form Manager placeholder*'
            Edit = { param($text) $text -replace '(placeholder:\s*Morphe Manager )\S+', '${1}1.20.0' } })
    foreach ($case in $noIndexCases) {
        Assert-FactRefused -Name $case.File -Edit $case.Edit -Pattern $case.Pattern -Case "$($case.Case), before the first release" -Lenient
    }
    # And the CHANGELOG as a release gives it: a dated heading with Instagram-scoped bullets is read
    # the way Manager reads it, Tooling bullets allowed and not counted.
    Set-FactsFile 'CHANGELOG.md' {
        param($text) "# Changelog`n`n## $versionHere (2026-09-30)`n`n* **Instagram:** One change.`n* **Tooling:** A release check.`n`n## 0.0.1 (2026-09-20)`n`n* **Instagram:** The first set.`n"
    }
    try {
        $said = Invoke-Facts
        Assert-True ($said -like "*Morphe Manager can read the $versionHere entry: dated 2026-09-30, 1 bullets scoped Instagram*") `
            "A dated CHANGELOG entry was not read the way Manager reads it: $said"
    } finally {
        Reset-FactsFile 'CHANGELOG.md'
    }

    # A bundle built here, which the check holds to the patcher the catalog pins. Stamped by another
    # patcher, Manager at the README's floor refuses it.
    # Test results as Gradle writes them, one class per file, for the cases below.
    $runtimeResults = 'extensions/instagram/build/test-results/testDebugUnitTest'
    $patchResults = 'patches/build/test-results/test/direct'
    function Write-FactsResults {
        param([string]$Folder, [string]$Suite, [int]$Tests, [int]$Skipped = 0, [string]$Under = $factsRoot)
        $directory = Join-Path $Under $Folder
        Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        $cases = (1..$Tests | ForEach-Object {
            if ($_ -le $Skipped) { "<testcase name=`"t$_`" classname=`"fixture.$Suite`"><skipped/></testcase>" }
            else { "<testcase name=`"t$_`" classname=`"fixture.$Suite`"/>" }
        }) -join ''
        Set-Content -LiteralPath (Join-Path $directory "TEST-fixture.$Suite.xml") -Encoding UTF8 -Value (
            "<?xml version=`"1.0`" encoding=`"UTF-8`"?><testsuite name=`"fixture.$Suite`" tests=`"$Tests`" " +
            "skipped=`"$Skipped`" failures=`"0`" errors=`"0`">$cases</testsuite>")
    }

    $factsBundle = Get-ReleaseBundlePath -Root $factsRoot -Version $versionHere
    $pinnedHere = (Read-CatalogToolchain -Source 'the copied catalog' `
        -Text (Get-Content -LiteralPath (Join-Path $factsRoot 'gradle/libs.versions.toml') -Raw)).PatcherVersion
    New-Item -ItemType Directory -Path (Split-Path -Parent $factsBundle) -Force | Out-Null
    try {
        foreach ($stamp in @($pinnedHere, '9.9.9')) {
            Remove-Item -LiteralPath $factsBundle -Force -ErrorAction SilentlyContinue
            New-TestBundleArchive -Path $factsBundle -Entries ([ordered]@{
                'META-INF/MANIFEST.MF' = "Manifest-Version: 1.0`nVersion: $versionHere`nTimestamp: 0`nPatcher-Version: $stamp`n`n" })
            if ($stamp -eq $pinnedHere) {
                $said = Invoke-Facts
                Assert-True ($said -like "*the bundle stamps patcher $pinnedHere, as the catalog pins*") `
                    "A bundle stamped by the pinned patcher was not compared, or was refused: $said"
            } else {
                Assert-Throws { Invoke-Facts } "*The bundle stamps Patcher-Version 9.9.9 but the catalog pins morphe-patcher $pinnedHere*" `
                    'A bundle stamped by another patcher was accepted.'
            }
        }
        # The same old bundle, and a run with a skipped test beside it, on the hook's own set for an
        # ordinary push (the in-place branch and the worktree branch both take it). Both belong to
        # whatever this checkout built last, so a push that only moved the README or the pin is not
        # refused for them. Without -SkipLocalBuild this set is refused twice over.
        Write-FactsResults $runtimeResults 'RuntimeTest' 3 -Skipped 1
        $ordinaryPush = Get-ReleaseFactsArguments -Push Ordinary -Root $factsRoot
        Assert-True (-not $ordinaryPush.Contains('SkipUrlCheck')) 'The hook skips the URL checks on an ordinary push.'
        $ordinaryPush['SkipUrlCheck'] = $true
        $global:LASTEXITCODE = 0
        $said = @(& $factsScript @ordinaryPush 6>&1 | ForEach-Object { "$_" }) -join "`n"
        Assert-True ($LASTEXITCODE -eq 0 -and $said -like '*the bundle in patches/build/release was left unread*' -and
            $said -like '*the test results here were left unread*') `
            "The hook's check for an ordinary push read this checkout's old bundle or test results: $said"
        $ordinaryPush.Remove('SkipLocalBuild')
        Assert-Throws { & $factsScript @ordinaryPush 6>&1 | Out-Null } '*skipped=1*' `
            'An ordinary push without -SkipLocalBuild read past a skipped test, so the case above proves nothing.'
    } finally {
        Remove-Item -LiteralPath (Join-Path $factsRoot 'extensions') -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath (Join-Path $factsRoot 'patches') -Recurse -Force -ErrorAction SilentlyContinue
    }

    # The hook's worktree branch runs the pushed commit's own check, and a commit from before
    # 1787137 carries one that knows -SkipLocalBuild only as -SkipTestResults. The set spelled for
    # that check has to get through pwsh -File the way the hook passes it.
    $olderCheck = Join-Path $factsRoot 'older-check.ps1'
    try {
        Set-Content -LiteralPath $olderCheck -Encoding UTF8 -Value @'
param([string]$Root, [switch]$SkipDescriptionTestCount, [switch]$SkipTestResults, [switch]$AllowPublishedIndexLag)
"bound $(@($PSBoundParameters.Keys | Sort-Object) -join ',')"
'@
        $olderSet = Get-ReleaseFactsArguments -Push Ordinary -Root $factsRoot -Script $olderCheck
        $said = "$(& pwsh -NoProfile -File $olderCheck @(ConvertTo-ScriptArguments $olderSet))"
        Assert-True ($LASTEXITCODE -eq 0 -and $said -eq 'bound AllowPublishedIndexLag,Root,SkipDescriptionTestCount,SkipTestResults') `
            "The ordinary set spelled for a check from before -SkipLocalBuild didn't bind: $said"
        Assert-True ((Get-ReleaseFactsArguments -Push Ordinary -Root $factsRoot -Script $factsScript).Contains('SkipLocalBuild')) `
            "The ordinary set spelled for this checkout's own check lost -SkipLocalBuild."
        Set-Content -LiteralPath $olderCheck -Encoding UTF8 -Value 'param([string]$Root, [switch]$SkipDescriptionTestCount, [switch]$AllowPublishedIndexLag)'
        Assert-Throws { Get-ReleaseFactsArguments -Push Ordinary -Root $factsRoot -Script $olderCheck } '*takes no -SkipLocalBuild*' `
            'A check that knows -SkipLocalBuild by neither name was handed the set anyway.'
    } finally {
        Remove-Item -LiteralPath $olderCheck -Force -ErrorAction SilentlyContinue
    }

    # Runtime test results that are here get read even when no description quotes them: a skipped
    # test fails the lenient check, and the pre-push hook's ordinary set leaves them unread.
    try {
        Write-FactsResults $runtimeResults 'RuntimeTest' 4 -Skipped 1
        Assert-Throws { Invoke-Facts } '*skipped=1*' 'A check before the first release read past a skipped runtime test.'
        $said = Invoke-Facts @{ SkipLocalBuild = $true }
        Assert-True ($said -like '*the test results here were left unread*') "A check told to leave the test results unread read them: $said"
    } finally {
        Remove-Item -LiteralPath (Join-Path $factsRoot 'extensions') -Recurse -Force -ErrorAction SilentlyContinue
    }

    Write-Host '[release-tooling] release facts before the first release passed'

    # --- the first release, written in ---------------------------------------------------------
    #
    # What the index push after the release carries: patches-bundle.json pointing at the release
    # asset, the README naming the release and its add-source link instead of saying there's none,
    # and the CHANGELOG's dated heading. The description quotes the test counts, which only a check
    # holding the description to them reads.
    $slugHere = 'SysAdminDoc/HushGram'
    $releaseUrl = "https://github.com/$slugHere/releases/tag/v$versionHere"
    $addSource = "https://morphe.software/add-source?github=$([Uri]::EscapeDataString($slugHere))"
    $runtimeQuoted = 5
    $patchQuoted = 7
    $indexDocument = [ordered]@{
        created_at = '2026-09-30T12:00:00'
        description = ("## [$versionHere](https://github.com/$slugHere/compare/v0.0.1...v$versionHere) (2026-09-30)`n`n" +
            "### Features`n`n* **Instagram:** Every change, one line each.`n`n" +
            "### Validation`n`nValidation: $runtimeQuoted runtime tests passed locally. All $patchQuoted patch tests passed too.")
        download_url = "https://github.com/$slugHere/releases/download/v$versionHere/patches-$versionHere.mpp"
        signature_download_url = ''
        version = $versionHere
    }
    [System.IO.File]::WriteAllText((Join-Path $factsRoot 'patches-bundle.json'), ($indexDocument | ConvertTo-Json -Depth 4),
        (New-Object System.Text.UTF8Encoding($false)))
    $readmeHere = Get-Content -LiteralPath (Join-Path $factsRoot 'README.md') -Raw
    $noReleaseSentence = [regex]::Match($readmeHere, "(?m)There's no release yet\.[^\r\n]*")
    Assert-True $noReleaseSentence.Success "The README no longer says there's no release yet, so this case can't write the release in."
    $releasedReadme = $readmeHere.Replace($noReleaseSentence.Value,
        "The latest release is [v$versionHere]($releaseUrl), with $countHere patches. Add it to Morphe Manager with [this link]($addSource), and it offers all $countHere patches.")
    Set-Content -LiteralPath (Join-Path $factsRoot 'README.md') -Encoding UTF8 -NoNewline -Value $releasedReadme
    Set-Content -LiteralPath (Join-Path $factsRoot 'CHANGELOG.md') -Encoding UTF8 -NoNewline -Value (
        "# Changelog`n`n## $versionHere (2026-09-30)`n`n* **Instagram:** Every change, one line each.`n`n" +
        "## 0.0.1 (2026-09-20)`n`n* **Instagram:** An earlier release.`n")
    Save-FactsBaseline

    $said = Invoke-Facts
    Assert-True ($said -like "*README's version badge names $versionHere, and it names v$versionHere as the latest release*" -and
        $said -like "*Morphe Manager can read the $versionHere entry*") `
        "The release check refused the copy with its first release written in: $said"

    $indexCases = @(
        @{ File = 'patches-bundle.json'; Case = 'an index publishing another version'; Pattern = "*patches-bundle.json version does not match v$versionHere*"
            Edit = { param($text) $text -replace '"version":\s*"[^"]+"', '"version": "0.0.1"' } },
        @{ File = 'patches-bundle.json'; Case = 'a description counting other patches'; Pattern = '*bundle description patch count*'
            Edit = { param($text) $text.Replace('Validation: ', "It has $($countHere + 1) patches. Validation: ") } },
        @{ File = 'patches-bundle.json'; Case = 'a description counting two ways'; Pattern = "*names $($countHere + 1) and $($countHere + 2) patches, and it has to name one count*"
            Edit = { param($text) $text.Replace('Validation: ', "It has $($countHere + 1) patches and $($countHere + 2) patches. Validation: ") } },
        @{ File = 'patches-bundle.json'; Case = 'a description naming another build'; Pattern = '*bundle description target version*'
            Edit = { param($text) $text.Replace('Validation: ', 'Built for Instagram 448.0.0.1.1. Validation: ') } },
        @{ File = 'patches-bundle.json'; Case = 'an address that is not the release asset'; Pattern = '*patches-bundle.json download URL does not match*'
            Edit = { param($text) $text -replace 'https://github\.com/SysAdminDoc/HushGram/releases/download/[^"]+', 'http://127.0.0.1:1/patches.mpp' } },
        @{ File = 'patches-bundle.json'; Case = 'an address over plain HTTP'; Pattern = '*must use HTTPS*'
            Edit = { param($text) $text.Replace('"https://github.com/', '"http://github.com/') } },
        @{ File = 'patches-bundle.json'; Case = 'an address on another host'; Pattern = '*must be on github.com*'
            Edit = { param($text) $text.Replace('"https://github.com/', '"https://example.com/') } },
        @{ File = 'patches-bundle.json'; Case = 'a description opening on another version'; Pattern = '*bundle description version heading*'
            Edit = { param($text) $text -replace '## \[[^\]]+\]', '## [0.0.1]' } },
        @{ File = 'patches-bundle.json'; Case = 'a description with no version heading'; Pattern = '*bundle description version heading*'
            Edit = { param($text) $text -replace '## \[[^\]]+\]\([^)]*\) \([^)]*\)', 'HushGram' } },
        @{ File = 'README.md'; Case = 'a README still saying there''s no release'; Pattern = "*README still says there's no release yet*"
            Edit = { param($text) $text + "`nThere's no release yet.`n" } },
        @{ File = 'README.md'; Case = 'a README naming no latest release'; Pattern = '*does not say which release is the latest*'
            Edit = { param($text) $text -replace 'The latest release is \[v[^\]]+\]\([^)\s]*\), with \d+ patches\.', 'Releases are on GitHub.' } },
        @{ File = 'README.md'; Case = 'a latest release linked to another tag'; Pattern = '*links it to*/tag/v0.1.0*'
            Edit = { param($text) $text -replace '(latest release is \[v\d+(?:\.\d+)+\]\([^)\s]*/tag/v)\d+(?:\.\d+)+', '${1}0.1.0' } },
        @{ File = 'README.md'; Case = 'a latest release counting other patches'; Pattern = '*latest release has 13 patches*'
            Edit = { param($text) $text -replace '(latest release is \[v[^\]]+\]\([^)\s]*\), with )\d+( patches)', '${1}13${2}' } },
        @{ File = 'README.md'; Case = 'a README without the add-source link'; Pattern = '*README Morphe add-source link*'
            Edit = { param($text) $text.Replace($addSource, 'https://morphe.software/') } },
        @{ File = 'CHANGELOG.md'; Case = 'a released version left under Unreleased'; Pattern = "*no heading for $versionHere*"
            Edit = { param($text) $text.Replace("## $versionHere (2026-09-30)", "## Unreleased`n`n### HushGram v$versionHere") } })
    foreach ($case in $indexCases) {
        Assert-FactRefused -Name $case.File -Edit $case.Edit -Pattern $case.Pattern -Case "$($case.Case), with an index"
    }
    # Manager decodes created_at as a LocalDateTime: a zone, an offset or anything but a clock time
    # fails its decoding with a generic "remote metadata file is unavailable".
    foreach ($invalidTimestamp in @('"2026-09-30T12:00:00Z"', '"2026-09-30T12:00:00+00:00"', '"2026-02-30T12:00:00"',
            '"2026-09-30"', '""', 'null', '1790000000')) {
        Assert-FactRefused -Name 'patches-bundle.json' -Pattern '*created_at*' -Case "an index with created_at $invalidTimestamp" -Edit {
            param($text) $text -replace '"created_at"\s*:\s*("[^"\r\n]*"|null|\d+)', ('"created_at": ' + $invalidTimestamp)
        }
    }

    # The next version being prepared over the published index: the version and the CHANGELOG move
    # first, and the index keeps naming the release until its own push. The lenient check the hook
    # runs on that source push accepts it, the new version described under Unreleased; the strict
    # one refuses it.
    $nextVersion = "$(([version]$versionHere).Major).$(([version]$versionHere).Minor).$(([version]$versionHere).Build + 1)"
    Set-FactsFile 'gradle.properties' { param($text) $text -replace '(?m)^(\s*version\s*=\s*)\S+', "`${1}$nextVersion" }
    Set-FactsFile 'patches-list.json' { param($text) $text.Replace("`"v$versionHere`"", "`"v$nextVersion`"") }
    Set-FactsFile 'README.md' { param($text) $text -replace 'badge/version-\d+(?:\.\d+)+-', "badge/version-$nextVersion-" -replace
        'alt="Version \d+(?:\.\d+)+"', "alt=`"Version $nextVersion`"" }
    Set-FactsFile 'CHANGELOG.md' { param($text) $text.Replace("## $versionHere (", "## Unreleased`n`n### HushGram v$nextVersion`n`n* A change on its way.`n`n## $versionHere (") }
    try {
        $said = Invoke-LenientFacts
        Assert-True ($said -like "*source $nextVersion is being prepared while the working index remains on $versionHere*" -and
            $said -like "*the CHANGELOG describes $nextVersion under Unreleased*" -and
            $said -like "*Morphe Manager can read the $versionHere entry*") `
            "The lenient check refused the next version being prepared over the published index: $said"
        Assert-Throws { Invoke-Facts } "*patches-bundle.json version does not match v$nextVersion*" `
            'The strict check accepted an index that lags the source.'
        # The published index still holds the old layout, an intro line and no version heading. While
        # it lags the source that description is history and passes as it is. Once this release
        # wrote the index (the strict run, and the 'no version heading' case above) it can't.
        Set-FactsFile 'patches-bundle.json' { param($text) $text -replace '"description":\s*"[^"]*"', '"description": "HushGram v0.0.1: 3 patches for Instagram 1.2.3 (com.instagram.android)."' }
        $said = Invoke-LenientFacts
        Assert-True ($said -like "*source $nextVersion is being prepared while the working index remains on $versionHere*") `
            "The lenient check refused an old-layout description on a lagging index: $said"
        Assert-Throws { Invoke-Facts } "*patches-bundle.json version does not match v$nextVersion*" `
            'The strict check accepted an index that lags the source.'
        Reset-FactsFile 'patches-bundle.json'
        # And the published version's heading can't go while the index still publishes it.
        Set-FactsFile 'CHANGELOG.md' { param($text) $text.Replace("## $versionHere (2026-09-30)", 'Some prose.') }
        Assert-Throws { Invoke-LenientFacts } "*no heading for $versionHere*" `
            'The lenient check accepted a CHANGELOG that dropped the version the index publishes.'
    } finally {
        foreach ($name in @('gradle.properties', 'patches-list.json', 'README.md', 'CHANGELOG.md', 'patches-bundle.json')) { Reset-FactsFile $name }
    }

    # A dead link: the address has every right shape and names a release nobody published. GitHub
    # answers 404 for it; a stand-in for the request does here, so the case needs no network.
    & {
        function Invoke-WebRequest { throw (New-NotFoundAnswer) }
        Assert-Throws { Invoke-Facts -WithUrls } `
            "*indexed bundle URL https://github.com/$slugHere/*answered HTTP 404*" 'An index naming a release nobody published was accepted.'
    }

    # The test counts the description quotes, read only by a check that holds the description to
    # them: a release, or the push that rewrites the index. One fact moves per case.
    try {
        Assert-Throws { Invoke-Facts -Strict } '*No runtime test results found*' 'A strict check ran with no runtime test results.'
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted
        Write-FactsResults $patchResults 'PatchTest' $patchQuoted
        $said = Invoke-Facts -Strict
        Assert-True ($said -like "*$runtimeQuoted runtime tests, $patchQuoted patch tests*") `
            "The strict check did not count the results the description quotes: $said"
        # A second top-level test class in one Kotlin file gets a result of its own. It is one of
        # the run's classes, neither left over from a deleted one nor optional. The nested class is
        # part of PatchTest and needs no result.
        $kotlinTests = Join-Path $factsRoot 'patches/src/test/kotlin/fixture'
        New-Item -ItemType Directory -Path $kotlinTests -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $kotlinTests 'PatchTest.kt') -Encoding UTF8 -Value (
            "package fixture`n`nclass PatchTest {`n    class NestedHelperTest`n}`n`n" +
            "@RunWith(Parameterized::class)`nclass PatchRefusalTest(private val variant: String)`n")
        Write-FactsResults $patchResults 'PatchTest' ($patchQuoted - 1)
        Set-Content -LiteralPath (Join-Path $factsRoot "$patchResults/TEST-fixture.PatchRefusalTest.xml") -Encoding UTF8 -Value (
            '<?xml version="1.0" encoding="UTF-8"?><testsuite name="fixture.PatchRefusalTest" tests="1" ' +
            'skipped="0" failures="0" errors="0"><testcase name="t1" classname="fixture.PatchRefusalTest"/></testsuite>')
        $said = Invoke-Facts -Strict
        Assert-True ($said -like "*$patchQuoted patch tests*") `
            "A second test class in one Kotlin file was refused or left out of the count: $said"
        Write-FactsResults $patchResults 'PatchTest' $patchQuoted
        Assert-Throws { Invoke-Facts -Strict } '*missing 1 of 2 test classes*PatchRefusalTest*' `
            'A run without the second test class a Kotlin file declares was accepted.'
        Remove-Item -LiteralPath (Join-Path $factsRoot 'patches/src') -Recurse -Force
        Write-FactsResults $patchResults 'PatchTest' $patchQuoted -Skipped 1
        Assert-Throws { Invoke-Facts -Strict } '*skipped 1 test*' 'A strict check accepted a skipped fixture test.'
        Write-FactsResults $patchResults 'PatchTest' ($patchQuoted + 1)
        Assert-Throws { Invoke-Facts -Strict } '*patch test count*' 'A description quoting a patch test count the run does not have was accepted.'
        Write-FactsResults $patchResults 'PatchTest' $patchQuoted
        Write-FactsResults $runtimeResults 'RuntimeTest' ($runtimeQuoted - 1)
        Assert-Throws { Invoke-Facts -Strict } '*test count*' 'A description quoting a runtime test count the run does not have was accepted.'
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted
        Remove-Item -LiteralPath (Join-Path $factsRoot 'patches') -Recurse -Force
        Assert-Throws { Invoke-Facts -Strict } '*No patch test results*' 'A strict check ran with no patch test results.'
        Assert-Throws { Invoke-Facts -Strict @{ SkipLocalBuild = $true } } '*SkipDescriptionTestCount*' `
            'A check holding the description to its counts left the results unread.'
        Assert-Throws { Invoke-Facts @{ SkipLocalBuild = $true; VerifyPublishedAsset = $true } } '*SkipLocalBuild leaves the bundle*' `
            'A published asset check was told to leave the bundle built here unread.'
        Assert-Throws { Invoke-Facts @{ SkipLocalBuild = $true; ArtifactPath = (Join-Path $factsRoot 'patches-9.9.9.mpp') } } `
            '*SkipLocalBuild leaves the bundle*' 'A check handed a bundle to compare was told to leave the bundle built here unread.'
    } finally {
        foreach ($folder in @('patches', 'extensions')) {
            Remove-Item -LiteralPath (Join-Path $factsRoot $folder) -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    # And the same tree, once every fact is put back, is accepted again. Without this the cases
    # above would also pass against a fixture that had become permanently broken.
    $said = Invoke-Facts
    Assert-True ($said -like "*v$versionHere`: $countHere patches*") "The release check refused the copy after every change was put back: $said"
} finally {
    Remove-Item -LiteralPath $factsRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[release-tooling] release facts with an index passed'

# --- a release root ------------------------------------------------------------------------------
#
# The release files of this checkout, copied into a fixture repository and committed, the way the
# tree looks at a release's source commit. The release check reads the commit a receipt names out
# of git, so a copied tree that isn't a repository never reaches the receipt. The receipt builder
# runs here too, on stand-ins for the tools it starts, and then the index push that follows a
# release, against a bare repository standing in for GitHub.

$releaseRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushgram-release-" + [guid]::NewGuid().ToString('N'))
try {
    $releaseRepo = Join-Path $releaseRoot 'repo'
    # The source ledger and the two files its rules hold an adopted source to go in as well: a
    # release is held to the census, and .gitignore has to keep the receipt and the build out.
    $releaseFiles = @('patches-list.json', 'gradle.properties', 'README.md', 'CHANGELOG.md', 'gradle/libs.versions.toml',
        $bugFormRelative, '.gitignore', 'sources/instagram-sources.json', 'NOTICE', 'provenance.json',
        'sources/carried-library-licenses.json', 'scripts/canonical-build-inputs.txt')
    foreach ($relative in $releaseFiles) { Copy-PreReleaseFile $relative $releaseRepo }
    $releaseLicenseLedger = Join-Path $releaseRepo 'sources/carried-library-licenses.json'
    $licenseFixtures = Get-Content -LiteralPath $releaseLicenseLedger -Raw | ConvertFrom-Json
    # Stand-in artifact records let the advisory failure tests reach OSV. These hashes belong to
    # test fixtures, not publisher binaries, and never enter the repository's reviewed ledger.
    foreach ($fixture in @(@{ group = 'com.google.code.gson'; name = 'gson'; version = '2.8.8' },
            @{ group = 'com.example'; name = 'unknown'; version = '1.0' })) {
        $licenseFixtures.artifacts += [ordered]@{ purl = "pkg:maven/$($fixture.group)/$($fixture.name)@$($fixture.version)"
            file = "$($fixture.name)-$($fixture.version).jar"; sha256 = ('2' * 64)
            license = @{ id = 'Apache-2.0'; url = 'https://www.apache.org/licenses/LICENSE-2.0.txt' }
            evidence = @{ url = ('https://repo.maven.apache.org/maven2/' + $fixture.group.Replace('.', '/') + '/' +
                $fixture.name + '/' + $fixture.version + '/' + $fixture.name + '-' + $fixture.version + '.pom')
                sha256 = ('3' * 64); declaredLicense = 'test publisher license fixture' } }
    }
    $baselineLicenses = @($licenseFixtures.artifacts | Where-Object purl -CEQ 'pkg:maven/com.google.code.gson/gson@2.14.0')
    $baselineLicenseLedger = [ordered]@{schemaVersion = 1; artifacts = $baselineLicenses}
    [IO.File]::WriteAllText($releaseLicenseLedger, ($baselineLicenseLedger | ConvertTo-Json -Depth 12),
        [Text.UTF8Encoding]::new($false))
    # These stand-ins have no compiler inputs. Their explicit boundary still includes every
    # fixture production input and its own policy, separately from the all-tracked audit binding.
    [IO.File]::WriteAllText((Join-Path $releaseRepo 'scripts/canonical-build-inputs.txt'),
        "source file NOTICE`ncatalog file patches-list.json`ntoolchain file gradle.properties`ntoolchain tree gradle`ntoolchain file scripts/canonical-build-inputs.txt`n")
    # The ledger, dated today, so the census a release is held to passes and each case below is
    # refused for its own fact. The checked-in ledger's date moves with every audit.
    $releaseLedgerPath = Join-Path $releaseRepo 'sources/instagram-sources.json'
    $releaseLedgerSource = [System.IO.File]::ReadAllText($releaseLedgerPath)
    function Save-ReleaseLedger([int]$AgeDays = 0) {
        $document = $releaseLedgerSource | ConvertFrom-Json
        # The UTC date, the one the ledger's rules read as today.
        $checked = [datetime]::UtcNow.Date.AddDays(-$AgeDays).ToString('yyyy-MM-dd')
        $document.census.checkedAt = $checked
        foreach ($index in @($document.indexes)) { if ($index.hushgram) { $index.hushgram.checked = $checked } }
        foreach ($record in @(@($document.entries) + @($document.outOfScope))) {
            if ($record -and $record.PSObject.Properties['lastChecked']) { $record.lastChecked = $checked }
        }
        [System.IO.File]::WriteAllText($releaseLedgerPath, ($document | ConvertTo-Json -Depth 20),
            (New-Object System.Text.UTF8Encoding($false)))
    }
    Save-ReleaseLedger
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('init', '--quiet') | Out-Null
    $releaseGitDir = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('rev-parse', '--absolute-git-dir') |
        Select-Object -First 1)".Trim()
    Assert-True ($releaseGitDir -and ([IO.Path]::GetFullPath($releaseGitDir).TrimEnd('\', '/') -ieq
            [IO.Path]::GetFullPath((Join-Path $releaseRepo '.git')).TrimEnd('\', '/'))) `
        "The release fixture resolved to $releaseGitDir, not its own repository. Refusing to write."
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', 'user.email', 'contracts@example.invalid') | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', 'user.name', 'Contracts') | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', 'core.autocrlf', 'false') | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('add', '-A') | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('commit', '-m', 'release', '--quiet') | Out-Null
    $releaseCommitted = @(Invoke-FixtureGit -Root $releaseRepo -Arguments @('show', '--name-only', '--format=', 'HEAD') |
        Where-Object { "$_".Trim() })
    Assert-True ($releaseCommitted.Count -eq $releaseFiles.Count) `
        ("The release fixture's commit touched $($releaseCommitted.Count) files, not the $($releaseFiles.Count) " +
            'copied: ' + (($releaseCommitted | Select-Object -First 5) -join ', '))
    $releaseCommit = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    $releaseSeconds = [long]"$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('log', '-1', '--format=%ct') |
        Select-Object -First 1)".Trim()

    $releaseVersionHere = Get-BundleVersion -Root $releaseRepo
    $releaseCatalog = Get-Content -LiteralPath (Join-Path $releaseRepo 'patches-list.json') -Raw | ConvertFrom-Json
    $releaseTarget = Get-PatchTarget -PatchList $releaseCatalog
    $releaseNames = @($releaseCatalog.patches | ForEach-Object { [string]$_.name })
    $releaseToolchain = Read-CatalogToolchain -Source 'the release fixture catalog' `
        -Text (Get-Content -LiteralPath (Join-Path $releaseRepo 'gradle/libs.versions.toml') -Raw)
    $releaseReceipt = Join-Path $releaseRepo "release-receipt-$releaseVersionHere.json"
    $declaredBuild = $releaseTarget.PackageVersion
    $declaredCode = [string]@($releaseTarget.PackageVersionCodes[$declaredBuild])[0]
    Assert-True (@($releaseTarget.PackageVersions).Count -eq 1 -and $declaredCode -match '^\d+$') `
        ("HushGram declares one Instagram build pinned to one version code, and the cases below are built on that. " +
            "The catalog declares $(Format-DeclaredBuilds -Target $releaseTarget).")

    $coverageFixture = (Get-Content (Join-Path $PSScriptRoot 'patch-coverage-expectations.json') -Raw | ConvertFrom-Json).fixtures |
        Where-Object { $_.versionName -ceq $declaredBuild -and $_.versionCode -ceq $declaredCode }
    Assert-True ($null -ne $coverageFixture) 'The release fixture has no reviewed coverage census.'
    $releaseCoverage = @($coverageFixture.families | ForEach-Object {
        [ordered]@{ family = $_.family; matched = $_.targets.Count; expected = $_.targets.Count
            targets = @($_.targets); missing = @() }
    })

    # A receipt for this commit with a run of each build given, every patch applied and the
    # manifest changes the checked-in allowlist approves, written where the release check looks.
    $approvedDelta = [ordered]@{ permissionsAdded = @(); permissionsRemoved = @()
        exportedComponentsAdded = @(); exportedComponentsRemoved = @(); versionCodeChanged = @() }
    foreach ($entry in $checkedInAllowlist) {
        $kind, $value = $entry -split ' ', 2
        switch ($kind) {
            'permission-added' { $approvedDelta.permissionsAdded += $value }
            'permission-removed' { $approvedDelta.permissionsRemoved += $value }
            'exported-added' { $approvedDelta.exportedComponentsAdded += $value }
            'exported-removed' { $approvedDelta.exportedComponentsRemoved += $value }
            'version-code' { $approvedDelta.versionCodeChanged += $value }
            default { throw "The release fixture doesn't know the allowlist kind $kind." }
        }
    }
    function Save-ReleaseReceipt([string[]]$Builds, [string]$Commit = $releaseCommit, [long]$Seconds = $releaseSeconds) {
        $targets = @(for ($i = 0; $i -lt $Builds.Count; $i++) {
            $code = @(@($releaseTarget.PackageVersionCodes[$Builds[$i]]) + @("38600000$i") | Where-Object { $_ })[0]
            [ordered]@{
                source        = [ordered]@{ file = "instagram-$($Builds[$i])-arm64-v8a.apkm"
                    package = $releaseTarget.PackageName; versionName = $Builds[$i]; versionCode = $code
                    sha256 = ([string]'ABCDEF'[$i % 6] * 64); forced = $releaseTarget.PackageVersions -notcontains $Builds[$i] }
                patches       = @($releaseNames | ForEach-Object { [ordered]@{ name = $_; applied = $true; reason = $null } })
                coverage      = $releaseCoverage
                coverageReviewed = $Builds[$i] -ceq $declaredBuild -and $code -ceq $declaredCode
                manifestDelta = $approvedDelta
            }
        })
        $document = [ordered]@{
            schemaVersion = Get-ReleaseReceiptSchemaVersion
            buildIdentity = (Get-CanonicalBuildIdentity -Root $releaseRepo)
            release   = [ordered]@{ version = $releaseVersionHere; tag = "v$releaseVersionHere"; commit = $Commit
                commitTimestamp = $Seconds; patchCount = $releaseNames.Count }
            bundle    = [ordered]@{ file = "patches-$releaseVersionHere.mpp"; sizeBytes = 10; sha256 = ('E' * 64)
                timestamp = $Seconds * 1000 }
            sbom      = [ordered]@{ file = "patches-$releaseVersionHere.cdx.json"; sha256 = ('D' * 64); components = 3 }
            toolchain = [ordered]@{ patcherVersion = $releaseToolchain.PatcherVersion; managerFloor = $releaseToolchain.ManagerFloor }
            extension = [ordered]@{ dexPayloads = @([ordered]@{ name = 'extensions/instagram.mpe'; sizeBytes = 10; sha256 = ('F' * 64) }) }
            targets   = $targets
        }
        Set-Content -LiteralPath $releaseReceipt -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 12)
    }
    # The run the hook makes for an ordinary push that carries a receipt (its own set), with no
    # network. What it says is kept, to tell a receipt it compared from one it never found.
    function Invoke-ReleaseCheck {
        $ordinary = Get-ReleaseFactsArguments -Push Ordinary -Root $releaseRepo
        $ordinary['SkipUrlCheck'] = $true
        $global:LASTEXITCODE = 0
        $said = @(& $factsScript @ordinary 6>&1 | ForEach-Object { "$_" }) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "The release check exited $LASTEXITCODE on the release root: $said" }
        return $said
    }

    # The receipt is ignored by git, as the builder's clean tree check needs it to be.
    Save-ReleaseReceipt -Builds @($declaredBuild)
    Assert-True (@(Invoke-FixtureGit -Root $releaseRepo -Arguments @('status', '--porcelain')).Count -eq 0) `
        '.gitignore does not keep the release receipt out of git, so the receipt builder would find the tree dirty.'
    try {
        $said = Invoke-ReleaseCheck
    } catch {
        throw "The release check refused a receipt with a run of the declared build: $($_.Exception.Message)"
    }
    $proved = "the receipt proves $($releaseNames.Count) patches on $declaredBuild from commit $($releaseCommit.Substring(0, 8))"
    Assert-True ($said -like "*$proved*") "The release check did not compare the receipt it was given: $said"
    # A forced run of a newer build alone, the receipt a release that skipped the declared fixture
    # would write. Refused, naming the build it never ran.
    $newerBuild = "$([int]($declaredBuild -split '\.')[0] + 1).0.0.1.1"
    Save-ReleaseReceipt -Builds @($newerBuild)
    Assert-Throws { Invoke-ReleaseCheck } "*No target in the receipt is the declared $($releaseTarget.PackageName) $declaredBuild patched without -f*" `
        'The release check accepted a receipt with no run of the declared build.'
    # A receipt kept from another commit of this repository.
    Save-ReleaseReceipt -Builds @($declaredBuild) -Seconds ($releaseSeconds - 60)
    Assert-Throws { Invoke-ReleaseCheck } '*git says*' 'The release check accepted a receipt whose commit time git disagrees with.'
    Save-ReleaseReceipt -Builds @($declaredBuild)

    # build-release-receipt.ps1 itself, on the same root. Stand-ins take the tools' places: a JDK
    # that answers -version, plays MergeSplits.java (the merged APK, and a note beside it naming
    # the bundle it came from), and does what the desktop CLI leaves behind for each input (the
    # result report and the patched APK), and an aapt2 that prints the manifest lines written for
    # each APK. One .apkm per build, and a bundle stamped with the commit's time where buildAndroid
    # leaves it. The merge carries a component the base APK's manifest lacks, so a delta taken
    # against the base instead of the merge records it as the patches' own, and the allowlist,
    # which approves only the advertising ID removals, refuses the receipt.
    $tools = Join-Path $releaseRoot 'tools'
    $fixtures = Join-Path $releaseRoot 'fixtures'
    New-Item -ItemType Directory -Path $tools, $fixtures -Force | Out-Null
    $stubJava = Join-Path $tools 'java.cmd'
    $stubAapt2 = Join-Path $tools 'aapt2.cmd'
    $stubJar = Join-Path $tools 'morphe-desktop.jar'
    $javaLog = Join-Path $tools 'java.log'
    $mergeLog = Join-Path $tools 'merge.log'
    [System.IO.File]::WriteAllText($stubJava, ((@(
        '@echo off',
        'setlocal EnableExtensions EnableDelayedExpansion',
        'if "%~1"=="-version" (',
        '    echo openjdk version "21.0.5" 2024-10-15',
        '    exit /b 0',
        ')',
        'rem Its own folder, read before shift moves %0 along with the arguments.',
        'set "HERE=%~dp0"',
        'rem -Xmx -cp <jar> <tool>.java and the tool''s arguments.',
        'if /i "%~nx4"=="MergeSplits.java" goto merge',
        'if /i "%~nx4"=="PatchCoverage.java" goto coverage',
        'set "OUT=" & set "RESULT=" & set "LAST=" & set "PREV=" & set "FORCED=0"',
        'shift',
        'shift',
        ':next',
        'if "%~1"=="" goto run',
        'set "V=%~1"',
        'if "!PREV!"=="-o" set "OUT=!V!"',
        'if "!PREV!"=="-r" set "RESULT=!V!"',
        'if "!V!"=="-f" set "FORCED=1"',
        'set "PREV=!V!"',
        'set "LAST=!V!"',
        'shift',
        'goto next',
        ':run',
        'rem A merged APK names the bundle it came from, whose report and patched manifest these are.',
        'set "SRC=!LAST!"',
        'set "VIA="',
        'if exist "!LAST!.source" (',
        '    set /p SRC=<"!LAST!.source"',
        '    set "VIA= merged"',
        ')',
        '>>"!HERE!java.log" echo patch !SRC!!VIA! forced=!FORCED!',
        'rem The CLI logs WARNING and SEVERE on standard error, which Windows PowerShell 5.1 makes a',
        'rem terminating error under Stop unless the caller steps down to Continue for the call.',
        'echo WARNING: a patch named a target this build lacks, and still applied 1>&2',
        'rem A case that needs something to change while a fixture is patched leaves this behind.',
        'if exist "!HERE!during-patch.cmd" call "!HERE!during-patch.cmd"',
        'copy /y "!SRC!.result.json" "!RESULT!" >nul || exit /b 3',
        'copy /y "!HERE!patched.apk" "!OUT!" >nul || exit /b 4',
        'copy /y "!SRC!.patched.txt" "!OUT!.xmltree" >nul || exit /b 5',
        'exit /b 0',
        'rem MergeSplits.java <bundle> <merged.apk>. A case can make it fail, or leave no APK behind.',
        ':merge',
        '>>"!HERE!merge.log" echo merge %~5',
        'if exist "!HERE!merge-fails.txt" (',
        '    echo [merge] could not read the bundle 1>&2',
        '    exit /b 9',
        ')',
        'if exist "!HERE!merge-writes-nothing.txt" exit /b 0',
        'copy /y "%~5.merged.txt" "%~6" >nul || exit /b 7',
        '>"%~6.source" echo %~5',
        'exit /b 0',
        ':coverage',
        'type "!HERE!coverage-output.txt"',
        'exit /b %errorlevel%') -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)
    Set-Content -LiteralPath (Join-Path $tools 'coverage-output.txt') -Encoding ASCII -Value @($releaseCoverage | ForEach-Object {
        "$($_.family)=1|$($_.matched)|$($_.expected)|$($_.targets -join ',')|"
    })
    [System.IO.File]::WriteAllText($stubAapt2, ((@(
        '@echo off',
        'setlocal EnableExtensions DisableDelayedExpansion',
        'set "APK="',
        ':next',
        'if "%~1"=="" goto run',
        'set "APK=%~1"',
        'shift',
        'goto next',
        ':run',
        'if exist "%APK%.xmltree" (type "%APK%.xmltree") else (type "%APK%")',
        'exit /b %errorlevel%') -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)
    Set-Content -LiteralPath $stubJar -Value 'not a jar' -Encoding ASCII
    New-TestBundleArchive -Path (Join-Path $tools 'patched.apk') -Entries ([ordered]@{
        'AndroidManifest.xml' = 'binary manifest'; 'classes.dex' = "dex`n035" })

    $androidName = 'http://schemas.android.com/apk/res/android:name(0x01010003)='
    $androidExported = '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true'
    # Instagram asks for the three advertising ID permissions, and the patched build doesn't, and
    # carries the raised version code: the changes the checked-in allowlist approves, and the only
    # ones the patches make here.
    function Get-FixtureManifest([string]$Build, [string]$Code, [switch]$WithSplit, [switch]$Patched,
            [string]$Package = $releaseTarget.PackageName) {
        $permissions = @('android.permission.INTERNET')
        if (-not $Patched) { $permissions += $removedPermissions }
        if ($Patched) { $Code = $raisedVersionCode }
        $lines = @(
            'N: android=http://schemas.android.com/apk/res/android (line=1)',
            '  E: manifest (line=1)',
            "    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=$Code",
            "    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)=`"$Build`" (Raw: `"$Build`")",
            "    A: package=`"$Package`" (Raw: `"$Package`")")
        $line = 10
        foreach ($permission in $permissions) {
            $lines += @("      E: uses-permission (line=$line)", "        A: $androidName`"$permission`" (Raw: `"$permission`")")
            $line++
        }
        $lines += @(
            '      E: application (line=20)',
            '        E: activity (line=21)',
            "          A: $androidName`"com.example.host.Main`" (Raw: `"com.example.host.Main`")",
            $androidExported)
        if ($WithSplit) {
            $lines += @('        E: activity (line=40)',
                "          A: $androidName`"com.example.split.FeatureActivity`" (Raw: `"com.example.split.FeatureActivity`")",
                $androidExported)
        }
        return ($lines -join "`n") + "`n"
    }
    $dependencyNamesHere = @(Get-PatchDependencyNames -PatchList $releaseCatalog -RequestedNames $releaseNames)
    $fixturePaths = @{}
    # Beside the declared build, a newer one the catalog doesn't declare, the kind a release run
    # patches under -f to see what still applies on it.
    foreach ($build in @($declaredBuild, $newerBuild)) {
        $versionCode = if ($build -eq $newerBuild) { 399000001 } else { [long]$declaredCode }
        # Named instagram-<version>-<code> like the real fixtures, since a folder search takes a
        # pinned version's file by its code.
        $apkm = Join-Path $fixtures "instagram-$build-$versionCode-arm64-v8a.apkm"
        New-TestBundleArchive -Path $apkm -Entries ([ordered]@{
            'info.json' = "{`"versioncode`":`"$versionCode`"}"
            'base.apk' = Get-FixtureManifest -Build $build -Code "$versionCode"
            'split_config.arm64_v8a.apk' = ('native code ' * 64) })
        Set-Content -LiteralPath "$apkm.merged.txt" -Encoding ASCII -NoNewline `
            -Value (Get-FixtureManifest -Build $build -Code "$versionCode" -WithSplit)
        Set-Content -LiteralPath "$apkm.patched.txt" -Encoding ASCII -NoNewline `
            -Value (Get-FixtureManifest -Build $build -Code "$versionCode" -WithSplit -Patched)
        # The report the CLI writes: every patch and the internal dependencies applied, one step,
        # and the input's own version.
        Set-Content -LiteralPath "$apkm.result.json" -Encoding ASCII -Value ([ordered]@{
            patchingSteps = @([ordered]@{ success = $true })
            appliedPatches = @(@($releaseNames) + @($dependencyNamesHere) | ForEach-Object { [ordered]@{ name = $_ } })
            failedPatches = @()
            packageName = $releaseTarget.PackageName
            packageVersion = $build } | ConvertTo-Json -Depth 6)
        $fixturePaths[$build] = $apkm
    }
    # And another build of the declared version, as APKMirror lists several arm64 builds of one
    # Instagram release: the declared name at a code the catalog doesn't pin. Only its base
    # manifest, since the builder has to refuse it before anything is merged or patched.
    $variantCode = [long]$declaredCode - 61
    # Its lower code sorts it ahead of the declared build's file.
    $variantApkm = Join-Path $fixtures "instagram-$declaredBuild-$variantCode-arm64-v8a.apkm"
    New-TestBundleArchive -Path $variantApkm -Entries ([ordered]@{
        'info.json' = "{`"versioncode`":`"$variantCode`"}"
        'base.apk' = Get-FixtureManifest -Build $declaredBuild -Code "$variantCode" })
    $releaseBundle = Get-ReleaseBundlePath -Root $releaseRepo
    New-Item -ItemType Directory -Path (Split-Path -Parent $releaseBundle) -Force | Out-Null
    function New-ReleaseBundle([long]$Stamp = $releaseSeconds * 1000) {
        Remove-Item -LiteralPath $releaseBundle -Force -ErrorAction SilentlyContinue
        New-TestBundleArchive -Path $releaseBundle -Entries ([ordered]@{
            'META-INF/MANIFEST.MF' = ("Manifest-Version: 1.0`nVersion: $releaseVersionHere`nTimestamp: $Stamp`n" +
                "Patcher-Version: $($releaseToolchain.PatcherVersion)`nHushGram-Build-Identity: $((Get-CanonicalBuildIdentity -Root $releaseRepo).id)`n`n")
            'classes.dex' = "dex`n035" + ('patches' * 8)
            'extensions/instagram.mpe' = "dex`n035" + ('payload' * 8) })
    }
    New-ReleaseBundle
    # And the SBOM buildAndroid writes beside it, listing a library OSV has nothing against.
    $releaseSbom = [System.IO.Path]::ChangeExtension($releaseBundle, '.cdx.json')
    New-TestSbom -Path $releaseSbom -Bundle $releaseBundle -LicenseLedger $releaseLicenseLedger -ReviewedLicenseRecords $licenseFixtures.artifacts -InputRoot $releaseRepo
    $releaseGraph = Join-Path $releaseRoot 'current-graphs.json'
    $currentGraphs = $graphJson | ConvertFrom-Json
    $currentGraphs.inputs = Get-DependencyAuditInputs -Root $releaseRepo
    $currentGraphs | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $releaseGraph -Encoding UTF8
    $releaseSums = Join-Path (Split-Path -Parent $releaseBundle) 'SHA256SUMS.txt'

    # The builder reads git with a plain `git -C`, which a GIT_DIR inherited from a hook would
    # override, so every GIT_* variable is cleared for the length of a run. OSV is the stand-in
    # above, and what the builder says is kept in $builderSaid, warnings included.
    $builderSaid = ''
    function Invoke-ReceiptBuilder([string[]]$Fixtures, [string]$Bundle,
            [string]$WorkDir = (Join-Path $releaseRoot 'work'), [string]$DesktopJar = $stubJar, [switch]$SkipAdvisoryCheck,
            [switch]$FromGate) {
        Remove-Item -LiteralPath $javaLog, $mergeLog -Force -ErrorAction SilentlyContinue
        $saved = @{}
        foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
            $saved[$variable.Name] = $variable.Value
            Remove-Item -LiteralPath ('Env:\' + $variable.Name)
        }
        $originalFixtureHead = $null
        $originalFixtureGraph = $null
        try {
            $ledgerChanges = @(Invoke-FixtureGit -Root $releaseRepo -Arguments @(
                'diff', '--name-only', 'HEAD', '--', 'sources/carried-library-licenses.json'))
            if ($ledgerChanges.Count -gt 0) {
                # A different stand-in library inventory is a different reviewed source input.
                # Commit only that inventory, leaving deliberately dirty production fixtures dirty.
                $originalFixtureHead = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @(
                    'rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
                Invoke-FixtureGit -Root $releaseRepo -CommitEpoch $releaseSeconds -Arguments @(
                    'commit', '--amend', '--only', 'sources/carried-library-licenses.json',
                    '--no-edit', '--quiet') | Out-Null
                if ($LASTEXITCODE -ne 0) { throw 'Could not commit the exact fixture license inventory.' }
                $originalFixtureGraph = [IO.File]::ReadAllBytes($releaseGraph)
                $variantGraphs = Get-Content -LiteralPath $releaseGraph -Raw | ConvertFrom-Json
                $variantGraphs.inputs = Get-DependencyAuditInputs -Root $releaseRepo
                $variantGraphs | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $releaseGraph -Encoding UTF8
            }
            . $osvStandIn
            $global:LASTEXITCODE = 0
            $arguments = @{ Root = $releaseRepo; WorkDir = $WorkDir; DesktopJar = $DesktopJar; Java = $stubJava; Aapt2 = $stubAapt2; DependencyGraph = $releaseGraph }
            if ($Fixtures) { $arguments['Fixture'] = $Fixtures }
            if ($Bundle) { $arguments['Bundle'] = $Bundle }
            if ($SkipAdvisoryCheck) { $arguments['SkipAdvisoryCheck'] = $true }
            if ($FromGate) { $arguments['FromGate'] = $true }
            $script:builderSaid = @(& (Join-Path $PSScriptRoot 'build-release-receipt.ps1') @arguments 3>&1 6>&1 |
                ForEach-Object { "$_" }) -join "`n"
            if ($LASTEXITCODE -ne 0) { throw "build-release-receipt.ps1 exited $LASTEXITCODE." }
        } finally {
            try {
                if ($originalFixtureGraph) { [IO.File]::WriteAllBytes($releaseGraph, $originalFixtureGraph) }
                if ($originalFixtureHead) {
                    Invoke-FixtureGit -Root $releaseRepo -Arguments @('update-ref', 'HEAD', $originalFixtureHead) | Out-Null
                    if ($LASTEXITCODE -ne 0) { throw 'Could not restore the fixture source revision.' }
                    Invoke-FixtureGit -Root $releaseRepo -Arguments @(
                        'reset', '--quiet', $originalFixtureHead, '--', 'sources/carried-library-licenses.json') | Out-Null
                    if ($LASTEXITCODE -ne 0) { throw 'Could not restore the fixture license index.' }
                }
            } finally {
                foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
            }
        }
    }

    # A fixture for the declared build and the newer one: one target each, only the newer build
    # forced, every patch applied, and no manifest change but the removals, because the patched
    # manifest is held to the merge and not to the base.
    $builtBuilds = @($declaredBuild, $newerBuild)
    $allFixtures = @($builtBuilds | ForEach-Object { $fixturePaths[$_] })
    Remove-Item -LiteralPath $releaseReceipt -Force
    try {
        Invoke-ReceiptBuilder -Fixtures $allFixtures
    } catch {
        throw "build-release-receipt.ps1 refused a run of the declared build: $($_.Exception.Message)"
    }
    $built = Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json
    $builtTargets = @($built.targets)
    $builtVersions = @($builtTargets | ForEach-Object { [string]$_.source.versionName })
    Assert-True (($builtVersions -join ',') -eq ($builtBuilds -join ',')) `
        "The receipt does not hold one run of each fixture: $($builtVersions -join ', ')"
    foreach ($builtTarget in $builtTargets) {
        $label = [string]$builtTarget.source.versionName
        $declared = $label -eq $declaredBuild
        Assert-True ($builtTarget.source.forced -eq (-not $declared)) `
            "The receipt says $label was $(if ($builtTarget.source.forced) { 'forced' } else { 'not forced' })."
        Assert-True ($builtTarget.source.sha256 -eq (Get-Sha256Hex -Path $fixturePaths[$label])) `
            "The receipt does not hash the $label fixture it was given."
        Assert-True (@($builtTarget.patches | Where-Object { $_.applied }).Count -eq $releaseNames.Count) `
            "The receipt does not record every patch applied to $label."
        $changes = @(ConvertTo-ManifestDeltaEntries -Delta $builtTarget.manifestDelta)
        Assert-True (($changes -join "`n") -ceq (@($checkedInAllowlist | Sort-Object -Unique -CaseSensitive) -join "`n")) `
            ("The receipt records other manifest changes for $label than the advertising ID removals and the raised " +
                "version code: $($changes -join ', ')")
    }
    # Each fixture merged once, and the CLI handed that merge rather than the bundle.
    $mergeRuns = @(Get-Content -LiteralPath $mergeLog)
    Assert-True (($mergeRuns -join "`n") -eq (@($builtBuilds | ForEach-Object { "merge $($fixturePaths[$_])" }) -join "`n")) `
        "Each fixture was not merged once, before it was patched: $($mergeRuns -join '; ')"
    $patchRuns = @(Get-Content -LiteralPath $javaLog)
    $expectedRuns = @($builtBuilds | ForEach-Object {
        "patch $($fixturePaths[$_]) merged forced=$(if ($_ -eq $declaredBuild) { 0 } else { 1 })" })
    Assert-True (($patchRuns -join "`n") -eq ($expectedRuns -join "`n")) `
        "The CLI was not run once per fixture, on its merge, with -f for the undeclared build only: $($patchRuns -join '; ')"
    # The SBOM beside the bundle, recorded by name, hash and count, once OSV had been asked about it.
    Assert-True ($built.sbom.file -eq "patches-$releaseVersionHere.cdx.json" -and
        $built.sbom.sha256 -ceq (Get-Sha256Hex -Path $releaseSbom) -and [int]$built.sbom.components -eq 3) `
        "The receipt does not record the SBOM beside the bundle: $($built.sbom | ConvertTo-Json -Compress)"
    Assert-True ($cleanPurl -cin $osvAsked -and $builderSaid -like '*Checking 1 unique resolved packages against OSV*') `
        "The receipt builder did not put the SBOM's libraries to OSV: $builderSaid"
    $builderReport = Read-CurrentDependencyAdvisoryReport -Path (Join-Path $releaseRepo 'build/reports/dependencies/advisories.json') `
        -Subject (Get-CurrentDependencyAuditSubject -Root $releaseRepo -Graphs (Read-DependencyGraphs -Path $releaseGraph) `
            -Sbom (Read-ReleaseSbom -Path $releaseSbom) -BundlePath $releaseBundle)
    Assert-True ($builderReport.graphs.Count -eq 6 -and $builderReport.subject.sbom.sha256 -eq $built.sbom.sha256) `
        'The receipt builder omitted a tooling scope or certified a different SBOM.'
    # SHA256SUMS.txt beside the bundle: the three files a release publishes with it, as sha256sum
    # writes them, and nothing else. The index push reads it back.
    $sumsBytes = [System.IO.File]::ReadAllBytes($releaseSums)
    $expectedSums = (@($releaseBundle, $releaseSbom, $releaseReceipt) | ForEach-Object {
        "$((Get-Sha256Hex -Path $_).ToLowerInvariant())  $(Split-Path -Leaf $_)" }) -join "`n"
    Assert-True ([System.Text.Encoding]::UTF8.GetString($sumsBytes) -ceq "$expectedSums`n" -and $sumsBytes[0] -ne 0xEF) `
        "SHA256SUMS.txt does not list the bundle, the SBOM and the receipt, one LF line each: $([System.Text.Encoding]::UTF8.GetString($sumsBytes))"
    # And the receipt the builder writes is one the release check accepts.
    $said = Invoke-ReleaseCheck
    $builtProved = "the receipt proves $($releaseNames.Count) patches on $($builtVersions -join ', ') " +
        "from commit $($releaseCommit.Substring(0, 8))"
    Assert-True ($said -like "*$builtProved*") "The release check did not accept the receipt the builder wrote: $said"
    $builtReceiptBytes = [System.IO.File]::ReadAllBytes($releaseReceipt)
    $builtSumsBytes = $sumsBytes

    # With no -Fixture, the declared build's fixture from HUSHGRAM_FIXTURE_DIR, found the way the
    # pre-push hook finds it, and only that one: the newer build and the other build of the declared
    # version beside it are left alone. With no folder, or none of the declared build in it, the
    # run stops before anything is patched.
    # An older build's fixture sorts ahead of the declared one, so a search that took the first
    # Instagram fixture it found, whatever its version, would take that one.
    $olderFixture = Join-Path $fixtures 'instagram-100.0.0.1.1-arm64-v8a.apkm'
    Copy-Item -LiteralPath $fixturePaths[$newerBuild] -Destination $olderFixture
    try {
        $env:HUSHGRAM_FIXTURE_DIR = $fixtures
        Invoke-ReceiptBuilder
        Assert-True ((@(Get-Content -LiteralPath $javaLog) -join '; ') -eq "patch $($fixturePaths[$declaredBuild]) merged forced=0") `
            "With no -Fixture, the builder did not take the declared build from the fixture folder alone: $(@(Get-Content -LiteralPath $javaLog) -join '; ')"
        $emptyFixtures = Join-Path $releaseRoot 'no-fixtures'
        New-Item -ItemType Directory -Path $emptyFixtures -Force | Out-Null
        $env:HUSHGRAM_FIXTURE_DIR = $emptyFixtures
        Assert-Throws { Invoke-ReceiptBuilder } "*holds no fixture of $($releaseTarget.PackageName) $declaredBuild*" `
            'The builder went ahead with a fixture folder holding no declared build.'
        Remove-Item -LiteralPath Env:\HUSHGRAM_FIXTURE_DIR
        Assert-Throws { Invoke-ReceiptBuilder } '*No -Fixture, and HUSHGRAM_FIXTURE_DIR names no folder*' `
            'The builder went ahead with no fixture named anywhere.'
        Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'The builder patched with no fixture to patch.'
    } finally {
        Remove-Item -LiteralPath Env:\HUSHGRAM_FIXTURE_DIR -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $olderFixture -Force -ErrorAction SilentlyContinue
        [System.IO.File]::WriteAllBytes($releaseReceipt, $builtReceiptBytes)
        [System.IO.File]::WriteAllBytes($releaseSums, $builtSumsBytes)
    }

    # A merge that fails, and one that exits 0 and writes nothing, stop the run before the CLI
    # patches anything. base.apk is not what the CLI patches, so there's no receipt to fall back to.
    $brokenMerges = @(
        @{ Flag = 'merge-fails.txt'; Pattern = '*Could not merge instagram-*.apkm into one APK (exit 9)*could not read the bundle*' },
        @{ Flag = 'merge-writes-nothing.txt'; Pattern = '*The merge of instagram-*.apkm wrote no APK at *stock-merged.apk*' })
    foreach ($broken in $brokenMerges) {
        $flag = Join-Path $tools $broken.Flag
        Set-Content -LiteralPath $flag -Value 'on' -Encoding ASCII
        Remove-Item -LiteralPath $releaseReceipt -Force
        try {
            Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } $broken.Pattern `
                "build-release-receipt.ps1 went ahead when $($broken.Flag -replace '\.txt$', '')."
            Assert-True (-not (Test-Path -LiteralPath $javaLog) -and -not (Test-Path -LiteralPath $releaseReceipt)) `
                "build-release-receipt.ps1 patched or wrote a receipt when $($broken.Flag -replace '\.txt$', '')."
            Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $releaseRoot 'work') -Directory -Filter 'receipt-*').Count -eq 0) `
                "build-release-receipt.ps1 left its run folder behind when $($broken.Flag -replace '\.txt$', '')."
        } finally {
            Remove-Item -LiteralPath $flag -Force -ErrorAction SilentlyContinue
            [System.IO.File]::WriteAllBytes($releaseReceipt, $builtReceiptBytes)
        }
    }

    # The newer build alone is not enough for a receipt: the declared build has no run. The builder
    # says so before it patches anything. Another build of the declared version in its place is no
    # run of it either: taken by its name, it would be patched without -f and recorded as proof of
    # a build nobody ran. And a fixture of another app at the declared build is refused by package.
    $declaredRefusal = "*No fixture is the declared $($releaseTarget.PackageName) $declaredBuild*Nothing was patched*"
    Assert-Throws { Invoke-ReceiptBuilder -Fixtures @($fixturePaths[$newerBuild]) } $declaredRefusal `
        'build-release-receipt.ps1 went ahead with a fixture of the newer build only.'
    Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'build-release-receipt.ps1 patched before it found the declared build missing.'
    $variantRefusal = "*No fixture is the declared $($releaseTarget.PackageName) $declaredBuild*" +
        "$declaredBuild ($declaredCode)*$declaredBuild ($variantCode)*Nothing was patched*"
    Assert-Throws { Invoke-ReceiptBuilder -Fixtures @($variantApkm) } $variantRefusal `
        'build-release-receipt.ps1 took another build of the declared version for the declared one.'
    Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'build-release-receipt.ps1 patched another build of the declared version.'
    $otherPackage = 'com.instagram.barcelona'
    $otherApkm = Join-Path $fixtures "threads-$declaredBuild-arm64-v8a.apkm"
    New-TestBundleArchive -Path $otherApkm -Entries ([ordered]@{
        'info.json' = "{`"versioncode`":`"$declaredCode`"}"
        'base.apk' = Get-FixtureManifest -Build $declaredBuild -Code $declaredCode -Package $otherPackage })
    Assert-Throws { Invoke-ReceiptBuilder -Fixtures @($fixturePaths[$declaredBuild], $otherApkm) } `
        "*$(Split-Path -Leaf $otherApkm) is $otherPackage, not the catalog's target $($releaseTarget.PackageName)*" `
        'build-release-receipt.ps1 took a fixture of another package.'
    Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'build-release-receipt.ps1 started the CLI on another package.'
    Remove-Item -LiteralPath $otherApkm -Force
    Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures -DesktopJar (Join-Path $tools 'missing.jar') } `
        '*No Morphe desktop CLI at the path given*' 'build-release-receipt.ps1 went looking elsewhere for a desktop CLI it was named.'

    # The SBOM and what OSV says about it come before anything is patched. The deliberately
    # vulnerable fixture is this bundle with an SBOM listing gson 2.8.8, and no receipt comes of it.
    # Offline, -SkipAdvisoryCheck gets a receipt with a warning, and the index push asks OSV again.
    # An OSV out of reach stops the run, and so does an SBOM of another build or none at all.
    $cleanSbomBytes = [System.IO.File]::ReadAllBytes($releaseSbom)
    $cleanLicenseLedgerBytes = [System.IO.File]::ReadAllBytes($releaseLicenseLedger)
    try {
        New-TestSbom -Path $releaseSbom -Bundle $releaseBundle -LicenseLedger $releaseLicenseLedger -ReviewedLicenseRecords $licenseFixtures.artifacts -Libraries @($gsonPurl) -InputRoot $releaseRepo
        Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } `
            '*high or critical*GHSA-4jrv-ppp4-jm57 (HIGH, CVE-2022-25647) in com.google.code.gson:gson 2.8.8*' `
            'build-release-receipt.ps1 wrote a receipt for a bundle carrying gson 2.8.8.'
        Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'build-release-receipt.ps1 patched before it asked OSV about the SBOM.'
        Invoke-ReceiptBuilder -Fixtures $allFixtures -SkipAdvisoryCheck
        Assert-True ($builderSaid -like '*-SkipAdvisoryCheck: OSV was not asked about the libraries*' -and
            (Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json).sbom.sha256 -ceq (Get-Sha256Hex -Path $releaseSbom)) `
            "An offline receipt run did not say the advisory check was skipped, or did not record the SBOM: $builderSaid"
        foreach ($refused in @(
                @{ Name = 'an OSV out of reach'; Sbom = { New-TestSbom -Path $releaseSbom -Bundle $releaseBundle -LicenseLedger $releaseLicenseLedger -ReviewedLicenseRecords $licenseFixtures.artifacts -Libraries @('pkg:maven/com.example/unknown@1.0') -InputRoot $releaseRepo }
                    Pattern = '*could not be asked about pkg:maven/com.example/unknown@1.0*fails closed*' },
                @{ Name = 'an SBOM of another build'; Pattern = '*The SBOM does not describe the bundle*written for another build*'
                    Sbom = { New-TestSbom -Path $releaseSbom -Bundle $releaseBundle -LicenseLedger $releaseLicenseLedger -ReviewedLicenseRecords $licenseFixtures.artifacts -InputRoot $releaseRepo -Mutate { param($d) $d.metadata.component.hashes[0].content = ('0' * 64) } } },
                @{ Name = 'no SBOM'; Sbom = { Remove-Item -LiteralPath $releaseSbom }; Pattern = "*No SBOM for the bundle: $releaseSbom*" })) {
            & $refused.Sbom
            Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } $refused.Pattern "build-release-receipt.ps1 went ahead with $($refused.Name)."
            Assert-True (-not (Test-Path -LiteralPath $javaLog)) "build-release-receipt.ps1 patched with $($refused.Name)."
        }
        # The receipt names the SBOM still beside the bundle when it's written: another buildAndroid
        # during the patch runs, which take long enough for one, replaces it.
        [System.IO.File]::WriteAllBytes($releaseSbom, $cleanSbomBytes)
        $replacement = Join-Path $releaseRoot 'replacement.cdx.json'
        New-TestSbom -Path $replacement -Bundle $releaseBundle -LicenseLedger $releaseLicenseLedger -ReviewedLicenseRecords $licenseFixtures.artifacts -InputRoot $releaseRepo
        $duringPatch = Join-Path $tools 'during-patch.cmd'
        [System.IO.File]::WriteAllText($duringPatch, "@copy /y `"$replacement`" `"$releaseSbom`" >nul`r`n", [System.Text.Encoding]::ASCII)
        try {
            Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } `
                '*canonical inputs or final artifacts changed while the fixtures*' `
                'build-release-receipt.ps1 wrote a receipt naming an SBOM that was replaced while it patched.'
        } finally {
            Remove-Item -LiteralPath $duringPatch -Force -ErrorAction SilentlyContinue
        }
    } finally {
        [System.IO.File]::WriteAllBytes($releaseSbom, $cleanSbomBytes)
        [System.IO.File]::WriteAllBytes($releaseLicenseLedger, $cleanLicenseLedgerBytes)
        [System.IO.File]::WriteAllBytes($releaseReceipt, $builtReceiptBytes)
        [System.IO.File]::WriteAllBytes($releaseSums, $builtSumsBytes)
    }

    # A bundle that isn't a build of the commit. The build stamps a bundle 0 when the tree had
    # uncommitted changes as it started, and another commit's bundle carries that commit's time;
    # both are refused before anything is patched. And a source written after the bundle, which an
    # edit put back since leaves: the tree is clean and the stamp right, yet the bundle may have
    # been built from the edit.
    $builtBundleBytes = [System.IO.File]::ReadAllBytes($releaseBundle)
    try {
        foreach ($stamped in @(
                @{ Name = 'a bundle built from a tree with uncommitted changes'; Stamp = 0L
                    Pattern = "*stamped 0*uncommitted changes*isn't a build of commit $releaseCommit*Nothing was patched*" },
                @{ Name = 'a bundle built from another commit'; Stamp = ($releaseSeconds - 60) * 1000
                    Pattern = "*stamped $(($releaseSeconds - 60) * 1000), but commit $releaseCommit was made at $($releaseSeconds * 1000)*Nothing was patched*" })) {
            New-ReleaseBundle -Stamp $stamped.Stamp
            New-TestSbom -Path $releaseSbom -Bundle $releaseBundle -LicenseLedger $releaseLicenseLedger -ReviewedLicenseRecords $licenseFixtures.artifacts -InputRoot $releaseRepo
            Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } $stamped.Pattern `
                "build-release-receipt.ps1 went ahead with $($stamped.Name)."
            Assert-True (-not (Test-Path -LiteralPath $javaLog)) "build-release-receipt.ps1 patched with $($stamped.Name)."
        }
    } finally {
        [System.IO.File]::WriteAllBytes($releaseBundle, $builtBundleBytes)
        [System.IO.File]::WriteAllBytes($releaseSbom, $cleanSbomBytes)
        [System.IO.File]::WriteAllBytes($releaseReceipt, $builtReceiptBytes)
    }
    $touchedCatalog = Join-Path $releaseRepo 'gradle/libs.versions.toml'
    $catalogWritten = [System.IO.File]::GetLastWriteTimeUtc($touchedCatalog)
    try {
        [System.IO.File]::SetLastWriteTimeUtc($touchedCatalog, [System.IO.File]::GetLastWriteTimeUtc($releaseBundle).AddMinutes(1))
        Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } `
            "*1 source file(s) changed after the bundle was built, the newest *libs.versions.toml*Nothing was patched*" `
            'build-release-receipt.ps1 went ahead with a bundle older than a source it was built from.'
        Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'build-release-receipt.ps1 patched with a bundle older than its sources.'
    } finally {
        [System.IO.File]::SetLastWriteTimeUtc($touchedCatalog, $catalogWritten)
        [System.IO.File]::WriteAllBytes($releaseReceipt, $builtReceiptBytes)
    }
    # The control, with relative paths from a location the process directory isn't: the same run
    # writes its receipt. Each path is found with Test-Path, in PowerShell's location, and opened
    # with .NET, which reads the process directory.
    $elsewhere = Join-Path $releaseRoot 'elsewhere'
    New-Item -ItemType Directory -Path $elsewhere -Force | Out-Null
    $inRoot = { param([string]$Path) $Path.Substring($releaseRoot.TrimEnd('\').Length + 1) }
    $savedProcessDirectory = [Environment]::CurrentDirectory
    Push-Location -LiteralPath $releaseRoot
    try {
        [Environment]::CurrentDirectory = $elsewhere
        try {
            Invoke-ReceiptBuilder -Fixtures @($allFixtures | ForEach-Object { & $inRoot $_ }) `
                -Bundle (& $inRoot $releaseBundle) -WorkDir 'work-relative' -DesktopJar (& $inRoot $stubJar)
        } catch {
            throw "build-release-receipt.ps1 refused relative paths: $($_.Exception.Message)"
        }
        Assert-True (@(Get-ChildItem -LiteralPath $elsewhere -Recurse -Force).Count -eq 0) `
            'A relative path was written in the process directory instead.'
    } finally {
        [Environment]::CurrentDirectory = $savedProcessDirectory
        Pop-Location
    }
    $builtReceiptBytes = [System.IO.File]::ReadAllBytes($releaseReceipt)
    $builtSumsBytes = [System.IO.File]::ReadAllBytes($releaseSums)

    # -FromGate: the push gate's run of this commit, kept the way pre-push.ps1 keeps it, with the
    # bundle and SBOM built here, both test runs and the declared build's patch run stamped with
    # what made it. The newer build has no kept run, so it is still patched here. Each case that
    # spoils the run patches the declared build again rather than reading it.
    . (Join-Path $PSScriptRoot 'gate-evidence.ps1')
    $declaredFixture = $fixturePaths[$declaredBuild]
    $declaredPatchRun = "patch $declaredFixture merged forced=0"
    Write-FactsResults $runtimeResults 'RuntimeTest' 5 -Under $releaseRepo
    Write-FactsResults $patchResults 'PatchTest' 7 -Under $releaseRepo
    $gateDir = Start-GateEvidence -Commit $releaseCommit
    $gateKept = Join-Path $gateDir "fixtures/$declaredBuild/kept"
    $gateStaging = Join-Path $releaseRoot 'gate-staging'
    New-Item -ItemType Directory -Path $gateStaging -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $tools 'patched.apk') -Destination (Join-Path $gateStaging 'patched.apk')
    Copy-Item -LiteralPath "$declaredFixture.merged.txt" -Destination (Join-Path $gateStaging 'stock-merged.apk')
    Copy-Item -LiteralPath "$declaredFixture.result.json" -Destination (Join-Path $gateStaging 'result.json')
    Write-GateKeptRun -KeepIn $gateKept -PatchedApk (Join-Path $gateStaging 'patched.apk') `
        -Result (Join-Path $gateStaging 'result.json') -MergedApk (Join-Path $gateStaging 'stock-merged.apk') `
        -Apk $declaredFixture -Bundle $releaseBundle -PatchList (Join-Path $releaseRepo 'patches-list.json') `
        -DesktopJar $stubJar -VersionName $declaredBuild -VersionCode $declaredCode -Forced $false
    # The aapt2 stand-in reads a patched APK's manifest from the file beside it.
    Copy-Item -LiteralPath "$declaredFixture.patched.txt" -Destination (Join-Path $gateKept 'patched.apk.xmltree')
    Save-GateEvidence -Directory $gateDir -GateRoot $releaseRepo -Commit $releaseCommit -Passed $true -Stage 'done' `
        -FixtureRuns @([pscustomobject]@{ Version = $declaredBuild; Apk = $declaredFixture; WorkDir = $gateStaging }) `
        -FixturesPatched $true | Out-Null
    $gateManifestPath = Join-Path $gateDir 'manifest.json'
    $gateManifestText = [System.IO.File]::ReadAllText($gateManifestPath)
    $gateStampPath = Join-Path $gateKept 'stamp.json'
    $gateStampText = [System.IO.File]::ReadAllText($gateStampPath)
    $gateBundle = Join-Path $gateDir "release/$(Split-Path -Leaf $releaseBundle)"
    $gateBundleBytes = [System.IO.File]::ReadAllBytes($gateBundle)
    $releaseBundleBytes = [System.IO.File]::ReadAllBytes($releaseBundle)
    $releaseSbomBytes = [System.IO.File]::ReadAllBytes($releaseSbom)
    $gateAllowlist = @($checkedInAllowlist | Sort-Object -Unique -CaseSensitive) -join "`n"
    try {
        Remove-Item -LiteralPath $releaseReceipt -Force
        Invoke-ReceiptBuilder -Fixtures $allFixtures -FromGate
        Assert-True ((@(Get-Content -LiteralPath $javaLog) -join '; ') -eq "patch $($fixturePaths[$newerBuild]) merged forced=1" -and
            (@(Get-Content -LiteralPath $mergeLog) -join '; ') -eq "merge $($fixturePaths[$newerBuild])") `
            ("With the gate's run of the declared build, the builder merged or patched it again, or skipped the newer one: " +
                "$(@(Get-Content -LiteralPath $javaLog) -join '; ')")
        foreach ($expected in @("*reading the gate's run of $($releaseCommit.Substring(0, 12))*",
                "*$(Split-Path -Leaf $declaredFixture): reading the gate's patch run*",
                "*the gate kept no run of $newerBuild, so it is patched here*",
                '*the sources here are not compared with it by date*',
                "*kept a copy of both with the gate's run*")) {
            Assert-True ($builderSaid -like $expected) "The builder reading the gate's run did not say $expected`: $builderSaid"
        }
        $gateReceipt = Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json
        $gateTarget = @($gateReceipt.targets | Where-Object { [string]$_.source.versionName -eq $declaredBuild })
        Assert-True (@($gateReceipt.targets).Count -eq 2 -and $gateTarget.Count -eq 1 -and -not $gateTarget[0].source.forced -and
            $gateTarget[0].source.sha256 -eq (Get-Sha256Hex -Path $declaredFixture) -and
            @($gateTarget[0].patches | Where-Object { $_.applied }).Count -eq $releaseNames.Count -and
            (@(ConvertTo-ManifestDeltaEntries -Delta $gateTarget[0].manifestDelta) -join "`n") -ceq $gateAllowlist) `
            "The receipt read from the gate's run does not record the declared build as a run here does: $($gateTarget | ConvertTo-Json -Depth 6 -Compress)"
        $said = Invoke-ReleaseCheck
        Assert-True ($said -like "*$builtProved*") "The release check refused the receipt cut from the gate's run: $said"
        foreach ($pair in @(@($releaseReceipt, "release-receipt-$releaseVersionHere.json"), @($releaseSums, 'SHA256SUMS.txt'))) {
            $keptCopy = Join-Path $gateDir "receipt/$($pair[1])"
            Assert-True ((Test-Path -LiteralPath $keptCopy -PathType Leaf) -and
                (Get-Sha256Hex -Path $keptCopy) -ceq (Get-Sha256Hex -Path $pair[0])) "The builder kept no copy of $($pair[1]) with the gate's run."
        }

        # No bundle built here: the gate's bundle and SBOM are copied in where buildAndroid leaves
        # them, and nothing is merged or patched.
        Remove-Item -LiteralPath $releaseBundle, $releaseSbom, $releaseSums -Force
        Invoke-ReceiptBuilder -Fixtures @($declaredFixture) -FromGate
        Assert-True (-not (Test-Path -LiteralPath $javaLog) -and -not (Test-Path -LiteralPath $mergeLog)) `
            'With no bundle built here, the builder patched the declared build the gate already patched.'
        Assert-True ($builderSaid -like "*no bundle built here, so the gate's $(Split-Path -Leaf $releaseBundle) and its SBOM were copied*" -and
            (Get-Sha256Hex -Path $releaseBundle) -ceq (Get-Sha256Hex -Path $gateBundle) -and
            [System.Linq.Enumerable]::SequenceEqual([byte[]][System.IO.File]::ReadAllBytes($releaseSbom), [byte[]]$releaseSbomBytes)) `
            "With no bundle built here, the gate's bundle and SBOM were not copied in: $builderSaid"

        # Without -FromGate the kept run is never read.
        Invoke-ReceiptBuilder -Fixtures @($declaredFixture)
        Assert-True ((@(Get-Content -LiteralPath $javaLog) -join '; ') -eq $declaredPatchRun -and $builderSaid -notlike "*the gate's*") `
            "The builder read the gate's run without -FromGate: $builderSaid"

        # And a run that doesn't hold up is not read: one stamped with another bundle, a gate that
        # didn't pass, and a bundle here that isn't the one the gate built. The evidence's bundle is
        # changed with the manifest hashing it, so only the comparison with this checkout's tells.
        $otherBundleHash = '0' * 64
        $changedGateBundle = [byte[]]($gateBundleBytes + [byte]10)
        foreach ($spoiled in @(
                @{ Name = 'a kept run made with another bundle'; Pattern = "*was made with another bundle, so it is patched here*"
                    Spoil = { [System.IO.File]::WriteAllText($gateStampPath, ($gateStampText -replace '"bundleSha256":\s*"[0-9A-F]{64}"', "`"bundleSha256`": `"$otherBundleHash`"")) } },
                @{ Name = 'a gate that did not pass'; Pattern = "*isn't used: that gate didn't pass*"
                    Spoil = { [System.IO.File]::WriteAllText($gateManifestPath, ($gateManifestText -replace '"passed":\s*true', '"passed": false')) } },
                @{ Name = 'a bundle here other than the one the gate built'; Pattern = "*isn't the one the gate built and tested, so the gate's run isn't read*"
                    Spoil = {
                        [System.IO.File]::WriteAllBytes($gateBundle, $changedGateBundle)
                        [System.IO.File]::WriteAllText($gateManifestPath, $gateManifestText.Replace(
                            (Get-Sha256Hex -Path $releaseBundle), (Get-Sha256Hex -Path $gateBundle)))
                    } })) {
            & $spoiled.Spoil
            try {
                Invoke-ReceiptBuilder -Fixtures @($declaredFixture) -FromGate
                Assert-True ((@(Get-Content -LiteralPath $javaLog) -join '; ') -eq $declaredPatchRun -and $builderSaid -like $spoiled.Pattern) `
                    "The builder read $($spoiled.Name) instead of patching: $builderSaid"
            } finally {
                [System.IO.File]::WriteAllText($gateStampPath, $gateStampText)
                [System.IO.File]::WriteAllText($gateManifestPath, $gateManifestText)
                [System.IO.File]::WriteAllBytes($gateBundle, $gateBundleBytes)
            }
        }
    } finally {
        [System.IO.File]::WriteAllBytes($releaseBundle, $releaseBundleBytes)
        [System.IO.File]::WriteAllBytes($releaseSbom, $releaseSbomBytes)
        [System.IO.File]::WriteAllBytes($releaseReceipt, $builtReceiptBytes)
        [System.IO.File]::WriteAllBytes($releaseSums, $builtSumsBytes)
        Remove-Item -LiteralPath $gateStaging -Recurse -Force -ErrorAction SilentlyContinue
    }

    Write-Host '[release-tooling] receipt builder contracts passed'

    # The index push. The release is published from the source commit above (its tag, the bundle,
    # the SBOM, the receipt and SHA256SUMS.txt), and the next commit writes the index, the README
    # sentence naming the release and the CHANGELOG's dated heading. The hook checks that push with
    # -VerifyPublishedAsset. A bare repository answers for GitHub through the fixture's
    # url.insteadOf, and stand-ins serve the downloads and the CLI's patch listing, so nothing
    # leaves the machine. The files served are the ones the builder wrote, SHA256SUMS.txt included.
    $slugHere = 'SysAdminDoc/HushGram'
    $publishedRepo = (Join-Path $releaseRoot 'published.git').Replace('\', '/')
    Invoke-FixtureGit -Root $releaseRoot -Arguments @('init', '--bare', '--quiet', $publishedRepo) | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', "url.$publishedRepo.insteadOf", "https://github.com/$slugHere.git") | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', "v$releaseVersionHere", $releaseCommit) | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--quiet', $publishedRepo, "refs/tags/v$releaseVersionHere") | Out-Null
    $releaseFloor = $releaseToolchain.ManagerFloor
    [System.IO.File]::WriteAllText((Join-Path $releaseRepo 'patches-bundle.json'), ([ordered]@{
            created_at = '2026-09-30T12:00:00'
            description = ("## [$releaseVersionHere](https://github.com/$slugHere/compare/v0.0.1...v$releaseVersionHere) (2026-09-30)`n`n" +
                "### Features`n`n* **Instagram:** Every change, one line each.`n`n" +
                "### Validation`n`nValidation: 5 runtime tests passed locally. All 7 patch tests passed too.")
            download_url = "https://github.com/$slugHere/releases/download/v$releaseVersionHere/patches-$releaseVersionHere.mpp"
            signature_download_url = ''
            version = $releaseVersionHere } | ConvertTo-Json -Depth 4), (New-Object System.Text.UTF8Encoding($false)))
    $readmePath = Join-Path $releaseRepo 'README.md'
    $readmeText = Get-Content -LiteralPath $readmePath -Raw
    $readmeText = [regex]::Replace($readmeText, "(?m)There's no release yet\.[^\r\n]*", (
        "The latest release is [v$releaseVersionHere](https://github.com/$slugHere/releases/tag/v$releaseVersionHere), " +
        "with $($releaseNames.Count) patches. Add it to Morphe Manager with " +
        "[this link](https://morphe.software/add-source?github=$([Uri]::EscapeDataString($slugHere)))."))
    Set-Content -LiteralPath $readmePath -Encoding UTF8 -NoNewline -Value $readmeText
    # The versions released before this one stay, as a release keeps them: the check holds the
    # CHANGELOG to every version it described at the tag, and once HushGram has a release that's
    # more than the one being dated here.
    $releaseChangelogPath = Join-Path $releaseRepo 'CHANGELOG.md'
    $releasedBefore = [regex]::Match((Get-Content -LiteralPath $releaseChangelogPath -Raw), '(?ms)^##\s+(?!Unreleased\b).*\z')
    Set-Content -LiteralPath $releaseChangelogPath -Encoding UTF8 -NoNewline -Value (
        "# Changelog`n`n## $releaseVersionHere (2026-09-30)`n`n* **Instagram:** Every change, one line each.`n" +
        $(if ($releasedBefore.Success) { "`n" + $releasedBefore.Value } else { '' }))
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('add', '-A') | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('commit', '-m', 'index', '--quiet') | Out-Null

    $servedDir = Join-Path $releaseRoot 'served'
    New-Item -ItemType Directory -Path $servedDir -Force | Out-Null
    foreach ($file in @($releaseBundle, $releaseSbom, $releaseReceipt, $releaseSums)) {
        Copy-Item -LiteralPath $file -Destination (Join-Path $servedDir (Split-Path -Leaf $file)) -Force
    }
    Set-Content -LiteralPath (Join-Path $tools 'patch-names.txt') -Encoding ASCII -Value @($releaseNames | ForEach-Object { "Name: $_" })
    $listJava = Join-Path $tools 'list-java.cmd'
    [System.IO.File]::WriteAllText($listJava, ((@(
        '@echo off',
        'setlocal EnableExtensions',
        'rem The release check runs -jar <cli> list-patches ... --out=<file>, and cmd splits that at the',
        'rem equals sign unless the path has a space in it.',
        'set "HERE=%~dp0"',
        'set "LISTING="',
        'if "%~1"=="-version" (',
        '    echo openjdk version "21.0.5" 2024-10-15',
        '    exit /b 0',
        ')',
        ':next',
        'if "%~1"=="" exit /b 2',
        'set "ARG=%~1"',
        'if "%ARG%"=="--out" set "LISTING=%~2"',
        'if "%ARG:~0,6%"=="--out=" set "LISTING=%ARG:~6%"',
        'if defined LISTING goto list',
        'shift',
        'goto next',
        ':list',
        'echo WARNING: a stand-in warning on standard error 1>&2',
        'copy /y "%HERE%patch-names.txt" "%LISTING%" >nul || exit /b 3',
        'exit /b 0') -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)
    # What GitHub answers: each asset from the served folder by its name, and a 404 for any name
    # the release doesn't carry. Morphe's add-source page answers for this repository only, and gh
    # reads back the repository description a release sets, unless a case has put another in
    # $githubDescription. Dot-sourced into each run, so the check finds them first.
    $githubDescription = "HushGram v$releaseVersionHere`: $($releaseNames.Count) patches for Instagram $declaredBuild."
    $publishedStandIns = {
        function Invoke-WebRequest {
            param($Uri, $Method, $OutFile, $MaximumRedirection, $TimeoutSec, [switch]$PassThru, [switch]$UseBasicParsing)
            # Windows PowerShell 5.1 hands a reply without -UseBasicParsing to the IE parser, which
            # asks first, and a hook can't answer.
            if (-not $UseBasicParsing) { throw "Invoke-WebRequest $Uri without -UseBasicParsing" }
            # Framework Uri.ToString() decodes the query slash on Windows PowerShell.
            # Match the request address rather than that display representation.
            if (([Uri]$Uri).AbsoluteUri -ceq "https://morphe.software/add-source?github=$([Uri]::EscapeDataString($slugHere))") {
                return [pscustomobject]@{ StatusCode = 200; Content = [byte[]]@() }
            }
            if (([Uri]$Uri).Host -ne 'github.com') { throw (New-NotFoundAnswer) }
            $served = Join-Path $servedDir ([Uri]$Uri).Segments[-1]
            if (-not (Test-Path -LiteralPath $served -PathType Leaf)) { throw (New-NotFoundAnswer) }
            if ($OutFile) { Copy-Item -LiteralPath $served -Destination $OutFile -Force }
            [pscustomobject]@{ StatusCode = 200; Content = [System.IO.File]::ReadAllBytes($served) }
        }
        function gh {
            if (($args -join ' ') -cne "api repos/$slugHere --jq .description") { throw "gh $args is not what the release check asks" }
            $global:LASTEXITCODE = 0
            $githubDescription
        }
    }
    # The index push runs the hook's own set for it, which reads the network, the description's test
    # counts and the bundle built here. The desktop CLI and Java come from the variables the hook's
    # environment would carry, since the hook passes neither.
    Write-FactsResults $runtimeResults 'RuntimeTest' 5 -Under $releaseRepo
    Write-FactsResults $patchResults 'PatchTest' 7 -Under $releaseRepo
    function Invoke-IndexPushCheck([System.Collections.IDictionary]$Arguments) {
        $saved = @{}
        foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' -or $_.Name -in @('HUSHGRAM_DESKTOP_JAR', 'HUSHGRAM_JAVA') })) {
            $saved[$variable.Name] = $variable.Value
            Remove-Item -LiteralPath ('Env:\' + $variable.Name)
        }
        $env:HUSHGRAM_DESKTOP_JAR = $stubJar
        $env:HUSHGRAM_JAVA = $listJava
        # The asset check asks git for the published tag from where it runs, not through -Root.
        Push-Location -LiteralPath $releaseRepo
        try {
            . $publishedStandIns
            . $osvStandIn
            $global:LASTEXITCODE = 0
            $said = @(& $factsScript @Arguments 3>&1 6>&1 | ForEach-Object { "$_" }) -join "`n"
            if ($LASTEXITCODE -ne 0) { throw "The release check exited $LASTEXITCODE on the index push: $said" }
            return $said
        } finally {
            Pop-Location
            Remove-Item -LiteralPath 'Env:\HUSHGRAM_DESKTOP_JAR', 'Env:\HUSHGRAM_JAVA' -ErrorAction SilentlyContinue
            foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
        }
    }
    $publishedRun = Get-ReleaseFactsArguments -Push Index -Root $releaseRepo
    Assert-True ($publishedRun['ArtifactPath'] -eq $releaseBundle) `
        "The hook's index push did not pick the bundle built here for v$releaseVersionHere`: $($publishedRun | ConvertTo-Json -Compress)"
    foreach ($skip in @('SkipUrlCheck', 'SkipDescriptionTestCount', 'SkipLocalBuild', 'AllowPublishedIndexLag', 'SkipAdvisoryCheck')) {
        Assert-True (-not $publishedRun.Contains($skip)) "The hook's index push passes -$skip, so a release goes out with that check left out."
    }
    # The gate's run of the source commit doesn't stand for this index commit: it dates the
    # CHANGELOG too, which no index-only change touches.
    Assert-True (-not $publishedRun.Contains('FromGate')) `
        "The hook's index push read the gate's run of a commit the index changes more than the index of: $($publishedRun | ConvertTo-Json -Compress)"
    $said = Invoke-IndexPushCheck $publishedRun
    foreach ($expected in @(
            "*indexed bundle URL answers 200*",
            "*Morphe add-source page answers 200*",
            "*the GitHub description of $slugHere names v$releaseVersionHere, $($releaseNames.Count) patches and Instagram $declaredBuild*",
            "*5 runtime tests, 7 patch tests*",
            "*the Instagram source census is 0 day(s) old*",
            "*the hosted patches-$releaseVersionHere.mpp matches the bundle built here byte for byte*",
            "*published bundle is pinned to v$releaseVersionHere ($releaseCommit)*",
            "*the published bundle carries $($releaseNames.Count) patches, as described*",
            "*the index description names no Morphe Manager floor, the README carries it*",
            "*Morphe Manager can read the $releaseVersionHere entry*",
            "*the hosted release-receipt-$releaseVersionHere.json is the receipt checked here, as SHA256SUMS.txt lists it*",
            "*the hosted patches-$releaseVersionHere.cdx.json is the SBOM the receipt names and SHA256SUMS.txt lists*",
            "*OSV has no advisory for the libraries patches-$releaseVersionHere.cdx.json lists: gson 2.14.0*")) {
        Assert-True ($said -like $expected) "The index push after the release did not say $expected`: $said"
    }
    # The hosted asset checked on its own, from a checkout with no bundle built for it: the hook's
    # set for that checkout.
    $asideBundle = "$releaseBundle.aside"
    Move-Item -LiteralPath $releaseBundle -Destination $asideBundle
    try {
        $hostedRun = Get-ReleaseFactsArguments -Push Index -Root $releaseRepo
        Assert-True ($hostedRun['ArtifactIsHosted'] -and -not $hostedRun.Contains('ArtifactPath')) `
            "The hook's index push with no bundle built here did not check the hosted one on its own: $($hostedRun | ConvertTo-Json -Compress)"
        $said = Invoke-IndexPushCheck $hostedRun
        Assert-True ($said -like '*is checked on its own*' -and $said -like "*the receipt proves $($releaseNames.Count) patches*") `
            "The hosted asset was not checked on its own: $said"
    } finally {
        Move-Item -LiteralPath $asideBundle -Destination $releaseBundle -Force
    }
    # A gate run of the index commit itself, with the receipt cut from it kept beside it, from a
    # checkout holding no bundle, test results or receipt of its own: the hook's set reads the
    # gate's and every published-asset check still runs on them. A gate run whose results don't
    # match the description is refused like local ones, and one that didn't pass leaves nothing to
    # read, so the run fails on the missing results as it did before there was a gate run.
    $indexCommit = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    $indexGate = Start-GateEvidence -Commit $indexCommit
    Save-GateEvidence -Directory $indexGate -GateRoot $releaseRepo -Commit $indexCommit -Passed $true -Stage 'done' -FixturesPatched $true | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $indexGate 'receipt') -Force | Out-Null
    Copy-Item -LiteralPath $releaseReceipt, $releaseSums -Destination (Join-Path $indexGate 'receipt') -Force
    $indexManifestPath = Join-Path $indexGate 'manifest.json'
    $indexManifestText = [System.IO.File]::ReadAllText($indexManifestPath)
    $asideRoot = Join-Path $releaseRoot 'aside'
    $localOutputs = @($releaseBundle, $releaseReceipt, (Join-Path $releaseRepo $runtimeResults), (Join-Path $releaseRepo $patchResults))
    New-Item -ItemType Directory -Path $asideRoot -Force | Out-Null
    for ($i = 0; $i -lt $localOutputs.Count; $i++) { Move-Item -LiteralPath $localOutputs[$i] -Destination (Join-Path $asideRoot "$i") }
    try {
        $gateSet = Get-ReleaseFactsArguments -Push Index -Root $releaseRepo
        Assert-True ($gateSet['FromGate'] -and -not $gateSet.Contains('ArtifactPath') -and -not $gateSet.Contains('ArtifactIsHosted')) `
            "The hook's index push with the gate's run of HEAD did not read it: $($gateSet | ConvertTo-Json -Compress)"
        foreach ($skip in @('SkipUrlCheck', 'SkipDescriptionTestCount', 'SkipLocalBuild', 'AllowPublishedIndexLag', 'SkipAdvisoryCheck')) {
            Assert-True (-not $gateSet.Contains($skip)) "The hook's index push from the gate's run passes -$skip."
        }
        $said = Invoke-IndexPushCheck $gateSet
        foreach ($expected in @(
                "*reading the gate's run of $($indexCommit.Substring(0, 12))*",
                "*the test results are the gate's run of $($indexCommit.Substring(0, 12))*",
                '*5 runtime tests, 7 patch tests*',
                '*no bundle built here, so the one the gate built is the local artifact*',
                "*the hosted patches-$releaseVersionHere.mpp matches the bundle built here byte for byte*",
                "*no receipt here, so the one cut from the gate's run is checked*",
                "*the hosted release-receipt-$releaseVersionHere.json is the receipt checked here, as SHA256SUMS.txt lists it*",
                "*the receipt proves $($releaseNames.Count) patches*")) {
            Assert-True ($said -like $expected) "The index push from the gate's run did not say $expected`: $said"
        }
        # A bundle built here that isn't the gate's can't borrow the gate's test results: the set
        # names it as -ArtifactPath beside -FromGate, and the check reads the build outputs here
        # (aside, so none) in place of the gate's run.
        $localSet = [ordered]@{}
        foreach ($key in $gateSet.Keys) { $localSet[$key] = $gateSet[$key] }
        $localSet['ArtifactPath'] = $releaseBundle
        [System.IO.File]::WriteAllBytes($releaseBundle, [byte[]](1, 2, 3))
        try {
            Assert-Throws { Invoke-IndexPushCheck $localSet } '*No runtime test results found*' `
                "A local bundle other than the gate's was checked against the gate's test results."
        } finally {
            Remove-Item -LiteralPath $releaseBundle -Force -ErrorAction SilentlyContinue
        }
        $sevenPatchTests = Get-ChildItem -LiteralPath (Join-Path $indexGate 'test-results/test') -Filter '*.xml' -File -Recurse | Select-Object -First 1
        $sevenPatchTestsText = [System.IO.File]::ReadAllText($sevenPatchTests.FullName)
        try {
            # Six patch tests in the gate's results, with the manifest saying so, against a
            # description that quotes seven.
            $sixPatchTests = $sevenPatchTestsText -replace 'tests="7"', 'tests="6"' -replace '<testcase name="t7"[^>]*/>', ''
            [System.IO.File]::WriteAllText($sevenPatchTests.FullName, $sixPatchTests)
            [System.IO.File]::WriteAllText($indexManifestPath, ($indexManifestText -replace '("patches":\s*\{\s*"files":\s*1,\s*"tests":\s*)7', '${1}6'))
            Assert-True ((Get-JUnitCounts -Directory (Join-Path $indexGate 'test-results/test')).tests -eq 6 -and
                (Get-ReleaseFactsArguments -Push Index -Root $releaseRepo)['FromGate']) 'The six-test gate run was not set up as a usable one.'
            Assert-Throws { Invoke-IndexPushCheck $gateSet } '*patch test count*' `
                "An index push went through on a gate run whose patch test count the description doesn't quote."
            [System.IO.File]::WriteAllText($sevenPatchTests.FullName, $sevenPatchTestsText)
            [System.IO.File]::WriteAllText($indexManifestPath, ($indexManifestText -replace '"passed":\s*true', '"passed": false'))
            Assert-True (-not (Get-ReleaseFactsArguments -Push Index -Root $releaseRepo).Contains('FromGate')) `
                "The hook's index push read the run of a gate that didn't pass."
            Assert-Throws { Invoke-IndexPushCheck $gateSet } '*No runtime test results found*' `
                "An index push read the results of a gate that didn't pass."
        } finally {
            [System.IO.File]::WriteAllText($sevenPatchTests.FullName, $sevenPatchTestsText)
            [System.IO.File]::WriteAllText($indexManifestPath, $indexManifestText)
        }
    } finally {
        for ($i = 0; $i -lt $localOutputs.Count; $i++) { Move-Item -LiteralPath (Join-Path $asideRoot "$i") -Destination $localOutputs[$i] -Force }
        Remove-Item -LiteralPath $indexGate -Recurse -Force -ErrorAction SilentlyContinue
    }
    # What the hook's set reads that the suite's own runs used to skip: the repository description
    # GitHub shows, Morphe's add-source page, and the test counts the index description quotes.
    $githubDescription = "HushGram v0.0.1: 3 patches for Instagram $declaredBuild."
    try {
        Assert-Throws { Invoke-IndexPushCheck $publishedRun } "*The GitHub description of $slugHere does not say*" `
            'An index push went through with a GitHub description naming another release.'
    } finally {
        $githubDescription = "HushGram v$releaseVersionHere`: $($releaseNames.Count) patches for Instagram $declaredBuild."
    }
    $savedSlug = $slugHere
    $slugHere = 'SysAdminDoc/Elsewhere'
    try {
        Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*Morphe add-source page*' `
            "An index push went through with Morphe's add-source page not answering."
    } finally {
        $slugHere = $savedSlug
    }
    Write-FactsResults $patchResults 'PatchTest' 6 -Under $releaseRepo
    try {
        Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*patch test count*' `
            'An index push went through quoting a patch test count the run here does not have.'
    } finally {
        Write-FactsResults $patchResults 'PatchTest' 7 -Under $releaseRepo
    }

    # Each way the published files can disagree, one at a time, with what was served put back.
    $servedSums = Join-Path $servedDir 'SHA256SUMS.txt'
    $servedReceipt = Join-Path $servedDir "release-receipt-$releaseVersionHere.json"
    $servedSumsText = [System.IO.File]::ReadAllText($servedSums)
    $servedReceiptBytes = [System.IO.File]::ReadAllBytes($servedReceipt)
    foreach ($case in @(
            @{ Name = 'a SHA256SUMS.txt without the receipt'; Pattern = "*SHA256SUMS.txt has no entry for release-receipt-$releaseVersionHere.json*"
                Serve = { [System.IO.File]::WriteAllText($servedSums, (($servedSumsText -split "`n" | Where-Object { $_ -notlike '*release-receipt-*' }) -join "`n")) } },
            @{ Name = 'a SHA256SUMS.txt without the SBOM'; Pattern = "*SHA256SUMS.txt has no entry for patches-$releaseVersionHere.cdx.json*"
                Serve = { [System.IO.File]::WriteAllText($servedSums, (($servedSumsText -split "`n" | Where-Object { $_ -notlike '*.cdx.json' }) -join "`n")) } },
            @{ Name = 'a SHA256SUMS.txt listing another bundle'; Pattern = "*SHA256SUMS.txt lists $('1' * 64) for patches-$releaseVersionHere.mpp*"
                Serve = { [System.IO.File]::WriteAllText($servedSums, ($servedSumsText -replace '^[0-9a-f]{64}', ('1' * 64))) } },
            @{ Name = 'a hosted receipt cut again after the upload'; Pattern = "*The hosted release-receipt-$releaseVersionHere.json is not the receipt checked here*"
                Serve = { [System.IO.File]::WriteAllBytes($servedReceipt, [byte[]]($servedReceiptBytes + [byte]10))
                    $lines = $servedSumsText -split "`n" | ForEach-Object {
                        if ($_ -like '*release-receipt-*') { "$((Get-Sha256Hex -Path $servedReceipt).ToLowerInvariant())  $(Split-Path -Leaf $servedReceipt)" } else { $_ } }
                    [System.IO.File]::WriteAllText($servedSums, ($lines -join "`n")) } },
            @{ Name = 'a release with no receipt'; Pattern = "*Could not download the hosted release-receipt-$releaseVersionHere.json*"
                Serve = { Remove-Item -LiteralPath $servedReceipt } })) {
        & $case.Serve
        try {
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } $case.Pattern "An index push went through with $($case.Name)."
        } finally {
            [System.IO.File]::WriteAllText($servedSums, $servedSumsText)
            [System.IO.File]::WriteAllBytes($servedReceipt, $servedReceiptBytes)
        }
    }
    # A local bundle that isn't the one published.
    $builtBundleBytes = [System.IO.File]::ReadAllBytes($releaseBundle)
    try {
        New-ReleaseBundle -Stamp (($releaseSeconds - 60) * 1000)
        Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*does not match the local artifact hash*' `
            'An index push went through with a local bundle other than the published one.'
    } finally {
        [System.IO.File]::WriteAllBytes($releaseBundle, $builtBundleBytes)
    }
    # The census: fourteen days old is still a release, fifteen isn't. The lenient check every other
    # push runs reads none of it, so a README fix never waits on an audit.
    try {
        Save-ReleaseLedger -AgeDays 14
        $said = Invoke-IndexPushCheck $publishedRun
        Assert-True ($said -like '*the Instagram source census is 14 day(s) old*') "A release on a census 14 days old did not say so: $said"
        Save-ReleaseLedger -AgeDays 15
        Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*source census*15 days old*audit-instagram-sources.ps1*' `
            'A release went out on a census 15 days old.'
        try {
            Invoke-ReleaseCheck | Out-Null
        } catch {
            throw "The lenient check an ordinary push runs refused a census 15 days old: $($_.Exception.Message)"
        }
    } finally {
        Save-ReleaseLedger
    }
    # And a receipt this push doesn't carry at all stops the release.
    Remove-Item -LiteralPath $releaseReceipt -Force
    try {
        Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*There is no release provenance receipt*' `
            'An index push went through with no receipt to check.'
    } finally {
        [System.IO.File]::WriteAllBytes($releaseReceipt, $builtReceiptBytes)
    }
} finally {
    Remove-Item -LiteralPath $releaseRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $env:HUSHGRAM_GATE_CACHE -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[release-tooling] release root and index push contracts passed'

# --- pre-push.ps1 ----------------------------------------------------------------------------
#
# The hook runs this suite when the release tooling moves, and the release check when a published
# file does. Read out of the hook itself, so a suite line in a comment or a dead branch doesn't
# count, and neither does a release check nothing reaches.

$prePush = Join-Path $PSScriptRoot 'pre-push.ps1'
Assert-True (Test-PushGateRunsSuite $prePush 'scripts/test-release-tooling.ps1') `
    'pre-push.ps1 no longer runs scripts/test-release-tooling.ps1 when the release tooling changes.'
Assert-True (Test-PushGateRunsSuite $prePush 'scripts/test-translations.ps1') `
    'pre-push.ps1 no longer checks translation imports and hosted setup.'
Assert-True (Test-PushGateRunsSuite $prePush 'scripts/test-build-identity.ps1') `
    'pre-push.ps1 no longer checks the production build identity boundary.'
$prePushAst = Get-ScriptAst $prePush
$releaseCalls = @($prePushAst.FindAll({ param($node)
        $node -is [System.Management.Automation.Language.StringConstantExpressionAst] -and
        $node.Value -eq 'scripts/validate-release-facts.ps1' }, $true))
Assert-True ($releaseCalls.Count -gt 0) 'pre-push.ps1 no longer runs scripts/validate-release-facts.ps1.'
$prePushText = [System.IO.File]::ReadAllText($prePush)
# Its three runs (the index push, an ordinary push checked in place, and one checked in a worktree)
# take their switches from Get-ReleaseFactsArguments, the sets the cases above run. A branch that
# spelled its own switches out again would be checked by nothing here, and dropping one of them
# from the in-place branch used to pass unnoticed as long as the worktree branch still had it.
# Each takes its set inline as its only argument after the script. A set held in a variable first
# could be changed on its way to the check, and a second splat could add a switch no case here
# ran. The worktree branch runs the pushed commit's copy of the check, which can be older than
# the hook, so its set has to be spelled for that copy (-Script).
function Get-PrePushFactsKinds([System.Management.Automation.Language.Ast]$Ast) {
    $runs = @($Ast.FindAll({ param($node)
            $node -is [System.Management.Automation.Language.CommandAst] -and
            $node.GetCommandName() -eq 'pwsh' -and $node.Extent.Text -match 'factsCheck|validate-release-facts' }, $true))
    Assert-True ($runs.Count -eq 3) "pre-push.ps1 runs the release check $($runs.Count) times, not the three this suite knows."
    $inline = '^@\(ConvertTo-ScriptArguments \(Get-ReleaseFactsArguments -Push (?<push>Index|Ordinary) -Root \$\w+(?: -Script (?<script>\$\w+))?\)\)$'
    foreach ($run in $runs) {
        $elements = $run.CommandElements
        $set = [regex]::Match($elements[$elements.Count - 1].Extent.Text, $inline)
        Assert-True ($elements.Count -eq 5 -and $elements[1].Extent.Text -eq '-NoProfile' -and
            $elements[2].Extent.Text -eq '-File' -and $set.Success) `
            "A release check in pre-push.ps1 doesn't take the hook's set from Get-ReleaseFactsArguments as its only argument: $($run.Extent.Text)"
        if ($elements[3] -is [System.Management.Automation.Language.VariableExpressionAst]) {
            Assert-True ($set.Groups['script'].Value -eq $elements[3].Extent.Text) `
                "pre-push.ps1 runs $($elements[3].Extent.Text) with a set not spelled for it (-Script): $($run.Extent.Text)"
        }
        $set.Groups['push'].Value
    }
}
$pushKinds = @(Get-PrePushFactsKinds $prePushAst)
Assert-True (($pushKinds -join ',') -eq 'Index,Ordinary,Ordinary') `
    "pre-push.ps1 should take the Index set for the index push and the Ordinary set for both other branches, but takes $($pushKinds -join ', ')."
# The shapes that check refuses, each planted in a copy of the hook.
$prePushSource = $prePushAst.Extent.Text
foreach ($plant in @(
        @{ From = '@(ConvertTo-ScriptArguments (Get-ReleaseFactsArguments -Push Index -Root $Root))'
            To = '@(ConvertTo-ScriptArguments $indexSet)'; Why = 'a set held in a variable' },
        @{ From = '@(ConvertTo-ScriptArguments (Get-ReleaseFactsArguments -Push Ordinary -Root $Root))'
            To = '@(ConvertTo-ScriptArguments (Get-ReleaseFactsArguments -Push Ordinary -Root $Root)) -SkipUrlCheck'; Why = 'an extra switch' },
        @{ From = ' -Script $factsCheck))'; To = '))'; Why = "a worktree set not spelled for the pushed commit's check" })) {
    Assert-True ($prePushSource.Contains($plant.From)) "pre-push.ps1 no longer holds the text the $($plant.Why) case plants into."
    $planted = [System.Management.Automation.Language.Parser]::ParseInput($prePushSource.Replace($plant.From, $plant.To), [ref]$null, [ref]$null)
    Assert-Throws { Get-PrePushFactsKinds $planted | Out-Null } '*pre-push.ps1*' "The hook's release check was taken with $($plant.Why)."
}
# Every file the release check reads makes the hook run it, and every release script makes it run
# this suite. A list that forgot one let a change to it go out unchecked.
foreach ($published in @('patches-bundle.json', 'patches-list.json', 'gradle.properties', 'README.md', 'CHANGELOG.md',
        'gradle/libs.versions.toml', '.github/ISSUE_TEMPLATE/bug_report.yml', 'sources/instagram-sources.json',
        'scripts/manifest-delta-allowlist.txt', 'scripts/advisory-exceptions.txt', 'scripts/validate-release-facts.ps1',
        'scripts/release-receipt.ps1', 'scripts/release-advisories.ps1')) {
    Assert-True ($prePushText.Contains("'$published'")) "pre-push.ps1 doesn't run the release check when $published changes."
}
foreach ($tool in @('scripts/build-release-receipt.ps1', 'scripts/release-receipt.ps1', 'scripts/release-advisories.ps1',
        'scripts/advisory-exceptions.txt', 'scripts/validate-release-facts.ps1', 'scripts/test-release-tooling.ps1',
        'scripts/build-inputs.gradle', 'scripts/dependency-graphs.init.gradle', 'scripts/audit-dependencies.ps1')) {
    Assert-True ($prePushText.Contains("'$tool'")) "pre-push.ps1 doesn't run the release tooling suite when $tool changes."
}
$inputBuildPattern = [regex]::Match($prePushText, '(?ms)^\$buildPaths\s*=\s*(.*?)(?=^\$ledgerPaths)')
Assert-True ($inputBuildPattern.Success -and $inputBuildPattern.Value.Contains('build-inputs\.gradle')) `
    'Changing the build-input producer no longer triggers the full private-worktree build gate.'

Write-Host '[release-tooling] pre-push wiring contracts passed'
Write-Host '[release-tooling] all passed'
