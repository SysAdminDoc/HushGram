<#
.SYNOPSIS
    Publishes the GitHub release, once a whole-app walk backs the exact bundle it uploads.

.DESCRIPTION
    Step 7 of the release in CONTRIBUTING.md. Nothing goes public until an APK patched from the
    bundle being released has been walked end to end on a release phone or an emulator and came
    back PASS: every Hush settings page and every patched screen, with no Instagram crash. The walk
    leaves walk-receipt.json (scripts/walk-receipt.ps1), and -Walk names it.

    The four files are the ones steps 2 and 3 left: patches-<version>.mpp and its SBOM in
    patches/build/release, SHA256SUMS.txt beside them, and release-receipt-<version>.json in the
    repository root. Every one has to be there and the walk has to name that .mpp's SHA-256, or
    the run stops before anything reaches GitHub. Then `gh release create v<version>` makes the
    tag on the commit checked out here, with the notes from step 6 and those four files.

.EXAMPLE
    scripts/release/publish-release.ps1 -Version 0.0.16 -Notes notes.md -Walk walk-receipt.json

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    # The text release_notes.py wrote with --out.
    [Parameter(Mandatory = $true)][string]$Notes,
    # The walk-receipt.json a whole-app walk of this bundle left.
    [string]$Walk,
    [string]$Repository = 'SysAdminDoc/HushGram',
    [string]$Root
)

$ErrorActionPreference = 'Stop'
$scripts = Split-Path -Parent $PSScriptRoot
if (-not $Root) { $Root = Split-Path -Parent $scripts }
. (Join-Path $scripts 'walk-receipt.ps1')

function Write-Step([string]$Message) { Write-Host "[publish] $Message" }

if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "-Version is a release number like 0.0.15, not '$Version'." }
if (-not (Test-Path -LiteralPath $Notes -PathType Leaf)) { throw "There are no release notes at $Notes. Step 6 writes them." }
$releaseDir = Join-Path $Root 'patches/build/release'
$bundle = Join-Path $releaseDir "patches-$Version.mpp"
$files = @($bundle, (Join-Path $releaseDir "patches-$Version.cdx.json"),
    (Join-Path $Root "release-receipt-$Version.json"), (Join-Path $releaseDir 'SHA256SUMS.txt'))
foreach ($file in $files) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "$file is missing, so nothing was published. Steps 2 and 3 write it." }
}

# Nothing goes public until the whole app has been walked with this exact bundle.
if (-not $Walk) {
    throw ('Nothing was published. -Walk names the walk-receipt.json a whole-app walk of this bundle left on a release phone ' +
        'or an emulator.')
}
$walked = Test-WalkReceipt -Path $Walk -BundlePath $bundle -CatalogPath (Join-Path $Root 'patches-list.json')
if (-not $walked.Valid) {
    throw ("The whole-app walk doesn't back patches-$Version.mpp, so nothing was published: $($walked.Reason). " +
        'Patch an APK from this bundle, walk the whole app on a release phone or an emulator, and pass its walk-receipt.json as -Walk.')
}
Write-Step "walked on $($walked.Receipt.device): $($walked.Receipt.screens) screens, no crash"
if ($env:HUSHGRAM_ALLOW_RELEASE -cne '1') {
    throw 'Nothing was published. A release needs explicit authorization and HUSHGRAM_ALLOW_RELEASE=1.'
}

$head = "$(& git -C $Root rev-parse HEAD)".Trim()
if ($LASTEXITCODE -ne 0 -or $head -notmatch '^[0-9a-f]{40}$') { throw "Could not read the commit checked out in $Root." }
Write-Step "publishing v$Version on $($head.Substring(0, 12)) with $(($files | ForEach-Object { Split-Path -Leaf $_ }) -join ', ')"
& gh release create "v$Version" --repo $Repository --target $head --title "v$Version" --notes-file $Notes @files
if ($LASTEXITCODE -ne 0) { throw "gh release create v$Version failed." }
Write-Step "v$Version is on GitHub. The index commit (step 8) comes next."
