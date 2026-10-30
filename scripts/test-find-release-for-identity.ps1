<#
.SYNOPSIS
    Offline checks for find-release-for-identity.ps1: the manifest read and the input parsing,
    the two pieces that aren't just a GitHub download.
.DESCRIPTION
    find-release-for-identity.ps1 reads the build identity out of a bundle's folded manifest the
    way patches/build.gradle.kts writes it, and takes its query as either an hg1 value or a
    diagnostics report. This builds a bundle whose manifest is folded exactly as the build folds
    it and confirms the identity is read back whole, confirms a bundle with no identity reads as
    none, and covers the value, report and garbage inputs. No network.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'find-release-for-identity.ps1')

function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Throws([scriptblock]$Action, [string]$Pattern) {
    try { & $Action | Out-Null } catch {
        if ($_.Exception.Message -like $Pattern) { return }
        throw "Unexpected error: $($_.Exception.Message)"
    }
    throw "Expected an error matching: $Pattern"
}

# A bundle whose manifest carries $Identity, folded the way patches/build.gradle.kts folds it:
# the line's first 70 characters, then a CRLF and one leading space, then the rest.
function New-FixtureBundle {
    param([Parameter(Mandatory = $true)][string]$Path, [string]$Identity)
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
    $manifest = "Manifest-Version: 1.0`r`nVersion: 0.0.99`r`n`r`n"
    if ($Identity) {
        $line = "HushGram-Build-Identity: $Identity"
        $folded = if ($line.Length -gt 70) { $line.Substring(0, 70) + "`r`n " + $line.Substring(70) } else { $line }
        $manifest = ([regex]"\r?\n\r?\n").Replace($manifest, "`r`n$folded`r`n`r`n", 1)
    }
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Create)
    try {
        $archive = New-Object System.IO.Compression.ZipArchive($stream, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            $entry = $archive.CreateEntry('META-INF/MANIFEST.MF')
            $writer = New-Object System.IO.StreamWriter($entry.Open(), [System.Text.UTF8Encoding]::new($false))
            try { $writer.Write($manifest) } finally { $writer.Dispose() }
        } finally { $archive.Dispose() }
    } finally { $stream.Dispose() }
}

$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ('hushgram-identity-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch -Force | Out-Null
try {
    $identity = 'hg1:' + ('b' * 64)

    # The manifest read puts the folded value back together whole.
    $bundle = Join-Path $scratch 'patches-0.0.99.mpp'
    New-FixtureBundle -Path $bundle -Identity $identity
    $read = Get-MppBuildIdentity -Path $bundle
    Assert-True ($read -ceq $identity) "The folded manifest identity read back as '$read', not '$identity'."

    # A bundle with no identity reads as none, so an early release is skipped, not misread.
    $none = Join-Path $scratch 'patches-none.mpp'
    New-FixtureBundle -Path $none -Identity ''
    Assert-True ($null -eq (Get-MppBuildIdentity -Path $none)) 'A bundle with no identity should read as none.'

    # The query is taken as a value, normalized to lower case, read from a report, or refused.
    Assert-True ((Resolve-WantedIdentity -Value $identity) -ceq $identity) 'A plain identity value should pass through.'
    $upper = 'hg1:' + ('B' * 64)
    Assert-True ((Resolve-WantedIdentity -Value $upper) -ceq $identity) 'An upper-case identity should normalize to lower case.'
    $report = Join-Path $scratch 'diag.txt'
    Set-Content -LiteralPath $report -Value "Morphe diagnostics`nSource build identity: $identity`ntrailing" -NoNewline
    Assert-True ((Resolve-WantedIdentity -Value $report) -ceq $identity) 'The identity should be read out of a report file.'
    Assert-Throws { Resolve-WantedIdentity -Value 'not-an-identity' } '*Not a build identity*'
    $empty = Join-Path $scratch 'empty.txt'
    Set-Content -LiteralPath $empty -Value 'nothing here' -NoNewline
    Assert-Throws { Resolve-WantedIdentity -Value $empty } '*No build identity*'

    Write-Host 'test-find-release-for-identity: all checks passed.'
} finally {
    Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
}
