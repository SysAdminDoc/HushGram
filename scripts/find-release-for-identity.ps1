<#
.SYNOPSIS
    Name the official HushGram release a diagnostics build identity came from, or say it came
    from none.

.DESCRIPTION
    A diagnostics report carries the line `hg1:<sha256>`, the canonical build identity of the
    bundle that patched the app (S/Utils.java getSourceBuildIdentity, set at patch time from the
    patches jar's `HushGram-Build-Identity` manifest entry). Forks such as
    a092devs/NexAlloyNext-Patches and Chazy12/HushGram repackage these patches, so a report can
    carry an identity we never shipped. This reads the identity out of each published release's
    `.mpp` and tells you which release matches, so a bug report from a build we didn't make is
    plain before anyone chases it.

    Pass either the `hg1:` value itself or the path to a diagnostics report to read it from. Each
    release's `.mpp` is downloaded once with `gh` and cached under -CacheDir, so a second run over
    the same cache does no network. Releases are read newest first and the search stops at the
    first match; -All reads every release and lists every match.

    The identity sits in the bundle's META-INF/MANIFEST.MF as `HushGram-Build-Identity`, folded
    the way patches/build.gradle.kts writes it (a CRLF and one space inside the value). The same
    value is what BundleVerifier checks the bundle against and what the extension returns in
    diagnostics, so the manifest is authoritative.

.PARAMETER Identity
    An `hg1:<64 hex>` value, or the path to a diagnostics report that contains one.

.PARAMETER Repo
    The GitHub repository to read releases from. Defaults to SysAdminDoc/HushGram.

.PARAMETER CacheDir
    Where downloaded `.mpp` bundles are kept between runs. Defaults to a HushGram folder under the
    system temp directory.

.PARAMETER All
    Read every release and list every match, instead of stopping at the newest matching release.

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>
[CmdletBinding()]
param(
    # Not Mandatory, so the test can dot-source this for its functions without a prompt; the main
    # block below still refuses to run without it.
    [string]$Identity,
    [string]$Repo = 'SysAdminDoc/HushGram',
    [string]$CacheDir,
    [switch]$All
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'build-identity.ps1')

function Resolve-WantedIdentity {
    <# The hg1 value to look for: the argument itself, or the first one in a report file. #>
    param([Parameter(Mandatory = $true)][string]$Value)
    $shape = '\bhg1:[0-9a-f]{64}\b'
    if ($Value -match "^$shape$") { return $Value.ToLowerInvariant() }
    if (Test-Path -LiteralPath $Value -PathType Leaf) {
        $text = [System.IO.File]::ReadAllText($Value)
        $found = [regex]::Match($text, $shape, 'IgnoreCase')
        if ($found.Success) { return $found.Value.ToLowerInvariant() }
        throw "No build identity (hg1:<64 hex>) found in the report: $Value"
    }
    throw "Not a build identity or a readable report: $Value. Pass an hg1:<64 hex> value or a diagnostics report path."
}

function Get-ReleaseMpp {
    <# The cached .mpp for a tag, downloaded once if it isn't there yet, or $null when the release
       publishes none. #>
    param([Parameter(Mandatory = $true)][string]$Tag, [Parameter(Mandatory = $true)][string]$Dir)
    $tagDir = Join-Path $Dir $Tag
    $have = @(Get-ChildItem -LiteralPath $tagDir -Filter '*.mpp' -File -ErrorAction SilentlyContinue)
    if ($have.Count -eq 0) {
        New-Item -ItemType Directory -Force -Path $tagDir | Out-Null
        Write-Host "[identity] downloading the $Tag bundle"
        # gh exits non-zero when a release has no matching asset; the file check below is what decides.
        & gh release download $Tag --repo $Repo --pattern '*.mpp' --dir $tagDir 2>$null
        $have = @(Get-ChildItem -LiteralPath $tagDir -Filter '*.mpp' -File -ErrorAction SilentlyContinue)
    }
    if ($have.Count -eq 0) { return $null }
    return $have[0].FullName
}

function Invoke-Main {
    if (-not $Identity) {
        throw 'Pass -Identity: an hg1:<64 hex> value, or the path to a diagnostics report that contains one.'
    }
    $wanted = Resolve-WantedIdentity -Value $Identity
    if (-not $CacheDir) { $CacheDir = Join-Path ([System.IO.Path]::GetTempPath()) 'hushgram-identity-cache' }
    New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null

    $releases = gh release list --repo $Repo --json tagName,publishedAt --limit 200 | ConvertFrom-Json
    if (-not $releases) { throw "No releases found for $Repo." }
    $releases = @($releases | Sort-Object { [datetime]$_.publishedAt } -Descending)

    $found = New-Object System.Collections.Generic.List[object]
    foreach ($release in $releases) {
        $tag = $release.tagName
        $mpp = Get-ReleaseMpp -Tag $tag -Dir $CacheDir
        if (-not $mpp) { Write-Host "[identity] $tag publishes no .mpp, skipping"; continue }
        $id = Get-MppBuildIdentity -Path $mpp
        if (-not $id) { Write-Host "[identity] $tag has no build identity in its bundle, skipping"; continue }
        if ($id -ceq $wanted) {
            $found.Add($release)
            if (-not $All) { break }
        }
    }

    if ($found.Count -eq 0) {
        Write-Host "[identity] no release bundle carries $wanted"
        Write-Output "This build identity isn't from an official HushGram release."
        $script:ResultCode = 2
        return
    }

    foreach ($release in $found) {
        Write-Host "[identity] $wanted is HushGram $($release.tagName), published $($release.publishedAt)"
        Write-Output $release.tagName
    }
    $script:ResultCode = 0
}

# Run only when invoked directly. A dot-source (the test) loads the functions and stops here.
if ($MyInvocation.InvocationName -ne '.') {
    $script:ResultCode = 1
    Invoke-Main
    exit $script:ResultCode
}
