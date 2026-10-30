<#
.SYNOPSIS
    The advisory gate: what OSV knows about the libraries a release's SBOM lists, and whether the
    release may go out with them.

.DESCRIPTION
    Taken from Hushfacebook's scripts/release-advisories.ps1
    (https://github.com/SysAdminDoc/Hushfacebook, commit 814acd23d7b70d5d23abce6cb6c97767e16a051e).
    GPL-3.0-only. Modified for HushGram (Instagram), 2026: resolved dependency-scope validation
    and a publisher-reviewed Guava supplement, separate from shipped-payload certification.

    Dot-sourced by build-release-receipt.ps1 and validate-release-facts.ps1, beside
    release-receipt.ps1, whose Read-ReleaseSbom reads the SBOM these functions take.

    Gradle's dependency verification (gradle/verification-metadata.xml) says an artifact is the one
    somebody checked the hash of. It says nothing about the advisories published against that
    version since, and the libraries a bundle carries are chosen by conflict resolution several
    levels down, so nobody reads them one by one. This asks OSV (https://api.osv.dev/v1/query)
    about every library the SBOM lists, one query per package URL, and refuses a release that
    carries a high or critical advisory.

    High or critical means an applicable label says so or a CVSS 3 vector scores 7.0 or more.
    Unread, malformed or ambiguous severity is held for review even beside a lower label.
    Package-specific ratings belong only to the queried package/version. CVSS 4 is not scored
    here and needs review. Consistent moderate and low advisories are printed and let through.

    An advisory can be accepted in scripts/advisory-exceptions.txt for one package, until a date
    at most 90 days out, with the reason it doesn't apply to what the bundle does with that
    library. An exception past its date covers nothing, and one that matches no advisory any more
    fails the run, as the manifest allowlist does: an exception that outlives its advisory stops
    being a review.

    OSV has to answer. A query that fails, or an answer this can't read, stops the run rather than
    reading as no advisories. -SkipAdvisoryCheck is the way through for offline work, and it says
    so in a warning. The pre-push hook never passes it, so the index push that hands a release to
    Manager users asks OSV again.
#>

function Read-AdvisoryExceptions {
    <#
    .SYNOPSIS
        The accepted advisories, one per line: "<advisory> <group>:<name> <yyyy-MM-dd> <reason>".
    .DESCRIPTION
        The advisory is OSV's id or one of its aliases (a CVE, say). The date is the last day the
        exception holds on this computer's calendar (-Today defaults to the local date, not UTC's),
        at most 90 days after -Today, so accepting an advisory means reading it
        again at least that often. The reason is at least three words: an exception is a claim
        about how the bundle uses the library, and a claim nobody wrote down can't be checked.
        Blank lines and # comments are ignored. A line in any other shape stops the run, naming
        it, rather than being skipped: a skipped exception would fail the release for a reason
        nobody could see in the file.
        -Scoped requires a reviewed tooling scope before each line. The same advisory in another
        scope needs its own review; payload exceptions keep the original format.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [datetime]$Today = [datetime]::Today,
        [switch]$Scoped
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "The advisory exception list is missing: $Path"
    }
    $entries = New-Object System.Collections.Generic.List[object]
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $number = 0
    foreach ($line in Get-Content -LiteralPath $Path) {
        $number++
        $text = ([string]$line).Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        $scope = 'shipped'
        if ($Scoped) {
            $prefix = [regex]::Match($text, '^(?<scope>settings-plugin|project-plugin|build|test|host-contract|tooling-distribution|external-desktop)\s+(?<line>.+)$')
            if (-not $prefix.Success) { throw "Line $number of $Path has no reviewed tooling scope: $text" }
            $scope = $prefix.Groups['scope'].Value
            $text = $prefix.Groups['line'].Value
        }
        $shape = [regex]::Match($text,
            '^(?<id>[A-Za-z][A-Za-z0-9]*-[A-Za-z0-9._-]+)\s+(?<package>[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)\s+(?<until>\S+)\s+(?<reason>.*\S)$')
        if (-not $shape.Success) {
            throw ("Line $number of $Path is not ""<advisory> <group>:<name> <yyyy-MM-dd> <reason>"": $text")
        }
        $until = [datetime]::MinValue
        if (-not [datetime]::TryParseExact($shape.Groups['until'].Value, 'yyyy-MM-dd',
                [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$until)) {
            throw "Line $number of $Path gives $($shape.Groups['until'].Value) as the last day, which isn't a yyyy-MM-dd date: $text"
        }
        $advisory = $shape.Groups['id'].Value
        $package = $shape.Groups['package'].Value
        if ($until -gt $Today.AddDays(90)) {
            throw ("Line $number of $Path accepts $advisory for $package until $($until.ToString('yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)), more than " +
                "90 days out. Read the advisory again when the date comes, and give it a date within 90 days.")
        }
        $reason = $shape.Groups['reason'].Value
        # A reachability review holds for the JAR it read, so it names that JAR's bytes.
        $desktopSha256 = $null
        if ($scope -ceq 'external-desktop') {
            $bound = [regex]::Match($reason, '^jar-sha256=(?<sha>[0-9A-Fa-f]{64})\s+(?<why>.*\S)$')
            if (-not $bound.Success) {
                throw ("Line $number of $Path reviews $advisory for $package without naming the Desktop JAR it read. " +
                    "Start the reason with jar-sha256=<the JAR's SHA-256>: $text")
            }
            $desktopSha256 = $bound.Groups['sha'].Value.ToUpperInvariant()
            $reason = $bound.Groups['why'].Value
        }
        if (@($reason -split '\s+' | Where-Object { $_ -match '[A-Za-z]' }).Count -lt 3) {
            throw ("Line $number of $Path accepts $advisory for $package without saying why. Give the reason it " +
                "doesn't apply to what the bundle does with the library: $text")
        }
        if (-not $seen.Add("$scope $advisory $package")) {
            throw "Line $number of $Path accepts $advisory for $package a second time."
        }
        $entries.Add([pscustomobject]@{
            Advisory = $advisory
            Package  = $package
            Until    = $until
            Reason   = $reason
            Line     = $number
            Expired  = $until -lt $Today
            Scope    = $scope
            DesktopSha256 = $desktopSha256
        })
    }
    # No comma wrap: an empty list comes back as nothing, and every caller writes @(...).
    return $entries.ToArray()
}

function Get-Cvss3BaseScore {
    <#
    .SYNOPSIS
        The base score of a CVSS 3.0 or 3.1 vector, or $null for anything else.
    .DESCRIPTION
        The formula and the rounding the CVSS 3.1 specification gives, which 3.0 vectors score the
        same way to one decimal. Temporal and environmental metrics after the base ones are
        validated but don't change the base score. Missing, repeated or unknown metrics and
        values the specification doesn't define have no score here rather than a guessed one.
    #>
    param([string]$Vector)

    if ("$Vector" -cnotmatch '^CVSS:3\.[01]/') { return $null }
    # FIRST's vector grammar allows any order, but each metric appears at most once.
    # https://www.first.org/cvss/v3.1/specification-document#6-Vector-String
    $allowed = @{
        AV = 'NALP'; AC = 'LH'; PR = 'NLH'; UI = 'NR'; S = 'UC'; C = 'HLN'; I = 'HLN'; A = 'HLN'
        E = 'XHFPU'; RL = 'XUWTO'; RC = 'XCRU'; CR = 'XHML'; IR = 'XHML'; AR = 'XHML'
        MAV = 'XNALP'; MAC = 'XLH'; MPR = 'XNLH'; MUI = 'XNR'; MS = 'XUC'; MC = 'XNLH'; MI = 'XNLH'; MA = 'XNLH'
    }
    $metrics = @{}
    foreach ($part in @($Vector -split '/' | Select-Object -Skip 1)) {
        $pair = $part -split ':', 2
        if ($pair.Count -ne 2 -or $pair[0] -cnotmatch '^[A-Z]+\z' -or
            -not $allowed.ContainsKey($pair[0]) -or $metrics.ContainsKey($pair[0]) -or
            $pair[1] -cnotmatch "^[$($allowed[$pair[0]])]\z") { return $null }
        $metrics[$pair[0]] = $pair[1]
    }
    # A metric the vector leaves out reads as '', which neither check below lets through.
    $scopeChanged = $metrics['S'] -ceq 'C'
    if (-not $scopeChanged -and $metrics['S'] -cne 'U') { return $null }
    $weights = @{
        AV = @{ N = 0.85; A = 0.62; L = 0.55; P = 0.2 }
        AC = @{ L = 0.77; H = 0.44 }
        PR = if ($scopeChanged) { @{ N = 0.85; L = 0.68; H = 0.5 } } else { @{ N = 0.85; L = 0.62; H = 0.27 } }
        UI = @{ N = 0.85; R = 0.62 }
        C  = @{ H = 0.56; L = 0.22; N = 0.0 }
        I  = @{ H = 0.56; L = 0.22; N = 0.0 }
        A  = @{ H = 0.56; L = 0.22; N = 0.0 }
    }
    $value = @{}
    foreach ($name in $weights.Keys) {
        $letter = [string]$metrics[$name]
        # Case matters in a vector, and a hashtable's keys don't.
        if ($letter -cnotmatch '^[A-Z]\z' -or -not $weights[$name].ContainsKey($letter)) { return $null }
        $value[$name] = [double]$weights[$name][$letter]
    }

    $iss = 1 - ((1 - $value['C']) * (1 - $value['I']) * (1 - $value['A']))
    $impact = if ($scopeChanged) {
        7.52 * ($iss - 0.029) - 3.25 * [Math]::Pow($iss - 0.02, 15)
    } else {
        6.42 * $iss
    }
    if ($impact -le 0) { return 0.0 }
    $exploitability = 8.22 * $value['AV'] * $value['AC'] * $value['PR'] * $value['UI']
    $raw = if ($scopeChanged) { [Math]::Min(1.08 * ($impact + $exploitability), 10) } else {
        [Math]::Min($impact + $exploitability, 10)
    }
    # Roundup as 3.1 defines it: through an integer, so 4.000000001 doesn't become 4.1.
    $scaled = [long][Math]::Round($raw * 100000, [MidpointRounding]::AwayFromZero)
    if ($scaled % 10000 -eq 0) { return $scaled / 100000.0 }
    return ([Math]::Floor($scaled / 10000) + 1) / 10.0
}

function Get-AdvisorySeverity {
    <#
    .SYNOPSIS
        How serious an OSV advisory is: @{ Level; Serious; Why }.
    .DESCRIPTION
        Keep the highest applicable rating. Unread evidence and conflicting minor ratings need
        review, even if a label is present. OSV queried the exact Library.Purl; package-level
        severity must still be selected from that response, not borrowed from a different
        package or version. Ambiguous per-version ranges need review rather than a guessed
        ecosystem version ordering. https://ossf.github.io/osv-schema/#affectedseverity-field
    #>
    param([Parameter(Mandatory = $true)]$Advisory, $Library)

    $rank = @{ NONE = 0; LOW = 1; MODERATE = 2; HIGH = 3; CRITICAL = 4 }
    $level = $null
    $why = $null
    $unread = $null
    $levels = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $containers = @($Advisory)
    $packageRecords = @()
    $affectedRecords = @($Advisory.affected | Where-Object { $null -ne $_ })
    foreach ($affected in $affectedRecords) {
        if (-not $Library) { $unread = 'package severity has no queried library context'; break }
        $namedPackage = $affected.package.ecosystem -ceq 'Maven' -and
            $affected.package.name -ceq "$($Library.Group):$($Library.Name)"
        $purl = [regex]::Match([string]$affected.package.purl, '^pkg:maven/([^/@?#]+)/([^/@?#]+)(?:@([^/@?#]+))?$')
        $urlPackage = $purl.Success -and [Uri]::UnescapeDataString($purl.Groups[1].Value) -ceq $Library.Group -and
            [Uri]::UnescapeDataString($purl.Groups[2].Value) -ceq $Library.Name
        if (-not $namedPackage -and -not $urlPackage) { continue }
        $versionKnown = $false
        if ($affected.package.purl) {
            if (-not $namedPackage -or -not $urlPackage) {
                $unread = 'affected package names and package URL disagree'; continue
            }
            if ($purl.Groups[3].Success) {
                if ([Uri]::UnescapeDataString($purl.Groups[3].Value) -cne $Library.Version) {
                    if (@($affected.versions) -ccontains $Library.Version) {
                        $unread = 'affected package URL and versions disagree'
                    }
                    continue
                }
                $versionKnown = $true
            }
        }
        $versions = @($affected.versions | Where-Object { $null -ne $_ })
        if (@($versions | Where-Object { $_ -isnot [string] }).Count) {
            $unread = 'affected versions cannot be read'; continue
        }
        if ($versions -ccontains $Library.Version) { $versionKnown = $true }
        elseif ($versions.Count -and -not @($affected.ranges | Where-Object { $null -ne $_ }).Count) {
            if ($versionKnown) { $unread = 'affected package URL and versions disagree' }
            continue
        }
        $packageRecords += [pscustomobject]@{ Affected = $affected; VersionKnown = $versionKnown }
    }
    if ($affectedRecords.Count) {
        if (-not $packageRecords.Count) { $unread = 'no affected record identifies the queried package/version' }
        elseif ($packageRecords.Count -gt 1 -and @($packageRecords | Where-Object { -not $_.VersionKnown }).Count) {
            $unread = 'multiple affected ranges do not identify a unique severity for this version'
        } else {
            # For a single matching block, the exact-version OSV query already established
            # applicability. Multiple blocks need explicit version evidence for each selection.
            $containers += @($packageRecords | ForEach-Object { $_.Affected })
        }
    }
    foreach ($container in $containers) {
        $labels = $container.database_specific
        if ($labels -and ($labels.PSObject.Properties['severity'] -or
                ($labels -is [System.Collections.IDictionary] -and $labels.Contains('severity')))) {
            if ($labels.severity -isnot [string]) { $unread = 'a severity label is malformed' }
            else {
                $label = $labels.severity.Trim().ToUpperInvariant()
                if ($label -eq 'MEDIUM') { $label = 'MODERATE' }
                if (-not $rank.ContainsKey($label)) { $unread = 'a severity label is unknown' }
                else {
                    [void]$levels.Add($label)
                    if ($null -eq $level -or $rank[$label] -gt $rank[$level]) {
                        $level = $label
                        $who = if ($Advisory.source -eq 'publisher') { 'The publisher' } else { 'OSV' }
                        $why = "$who rates it $label"
                    }
                }
            }
        }
        if ($container.PSObject.Properties['severity'] -or
            ($container -is [System.Collections.IDictionary] -and $container.Contains('severity'))) {
            if ($container.severity -isnot [array]) { $unread = 'severity is not an array'; continue }
        } else { continue }
        foreach ($entry in @($container.severity)) {
            if ($null -eq $entry -or $entry.type -cne 'CVSS_V3' -or $entry.score -isnot [string]) {
                $unread = 'a severity vector is unreadable or uses an unsupported scoring method'; continue
            }
            $score = Get-Cvss3BaseScore -Vector $entry.score
            if ($null -eq $score) { $unread = 'a CVSS 3 vector is malformed'; continue }
            $scored = if ($score -ge 9.0) { 'CRITICAL' } elseif ($score -ge 7.0) { 'HIGH' } elseif ($score -ge 4.0) {
                'MODERATE' } elseif ($score -gt 0) { 'LOW' } else { 'NONE' }
            [void]$levels.Add($scored)
            if ($null -eq $level -or $rank[$scored] -gt $rank[$level]) {
                $level = $scored
                $why = "its CVSS 3 vector scores $score"
            }
        }
    }
    if ($levels.Count -gt 1 -and $null -ne $level -and $rank[$level] -lt 3) {
        $unread = 'applicable minor severity assessments conflict'
    }
    if ($unread -and $level -in @('HIGH', 'CRITICAL')) {
        return [pscustomobject]@{ Level = $level; Serious = $true
            Why = "$why; additional severity needs review: $unread" }
    }
    if ($null -eq $level -or $unread) {
        return [pscustomobject]@{ Level = 'UNRATED'; Serious = $true
            Why = "OSV gives it no severity this check can certify, so it needs review$(if ($unread) { ': ' + $unread })" }
    }
    return [pscustomobject]@{ Level = $level; Serious = $rank[$level] -ge 3; Why = $why }
}

function Invoke-OsvQuery {
    <#
    .SYNOPSIS
        Every advisory OSV has for one package URL, following its pages.
    .DESCRIPTION
        OSV answers {} for a package it knows nothing about, and for a package URL it can't read
        at all, which is why Read-ReleaseSbom refuses a malformed one before it gets here. Anything
        that isn't a query result throws, and so does a failed request: the gate fails closed.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Purl,
        [string]$Endpoint = 'https://api.osv.dev/v1/query'
    )

    $found = New-Object System.Collections.Generic.List[object]
    $token = $null
    for ($page = 0; $page -lt 20; $page++) {
        $query = [ordered]@{ package = [ordered]@{ purl = $Purl } }
        if ($token) { $query['page_token'] = $token }
        try {
            $answer = Invoke-RestMethod -Uri $Endpoint -Method Post -ContentType 'application/json' `
                -Body ($query | ConvertTo-Json -Compress -Depth 4) -TimeoutSec 60
        } catch {
            throw ("OSV could not be asked about ${Purl}: $($_.Exception.Message). The advisory check fails " +
                'closed, so nothing goes out on a check that did not run. Check the connection to api.osv.dev ' +
                'and run it again, or pass -SkipAdvisoryCheck to work offline: the index push asks OSV again.')
        }
        if ($answer -isnot [System.Management.Automation.PSCustomObject]) {
            throw "OSV answered the query about $Purl with something that isn't a query result: $answer"
        }
        foreach ($advisory in @($answer.vulns | Where-Object { $null -ne $_ })) {
            if ([string]::IsNullOrWhiteSpace([string]$advisory.id)) {
                throw "OSV answered the query about $Purl with an advisory that has no id."
            }
            # A withdrawn advisory is one its publisher took back.
            if ($advisory.PSObject.Properties['withdrawn'] -and $advisory.withdrawn) { continue }
            $found.Add($advisory)
        }
        $token = [string]$answer.next_page_token
        if (-not $token) { return $found.ToArray() }
    }
    throw "OSV was still paging its answer about $Purl after 20 pages."
}

function Get-VendorAdvisories {
    <# Publisher-reviewed supplements for advisories not yet returned by OSV. #>
    param([Parameter(Mandatory = $true)]$Library)

    if ($Library.Group -ceq 'org.gradle' -and $Library.Name -ceq 'gradle-core') {
        $gradle = [regex]::Match([string]$Library.Version, '^(\d+\.\d+(?:\.\d+)?)$')
        if (-not $gradle.Success) { throw "The publisher dependency check cannot classify Gradle $($Library.Version)." }
        $number = [version]$gradle.Groups[1].Value
        if ($number -ge [version]'8.14.6' -and -not ($number -ge [version]'9.0' -and $number -le [version]'9.8.0')) { return }
        # https://github.com/gradle/gradle/security/advisories, published 2026-10-07 and reviewed
        # 2026-10-12: affected below 8.14.6 and 9.0.0 to 9.8.0, fixed in 8.14.6 and 9.8.1.
        return @(
            @('GHSA-xwqc-3h47-hg64', 'Deserialization of untrusted Java objects before authentication in the client - daemon communication'),
            @('GHSA-mvvg-497x-hmj8', 'Unauthenticated worker-to-daemon channel deserializes untrusted Java objects'),
            @('GHSA-j5m7-59rp-24f5', 'Failure to disable repositories failing to establish an SSL connection can expose builds to malicious artifacts')
        ) | ForEach-Object {
            [pscustomobject]@{ id = $_[0]; aliases = @(); summary = $_[1]
                database_specific = [pscustomobject]@{ severity = 'HIGH' }; source = 'publisher'
                sourceUrl = "https://github.com/gradle/gradle/security/advisories/$($_[0])" }
        }
    }
    if ($Library.Group -cne 'com.google.guava' -or $Library.Name -cne 'guava') { return }
    $version = [regex]::Match([string]$Library.Version, '^(\d+\.\d+(?:\.\d+)?)(?:-(?:jre|android))?$')
    if (-not $version.Success) { throw "The publisher dependency check cannot classify Guava $($Library.Version)." }
    $number = [version]$version.Groups[1].Value
    if ($number -lt [version]'4.0' -or $number -ge [version]'33.7.2') { return }
    # https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94
    # Reviewed 2026-10-01. Requires attacker-controlled native Java deserialization;
    # a component finding is not evidence of a reachable deserialization path.
    return [pscustomobject]@{
        id = 'GHSA-xxph-c9ww-hj94'; aliases = @('CVE-2026-102554')
        summary = 'Uncontrolled allocation during native Java deserialization'
        database_specific = [pscustomobject]@{ severity = 'MODERATE' }
        severity = @([pscustomobject]@{ type = 'CVSS_V3'; score = 'CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:N/A:H' })
        source = 'publisher'
        sourceUrl = 'https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94'
    }
}

function Read-DependencyGraphs {
    param([Parameter(Mandatory = $true)][string]$Path)

    try { $report = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -ErrorAction Stop }
    catch { throw "The dependency graph report cannot be read: $($_.Exception.Message)" }
    if ($report.schemaVersion -ne 2 -or -not $report.graphs) {
        throw 'The dependency graph report must use schema 2 and contain bound graphs.'
    }
    Assert-DependencyInputIdentity -Inputs $report.inputs -Label 'The dependency graph report'
    $scopes = @('settings-plugin', 'project-plugin', 'build', 'test', 'host-contract')
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $libraries = @{}
    foreach ($graph in @($report.graphs)) {
        if ($graph.scope -cnotin $scopes -or -not $graph.owner -or -not $graph.configuration -or
            -not $graph.PSObject.Properties['libraries']) {
            throw 'A dependency graph has no reviewed scope, owner, configuration or library list.'
        }
        if (-not $seen.Add("$($graph.scope) $($graph.owner) $($graph.configuration)")) {
            throw 'The dependency graph report contains a duplicate graph.'
        }
        $moduleRefs = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
        foreach ($library in @($graph.libraries)) {
            $purl = [regex]::Match([string]$library.purl, '^pkg:maven/([^/@?#]+)/([^/@?#]+)@([^/@?#]+)$')
            if (-not $library.group -or -not $library.name -or -not $library.version -or -not $purl.Success -or
                [Uri]::UnescapeDataString($purl.Groups[1].Value) -cne $library.group -or
                [Uri]::UnescapeDataString($purl.Groups[2].Value) -cne $library.name -or
                [Uri]::UnescapeDataString($purl.Groups[3].Value) -cne $library.version) {
                throw 'A dependency graph library is unresolved or its package URL names another coordinate.'
            }
            if (-not $moduleRefs.Add([string]$library.purl)) { throw 'A dependency graph contains a duplicate library.' }
            $libraries[[string]$library.purl] = $library
        }
        $nodes = [System.Collections.Generic.Dictionary[string, object]]::new([System.StringComparer]::Ordinal)
        foreach ($node in @($graph.dependencies)) {
            $ref = [string]$node.ref
            if (-not $ref -or -not $node.PSObject.Properties['dependsOn'] -or $nodes.ContainsKey($ref)) {
                throw 'A dependency graph has a missing or duplicate edge node.'
            }
            $nodes.Add($ref, $node)
        }
        if ($graph.root -cne 'root' -or -not $nodes.ContainsKey('root')) {
            throw 'A dependency graph omits its root-to-module edges.'
        }
        $expected = @('root') + @($graph.libraries | ForEach-Object { $_.purl })
        foreach ($ref in $nodes.Keys) {
            if ($ref -cnotin $expected -and -not $ref.StartsWith('project:')) {
                throw 'A dependency graph edge names an unlisted library.'
            }
            $targets = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
            foreach ($target in @($nodes[$ref].dependsOn)) {
                if (-not $nodes.ContainsKey([string]$target) -or -not $targets.Add([string]$target)) {
                    throw 'A dependency graph has an unresolved or duplicate dependency edge.'
                }
            }
        }
        $reached = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
        $queue = [System.Collections.Generic.Queue[string]]::new()
        $queue.Enqueue('root')
        while ($queue.Count) {
            $ref = $queue.Dequeue()
            if ($reached.Add($ref)) { foreach ($target in @($nodes[$ref].dependsOn)) { $queue.Enqueue([string]$target) } }
        }
        if ($nodes.Count -ne $reached.Count -or @($expected | Where-Object { -not $reached.Contains($_) }).Count) {
            throw 'A dependency graph has a library or node not reachable from its root.'
        }
    }
    foreach ($scope in $scopes) {
        if (-not @($report.graphs | Where-Object scope -CEQ $scope).Count) {
            throw "The dependency graph report omits the $scope scope."
        }
    }
    return [pscustomobject]@{ Path = $Path; Sha256 = Get-Sha256Hex -Path $Path; Inputs = $report.inputs; Graphs = @($report.graphs)
        Libraries = @($libraries.Values | Sort-Object purl) }
}

function Assert-DependencyInputIdentity {
    param($Inputs, [string]$Label)
    if (-not $Inputs -or $Inputs.schemaVersion -ne 1 -or -not $Inputs.version) {
        throw "$Label has no current build-input identity. Rebuild and regenerate the dependency audit."
    }
    foreach ($field in 'sourceSha256', 'catalogSha256', 'toolchainSha256', 'inputSha256') {
        if ([string]$Inputs.$field -cnotmatch '^[0-9a-f]{64}$') { throw "$Label has an invalid $field build-input digest." }
    }
}

function Get-DependencyAuditInputs {
    param([Parameter(Mandatory = $true)][string]$Root)
    $names = @(Invoke-RepoGit -Root $Root -Arguments @('-c', 'core.quotepath=false', 'ls-files', '--cached'))
    if ($LASTEXITCODE -ne 0 -or -not $names.Count) { throw 'Cannot enumerate tracked build inputs.' }
    $added = @(Invoke-RepoGit -Root $Root -Arguments @('ls-files', '--others', '--exclude-standard'))
    if ($LASTEXITCODE -ne 0) { throw 'Cannot check untracked build inputs.' }
    if ($added.Count) { throw 'Stage new repository inputs before building or certifying an input-bound artifact.' }
    $sorted = [System.Collections.Generic.SortedSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($name in $names) {
        if (-not $name -or $name -match '[\r\n\t]' -or $name.StartsWith('"') -or -not $sorted.Add($name)) {
            throw 'A tracked build input has an unsupported or duplicate filename.'
        }
    }
    $groups = [ordered]@{ source = [Text.StringBuilder]::new(); catalog = [Text.StringBuilder]::new(); toolchain = [Text.StringBuilder]::new() }
    foreach ($name in $sorted) {
        $path = Join-Path $Root $name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "A tracked build input is missing: $name" }
        $group = if ($name -ceq 'patches-list.json') { 'catalog' } elseif (
            $name.StartsWith('gradle/') -or $name -cin @('gradle.properties', 'settings.gradle.kts', 'build.gradle.kts', 'gradlew', 'gradlew.bat') -or
            $name.EndsWith('.gradle') -or $name.EndsWith('.gradle.kts')) { 'toolchain' } else { 'source' }
        [void]$groups[$group].Append("$name`t$((Get-Sha256Hex -Path $path).ToLowerInvariant())`n")
    }
    if (@($groups.Values | Where-Object { -not $_.Length }).Count) { throw 'Tracked inputs omit the source, catalog or toolchain.' }
    $version = [regex]::Match((Get-Content -LiteralPath (Join-Path $Root 'gradle.properties') -Raw), '(?m)^version\s*=\s*(\S+)').Groups[1].Value
    if (-not $version) { throw 'No current bundle version is pinned.' }
    $inputs = [ordered]@{ schemaVersion = 1; version = $version }
    $combined = [Text.StringBuilder]::new()
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        foreach ($group in $groups.Keys) {
            $digest = ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($groups[$group].ToString())) | ForEach-Object { '{0:x2}' -f $_ }) -join ''
            $inputs["${group}Sha256"] = $digest
            [void]$combined.Append("${group}:$digest`n")
        }
        $inputs.inputSha256 = ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($combined.ToString())) | ForEach-Object { '{0:x2}' -f $_ }) -join ''
    } finally { $sha.Dispose() }
    return [pscustomobject]$inputs
}

function Assert-DependencyInputsMatch {
    param($Expected, $Actual, [string]$Label)
    Assert-DependencyInputIdentity -Inputs $Actual -Label $Label
    foreach ($field in 'version', 'sourceSha256', 'catalogSha256', 'toolchainSha256', 'inputSha256') {
        if ($Actual.$field -cne $Expected.$field) { throw "$Label has stale or mismatched $field build inputs. Rebuild and regenerate the dependency audit." }
    }
}

function Get-CurrentDependencyAuditSubject {
    param([Parameter(Mandatory = $true)][string]$Root, [Parameter(Mandatory = $true)]$Graphs,
        [Parameter(Mandatory = $true)]$Sbom, [Parameter(Mandatory = $true)][string]$BundlePath, [string]$DesktopJar)
    Assert-NoIgnoredCanonicalProductionInputs -Root $Root
    $inputs = Get-DependencyAuditInputs -Root $Root
    if ((Get-Sha256Hex -Path $Graphs.Path) -cne $Graphs.Sha256) { throw 'The dependency graph bytes changed after they were read.' }
    Assert-DependencyInputsMatch -Expected $inputs -Actual $Graphs.Inputs -Label 'The dependency graph report'
    $document = Get-Content -LiteralPath $Sbom.Path -Raw | ConvertFrom-Json
    $recorded = @($document.metadata.properties | Where-Object name -CEQ 'hushgram:build-inputs')
    if ($recorded.Count -ne 1) { throw 'The current SBOM has no unique build-input identity. Rebuild it.' }
    try { $sbomInputs = $recorded[0].value | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'The current SBOM build-input identity cannot be read.' }
    Assert-DependencyInputsMatch -Expected $inputs -Actual $sbomInputs -Label 'The current SBOM'
    $bundleName = "patches-$($inputs.version).mpp"
    $sbomName = "patches-$($inputs.version).cdx.json"
    if ((Split-Path -Leaf $BundlePath) -cne $bundleName -or (Split-Path -Leaf $Sbom.Path) -cne $sbomName -or
        $Sbom.BundleVersion -cne $inputs.version) { throw 'The current SBOM or bundle has a mismatched name or version.' }
    if ((Get-Sha256Hex -Path $Sbom.Path) -cne $Sbom.Sha256) { throw 'The current SBOM bytes changed after they were read.' }
    $bound = Test-ReleaseSbom -Sbom $Sbom -BundlePath $BundlePath -BundleName $bundleName
    if (-not $bound.Valid) { throw "The current SBOM does not describe the bundle: $($bound.Reason)" }
    $desktop = if ($DesktopJar) { Get-ExternalDesktopGraph -JarPath $DesktopJar }
    $subject = [ordered]@{ inputs = $inputs
        bundle = [ordered]@{ name = $bundleName; version = $inputs.version; sha256 = Get-Sha256Hex -Path $BundlePath }
        sbom = [ordered]@{ name = $sbomName; sha256 = $Sbom.Sha256 }
        graphReport = [ordered]@{ name = Split-Path -Leaf $Graphs.Path; sha256 = $Graphs.Sha256
            graphsSha256 = Get-DependencyGraphsDigest -Graphs (Get-DependencyAuditGraphs -Graphs $Graphs -Sbom $Sbom -Root $Root -Desktop $desktop) } }
    if ($desktop) { $subject.externalDesktop = [ordered]@{ name = $desktop.configuration; sha256 = $desktop.sha256 } }
    return $subject
}

function Get-GradleDistributionGraph {
    <#
    .SYNOPSIS
        The Gradle distribution the wrapper runs, as its own audit graph.
    .DESCRIPTION
        Resolved dependency graphs never list Gradle itself, so its advisories (published against
        org.gradle:gradle-core) were invisible to the audit. The version comes from the wrapper's
        distributionUrl, which VerifyGradleWrapper.java pins with its checksums.
    #>
    param([Parameter(Mandatory = $true)][string]$Root)
    $path = Join-Path $Root 'gradle/wrapper/gradle-wrapper.properties'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'The Gradle wrapper properties are missing, so the distribution cannot be audited.' }
    $url = [regex]::Match((Get-Content -LiteralPath $path -Raw), '(?m)^distributionUrl\s*=\s*(\S+)\s*$').Groups[1].Value.Replace('\:', ':')
    $version = [regex]::Match($url, '^https://services\.gradle\.org/distributions/gradle-(\d+\.\d+(?:\.\d+)?)-bin\.zip$')
    if (-not $version.Success) { throw "The Gradle wrapper names no reviewed distribution version: $url" }
    $purl = "pkg:maven/org.gradle/gradle-core@$($version.Groups[1].Value)"
    return [pscustomobject]@{ scope = 'tooling-distribution'; owner = 'gradle-wrapper'
        configuration = 'gradle/wrapper/gradle-wrapper.properties'; root = 'root'
        libraries = @([pscustomobject]@{ group = 'org.gradle'; name = 'gradle-core'; version = $version.Groups[1].Value; purl = $purl })
        dependencies = @([pscustomobject]@{ ref = 'root'; dependsOn = @($purl) }, [pscustomobject]@{ ref = $purl; dependsOn = @() }) }
}

# The classes each reviewed advisory names, as JAR entries. A shaded, minimized JAR can keep a
# library's metadata after dropping the classes an advisory is about, and only the classes are
# exposure. An advisory missing here is unreviewed and is never read as absent.
# GHSA-xxph-c9ww-hj94, reviewed 2026-10-18: the CompactHash* family and MapMaker's internal map.
$script:ReviewedAdvisoryClasses = @{
    'GHSA-xxph-c9ww-hj94' = @('CompactHashMap', 'CompactHashSet', 'CompactLinkedHashMap', 'CompactLinkedHashSet',
        'CompactHashing', 'MapMakerInternalMap', 'MapMaker') | ForEach-Object { "com/google/common/collect/$_.class" }
}

function Get-ExternalDesktopGraph {
    <#
    .SYNOPSIS
        The Morphe Desktop JAR a release gate patches with, as its own audit graph.
    .DESCRIPTION
        Desktop is shaded and minimized upstream, so the repository's Bouncy Castle and Guava
        overrides never reach the copies inside it. The graph is bound to the JAR's SHA-256 and
        keeps its evidence apart: libraries whose Maven metadata (META-INF/maven/*/*/pom.properties)
        the JAR embeds, and Bouncy Castle, which embeds none and is read from its provider's
        version string, with its PKIX classes counted as the same release's bcpkix. Metadata says a
        library was packed, not that its classes survived, so the graph also records which classes
        each reviewed advisory names are in the JAR. A JAR that can't be read, or metadata or a
        provider that can't be, certifies nothing.
    #>
    param([Parameter(Mandatory = $true)][string]$JarPath)
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw "The Desktop JAR is missing, so it can't be audited: $JarPath" }
    $full = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($JarPath)
    $name = Split-Path -Leaf $full
    $sha256 = Get-Sha256Hex -Path $full
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    try { $archive = [System.IO.Compression.ZipFile]::OpenRead($full) }
    catch { throw "The Desktop JAR $name can't be read, so no advisory result is certified: $($_.Exception.Message)" }
    $libraries = [ordered]@{}
    $entries = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    try {
        foreach ($entry in $archive.Entries) {
            [void]$entries.Add($entry.FullName)
            $path = [regex]::Match($entry.FullName, '^META-INF/maven/([^/]+)/([^/]+)/pom\.properties$')
            if (-not $path.Success) { continue }
            $reader = [IO.StreamReader]::new($entry.Open())
            try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
            $fields = @{}
            foreach ($line in $text -split "`r?`n") {
                $pair = [regex]::Match($line, '^\s*(groupId|artifactId|version)\s*=\s*(\S+)\s*$')
                if ($pair.Success) { $fields[$pair.Groups[1].Value] = $pair.Groups[2].Value }
            }
            if ($fields['groupId'] -cne $path.Groups[1].Value -or $fields['artifactId'] -cne $path.Groups[2].Value -or
                [string]$fields['version'] -cnotmatch '^[A-Za-z0-9][A-Za-z0-9._+-]*$') {
                throw "The Desktop JAR's $($entry.FullName) doesn't name the library its path does, so its inventory can't be read."
            }
            $purl = "pkg:maven/$($fields['groupId'])/$($fields['artifactId'])@$($fields['version'])"
            $libraries[$purl] = [pscustomobject]@{ group = $fields['groupId']; name = $fields['artifactId']
                version = $fields['version']; purl = $purl; evidence = 'embedded-metadata'; source = $entry.FullName }
        }
        $provider = 'org/bouncycastle/jce/provider/BouncyCastleProvider.class'
        if (@($entries | Where-Object { $_.StartsWith('org/bouncycastle/') -and $_.EndsWith('.class') } | Select-Object -First 1).Count) {
            $release = $null
            if ($entries.Contains($provider)) {
                $stream = [IO.MemoryStream]::new()
                $open = $archive.GetEntry($provider).Open()
                try { $open.CopyTo($stream) } finally { $open.Dispose() }
                $release = [regex]::Match([Text.Encoding]::GetEncoding(28591).GetString($stream.ToArray()),
                    'BouncyCastle Security Provider v(\d+\.\d+(?:\.\d+)?)')
            }
            if (-not $release -or -not $release.Success) {
                throw "The Desktop JAR $name carries Bouncy Castle classes without a provider version, so their release can't be read."
            }
            $version = $release.Groups[1].Value
            # 1.71 renamed the Java 8+ artifacts from jdk15on to jdk18on.
            $suffix = if ([version]$version -ge [version]'1.71') { 'jdk18on' } else { 'jdk15on' }
            $artifacts = @("bcprov-$suffix") + @(if (@($entries | Where-Object { $_.StartsWith('org/bouncycastle/cert/') }).Count) { "bcpkix-$suffix" })
            foreach ($artifact in $artifacts) {
                $purl = "pkg:maven/org.bouncycastle/$artifact@$version"
                if (-not $libraries.Contains($purl)) {
                    $libraries[$purl] = [pscustomobject]@{ group = 'org.bouncycastle'; name = $artifact; version = $version
                        purl = $purl; evidence = 'provider-version'; source = $provider }
                }
            }
        }
    } finally { $archive.Dispose() }
    $classes = @(foreach ($advisory in @($script:ReviewedAdvisoryClasses.Keys | Sort-Object)) {
        foreach ($class in $script:ReviewedAdvisoryClasses[$advisory]) {
            [pscustomobject]@{ advisory = $advisory; class = $class; present = $entries.Contains($class) }
        }
    })
    $purls = @($libraries.Keys | Sort-Object)
    return [pscustomobject]@{ scope = 'external-desktop'; owner = 'morphe-desktop'; configuration = $name; sha256 = $sha256
        root = 'root'; libraries = @($purls | ForEach-Object { $libraries[$_] }); advisoryClasses = $classes
        dependencies = @([pscustomobject]@{ ref = 'root'; dependsOn = $purls }) + @($purls | ForEach-Object {
            [pscustomobject]@{ ref = $_; dependsOn = @() } }) }
}

function Get-DesktopClassPresence {
    <#
    .SYNOPSIS
        Whether the classes a finding's advisory names are in the Desktop JAR: present, absent, or
        unreviewed when no reviewed class list covers the advisory or any of its aliases.
    #>
    param([Parameter(Mandatory = $true)]$Finding, [Parameter(Mandatory = $true)]$Graph)
    $names = @(@($Finding.Advisory) + @($Finding.Aliases) | Where-Object { $_ })
    $reviewed = @(@($Graph.advisoryClasses) | Where-Object { $_ -and $names -contains $_.advisory })
    if (-not $reviewed.Count) { return 'unreviewed' }
    if (@($reviewed | Where-Object { $_.present -eq $true }).Count) { return 'present' }
    return 'absent'
}

function Get-DependencyAuditGraphs {
    param($Graphs, $Sbom, [Parameter(Mandatory = $true)][string]$Root, $Desktop)
    return @($Graphs.Graphs) + @([pscustomobject]@{ scope = 'shipped'; owner = 'bundle'
        configuration = Split-Path -Leaf $Sbom.Path; libraries = @($Sbom.Libraries)
        dependencies = (Get-Content -LiteralPath $Sbom.Path -Raw | ConvertFrom-Json).dependencies }) +
        @(Get-GradleDistributionGraph -Root $Root) + @(if ($Desktop) { $Desktop })
}

function Get-DependencyGraphsDigest {
    param([object[]]$Graphs)
    $json = ConvertTo-Json -InputObject $Graphs -Depth 16 -Compress
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($json)) | ForEach-Object { '{0:x2}' -f $_ }) -join '' }
    finally { $sha.Dispose() }
}

function Read-CurrentDependencyAdvisoryReport {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)]$Subject,
        [datetime]$Now = [datetime]::UtcNow)
    try { $report = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'The current dependency advisory report cannot be read.' }
    if ($report.schemaVersion -ne 2 -or $report.valid -isnot [bool] -or -not $report.valid) {
        throw 'The current dependency advisory report is uncertified or uses an old schema.'
    }
    Assert-DependencyInputsMatch -Expected $Subject.inputs -Actual $report.subject.inputs -Label 'The advisory report'
    # A gate that patches with a Desktop JAR holds the report to that JAR's bytes and scope.
    $desktop = $Subject.Contains('externalDesktop')
    foreach ($part in @(if ($desktop) { 'externalDesktop' }) + @('bundle', 'sbom', 'graphReport')) {
        foreach ($field in @($Subject[$part].Keys)) {
            if ($report.subject.$part.$field -cne $Subject[$part][$field]) { throw "The advisory report names a different $part $field." }
        }
    }
    if ((Get-DependencyGraphsDigest -Graphs $report.graphs) -cne $Subject.graphReport.graphsSha256) {
        throw 'The advisory report contains substituted dependency graphs.'
    }
    try { $stamp = [DateTimeOffset]::Parse((ConvertTo-UtcStamp $report.checkedAt)).UtcDateTime }
    catch { throw 'The advisory report has no valid check date.' }
    if ($stamp -gt $Now.AddMinutes(5) -or $stamp -lt $Now.AddDays(-1)) { throw 'The advisory report is stale or dated in the future. Run the current audit again.' }
    foreach ($scope in @('shipped', 'settings-plugin', 'project-plugin', 'build', 'test', 'host-contract', 'tooling-distribution') +
            @(if ($desktop) { 'external-desktop' })) {
        if (-not $report.scopeVerdicts.PSObject.Properties[$scope] -or $report.scopeVerdicts.$scope.Valid -isnot [bool] -or
            -not $report.scopeVerdicts.$scope.Valid -or
            -not @($report.graphs | Where-Object scope -CEQ $scope).Count) { throw "The advisory report omits or refuses the $scope scope." }
    }
    return $report
}

function Get-SbomAdvisories {
    <#
    .SYNOPSIS
        One finding per advisory identity OSV or the publisher has for an SBOM library.
    .DESCRIPTION
        Each: @{ Package; Version; Purl; Advisory; Aliases; Summary; Severity }. First-party
        components carry no package URL and have nothing to ask about.
    #>
    param([Parameter(Mandatory = $true)]$Sbom)

    $findings = New-Object System.Collections.Generic.List[object]
    foreach ($library in @($Sbom.Libraries)) {
        $reported = @(@(Invoke-OsvQuery -Purl $library.Purl) + @(Get-VendorAdvisories -Library $library))
        $rank = @{ NONE = 0; LOW = 1; MODERATE = 2; UNRATED = 2.5; HIGH = 3; CRITICAL = 4 }
        # OSV aliases denote the same vulnerability. Join overlapping identities before
        # choosing severity, including a record that bridges two earlier groups.
        $groups = @()
        foreach ($record in $reported) {
            $names = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
            foreach ($name in @($record.id) + @($record.aliases)) { if ($name) { [void]$names.Add([string]$name) } }
            $members = @($record)
            $separate = @()
            foreach ($existing in $groups) {
                if (@($names | Where-Object { $existing.Names.Contains($_) }).Count) {
                    $members += $existing.Records
                    foreach ($name in $existing.Names) { [void]$names.Add($name) }
                } else { $separate += $existing }
            }
            $groups = @($separate) + @([pscustomobject]@{ Names = $names; Records = $members })
        }
        foreach ($group in $groups) {
            $rated = @($group.Records | ForEach-Object {
                [pscustomobject]@{ Record = $_; Severity = Get-AdvisorySeverity -Advisory $_ -Library $library }
            } | Sort-Object { $rank[$_.Severity.Level] } -Descending)
            $advisory = $rated[0].Record
            $severity = $rated[0].Severity
            $unrated = @($rated.Severity | Where-Object { $_.Level -eq 'UNRATED' })
            if ($severity.Level -in @('HIGH', 'CRITICAL') -and $unrated.Count) {
                $severity = [pscustomobject]@{ Level = $severity.Level; Serious = $true
                    Why = $severity.Why + '; ' + (($unrated.Why | Sort-Object -Unique) -join '; ') }
            }
            if (-not $severity.Serious -and @($rated.Severity.Level | Sort-Object -Unique).Count -gt 1) {
                $severity = [pscustomobject]@{ Level = 'UNRATED'; Serious = $true
                    Why = 'Publisher or alias severity assessments conflict, so this finding needs review' }
            }
            $findings.Add([pscustomobject]@{
                Package  = "$($library.Group):$($library.Name)"
                Version  = $library.Version
                Purl     = $library.Purl
                Advisory = [string]$advisory.id
                Aliases  = @($group.Names | Where-Object { $_ -cne $advisory.id } | Sort-Object)
                Summary  = "$($advisory.summary)".Trim()
                Severity = $severity
                Sources  = @($group.Records | ForEach-Object { if ($_.source) { $_.source } else { 'OSV' } } | Sort-Object -Unique)
            })
        }
    }
    return $findings.ToArray()
}

function Test-AdvisoryFindings {
    <#
    .SYNOPSIS
        Whether a release may go out with these findings and these exceptions.
    .DESCRIPTION
        Answers @{ Valid; Reason; Refused; Excused; Minor; Stale }, the last four as the lines to
        print. A serious finding needs an exception naming its advisory, or one of the advisory's
        aliases, and its package, dated today or later. An exception that matches no finding at
        all, of any severity, is stale and refused too.
        In the external-desktop scope a finding carries DesktopClassPresence. One whose reviewed
        classes are all absent from the JAR is packed metadata, not exposure, and is listed
        rather than refused. Present or unreviewed classes need the reviewed-reachability
        exception like any other scope, and only one naming -DesktopSha256 counts. A review of
        another JAR is left out, neither used nor stale, so a new Desktop's findings stay refused
        until that JAR is read again.
    #>
    param(
        [object[]]$Findings = @(),
        [object[]]$Exceptions = @(),
        [string]$Subject = 'what the bundle carries',
        [string]$ExceptionsLabel = 'scripts/advisory-exceptions.txt',
        [ValidateSet('shipped', 'settings-plugin', 'project-plugin', 'build', 'test', 'host-contract', 'tooling-distribution', 'external-desktop')]
        [string]$Scope = 'shipped',
        [string]$DesktopSha256
    )

    $findingsIn = @(@($Findings) | Where-Object { $null -ne $_ })
    # Legacy payload exceptions have no Scope. They can only cover shipment.
    $exceptionsIn = @(@($Exceptions) | Where-Object {
        $null -ne $_ -and ($_.Scope -ceq $Scope -or ($Scope -ceq 'shipped' -and -not $_.Scope))
    })
    if ($Scope -ceq 'external-desktop') {
        $DesktopSha256 = "$DesktopSha256".ToUpperInvariant()
        $exceptionsIn = @($exceptionsIn | Where-Object { $DesktopSha256 -and $_.DesktopSha256 -ceq $DesktopSha256 })
    }
    $refused = New-Object System.Collections.Generic.List[string]
    $excused = New-Object System.Collections.Generic.List[string]
    $minor = New-Object System.Collections.Generic.List[string]
    # By advisory and package, which the reader holds to one line each.
    $used = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)

    foreach ($finding in $findingsIn) {
        $names = @(@($finding.Advisory) + @($finding.Aliases))
        $matching = @($exceptionsIn | Where-Object {
            $exception = $_
            [string]::Equals($exception.Package, $finding.Package, [System.StringComparison]::OrdinalIgnoreCase) -and
                @($names | Where-Object { [string]::Equals($_, $exception.Advisory, [System.StringComparison]::OrdinalIgnoreCase) }).Count -gt 0
        })
        foreach ($exception in $matching) { [void]$used.Add("$($exception.Advisory) $($exception.Package)") }
        $aliases = if (@($finding.Aliases).Count -gt 0) { ", $(@($finding.Aliases) -join ', ')" } else { '' }
        $described = ("$($finding.Advisory) ($($finding.Severity.Level)$aliases) in $($finding.Package) " +
            "$($finding.Version): $($finding.Summary)")
        if ($Scope -ceq 'external-desktop') {
            $presence = [string]$finding.DesktopClassPresence
            if ($presence -cnotin @('present', 'absent', 'unreviewed')) { $presence = 'unreviewed' }
            $described += "; its reviewed classes in the Desktop JAR: $presence"
            if ($finding.Severity.Serious -and $presence -ceq 'absent') {
                $minor.Add("$described, so the packed metadata is not exposure; $($finding.Severity.Why)")
                continue
            }
        }
        if (-not $finding.Severity.Serious) {
            $minor.Add("$described; $($finding.Severity.Why)")
            continue
        }
        $current = @($matching | Where-Object { -not $_.Expired })
        if ($current.Count -gt 0) {
            $excused.Add("$described; accepted until $($current[0].Until.ToString('yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)): $($current[0].Reason)")
            continue
        }
        $lapsed = @($matching | Where-Object { $_.Expired })
        if ($lapsed.Count -gt 0) {
            $refused.Add("$described; $($finding.Severity.Why), and its exception ran out on $($lapsed[0].Until.ToString('yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture))")
        } else {
            $refused.Add("$described; $($finding.Severity.Why)")
        }
    }
    $stale = New-Object System.Collections.Generic.List[string]
    foreach ($exception in $exceptionsIn) {
        if (-not $used.Contains("$($exception.Advisory) $($exception.Package)")) {
            $stale.Add("$($exception.Advisory) for $($exception.Package) (line $($exception.Line))")
        }
    }

    $reasons = @()
    if ($refused.Count -gt 0) {
        $reasons += ("OSV reports high or critical advisories for ${Subject}: " + ($refused -join '; ') +
            ". Move to a version without them, or accept one in $ExceptionsLabel with the reason " +
            'it does not apply and a date within 90 days.')
    }
    if ($stale.Count -gt 0) {
        $reasons += ("$ExceptionsLabel accepts advisories OSV no longer reports for ${Subject}: " +
            ($stale -join '; ') + '. Take them out.')
    }
    return [pscustomobject]@{
        Valid   = $reasons.Count -eq 0
        Reason  = if ($reasons.Count -gt 0) { $reasons -join ' ' } else { $null }
        Refused = $refused.ToArray()
        Excused = $excused.ToArray()
        Minor   = $minor.ToArray()
        Stale   = $stale.ToArray()
    }
}

function Invoke-ReleaseAdvisoryGate {
    <#
    .SYNOPSIS
        Asks OSV about an SBOM's libraries, prints what it said, and throws when the release may
        not go out.
    #>
    param(
        [Parameter(Mandatory = $true)]$Sbom,
        [Parameter(Mandatory = $true)][string]$ExceptionsPath,
        [switch]$SkipAdvisoryCheck,
        [datetime]$Today = [datetime]::Today
    )

    $libraries = @($Sbom.Libraries)
    $sbomName = Split-Path -Leaf $Sbom.Path
    # Read even when OSV isn't asked: a malformed or overlong exception is wrong offline too.
    $exceptions = @(Read-AdvisoryExceptions -Path $ExceptionsPath -Today $Today)
    if ($SkipAdvisoryCheck) {
        Write-Warning ("-SkipAdvisoryCheck: OSV was not asked about the libraries $sbomName lists, so nothing " +
            'here says they have no serious advisory. The index push asks OSV again.')
        return
    }
    if ($libraries.Count -eq 0) {
        Write-Host "[advisories] $sbomName lists no library to ask OSV about"
        return
    }
    $findings = @(Get-SbomAdvisories -Sbom $Sbom)
    $verdict = Test-AdvisoryFindings -Findings $findings -Exceptions $exceptions
    foreach ($line in @($verdict.Minor)) { Write-Host "[advisories] below high, let through: $line" }
    foreach ($line in @($verdict.Excused)) { Write-Host "[advisories] accepted: $line" }
    if (-not $verdict.Valid) { throw $verdict.Reason }
    $packages = @($libraries | ForEach-Object { "$($_.Name) $($_.Version)" }) -join ', '
    $said = if ($findings.Count -eq 0) { 'no advisory' } else { "$($findings.Count) advisories, none refused" }
    if (@($findings | Where-Object { 'publisher' -in $_.Sources }).Count) {
        Write-Host "[advisories] OSV and publisher checks found $said for the libraries $sbomName lists: $packages"
    } else {
        Write-Host "[advisories] OSV has $said for the libraries $sbomName lists: $packages"
    }
}
