<#
.SYNOPSIS
    Hold the Instagram source ledger and its audit to their rules, with no network.

.DESCRIPTION
    The checked-in ledger has to keep every rule of scripts/instagram-sources.ps1, and
    docs/sources.md can't link a source the ledger doesn't know or call a behavior-only source
    portable. Then each rule is broken once on a copy, and each has to be refused by name: an
    adopted source missing its pinned commit, compatible licence, NOTICE entry, provenance rule or
    evidence from every declared fixture, an unlicensed or contaminated source that isn't
    behavior-only, a source for an app the census doesn't cover, a provenance rule crediting a
    source the ledger doesn't allow, an index with no listing record. The census is held to its 14
    days on both sides of the line.

    scripts/audit-instagram-sources.ps1 then runs against a stand-in for every forge and index it
    reads, so nothing here touches the network: a request the stand-in doesn't know fails the case.
    A clean run stamps only dates and collapses a byte-for-byte copy into its lineage; an addition
    from an index or code search, a removed licence, a moved branch head, a new branch with
    unpinned work, an unseen fork, a repository that's gone and a changed listing each exit 1 with
    the ledger untouched. Through all of it the audit writes nothing but its report and the
    ledger's dates, and a parse of its source finds nothing that clones, downloads to disk or
    copies files, with a copy that does each of those as the proof the parse can see them.

    The pre-push hook runs this for the audit, its rules, the ledger, provenance.json and NOTICE.

.NOTES
    Taken from Hushfacebook's scripts/test-facebook-sources.ps1
    (https://github.com/SysAdminDoc/Hushfacebook, commit 3a47363954eea853357e71e7cf3951a5ee984cee).
    GPL-3.0-only. Modified for HushGram (Instagram), 2026.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'instagram-sources.ps1')

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

function Copy-Json {
    param($Value)
    return ($Value | ConvertTo-Json -Depth 20 | ConvertFrom-Json)
}

function Get-Sha256Text {
    param([string]$Text)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        return -join ($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)) | ForEach-Object { $_.ToString('x2') })
    } finally { $hasher.Dispose() }
}

$caseRoot = Join-Path ([IO.Path]::GetTempPath()) ("hushgram-sources-" +[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $caseRoot -Force | Out-Null
$savedGitLabToken = $env:GITLAB_TOKEN
# A maintainer's own token never reaches the stand-ins; the cases that need one set their own.
$env:GITLAB_TOKEN = $null
try {

# --- the checked-in ledger ------------------------------------------------------------------------

$ledgerPath = Get-SourceLedgerPath -Root $Root
$ledger = Read-SourceLedger -Path $ledgerPath
$checkedIn = Test-SourceLedger -Ledger $ledger -Root $Root
Assert-True $checkedIn.Valid ("The checked-in ledger breaks its rules: " + ($checkedIn.Problems -join ' | '))
$entries = @($ledger.entries)
Assert-True (@($entries | Where-Object { $_.disposition -eq 'adopted' }).Count -ge 1) `
    'The checked-in ledger adopts nothing, so the adopted gates below would test an empty set.'
foreach ($package in Get-SourcePackages) {
    Assert-True (@($entries | Where-Object { @($_.packages) -contains $package }).Count -gt 0) "The checked-in ledger has no $package source."
}

# docs/sources.md is the readable version of the ledger. A source it links has to be one the
# ledger knows, and a behavior-only source can never be described as portable: Hushfacebook's
# version of this page once said a contaminated module's code could be ported with credit.
$known = @{}
foreach ($entry in $entries) {
    $known[(ConvertTo-SourceKey $entry.repository)] = $entry
    foreach ($mirror in @($entry.mirrors | Where-Object { $_ })) { $known[(ConvertTo-SourceKey $mirror.repository)] = $entry }
    if ($entry.upstream) { $known[(ConvertTo-SourceKey $entry.upstream)] = $entry }
}
foreach ($item in @($ledger.outOfScope | Where-Object { $_ })) { $known[(ConvertTo-SourceKey $item.repository)] = $item }
$known[(ConvertTo-SourceKey $ledger.self.repository)] = $ledger.self
$known[(ConvertTo-SourceKey $ledger.officialBundle.repository)] = $ledger.officialBundle
foreach ($index in @($ledger.indexes)) {
    foreach ($address in @($index.url, $index.hushgram.url)) {
        $indexKey = ConvertTo-SourceKey $address
        if ($indexKey) { $known[$indexKey] = $index }
    }
}
function Test-SourcesDoc {
    param([string]$Text)
    $problems = New-Object System.Collections.Generic.List[string]
    foreach ($match in [regex]::Matches($Text, 'https://(?:github\.com|gitlab\.com)/[A-Za-z0-9_.\-/]+')) {
        $key = ConvertTo-SourceKey ($match.Value.TrimEnd('.', ')'))
        if ($key -and -not $known.ContainsKey($key)) { $problems.Add("docs/sources.md links $key, which the ledger doesn't know.") }
    }
    $portable = '(?i)\b(can be ported|ported with credit|port(ed)? it with credit|can be copied|copy its code)\b'
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -notmatch $portable) { continue }
        foreach ($entry in $entries | Where-Object { $_.disposition -eq 'behavior-only' }) {
            if ($line.IndexOf([string]$entry.repository, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $problems.Add("docs/sources.md calls $($entry.repository) portable, and the ledger says behavior-only: $line")
            }
        }
    }
    return $problems.ToArray()
}
$sourcesDoc = [IO.File]::ReadAllText((Join-Path $Root 'docs/sources.md'))
$docProblems = @(Test-SourcesDoc $sourcesDoc)
Assert-True ($docProblems.Count -eq 0) ($docProblems -join ' | ')
# Every behavior-only source, not one picked by name: any of them called portable is refused.
$behaviorOnlyEntries = @($entries | Where-Object { $_.disposition -eq 'behavior-only' })
Assert-True ($behaviorOnlyEntries.Count -gt 0) 'The checked-in ledger has no behavior-only source, so the portability check would test nothing.'
foreach ($entry in $behaviorOnlyEntries) {
    $portableLine = "- [$($entry.id)]($($entry.repository)) has what we need. Its code can be ported with credit."
    Assert-True (@(@(Test-SourcesDoc ($sourcesDoc + "`n" + $portableLine)) -like '*calls*portable*').Count -gt 0) `
        "A docs line calling $($entry.repository) portable passed the check."
}
Assert-True (@(@(Test-SourcesDoc ($sourcesDoc + "`nSee https://github.com/unknown-owner/new-instagram-patches.")) -like '*unknown-owner*').Count -gt 0) `
    'A docs link to a source the ledger doesn''t know passed the check.'

Write-Host '[sources] the checked-in ledger and docs/sources.md keep their rules'

# --- each rule, broken once -------------------------------------------------------------------------

$rulesRoot = Join-Path $caseRoot 'rules'
New-Item -ItemType Directory -Path $rulesRoot -Force | Out-Null
foreach ($name in @('NOTICE', 'provenance.json', 'patches-list.json')) {
    Copy-Item -LiteralPath (Join-Path $Root $name) -Destination (Join-Path $rulesRoot $name)
}
$today = [datetime]::UtcNow.Date.ToString('yyyy-MM-dd')
function Test-Broken {
    param([scriptblock]$Break, [string]$Pattern, [string]$Message, [scriptblock]$BreakRoot)
    $copy = Copy-Json $ledger
    & $Break $copy
    $savedNotice = [IO.File]::ReadAllText((Join-Path $rulesRoot 'NOTICE'))
    $savedProvenance = [IO.File]::ReadAllText((Join-Path $rulesRoot 'provenance.json'))
    try {
        if ($BreakRoot) { & $BreakRoot }
        $result = Test-SourceLedger -Ledger $copy -Root $rulesRoot -Today $today
    } finally {
        [IO.File]::WriteAllText((Join-Path $rulesRoot 'NOTICE'), $savedNotice)
        [IO.File]::WriteAllText((Join-Path $rulesRoot 'provenance.json'), $savedProvenance)
    }
    Assert-True (-not $result.Valid) "$Message The ledger was accepted."
    Assert-True (@($result.Problems | Where-Object { $_ -like $Pattern }).Count -gt 0) `
        "$Message It was refused, but not for that: $($result.Problems -join ' | ')"
}
function Get-Entry { param($Copy, [string]$Id) return @($Copy.entries | Where-Object { $_.id -eq $Id })[0] }
$adoptedId = [string]@($entries | Where-Object { $_.disposition -eq 'adopted' })[0].id
$adoptedRepository = [string](Get-Entry $ledger $adoptedId).repository

$control = Test-SourceLedger -Ledger (Copy-Json $ledger) -Root $rulesRoot -Today $today
Assert-True $control.Valid ("The unchanged copy was refused, so the cases below would prove nothing: " + ($control.Problems -join ' | '))

# The five things an adopted source needs.
Test-Broken { param($c) (Get-Entry $c $adoptedId).adopted.commit = $null } '*no pinned commit its code was taken from*' `
    'An adopted source with no pinned commit'
Test-Broken { param($c) (Get-Entry $c $adoptedId).license.spdx = 'NOASSERTION' } '*has no licence compatible with GPL-3.0*' `
    'An adopted source with no compatible licence'
Test-Broken { param($c) } '*is not in NOTICE*' 'An adopted source missing from NOTICE' {
    $path = Join-Path $rulesRoot 'NOTICE'
    # NOTICE is matched without case, so it's replaced without case too.
    [IO.File]::WriteAllText($path, [regex]::Replace([IO.File]::ReadAllText($path), [regex]::Escape($adoptedRepository), 'https://example.invalid/elsewhere', 'IgnoreCase'))
}
Test-Broken { param($c) (Get-Entry $c $adoptedId).adopted.commit = ('f' * 40) } '*has no provenance rule*' `
    'An adopted source whose commit no provenance rule names'
Test-Broken { param($c) (Get-Entry $c $adoptedId).adopted.fixtures.builds = @() } '*lacks fixture evidence*' `
    'An adopted source proved on no fixture'
Test-Broken { param($c) (Get-Entry $c $adoptedId).adopted.fixtures.builds = @('1.0.0.0.0') } '*lacks fixture evidence*is missing*' `
    'An adopted source proved on a build the catalog doesn''t declare'

# Unlicensed or contaminated sources stay behavior-only.
$unlicensed = [string]@($entries | Where-Object { $null -eq $_.license })[0].id
Test-Broken { param($c) (Get-Entry $c $unlicensed).disposition = 'candidate' } '*has no licence, so it is behavior-only*' `
    'An unlicensed source marked candidate'
$licensedCandidate = [string]@($entries | Where-Object { $_.disposition -eq 'candidate' -and $null -ne $_.license })[0].id
# No Instagram source is contaminated today, so a licensed candidate is given the record one would carry.
Test-Broken { param($c) (Get-Entry $c $licensedCandidate).contaminatedBy = [pscustomobject]@{
        source = 'https://github.com/unlicensed-owner/instagram-module'; evidence = 'https://github.com/unlicensed-owner/instagram-module/blob/main/Hook.java' } } `
    '*carries code from*behavior-only*' 'A contaminated source marked candidate'
Test-Broken { param($c) (Get-Entry $c $licensedCandidate).contaminatedBy = [pscustomobject]@{ source = 'not a repository'; evidence = 'http://example.com' } } `
    '*contaminatedBy names no source repository*' 'A contamination record with no source'
Test-Broken { param($c) (Get-Entry $c $licensedCandidate).license.spdx = 'CC-BY-NC-4.0' } '*can''t be combined with GPL-3.0*' `
    'A candidate whose licence can''t be combined with GPL-3.0'
Test-Broken { param($c) (Get-Entry $c $unlicensed).disposition = 'adopted' } '*has no licence*' 'An unlicensed source marked adopted'

# A relicensed source keeps its earlier licence pinned, and code it took from another lineage says so.
$relicensed = @($entries | Where-Object { @($_.licenseHistory | Where-Object { $_ }).Count -gt 0 })
Assert-True ($relicensed.Count -ge 1) 'No ledger source records an earlier licence, so the cases below would prove nothing.'
$relicensedId = [string]$relicensed[0].id
Test-Broken { param($c) (Get-Entry $c $relicensedId).licenseHistory[0].commit = 'main' } '*earlier licence*pinned to no commit*' `
    'An earlier licence pinned to a branch name'
Test-Broken { param($c) (Get-Entry $c $relicensedId).licenseHistory[0].sha256 = $null } '*earlier licence*no sha256*' 'An earlier licence with no hash'
Test-Broken { param($c) (Get-Entry $c $relicensedId).licenseHistory[0].url = 'http://example.com/LICENSE' } '*earlier licence*no https url*' `
    'An earlier licence with no https url'
Test-Broken { param($c) (Get-Entry $c $relicensedId).licenseHistory[0].spdx = '' } '*earlier licence with no SPDX*' 'An earlier licence with no SPDX identifier'
Test-Broken { param($c) (Get-Entry $c $relicensedId).licenseHistory[0].until = 'October' } '*earlier licence*until is not a yyyy-MM-dd date*' `
    'An earlier licence with no end date'
Test-Broken { param($c) (Get-Entry $c $relicensedId).licenseHistory[0].until = '2999-01-01' } '*earlier licence*until is 2999-01-01, after today*' `
    'An earlier licence that ends in the future'
$inheriting = @($entries | Where-Object { @($_.inherits | Where-Object { $_ }).Count -gt 0 })
Assert-True ($inheriting.Count -ge 1) 'No ledger source records inherited code, so the cases below would prove nothing.'
$inheritingId = [string]$inheriting[0].id
Test-Broken { param($c) (Get-Entry $c $inheritingId).inherits[0].source = 'not a repository' } '*inherits code from no source repository*' `
    'Inherited code with no source'
Test-Broken { param($c) (Get-Entry $c $inheritingId).inherits[0].lineage = ' ' } '*inherits code from*no lineage named*' 'Inherited code with no lineage'
Test-Broken { param($c) (Get-Entry $c $inheritingId).inherits[0].evidence = 'http://example.com' } '*inherits code from*no https evidence*' `
    'Inherited code with no evidence'
Test-Broken { param($c) (Get-Entry $c $inheritingId).inherits[0].paths = @() } '*inherits code from*without saying where*' 'Inherited code with no path'
Test-Broken { param($c) (Get-Entry $c $inheritingId).inherits[0].paths = @('../outside') } '*inherited path*inside the repository*' `
    'Inherited code outside the repository'

# provenance.json can't credit what the ledger doesn't allow, and can't port from what isn't adopted.
$behaviorOnly = [string]@($entries | Where-Object { $_.disposition -eq 'behavior-only' })[0].repository
Test-Broken { param($c) } '*credits*but the ledger lists it as behavior-only*' 'A provenance rule crediting a behavior-only source' {
    $path = Join-Path $rulesRoot 'provenance.json'
    $document = [IO.File]::ReadAllText($path) | ConvertFrom-Json
    $document.rules[0].via = @(@($document.rules[0].via) + $behaviorOnly)
    [IO.File]::WriteAllText($path, ($document | ConvertTo-Json -Depth 10))
}
Test-Broken { param($c) $e = Get-Entry $c $adoptedId; $e.disposition = 'candidate'; $e.PSObject.Properties.Remove('adopted') } '*ports files from*not adopted*' `
    'A ported provenance rule from a source that isn''t adopted'
# A recorded fork or mirror carries its entry's code, so it's held to that entry's disposition and
# is never a porting source of its own.
$forkedEntry = @($entries | Where-Object { $_.disposition -eq 'behavior-only' -and @($_.forks | Where-Object { $_ }).Count -gt 0 })[0]
$behaviorOnlyFork = 'https://' + (ConvertTo-SourceKey $forkedEntry.repository).Split('/')[0] + '/' + @($forkedEntry.forks)[0]
$recordedMirror = [string]@(@($entries | Where-Object { @($_.mirrors | Where-Object { $_ }).Count -gt 0 })[0].mirrors)[0].repository
function Set-FirstRule {
    param([string]$Property, [string]$Value)
    $path = Join-Path $rulesRoot 'provenance.json'
    $document = [IO.File]::ReadAllText($path) | ConvertFrom-Json
    $document.rules[0].$Property = if ($Property -eq 'via') { @(@($document.rules[0].via) + $Value) } else { $Value }
    [IO.File]::WriteAllText($path, ($document | ConvertTo-Json -Depth 10))
}
Test-Broken { param($c) } '*credits*as a fork of*behavior-only*' 'A provenance rule crediting a fork of a behavior-only source' {
    Set-FirstRule 'via' $behaviorOnlyFork }
Test-Broken { param($c) } '*ports files from*only as a fork of*' 'A ported provenance rule from a fork of a behavior-only source' {
    Set-FirstRule 'upstream' $behaviorOnlyFork }
Test-Broken { param($c) } '*ports files from*only as a mirror of*' 'A ported provenance rule from a recorded mirror' {
    Set-FirstRule 'upstream' $recordedMirror }

# The records themselves.
Test-Broken { param($c) $c.indexes[0].hushgram.status = '' } '*records no HushGram listing*' 'An index with no listing record'
Test-Broken { param($c) $c.indexes[0].hushgram = [pscustomobject]@{ status = 'listed'; checked = $today } } `
    '*listed but records no https listing url*' 'A listing with no URL'
Test-Broken { param($c) $c.indexes[0].hushgram = [pscustomobject]@{ status = 'submitted'; url = 'https://example.com/issue/1' } } `
    '*submission date is not a yyyy-MM-dd date*' 'A submission with no date'
Test-Broken { param($c) $c.indexes = @($c.indexes | Select-Object -Skip 1) } '*has no record of index*' 'A ledger missing an index'
Test-Broken { param($c) $c.entries[1].repository = $c.entries[0].repository } '*which Entry*already names*' 'A repository listed twice'
Test-Broken { param($c) $c.entries[0].branches[0].commit = 'main' } '*has no pinned commit (40 hex characters)*' 'A branch with no pinned commit'
Test-Broken { param($c) $c.entries[0].lastChecked = '25/09/2026' } '*lastChecked is not a yyyy-MM-dd date*' 'A lastChecked that isn''t ISO'
Test-Broken { param($c) $c.entries[0].lastChecked = '2999-01-01' } '*lastChecked is 2999-01-01, after today*' 'A lastChecked in the future'
Test-Broken { param($c) $c.entries[0].features = @() } '*names no features*' 'A source with no features'
Test-Broken { param($c) $c.entries[0].targetVersions = [pscustomobject]@{} } '*records no declared target versions*' 'A source with no target versions'
Test-Broken { param($c) $e = Get-Entry $c $licensedCandidate; $e.packages = @('com.zhiliaoapp.musically')
    $e.targetVersions = [pscustomobject]@{ 'com.zhiliaoapp.musically' = @() } } '*not Instagram or another Meta app*' 'A source for another app'
# A Facebook source may stand in the ledger only because HushGram ships its code.
Test-Broken { param($c) $e = Get-Entry $c $licensedCandidate; $e.packages = @('com.facebook.katana')
    $e.targetVersions = [pscustomobject]@{ 'com.facebook.katana' = @() } } '*census doesn''t cover*Only an adopted source*' `
    'A candidate for a Meta app the census doesn''t cover'
Assert-True (@($entries | Where-Object { $_.disposition -eq 'adopted' -and @($_.packages) -contains 'com.facebook.katana' }).Count -gt 0) `
    'No adopted source names a Facebook package, so the case above proves nothing about the adopted exception.'
Test-Broken { param($c) $c.entries[0].license = [pscustomobject]@{ spdx = 'GPL-3.0'; url = 'https://example.com/LICENSE'; sha256 = 'abc' } } `
    '*licence has no sha256*' 'A licence with no hash'
Test-Broken { param($c) $c.entries[0].lineage = '' } '*names no lineage*' 'A source with no lineage'

Write-Host '[sources] every ledger rule refuses the copy that breaks it'

# --- the census --------------------------------------------------------------------------------------

$census = Copy-Json $ledger
$census.census.checkedAt = '2026-09-25'
Assert-True (Test-SourceCensus -Ledger $census -Today '2026-10-09').Valid 'A census 14 days old was refused.'
$stale = Test-SourceCensus -Ledger $census -Today '2026-10-10'
Assert-True (-not $stale.Valid -and $stale.Reason -like '*15 days old*audit-instagram-sources.ps1*') "A census 15 days old was accepted, or not sent to the audit: $($stale.Reason)"
Assert-True (-not (Test-SourceCensus -Ledger $census -Today '2026-09-24').Valid) 'A census dated after today was accepted.'
$census.census.checkedAt = 'yesterday'
Assert-True (-not (Test-SourceCensus -Ledger $census -Today '2026-10-01').Valid) 'A census with no date was accepted.'
Assert-Throws { Read-SourceLedger -Path (Join-Path $caseRoot 'missing/instagram-sources.json') } '*ledger is missing*' 'A missing ledger was read.'

Write-Host '[sources] the census holds to 14 days'

# --- the audit, against stand-ins for every forge and index -----------------------------------------

$auditScript = Join-Path $PSScriptRoot 'audit-instagram-sources.ps1'
$fixtureRoot = Join-Path $caseRoot 'audit'
New-Item -ItemType Directory -Path (Join-Path $fixtureRoot 'sources'), (Join-Path $fixtureRoot 'patches'), (Join-Path $fixtureRoot 'extensions') -Force | Out-Null
# The fixture's alpha source is adopted, so its root carries the NOTICE entry and the provenance
# rule an adopted source needs, and the catalog whose declared builds its fixture evidence has to name.
Copy-Item -LiteralPath (Join-Path $Root 'patches-list.json') -Destination (Join-Path $fixtureRoot 'patches-list.json')
$adoptedCommit = 'ad' * 20
$declaredBuilds = @((Get-PatchTarget -PatchList ([IO.File]::ReadAllText((Join-Path $Root 'patches-list.json')) | ConvertFrom-Json)).PackageVersions)
Assert-True ($declaredBuilds.Count -ge 1) 'The catalog declares no build, so the fixture evidence cases would prove nothing.'
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'NOTICE'), "Fixture NOTICE`n  alpha  https://github.com/fixture-owner/alpha-patches`n")
[IO.File]::WriteAllText((Join-Path $fixtureRoot 'provenance.json'), (@{ rules = @(@{ paths = @('patches/**'); origin = 'ported'
    upstream = 'https://github.com/fixture-owner/alpha-patches'; commit = $adoptedCommit; license = 'GPL-3.0'; via = @() }) } | ConvertTo-Json -Depth 6))
Set-Content -LiteralPath (Join-Path $fixtureRoot 'patches/Keep.kt') -Value 'class Keep' -Encoding ASCII
$fixtureLedgerPath = Get-SourceLedgerPath -Root $fixtureRoot
$fakeToken = 'fixture-token-' + [guid]::NewGuid().ToString('N')
$licenseText = "GNU GENERAL PUBLIC LICENSE`nVersion 3, 29 June 2007`n"
$licenseHash = Get-Sha256Text $licenseText
$licenseBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($licenseText))
$commitA1 = 'a1' * 20
$commitB1 = 'b1' * 20
$commitG1 = 'c1' * 20
$blobKnown = '1b' * 20
$blobMirror = '2b' * 20
$fixtureLedger = [ordered]@{
    schemaVersion = 1
    census = [ordered]@{ checkedAt = '2026-09-01'; skipped = @('gitlab-code-search') }
    self = [ordered]@{ repository = 'https://github.com/SysAdminDoc/HushGram' }
    officialBundle = [ordered]@{ repository = 'https://github.com/MorpheApp/morphe-patches'; branch = 'main'; list = 'patches-list.json'; packages = @() }
    indexes = @(
        [ordered]@{ id = 'morphe-directory'; name = 'Morphe community directory'; url = 'https://morphe-patches.software'
            hushgram = [ordered]@{ status = 'not-listed'; checked = '2026-09-01' } }
        [ordered]@{ id = 'awesome-morphe'; name = 'Awesome Morphe'; url = 'https://github.com/nvbangg/awesome-morphe'
            hushgram = [ordered]@{ status = 'submitted'; url = 'https://github.com/nvbangg/awesome-morphe/issues/1'; submitted = '2026-09-01' } }
        [ordered]@{ id = 'morphe-patch-tracker'; name = 'Morphe Patch Tracker'; url = 'https://drnx64.github.io/morphe-track-patches/'
            hushgram = [ordered]@{ status = 'listed'; url = 'https://drnx64.github.io/morphe-track-patches/#/bundle/sysadmindoc'; checked = '2026-09-01' } }
        [ordered]@{ id = 'jman-bundles'; name = 'Jman''s Patch Bundle Registry'; url = 'https://github.com/Jman-Github/Patch-Bundle-Registry'
            hushgram = [ordered]@{ status = 'not-listed'; checked = '2026-09-01' } }
        [ordered]@{ id = 'morphe-archive'; name = 'Morphe Archive'; url = 'https://rushiforai.github.io/morphe-archive/'
            hushgram = [ordered]@{ status = 'listed'; url = 'https://github.com/rushiforai/morphe-archive'; checked = '2026-09-01' } }
    )
    entries = @(
        [ordered]@{ id = 'alpha'; repository = 'https://github.com/fixture-owner/alpha-patches'; lineage = 'alpha'; upstream = $null
            kind = 'morphe-patches'; packages = @('com.instagram.android'); targetVersions = [ordered]@{ 'com.instagram.android' = @('449.0.0.52.84') }
            features = @('Hide ads'); branches = @([ordered]@{ name = 'main'; commit = $commitA1 }); watchPaths = @()
            license = [ordered]@{ spdx = 'GPL-3.0'; url = "https://github.com/fixture-owner/alpha-patches/blob/$commitA1/LICENSE"; sha256 = $licenseHash }
            contaminatedBy = $null; disposition = 'adopted'; reason = 'A fixture source.'; archived = $false
            adopted = [ordered]@{ commit = $adoptedCommit; fixtures = [ordered]@{ builds = $declaredBuilds } }
            forks = @('someone/alpha-patches'); contentHashes = @($blobKnown)
            mirrors = @([ordered]@{ repository = 'https://github.com/copier/alpha-copy'; reason = 'A fixture copy.'; blobs = @($blobMirror) })
            lastChecked = '2026-09-01' }
        [ordered]@{ id = 'beta'; repository = 'https://github.com/fixture-owner/beta-module'; lineage = 'beta'; upstream = $null
            kind = 'xposed-module'; packages = @('com.instagram.android'); targetVersions = [ordered]@{ 'com.instagram.android' = @() }
            features = @('Hide DM inbox ads'); branches = @([ordered]@{ name = 'main'; commit = $commitB1 }); watchPaths = @('app/instagram')
            license = $null; contaminatedBy = $null; disposition = 'behavior-only'; reason = 'A fixture module with no licence.'; archived = $false
            forks = @(); contentHashes = @(); mirrors = @(); lastChecked = '2026-09-01' }
        [ordered]@{ id = 'gamma'; repository = 'https://gitlab.com/fixture-group/gamma-patches'; lineage = 'gamma'; upstream = $null
            kind = 'revanced-patches'; packages = @('com.instagram.android'); targetVersions = [ordered]@{ 'com.instagram.android' = @() }
            features = @('Open links outside'); branches = @([ordered]@{ name = 'main'; commit = $commitG1 }); watchPaths = @()
            license = [ordered]@{ spdx = 'GPL-3.0'; url = "https://gitlab.com/fixture-group/gamma-patches/-/blob/$commitG1/LICENSE"; sha256 = $licenseHash }
            licenseHistory = @([ordered]@{ spdx = 'MIT'; url = "https://gitlab.com/fixture-group/gamma-patches/-/blob/$('c0' * 20)/LICENSE"
                sha256 = ('ab' * 32); commit = ('c0' * 20); until = '2026-08-01' })
            inherits = @([ordered]@{ source = 'https://github.com/SysAdminDoc/HushGram'; lineage = 'hushgram'
                evidence = "https://gitlab.com/fixture-group/gamma-patches/-/blob/$commitG1/third-party/HushGram/NOTICE.md"; paths = @('third-party/HushGram') })
            contaminatedBy = $null; disposition = 'candidate'; reason = 'A fixture GitLab source.'; archived = $false
            forks = @(); contentHashes = @(); mirrors = @(); lastChecked = '2026-09-01' }
    )
    outOfScope = @([ordered]@{ repository = 'https://github.com/noise/mentions-instagram'; reason = 'Names the package in a list.'; lastChecked = '2026-09-01' })
}
$fixtureLedgerText = ($fixtureLedger | ConvertTo-Json -Depth 20) -replace "`r`n", "`n"
function Reset-FixtureLedger { [IO.File]::WriteAllText($fixtureLedgerPath, $fixtureLedgerText, (New-Object Text.UTF8Encoding $false)) }
Reset-FixtureLedger
Assert-True (Test-SourceLedger -Ledger ($fixtureLedgerText | ConvertFrom-Json) -Root $fixtureRoot -Today '2026-09-25').Valid `
    'The audit fixture ledger breaks the rules, so the audit would stop before any case below.'

# What each forge and index answers. A case changes one answer and puts it back.
function New-FakeAnswers {
    $tick = [string][char]96 * 3
    $answers = [ordered]@{
        official = @{ Status = 200; Content = '{"patches":[{"name":"Example","compatiblePackages":{"com.google.android.youtube":["20.1"]}}]}' }
        directory = @{ Status = 200; Content = (@{
            bundles = @(@{ source = 'github'; repo = 'fixture-owner/alpha-patches'; name = 'Alpha'; targetApps = @('com.instagram.android')
                patches = @(@{ name = 'Hide ads'; compatiblePackagesKey = 0 }) })
            compatibilities = @(@{ packageName = 'com.instagram.android'; targets = @(@{ version = '449.0.0.52.84' }) }) } | ConvertTo-Json -Depth 8) }
        awesome = @{ Status = 200; Content = '{"fixture-owner/alpha-patches":{"com.instagram.android":"0123"},"SysAdminDoc/hushfeed":{"com.zhiliaoapp.musically":"4567"}}' }
        # The real tracker lists a bundle twice under keys that differ only in case ('immortalzeus:dev' and
        # 'ImmortalZeus:dev'), which ConvertFrom-Json alone refuses, so the fixture is written as text.
        tracker = @{ Status = 200; Content = ('{"sysadmindoc:stable":{"repo_url":"https://github.com/SysAdminDoc/HushGram","apps":[{"package":"com.instagram.android","patches":[{"name":"Hide ads"}]}]},' +
            '"alpha:stable":{"repo_url":"https://github.com/fixture-owner/alpha-patches","apps":[{"package":"com.instagram.android","patches":[{"name":"Hide ads"}]}]},' +
            '"Alpha:stable":{"repo_url":"https://github.com/fixture-owner/alpha-patches","apps":[{"package":"com.instagram.android","patches":[{"name":"Hide ads"}]}]}}') }
        # Jman's registry answers GraphQL: whether HushGram is a source, and the latest bundles that patch Instagram.
        jmanSelf = @{ Status = 200; Content = '{"data":{"source":[]}}' }
        jmanBundles = @{ Status = 200; Content = '{"data":{"bundle":[{"source":{"url":"https://github.com/fixture-owner/alpha-patches"},"patches":[{"name":"Hide ads"}]},{"source":{"url":"https://gitlab.com/fixture-group/gamma-patches"},"patches":[{"name":"Open links outside"}]},{"source":{"url":"https://git.example.test/elsewhere/patches"},"patches":[{"name":"Off forge"}]}]}}' }
        archive = @{ Status = 200; Content = '{"name":"patches-list.json"}' }
        searchHits = @(
            @{ repository = @{ full_name = 'fixture-owner/alpha-patches' }; path = 'patches/Alpha.kt'; sha = $blobKnown }
            @{ repository = @{ full_name = 'copier/alpha-copy' }; path = 'patches/Alpha.kt'; sha = $blobMirror }
            @{ repository = @{ full_name = 'clone/alpha-clone' }; path = 'src/Alpha.kt'; sha = $blobKnown }
            @{ repository = @{ full_name = 'rushiforai/morphe-archive' }; path = 'examplepatches/fixture-owner/alpha-patches/patches-list.json'; sha = ('3b' * 20) }
            @{ repository = @{ full_name = 'rushiforai/morphe-archive' }; path = 'examplepatches/Jman-Github/Patch-Bundle-Registry/patch-bundles/x/y-patches-list.json'; sha = ('4b' * 20) }
            @{ repository = @{ full_name = 'noise/mentions-instagram' }; path = 'list.txt'; sha = ('5b' * 20) }
            @{ repository = @{ full_name = 'SysAdminDoc/HushGram' }; path = 'patches-list.json'; sha = ('6b' * 20) }
            # The archive keeps a recorded fork's patch list for its entry's own package, which is no bundle of its own.
            @{ repository = @{ full_name = 'rushiforai/morphe-archive' }; path = 'examplepatches/someone/alpha-patches/patches-list.json'; sha = ('7b' * 20) }
        )
        alphaRepo = @{ Status = 200; Content = '{"full_name":"fixture-owner/alpha-patches","archived":false,"default_branch":"main"}' }
        alphaLicense = @{ Status = 200; Content = (@{ path = 'LICENSE'; content = $licenseBase64; license = @{ spdx_id = 'GPL-3.0' } } | ConvertTo-Json) }
        alphaBranches = @{ Status = 200; Content = "[{`"name`":`"main`",`"commit`":{`"sha`":`"$commitA1`"}}]" }
        alphaForks = @{ Status = 200; Content = '[{"full_name":"someone/alpha-patches"}]' }
        compare = @{}
        betaRepo = @{ Status = 200; Content = '{"full_name":"fixture-owner/beta-module","archived":false,"default_branch":"main"}' }
        betaLicense = @{ Status = 404; Content = $null }
        betaBranches = @{ Status = 200; Content = "[{`"name`":`"main`",`"commit`":{`"sha`":`"$('b9' * 20)`"}}]" }
        betaWatch = @{ Status = 200; Content = "[{`"sha`":`"$commitB1`",`"commit`":{`"committer`":{`"date`":`"2026-09-01T10:00:00Z`"}}}]" }
        betaForks = @{ Status = 200; Content = '[]' }
        gammaProject = @{ Status = 200; Content = '{"path_with_namespace":"fixture-group/gamma-patches","archived":false,"default_branch":"main"}' }
        gammaTree = @{ Status = 200; Content = '[{"type":"blob","name":"LICENSE","path":"LICENSE"},{"type":"tree","name":"patches","path":"patches"}]' }
        gammaLicense = @{ Status = 200; Content = (@{ file_name = 'LICENSE'; content = $licenseBase64 } | ConvertTo-Json) }
        gammaBranches = @{ Status = 200; Content = "[{`"name`":`"main`",`"commit`":{`"id`":`"$commitG1`"}}]" }
        gammaForks = @{ Status = 200; Content = '[]' }
        gitlabHits = @()
        gitlabProject = @{ Status = 200; Content = '{"path_with_namespace":"fixture-group/gamma-patches"}' }
        repoMeta = @{ Status = 200; Content = '{"full_name":"newcomer/ig-patches","license":{"spdx_id":"MIT"},"fork":false,"archived":false,"pushed_at":"2026-09-20T00:00:00Z","description":"Instagram patches"}' }
        oldNameMeta = @{ Status = 200; Content = '{"full_name":"fixture-owner/alpha-patches","fork":false,"archived":false}' }
    }
    return $answers
}
# Drops maps a URL pattern to how many more requests for it go unanswered, as a timeout would.
$fakeForge = @{ Answers = (New-FakeAnswers); Requests = (New-Object System.Collections.Generic.List[object]); Drops = @{} }

& {
    # Stands in for every request the audit makes, so no case needs the network. A request it
    # doesn't recognize fails the case that made it.
    function Invoke-WebRequest {
        param($Uri, $Headers, $UserAgent, [switch]$UseBasicParsing, $TimeoutSec, $Method, $OutFile, $MaximumRedirection, [switch]$PassThru)
        $url = [string]$Uri
        $authorization = if ($Headers -and $Headers.ContainsKey('Authorization')) { [string]$Headers['Authorization'] } else { $null }
        $fakeForge.Requests.Add([pscustomobject]@{ Uri = $url; Authorization = $authorization })
        foreach ($pattern in @($fakeForge.Drops.Keys)) {
            if ($url -like $pattern -and $fakeForge.Drops[$pattern] -gt 0) {
                $fakeForge.Drops[$pattern]--
                throw 'The request was canceled due to the configured HttpClient.Timeout of 120 seconds elapsing.'
            }
        }
        $a = $fakeForge.Answers
        $answer = $null
        switch -Regex ($url) {
            '^https://raw\.githubusercontent\.com/MorpheApp/morphe-patches/main/patches-list\.json$' { $answer = $a.official; break }
            '^https://morphe-patches\.software/data/bundles\.json$' { $answer = $a.directory; break }
            '^https://raw\.githubusercontent\.com/nvbangg/awesome-morphe/main/data/hashes\.json$' { $answer = $a.awesome; break }
            '^https://raw\.githubusercontent\.com/drnx64/morphe-track-patches/main/data/bundles\.json$' { $answer = $a.tracker; break }
            '^https://patch-bundles\.api\.jmancentral\.com/graphql\?query=(.+)$' {
                $graphql = [Uri]::UnescapeDataString($Matches[1])
                if ($graphql -like '{ source(*') { $answer = $a.jmanSelf }
                elseif ($graphql -like '{ bundle(*offset: 0,*') { $answer = $a.jmanBundles }
                else { $answer = @{ Status = 200; Content = '{"data":{"bundle":[]}}' } }
                break
            }
            '^https://api\.github\.com/repos/rushiforai/morphe-archive/contents/examplepatches/SysAdminDoc/HushGram/patches-list\.json$' { $answer = $a.archive; break }
            '^https://api\.github\.com/search/code\?q=([^&]+)&per_page=100&page=1$' {
                $query = [Uri]::UnescapeDataString($Matches[1])
                $items = if ($query -eq '"com.instagram.android" bytecodePatch') { @($a.searchHits) } else { @() }
                $answer = @{ Status = 200; Content = (@{ total_count = $items.Count; incomplete_results = $false; items = $items } | ConvertTo-Json -Depth 8) }
                break
            }
            '^https://api\.github\.com/repos/fixture-owner/alpha-patches$' { $answer = $a.alphaRepo; break }
            '^https://api\.github\.com/repos/fixture-owner/alpha-patches/license$' { $answer = $a.alphaLicense; break }
            '^https://api\.github\.com/repos/fixture-owner/alpha-patches/branches\?per_page=100&page=1$' { $answer = $a.alphaBranches; break }
            '^https://api\.github\.com/repos/fixture-owner/alpha-patches/forks\?per_page=100&page=1$' { $answer = $a.alphaForks; break }
            '^https://api\.github\.com/repos/fixture-owner/alpha-patches/compare/([0-9a-f]{40})\.\.\.([0-9a-f]{40})$' {
                # A compare entry is GitHub's status for that commit, or a whole answer such as a limit.
                $status = $a.compare[$Matches[2]]
                if ($status -is [hashtable]) { $answer = $status }
                elseif ($status) { $answer = @{ Status = 200; Content = "{`"status`":`"$status`"}" } }
                break
            }
            '^https://api\.github\.com/repos/fixture-owner/beta-module$' { $answer = $a.betaRepo; break }
            '^https://api\.github\.com/repos/fixture-owner/beta-module/license$' { $answer = $a.betaLicense; break }
            '^https://api\.github\.com/repos/fixture-owner/beta-module/branches\?per_page=100&page=1$' { $answer = $a.betaBranches; break }
            '^https://api\.github\.com/repos/fixture-owner/beta-module/commits\?sha=main&path=app%2Finstagram&per_page=1$' { $answer = $a.betaWatch; break }
            '^https://api\.github\.com/repos/fixture-owner/beta-module/forks\?per_page=100&page=1$' { $answer = $a.betaForks; break }
            '^https://gitlab\.com/api/v4/projects/fixture-group%2Fgamma-patches$' { $answer = $a.gammaProject; break }
            '^https://gitlab\.com/api/v4/projects/fixture-group%2Fgamma-patches/repository/tree\?ref=main&per_page=100&page=1$' { $answer = $a.gammaTree; break }
            '^https://gitlab\.com/api/v4/projects/fixture-group%2Fgamma-patches/repository/files/LICENSE\?ref=main$' { $answer = $a.gammaLicense; break }
            '^https://gitlab\.com/api/v4/projects/fixture-group%2Fgamma-patches/repository/branches\?per_page=100&page=1$' { $answer = $a.gammaBranches; break }
            '^https://gitlab\.com/api/v4/projects/fixture-group%2Fgamma-patches/forks\?per_page=100&page=1$' { $answer = $a.gammaForks; break }
            '^https://gitlab\.com/api/v4/projects/fixture-group%2Fgamma-patches/repository/compare\?from=([0-9a-f]{40})&to=([0-9a-f]{40})$' {
                # GitLab lists the commits $to has past $from; behind or identical lists none.
                $status = $a.compare[$Matches[2]]
                if ($status -is [hashtable]) { $answer = $status }
                elseif ($status) {
                    $commits = if ($status -eq 'ahead') { @(@{ id = $Matches[2] }) } else { @() }
                    $answer = @{ Status = 200; Content = (@{ commits = $commits } | ConvertTo-Json -Depth 4) }
                }
                break
            }
            '^https://gitlab\.com/api/v4/search\?scope=blobs&search=([^&]+)&per_page=100&page=1$' {
                $items = if ([Uri]::UnescapeDataString($Matches[1]) -eq '"com.instagram.android"') { @($a.gitlabHits) } else { @() }
                $answer = @{ Status = 200; Content = (ConvertTo-Json -InputObject @($items) -Depth 8) }
                break
            }
            '^https://gitlab\.com/api/v4/projects/\d+$' { $answer = $a.gitlabProject; break }
            '^https://api\.github\.com/repos/(newcomer/ig-patches|clone/alpha-clone)$' { $answer = $a.repoMeta; break }
            '^https://api\.github\.com/repos/fixture-owner/alpha-old$' { $answer = $a.oldNameMeta; break }
        }
        if ($null -eq $answer) { throw "The audit made a request no stand-in answers: $url" }
        if ($answer.Status -ne 200) {
            $failure = New-Object System.Exception "Response status code does not indicate success: $($answer.Status)."
            $response = [pscustomobject]@{ StatusCode = $answer.Status }
            # A Retry-After comes back the way HttpResponseHeaders carries one: a delta, no date.
            if ($null -ne $answer.RetryAfter) {
                Add-Member -InputObject $response -NotePropertyName Headers -NotePropertyValue ([pscustomobject]@{
                    RetryAfter = [pscustomobject]@{ Delta = [TimeSpan]::FromSeconds($answer.RetryAfter); Date = $null } })
            }
            Add-Member -InputObject $failure -NotePropertyName Response -NotePropertyValue $response
            if (-not $answer.Body) { throw $failure }
            # A body comes back as the error's details, where Invoke-WebRequest puts what the forge said.
            $record = New-Object System.Management.Automation.ErrorRecord $failure, 'WebCmdletWebResponseException', 'InvalidOperation', $null
            $record.ErrorDetails = New-Object System.Management.Automation.ErrorDetails ([string]$answer.Body)
            throw $record
        }
        return [pscustomobject]@{ StatusCode = 200; Content = [string]$answer.Content }
    }

    function Invoke-Audit {
        param([hashtable]$Extra = @{})
        $report = Join-Path $caseRoot ('report-' + [guid]::NewGuid().ToString('N') + '.json')
        $fakeForge.Requests.Clear()
        $arguments = @{ Root = $fixtureRoot; GitHubToken = $fakeToken; SkipGitLabCodeSearch = $true; SearchDelaySeconds = 0
            RateLimitWaitSeconds = 0; RetryWaitSeconds = 0; Today = '2026-09-25'; ReportPath = $report }
        foreach ($key in $Extra.Keys) { $arguments[$key] = $Extra[$key] }
        $global:LASTEXITCODE = 0
        $said = @(& $auditScript @arguments 6>&1 | ForEach-Object { "$_" }) -join "`n"
        $code = $LASTEXITCODE
        $document = if (Test-Path -LiteralPath $report) { [IO.File]::ReadAllText($report) | ConvertFrom-Json } else { $null }
        return [pscustomobject]@{ ExitCode = $code; Said = $said; Report = $document; ReportText = $(if ($document) { [IO.File]::ReadAllText($report) } else { '' }) }
    }

    function Get-TreeState {
        # Every file under the fixture root with its hash, the ledger included.
        return @(Get-ChildItem -LiteralPath $fixtureRoot -Recurse -File | Sort-Object FullName | ForEach-Object {
            $_.FullName.Substring($fixtureRoot.Length) + ' ' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash })
    }

    function Assert-Drift {
        param([string]$Kind, [string]$Detail, [string]$Case)
        $result = Invoke-Audit
        $found = @($result.Report.findings | Where-Object { $_.kind -eq $Kind -and "$($_.repository) $($_.detail)" -like $Detail })
        Assert-True ($result.ExitCode -eq 1) "$Case exited $($result.ExitCode), not 1: $($result.Said)"
        Assert-True ($found.Count -gt 0) "$Case was not reported as $Kind ($Detail): $(@($result.Report.findings | ForEach-Object { "$($_.kind): $($_.repository) $($_.detail)" }) -join ' | ')"
        Assert-True ([IO.File]::ReadAllText($fixtureLedgerPath) -ceq $fixtureLedgerText) "$Case changed the ledger although the audit found drift."
    }

    # The control: nothing moved. Only dates change in the ledger, nothing else under the root
    # changes, and the copy with the lineage's own blob counts as that lineage.
    $before = Get-TreeState
    $clean = Invoke-Audit
    Assert-True ($clean.ExitCode -eq 0) ("The audit refused a fixture where nothing moved: " +
        (@($clean.Report.findings | ForEach-Object { "$($_.kind): $($_.repository) $($_.detail)" }) -join ' | ') + "`n$($clean.Said)")
    $stamped = [IO.File]::ReadAllText($fixtureLedgerPath)
    Assert-True (($stamped -replace '\d{4}-\d{2}-\d{2}', 'DATE') -ceq ($fixtureLedgerText -replace '\d{4}-\d{2}-\d{2}', 'DATE')) `
        'The clean audit changed more of the ledger than its dates.'
    $stampedLedger = $stamped | ConvertFrom-Json
    Assert-True ($stampedLedger.census.checkedAt -eq '2026-09-25' -and @($stampedLedger.entries | Where-Object { $_.lastChecked -ne '2026-09-25' }).Count -eq 0 -and
        $stampedLedger.indexes[2].hushgram.checked -eq '2026-09-25' -and $stampedLedger.indexes[1].hushgram.submitted -eq '2026-09-01') `
        'The clean audit did not stamp the census and every checked record with today, or it moved a submission date.'
    $after = Get-TreeState
    $changed = @(Compare-Object $before $after | ForEach-Object { ($_.InputObject -split ' ')[0] } | Sort-Object -Unique)
    Assert-True ($changed.Count -eq 1 -and $changed[0] -like '*instagram-sources.json') `
        "The audit wrote more than the ledger's dates under the root: $($changed -join ', ')"
    Assert-True ($clean.Report.counts.lineages -eq 3 -and $clean.Report.counts.mirrorsCollapsed -eq 1) `
        "The clean audit did not count three lineages with one copy collapsed: $($clean.Report.counts | ConvertTo-Json -Compress)"
    Assert-True (@($clean.Report.notes | Where-Object { $_ -like '*clone/alpha-clone is a byte-for-byte copy*alpha lineage*' }).Count -eq 1) `
        "The copy of the alpha lineage's file was not collapsed into it: $($clean.Report.notes -join ' | ')"
    Assert-True (@($clean.Report.lineages | Where-Object { $_.lineage -eq 'alpha' })[0].mirrors -contains 'https://github.com/clone/alpha-clone') `
        'The collapsed copy was not listed under its lineage.'
    # Inherited code is reported as the lineage it came from, and adds no lineage of its own.
    Assert-True (@($clean.Report.notes | Where-Object { $_ -like '*gamma-patches carries code from the hushgram lineage (third-party/HushGram)*not as its own work*' }).Count -eq 1) `
        "The gamma fixture's inherited HushGram code was not reported as HushGram's: $($clean.Report.notes -join ' | ')"
    Assert-True (@($clean.Report.lineages | Where-Object { $_.lineage -eq 'hushgram' }).Count -eq 0) 'Inherited code was counted as a lineage of its own.'
    # The stamp moves the census dates, never when an earlier licence ended.
    $stampedGamma = @($stampedLedger.entries | Where-Object { $_.id -eq 'gamma' })[0]
    Assert-True (@($stampedGamma.licenseHistory).Count -eq 1 -and $stampedGamma.licenseHistory[0].until -eq '2026-08-01' -and
        $stampedGamma.licenseHistory[0].commit -eq ('c0' * 20)) 'The clean audit moved or dropped an earlier licence record.'
    $leaked = @($fakeForge.Requests | Where-Object { $_.Authorization -and $_.Uri -notlike 'https://api.github.com/*' })
    Assert-True ($leaked.Count -eq 0) "The GitHub token went to another host: $($leaked[0].Uri)"
    Assert-True (@($fakeForge.Requests | Where-Object { $_.Uri -like 'https://api.github.com/*' -and $_.Authorization -ne "Bearer $fakeToken" }).Count -eq 0) `
        'A GitHub API request went out without the token.'
    Assert-True ($clean.ReportText.IndexOf($fakeToken) -lt 0 -and $stamped.IndexOf($fakeToken) -lt 0) 'The token was written to the report or the ledger.'
    Reset-FixtureLedger

    # -SkipGitLabCodeSearch holds with GITLAB_TOKEN set, as the audit's help tells a maintainer to
    # set it: no GitLab search goes out and the census keeps recording the skip.
    $env:GITLAB_TOKEN = 'fixture-gitlab-' + [guid]::NewGuid().ToString('N')
    try { $withToken = Invoke-Audit } finally { $env:GITLAB_TOKEN = $null }
    Assert-True ($withToken.ExitCode -eq 0) "The audit refused a clean fixture once GITLAB_TOKEN was set with -SkipGitLabCodeSearch: $($withToken.Said)"
    Assert-True (@($fakeForge.Requests | Where-Object { $_.Uri -like 'https://gitlab.com/api/v4/search*' }).Count -eq 0) `
        'GitLab code search ran although -SkipGitLabCodeSearch was passed.'
    Assert-True (@(([IO.File]::ReadAllText($fixtureLedgerPath) | ConvertFrom-Json).census.skipped) -contains 'gitlab-code-search') `
        'The census stopped recording the GitLab skip because a token was set.'
    Reset-FixtureLedger

    # -ValidateOnly and an invalid ledger never reach the network.
    $validate = Invoke-Audit @{ ValidateOnly = $true }
    Assert-True ($validate.ExitCode -eq 0 -and $fakeForge.Requests.Count -eq 0 -and $validate.Said -like '*3 sources in 3 lineages*') `
        "-ValidateOnly made $($fakeForge.Requests.Count) requests or refused the fixture: $($validate.Said)"
    $broken = $fixtureLedgerText | ConvertFrom-Json
    $broken.entries[1].disposition = 'candidate'
    [IO.File]::WriteAllText($fixtureLedgerPath, ($broken | ConvertTo-Json -Depth 20))
    $invalid = Invoke-Audit
    Assert-True ($invalid.ExitCode -eq 1 -and $fakeForge.Requests.Count -eq 0 -and
        @($invalid.Report.findings | Where-Object { $_.kind -eq 'ledger-invalid' }).Count -gt 0) `
        "An invalid ledger was audited ($($fakeForge.Requests.Count) requests, exit $($invalid.ExitCode))."
    Reset-FixtureLedger

    # Each way a source can move, one at a time, each put back after.
    $fakeForge.Answers.directory.Content = $fakeForge.Answers.directory.Content.Replace('"repo":  "fixture-owner/alpha-patches"', '"repo":  "newcomer/ig-patches"').Replace(
        '"repo": "fixture-owner/alpha-patches"', '"repo": "newcomer/ig-patches"')
    Assert-True ($fakeForge.Answers.directory.Content -like '*newcomer/ig-patches*') 'The directory answer was not edited, so the addition case would prove nothing.'
    Assert-Drift 'addition' '*github.com/newcomer/ig-patches*Morphe directory*' 'A bundle only the directory knows'
    Reset-FixtureLedger
    # A limit on the newcomer's metadata is named as one in the evidence, not passed off as its answer.
    $fakeForge.Answers.repoMeta = @{ Status = 429; Content = $null }
    $unreadMeta = Invoke-Audit
    $addition = @($unreadMeta.Report.findings | Where-Object { $_.kind -eq 'addition' -and $_.repository -like '*newcomer/ig-patches' })
    Assert-True ($addition.Count -eq 1 -and $addition[0].evidence.repository.status -eq 429 -and
        $addition[0].evidence.repository.unread -like 'api.github.com was still rate limiting the audit (HTTP 429) when it read its metadata*') `
        "An addition whose metadata a limit kept unread didn't say so: $($addition | ConvertTo-Json -Depth 6 -Compress)"
    Reset-FixtureLedger
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.searchHits[2].sha = ('9b' * 20)
    Assert-Drift 'addition' '*github.com/clone/alpha-clone*GitHub code search*' 'A code search hit whose file no lineage holds'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.alphaLicense = @{ Status = 404; Content = $null }
    Assert-Drift 'license-removed' '*alpha-patches*licence*gone*' 'A source whose licence was removed'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.gammaLicense.Content = (@{ content = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('All rights reserved.')) } | ConvertTo-Json)
    Assert-Drift 'license-changed' '*gamma-patches*licence text changed*' 'A GitLab source whose licence text changed'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.alphaBranches.Content = $fakeForge.Answers.alphaBranches.Content.Replace($commitA1, ('a2' * 20))
    Assert-Drift 'changed-head' "*alpha-patches*branch main moved from $commitA1 to $('a2' * 20)*" 'A pinned branch that moved'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.betaWatch.Content = $fakeForge.Answers.betaWatch.Content.Replace($commitB1, ('b2' * 20))
    Assert-Drift 'changed-head' '*beta-module*branch main moved*' 'A watched path that moved under a branch whose head the ledger never pinned'
    $fakeForge.Answers = New-FakeAnswers

    # A branch cut from main before its watched code last moved carries nothing new; one ahead of it does.
    $fakeForge.Answers.alphaBranches.Content = "[{`"name`":`"main`",`"commit`":{`"sha`":`"$commitA1`"}},{`"name`":`"old`",`"commit`":{`"sha`":`"$('a3' * 20)`"}}]"
    $fakeForge.Answers.compare[('a3' * 20)] = 'behind'
    $contained = Invoke-Audit
    Assert-True ($contained.ExitCode -eq 0) ("A branch behind a pinned one was reported: " +
        (@($contained.Report.findings | ForEach-Object { "$($_.kind): $($_.detail)" }) -join ' | '))
    Reset-FixtureLedger
    $fakeForge.Answers.compare[('a3' * 20)] = 'ahead'
    Assert-Drift 'new-branch' '*alpha-patches*branch old carries work the ledger hasn''t pinned*' 'A branch with work of its own'
    Reset-FixtureLedger
    # A limit on the comparison says nothing about the branch, on either forge: the source goes
    # unread instead of reporting work the ledger hasn't pinned.
    $fakeForge.Answers.compare[('a3' * 20)] = @{ Status = 403; Content = $null; Body = '{"message":"You have exceeded a secondary rate limit."}' }
    $fakeForge.Answers.gammaBranches.Content = "[{`"name`":`"main`",`"commit`":{`"id`":`"$commitG1`"}},{`"name`":`"old`",`"commit`":{`"id`":`"$('c3' * 20)`"}}]"
    $fakeForge.Answers.compare[('c3' * 20)] = 'behind'
    $gitlabBehind = Invoke-Audit
    Assert-True (@($gitlabBehind.Report.findings | Where-Object { $_.repository -like '*gamma-patches*' }).Count -eq 0) `
        "A GitLab branch behind a pinned one was reported: $(@($gitlabBehind.Report.findings | ForEach-Object { "$($_.kind): $($_.detail)" }) -join ' | ')"
    Reset-FixtureLedger
    $fakeForge.Answers.compare[('c3' * 20)] = @{ Status = 429; Content = $null }
    $compareLimited = Invoke-Audit
    $compareFindings = @($compareLimited.Report.findings | ForEach-Object { "$($_.kind): $($_.repository) $($_.detail)" }) -join ' | '
    Assert-True ($compareLimited.ExitCode -eq 1 -and @($compareLimited.Report.findings | Where-Object { $_.kind -eq 'source-failed' -and
                $_.detail -like "*alpha-patches could not be read: api.github.com was still rate limiting the audit (HTTP 403) when it read the comparison of $('a3' * 20) with $commitA1*" }).Count -eq 1 -and
        @($compareLimited.Report.findings | Where-Object { $_.kind -eq 'source-failed' -and
                $_.detail -like "*gamma-patches could not be read: gitlab.com was still rate limiting the audit (HTTP 429) when it read the comparison of $('c3' * 20) with $commitG1*" }).Count -eq 1) `
        "A limited comparison didn't leave its source unread (exit $($compareLimited.ExitCode)): $compareFindings"
    Assert-True (@($compareLimited.Report.findings | Where-Object { $_.kind -eq 'new-branch' }).Count -eq 0) "A limited comparison was reported as a new branch: $compareFindings"
    Assert-True ([IO.File]::ReadAllText($fixtureLedgerPath) -ceq $fixtureLedgerText) 'An audit a limited comparison cut short changed the ledger.'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.alphaForks.Content = '[{"full_name":"someone/alpha-patches"},{"full_name":"stranger/alpha-patches"}]'
    Assert-Drift 'unseen-fork' '*alpha-patches*fork stranger/alpha-patches*' 'A fork the ledger has not seen'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.gammaProject = @{ Status = 404; Content = $null }
    Assert-Drift 'repository-gone' '*gamma-patches*HTTP 404*' 'A GitLab source that is gone'
    $fakeForge.Answers = New-FakeAnswers

    # A limit still in force after the waits leaves the source unread, never gone (GitLab's per-address
    # limit for unauthenticated reads outlasted the waits on 2026-10-30).
    $fakeForge.Answers.gammaProject = @{ Status = 429; Content = $null }
    $limitedRun = Invoke-Audit
    $limitedFindings = @($limitedRun.Report.findings | ForEach-Object { "$($_.kind): $($_.repository) $($_.detail)" }) -join ' | '
    Assert-True ($limitedRun.ExitCode -eq 1 -and @($limitedRun.Report.findings | Where-Object {
                $_.kind -eq 'source-failed' -and $_.detail -like '*gamma-patches could not be read*rate limiting*HTTP 429*' }).Count -eq 1) `
        "A GitLab source behind a rate limit was not reported unread (exit $($limitedRun.ExitCode)): $limitedFindings"
    Assert-True (@($limitedRun.Report.findings | Where-Object { $_.kind -eq 'repository-gone' }).Count -eq 0) "A rate-limited source was reported gone: $limitedFindings"
    Assert-True (@($fakeForge.Requests | Where-Object { $_.Uri -ceq 'https://gitlab.com/api/v4/projects/fixture-group%2Fgamma-patches' }).Count -eq 4) `
        'The rate-limited source was not asked four times before the audit gave up on it.'
    Assert-True ([IO.File]::ReadAllText($fixtureLedgerPath) -ceq $fixtureLedgerText) 'A rate-limited audit changed the ledger.'
    Assert-True (@($limitedRun.Report.findings | Where-Object { $_.detail -like '*audit again once it resets*' }).Count -eq 1) `
        "A limit that named no reset said when to audit again anyway: $limitedFindings"
    $fakeForge.Answers = New-FakeAnswers

    # GitLab's per-address limit runs by the hour and says so in Retry-After. A wait longer than
    # -MaxRateLimitWaitSeconds gives up on the request at once and names the reset; a short one is
    # waited for as long as the forge asked.
    # The reset is named the same way under any culture (fi-FI writes a time as 11.12), and rounded
    # up so it's never a minute early.
    $fakeForge.Answers.gammaProject = @{ Status = 429; Content = $null; RetryAfter = 3036 }
    $culture = [Globalization.CultureInfo]::CurrentCulture
    $askedFrom = [DateTime]::UtcNow
    try {
        [Globalization.CultureInfo]::CurrentCulture = [Globalization.CultureInfo]::GetCultureInfo('fi-FI')
        $hourlyRun = Invoke-Audit
    } finally { [Globalization.CultureInfo]::CurrentCulture = $culture }
    $askedUntil = [DateTime]::UtcNow
    $hourly = @($hourlyRun.Report.findings | Where-Object { $_.kind -eq 'source-failed' -and $_.detail -like '*gamma-patches could not be read*' })
    Assert-True ($hourlyRun.ExitCode -eq 1 -and $hourly.Count -eq 1 -and
        $hourly[0].detail -like '*gitlab.com was still rate limiting the audit (HTTP 429)*audit again after ????-??-?? ??:?? UTC, when it resets*') `
        "A limit resetting within the hour wasn't reported unread with its reset: $(@($hourlyRun.Report.findings | ForEach-Object { $_.detail }) -join ' | ')"
    $named = [DateTime]::ParseExact(([regex]::Match($hourly[0].detail, 'after (\d{4}-\d\d-\d\d \d\d:\d\d) UTC')).Groups[1].Value, 'yyyy-MM-dd HH:mm',
        [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]'AssumeUniversal, AdjustToUniversal')
    Assert-True ($named -ge $askedFrom.AddSeconds(3036) -and $named -le $askedUntil.AddSeconds(3036 + 60)) `
        "The reset named ($named) wasn't the minute after Retry-After's 3036 s from $askedFrom."
    Assert-True (@($fakeForge.Requests | Where-Object { $_.Uri -ceq 'https://gitlab.com/api/v4/projects/fixture-group%2Fgamma-patches' }).Count -eq 1) `
        'A Retry-After past the longest wait was waited out instead of given up on.'
    # A Retry-After decades out still reads as a limit, not as a number too big for the audit.
    $fakeForge.Answers.gammaProject = @{ Status = 429; Content = $null; RetryAfter = 1e10 }
    $farRun = Invoke-Audit
    Assert-True (@($farRun.Report.findings | Where-Object { $_.kind -eq 'source-failed' -and
                $_.detail -like '*gamma-patches could not be read: gitlab.com was still rate limiting the audit (HTTP 429)*audit again after ????-??-?? ??:?? UTC, when it resets*' }).Count -eq 1) `
        "A Retry-After past what an [int] holds wasn't reported as the limit: $(@($farRun.Report.findings | ForEach-Object { $_.detail }) -join ' | ')"
    # The audit's own wait, for a limit that names none, isn't held to the cap on what a forge asks.
    $fakeForge.Answers.gammaProject = @{ Status = 429; Content = $null }
    $ownWait = Invoke-Audit @{ RateLimitWaitSeconds = 1; MaxRateLimitWaitSeconds = 0 }
    Assert-True (@($fakeForge.Requests | Where-Object { $_.Uri -ceq 'https://gitlab.com/api/v4/projects/fixture-group%2Fgamma-patches' }).Count -eq 4 -and
        ([regex]::Matches($ownWait.Said, 'rate limited on gitlab\.com, waiting 1 s')).Count -eq 3) `
        "A limit naming no wait wasn't waited out three times past a smaller cap: $($ownWait.Said)"
    $fakeForge.Answers.gammaProject = @{ Status = 429; Content = $null; RetryAfter = 1 }
    $shortRun = Invoke-Audit
    Assert-True (@($fakeForge.Requests | Where-Object { $_.Uri -ceq 'https://gitlab.com/api/v4/projects/fixture-group%2Fgamma-patches' }).Count -eq 4 -and
        ([regex]::Matches($shortRun.Said, 'rate limited on gitlab\.com, waiting 1 s')).Count -eq 3) `
        "A short Retry-After wasn't waited for three times as asked: $($shortRun.Said)"
    $fakeForge.Answers = New-FakeAnswers

    # A limit on a watched path's commits leaves the source unread as a limit, not as an error.
    $fakeForge.Answers.betaWatch = @{ Status = 429; Content = $null }
    $watchRun = Invoke-Audit
    Assert-True ($watchRun.ExitCode -eq 1 -and @($watchRun.Report.findings | Where-Object { $_.kind -eq 'source-failed' -and
                $_.detail -like '*beta-module could not be read: api.github.com was still rate limiting the audit (HTTP 429) when it read the commits of main under app/instagram*' }).Count -eq 1) `
        "A limited watched-path read wasn't reported as the limit: $(@($watchRun.Report.findings | ForEach-Object { $_.detail }) -join ' | ')"
    $fakeForge.Answers = New-FakeAnswers

    # GitHub answers its secondary limit with a 403 that says so in the body, and that one is waited
    # out the same way. A 403 that names no limit is a repository gone private, asked once.
    $fakeForge.Answers.alphaRepo = @{ Status = 403; Content = $null
        Body = '{"message":"You have exceeded a secondary rate limit. Please wait a few minutes before you try again.","documentation_url":"https://docs.github.com/rest"}' }
    $forbiddenRun = Invoke-Audit
    $forbiddenFindings = @($forbiddenRun.Report.findings | ForEach-Object { "$($_.kind): $($_.repository) $($_.detail)" }) -join ' | '
    Assert-True ($forbiddenRun.ExitCode -eq 1 -and @($forbiddenRun.Report.findings | Where-Object {
                $_.kind -eq 'source-failed' -and $_.detail -like '*alpha-patches could not be read*rate limiting*HTTP 403*' }).Count -eq 1) `
        "A GitHub source behind a secondary limit was not reported unread (exit $($forbiddenRun.ExitCode)): $forbiddenFindings"
    Assert-True (@($forbiddenRun.Report.findings | Where-Object { $_.kind -eq 'repository-gone' }).Count -eq 0) "A source behind a 403 limit was reported gone: $forbiddenFindings"
    Assert-True (@($fakeForge.Requests | Where-Object { $_.Uri -ceq 'https://api.github.com/repos/fixture-owner/alpha-patches' }).Count -eq 4) `
        'The source behind a 403 limit was not asked four times before the audit gave up on it.'
    $fakeForge.Answers.alphaRepo = @{ Status = 403; Content = $null; Body = '{"message":"Repository access blocked"}' }
    Assert-Drift 'repository-gone' '*alpha-patches*HTTP 403*' 'A GitHub source that answers 403 without naming a limit'
    Assert-True (@($fakeForge.Requests | Where-Object { $_.Uri -ceq 'https://api.github.com/repos/fixture-owner/alpha-patches' }).Count -eq 1) `
        'A 403 that names no limit was waited out as one.'
    $fakeForge.Answers = New-FakeAnswers

    # GitLab code search looks each hit's project up. A project that's gone drops its hit; one a
    # limit kept unread fails the search instead of quietly shrinking it.
    $env:GITLAB_TOKEN = 'fixture-gitlab-' + [guid]::NewGuid().ToString('N')
    try {
        $fakeForge.Answers.gitlabHits = @(@{ project_id = 4242; path = 'patches/Gamma.kt' })
        $searched = Invoke-Audit @{ SkipGitLabCodeSearch = $false }
        Assert-True ($searched.ExitCode -eq 0 -and $searched.Said -like '*GitLab code search: 1 hits in 1 projects*') `
            "GitLab code search did not read the gamma project's hit: $($searched.Said)"
        Reset-FixtureLedger
        $fakeForge.Answers.gitlabProject = @{ Status = 404; Content = $null }
        $dropped = Invoke-Audit @{ SkipGitLabCodeSearch = $false }
        Assert-True ($dropped.ExitCode -eq 0 -and $dropped.Said -like '*GitLab code search: 0 hits in 0 projects*') `
            "A hit in a project that's gone was not dropped: $($dropped.Said)"
        Reset-FixtureLedger
        $fakeForge.Answers.gitlabProject = @{ Status = 429; Content = $null }
        $searchLimited = Invoke-Audit @{ SkipGitLabCodeSearch = $false }
        Assert-True ($searchLimited.ExitCode -eq 1 -and @($searchLimited.Report.findings | Where-Object {
                    $_.kind -eq 'source-failed' -and $_.detail -like '*GitLab code search could not be read*rate limiting*HTTP 429*project 4242*' }).Count -eq 1) `
            ("A project lookup behind a rate limit did not fail GitLab code search (exit $($searchLimited.ExitCode)): " +
                (@($searchLimited.Report.findings | ForEach-Object { "$($_.kind): $($_.detail)" }) -join ' | '))
        Assert-True (@($fakeForge.Requests | Where-Object { $_.Uri -ceq 'https://gitlab.com/api/v4/projects/4242' }).Count -eq 4) `
            'The rate-limited project lookup was not asked four times before the search gave up on it.'
        Assert-True ([IO.File]::ReadAllText($fixtureLedgerPath) -ceq $fixtureLedgerText) 'A search a limit cut short changed the ledger.'
        Reset-FixtureLedger
        # A server error is no answer about the project either, so it fails the search too.
        $fakeForge.Answers.gitlabProject = @{ Status = 502; Content = $null }
        $searchBroken = Invoke-Audit @{ SkipGitLabCodeSearch = $false }
        Assert-True ($searchBroken.ExitCode -eq 1 -and @($searchBroken.Report.findings | Where-Object {
                    $_.kind -eq 'source-failed' -and $_.detail -like '*GitLab code search could not be read: project 4242 answered HTTP 502*' }).Count -eq 1) `
            ("A project lookup that got a server error didn't fail GitLab code search (exit $($searchBroken.ExitCode)): " +
                (@($searchBroken.Report.findings | ForEach-Object { "$($_.kind): $($_.detail)" }) -join ' | '))
    } finally {
        $env:GITLAB_TOKEN = $null
        Reset-FixtureLedger
    }
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.alphaRepo.Content = $fakeForge.Answers.alphaRepo.Content.Replace('"archived":false', '"archived":true')
    Assert-Drift 'archived-changed' '*alpha-patches*archived*' 'A source that was archived'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.tracker.Content = $fakeForge.Answers.tracker.Content.Replace('SysAdminDoc/HushGram', 'SysAdminDoc/hushfeed')
    Assert-Drift 'listing-changed' '*Morphe Patch Tracker no longer lists HushGram*' 'A listing that disappeared'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.official.Content = '{"patches":[{"name":"Hide ads","compatiblePackages":[{"packageName":"com.instagram.android"}]}]}'
    Assert-Drift 'official-bundle-changed' '*official bundle now patches*com.instagram.android*' 'The official bundle taking on Instagram'
    $fakeForge.Answers = New-FakeAnswers


    $fakeForge.Answers.alphaRepo.Content = $fakeForge.Answers.alphaRepo.Content.Replace('"fixture-owner/alpha-patches"', '"fixture-owner/alpha-renamed"')
    Assert-Drift 'renamed' '*alpha-patches*now https://github.com/fixture-owner/alpha-renamed*' 'A source that was renamed'
    $fakeForge.Answers = New-FakeAnswers


    # A recorded fork or an out-of-scope repository that an index lists as a bundle of its own. The
    # control's archived copy of the fork's list, for alpha's own package, stayed quiet.
    foreach ($listed in @('someone/alpha-patches', 'noise/mentions-instagram')) {
        $fakeForge.Answers.directory.Content = $fakeForge.Answers.directory.Content.Replace('"repo":  "fixture-owner/alpha-patches"', "`"repo`":  `"$listed`"").Replace(
            '"repo": "fixture-owner/alpha-patches"', "`"repo`": `"$listed`"")
        Assert-True ($fakeForge.Answers.directory.Content -like "*$listed*") "The directory answer was not edited, so the $listed case would prove nothing."
        $expected = if ($listed -like 'someone/*') { "*github.com/$listed*recorded fork of alpha*Morphe directory*" } else { "*$listed*out of scope*Morphe directory*" }
        Assert-Drift 'listed-as-bundle' $expected "The directory listing $listed as a bundle"
        $fakeForge.Answers = New-FakeAnswers
    }
    # The census covers one package, so a source only gains one the ledger doesn't record when it's
    # an adopted source for another Meta app, the way HushGram's Facebook upstreams are. Here alpha
    # is one: every index listing it for Instagram is news, and so is the archive keeping its fork's
    # list for Instagram, since the fork's entry doesn't target it.
    $family = $fixtureLedgerText | ConvertFrom-Json
    $family.entries[0].packages = @('com.facebook.katana')
    $family.entries[0].targetVersions = [pscustomobject]@{ 'com.facebook.katana' = @('580.0.0.51.74') }
    $familyText = ($family | ConvertTo-Json -Depth 20) -replace "`r`n", "`n"
    [IO.File]::WriteAllText($fixtureLedgerPath, $familyText, (New-Object Text.UTF8Encoding $false))
    Assert-True (Test-SourceLedger -Ledger ($familyText | ConvertFrom-Json) -Root $fixtureRoot -Today '2026-09-25').Valid `
        'An adopted source for another Meta app was refused, so the packages case would stop before the network.'
    $moved = Invoke-Audit
    $movedFindings = @($moved.Report.findings | ForEach-Object { "$($_.kind): $($_.repository) $($_.detail)" })
    Assert-True ($moved.ExitCode -eq 1 -and @($movedFindings -like 'packages-changed:*alpha-patches*now targets com.instagram.android*').Count -gt 0) `
        "An adopted Facebook source that an index now lists for Instagram was not reported: $($movedFindings -join ' | ')"
    Assert-True (@($movedFindings -like 'listed-as-bundle:*someone/alpha-patches*com.instagram.android*').Count -gt 0) `
        "A recorded fork whose archived patch list takes on Instagram was not reported: $($movedFindings -join ' | ')"
    Assert-True ([IO.File]::ReadAllText($fixtureLedgerPath) -ceq $familyText) 'The packages case changed the ledger although the audit found drift.'
    Reset-FixtureLedger

    $fakeForge.Answers.searchHits[1].sha = ('8b' * 20)
    Assert-Drift 'mirror-changed' '*copier/alpha-copy*' 'A recorded copy whose file changed'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.alphaBranches.Content = "[{`"name`":`"trunk`",`"commit`":{`"sha`":`"$commitA1`"}}]"
    Assert-Drift 'branch-removed' '*alpha-patches*branch main is gone*' 'A pinned branch that was deleted'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.betaLicense = @{ Status = 200; Content = (@{ path = 'LICENSE'; content = $licenseBase64; license = @{ spdx_id = 'MIT' } } | ConvertTo-Json) }
    Assert-Drift 'license-added' '*beta-module*now has a licence (MIT*' 'An unlicensed source that gained a licence'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.jmanBundles.Content = '{"data":{"bundle":[{"source":{"url":"https://github.com/newcomer/ig-patches"},"patches":[{"name":"Hide reels"}]}]}}'
    Assert-Drift 'addition' '*newcomer/ig-patches*Jman''s bundle index*' 'A bundle only the Jman registry lists'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.jmanSelf.Content = '{"data":{"source":[{"url":"https://github.com/SysAdminDoc/HushGram"}]}}'
    Assert-Drift 'listing-changed' '*Jman''s Patch Bundle Registry lists HushGram now*' 'The Jman registry taking HushGram on'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.jmanSelf = @{ Status = 200; Content = '{"errors":[{"message":"Query exceeds catalog budget"}]}' }
    Assert-Drift 'source-failed' '*Jman''s bundle index could not be read*refused the query*' 'A Jman registry that refuses the query'
    $fakeForge.Answers = New-FakeAnswers

    # An index still spelling a source by its old name is that source, not an addition.
    $fakeForge.Answers.directory.Content = $fakeForge.Answers.directory.Content -replace '"fixture-owner/alpha-patches"', '"fixture-owner/alpha-old"'
    $oldName = Invoke-Audit
    Assert-True ($oldName.ExitCode -eq 0 -and @($oldName.Report.notes | Where-Object { $_ -like '*alpha-old is an old name of*alpha-patches*' }).Count -eq 1) `
        ("An index spelling a source by its old name was not read as that source: " +
            (@($oldName.Report.findings | ForEach-Object { "$($_.kind): $($_.repository)" }) -join ' | '))
    Reset-FixtureLedger
    $fakeForge.Answers = New-FakeAnswers

    # An old name gets the checks the current name gets: here it's the recorded fork's old name,
    # listed as a bundle of its own.
    $fakeForge.Answers.directory.Content = $fakeForge.Answers.directory.Content -replace '"fixture-owner/alpha-patches"', '"fixture-owner/alpha-old"'
    $fakeForge.Answers.oldNameMeta.Content = '{"full_name":"someone/alpha-patches","fork":true,"archived":false}'
    Assert-Drift 'listed-as-bundle' '*github.com/someone/alpha-patches*recorded fork of alpha*' 'A recorded fork an index lists by its old name'
    $fakeForge.Answers = New-FakeAnswers

    $fakeForge.Answers.awesome = @{ Status = 500; Content = $null }
    Assert-Drift 'source-failed' '*Awesome Morphe could not be read*' 'An index that could not be read'
    $fakeForge.Answers = New-FakeAnswers

    # A request that gets no answer is asked again. Two misses in a row still read the source;
    # three make it a source that couldn't be read, and the census isn't stamped.
    $gammaForksUrl = 'https://gitlab.com/api/v4/projects/fixture-group%2Fgamma-patches/forks*'
    $fakeForge.Drops = @{ $gammaForksUrl = 2 }
    $recovered = Invoke-Audit
    Assert-True ($recovered.ExitCode -eq 0 -and
        @($fakeForge.Requests | Where-Object { $_.Uri -like $gammaForksUrl }).Count -eq 3) `
        ("Two unanswered requests in a row were not asked again until the source was read: " +
            (@($recovered.Report.findings | ForEach-Object { "$($_.kind): $($_.repository) $($_.detail)" }) -join ' | '))
    Reset-FixtureLedger
    $fakeForge.Drops = @{ $gammaForksUrl = 3 }
    Assert-Drift 'source-failed' '*gamma-patches*after three tries*' 'A request that never got an answer'
    $fakeForge.Drops = @{}

    # GitLab code search needs a token, or an explicit skip the census records.
    $env:GITLAB_TOKEN = $null
    Assert-Throws { Invoke-Audit @{ SkipGitLabCodeSearch = $false } } '*GITLAB_TOKEN*' 'The audit ran without GitLab code search and without saying so.'
    Assert-Throws { Invoke-Audit @{ ReportPath = (Join-Path $fixtureRoot 'patches/report.json') } } '*under patches/*' `
        'The audit wrote its report among the patch sources.'
    Assert-True ([IO.File]::ReadAllText($fixtureLedgerPath) -ceq $fixtureLedgerText) 'A refused run changed the ledger.'
}

Write-Host '[sources] the audit reports every kind of drift, collapses copies by content and stamps only a clean census'

# --- the audit never imports code ----------------------------------------------------------------

# What the two files can do, read through the parser: no git, no archive or download tool, no
# -OutFile, nothing that copies or writes files except the audit's two WriteAllText calls, one for
# the report and one for the ledger's dates. Each forbidden shape is added to a copy, and the copy
# has to be caught, so the check can't pass by seeing nothing.
function Get-ImportProblems {
    param([string]$Text, [string]$Name)
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseInput($Text, [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) { return @("$Name does not parse: $($errors[0].Message)") }
    $problems = New-Object System.Collections.Generic.List[string]
    $forbidden = @('git', 'git.exe', 'gh', 'curl', 'curl.exe', 'wget', 'Expand-Archive', 'Start-BitsTransfer', 'Copy-Item', 'Move-Item',
        'Set-Content', 'Add-Content', 'Out-File', 'Invoke-RestMethod', 'Export-Csv')
    foreach ($command in $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.CommandAst] }, $true)) {
        $commandName = $command.GetCommandName()
        if ($commandName -and ($forbidden -contains $commandName)) {
            # The token lookup for GitHub is the one gh call allowed: it prints a token, nothing more.
            if ($commandName -eq 'gh' -and $command.Extent.Text -match '^&\s*gh\s+auth\s+token\b') { continue }
            $problems.Add("$Name runs $commandName`: $($command.Extent.Text)")
        }
        foreach ($element in $command.CommandElements) {
            if ($element -is [System.Management.Automation.Language.CommandParameterAst] -and $element.ParameterName -eq 'OutFile') {
                $problems.Add("$Name downloads to a file: $($command.Extent.Text)")
            }
        }
    }
    $writes = 0
    foreach ($member in $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.InvokeMemberExpressionAst] }, $true)) {
        $memberName = [string]$member.Member.Extent.Text
        if ($memberName -in @('WriteAllBytes', 'Copy', 'Move', 'ExtractToDirectory', 'CreateFromDirectory', 'DownloadFile', 'AppendAllText')) {
            $problems.Add("$Name calls $memberName`: $($member.Extent.Text)")
        }
        if ($memberName -eq 'WriteAllText') { $writes++ }
    }
    $allowed = if ($Name -like 'audit*') { 2 } else { 0 }
    if ($writes -gt $allowed) { $problems.Add("$Name writes $writes files with WriteAllText, and only the report and the ledger stamp may.") }
    return $problems.ToArray()
}
$auditText = [IO.File]::ReadAllText($auditScript)
$libraryText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'instagram-sources.ps1'))
foreach ($pair in @(@('audit-instagram-sources.ps1', $auditText), @('instagram-sources.ps1', $libraryText))) {
    $found = @(Get-ImportProblems -Text $pair[1] -Name $pair[0])
    Assert-True ($found.Count -eq 0) ($found -join ' | ')
}
foreach ($bad in @(
        @{ Line = '& git clone https://github.com/example/patches.git $env:TEMP'; Pattern = '*runs git*' }
        @{ Line = 'Invoke-WebRequest -Uri $uri -OutFile (Join-Path $rootPath ''patches/x.kt'')'; Pattern = '*downloads to a file*' }
        @{ Line = 'Copy-Item -LiteralPath $source -Destination $rootPath'; Pattern = '*runs Copy-Item*' }
        @{ Line = '[IO.Compression.ZipFile]::ExtractToDirectory($zip, $rootPath)'; Pattern = '*calls ExtractToDirectory*' }
        @{ Line = '[IO.File]::WriteAllText((Join-Path $rootPath ''patches/Ported.kt''), $code)'; Pattern = '*writes 3 files with WriteAllText*' }
        @{ Line = '& gh repo clone example/patches'; Pattern = '*runs gh*' })) {
    $found = @(Get-ImportProblems -Text ($auditText + "`n" + $bad.Line + "`n") -Name 'audit-instagram-sources.ps1 (copy)')
    Assert-True (@($found | Where-Object { $_ -like $bad.Pattern }).Count -gt 0) "The import check missed: $($bad.Line)"
}

Write-Host '[sources] the audit and its rules hold nothing that clones, downloads to disk or copies code'
Write-Host '[sources] source ledger contracts passed'
} finally {
    $env:GITLAB_TOKEN = $savedGitLabToken
    Remove-Item -LiteralPath $caseRoot -Recurse -Force -ErrorAction SilentlyContinue
}
