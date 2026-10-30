<#
.SYNOPSIS
    Refresh the Instagram source census and report every way the sources moved.

.DESCRIPTION
    Holds sources/instagram-sources.json to what the indexes and the forges say today, and changes
    nothing but dates. It reads:

    - the official Morphe patch bundle's patches-list.json, which has shipped no Instagram patch
      so far;
    - the Morphe community directory, Awesome Morphe, the Morphe Patch Tracker and Jman's bundle
      index, for every bundle that targets com.instagram.android and for whether HushGram is listed
      on each, plus the Morphe Archive for HushGram's own listing;
    - GitHub code search for the package name in patch lists, patch code and Xposed hooks, with the
      two archive mirrors mapped back to the repositories they copied, and GitLab code search when
      GITLAB_TOKEN is set and -SkipGitLabCodeSearch isn't passed;
    - every ledger source's repository, licence, branches and forks. A source with watchPaths is
      read through the newest commit that touched those paths, so a busy multi-app repository only
      counts as moved when its Instagram code did (or, for an adopted Facebook source, the files
      HushGram took from it).

    A repository the ledger doesn't know is an addition, unless every file code search found in it
    is byte for byte a file the ledger already holds (the same git blob id), which makes it a copy
    of that lineage: it's reported under that lineage and not counted again. A removed or changed
    licence, a branch head that moved or a new branch with unpinned work, a fork the ledger hasn't
    seen, a recorded fork or out-of-scope repository an index lists as a bundle of its own, a
    repository that's gone, renamed or archived, and a listing that changed are the other findings.
    Any finding, or a source that couldn't be read, writes the report and exits 1 with the ledger
    untouched. A clean run stamps today's date on the census and on every record it checked, and
    exits 0. Commit that change: -ValidateOnly calls a census more than 14 days old stale.

    The audit never imports code. It reads metadata through the forges' APIs; a licence is hashed
    in memory and a patch list is read for its names. Nothing is cloned, downloaded to disk or
    written anywhere but the report and the ledger's dates. The report can't go under patches/ or
    extensions/.

    GitHub requests use GITHUB_TOKEN or the gh CLI's token (code search needs one). GitLab's code
    search needs GITLAB_TOKEN; without one, pass -SkipGitLabCodeSearch and the census records the
    skip. Tokens go in request headers only, never into the report or the ledger.

.EXAMPLE
    pwsh -File scripts/audit-instagram-sources.ps1 -SkipGitLabCodeSearch
.EXAMPLE
    pwsh -File scripts/audit-instagram-sources.ps1 -ValidateOnly

.NOTES
    Taken from Hushfacebook's scripts/audit-facebook-sources.ps1
    (https://github.com/SysAdminDoc/Hushfacebook, commit 3a47363954eea853357e71e7cf3951a5ee984cee).
    GPL-3.0-only. Modified for HushGram (Instagram), 2026.
#>
[CmdletBinding()]
param(
    [string]$Root,
    # Defaults to sources/instagram-sources.json under -Root.
    [string]$Ledger,
    # Defaults to a dated file in the temporary folder.
    [string]$ReportPath,
    # Only the offline rules, with no network. The pre-push suite runs this.
    [switch]$ValidateOnly,
    # Report without stamping the ledger, even when nothing moved.
    [switch]$NoStamp,
    [switch]$SkipGitLabCodeSearch,
    [string]$GitHubToken,
    [string]$GitLabToken,
    # The date the census is stamped with and ledger dates are held to, yyyy-MM-dd. UTC today by default.
    [string]$Today,
    # GitHub allows ten code searches a minute.
    [double]$SearchDelaySeconds = 7,
    # How long to wait out a rate limit before trying again, when the forge doesn't say.
    [int]$RateLimitWaitSeconds = 61,
    # The longest Retry-After a limited request waits for. GitLab's per-address limit on reads
    # without a token runs by the hour, so a longer one gives up on that request at once, and the
    # failure says when the limit resets.
    [int]$MaxRateLimitWaitSeconds = 300,
    # How long to wait after a request that got no answer at all (a timeout or a dropped
    # connection) before asking again. A request gets three tries.
    [int]$RetryWaitSeconds = 15
)

$ErrorActionPreference = 'Stop'
# Not a parameter default: Windows PowerShell leaves $PSScriptRoot empty while it evaluates the
# defaults of an advanced script started with -File.
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'instagram-sources.ps1')

if ($PSVersionTable.PSVersion.Major -lt 6) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
}

$rootPath = (Resolve-Path -LiteralPath $Root).Path
$ledgerPath = if ($Ledger) { $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Ledger) } else {
    Get-SourceLedgerPath -Root $rootPath
}
$todayDate = Get-SourceToday $Today
$todayText = $todayDate.ToString('yyyy-MM-dd')

if (-not $ReportPath) {
    $ReportPath = Join-Path ([IO.Path]::GetTempPath()) ("hushgram-source-audit-" +[datetime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '.json')
}
$ReportPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ReportPath)
foreach ($guarded in @('patches', 'extensions')) {
    $guardedPath = [IO.Path]::GetFullPath((Join-Path $rootPath $guarded)).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ([IO.Path]::GetFullPath($ReportPath).StartsWith($guardedPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "The audit report can't go under $guarded/, where the shipped sources live: $ReportPath"
    }
}
if ($ReportPath -match '(?i)\.(kt|kts|java|smali)$') {
    throw "The audit report is JSON, not source code: $ReportPath"
}

$findings = New-Object System.Collections.Generic.List[object]
$notes = New-Object System.Collections.Generic.List[string]
$sourceStatus = New-Object System.Collections.Generic.List[object]
$requestCount = 0

function Write-Step {
    param([string]$Message)
    Write-Host "[sources] $Message"
}

function Add-Finding {
    param([string]$Kind, [string]$Repository, [string]$Detail, $Evidence)
    $findings.Add([pscustomobject][ordered]@{ kind = $Kind; repository = $Repository; detail = $Detail; evidence = $Evidence })
}

function Save-Report {
    param([bool]$Clean, $Counts, $Lineages, $KnownHits)
    $report = [ordered]@{
        generatedAt = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        today = $todayText
        ledger = $ledgerPath
        clean = $Clean
        counts = $Counts
        sources = $sourceStatus.ToArray()
        lineages = @($Lineages | Where-Object { $null -ne $_ })
        findings = $findings.ToArray()
        notes = $notes.ToArray()
        knownHits = @($KnownHits | Where-Object { $null -ne $_ })
    }
    $directory = Split-Path -Parent $ReportPath
    if ($directory -and -not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    [IO.File]::WriteAllText($ReportPath, ($report | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding $false))
    Write-Step "report: $ReportPath"
}

# --- the ledger's own rules ------------------------------------------------------------------

$ledgerDocument = Read-SourceLedger -Path $ledgerPath
$rules = Test-SourceLedger -Ledger $ledgerDocument -Root $rootPath -Today $todayText
if (-not $rules.Valid) {
    foreach ($problem in $rules.Problems) {
        Write-Step "ledger: $problem"
        Add-Finding -Kind 'ledger-invalid' -Repository $null -Detail $problem -Evidence $null
    }
    Save-Report -Clean $false -Counts ([ordered]@{ findings = $findings.Count }) -Lineages @()
    Write-Step "the ledger breaks $($rules.Problems.Count) rule(s), so nothing was fetched"
    exit 1
}
$entries = @(Get-SourceProperty $ledgerDocument 'entries' | Where-Object { $null -ne $_ })
$lineageNames = @($entries | ForEach-Object { [string]$_.lineage } | Sort-Object -Unique)
if ($ValidateOnly) {
    $census = Test-SourceCensus -Ledger $ledgerDocument -Today $todayText
    $censusLine = if ($census.Valid) { "the census is $($census.AgeDays) day(s) old" } else { $census.Reason }
    Write-Step ("the ledger keeps its rules: $($entries.Count) sources in $($lineageNames.Count) lineages; " + $censusLine)
    exit 0
}

# --- tokens ----------------------------------------------------------------------------------

if (-not $GitHubToken) { $GitHubToken = $env:GITHUB_TOKEN }
if (-not $GitHubToken -and (Get-Command gh -ErrorAction SilentlyContinue)) {
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $GitHubToken = "$(& gh auth token 2>$null)".Trim() } finally { $ErrorActionPreference = $preference }
}
if (-not $GitHubToken) {
    throw 'GitHub code search needs a token. Run gh auth login, or set GITHUB_TOKEN.'
}
if (-not $GitLabToken) { $GitLabToken = $env:GITLAB_TOKEN }
if (-not $GitLabToken -and -not $SkipGitLabCodeSearch) {
    throw ('GitLab code search needs a token. Set GITLAB_TOKEN, or pass -SkipGitLabCodeSearch and the ' +
        'census records that GitLab code was not searched.')
}
# The switch wins over a token in the environment, which still authenticates the ledger's GitLab reads.
$searchGitLab = [bool]$GitLabToken -and -not $SkipGitLabCodeSearch

# --- requests --------------------------------------------------------------------------------

function Get-RetryAfterSeconds {
    # How long a limited answer's Retry-After asks for, in whole seconds, or $null when it names nothing.
    param($Response)
    $headers = $Response.PSObject.Properties['Headers']
    if ($null -eq $headers -or $null -eq $headers.Value) { return $null }
    $hint = $headers.Value.PSObject.Properties['RetryAfter']
    if ($null -eq $hint -or $null -eq $hint.Value) { return $null }
    $seconds = $null
    if ($null -ne $hint.Value.Delta) { $seconds = $hint.Value.Delta.TotalSeconds }
    elseif ($null -ne $hint.Value.Date) { $seconds = ($hint.Value.Date - [DateTimeOffset]::UtcNow).TotalSeconds }
    if ($null -eq $seconds) { return $null }
    # A reset decades out still has to fit an [int], or reading it throws inside the request's catch.
    return [int][Math]::Min([double][int]::MaxValue, [Math]::Max([double]0, [Math]::Ceiling([double]$seconds)))
}

function Get-RequestFault {
    # Why a request that didn't answer 200 left its source unread: the status, or the limit still
    # in force and when to audit again.
    param($Answer, [string]$What)
    if (-not $Answer.Limited) { return "$What answered HTTP $($Answer.Status)" }
    $when = 'once it resets'
    if ($null -ne $Answer.ResetsAt) {
        # Rounded up to the minute, so the time named is never before the reset.
        $minute = [DateTime]::new($Answer.ResetsAt.Ticks - ($Answer.ResetsAt.Ticks % [TimeSpan]::TicksPerMinute), [DateTimeKind]::Utc)
        if ($minute -lt $Answer.ResetsAt) { $minute = $minute.AddMinutes(1) }
        $when = "after $($minute.ToString('yyyy-MM-dd HH:mm', [Globalization.CultureInfo]::InvariantCulture)) UTC, when it resets"
    }
    # GitLab counts reads without a token per address and GitHub counts per token, so this names neither.
    return "$($Answer.Host) was still rate limiting the audit (HTTP $($Answer.Status)) when it read $What; audit again $when"
}

function Invoke-SourceRequest {
    <#
        One GET, answering Status and Content. A 4xx or 5xx answer comes back as its status with no
        content, since a repository that's gone is a finding and not a crash; only a request that
        never got an answer throws, and only after its third try. A rate limit is waited out, three
        times at most, for as long as the forge's Retry-After asks when that's within
        -MaxRateLimitWaitSeconds. One still in force after that, or asking for longer, comes back
        with Limited set and, when the forge said, ResetsAt.
    #>
    param([Parameter(Mandatory = $true)][string]$Uri, [string]$Auth = 'plain')

    $headers = @{}
    if ($Auth -eq 'github') { $headers['Authorization'] = "Bearer $GitHubToken" }
    if ($Auth -eq 'gitlab' -and $GitLabToken) { $headers['PRIVATE-TOKEN'] = $GitLabToken }
    for ($attempt = 1; ; $attempt++) {
        $script:requestCount++
        try {
            $response = Invoke-WebRequest -Uri $Uri -Headers $headers -UserAgent 'HushGram-source-audit' `
                -UseBasicParsing -TimeoutSec 120
            $content = $null
            $raw = $response.PSObject.Properties['RawContentStream']
            if ($null -ne $raw -and $null -ne $raw.Value) {
                $content = [Text.Encoding]::UTF8.GetString($raw.Value.ToArray())
            } else {
                $content = $response.Content
                if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
            }
            return [pscustomobject]@{ Status = [int]$response.StatusCode; Content = [string]$content }
        } catch {
            $status = $null
            $answer = $_.Exception.PSObject.Properties['Response']
            if ($null -ne $answer -and $null -ne $answer.Value -and $null -ne $answer.Value.StatusCode) {
                $status = [int]$answer.Value.StatusCode
            }
            if ($null -eq $status) {
                if ($attempt -lt 3) {
                    Write-Step "no answer from $([Uri]$Uri | ForEach-Object { $_.Host }), asking again in $RetryWaitSeconds s"
                    Start-Sleep -Seconds $RetryWaitSeconds
                    continue
                }
                throw "Could not reach ${Uri} after three tries: $($_.Exception.Message)"
            }
            $said = "$($_.ErrorDetails.Message) $($_.Exception.Message)"
            $limited = $status -eq 429 -or ($status -eq 403 -and $said -match '(?i)rate limit')
            $retryAfter = if ($limited) { Get-RetryAfterSeconds $answer.Value } else { $null }
            $wait = if ($null -ne $retryAfter) { [Math]::Max($retryAfter, 1) } else { $RateLimitWaitSeconds }
            # The cap is for what a forge asks; the audit's own wait is always taken.
            if ($limited -and $attempt -lt 4 -and ($null -eq $retryAfter -or $wait -le $MaxRateLimitWaitSeconds)) {
                Write-Step "rate limited on $(([Uri]$Uri).Host), waiting $wait s"
                Start-Sleep -Seconds $wait
                continue
            }
            $resetsAt = if ($null -ne $retryAfter) { [DateTime]::UtcNow.AddSeconds($retryAfter) } else { $null }
            return [pscustomobject]@{ Status = $status; Content = $null; Limited = $limited; ResetsAt = $resetsAt; Host = ([Uri]$Uri).Host }
        }
    }
}

function ConvertFrom-SourceJson {
    # -AsHashtable is for a document whose keys differ only in case (the Morphe Patch Tracker lists
    # immortalzeus:dev and ImmortalZeus:dev), which PowerShell 7 can only read as a case-sensitive map.
    # Windows PowerShell has no such switch and reads the document as it always did.
    param([string]$Text, [string]$What, [switch]$AsHashtable)
    try {
        if ($AsHashtable -and $PSVersionTable.PSVersion.Major -ge 6) { return , ($Text | ConvertFrom-Json -AsHashtable) }
        return , ($Text | ConvertFrom-Json)
    } catch {
        throw "$What did not answer JSON: $($_.Exception.Message)"
    }
}

function Get-JsonItems {
    # The elements of a parsed JSON array, one at a time, the same way in both editions: Windows
    # PowerShell hands a top-level array over as one object, PowerShell 7 one element at a time.
    # Callers collect them with @() or foreach.
    param($Parsed)
    foreach ($item in $Parsed) { if ($null -ne $item) { $item } }
}

function Get-SourceJson {
    param([string]$Uri, [string]$Auth = 'plain', [string]$What = $Uri, [switch]$AsHashtable)
    $answer = Invoke-SourceRequest -Uri $Uri -Auth $Auth
    if ($answer.Status -ne 200) { throw "$(Get-RequestFault $answer $What)." }
    return , (ConvertFrom-SourceJson -Text $answer.Content -What $What -AsHashtable:$AsHashtable)
}

function Get-JsonEntries {
    # The name and value of every key of a parsed JSON object, whether it came back as an object or,
    # for a document read with -AsHashtable, as a map.
    param($Parsed)
    if ($Parsed -is [System.Collections.IDictionary]) {
        foreach ($name in $Parsed.Keys) { [pscustomobject]@{ Name = [string]$name; Value = $Parsed[$name] } }
    } else {
        foreach ($property in $Parsed.PSObject.Properties) { [pscustomobject]@{ Name = $property.Name; Value = $property.Value } }
    }
}

function Get-PagedItems {
    <#
        Every page of a GitHub or GitLab list, a hundred at a time. Status is the first page's
        answer, with its limit if it had one; Items is $null unless that was 200.
    #>
    param([string]$Uri, [string]$Auth)
    $all = New-Object System.Collections.Generic.List[object]
    for ($page = 1; $page -le 50; $page++) {
        $separator = if ($Uri.Contains('?')) { '&' } else { '?' }
        $answer = Invoke-SourceRequest -Uri "$Uri${separator}per_page=100&page=$page" -Auth $Auth
        if ($answer.Status -ne 200) {
            if ($page -eq 1) {
                return [pscustomobject]@{ Status = $answer.Status; Items = $null; Limited = $answer.Limited; ResetsAt = $answer.ResetsAt; Host = $answer.Host }
            }
            throw "$(Get-RequestFault $answer "page $page of $Uri")."
        }
        $items = @(Get-JsonItems (ConvertFrom-SourceJson -Text $answer.Content -What $Uri))
        foreach ($item in $items) { $all.Add($item) }
        if ($items.Count -lt 100) { break }
    }
    return [pscustomobject]@{ Status = 200; Items = $all }
}

function Get-Sha256Hex {
    param([byte[]]$Bytes)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { return -join ($hasher.ComputeHash($Bytes) | ForEach-Object { $_.ToString('x2') }) } finally { $hasher.Dispose() }
}

function Split-SourceKey {
    param([string]$Key)
    $at = $Key.IndexOf('/')
    return [pscustomobject]@{ Host = $Key.Substring(0, $at); Path = $Key.Substring($at + 1) }
}

function Get-EncodedPath {
    param([string]$Path)
    return (@($Path -split '/' | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/')
}

# --- what the ledger already knows ------------------------------------------------------------

$packages = Get-SourcePackages
$selfKey = ConvertTo-SourceKey $ledgerDocument.self.repository
$selfOwner = ($selfKey -split '/')[1]
$known = @{}
$knownBlobs = @{}
foreach ($entry in $entries) {
    $key = ConvertTo-SourceKey $entry.repository
    $known[$key] = [pscustomobject]@{ Kind = 'entry'; Lineage = [string]$entry.lineage; Entry = $entry; Record = $entry }
    $entryHost = (Split-SourceKey $key).Host
    foreach ($hash in @(Get-SourceProperty $entry 'contentHashes' | Where-Object { $_ })) { $knownBlobs["$hash"] = [string]$entry.lineage }
    foreach ($mirror in @(Get-SourceProperty $entry 'mirrors' | Where-Object { $_ })) {
        $known[(ConvertTo-SourceKey $mirror.repository)] = [pscustomobject]@{ Kind = 'mirror'; Lineage = [string]$entry.lineage; Entry = $entry; Record = $mirror }
        foreach ($hash in @(Get-SourceProperty $mirror 'blobs' | Where-Object { $_ })) { $knownBlobs["$hash"] = [string]$entry.lineage }
    }
    foreach ($fork in @(Get-SourceProperty $entry 'forks' | Where-Object { $_ })) {
        $forkKey = "$entryHost/$fork".ToLowerInvariant()
        if (-not $known.ContainsKey($forkKey)) {
            $known[$forkKey] = [pscustomobject]@{ Kind = 'fork'; Lineage = [string]$entry.lineage; Entry = $entry; Record = $null }
        }
    }
}
foreach ($item in @(Get-SourceProperty $ledgerDocument 'outOfScope' | Where-Object { $_ })) {
    $known[(ConvertTo-SourceKey $item.repository)] = [pscustomobject]@{ Kind = 'outOfScope'; Lineage = $null; Entry = $null; Record = $item }
}
# The indexes' own data repositories, read below as indexes. A code search hit in one is a copy of
# a bundle's patch list, never a source of its own.
$indexRepositories = @('github.com/nvbangg/awesome-morphe', 'github.com/drnx64/morphe-track-patches',
    'github.com/jman-github/revanced-patch-bundles', 'github.com/jman-github/patch-bundle-registry', 'github.com/rushiforai/morphe-archive', 'github.com/rushiforai/revanced-archive')
# The two archives keep examplepatches/<owner>/<name>/patches-list.json for every bundle they
# found, which is a pointer back to that repository.
$archiveRepositories = @('github.com/rushiforai/morphe-archive', 'github.com/rushiforai/revanced-archive')

$candidates = @{}
function Add-Candidate {
    <#
        Packages are what an index says a bundle targets. Code search only proves a file names the
        package, which a clone patch listing apps it won't clone does too, so its packages are Mentions.
    #>
    param([string]$Key, [string]$Source, [string[]]$Packages, [string[]]$Mentions, [string]$Path, [string]$Blob, [string[]]$Features)
    if (-not $Key) { return }
    if (-not $candidates.ContainsKey($Key)) {
        $candidates[$Key] = [pscustomobject]@{
            Key = $Key
            Sources = New-Object System.Collections.Generic.List[string]
            Packages = New-Object System.Collections.Generic.List[string]
            Mentions = New-Object System.Collections.Generic.List[string]
            Hits = New-Object System.Collections.Generic.List[object]
            Features = New-Object System.Collections.Generic.List[string]
        }
    }
    $candidate = $candidates[$Key]
    if ($Source -and -not $candidate.Sources.Contains($Source)) { $candidate.Sources.Add($Source) }
    foreach ($package in @($Packages)) { if ($package -and -not $candidate.Packages.Contains($package)) { $candidate.Packages.Add($package) } }
    foreach ($package in @($Mentions)) { if ($package -and -not $candidate.Mentions.Contains($package)) { $candidate.Mentions.Add($package) } }
    foreach ($feature in @($Features)) { if ($feature -and -not $candidate.Features.Contains($feature)) { $candidate.Features.Add($feature) } }
    if ($Path) { $candidate.Hits.Add([pscustomobject][ordered]@{ path = $Path; blob = $Blob }) }
}

$listed = @{}
function Invoke-Source {
    # Runs one reader. A reader that throws is recorded as failed, which fails the audit, and the
    # others still run so the report shows everything that could be read.
    param([string]$Name, [scriptblock]$Read)
    try {
        $said = & $Read
        $sourceStatus.Add([pscustomobject][ordered]@{ source = $Name; status = 'read'; detail = "$said" })
        Write-Step "$Name`: $said"
    } catch {
        $sourceStatus.Add([pscustomobject][ordered]@{ source = $Name; status = 'failed'; detail = $_.Exception.Message })
        Add-Finding -Kind 'source-failed' -Repository $null -Detail "$Name could not be read: $($_.Exception.Message)" -Evidence $null
        Write-Step "$Name FAILED: $($_.Exception.Message)"
    }
}

# --- the official bundle and the indexes -------------------------------------------------------

$official = $ledgerDocument.officialBundle
Invoke-Source 'official Morphe bundle' {
    $officialKey = Split-SourceKey (ConvertTo-SourceKey $official.repository)
    $list = Get-SourceJson -Uri ("https://raw.githubusercontent.com/$($officialKey.Path)/" +
        "$(Get-EncodedPath ([string]$official.branch))/$(Get-EncodedPath ([string]$official.list))") -What 'the official patch list'
    $found = New-Object System.Collections.Generic.List[string]
    foreach ($patch in (Get-JsonItems $list.patches)) {
        # Assigned in place, not returned from a function or an if: either one hands a one-element
        # array back as its element, and the list form would then be read as the map form.
        $property = $patch.PSObject.Properties['compatiblePackages']
        $compatible = $null
        if ($null -ne $property) { $compatible = $property.Value }
        $names = @()
        if ($null -ne $compatible) {
            if ($compatible -is [array] -or $compatible -is [System.Collections.IList]) {
                # The list form Morphe writes now: [{"packageName": ...}, ...].
                $names = @($compatible | ForEach-Object { if ($_ -is [string]) { $_ } else { Get-SourceProperty $_ 'packageName' } })
            } else {
                # The map form: {"com.example": ["1.0"]}.
                $names = @($compatible.PSObject.Properties | ForEach-Object { $_.Name })
            }
        }
        foreach ($name in $names) { if ($packages -contains $name -and -not $found.Contains($name)) { $found.Add($name) } }
    }
    $recorded = @($official.packages | Where-Object { $_ } | ForEach-Object { "$_" })
    $added = @($found | Where-Object { $recorded -notcontains $_ })
    $dropped = @($recorded | Where-Object { $found -notcontains $_ })
    if ($added.Count -gt 0 -or $dropped.Count -gt 0) {
        Add-Finding -Kind 'official-bundle-changed' -Repository $official.repository `
            -Detail ("the official bundle now patches [" + ($found -join ', ') + "], the ledger records [" + ($recorded -join ', ') + ']') `
            -Evidence ([ordered]@{ added = $added; dropped = $dropped })
    }
    "$(@((Get-JsonItems $list.patches)).Count) patches, Instagram packages: " + $(if ($found.Count) { $found -join ', ' } else { 'none' })
}

Invoke-Source 'Morphe directory' {
    $directory = Get-SourceJson -Uri 'https://morphe-patches.software/data/bundles.json' -What 'the Morphe directory'
    $compatibilities = @(Get-JsonItems $directory.compatibilities)
    $count = 0
    $listed['morphe-directory'] = $false
    foreach ($bundle in (Get-JsonItems $directory.bundles)) {
        $forge = switch ("$($bundle.source)") { 'github' { 'github.com' } 'gitlab' { 'gitlab.com' } default { $null } }
        $key = if ($forge) { ConvertTo-SourceKey "https://$forge/$($bundle.repo)" } else { $null }
        if ($key -and $key -eq $selfKey) { $listed['morphe-directory'] = $true }
        $apps = @($bundle.targetApps | Where-Object { $packages -contains $_ })
        if ($apps.Count -eq 0) { continue }
        $features = @(Get-JsonItems $bundle.patches | Where-Object {
            $index = Get-SourceProperty $_ 'compatiblePackagesKey'
            $null -ne $index -and [int]$index -lt $compatibilities.Count -and
                @($compatibilities[[int]$index] | Where-Object { $packages -contains $_.packageName }).Count -gt 0
        } | ForEach-Object { [string]$_.name })
        if (-not $key) {
            Add-Finding -Kind 'index-unresolved' -Repository $null -Detail "the Morphe directory lists $($bundle.repo) on $($bundle.source), which is not GitHub or GitLab" -Evidence $null
            continue
        }
        Add-Candidate -Key $key -Source 'Morphe directory' -Packages $apps -Features $features
        $count++
    }
    "$count Instagram bundles; HushGram " + $(if ($listed['morphe-directory']) { 'listed' } else { 'not listed' })
}

Invoke-Source 'Awesome Morphe' {
    $hashes = Get-SourceJson -Uri 'https://raw.githubusercontent.com/nvbangg/awesome-morphe/main/data/hashes.json' -What 'Awesome Morphe'
    $count = 0
    $listed['awesome-morphe'] = $false
    foreach ($property in $hashes.PSObject.Properties) {
        $key = ConvertTo-SourceKey $property.Name
        if ($key -and $key -eq $selfKey) { $listed['awesome-morphe'] = $true }
        $apps = @($property.Value.PSObject.Properties | Where-Object { $packages -contains $_.Name } | ForEach-Object { $_.Name })
        if ($apps.Count -eq 0) { continue }
        Add-Candidate -Key $key -Source 'Awesome Morphe' -Packages $apps
        $count++
    }
    "$count Instagram bundles; HushGram " + $(if ($listed['awesome-morphe']) { 'listed' } else { 'not listed' })
}

Invoke-Source 'Morphe Patch Tracker' {
    $tracker = Get-SourceJson -Uri 'https://raw.githubusercontent.com/drnx64/morphe-track-patches/main/data/bundles.json' `
        -What 'the Morphe Patch Tracker' -AsHashtable
    $seen = @{}
    $listed['morphe-patch-tracker'] = $false
    foreach ($property in (Get-JsonEntries $tracker)) {
        $bundle = $property.Value
        $key = ConvertTo-SourceKey ([string]$bundle.repo_url)
        if ($key -and $key -eq $selfKey) { $listed['morphe-patch-tracker'] = $true }
        $apps = @(Get-JsonItems $bundle.apps | Where-Object { $packages -contains $_.package })
        if ($apps.Count -eq 0) { continue }
        $features = @($apps | ForEach-Object { Get-JsonItems $_.patches } | ForEach-Object { [string]$_.name })
        Add-Candidate -Key $key -Source 'Morphe Patch Tracker' -Packages @($apps | ForEach-Object { $_.package }) -Features $features
        $seen[$key] = $true
    }
    "$($seen.Count) Instagram bundles; HushGram " + $(if ($listed['morphe-patch-tracker']) { 'listed' } else { 'not listed' })
}

Invoke-Source 'Jman''s bundle index' {
    # Jman's bundle-sources.json and PATCH-LIST-CATALOG.md left the repository (now Patch-Bundle-Registry)
    # for a catalog API. Its REST routes refuse a query for a package as big as Instagram's, so this reads
    # its GraphQL endpoint (GET, read only): the newest stable and prerelease bundle of every source that
    # patches the package, and whether HushGram is a source there at all.
    $api = 'https://patch-bundles.api.jmancentral.com/graphql?query='
    $selfPattern = ([string]$ledgerDocument.self.repository).TrimEnd('/') -replace '([\\_%])', '\$1'
    $selfAnswer = Get-SourceJson -Uri ($api + [Uri]::EscapeDataString('{ source(where: {url: {_ilike: "' + ($selfPattern -replace '\\', '\\') + '"}}) { url } }')) `
        -What 'Jman''s patch bundle registry'
    if ($null -ne (Get-SourceProperty $selfAnswer 'errors')) {
        throw "Jman's patch bundle registry refused the query: $(@($selfAnswer.errors | ForEach-Object { $_.message }) -join '; ')"
    }
    $listed['jman-bundles'] = @(Get-JsonItems $selfAnswer.data.source).Count -gt 0
    $byRepository = @{}
    foreach ($package in $packages) {
        for ($offset = 0; ; $offset += 100) {
            $filter = 'patch_packages: {package: {name: {_eq: "' + $package + '"}}}'
            $query = '{ bundle(where: {is_latest: {_eq: true}, patches: {' + $filter + '}}, limit: 100, offset: ' + $offset +
                ', order_by: {id: asc}) { source { url } patches(where: {' + $filter + '}, limit: 100) { name } } }'
            $page = Get-SourceJson -Uri ($api + [Uri]::EscapeDataString($query)) -What 'Jman''s patch bundle registry'
            if ($null -ne (Get-SourceProperty $page 'errors')) {
                throw "Jman's patch bundle registry refused the query: $(@($page.errors | ForEach-Object { $_.message }) -join '; ')"
            }
            $bundles = @(Get-JsonItems $page.data.bundle)
            foreach ($bundle in $bundles) {
                $key = ConvertTo-SourceKey ([string]$bundle.source.url)
                if (-not $key) { continue }
                if (-not $byRepository.ContainsKey($key)) { $byRepository[$key] = New-Object System.Collections.Generic.List[string] }
                foreach ($patch in (Get-JsonItems $bundle.patches)) {
                    if ($patch.name -and -not $byRepository[$key].Contains([string]$patch.name)) { $byRepository[$key].Add([string]$patch.name) }
                }
            }
            if ($bundles.Count -lt 100) { break }
        }
    }
    foreach ($key in $byRepository.Keys) {
        if ($key -eq $selfKey) { continue }
        Add-Candidate -Key $key -Source 'Jman''s bundle index' -Packages $packages -Features @($byRepository[$key])
    }
    "$($byRepository.Count) Instagram bundles; HushGram " + $(if ($listed['jman-bundles']) { 'listed' } else { 'not listed' })
}

Invoke-Source 'Morphe Archive' {
    $self = Split-SourceKey $selfKey
    $answer = Invoke-SourceRequest -Uri ("https://api.github.com/repos/rushiforai/morphe-archive/contents/examplepatches/" +
        (Get-EncodedPath ([string]$ledgerDocument.self.repository -replace '^https://github\.com/', '')) + '/patches-list.json') -Auth github
    if ($answer.Status -eq 200) { $listed['morphe-archive'] = $true }
    elseif ($answer.Status -eq 404) { $listed['morphe-archive'] = $false }
    else { throw (Get-RequestFault $answer "the archive's copy of $($self.Path)") }
    'HushGram ' + $(if ($listed['morphe-archive']) { 'listed' } else { 'not listed' }) + '; its bundles are read through code search'
}

# --- code search -----------------------------------------------------------------------------

$searchCount = 0
Invoke-Source 'GitHub code search' {
    $queries = New-Object System.Collections.Generic.List[object]
    foreach ($package in $packages) {
        foreach ($term in @('filename:patches-list.json', 'bytecodePatch', 'resourcePatch', 'IXposedHookLoadPackage', 'XposedHelpers')) {
            $queries.Add([pscustomobject]@{ Package = $package; Query = "`"$package`" $term" })
        }
    }
    $hits = 0
    foreach ($query in $queries) {
        for ($page = 1; $page -le 10; $page++) {
            if ($script:searchCount -gt 0 -and $SearchDelaySeconds -gt 0) { Start-Sleep -Milliseconds ([int]($SearchDelaySeconds * 1000)) }
            $script:searchCount++
            $answer = Invoke-SourceRequest -Uri ("https://api.github.com/search/code?q=" + [Uri]::EscapeDataString($query.Query) +
                "&per_page=100&page=$page") -Auth github
            if ($answer.Status -ne 200) { throw (Get-RequestFault $answer "the search '$($query.Query)'") }
            $result = ConvertFrom-SourceJson -Text $answer.Content -What "the search '$($query.Query)'"
            $items = @(Get-JsonItems $result.items)
            foreach ($item in $items) {
                $key = ConvertTo-SourceKey ([string]$item.repository.full_name)
                $path = [string]$item.path
                if ($archiveRepositories -contains $key) {
                    # A copy of another repository's patch list. Only the archive's own copy of a
                    # bundle points somewhere; its copies of Jman's copies are read as Jman.
                    if ($path -match '^examplepatches/([^/]+)/([^/]+)/patches-list\.json$') {
                        Add-Candidate -Key (ConvertTo-SourceKey "$($Matches[1])/$($Matches[2])") -Source 'Morphe Archive' -Packages @($query.Package)
                    }
                    continue
                }
                if ($indexRepositories -contains $key) { continue }
                Add-Candidate -Key $key -Source 'GitHub code search' -Mentions @($query.Package) -Path $path -Blob ([string]$item.sha)
                $hits++
            }
            if ($items.Count -lt 100 -or ($page * 100) -ge [int]$result.total_count) { break }
        }
    }
    "$($queries.Count) queries, $hits hits outside the indexes"
}

if ($searchGitLab) {
    Invoke-Source 'GitLab code search' {
        $projects = @{}
        $hits = 0
        foreach ($package in $packages) {
            $result = Get-PagedItems -Uri ("https://gitlab.com/api/v4/search?scope=blobs&search=" + [Uri]::EscapeDataString("`"$package`"")) -Auth gitlab
            if ($result.Status -ne 200) { throw (Get-RequestFault $result "the search for $package") }
            foreach ($item in $result.Items) {
                $projectId = [string]$item.project_id
                if (-not $projects.ContainsKey($projectId)) {
                    $project = Invoke-SourceRequest -Uri "https://gitlab.com/api/v4/projects/$projectId" -Auth gitlab
                    # A project that's gone or private (GitLab answers 404 for both) drops its hit; one a
                    # limit or a server error kept unread fails the search.
                    if ($project.Status -ne 200 -and $project.Status -ne 404) { throw (Get-RequestFault $project "project $projectId") }
                    $projects[$projectId] = if ($project.Status -eq 200) {
                        ConvertTo-SourceKey ('https://gitlab.com/' + (ConvertFrom-SourceJson -Text $project.Content -What "GitLab project $projectId").path_with_namespace)
                    } else { $null }
                }
                if ($projects[$projectId]) {
                    Add-Candidate -Key $projects[$projectId] -Source 'GitLab code search' -Mentions @($package) -Path ([string]$item.path) -Blob $null
                    $hits++
                }
            }
        }
        "$hits hits in $(@($projects.Values | Where-Object { $_ } | Sort-Object -Unique).Count) projects"
    }
} else {
    $notes.Add('GitLab code search was skipped (-SkipGitLabCodeSearch); the census records it. GitLab sources in the ledger were still read.')
    $sourceStatus.Add([pscustomobject][ordered]@{ source = 'GitLab code search'; status = 'skipped'; detail = '-SkipGitLabCodeSearch' })
    Write-Step 'GitLab code search: skipped (-SkipGitLabCodeSearch)'
}

# --- what the discovery found that the ledger doesn't know --------------------------------------

$collapsed = New-Object System.Collections.Generic.List[object]
# What code search found in repositories the ledger already knows, so a maintainer can copy the
# blob ids into contentHashes or a mirror's blobs when settling a finding.
$knownHits = New-Object System.Collections.Generic.List[object]

# What an index or code search says about a repository the ledger already holds, under whatever
# name it was found by.
function Test-KnownRepository {
    param($Candidate, [string]$Key, $Record, [string[]]$Blobs)
    if ($Record.Kind -eq 'entry') {
        $recordedPackages = @($Record.Entry.packages | ForEach-Object { "$_" })
        $newPackages = @($Candidate.Packages | Where-Object { $recordedPackages -notcontains $_ })
        if ($newPackages.Count -gt 0) {
            Add-Finding -Kind 'packages-changed' -Repository ([string]$Record.Entry.repository) `
                -Detail ("now targets " + ($newPackages -join ', ') + ", which the ledger doesn't record") `
                -Evidence ([ordered]@{ sources = @($Candidate.Sources); features = @($Candidate.Features) })
        }
    } elseif ($Record.Kind -eq 'mirror') {
        $diverged = @($Blobs | Where-Object { -not $knownBlobs.ContainsKey($_) })
        if ($diverged.Count -gt 0) {
            Add-Finding -Kind 'mirror-changed' -Repository ([string]$Record.Record.repository) `
                -Detail "a copy of $($Record.Lineage) now holds Instagram files the lineage doesn't" `
                -Evidence ([ordered]@{ hits = @($Candidate.Hits | Where-Object { $diverged -contains $_.blob }) })
        }
    } elseif ($Candidate.Packages.Count -gt 0) {
        # An index listing a recorded fork or an out-of-scope repository for Instagram means it
        # publishes a bundle of its own. The Morphe Archive keeps every fork's
        # patch list, so a fork only it lists counts for packages its entry doesn't target.
        $listers = @($Candidate.Sources | Where-Object { $_ -notlike '*code search' }) -join ', '
        $evidence = [ordered]@{ sources = @($Candidate.Sources); packages = @($Candidate.Packages); features = @($Candidate.Features) }
        if ($Record.Kind -eq 'fork') {
            $recordedPackages = @($Record.Entry.packages | ForEach-Object { "$_" })
            $newPackages = @($Candidate.Packages | Where-Object { $recordedPackages -notcontains $_ })
            $curated = @($Candidate.Sources | Where-Object { $_ -ne 'Morphe Archive' -and $_ -notlike '*code search' })
            if ($curated.Count -gt 0 -or $newPackages.Count -gt 0) {
                Add-Finding -Kind 'listed-as-bundle' -Repository "https://$Key" `
                    -Detail ("a recorded fork of $($Record.Lineage) that $listers lists as a bundle of its own for " +
                        (@($Candidate.Packages) -join ', ') + '; give it an entry of its own') -Evidence $evidence
            }
        } else {
            Add-Finding -Kind 'listed-as-bundle' -Repository ([string]$Record.Record.repository) `
                -Detail ("out of scope in the ledger, but $listers lists it as a bundle for " + (@($Candidate.Packages) -join ', ') +
                    '; record it as an entry') -Evidence $evidence
        }
    }
}

foreach ($candidate in @($candidates.Values | Sort-Object Key)) {
    $key = $candidate.Key
    $segments = @($key -split '/')
    if ($key -eq $selfKey -or ($segments[0] -eq 'github.com' -and $segments[1] -eq $selfOwner)) { continue }
    $blobs = @($candidate.Hits | ForEach-Object { $_.blob } | Where-Object { $_ } | Sort-Object -Unique)
    if ($known.ContainsKey($key)) {
        $record = $known[$key]
        if ($candidate.Hits.Count -gt 0) {
            $knownHits.Add([pscustomobject][ordered]@{ repository = "https://$key"; kind = $record.Kind; lineage = $record.Lineage
                hits = @($candidate.Hits | Sort-Object path -Unique) })
        }
        Test-KnownRepository -Candidate $candidate -Key $key -Record $record -Blobs $blobs
        continue
    }
    $unknownBlobs = @($blobs | Where-Object { -not $knownBlobs.ContainsKey($_) })
    if ($blobs.Count -gt 0 -and $unknownBlobs.Count -eq 0) {
        $lineage = @($blobs | ForEach-Object { $knownBlobs[$_] } | Group-Object | Sort-Object Count -Descending)[0].Name
        $collapsed.Add([pscustomobject][ordered]@{ repository = "https://$key"; lineage = $lineage; blobs = $blobs })
        continue
    }
    $meta = $null
    $where = Split-SourceKey $key
    if ($where.Host -eq 'github.com') {
        $answer = Invoke-SourceRequest -Uri "https://api.github.com/repos/$($where.Path)" -Auth github
        if ($answer.Status -eq 200) {
            $repo = ConvertFrom-SourceJson -Text $answer.Content -What $key
            # An index that still spells a renamed repository by its old name: GitHub redirects
            # the old name, and the repository it lands on is one the ledger knows.
            $resolved = ConvertTo-SourceKey "https://github.com/$($repo.full_name)"
            if ($resolved -and $resolved -ne $key -and $known.ContainsKey($resolved)) {
                $notes.Add("https://$key is an old name of https://$resolved, which the ledger knows")
                # Held to the same checks as a listing under the current name.
                Test-KnownRepository -Candidate $candidate -Key $resolved -Record $known[$resolved] -Blobs $blobs
                continue
            }
            $meta = [ordered]@{ fullName = [string]$repo.full_name; license = [string](Get-SourceProperty (Get-SourceProperty $repo 'license') 'spdx_id')
                fork = [bool]$repo.fork; parent = [string](Get-SourceProperty (Get-SourceProperty $repo 'parent') 'full_name')
                archived = [bool]$repo.archived; pushedAt = "$($repo.pushed_at)"; description = [string]$repo.description }
        } else {
            $meta = [ordered]@{ status = $answer.Status }
            # A limit says nothing about the repository, and the finding says so.
            if ($answer.Limited) { $meta.unread = Get-RequestFault $answer 'its metadata' }
        }
    }
    Add-Finding -Kind 'addition' -Repository "https://$key" `
        -Detail ("found by " + ($candidate.Sources -join ', ') + " for " + (@(@($candidate.Packages) + @($candidate.Mentions) | Sort-Object -Unique) -join ', ') + ", and the ledger doesn't know it") `
        -Evidence ([ordered]@{ sources = @($candidate.Sources); packages = @($candidate.Packages); mentions = @($candidate.Mentions); features = @($candidate.Features)
            hits = $candidate.Hits.ToArray(); unknownBlobs = $unknownBlobs; repository = $meta })
}
foreach ($copy in $collapsed) {
    $notes.Add("$($copy.repository) is a byte-for-byte copy of files in the $($copy.lineage) lineage, so it counts as that lineage")
}
foreach ($entry in $entries) {
    foreach ($inherited in @(Get-SourceProperty $entry 'inherits' | Where-Object { $_ })) {
        $notes.Add("$($entry.repository) carries code from the $($inherited.lineage) lineage ($(@($inherited.paths) -join ', ')), " +
            "which counts as that lineage, not as its own work")
    }
}

# --- every source the ledger holds ---------------------------------------------------------------

function Get-GitHubWatchedHead {
    param([string]$Path, [string]$Branch, [string[]]$WatchPaths, [string]$Head)
    if (@($WatchPaths).Count -eq 0) { return $Head }
    $newest = $null
    foreach ($watch in $WatchPaths) {
        $answer = Invoke-SourceRequest -Uri ("https://api.github.com/repos/$Path/commits?sha=" + [Uri]::EscapeDataString($Branch) +
            '&path=' + [Uri]::EscapeDataString($watch) + '&per_page=1') -Auth github
        if ($answer.Status -ne 200) { throw (Get-RequestFault $answer "the commits of $Branch under $watch") }
        foreach ($commit in (Get-JsonItems (ConvertFrom-SourceJson -Text $answer.Content -What "$Path commits"))) {
            $date = [datetime]::Parse("$($commit.commit.committer.date)", [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::AdjustToUniversal)
            if ($null -eq $newest -or $date -gt $newest.Date) { $newest = [pscustomobject]@{ Sha = [string]$commit.sha; Date = $date } }
        }
    }
    if ($null -eq $newest) { return $null }
    return $newest.Sha
}

function Get-GitLabWatchedHead {
    param([string]$Project, [string]$Branch, [string[]]$WatchPaths, [string]$Head)
    if (@($WatchPaths).Count -eq 0) { return $Head }
    $newest = $null
    foreach ($watch in $WatchPaths) {
        $answer = Invoke-SourceRequest -Uri ("https://gitlab.com/api/v4/projects/$Project/repository/commits?ref_name=" +
            [Uri]::EscapeDataString($Branch) + '&path=' + [Uri]::EscapeDataString($watch) + '&per_page=1') -Auth gitlab
        if ($answer.Status -ne 200) { throw (Get-RequestFault $answer "the commits of $Branch under $watch") }
        foreach ($commit in (Get-JsonItems (ConvertFrom-SourceJson -Text $answer.Content -What "$Project commits"))) {
            $date = [datetime]::Parse("$($commit.committed_date)", [Globalization.CultureInfo]::InvariantCulture,
                [Globalization.DateTimeStyles]::AdjustToUniversal)
            if ($null -eq $newest -or $date -gt $newest.Date) { $newest = [pscustomobject]@{ Sha = [string]$commit.id; Date = $date } }
        }
    }
    if ($null -eq $newest) { return $null }
    return $newest.Sha
}

function Test-CommitContained {
    # Whether $Commit is $Within or one of its ancestors. A commit either forge can't compare (a
    # pin rewritten away by a force push, say) counts as not contained, so it gets looked at. A
    # limit leaves the source unread instead, since it says nothing about the branch.
    param($Where, [string]$Commit, [string]$Within)
    if ($Commit -notmatch '^[0-9a-f]{40}$' -or $Within -notmatch '^[0-9a-f]{40}$') { return $false }
    if ($Where.Host -eq 'github.com') {
        $answer = Invoke-SourceRequest -Uri "https://api.github.com/repos/$($Where.Path)/compare/$Within...$Commit" -Auth github
        if ($answer.Limited) { throw (Get-RequestFault $answer "the comparison of $Commit with $Within") }
        if ($answer.Status -ne 200) { return $false }
        $status = [string](ConvertFrom-SourceJson -Text $answer.Content -What "$($Where.Path) comparison").status
        return $status -eq 'identical' -or $status -eq 'behind'
    }
    $answer = Invoke-SourceRequest -Uri ("https://gitlab.com/api/v4/projects/$([Uri]::EscapeDataString($Where.Path))/repository/compare?from=" +
        "$Within&to=$Commit") -Auth gitlab
    if ($answer.Limited) { throw (Get-RequestFault $answer "the comparison of $Commit with $Within") }
    if ($answer.Status -ne 200) { return $false }
    return @(Get-JsonItems (ConvertFrom-SourceJson -Text $answer.Content -What "$($Where.Path) comparison").commits).Count -eq 0
}

function Read-GitHubSource {
    param($Entry, [string]$Path)
    $answer = Invoke-SourceRequest -Uri "https://api.github.com/repos/$Path" -Auth github
    if ($answer.Status -ne 200) { return $answer }
    $repo = ConvertFrom-SourceJson -Text $answer.Content -What $Path
    $license = $null
    $licenseAnswer = Invoke-SourceRequest -Uri "https://api.github.com/repos/$Path/license" -Auth github
    if ($licenseAnswer.Status -eq 200) {
        $document = ConvertFrom-SourceJson -Text $licenseAnswer.Content -What "$Path licence"
        $license = [pscustomobject]@{ Spdx = [string](Get-SourceProperty $document.license 'spdx_id'); Path = [string]$document.path
            Sha256 = Get-Sha256Hex ([Convert]::FromBase64String((([string]$document.content) -replace '\s', ''))) }
    } elseif ($licenseAnswer.Status -ne 404) {
        throw (Get-RequestFault $licenseAnswer "the licence of $Path")
    }
    $branchList = Get-PagedItems -Uri "https://api.github.com/repos/$Path/branches" -Auth github
    if ($branchList.Status -ne 200) { throw (Get-RequestFault $branchList "the branches of $Path") }
    $forkList = Get-PagedItems -Uri "https://api.github.com/repos/$Path/forks" -Auth github
    if ($forkList.Status -ne 200) { throw (Get-RequestFault $forkList "the forks of $Path") }
    $watch = @(Get-SourceProperty $Entry 'watchPaths' | Where-Object { $_ })
    $branches = [ordered]@{}
    foreach ($branch in $branchList.Items) {
        $branches[[string]$branch.name] = [pscustomobject]@{ Head = [string]$branch.commit.sha
            Watched = (Get-GitHubWatchedHead -Path $Path -Branch ([string]$branch.name) -WatchPaths $watch -Head ([string]$branch.commit.sha)) }
    }
    return [pscustomobject]@{ Status = 200; FullName = "github.com/$($repo.full_name)".ToLowerInvariant(); Archived = [bool]$repo.archived
        License = $license; Branches = $branches; Forks = @($forkList.Items | ForEach-Object { [string]$_.full_name }) }
}

function Read-GitLabSource {
    param($Entry, [string]$Path)
    $project = [Uri]::EscapeDataString($Path)
    $answer = Invoke-SourceRequest -Uri "https://gitlab.com/api/v4/projects/$project" -Auth gitlab
    if ($answer.Status -ne 200) { return $answer }
    $repo = ConvertFrom-SourceJson -Text $answer.Content -What $Path
    $defaultBranch = [string]$repo.default_branch
    $license = $null
    $tree = Get-PagedItems -Uri ("https://gitlab.com/api/v4/projects/$project/repository/tree?ref=" + [Uri]::EscapeDataString($defaultBranch)) -Auth gitlab
    if ($tree.Status -ne 200) { throw (Get-RequestFault $tree "the file list of $Path") }
    $licenseFile = @($tree.Items | Where-Object { $_.type -eq 'blob' -and "$($_.name)" -match '^(?i)(LICEN[CS]E|COPYING)(\.(md|txt))?$' } | Select-Object -First 1)
    if ($licenseFile.Count -gt 0) {
        $file = Get-SourceJson -Uri ("https://gitlab.com/api/v4/projects/$project/repository/files/" +
            [Uri]::EscapeDataString([string]$licenseFile[0].path) + '?ref=' + [Uri]::EscapeDataString($defaultBranch)) -Auth gitlab -What "$Path licence"
        $license = [pscustomobject]@{ Spdx = $null; Path = [string]$licenseFile[0].path
            Sha256 = Get-Sha256Hex ([Convert]::FromBase64String((([string]$file.content) -replace '\s', ''))) }
    }
    $branchList = Get-PagedItems -Uri "https://gitlab.com/api/v4/projects/$project/repository/branches" -Auth gitlab
    if ($branchList.Status -ne 200) { throw (Get-RequestFault $branchList "the branches of $Path") }
    $forkList = Get-PagedItems -Uri "https://gitlab.com/api/v4/projects/$project/forks" -Auth gitlab
    if ($forkList.Status -ne 200) { throw (Get-RequestFault $forkList "the forks of $Path") }
    $watch = @(Get-SourceProperty $Entry 'watchPaths' | Where-Object { $_ })
    $branches = [ordered]@{}
    foreach ($branch in $branchList.Items) {
        $branches[[string]$branch.name] = [pscustomobject]@{ Head = [string]$branch.commit.id
            Watched = (Get-GitLabWatchedHead -Project $project -Branch ([string]$branch.name) -WatchPaths $watch -Head ([string]$branch.commit.id)) }
    }
    return [pscustomobject]@{ Status = 200; FullName = "gitlab.com/$($repo.path_with_namespace)".ToLowerInvariant(); Archived = [bool]$repo.archived
        License = $license; Branches = $branches; Forks = @($forkList.Items | ForEach-Object { [string]$_.path_with_namespace }) }
}

$forkCount = 0
foreach ($entry in $entries) {
    $repository = [string]$entry.repository
    $key = ConvertTo-SourceKey $repository
    $where = Split-SourceKey $key
    Invoke-Source $repository {
        $live = if ($where.Host -eq 'github.com') { Read-GitHubSource -Entry $entry -Path $where.Path } else { Read-GitLabSource -Entry $entry -Path $where.Path }
        # A limit still in force says nothing about the repository, so the source goes unread
        # rather than gone.
        if ($live.Limited) { throw (Get-RequestFault $live 'the repository') }
        if ($live.Status -ne 200) {
            Add-Finding -Kind 'repository-gone' -Repository $repository -Detail "the repository answers HTTP $($live.Status)" -Evidence $null
            return "gone (HTTP $($live.Status))"
        }
        if ($live.FullName -ne $key) {
            Add-Finding -Kind 'renamed' -Repository $repository -Detail "the repository is now https://$($live.FullName)" -Evidence $null
        }
        if ($live.Archived -ne [bool]$entry.archived) {
            Add-Finding -Kind 'archived-changed' -Repository $repository `
                -Detail $(if ($live.Archived) { 'the repository was archived' } else { 'the repository is no longer archived' }) -Evidence $null
        }
        $recordedLicense = Get-SourceProperty $entry 'license'
        if ($null -ne $recordedLicense -and $null -eq $live.License) {
            Add-Finding -Kind 'license-removed' -Repository $repository -Detail "the $($recordedLicense.spdx) licence the ledger records is gone" -Evidence $null
        } elseif ($null -eq $recordedLicense -and $null -ne $live.License) {
            Add-Finding -Kind 'license-added' -Repository $repository -Detail "the repository now has a licence ($($live.License.Spdx), $($live.License.Path))" `
                -Evidence ([ordered]@{ spdx = $live.License.Spdx; path = $live.License.Path; sha256 = $live.License.Sha256 })
        } elseif ($null -ne $recordedLicense -and $live.License.Sha256 -ne [string]$recordedLicense.sha256) {
            Add-Finding -Kind 'license-changed' -Repository $repository -Detail "the licence text changed ($($live.License.Path))" `
                -Evidence ([ordered]@{ spdx = $live.License.Spdx; path = $live.License.Path; recorded = [string]$recordedLicense.sha256
                    sha256 = $live.License.Sha256 })
        }
        $pins = @(Get-SourceProperty $entry 'branches' | Where-Object { $_ })
        $pinnedCommits = @($pins | ForEach-Object { [string]$_.commit })
        $pinnedNames = @($pins | ForEach-Object { [string]$_.name })
        foreach ($pin in $pins) {
            $name = [string]$pin.name
            if (-not $live.Branches.Contains($name)) {
                Add-Finding -Kind 'branch-removed' -Repository $repository -Detail "branch $name is gone" -Evidence $null
                continue
            }
            $now = $live.Branches[$name]
            if ($now.Watched -ne [string]$pin.commit) {
                Add-Finding -Kind 'changed-head' -Repository $repository `
                    -Detail ("branch $name moved from $([string]$pin.commit) to " + $(if ($now.Watched) { $now.Watched } else { 'no watched code' })) `
                    -Evidence ([ordered]@{ branch = $name; pinned = [string]$pin.commit; watchedHead = $now.Watched; head = $now.Head })
            }
        }
        foreach ($name in @($live.Branches.Keys)) {
            if ($pinnedNames -contains $name) { continue }
            $now = $live.Branches[$name]
            if (-not $now.Watched -or $pinnedCommits -contains $now.Watched) { continue }
            # A branch cut from a pinned one before its watched code last moved carries
            # nothing new, and busy repositories keep dozens of those.
            $contained = $false
            foreach ($pinned in $pinnedCommits) {
                if (Test-CommitContained -Where $where -Commit $now.Watched -Within $pinned) { $contained = $true; break }
            }
            if (-not $contained) {
                Add-Finding -Kind 'new-branch' -Repository $repository -Detail "branch $name carries work the ledger hasn't pinned" `
                    -Evidence ([ordered]@{ branch = $name; watchedHead = $now.Watched; head = $now.Head })
            }
        }
        $recordedForks = @(Get-SourceProperty $entry 'forks' | Where-Object { $_ } | ForEach-Object { "$_".ToLowerInvariant() })
        foreach ($fork in $live.Forks) {
            if ($recordedForks -notcontains $fork.ToLowerInvariant()) {
                Add-Finding -Kind 'unseen-fork' -Repository $repository -Detail "fork $fork isn't in the ledger" -Evidence ([ordered]@{ fork = $fork })
            }
        }
        foreach ($fork in $recordedForks) {
            if (@($live.Forks | ForEach-Object { $_.ToLowerInvariant() }) -notcontains $fork) { $notes.Add("$repository no longer lists the fork $fork") }
        }
        $script:forkCount += @($live.Forks).Count
        "$(@($live.Branches.Keys).Count) branches, $(@($live.Forks).Count) forks, licence " +
            $(if ($live.License) { $live.License.Sha256.Substring(0, 12) } else { 'none' })
    }
    # An adopted source's fixture evidence is offline (Test-SourceLedger holds it to the declared
    # builds); HushGram publishes no release receipt for the audit to read.
}

# --- HushGram's own listings ---------------------------------------------------------------------

foreach ($index in @($ledgerDocument.indexes)) {
    $id = [string]$index.id
    if (-not $listed.ContainsKey($id)) { continue }
    $status = [string]$index.hushgram.status
    if ($listed[$id] -and $status -ne 'listed') {
        Add-Finding -Kind 'listing-changed' -Repository $null -Detail "$($index.name) lists HushGram now; record the listing URL" -Evidence ([ordered]@{ index = $id; recorded = $status })
    } elseif (-not $listed[$id] -and $status -eq 'listed') {
        Add-Finding -Kind 'listing-changed' -Repository $null -Detail "$($index.name) no longer lists HushGram" -Evidence ([ordered]@{ index = $id; recorded = $status })
    } elseif (-not $listed[$id]) {
        $what = if ($status -eq 'submitted') { "submitted $($index.hushgram.submitted), not listed yet" } else { 'not listed, and no submission is recorded' }
        $notes.Add("$($index.name): HushGram is $what")
    }
}

# --- the report, and the stamp -------------------------------------------------------------------

$lineages = @($entries | Group-Object { [string]$_.lineage } | Sort-Object Name | ForEach-Object {
    $lineage = $_.Name
    [ordered]@{
        lineage = $lineage
        sources = @($_.Group | ForEach-Object { [ordered]@{ repository = [string]$_.repository; disposition = [string]$_.disposition } })
        mirrors = @(@($_.Group | ForEach-Object { Get-SourceProperty $_ 'mirrors' } | Where-Object { $_ } | ForEach-Object { [string]$_.repository }) +
            @($collapsed | Where-Object { $_.lineage -eq $lineage } | ForEach-Object { $_.repository }))
    }
})
$counts = [ordered]@{
    sources = $entries.Count
    lineages = $lineageNames.Count
    mirrorsRecorded = @($entries | ForEach-Object { Get-SourceProperty $_ 'mirrors' } | Where-Object { $_ }).Count
    mirrorsCollapsed = $collapsed.Count
    forks = $forkCount
    candidatesFound = $candidates.Count
    requests = $requestCount
    findings = $findings.Count
}
$clean = $findings.Count -eq 0
Save-Report -Clean $clean -Counts $counts -Lineages $lineages -KnownHits $knownHits
Write-Step ("$($entries.Count) sources in $($lineageNames.Count) lineages, $($counts.mirrorsRecorded) recorded mirrors, " +
    "$($collapsed.Count) more collapsed by content, $forkCount forks; $($candidates.Count) repositories found by the indexes and code search")
foreach ($note in $notes) { Write-Step "note: $note" }
if (-not $clean) {
    foreach ($finding in $findings) {
        $who = if ($finding.repository) { "$($finding.repository): " } else { '' }
        Write-Step "$($finding.kind): $who$($finding.detail)"
    }
    Write-Step "$($findings.Count) finding(s). Settle them in the ledger and run the audit again; the census was not stamped."
    exit 1
}

if ($NoStamp) {
    Write-Step 'nothing moved; -NoStamp left the ledger as it was'
    exit 0
}
$skipped = if ($searchGitLab) { @() } else { @('gitlab-code-search') }
$text = [IO.File]::ReadAllText($ledgerPath)
$stamped = $text -replace '("(?:lastChecked|checked|checkedAt)"\s*:\s*")\d{4}-\d{2}-\d{2}(")', ('${1}' + $todayText + '${2}')
# Only rewritten when it changes, so the file keeps the layout it was written with.
if ((@($ledgerDocument.census.skipped | Where-Object { $_ }) -join ',') -ne ($skipped -join ',')) {
    $skippedJson = '[' + (@($skipped | ForEach-Object { '"' + $_ + '"' }) -join ', ') + ']'
    $stamped = $stamped -replace '("skipped"\s*:\s*)\[[^\]]*\]', ('${1}' + $skippedJson)
}
$check = Test-SourceLedger -Ledger ($stamped | ConvertFrom-Json) -Root $rootPath -Today $todayText
if (-not $check.Valid) { throw "Stamping the ledger broke it, so it was left as it was: $($check.Problems[0])" }
[IO.File]::WriteAllText($ledgerPath, $stamped, (New-Object Text.UTF8Encoding $false))
Write-Step "nothing moved; the census and every record it checked are stamped $todayText"
exit 0
