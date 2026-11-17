<#
.SYNOPSIS
    Inspect selected local bundles and attribute dependency failures to their exact owner.
.NOTES
    https://github.com/SysAdminDoc/HushGram
    Original HushGram tooling. GPL-3.0-only.
#>

function Read-PatchSourceSelection {
    param([Parameter(Mandatory = $true)][string]$Path)
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    try { $selection = [IO.File]::ReadAllText($resolved) | ConvertFrom-Json }
    catch { throw 'The selected-source file is not valid JSON.' }
    if (($selection.schemaVersion -isnot [int] -and $selection.schemaVersion -isnot [long]) -or
            $selection.schemaVersion -ne 1 -or $selection.sources -isnot [array] -or $selection.sources.Count -eq 0) {
        throw 'Expected selected-source schemaVersion 1 and a nonempty sources array.'
    }
    foreach ($source in $selection.sources) {
        if ($source.bundle -isnot [string] -or [string]::IsNullOrWhiteSpace($source.bundle) -or
                $source.patches -isnot [array] -or $source.patches.Count -eq 0 -or
                @($source.patches | Where-Object { $_ -isnot [string] -or [string]::IsNullOrWhiteSpace($_) -or
                    $_ -match '[\r\n\x00]' }).Count -ne 0 -or
                ($source.patches -ccontains '*' -and $source.patches.Count -ne 1)) {
            throw 'Every selected source needs a local bundle and exact patch names, or just "*" for all its patches.'
        }
        $bundle = if ([IO.Path]::IsPathRooted($source.bundle)) { $source.bundle } else {
            Join-Path (Split-Path -Parent $resolved) $source.bundle
        }
        if (-not (Test-Path -LiteralPath $bundle -PathType Leaf)) { throw 'A selected local bundle is missing.' }
        [pscustomobject]@{ Bundle = (Resolve-Path -LiteralPath $bundle).Path; Patches = @($source.patches) }
    }
}

function Get-PatchSourceInspection {
    param([string]$Bundle, [string[]]$Patches, [string]$PackageName, [string]$Java,
        [string]$DesktopJar, [string]$Scratch)
    # Native readers only see a private snapshot. Builds can replace their own live output.
    New-Item -ItemType Directory -Path $Scratch -Force | Out-Null
    $snapshot = Join-Path $Scratch 'bundle.mpp'
    Copy-Item -LiteralPath $Bundle -Destination $snapshot
    $selection = Join-Path $Scratch 'selection.txt'
    [IO.File]::WriteAllLines($selection, $Patches, [Text.UTF8Encoding]::new($false))
    $global:LASTEXITCODE = -1
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $Java '-Xmx2g' '-cp' $DesktopJar (Join-Path $PSScriptRoot 'InspectPatchBundle.java') `
            $snapshot $PackageName $selection 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    $hash = (Get-FileHash -LiteralPath $snapshot -Algorithm SHA256).Hash.ToLowerInvariant()
    try { $inspection = ($output -join "`n") | ConvertFrom-Json } catch { $inspection = $null }
    if ($null -eq $inspection -or $inspection.schemaVersion -ne 1 -or
            $inspection.identity.sha256 -cnotmatch '^[0-9a-f]{64}$' -or $inspection.patches -isnot [array]) {
        $inspection = [pscustomobject]@{ schemaVersion = 1
            identity = [pscustomobject]@{ sha256 = $hash; name = 'Unknown bundle'; version = ''; patcherVersion = ''; buildIdentity = '' }
            patches = @(); classes = @(); extensions = @(); inspectionError = 'UnreadableInspection' }
    } elseif ($code -ne 0 -or $inspection.patches.Count -eq 0) {
        if (-not $inspection.inspectionError) {
            $inspection | Add-Member -NotePropertyName inspectionError -NotePropertyValue 'IncompleteInspection'
        }
    }
    if ($hash -cne $inspection.identity.sha256) {
        throw 'The inspected bundle changed. No APK mutation was started.'
    }
    return [pscustomobject]@{ Inspection = $inspection; Snapshot = $snapshot }
}

function Test-PatchSourceDefinitions {
    param([object[]]$Sources)
    $definitions = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    $shared = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($source in $Sources) {
        foreach ($definition in @($source.extensions)) {
            $type = [string]$definition.type
            if (-not $definitions.ContainsKey($type)) {
                $definitions.Add($type, [pscustomobject]@{ Hash = $definition.sha256; Owner = $source.identity })
                continue
            }
            $previous = $definitions[$type]
            if ($previous.Hash -cne $definition.sha256) {
                return [pscustomobject]@{ Valid = $false; SharedDefinitions = $shared.Count; Reason = (
                    "Conflicting selected extension definition $type in " +
                    "$($previous.Owner.name) $($previous.Owner.version) [$($previous.Owner.sha256)] and " +
                    "$($source.identity.name) $($source.identity.version) [$($source.identity.sha256)]. " +
                    'No APK mutation was started.') }
            }
            [void]$shared.Add($type)
        }
    }
    return [pscustomobject]@{ Valid = $true; SharedDefinitions = $shared.Count; Reason = $null }
}

function Get-PatchFailureOwnership {
    param([object[]]$Sources, [string]$Text, [object]$Report)
    $patchName = $null
    $block = [Collections.Generic.List[string]]::new()
    function Read-FailureBlock {
        param([string]$Name, [string[]]$Lines)
        $body = $Lines -join "`n"
        $match = [regex]::Match($body,
            '(?:Failed to match the fingerprint:|Could not initialize class)\s+([A-Za-z_$][A-Za-z0-9_$.]*)')
        if (-not $match.Success) { return }
        $initializer = $match.Groups[1].Value
        $owners = @($Sources | Where-Object { @($_.classes) -ccontains $initializer })
        $dependency = $null
        if ($owners.Count -eq 1) {
            $nodes = @($owners[0].patches | Where-Object { -not $_.selected -and $_.implementation -and
                $body.Contains("at $($_.implementation).") })
            if ($nodes.Count -eq 1) {
                $dependency = [pscustomobject]@{ Name = $nodes[0].name; Implementation = $nodes[0].implementation }
            }
        }
        [pscustomobject]@{
            patch = $Name
            selectedPatchOwners = @($Sources | Where-Object { @($_.patches | Where-Object {
                $_.selected -and $_.name -ceq $Name }).Count -gt 0 } | ForEach-Object { $_.identity })
            initializer = $initializer
            dependencyOwner = if ($owners.Count -eq 1) { $owners[0].identity } else { $null }
            dependency = $dependency
            attribution = if ($owners.Count -eq 1) { 'exact-class-owner' } else { 'unknown-or-ambiguous' }
        }
    }
    if ($null -ne $Report) {
        # morphe-desktop 1.20.0 and newer write {name, error}; older CLIs wrote {patch: {name}, reason}.
        foreach ($failure in @($Report.failedPatches)) {
            $failedName = if ($failure.name) { [string]$failure.name } else { [string]$failure.patch.name }
            $failedText = if ($failure.error) { [string]$failure.error } else { [string]$failure.reason }
            Read-FailureBlock -Name $failedName -Lines @($failedText)
        }
        return
    }
    foreach ($line in @($Text -split '\r?\n')) {
        $match = [regex]::Match($line, "(?:^|\s)Patch '([^'\r\n]+)' failed:")
        if ($match.Success) {
            if ($patchName) { Read-FailureBlock -Name $patchName -Lines $block.ToArray() }
            $patchName = $match.Groups[1].Value
            $block.Clear()
        } elseif ($patchName) { $block.Add($line) }
    }
    if ($patchName) { Read-FailureBlock -Name $patchName -Lines $block.ToArray() }
}
