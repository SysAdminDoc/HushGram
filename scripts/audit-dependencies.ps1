<#
.SYNOPSIS
    Resolve and audit settings, plugin, build, test, host-contract and shipped libraries separately.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
#>
[CmdletBinding()]
param([string]$Root, [string]$GraphPath, [string]$SbomPath, [string]$BundlePath, [string]$OutputPath)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'release-advisories.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'build-jobs.ps1')

if (-not $GraphPath) {
    # Through the machine's build queue when it has one (build-jobs.ps1).
    Invoke-GradleBuild -ProjectDir $Root -Tasks @('-I', (Join-Path $PSScriptRoot 'dependency-graphs.init.gradle'),
        'dependencyGraphReport', '--no-configuration-cache', '--console=plain')
    if ($LASTEXITCODE -ne 0) { throw 'Dependency resolution failed. No advisory result is certified.' }
    $GraphPath = Join-Path $Root 'build/reports/dependencies/all-graphs.json'
}
$graphs = Read-DependencyGraphs -Path $GraphPath
if (-not $SbomPath) {
    $version = [regex]::Match((Get-Content -LiteralPath (Join-Path $Root 'gradle.properties') -Raw), '(?m)^version\s*=\s*(\S+)').Groups[1].Value
    $SbomPath = Join-Path $Root "patches/build/release/patches-$version.cdx.json"
}
$sbom = Read-ReleaseSbom -Path $SbomPath -RequireReviewedLicenses `
    -LicenseLedger (Join-Path $Root 'sources/carried-library-licenses.json')
if (-not $BundlePath) { $BundlePath = Join-Path (Split-Path -Parent $SbomPath) $sbom.BundleName }
$subject = Get-CurrentDependencyAuditSubject -Root $Root -Graphs $graphs -Sbom $sbom -BundlePath $BundlePath
$allGraphs = @(Get-DependencyAuditGraphs -Graphs $graphs -Sbom $sbom)
$libraries = @($allGraphs.libraries | Sort-Object purl -Unique)
Write-Host "[dependencies] Checking $($libraries.Count) unique resolved packages against OSV and publisher supplements."
$findings = @(Get-SbomAdvisories -Sbom ([pscustomobject]@{ Libraries = $libraries }))
foreach ($finding in $findings) {
    $origins = @($allGraphs | Where-Object { $finding.Purl -cin @($_.libraries.purl) })
    $locations = @($origins |
        ForEach-Object { "$($_.scope) $($_.owner) $($_.configuration)" })
    Add-Member -InputObject $finding -NotePropertyName Graphs -NotePropertyValue $locations
    Add-Member -InputObject $finding -NotePropertyName Scopes -NotePropertyValue @($origins.scope | Sort-Object -Unique)
}
$shippedFindings = @($findings | Where-Object { @($_.Graphs | Where-Object { $_.StartsWith('shipped ') }).Count })
$shippedExceptions = @(Read-AdvisoryExceptions -Path (Join-Path $PSScriptRoot 'advisory-exceptions.txt'))
$toolExceptions = @(Read-AdvisoryExceptions -Path (Join-Path $PSScriptRoot 'dependency-advisory-exceptions.txt') -Scoped)
$verdicts = [ordered]@{ shipped = Test-AdvisoryFindings -Findings $shippedFindings -Exceptions $shippedExceptions }
foreach ($scope in @('settings-plugin', 'project-plugin', 'build', 'test', 'host-contract')) {
    $scopeFindings = @($findings | Where-Object { $scope -cin $_.Scopes })
    $verdicts[$scope] = Test-AdvisoryFindings -Findings $scopeFindings -Exceptions $toolExceptions -Scope $scope `
        -Subject "the $scope scope" -ExceptionsLabel 'scripts/dependency-advisory-exceptions.txt'
}
$valid = @($verdicts.Values | Where-Object { -not $_.Valid }).Count -eq 0
$reason = @($verdicts.Values.Reason | Where-Object { $_ }) -join ' '
# Querying can take long enough for another producer to change a file. A report cannot certify
# inputs or artifacts that stopped matching while the publishers were being asked.
Get-CurrentDependencyAuditSubject -Root $Root -Graphs $graphs -Sbom $sbom -BundlePath $BundlePath | Out-Null
if (-not $OutputPath) { $OutputPath = Join-Path $Root 'build/reports/dependencies/advisories.json' }
$report = [ordered]@{ schemaVersion = 2; checkedAt = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'); subject = $subject
    hostMeaning = 'Locally resolved patcher contract only. Not proof of installed Manager/Desktop versions or exploit reachability.'
    graphs = $allGraphs; findings = $findings; scopeVerdicts = $verdicts; valid = $valid; reason = $reason }
$directory = Split-Path -Parent ([IO.Path]::GetFullPath($OutputPath))
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$report | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
if ($valid) { Read-CurrentDependencyAdvisoryReport -Path $OutputPath -Subject $subject | Out-Null }
foreach ($scope in @($allGraphs | Group-Object scope)) {
    $count = @($scope.Group.libraries | Sort-Object purl -Unique).Count
    Write-Host "[dependencies] $($scope.Name): $($scope.Count) graphs, $count resolved packages"
}
foreach ($finding in $findings) {
    Write-Host "[dependencies] $($finding.Advisory) $($finding.Severity.Level) in $($finding.Package) $($finding.Version) [$($finding.Sources -join ', ')]"
}
Write-Host "[dependencies] Report: $OutputPath"
Write-Host '[dependencies] Host contracts are not an installed-host audit; findings do not prove exploit reachability.'
if (-not $valid) { throw $reason }
