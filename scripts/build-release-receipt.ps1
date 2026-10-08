<#
.SYNOPSIS
    Build the machine-readable release provenance receipt.

.DESCRIPTION
    Local patching puts three things together: the user's APK, this bundle, and a toolchain. A
    checksum can only say that one of them arrived unaltered. This writes down all of it as one
    JSON document: which commit and tag the bundle was built from, what the bundle weighs and
    hashes to, which extension payloads it carries, which APK each proof was run against, that
    every patch in the catalog applied to it, and what patching did to the Android manifest.

    Nothing here is asserted. Each fixture is patched with the real desktop CLI, the verdicts
    come out of the CLI's own result report, and both manifests are read back with aapt2: the
    patched one, and the one the patches started from. A split bundle is merged into one APK first
    with the CLI's own merger (Get-MergedApk), the CLI patches that merge, and its manifest is the
    baseline, since the merge rewrites the manifest before any patch runs. A
    patch that fails on any fixture stops the run with its name and no receipt is written: the
    receipt describes a bundle that fully applies, which is why the validator refuses any
    verdict of applied = false rather than reading it as a recorded failure. Every build the
    catalog declares needs a fixture of its own, patched without -f: the README says the
    patches were checked on each of them, and the receipt is refused without a run of every
    one. A fixture of any other build is patched under -f and recorded as forced. The fixtures'
    base manifests are read before anything is patched, so a declared build with no fixture
    stops the run at once instead of after the others. Without -Fixture, the run takes the
    fixture of each declared build from the folder HUSHGRAM_FIXTURE_DIR names, found the way the
    pre-push hook finds it (instagram-<version>-*.apks, .apkm, .xapk or .apk).

    The patched APKs are working files and are deleted on the way out, including after a failure.

    The bundle has to be a build of HEAD from a clean tree, and a clean tree when the receipt is
    cut doesn't show that. So its stamp has to be HEAD's commit time (the build writes 0 when the
    tree had uncommitted changes as it started), and no source may be newer than it. Both are
    checked before anything is patched.

    Before any of that, the SBOM :patches:buildAndroid wrote beside the bundle is held to it and
    the libraries it lists are put to OSV (release-advisories.ps1). A high or critical advisory
    that scripts/advisory-exceptions.txt doesn't accept stops the run before anything is patched,
    and so does an OSV that can't be asked. -SkipAdvisoryCheck lets an offline run through with a
    warning. The receipt records the SBOM's name, hash and component count either way, and the
    pre-push hook asks OSV again on the index push.

    Last, SHA256SUMS.txt is written beside the bundle, listing the bundle, its SBOM and the
    receipt. A release publishes all four, and the index push downloads the first three back and
    holds each to that list.

    Taken from Hushfacebook's scripts/build-release-receipt.ps1
    (https://github.com/SysAdminDoc/Hushfacebook, commit 814acd23d7b70d5d23abce6cb6c97767e16a051e),
    which came from Hushfeed (https://github.com/SysAdminDoc/hushfeed). GPL-3.0-only.
    Modified for HushGram (Instagram), 2026: the fixtures default to the declared builds in
    HUSHGRAM_FIXTURE_DIR, the desktop CLI is found the way the other scripts here find it, and
    SHA256SUMS.txt is written with the receipt.

.EXAMPLE
    scripts/build-release-receipt.ps1 -WorkDir C:\scratch

    Every declared build's fixture from HUSHGRAM_FIXTURE_DIR, the bundle in patches/build/release,
    and the receipt in the repository root, where .gitignore keeps it out of commits.

.EXAMPLE
    One -Fixture taking a comma separated list, not the switch repeated: PowerShell binds a
    parameter once and refuses the second.

    scripts/build-release-receipt.ps1 -WorkDir C:\scratch `
        -Fixture C:\fixtures\instagram-a.apks,C:\fixtures\instagram-b.apks
#>
[CmdletBinding()]
param(
    [string[]]$Fixture,
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [string]$Root,
    [string]$Bundle,
    [string]$PatchList,
    [string]$DesktopJar,
    [string]$Java,
    [string]$Aapt2,
    [string]$OutputPath,
    # The SBOM :patches:buildAndroid writes beside the bundle, named for it. Defaults to that.
    [string]$Sbom,
    # A freshly resolved, input-bound report. Without one the full dependency audit resolves it.
    [string]$DependencyGraph,
    # For working with no network only: OSV isn't asked about the SBOM's libraries, and the run
    # says so. The index push asks again, so a release can't go out on it.
    [switch]$SkipAdvisoryCheck
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }

. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
. (Join-Path $PSScriptRoot 'release-advisories.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

$Java = Resolve-Java -Explicit $Java
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $Root
# The receipt records real patch verdicts, so it has to actually patch each fixture.
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $Root -Required

$releaseVersion = Get-BundleVersion -Root $Root
if (-not $Bundle) { $Bundle = Get-ReleaseBundlePath -Root $Root -Version $releaseVersion }
if (-not (Test-Path -LiteralPath $Bundle -PathType Leaf)) {
    throw "No bundle for version ${releaseVersion}: $Bundle. Run :patches:generatePatchesList then :patches:buildAndroid."
}
# Found in PowerShell's location; the ZipFile reads below would look in the process directory.
$Bundle = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Bundle)
if (-not $PatchList) { $PatchList = Join-Path $Root 'patches-list.json' }
if (-not $OutputPath) { $OutputPath = Join-Path $Root "release-receipt-$releaseVersion.json" }

$catalog = Get-Content -LiteralPath $PatchList -Raw | ConvertFrom-Json
$patchNames = @($catalog.patches | ForEach-Object { $_.name })
if ($patchNames.Count -eq 0) { throw "No patches listed in $PatchList." }
$dependencyNames = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $patchNames)
$expectedTarget = Get-PatchTarget -PatchList $catalog

# The declared builds' fixtures, when none were named: one per declared version, as the pre-push
# hook picks them. A declared build with no fixture there stops the run below, before anything is
# patched, with the builds the catalog declares.
if (-not $Fixture -or $Fixture.Count -eq 0) {
    $fixtureDir = $env:HUSHGRAM_FIXTURE_DIR
    if (-not $fixtureDir -or -not (Test-Path -LiteralPath $fixtureDir -PathType Container)) {
        throw ('No -Fixture, and HUSHGRAM_FIXTURE_DIR names no folder to take the declared builds from. ' +
            "The receipt needs a run of $($expectedTarget.PackageName) $(Format-DeclaredBuilds -Target $expectedTarget).")
    }
    $Fixture = @(foreach ($version in @($expectedTarget.PackageVersions)) {
        Get-ChildItem -LiteralPath $fixtureDir -File | Where-Object {
            $_.Name -like "instagram-$version-*" -and $_.Extension -in '.apk', '.apks', '.apkm', '.xapk'
        } | Sort-Object Name | Select-Object -First 1 | ForEach-Object { $_.FullName }
    })
    if ($Fixture.Count -eq 0) {
        throw ("HUSHGRAM_FIXTURE_DIR ($fixtureDir) holds no fixture of " +
            "$($expectedTarget.PackageName) $(Format-DeclaredBuilds -Target $expectedTarget). Name them with -Fixture.")
    }
}

$catalogText = Get-Content -LiteralPath (Join-Path $Root 'gradle/libs.versions.toml') -Raw
$patcherMatch = [regex]::Match($catalogText, '(?m)^\s*morphe-patcher\s*=\s*"([^"]+)"')
$floorMatch = [regex]::Match($catalogText, '(?m)^\s*manager-floor\s*=\s*"([^"]+)"')
if (-not $patcherMatch.Success -or -not $floorMatch.Success) {
    throw 'gradle/libs.versions.toml does not pin both morphe-patcher and manager-floor.'
}

$commit = (& git -C $Root rev-parse HEAD).Trim()
if ($commit -notmatch '^[0-9a-f]{40}$') { throw "git did not answer with a commit: $commit" }
$commitTimestamp = [long](& git -C $Root log -1 --format=%ct).Trim()

# The bundle is read once, here, before any fixture is patched. Measuring it at the end instead
# would describe whatever the release path holds when the run finishes, which is not necessarily
# what the patch runs used: a run takes long enough for another buildAndroid to replace it.
$bundleManifest = Get-BundleManifestFacts -BundlePath $Bundle
$bundleSize = (Get-Item -LiteralPath $Bundle).Length
$bundleHash = Get-Sha256Hex -Path $Bundle

# Refused here rather than reported, because a receipt that records the mismatch would be a
# document saying its own subject cannot be rebuilt from the source it names.
$dirty = @(& git -C $Root status --porcelain)
if ($dirty.Count -gt 0) {
    # -join, not Join-String: the pre-push hook prefers pwsh and falls back to Windows
    # PowerShell 5.1, which has no Join-String, so a dirty tree would have stopped the run with
    # a command-not-found instead of the reason it stopped.
    $shown = @($dirty | Select-Object -First 5 | ForEach-Object { $_.Trim() }) -join '; '
    throw ("The working tree has uncommitted changes, so the commit this receipt would name is " +
        "not what was built: $shown")
}

# A clean tree now says nothing about the tree the bundle was built from. Edits someone else made
# in a shared checkout can be in the bundle and gone again by the time the receipt is cut, and the
# stamp used to be HEAD's commit time whatever the tree held. :patches:buildAndroid stamps a bundle
# 0 now when the tree had uncommitted changes as it started, and anything but this commit's time
# means the bundle isn't a build of it. The receipt check at the end refuses that too, but only
# once every fixture has been patched.
$expectedStamp = $commitTimestamp * 1000
if ($bundleManifest.timestamp -ne $expectedStamp) {
    if ($bundleManifest.timestamp -eq 0) {
        throw ("The bundle is stamped 0. :patches:buildAndroid writes that when the working tree has uncommitted " +
            "changes as the build starts, or git can't read it, so this isn't a build of commit $commit. " +
            'Build it again from a clean tree. Nothing was patched.')
    }
    throw ("The bundle is stamped $($bundleManifest.timestamp), but commit $commit was made at $expectedStamp. " +
        "It was built from another commit, or with SOURCE_DATE_EPOCH set to something else. Build it again " +
        'from this commit. Nothing was patched.')
}
# And what changed after the build started: an edit made and put back since leaves the tree clean
# and the stamp right, and a file written after the bundle is the trace it leaves.
$newerSources = @(Get-SourcesNewerThanBundle -Root $Root -Bundle $Bundle)
if ($newerSources.Count -gt 0) {
    throw ("$($newerSources.Count) source file(s) changed after the bundle was built, the newest " +
        "$($newerSources[0].FullName). The bundle may not hold what they hold now, so build it again. " +
        'Nothing was patched.')
}

# The SBOM, read with the bundle and for the same reason, and held to it: an SBOM left from another
# build would put another bundle's libraries in front of OSV. Then the advisory gate, before any
# fixture is patched, so a release OSV refuses doesn't cost the patch runs first.
if (-not $Sbom) { $Sbom = [System.IO.Path]::ChangeExtension($Bundle, '.cdx.json') }
if (-not (Test-Path -LiteralPath $Sbom -PathType Leaf)) {
    throw "No SBOM for the bundle: $Sbom. :patches:buildAndroid writes it beside the bundle, so build again."
}
$Sbom = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Sbom)
$sbomDocument = Read-ReleaseSbom -Path $Sbom -RequireReviewedLicenses `
    -LicenseLedger (Join-Path $Root 'sources/carried-library-licenses.json')
$sbomBound = Test-ReleaseSbom -Sbom $sbomDocument -BundlePath $Bundle -BundleName (Split-Path -Leaf $Bundle)
if (-not $sbomBound.Valid) { throw "The SBOM does not describe the bundle: $($sbomBound.Reason)" }
$buildIdentity = Get-CanonicalBuildIdentity -Root $Root
$sbomIdentity = Read-SbomCanonicalBuildIdentity -Path $Sbom
if ($bundleManifest.buildIdentity -cne $buildIdentity.id -or $sbomIdentity.id -cne $buildIdentity.id) {
    throw 'The bundle or SBOM production identity differs from the current canonical inputs. Nothing was patched.'
}
if ($SkipAdvisoryCheck) {
    # Offline preparation still verifies the producer's input identity. It cannot certify
    # tooling advisories, and published receipt readers keep their historical rules.
    if (-not $DependencyGraph) { $DependencyGraph = Join-Path $Root 'build/reports/dependencies/all-graphs.json' }
    Get-CurrentDependencyAuditSubject -Root $Root -Graphs (Read-DependencyGraphs -Path $DependencyGraph) `
        -Sbom $sbomDocument -BundlePath $Bundle | Out-Null
    Invoke-ReleaseAdvisoryGate -Sbom $sbomDocument -ExceptionsPath (Join-Path $PSScriptRoot 'advisory-exceptions.txt') `
        -SkipAdvisoryCheck
} else {
    & (Join-Path $PSScriptRoot 'audit-dependencies.ps1') -Root $Root -GraphPath $DependencyGraph -SbomPath $Sbom -BundlePath $Bundle
}

function Get-PatchVerdicts {
    <#
    .SYNOPSIS
        One entry per catalog patch, in catalog order, from the CLI's own report.
    .DESCRIPTION
        Every requested patch has to appear in exactly one of the report's two lists. A patch in
        neither was not decided, and recording it as applied would be the receipt inventing a
        verdict, which is the one thing it exists not to do.
    #>
    param([object]$Report, [string[]]$Names)

    $applied = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]@(Get-ReportPatchNames -Entries $Report.appliedPatches),
        [System.StringComparer]::Ordinal)
    $failures = @{}
    foreach ($failure in @($Report.failedPatches)) {
        $name = if ($null -ne $failure.patch) { [string]$failure.patch.name } else { $null }
        if (-not $name) { continue }
        $reason = ([string]$failure.reason -split "`n" | Select-Object -First 1).Trim()
        $failures[$name] = $reason
    }

    $verdicts = New-Object System.Collections.Generic.List[object]
    foreach ($name in $Names) {
        if ($applied.Contains($name)) {
            $verdicts.Add([ordered]@{ name = $name; applied = $true; reason = $null })
        } elseif ($failures.ContainsKey($name)) {
            $verdicts.Add([ordered]@{ name = $name; applied = $false; reason = $failures[$name] })
        } else {
            throw "The result report decided nothing about patch $name."
        }
    }
    return ,$verdicts.ToArray()
}

# Read with the hash and size above, and for the same reason.
$extensionPayloads = Get-ExtensionPayloads -BundlePath $Bundle

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$workRoot = (Resolve-Path -LiteralPath $WorkDir).Path
$targets = New-Object System.Collections.Generic.List[object]

# Every declared build needs its own run without -f, and the receipt check refuses a receipt
# without one. That check comes after the patch runs, and a patch run unpacks the whole split
# bundle, while a base manifest is cheap to read. So the fixtures are held to the catalog here,
# before anything is patched: a run given only the newest build patched it and then refused the
# receipt it had produced.
$stockFacts = @{}
foreach ($apk in $Fixture) {
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Fixture not found: $apk" }
    $label = Split-Path -Leaf $apk
    $readDir = Resolve-WithinRoot -Path (Join-Path $workRoot ("manifest-" + [guid]::NewGuid().ToString('N'))) -Root $workRoot
    try {
        # Instagram's Play install is a split bundle, which aapt2 can't read; its manifest is the base APK's.
        $stockApk = Get-BaseApk -Apk $apk -Destination (Join-Path $readDir 'stock-base.apk')
        $stock = Get-ApkManifestFacts -Apk $stockApk -Aapt2 $Aapt2
    } finally {
        Remove-GeneratedPath -Path $readDir -Root $workRoot
    }
    if ($stock.package -ne $expectedTarget.PackageName) {
        throw "$label is $($stock.package), not the catalog's target $($expectedTarget.PackageName)."
    }
    $stockFacts[$apk] = $stock
}
# A fixture is the run of a declared build only when it's that build, version code and all: in
# Hushfacebook another arm64 build of Facebook 580 was taken for the declared one by its name,
# patched without -f and recorded as proof of a build nobody ran.
$fixtureVersions = @($Fixture | Where-Object {
        Test-DeclaredBuild -Target $expectedTarget -VersionName ([string]$stockFacts[$_].versionName) `
            -VersionCode ([string]$stockFacts[$_].versionCode) } |
    ForEach-Object { [string]$stockFacts[$_].versionName })
$unfixed = @($expectedTarget.PackageVersions | Where-Object { $fixtureVersions -notcontains $_ })
if ($unfixed.Count -gt 0) {
    $given = @($Fixture | ForEach-Object { "$($stockFacts[$_].versionName) ($($stockFacts[$_].versionCode))" })
    throw ("No fixture is the declared $($expectedTarget.PackageName) $($unfixed -join ', '), and the " +
        "receipt needs a run of every declared build without -f. The catalog declares " +
        "$(Format-DeclaredBuilds -Target $expectedTarget), and the fixtures given are $($given -join ', '). " +
        'Nothing was patched.')
}

foreach ($apk in $Fixture) {
    $label = Split-Path -Leaf $apk
    Write-Host "[receipt] patching $label with $($patchNames.Count) patches"

    $runId = [guid]::NewGuid().ToString('N')
    $runDir = Resolve-WithinRoot -Path (Join-Path $workRoot "receipt-$runId") -Root $workRoot
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    try {
        $stock = $stockFacts[$apk]

        $out = Resolve-WithinRoot -Path (Join-Path $runDir 'patched.apk') -Root $workRoot
        $temp = Resolve-WithinRoot -Path (Join-Path $runDir 'tmp') -Root $workRoot
        $resultPath = Resolve-WithinRoot -Path (Join-Path $runDir 'result.json') -Root $workRoot

        # A fixture of a build the bundle does not declare, by version name or by version code, is
        # patched under -f, and the receipt says so rather than letting a forced run read like a
        # declared-compatible one.
        $forced = -not (Test-DeclaredBuild -Target $expectedTarget -VersionName ([string]$stock.versionName) `
            -VersionCode ([string]$stock.versionCode))

        # The one APK the CLI patches: the fixture's merge when it's a split bundle, made here
        # because the CLI deletes its own, or the fixture itself. No merge, no receipt.
        $mergedApk = Resolve-WithinRoot -Path (Join-Path $runDir 'stock-merged.apk') -Root $workRoot
        $patchInput = Get-MergedApk -Apk $apk -Destination $mergedApk -Java $Java -DesktopJar $DesktopJar

        $enable = @()
        foreach ($name in $patchNames) { $enable += '-e'; $enable += $name }
        $arguments = @('patch', '--exclusive', '--continue-on-error', '--unsigned', '-p', $Bundle,
            '-o', $out, '-t', $temp, '-r', $resultPath)
        if ($forced) { $arguments += '-f' }
        $arguments = $arguments + $enable + @($patchInput)
        # Continue for the call alone: the CLI logs WARNING and SEVERE on stderr, which Windows
        # PowerShell 5.1 turns into a terminating error under Stop. The report and the exit code
        # are what decide.
        $preference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $global:LASTEXITCODE = -1
            & $Java '-jar' $DesktopJar @arguments 2>&1 | Out-Null
            $cliExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $preference
        }

        if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
            throw "The desktop CLI wrote no result report for $label (exit $cliExitCode)."
        }
        $report = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
        $validation = Test-PatchingReport -Report $report -ExpectedNames $patchNames `
            -AllowedDependencyNames $dependencyNames -OutputPath $out `
            -ExpectedPackageName $expectedTarget.PackageName `
            -ExpectedPackageVersion $stock.versionName
        if (-not $validation.Valid) { throw "$label did not patch cleanly: $($validation.Reason)" }
        if ($cliExitCode -ne 0) { throw "The desktop CLI exited with $cliExitCode on $label." }

        $coverage = @(Get-ApkTargetCoverage -Apk $out -Java $Java -DesktopJar $DesktopJar -Names $patchNames)
        $coverageVerdict = Test-TargetCoverage -Coverage $coverage -Names $patchNames -Package $stock.package `
            -VersionName $stock.versionName -VersionCode $stock.versionCode
        if (-not $coverageVerdict.Valid -or (-not $forced -and -not $coverageVerdict.Reviewed)) {
            throw "Target coverage failed for ${label}: $($coverageVerdict.Reason)"
        }

        $patched = Get-ApkManifestFacts -Apk $out -Aapt2 $Aapt2
        # The manifest the patches started from is the APK the CLI patched, the merge for a split
        # bundle, not the base APK's: the merge rewrites the manifest itself, and a delta against
        # the base would record its changes as the patches' own.
        $baseline = Get-ApkManifestFacts -Apk $patchInput -Aapt2 $Aapt2
        $delta = Get-ManifestDelta -Stock $baseline -Patched $patched
        $verdicts = Get-PatchVerdicts -Report $report -Names $patchNames
        $changes = @(ConvertTo-ManifestDeltaEntries -Delta $delta)
        Write-Host ("[receipt] $label" + ": $(@($verdicts | Where-Object { $_.applied }).Count)/" +
            "$($patchNames.Count) applied, $($changes.Count) manifest changes")
        foreach ($change in $changes) { Write-Host "[receipt]   $change" }

        $targets.Add([ordered]@{
            source        = [ordered]@{
                file        = $label
                package     = $stock.package
                versionName = $stock.versionName
                versionCode = $stock.versionCode
                sha256      = Get-Sha256Hex -Path $apk
                forced      = $forced
            }
            patches       = $verdicts
            coverage      = $coverage
            coverageReviewed = $coverageVerdict.Reviewed
            manifestDelta = [ordered]@{
                permissionsAdded          = @($delta.permissionsAdded)
                permissionsRemoved        = @($delta.permissionsRemoved)
                exportedComponentsAdded   = @($delta.exportedComponentsAdded)
                exportedComponentsRemoved = @($delta.exportedComponentsRemoved)
                versionCodeChanged        = @($delta.versionCodeChanged)
            }
        })
    } finally {
        if ($runDir.StartsWith($workRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
            (Test-Path -LiteralPath $runDir)) {
            Remove-Item -LiteralPath $runDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

if ((Get-CanonicalBuildIdentity -Root $Root).id -cne $buildIdentity.id -or
    (Get-Sha256Hex -Path $Bundle) -cne $bundleHash -or (Get-Sha256Hex -Path $Sbom) -cne $sbomDocument.Sha256) {
    throw 'The canonical inputs or final artifacts changed while the fixtures were being patched. No receipt was written.'
}
$receipt = [ordered]@{
    schemaVersion = Get-ReleaseReceiptSchemaVersion
    buildIdentity = $buildIdentity
    generatedAt   = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    release       = [ordered]@{
        version         = $releaseVersion
        tag             = "v$releaseVersion"
        commit          = $commit
        commitTimestamp = $commitTimestamp
        patchCount      = $patchNames.Count
    }
    bundle        = [ordered]@{
        file      = Split-Path -Leaf $Bundle
        sizeBytes = $bundleSize
        sha256    = $bundleHash
        timestamp = $bundleManifest.timestamp
    }
    sbom          = [ordered]@{
        file       = Split-Path -Leaf $Sbom
        sha256     = $sbomDocument.Sha256
        components = @($sbomDocument.Components).Count
    }
    toolchain     = [ordered]@{
        patcherVersion = $patcherMatch.Groups[1].Value
        managerFloor   = $floorMatch.Groups[1].Value
    }
    extension     = [ordered]@{ dexPayloads = $extensionPayloads }
    targets       = $targets.ToArray()
}

# The receipt is checked before it is written. A file that fails the gate it exists to pass is
# worse than no file, because the next reader has to work out which half to believe.
$approved = Read-ManifestDeltaAllowlist -Path (Join-Path $PSScriptRoot 'manifest-delta-allowlist.txt')
$check = Test-ReleaseReceipt -Receipt ($receipt | ConvertTo-Json -Depth 12 | ConvertFrom-Json) `
    -ExpectedVersion $releaseVersion -ExpectedPatchNames $patchNames `
    -ExpectedPatcherVersion $patcherMatch.Groups[1].Value `
    -ExpectedManagerFloor $floorMatch.Groups[1].Value `
    -ExpectedPackageName $expectedTarget.PackageName `
    -ExpectedPackageVersions $expectedTarget.PackageVersions -ExpectedPackageVersionCodes $expectedTarget.PackageVersionCodes `
    -BundlePath $Bundle `
    -ApprovedManifestDelta $approved -SbomPath $Sbom
if (-not $check.Valid) { throw "The receipt this run produced does not pass validation: $($check.Reason)" }

$receipt | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Host "[receipt] wrote $OutputPath"

# The list a release publishes beside the three files, in the form sha256sum writes, and the form
# validate-release-facts.ps1 reads back on the index push. Written beside the bundle, in the build
# folder .gitignore keeps out of commits.
$sums = Join-Path (Split-Path -Parent $Bundle) 'SHA256SUMS.txt'
$lines = foreach ($file in @($Bundle, $Sbom, $OutputPath)) {
    "$((Get-Sha256Hex -Path $file).ToLowerInvariant())  $(Split-Path -Leaf $file)"
}
[System.IO.File]::WriteAllText($sums, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "[receipt] wrote $sums"
Write-Host ("[receipt] a release publishes $(Split-Path -Leaf $Bundle), $(Split-Path -Leaf $Sbom), " +
    "$(Split-Path -Leaf $OutputPath) and SHA256SUMS.txt")
exit 0
