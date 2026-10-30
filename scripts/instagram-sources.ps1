<#
.SYNOPSIS
    The Instagram source ledger, sources/instagram-sources.json: reading it, holding it to its
    rules, and how old its census is.

.DESCRIPTION
    Dot-source this. The ledger names every patch source, Xposed module, standalone patcher and
    archive found for com.instagram.android, the branches and commits each was last read at, its
    licence, and what HushGram may take from it. It also names the Meta-app sources HushGram's
    shared code was copied from, since provenance.json may only port from a source the ledger
    adopts. Here the rules are checked:

    - A source with no licence, one whose licence can't be combined with GPL-3.0, or one whose
      Instagram code came from such a source (contaminatedBy) is behavior-only. Its code is never
      read for porting; what it does can be rediscovered from Instagram's own code.
    - An adopted source, one whose code HushGram ships, needs a pinned commit, a compatible licence
      with its URL and hash, its repository in NOTICE, a ported rule in provenance.json naming that
      repository and commit, and evidence from every Instagram fixture the catalog declares. A
      provenance rule can't name a source the ledger doesn't allow.
    - Every index people find patch sources through records whether HushGram is listed there,
      when that was checked, or the dated submission.

    scripts/audit-instagram-sources.ps1 refreshes the ledger against those indexes and the forges,
    and its -ValidateOnly run says how old the census is. Nothing in this file reads the network or
    any source's code.

.NOTES
    Taken from Hushfacebook's scripts/facebook-sources.ps1
    (https://github.com/SysAdminDoc/Hushfacebook, commit 3a47363954eea853357e71e7cf3951a5ee984cee).
    GPL-3.0-only. Modified for HushGram (Instagram), 2026.
#>

. (Join-Path $PSScriptRoot 'patch-target.ps1')

function Get-SourcePackages {
    # The package the census searches for. Threads (com.instagram.barcelona) shares Instagram's
    # code, but it has a sibling bundle with a census of its own, so its sources are counted there.
    return @('com.instagram.android')
}

function Get-SourceFamilyPackages {
    <#
        The Meta apps whose sources may stand in the ledger. Only an adopted source can name one the
        census doesn't cover: it's there because HushGram ships its code (the shared extension and
        the re-signed build fix came from Facebook bundles), not because it patches Instagram.
    #>
    return @('com.instagram.android', 'com.instagram.barcelona', 'com.facebook.katana', 'com.facebook.orca', 'com.facebook.lite')
}

function Get-SourceCensusMaxAgeDays {
    # A census counts as current for this many days. Instagram ships weekly and so do the busier
    # sources, so two weeks is one missed cycle, not two.
    return 14
}

function Get-SourceCompatibleLicenses {
    <#
        SPDX identifiers whose code can be combined into HushGram's GPL-3.0 work, as GitHub and
        GitLab report them. Anything else, NOASSERTION included, is treated as no licence at all.
    #>
    return @('GPL-3.0', 'GPL-3.0-only', 'GPL-3.0-or-later', 'GPL-2.0-or-later', 'LGPL-2.1-or-later',
        'LGPL-3.0', 'LGPL-3.0-only', 'LGPL-3.0-or-later', 'MIT', 'Apache-2.0', 'BSD-2-Clause',
        'BSD-3-Clause', 'ISC', 'MPL-2.0', 'Unlicense', '0BSD', 'CC0-1.0')
}

function Get-SourceDispositions {
    # adopted: HushGram ships code from it. candidate: licensed so a port could happen, after the
    # adopted gates. behavior-only: no code is taken, ever. rejected: licensed, nothing to take.
    return @('adopted', 'candidate', 'behavior-only', 'rejected')
}

function Get-SourceKinds {
    # standalone-patcher: a patcher with its own engine (Instafel, DFInsta), not a Morphe or
    # ReVanced bundle, so anything taken from it is rewritten as a patch.
    return @('morphe-patches', 'revanced-patches', 'xposed-module', 'standalone-patcher', 'apk-distribution', 'source-archive', 'readme-only')
}

function Get-SourceIndexIds {
    # The indexes the audit knows how to read. A new one needs its reader in the audit first.
    return @('morphe-directory', 'awesome-morphe', 'morphe-patch-tracker', 'jman-bundles', 'morphe-archive')
}

function Get-SourceLedgerPath {
    param([Parameter(Mandatory = $true)][string]$Root)
    return Join-Path (Join-Path $Root 'sources') 'instagram-sources.json'
}

function Read-SourceLedger {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "The Instagram source ledger is missing: $Path"
    }
    try {
        return [System.IO.File]::ReadAllText($Path) | ConvertFrom-Json
    } catch {
        throw "The Instagram source ledger is not JSON: $($_.Exception.Message)"
    }
}

function ConvertTo-SourceKey {
    <#
    .SYNOPSIS
        One spelling for a repository: host/owner/name in lower case, or $null when it isn't a
        GitHub or GitLab repository address.
    .DESCRIPTION
        The indexes spell the same repository several ways: ShuhaibNC and shuhaibnc, an API URL,
        a .git suffix, owner/name with no host. Two spellings of one repository must never count
        as two sources. A bare owner/name is read as GitHub's.
    #>
    param([string]$Repository)

    $text = "$Repository".Trim()
    if (-not $text) { return $null }
    $sourceHost = 'github.com'
    $path = $text
    if ($text -match '^(?i)https?://') {
        $uri = $null
        if (-not [Uri]::TryCreate($text, [UriKind]::Absolute, [ref]$uri)) { return $null }
        $sourceHost = $uri.Host.ToLowerInvariant() -replace '^www\.', ''
        $path = [Uri]::UnescapeDataString($uri.AbsolutePath)
        if ($sourceHost -eq 'api.github.com') {
            if ($path -notmatch '^/repos/') { return $null }
            $sourceHost = 'github.com'
            $path = $path.Substring(6)
        } elseif ($sourceHost -eq 'gitlab.com' -and $path -match '^/api/v4/projects/(.+)$') {
            $path = $Matches[1]
        }
    }
    $path = ($path.Trim('/') -replace '\.git$', '') -replace '/-/.*$', ''
    $segments = @($path -split '/' | Where-Object { $_ })
    if ($sourceHost -eq 'github.com') {
        if ($segments.Count -lt 2) { return $null }
        $segments = $segments[0..1]
    } elseif ($sourceHost -eq 'gitlab.com') {
        if ($segments.Count -lt 2) { return $null }
    } else {
        return $null
    }
    return ($sourceHost + '/' + ($segments -join '/')).ToLowerInvariant()
}

function Test-SourceDate {
    # A yyyy-MM-dd date, returned as a DateTime, or $null.
    param($Value)
    if ($Value -is [datetime]) { return $null }
    $parsed = [datetime]::MinValue
    if ([datetime]::TryParseExact("$Value", 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::None, [ref]$parsed)) {
        return $parsed
    }
    return $null
}

function Get-SourceToday {
    param([string]$Today)
    if ($Today) {
        $parsed = Test-SourceDate $Today
        if ($null -eq $parsed) { throw "-Today must be a yyyy-MM-dd date: $Today" }
        return $parsed
    }
    return [datetime]::UtcNow.Date
}

function Get-SourceProperty {
    # A property of a parsed JSON object, or $null when it isn't there. StrictMode-safe, and a
    # missing property reads the same in both editions.
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Test-SourceHttpsUrl {
    param($Value, [string[]]$Hosts)
    $uri = $null
    if (-not [Uri]::TryCreate("$Value", [UriKind]::Absolute, [ref]$uri)) { return $false }
    if ($uri.Scheme -ne 'https') { return $false }
    if ($Hosts -and ($Hosts -notcontains $uri.Host.ToLowerInvariant())) { return $false }
    return $true
}

function Test-SourceLedger {
    <#
    .SYNOPSIS
        Every rule the ledger is held to, offline. Returns Valid and the Problems found.
    .DESCRIPTION
        NOTICE, provenance.json and patches-list.json are read from -Root: an adopted source is
        held to all three, and provenance.json is held to the ledger in turn.
    #>
    param(
        [Parameter(Mandatory = $true)]$Ledger,
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Today
    )

    $problems = New-Object System.Collections.Generic.List[string]
    $todayDate = Get-SourceToday $Today
    $packages = Get-SourcePackages
    $family = Get-SourceFamilyPackages
    $compatible = Get-SourceCompatibleLicenses
    $dispositions = Get-SourceDispositions
    $kinds = Get-SourceKinds

    function Add-DateProblem($Value, [string]$What) {
        $date = Test-SourceDate $Value
        if ($null -eq $date) {
            $problems.Add("$What is not a yyyy-MM-dd date: '$Value'.")
        } elseif ($date -gt $todayDate) {
            $problems.Add("$What is $($date.ToString('yyyy-MM-dd')), after today ($($todayDate.ToString('yyyy-MM-dd'))).")
        }
    }

    if ((Get-SourceProperty $Ledger 'schemaVersion') -ne 1) {
        $problems.Add("schemaVersion must be 1, not '$(Get-SourceProperty $Ledger 'schemaVersion')'.")
    }
    $census = Get-SourceProperty $Ledger 'census'
    if ($null -eq $census) {
        $problems.Add('The ledger has no census, so nothing says when the sources were last checked.')
    } else {
        Add-DateProblem (Get-SourceProperty $census 'checkedAt') 'census.checkedAt'
        foreach ($skipped in @(Get-SourceProperty $census 'skipped')) {
            if ($null -ne $skipped -and "$skipped" -notin @('gitlab-code-search')) {
                $problems.Add("census.skipped names '$skipped', which is not a part of the census that can be skipped.")
            }
        }
    }
    $self = Get-SourceProperty $Ledger 'self'
    $selfKey = ConvertTo-SourceKey (Get-SourceProperty $self 'repository')
    if (-not $selfKey -or $selfKey -notlike 'github.com/*') {
        $problems.Add('self.repository must name HushGram''s own GitHub repository.')
    }

    $official = Get-SourceProperty $Ledger 'officialBundle'
    if (-not (ConvertTo-SourceKey (Get-SourceProperty $official 'repository'))) {
        $problems.Add('officialBundle.repository must name the official Morphe patch bundle.')
    }
    foreach ($package in @(Get-SourceProperty $official 'packages')) {
        if ($null -ne $package -and $packages -notcontains "$package") {
            $problems.Add("officialBundle.packages names '$package', which is not a package the census covers.")
        }
    }

    # Indexes, and whether HushGram is on each.
    $indexIds = Get-SourceIndexIds
    $seenIndexes = @{}
    foreach ($index in @(Get-SourceProperty $Ledger 'indexes')) {
        if ($null -eq $index) { continue }
        $id = [string](Get-SourceProperty $index 'id')
        $label = "Index '$id'"
        if ($indexIds -notcontains $id) {
            $problems.Add("$label is not one the audit can read ($($indexIds -join ', ')).")
            continue
        }
        if ($seenIndexes.ContainsKey($id)) { $problems.Add("$label is listed twice.") }
        $seenIndexes[$id] = $true
        if (-not (Test-SourceHttpsUrl (Get-SourceProperty $index 'url'))) { $problems.Add("$label has no https url.") }
        $listing = Get-SourceProperty $index 'hushgram'
        $status = [string](Get-SourceProperty $listing 'status')
        switch -Exact ($status) {
            'listed' {
                if (-not (Test-SourceHttpsUrl (Get-SourceProperty $listing 'url'))) {
                    $problems.Add("$label says HushGram is listed but records no https listing url.")
                }
                Add-DateProblem (Get-SourceProperty $listing 'checked') "$label listing checked date"
            }
            'submitted' {
                if (-not (Test-SourceHttpsUrl (Get-SourceProperty $listing 'url'))) {
                    $problems.Add("$label says HushGram was submitted but records no https submission url.")
                }
                Add-DateProblem (Get-SourceProperty $listing 'submitted') "$label submission date"
            }
            'not-listed' {
                Add-DateProblem (Get-SourceProperty $listing 'checked') "$label listing checked date"
            }
            default {
                $problems.Add("$label records no HushGram listing: status must be listed, submitted or not-listed, not '$status'.")
            }
        }
    }
    foreach ($id in $indexIds) {
        if (-not $seenIndexes.ContainsKey($id)) { $problems.Add("The ledger has no record of index '$id'.") }
    }

    # Every repository once, whichever list it sits in.
    $keys = @{}
    function Add-Key([string]$Repository, [string]$Where) {
        $key = ConvertTo-SourceKey $Repository
        if (-not $key) {
            $problems.Add("$Where names '$Repository', which is not a GitHub or GitLab repository address.")
            return $null
        }
        if ($keys.ContainsKey($key)) {
            $problems.Add("$Where names $key, which $($keys[$key]) already names.")
        } else {
            $keys[$key] = $Where
        }
        return $key
    }
    if ($selfKey) { $keys[$selfKey] = 'self' }

    $entriesByKey = @{}
    # Recorded forks and mirrors carry their entry's code, so they carry its disposition too.
    $copiesByKey = @{}
    $ids = @{}
    $entries = @(Get-SourceProperty $Ledger 'entries' | Where-Object { $null -ne $_ })
    if ($entries.Count -eq 0) { $problems.Add('The ledger has no entries.') }
    foreach ($entry in $entries) {
        $id = [string](Get-SourceProperty $entry 'id')
        $label = "Entry '$id'"
        if ($id -notmatch '^[a-z0-9][a-z0-9-]*$') { $problems.Add("$label needs an id of lower-case letters, digits and dashes.") }
        if ($ids.ContainsKey($id)) { $problems.Add("$label is listed twice.") }
        $ids[$id] = $true
        $repository = [string](Get-SourceProperty $entry 'repository')
        $key = Add-Key $repository $label
        if ($key) { $entriesByKey[$key] = $entry }
        if (-not (Test-SourceHttpsUrl $repository @('github.com', 'gitlab.com'))) {
            $problems.Add("$label repository must be an https GitHub or GitLab address.")
        }
        if ([string]::IsNullOrWhiteSpace([string](Get-SourceProperty $entry 'lineage'))) {
            $problems.Add("$label names no lineage, so its copies can't be told from independent work.")
        }
        $upstream = Get-SourceProperty $entry 'upstream'
        if ($null -ne $upstream -and -not (ConvertTo-SourceKey $upstream)) {
            $problems.Add("$label upstream '$upstream' is not a repository address.")
        }
        $kind = [string](Get-SourceProperty $entry 'kind')
        if ($kinds -notcontains $kind) { $problems.Add("$label kind '$kind' is not one of $($kinds -join ', ').") }

        $disposition = [string](Get-SourceProperty $entry 'disposition')
        $entryPackages = @(Get-SourceProperty $entry 'packages' | Where-Object { $null -ne $_ } | ForEach-Object { "$_" })
        if ($entryPackages.Count -eq 0) { $problems.Add("$label names no package.") }
        foreach ($package in $entryPackages) {
            if ($family -notcontains $package) {
                $problems.Add("$label names '$package', which is not Instagram or another Meta app.")
            } elseif ($packages -notcontains $package -and $disposition -ne 'adopted') {
                $problems.Add(("$label names '$package', which the census doesn't cover. Only an adopted source can stand " +
                    "in the ledger for another Meta app."))
            }
        }
        $targets = Get-SourceProperty $entry 'targetVersions'
        foreach ($package in $entryPackages) {
            $declared = Get-SourceProperty $targets $package
            if ($null -eq $targets -or $null -eq $targets.PSObject.Properties[$package]) {
                $problems.Add("$label records no declared target versions for $package (an empty list says it declares none).")
            } else {
                foreach ($version in @($declared | Where-Object { $null -ne $_ })) {
                    if ("$version" -notmatch '^\d+(\.\d+)+$') { $problems.Add("$label declares '$version' for $package, which is not a version.") }
                }
            }
        }
        if (@(Get-SourceProperty $entry 'features' | Where-Object { -not [string]::IsNullOrWhiteSpace("$_") }).Count -eq 0) {
            $problems.Add("$label names no features.")
        }

        $branches = @(Get-SourceProperty $entry 'branches' | Where-Object { $null -ne $_ })
        if ($branches.Count -eq 0) { $problems.Add("$label pins no branch.") }
        $branchNames = @{}
        foreach ($branch in $branches) {
            $name = [string](Get-SourceProperty $branch 'name')
            if (-not $name) { $problems.Add("$label has a branch with no name.") }
            elseif ($branchNames.ContainsKey($name)) { $problems.Add("$label pins branch '$name' twice.") }
            $branchNames[$name] = $true
            if ([string](Get-SourceProperty $branch 'commit') -notmatch '^[0-9a-f]{40}$') {
                $problems.Add("$label branch '$name' has no pinned commit (40 hex characters).")
            }
        }
        foreach ($path in @(Get-SourceProperty $entry 'watchPaths')) {
            if ($null -ne $path -and ("$path" -match '^[/\\]' -or "$path" -match '\.\.' -or [string]::IsNullOrWhiteSpace("$path"))) {
                $problems.Add("$label watch path '$path' must be a path inside the repository.")
            }
        }

        $license = Get-SourceProperty $entry 'license'
        $spdx = $null
        if ($null -ne $license) {
            $spdx = [string](Get-SourceProperty $license 'spdx')
            if (-not $spdx) { $problems.Add("$label has a licence with no SPDX identifier.") }
            if (-not (Test-SourceHttpsUrl (Get-SourceProperty $license 'url'))) { $problems.Add("$label licence has no https url.") }
            if ([string](Get-SourceProperty $license 'sha256') -notmatch '^[0-9a-f]{64}$') {
                $problems.Add("$label licence has no sha256 of its text.")
            }
        }
        $contamination = Get-SourceProperty $entry 'contaminatedBy'
        if ($null -ne $contamination) {
            if (-not (ConvertTo-SourceKey (Get-SourceProperty $contamination 'source'))) {
                $problems.Add("$label contaminatedBy names no source repository.")
            }
            if (-not (Test-SourceHttpsUrl (Get-SourceProperty $contamination 'evidence'))) {
                $problems.Add("$label contaminatedBy has no https evidence link.")
            }
        }
        # A relicensed source keeps each earlier licence pinned to a commit read under it, so a
        # copy taken back then is still judged by what it was offered under.
        foreach ($earlier in @(Get-SourceProperty $entry 'licenseHistory' | Where-Object { $null -ne $_ })) {
            $earlierSpdx = [string](Get-SourceProperty $earlier 'spdx')
            if (-not $earlierSpdx) { $problems.Add("$label has an earlier licence with no SPDX identifier.") }
            if (-not (Test-SourceHttpsUrl (Get-SourceProperty $earlier 'url'))) { $problems.Add("$label earlier licence $earlierSpdx has no https url.") }
            if ([string](Get-SourceProperty $earlier 'sha256') -notmatch '^[0-9a-f]{64}$') {
                $problems.Add("$label earlier licence $earlierSpdx has no sha256 of its text.")
            }
            if ([string](Get-SourceProperty $earlier 'commit') -notmatch '^[0-9a-f]{40}$') {
                $problems.Add("$label earlier licence $earlierSpdx is pinned to no commit read under it.")
            }
            Add-DateProblem (Get-SourceProperty $earlier 'until') "$label earlier licence $earlierSpdx until"
        }
        # Code a source took from another lineage (HushGram's downloader in InstaEclipse) is that
        # lineage's work, so it never counts as independent evidence for this one.
        foreach ($inherited in @(Get-SourceProperty $entry 'inherits' | Where-Object { $null -ne $_ })) {
            $inheritedSource = [string](Get-SourceProperty $inherited 'source')
            if (-not (ConvertTo-SourceKey $inheritedSource)) { $problems.Add("$label inherits code from no source repository.") }
            if ([string]::IsNullOrWhiteSpace([string](Get-SourceProperty $inherited 'lineage'))) {
                $problems.Add("$label inherits code from $inheritedSource with no lineage named.")
            }
            if (-not (Test-SourceHttpsUrl (Get-SourceProperty $inherited 'evidence'))) {
                $problems.Add("$label inherits code from $inheritedSource with no https evidence link.")
            }
            $inheritedPaths = @(Get-SourceProperty $inherited 'paths' | Where-Object { $null -ne $_ })
            if ($inheritedPaths.Count -eq 0) { $problems.Add("$label inherits code from $inheritedSource without saying where it sits.") }
            foreach ($path in $inheritedPaths) {
                if ("$path" -match '^[/\\]' -or "$path" -match '\.\.' -or [string]::IsNullOrWhiteSpace("$path")) {
                    $problems.Add("$label inherited path '$path' must be a path inside the repository.")
                }
            }
        }
        if ($dispositions -notcontains $disposition) {
            $problems.Add("$label disposition '$disposition' is not one of $($dispositions -join ', ').")
        }
        $licensed = $null -ne $license -and $compatible -contains $spdx
        if ($null -eq $license -and $disposition -ne 'behavior-only') {
            $problems.Add("$label has no licence, so it is behavior-only, not $disposition.")
        } elseif ($null -ne $license -and -not $licensed -and $disposition -ne 'behavior-only') {
            $problems.Add("$label is licensed $spdx, which can't be combined with GPL-3.0, so it is behavior-only, not $disposition.")
        }
        if ($null -ne $contamination -and $disposition -ne 'behavior-only') {
            $problems.Add("$label carries code from $(Get-SourceProperty $contamination 'source'), so it is behavior-only, not $disposition.")
        }
        if ([string]::IsNullOrWhiteSpace([string](Get-SourceProperty $entry 'reason'))) {
            $problems.Add("$label gives no reason for its disposition.")
        }
        if ((Get-SourceProperty $entry 'archived') -isnot [bool]) { $problems.Add("$label must say whether it is archived.") }
        foreach ($fork in @(Get-SourceProperty $entry 'forks')) {
            if ($null -ne $fork -and "$fork" -notmatch '^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+$') {
                $problems.Add("$label fork '$fork' must be written owner/name.")
            } elseif ($null -ne $fork -and $key) {
                $copiesByKey["$(($key -split '/')[0])/$fork".ToLowerInvariant()] = [pscustomobject]@{ Entry = $entry; What = 'fork' }
            }
        }
        foreach ($hash in @(Get-SourceProperty $entry 'contentHashes')) {
            if ($null -ne $hash -and "$hash" -notmatch '^[0-9a-f]{40}$') { $problems.Add("$label content hash '$hash' is not a git blob id.") }
        }
        foreach ($mirror in @(Get-SourceProperty $entry 'mirrors' | Where-Object { $null -ne $_ })) {
            $mirrorRepository = [string](Get-SourceProperty $mirror 'repository')
            $mirrorKey = Add-Key $mirrorRepository "$label mirror"
            if ($mirrorKey) { $copiesByKey[$mirrorKey] = [pscustomobject]@{ Entry = $entry; What = 'mirror' } }
            if ([string]::IsNullOrWhiteSpace([string](Get-SourceProperty $mirror 'reason'))) {
                $problems.Add("$label mirror $mirrorRepository gives no reason it is a copy.")
            }
            foreach ($hash in @(Get-SourceProperty $mirror 'blobs')) {
                if ($null -ne $hash -and "$hash" -notmatch '^[0-9a-f]{40}$') { $problems.Add("$label mirror $mirrorRepository blob '$hash' is not a git blob id.") }
            }
        }
        Add-DateProblem (Get-SourceProperty $entry 'lastChecked') "$label lastChecked"

        $adopted = Get-SourceProperty $entry 'adopted'
        if ($disposition -ne 'adopted' -and $null -ne $adopted) {
            $problems.Add("$label records adoption evidence but is $disposition.")
        }
    }
    foreach ($item in @(Get-SourceProperty $Ledger 'outOfScope' | Where-Object { $null -ne $_ })) {
        $repository = [string](Get-SourceProperty $item 'repository')
        Add-Key $repository 'outOfScope' | Out-Null
        if ([string]::IsNullOrWhiteSpace([string](Get-SourceProperty $item 'reason'))) {
            $problems.Add("outOfScope $repository gives no reason.")
        }
        Add-DateProblem (Get-SourceProperty $item 'lastChecked') "outOfScope $repository lastChecked"
    }

    # The adopted gates, and provenance.json held to the ledger.
    $adoptedEntries = @($entries | Where-Object { [string](Get-SourceProperty $_ 'disposition') -eq 'adopted' })
    $noticePath = Join-Path $Root 'NOTICE'
    $provenancePath = Join-Path $Root 'provenance.json'
    $catalogPath = Join-Path $Root 'patches-list.json'
    $notice = if (Test-Path -LiteralPath $noticePath -PathType Leaf) { [System.IO.File]::ReadAllText($noticePath) } else { $null }
    $rules = @()
    if (Test-Path -LiteralPath $provenancePath -PathType Leaf) {
        try {
            $rules = @(([System.IO.File]::ReadAllText($provenancePath) | ConvertFrom-Json).rules | Where-Object { $null -ne $_ })
        } catch {
            $problems.Add("provenance.json is not JSON: $($_.Exception.Message)")
        }
    } else {
        $problems.Add("provenance.json is missing, so the ledger can't be held to what HushGram ships.")
    }
    $declaredBuilds = @()
    if ($adoptedEntries.Count -gt 0) {
        try {
            $declaredBuilds = @((Get-PatchTarget -PatchList ([System.IO.File]::ReadAllText($catalogPath) | ConvertFrom-Json)).PackageVersions)
        } catch {
            $problems.Add("The declared Instagram builds could not be read from patches-list.json: $($_.Exception.Message)")
        }
        if ($declaredBuilds.Count -eq 0) {
            $problems.Add('patches-list.json declares no Instagram build, so no adopted source can be proved on one.')
        }
    }
    foreach ($entry in $adoptedEntries) {
        $label = "Adopted entry '$(Get-SourceProperty $entry 'id')'"
        $repository = [string](Get-SourceProperty $entry 'repository')
        $key = ConvertTo-SourceKey $repository
        $adopted = Get-SourceProperty $entry 'adopted'
        $commit = [string](Get-SourceProperty $adopted 'commit')
        if ($commit -notmatch '^[0-9a-f]{40}$') {
            $problems.Add("$label has no pinned commit its code was taken from (adopted.commit, 40 hex characters).")
        }
        $license = Get-SourceProperty $entry 'license'
        if ($null -eq $license -or (Get-SourceCompatibleLicenses) -notcontains [string](Get-SourceProperty $license 'spdx')) {
            $problems.Add("$label has no licence compatible with GPL-3.0.")
        }
        if ($null -eq $notice -or $notice.IndexOf($repository.TrimEnd('/'), [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            $problems.Add("$label is not in NOTICE: $repository has to be named there.")
        }
        $rule = @($rules | Where-Object {
            [string](Get-SourceProperty $_ 'origin') -eq 'ported' -and
            (ConvertTo-SourceKey (Get-SourceProperty $_ 'upstream')) -eq $key -and
            [string](Get-SourceProperty $_ 'commit') -eq $commit })
        if ($rule.Count -eq 0) {
            $problems.Add("$label has no provenance rule: provenance.json needs a ported rule with upstream $repository and commit $commit.")
        }
        # HushGram publishes no release receipts, so the evidence is the fixture builds its patches
        # applied to unforced (verify-all-patches.ps1), and they have to be every declared build.
        $fixtures = Get-SourceProperty $adopted 'fixtures'
        $builds = @(Get-SourceProperty $fixtures 'builds' | Where-Object { $null -ne $_ } | ForEach-Object { "$_" } | Sort-Object -Unique)
        $unproved = @($declaredBuilds | Where-Object { $builds -notcontains $_ })
        if ($builds.Count -eq 0 -or $unproved.Count -gt 0) {
            $missing = if ($unproved.Count -gt 0) { $unproved -join ', ' } else { 'every declared build' }
            $problems.Add("$label lacks fixture evidence: it names $(@($builds).Count) build(s) and is missing $missing.")
        }
    }
    foreach ($rule in $rules) {
        $origin = [string](Get-SourceProperty $rule 'origin')
        $upstreamKey = ConvertTo-SourceKey (Get-SourceProperty $rule 'upstream')
        if ($upstreamKey -and $entriesByKey.ContainsKey($upstreamKey)) {
            $entry = $entriesByKey[$upstreamKey]
            $disposition = [string](Get-SourceProperty $entry 'disposition')
            if ($origin -eq 'ported' -and $disposition -ne 'adopted') {
                $problems.Add(("provenance.json ports files from $(Get-SourceProperty $rule 'upstream'), which the ledger " +
                    "lists as $disposition, not adopted."))
            }
        } elseif ($upstreamKey -and $copiesByKey.ContainsKey($upstreamKey) -and $origin -eq 'ported') {
            # Only an entry is pinned, licensed and proved on the declared builds; its copies are none of those.
            $copy = $copiesByKey[$upstreamKey]
            $problems.Add(("provenance.json ports files from $(Get-SourceProperty $rule 'upstream'), which the ledger records " +
                "only as a $($copy.What) of $(Get-SourceProperty $copy.Entry 'repository') " +
                "($(Get-SourceProperty $copy.Entry 'disposition')). Port from an adopted entry."))
        }
        foreach ($via in @(Get-SourceProperty $rule 'via')) {
            $viaKey = ConvertTo-SourceKey $via
            $viaEntry = $null
            $through = ''
            if ($viaKey -and $entriesByKey.ContainsKey($viaKey)) {
                $viaEntry = $entriesByKey[$viaKey]
            } elseif ($viaKey -and $copiesByKey.ContainsKey($viaKey)) {
                $viaEntry = $copiesByKey[$viaKey].Entry
                $through = " as a $($copiesByKey[$viaKey].What) of $(Get-SourceProperty $viaEntry 'repository'),"
            }
            if ($null -ne $viaEntry) {
                $disposition = [string](Get-SourceProperty $viaEntry 'disposition')
                if ($disposition -notin @('adopted', 'candidate')) {
                    $problems.Add("provenance.json credits $via for shipped files, but the ledger lists it$through as $disposition.")
                }
            }
        }
    }

    return [pscustomobject]@{ Valid = ($problems.Count -eq 0); Problems = [string[]]$problems.ToArray() }
}

function Test-SourceCensus {
    <#
    .SYNOPSIS
        Whether the census is current: at most Get-SourceCensusMaxAgeDays old, and not dated in
        the future. The audit's -ValidateOnly run reports it.
    #>
    param([Parameter(Mandatory = $true)]$Ledger, [string]$Today)

    $todayDate = Get-SourceToday $Today
    $checked = Test-SourceDate (Get-SourceProperty (Get-SourceProperty $Ledger 'census') 'checkedAt')
    $limit = Get-SourceCensusMaxAgeDays
    if ($null -eq $checked) {
        return [pscustomobject]@{ Valid = $false; AgeDays = $null; CheckedAt = $null
            Reason = 'the ledger records no census date' }
    }
    $age = [int]($todayDate - $checked).TotalDays
    $checkedText = $checked.ToString('yyyy-MM-dd')
    if ($age -lt 0) {
        return [pscustomobject]@{ Valid = $false; AgeDays = $age; CheckedAt = $checkedText
            Reason = "the census is dated $checkedText, after today" }
    }
    if ($age -gt $limit) {
        return [pscustomobject]@{ Valid = $false; AgeDays = $age; CheckedAt = $checkedText
            Reason = ("the census is $age days old (checked $checkedText) and counts as current for $limit days. " +
                'Run scripts/audit-instagram-sources.ps1, settle what it reports, and commit the ledger it stamps') }
    }
    return [pscustomobject]@{ Valid = $true; AgeDays = $age; CheckedAt = $checkedText; Reason = $null }
}
