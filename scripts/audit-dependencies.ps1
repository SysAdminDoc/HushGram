<#
.SYNOPSIS
    Resolve and audit settings, plugin, build, test, host-contract and shipped libraries, and the
    Gradle distribution the wrapper runs, separately.
.DESCRIPTION
    -DesktopJar adds the Morphe Desktop JAR a release patches with as the external-desktop scope,
    bound to that file's SHA-256. Its libraries come from the JAR itself, so repository overrides
    don't stand in for them, and each finding there says whether the classes its advisory names
    are in the JAR.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
#>
[CmdletBinding()]
param([string]$Root, [string]$GraphPath, [string]$SbomPath, [string]$BundlePath, [string]$OutputPath, [string]$DesktopJar)

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
$subject = Get-CurrentDependencyAuditSubject -Root $Root -Graphs $graphs -Sbom $sbom -BundlePath $BundlePath -DesktopJar $DesktopJar
$desktop = if ($DesktopJar) { Get-ExternalDesktopGraph -JarPath $DesktopJar }
$allGraphs = @(Get-DependencyAuditGraphs -Graphs $graphs -Sbom $sbom -Root $Root -Desktop $desktop)
$libraries = @($allGraphs.libraries | Sort-Object purl -Unique)
Write-Host "[dependencies] Checking $($libraries.Count) unique resolved packages against OSV and publisher supplements."
$findings = @(Get-SbomAdvisories -Sbom ([pscustomobject]@{ Libraries = $libraries }))
foreach ($finding in $findings) {
    $origins = @($allGraphs | Where-Object { $finding.Purl -cin @($_.libraries.purl) })
    $locations = @($origins |
        ForEach-Object { "$($_.scope) $($_.owner) $($_.configuration)" })
    Add-Member -InputObject $finding -NotePropertyName Graphs -NotePropertyValue $locations
    Add-Member -InputObject $finding -NotePropertyName Scopes -NotePropertyValue @($origins.scope | Sort-Object -Unique)
    if ($desktop -and $finding.Purl -cin @($desktop.libraries.purl)) {
        Add-Member -InputObject $finding -NotePropertyName DesktopClassPresence -NotePropertyValue (
            Get-DesktopClassPresence -Finding $finding -Graph $desktop)
    }
}
$shippedFindings = @($findings | Where-Object { @($_.Graphs | Where-Object { $_.StartsWith('shipped ') }).Count })
$shippedExceptions = @(Read-AdvisoryExceptions -Path (Join-Path $PSScriptRoot 'advisory-exceptions.txt'))
$toolExceptions = @(Read-AdvisoryExceptions -Path (Join-Path $PSScriptRoot 'dependency-advisory-exceptions.txt') -Scoped)
$verdicts = [ordered]@{ shipped = Test-AdvisoryFindings -Findings $shippedFindings -Exceptions $shippedExceptions }
foreach ($scope in @('settings-plugin', 'project-plugin', 'build', 'test', 'host-contract', 'tooling-distribution') +
        @(if ($desktop) { 'external-desktop' })) {
    $scopeFindings = @($findings | Where-Object { $scope -cin $_.Scopes })
    $verdicts[$scope] = Test-AdvisoryFindings -Findings $scopeFindings -Exceptions $toolExceptions -Scope $scope `
        -Subject "the $scope scope" -ExceptionsLabel 'scripts/dependency-advisory-exceptions.txt' `
        -DesktopSha256 $(if ($desktop) { $desktop.sha256 })
}
$valid = @($verdicts.Values | Where-Object { -not $_.Valid }).Count -eq 0
$reason = @($verdicts.Values.Reason | Where-Object { $_ }) -join ' '
# Querying can take long enough for another producer to change a file. A report cannot certify
# inputs or artifacts that stopped matching while the publishers were being asked.
$after = Get-CurrentDependencyAuditSubject -Root $Root -Graphs $graphs -Sbom $sbom -BundlePath $BundlePath -DesktopJar $DesktopJar
if ($after.graphReport.graphsSha256 -cne $subject.graphReport.graphsSha256) {
    throw 'An audited artifact changed while OSV was being asked. No advisory result is certified.'
}
if (-not $OutputPath) { $OutputPath = Join-Path $Root 'build/reports/dependencies/advisories.json' }
$report = [ordered]@{ schemaVersion = 2; checkedAt = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'); subject = $subject
    hostMeaning = 'Locally resolved patcher contract only. Not proof of installed Manager/Desktop versions or exploit reachability.'
    desktopMeaning = 'The external-desktop scope is the exact JAR its SHA-256 names: embedded metadata, then whether reviewed advisory classes are present. Neither proves reachability.'
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
if ($desktop) { Write-Host "[dependencies] Desktop JAR $($desktop.configuration) sha256 $($desktop.sha256)" }
Write-Host '[dependencies] Host contracts are not an installed-host audit; findings do not prove exploit reachability.'
if (-not $valid) { throw $reason }
