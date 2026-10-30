<#
.SYNOPSIS
    Required walkthrough destinations and their visible content checks.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>

function Test-WalkPatchCondition {
    param($Condition, [Collections.Generic.HashSet[string]]$Selected, [int]$Depth = 0,
        [Collections.Generic.HashSet[string]]$Known)
    if ($null -eq $Condition -or $Depth -gt 8 -or
        ($Condition -isnot [Collections.IDictionary] -and $Condition -isnot [pscustomobject])) {
        throw 'Invalid settings category condition.'
    }
    $properties = if ($Condition -is [Collections.IDictionary]) { @($Condition.Keys) } else { @($Condition.PSObject.Properties.Name) }
    $allowed = switch -CaseSensitive ($Condition.op) {
        'unconditional' { @('op') }
        { $_ -cin @('any', 'all') } { @('op','patches') }
        'at_least' { @('op','patches','count') }
        { $_ -cin @('any_of', 'all_of') } { @('op','conditions') }
        default { throw 'Unknown settings category condition.' }
    }
    if (@($properties | Where-Object { $_ -cnotin $allowed }).Count -or $properties.Count -ne @($allowed).Count) {
        throw 'Invalid settings category condition fields.'
    }
    switch -CaseSensitive ($Condition.op) {
        'unconditional' { return $true }
        { $_ -cin @('any', 'all', 'at_least') } {
            $names = @($Condition.patches)
            $unique = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
            if ($Condition.patches -isnot [array] -or -not $names.Count) { throw 'Invalid settings category patch names.' }
            foreach ($name in $names) {
                if ($name -isnot [string] -or [string]::IsNullOrWhiteSpace($name) -or -not $unique.Add($name) -or
                    ($null -ne $Known -and -not $Known.Contains($name))) { throw 'Unknown or duplicate settings category patch names.' }
            }
            $matched = @($names | Where-Object { $Selected.Contains($_) }).Count
            if ($Condition.op -ceq 'any') { return $matched -gt 0 }
            if ($Condition.op -ceq 'all') { return $matched -eq $names.Count }
            if (($Condition.count -isnot [int] -and $Condition.count -isnot [long]) -or
                $Condition.count -lt 1 -or $Condition.count -gt $names.Count) { throw 'Invalid category patch threshold.' }
            return $matched -ge $Condition.count
        }
        { $_ -cin @('any_of', 'all_of') } {
            $conditions = @($Condition.conditions)
            if ($Condition.conditions -isnot [array] -or -not $conditions.Count -or $conditions -contains $null) {
                throw 'Empty settings category alternatives.'
            }
            $answers = @($conditions | ForEach-Object { Test-WalkPatchCondition -Condition $_ -Selected $Selected -Known $Known -Depth ($Depth + 1) })
            if ($Condition.op -ceq 'any_of') { return $answers -contains $true }
            return $answers -notcontains $false
        }
        default { throw 'Unknown settings category condition.' }
    }
}

function Get-WalkRequiredDestinations {
    param([string]$CatalogPath, [string[]]$SelectedPatches,
        [string]$PolicyPath = (Join-Path $PSScriptRoot 'walk-categories.json'))
    $catalog = Get-Content -LiteralPath $CatalogPath -Raw | ConvertFrom-Json
    $policy = Get-Content -LiteralPath $PolicyPath -Raw | ConvertFrom-Json
    if (($policy.schema -isnot [int] -and $policy.schema -isnot [long]) -or $policy.schema -ne 1 -or $policy.categories -isnot [array] -or
        -not $policy.categories.Count -or $catalog.patches -isnot [array] -or -not $catalog.patches.Count) {
        throw 'Unsupported walkthrough category policy or catalog.'
    }
    $known = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($catalogPatch in @($catalog.patches)) {
        if ($catalogPatch.name -isnot [string] -or [string]::IsNullOrWhiteSpace($catalogPatch.name) -or -not $known.Add($catalogPatch.name)) {
            throw 'The walkthrough catalog has missing or duplicate patch names.'
        }
    }
    if ($policy.prerequisite -isnot [string] -or -not $known.Contains($policy.prerequisite)) {
        throw 'Unknown walkthrough settings prerequisite.'
    }
    $selected = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($selectedPatch in $SelectedPatches) {
        if (-not $known.Contains($selectedPatch) -or -not $selected.Add($selectedPatch)) {
            throw 'The walkthrough selection has unknown or duplicate patches.'
        }
    }
    # Validate the whole policy, including currently unselected categories and anchors.
    $categoryIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $categoryTitles = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($category in $policy.categories) {
        if ($category.id -isnot [string] -or $category.id -cnotmatch '^settings-[a-z]+$' -or
            $category.title -isnot [string] -or [string]::IsNullOrWhiteSpace($category.title) -or
            $category.builder -isnot [string] -or $category.builder -cnotmatch '^[A-Z][A-Za-z]*Category$' -or
            $category.page -isnot [bool] -or -not $categoryIds.Add($category.id) -or -not $categoryTitles.Add($category.title)) {
            throw 'Invalid or duplicate walkthrough category identity.'
        }
        [void](Test-WalkPatchCondition -Condition $category.when -Selected $selected -Known $known)
        $anchorTitles = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        if ($category.anchors -isnot [array] -or -not $category.anchors.Count) { throw 'Missing walkthrough category anchors.' }
        foreach ($anchor in $category.anchors) {
            if ($anchor.title -isnot [string] -or [string]::IsNullOrWhiteSpace($anchor.title) -or -not $anchorTitles.Add($anchor.title)) {
                throw 'Invalid or duplicate walkthrough category anchor.'
            }
            [void](Test-WalkPatchCondition -Condition $anchor.when -Selected $selected -Known $known)
        }
    }
    $destinations = [Collections.Generic.List[object]]::new()
    # Native IDs and signatures come from audit-2026-10-25 captures (Home, Explore, inbox,
    # profile) and BurnInGuard.java:107-134 (Reels). Containers alone never prove loaded content.
    $destinations.Add([pscustomobject]@{ id='home'; title='Home'; kind='native'; link='instagram://mainfeed'; tab='feed_tab'
        requiredIds=@('main_feed_action_bar'); contentIds=@('row_feed_profile_header') })
    $destinations.Add([pscustomobject]@{ id='explore'; title='Explore'; kind='native'; link='instagram://explore'; tab='search_tab'
        requiredIds=@('explore_action_bar'); contentIds=@('recycler_view') })
    $destinations.Add([pscustomobject]@{ id='reels'; title='Reels'; kind='native'; link='instagram://reels_home'; tab='clips_tab'
        requiredIds=@(); contentIds=@('clips_viewer_view_pager') })
    $destinations.Add([pscustomobject]@{ id='inbox'; title='Messages'; kind='native'; link='instagram://direct-inbox'; tab='direct_tab'
        requiredIds=@('direct_inbox_action_bar'); contentIds=@('inbox_refreshable_thread_list_recyclerview') })
    $destinations.Add([pscustomobject]@{ id='profile'; title='Your profile'; kind='native'; link=$null; tab='profile_tab'
        requiredIds=@('profile_action_bar'); contentIds=@('row_profile_header') })
    if ($selected.Contains($policy.prerequisite)) {
        $overview = [pscustomobject]@{ id='settings-root'; title='HushGram settings'; kind='settings-root'; page=$false; anchors=@() }
        $destinations.Add($overview)
        # Every heading title, so a row is only credited to the section it sits under.
        $headingTitles = @($policy.categories | ForEach-Object { $_.title })
        foreach ($category in @($policy.categories)) {
            if (-not (Test-WalkPatchCondition -Condition $category.when -Selected $selected -Known $known)) { continue }
            $anchors = @($category.anchors | Where-Object { Test-WalkPatchCondition -Condition $_.when -Selected $selected } |
                ForEach-Object { $_.title })
            if (-not $anchors.Count) { throw "Required walkthrough category '$($category.title)' has no applicable verified anchor." }
            $destinations.Add([pscustomobject]@{ id=$category.id; title=$category.title; kind='settings'; page=$category.page
                anchors=$anchors; headings=$headingTitles; builder=$category.builder })
        }
        $overview.anchors = @('HushGram is on','HushGram is paused','Show all settings as a list','Show settings by category') +
            @($destinations | Where-Object { $_.kind -ceq 'settings' } | ForEach-Object { $_.anchors } | Select-Object -Unique)
    }
    if (@($destinations.id | Select-Object -Unique).Count -ne $destinations.Count) { throw 'Duplicate walkthrough destinations.' }
    $destinations.ToArray()
}

function Read-WalkUiDocument {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf) -or (Get-Item -LiteralPath $Path).Length -gt 8MB) {
        throw 'The walkthrough UI snapshot is missing or too large.'
    }
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $document = [Xml.XmlDocument]::new()
    $document.XmlResolver = $null
    $reader = [Xml.XmlReader]::Create([IO.Path]::GetFullPath($Path), $settings)
    try { $document.Load($reader) } finally { $reader.Dispose() }
    if ($document.DocumentElement.Name -cne 'hierarchy') { throw 'The UI snapshot has no hierarchy root.' }
    return ,$document
}

function Test-WalkNodeUncovered {
    param([Xml.XmlNode]$Node, [Collections.Generic.HashSet[Xml.XmlNode]]$Visible,
        [Collections.Generic.Dictionary[Xml.XmlNode,object]]$Geometry, $TargetArea)
    function Get-WalkNodeGeometry([Xml.XmlNode]$Candidate) {
        if ($Geometry.ContainsKey($Candidate)) { return $Geometry[$Candidate] }
        $left = [int]::MinValue; $top = [int]::MinValue
        $right = [int]::MaxValue; $bottom = [int]::MaxValue
        $ancestor = $Candidate
        while ($ancestor -and $ancestor.Name -ceq 'node') {
            $bounds = [regex]::Match($ancestor.GetAttribute('bounds'), '^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$')
            $left = [Math]::Max($left, [int]$bounds.Groups[1].Value)
            $top = [Math]::Max($top, [int]$bounds.Groups[2].Value)
            $right = [Math]::Min($right, [int]$bounds.Groups[3].Value)
            $bottom = [Math]::Min($bottom, [int]$bounds.Groups[4].Value)
            $ancestor = $ancestor.ParentNode
        }
        $order = 0
        $knownOrder = [int]::TryParse($Candidate.GetAttribute('drawing-order'), [ref]$order) -and $order -gt 0
        $Geometry[$Candidate] = [pscustomobject]@{ Left=$left; Top=$top; Right=$right; Bottom=$bottom; Order=$order; KnownOrder=$knownOrder }
        return $Geometry[$Candidate]
    }
    function Test-WalkEmptyScaffold([Xml.XmlNode]$Candidate) {
        # These exact empty structures coexist with visible pixels in audit-2026-10-25
        # 16-baseline-home-dark,25-final-inbox,02-baseline-profile.{png,xml}.
        # This is a structural capture allowance, not an opacity measurement. Labels,
        # controls, children or an ImageView invalidate it. Unknown structures refuse.
        $prefix = $Candidate.GetAttribute('package') + ':id/'
        # Native snapshots omit these flags; a legacy UIAutomator dump that sets one marks a control.
        function Test-WalkInteractiveFlag([Xml.XmlNode]$Flagged) {
            return @('long-clickable','checkable','checked','focusable','focused','password' | Where-Object { $Flagged.GetAttribute($_) -cne '' -and $Flagged.GetAttribute($_) -cne 'false' }).Count -ne 0
        }
        function Test-WalkInertNode([Xml.XmlNode]$Inert) {
            return -not (Test-WalkInteractiveFlag $Inert) -and
                $Inert.GetAttribute('enabled') -ceq 'true' -and $Inert.GetAttribute('clickable') -ceq 'false' -and
                $Inert.GetAttribute('scrollable') -ceq 'false' -and $Inert.GetAttribute('selected') -ceq 'false' -and
                -not $Inert.GetAttribute('resource-id') -and
                [string]::IsNullOrWhiteSpace($Inert.GetAttribute('text') + $Inert.GetAttribute('content-desc') + $Inert.GetAttribute('hint'))
        }
        # Loaded Explore (cold-control walk 8a838eb8 capture-0004.{png,xml} and -after.xml)
        # draws an anonymous ComposeView above refreshable_container, holding exactly one
        # childless contentless View of its bounds, while the PNG shows the grid unobscured.
        if ($Candidate.GetAttribute('class') -ceq 'androidx.compose.ui.platform.ComposeView') {
            $inner = @($Candidate.SelectNodes('./node'))
            $holder = $Candidate.ParentNode
            return $Candidate.ChildNodes.Count -eq 1 -and $inner.Count -eq 1 -and $inner[0].ChildNodes.Count -eq 0 -and
                $inner[0].GetAttribute('class') -ceq 'android.view.View' -and
                (Test-WalkInertNode $Candidate) -and (Test-WalkInertNode $inner[0]) -and
                $inner[0].GetAttribute('bounds') -ceq $Candidate.GetAttribute('bounds') -and
                $holder.GetAttribute('resource-id') -ceq ($prefix + 'layout_recyclerview_parent_container') -and
                $holder.GetAttribute('bounds') -ceq $Candidate.GetAttribute('bounds') -and
                @($holder.SelectNodes('./node') | Where-Object { $_.GetAttribute('resource-id') -ceq ($prefix + 'refreshable_container') }).Count -eq 1
        }
        if ($Candidate.GetAttribute('class') -cnotin @('android.widget.FrameLayout','android.widget.Button') -or $Candidate.ChildNodes.Count -ne 0 -or
            $Candidate.GetAttribute('enabled') -cne 'true' -or $Candidate.GetAttribute('clickable') -cne 'false' -or
            $Candidate.GetAttribute('scrollable') -cne 'false' -or (Test-WalkInteractiveFlag $Candidate) -or
            -not [string]::IsNullOrWhiteSpace($Candidate.GetAttribute('text') + $Candidate.GetAttribute('content-desc') + $Candidate.GetAttribute('hint'))) {
            return $false
        }
        $resourceId = $Candidate.GetAttribute('resource-id')
        $parent = $Candidate.ParentNode
        $parentId = $parent.GetAttribute('resource-id')
        # The loaded inbox capture has a contentless virtual Button beside its thread
        # description, at exactly the same bounds. Scope that semantic scaffold to the
        # anonymous inbox row and its composite thread description, not arbitrary buttons.
        if ($Candidate.GetAttribute('class') -ceq 'android.widget.Button') {
            if ($resourceId -or $parentId -or $parent.GetAttribute('class') -cne 'android.view.View' -or
                $Candidate.SelectNodes("ancestor::node[@resource-id='${prefix}inbox_refreshable_thread_list_recyclerview']").Count -ne 1) { return $false }
            return @($parent.SelectNodes('./node') | Where-Object {
                $_.GetAttribute('class') -ceq 'android.view.View' -and
                $_.GetAttribute('bounds') -ceq $Candidate.GetAttribute('bounds') -and
                $_.GetAttribute('content-desc') -match '^[^,]+,\s*[^,]+,\s*\S'
            }).Count -eq 1
        }
        $parents = @{
            layout_container_main='layout_container_main_wrapper'
            layout_container_right='layout_container_center_right_coordinator_layout'
            activity_and_camera_shared_views_main_container='layout_container_center_right_coordinator_layout'
            bottom_sheet_camera_container='swipe_navigation_container'
        }
        foreach ($name in $parents.Keys) {
            if ($resourceId -ceq ($prefix + $name) -and $parentId -ceq ($prefix + $parents[$name])) { return $true }
        }
        if ($resourceId -cin @(($prefix + 'modal_container'),($prefix + 'overlay_layout_container')) -and
            -not $parentId -and $parent.GetAttribute('class') -ceq 'android.widget.FrameLayout' -and
            @($parent.SelectNodes('./node') | Where-Object { $_.GetAttribute('resource-id') -ceq ($prefix + 'swipe_navigation_container') }).Count -eq 1) {
            return $true
        }
        if (-not $resourceId -and $parentId -ceq ($prefix + 'coordinator_root_layout') -and
            @($parent.SelectNodes('./node') | Where-Object { $_.GetAttribute('resource-id') -ceq ($prefix + 'tab_appbar') }).Count -eq 1 -and
            @($parent.SelectNodes('./node') | Where-Object { $_.GetAttribute('resource-id') -ceq ($prefix + 'profile_viewpager') }).Count -eq 1) {
            $box = Get-WalkNodeGeometry $Candidate
            $parentBox = Get-WalkNodeGeometry $parent
            return $box.Left -eq $parentBox.Left -and $box.Top -eq $parentBox.Top -and
                $box.Right -eq $parentBox.Right -and $box.Bottom -eq $parentBox.Bottom
        }
        return $false
    }
    $target = Get-WalkNodeGeometry $Node
    if ($null -ne $TargetArea) {
        foreach($edge in @('Left','Top','Right','Bottom')) { if($TargetArea.$edge -isnot [int]) { return $false } }
        if ($TargetArea.Left -lt $target.Left -or $TargetArea.Top -lt $target.Top -or
            $TargetArea.Right -gt $target.Right -or $TargetArea.Bottom -gt $target.Bottom -or
            $TargetArea.Left -ge $TargetArea.Right -or $TargetArea.Top -ge $TargetArea.Bottom) { return $false }
        $target=$TargetArea
    }
    $blockers = [Collections.Generic.List[object]]::new()
    $branch = $Node
    while ($branch.ParentNode -and $branch.ParentNode.Name -ceq 'node') {
        $branchBox = Get-WalkNodeGeometry $branch
        foreach ($sibling in @($branch.ParentNode.SelectNodes('./node'))) {
            if ([object]::ReferenceEquals($sibling, $branch) -or -not $Visible.Contains($sibling)) { continue }
            $siblingBox = Get-WalkNodeGeometry $sibling
            # Accessibility child index is not z-order. Native WalkSnapshot and legacy
            # UIAutomator record getDrawingOrder(); zero/missing/equal orders are unknown.
            if ($branchBox.KnownOrder -and $siblingBox.KnownOrder -and $siblingBox.Order -lt $branchBox.Order) { continue }
            if (Test-WalkEmptyScaffold $sibling) { continue }
            if ($siblingBox.Left -le $target.Left -and $siblingBox.Top -le $target.Top -and
                $siblingBox.Right -ge $target.Right -and $siblingBox.Bottom -ge $target.Bottom -and
                $siblingBox.Right -gt $siblingBox.Left -and $siblingBox.Bottom -gt $siblingBox.Top) { return $false }
            $left=[Math]::Max($target.Left,$siblingBox.Left); $top=[Math]::Max($target.Top,$siblingBox.Top)
            $right=[Math]::Min($target.Right,$siblingBox.Right); $bottom=[Math]::Min($target.Bottom,$siblingBox.Bottom)
            if ($left -lt $right -and $top -lt $bottom) {
                # Bound the sweep for malformed or excessively layered local evidence.
                if ($blockers.Count -ge 128) { return $false }
                $blockers.Add([pscustomobject]@{ Left=$left; Top=$top; Right=$right; Bottom=$bottom })
            }
        }
        $branch = $branch.ParentNode
    }
    if ($blockers.Count -gt 1) {
        # Two or more partial siblings can cover a target together. Sweep disjoint X
        # slices and merge their Y intervals, so overlaps aren't counted twice.
        $edges=[Collections.Generic.SortedSet[int]]::new()
        [void]$edges.Add($target.Left); [void]$edges.Add($target.Right)
        foreach($box in $blockers) { [void]$edges.Add($box.Left); [void]$edges.Add($box.Right) }
        $columns=@($edges)
        for($column=0; $column -lt $columns.Count-1; $column++) {
            $coverTo=$target.Top
            foreach($box in @($blockers | Where-Object { $_.Left -le $columns[$column] -and $_.Right -ge $columns[$column+1] } | Sort-Object Top,Bottom)) {
                if ($box.Top -gt $coverTo) { break }
                $coverTo=[Math]::Max($coverTo,$box.Bottom)
                if ($coverTo -ge $target.Bottom) { break }
            }
            if ($coverTo -lt $target.Bottom) { return $true }
        }
        return $false
    }
    return $true
}

function Get-WalkVisibleNodes {
    param([xml]$Document, [switch]$GeometryOnly)
    $geometricallyVisible = @($Document.SelectNodes('//node') | Where-Object {
        $candidateNode = $_
        $visible = $true
        $leftEdge = [int]::MinValue; $topEdge = [int]::MinValue
        $rightEdge = [int]::MaxValue; $bottomEdge = [int]::MaxValue
        while ($candidateNode -and $candidateNode.Name -ceq 'node') {
            $bounds = [regex]::Match($candidateNode.GetAttribute('bounds'), '^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$')
            if ($candidateNode.GetAttribute('visible-to-user') -cne 'true' -or -not $bounds.Success) { $visible=$false; break }
            $leftEdge = [Math]::Max($leftEdge, [int]$bounds.Groups[1].Value)
            $topEdge = [Math]::Max($topEdge, [int]$bounds.Groups[2].Value)
            $rightEdge = [Math]::Min($rightEdge, [int]$bounds.Groups[3].Value)
            $bottomEdge = [Math]::Min($bottomEdge, [int]$bounds.Groups[4].Value)
            if ($leftEdge -ge $rightEdge -or $topEdge -ge $bottomEdge) { $visible=$false; break }
            $candidateNode = $candidateNode.ParentNode
        }
        $visible
    })
    if ($GeometryOnly) { return $geometricallyVisible }
    $visible = [Collections.Generic.HashSet[Xml.XmlNode]]::new()
    foreach ($uiNode in $geometricallyVisible) { [void]$visible.Add($uiNode) }
    $geometry = [Collections.Generic.Dictionary[Xml.XmlNode,object]]::new()
    # Shared with producer action selection, so an obscured tab/row cannot be tapped
    # merely because AccessibilityNodeInfo.isVisibleToUser() remains true behind a cover.
    @($geometricallyVisible | Where-Object { Test-WalkNodeUncovered -Node $_ -Visible $visible -Geometry $geometry })
}

function Test-WalkDestinationContent {
    param($Destination, [xml]$Document, [string]$Package)
    function Get-WalkContentFailure([string]$Reason) { [pscustomobject]@{ Valid=$false; Reason=$Reason } }
    if ($Package -cnotmatch '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$' -or
        $Document.DocumentElement.Name -cne 'hierarchy') { return Get-WalkContentFailure 'Invalid app hierarchy identity.' }
    $nodes = @(Get-WalkVisibleNodes -Document $Document)
    $labels = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $idNodes = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $visibleSet = [Collections.Generic.HashSet[Xml.XmlNode]]::new()
    foreach ($uiNode in $nodes) {
        [void]$visibleSet.Add($uiNode)
        foreach ($attributeName in @('text','content-desc','hint')) {
            $label = $uiNode.GetAttribute($attributeName).Trim()
            if ($label) { [void]$labels.Add($label) }
        }
        $resourceId = $uiNode.GetAttribute('resource-id')
        if ($resourceId) {
            [void]$ids.Add($resourceId)
            if (-not $idNodes.ContainsKey($resourceId)) { $idNodes[$resourceId] = [Collections.Generic.List[Xml.XmlNode]]::new() }
            $idNodes[$resourceId].Add($uiNode)
        }
    }
    # Membership/ReferenceEquals return booleans. Never compare XML node objects with -eq.
    $roots = @($Document.SelectNodes('/hierarchy/node') | Where-Object { $visibleSet.Contains($_) })
    if ($roots.Count -ne 1 -or $roots[0].GetAttribute('package') -cne $Package -or
        @($nodes | Where-Object { $_.GetAttribute('package') -cne $Package }).Count) {
        return Get-WalkContentFailure 'The app is not the sole active visible hierarchy.'
    }
    function Get-WalkIdNode([string]$Id) {
        $fullId = if ($Id.Contains(':id/')) { $Id } else { "${Package}:id/$Id" }
        if ($idNodes.ContainsKey($fullId)) { $idNodes[$fullId].ToArray() }
    }
    function Get-WalkDescendant([Xml.XmlNode]$Node) {
        @($Node.SelectNodes('.//node') | Where-Object { $visibleSet.Contains($_) })
    }
    function Test-WalkNodeLabel([Xml.XmlNode]$Node) {
        return -not [string]::IsNullOrWhiteSpace($Node.GetAttribute('text') + $Node.GetAttribute('content-desc'))
    }
    # MessagesLock.java:872-876,927-940; PinLock.java:288-297. Android AlertDialogs
    # can have no positive button, so an alert title alone must reject the covered screen.
    $lockLabels = @('Ask to unlock','Unlock asked','Enter your PIN','Instagram is locked','Your messages are locked',
        'This chat is locked','HushGram settings are locked')
    if (@($lockLabels | Where-Object { $labels.Contains($_) }).Count -or
        @(@('android:id/alertTitle','android:id/button1','android:id/button2','android:id/button3') |
            Where-Object { $ids.Contains($_) }).Count) {
        return Get-WalkContentFailure 'A lock or dialog covers the destination.'
    }
    if (@($nodes | Where-Object { $_.GetAttribute('class') -match '(^|\.)[^.]*Dialog[^.]*$' }).Count) {
        return Get-WalkContentFailure 'A dialog covers the destination.'
    }
    # home.png/xml in native-capture/a7907be0a5284f6e8bdc47265eb0e455 and
    # audit-2026-10-25/46-input-control-instagram-explore.xml both show shimmer_content.
    $statePattern = '^(?:loading(?:[.\s\u2026]|$)|please wait(?:[.\s\u2026]|$)|(?:couldn''t|could not|unable to|failed to) (?:load|refresh|connect)\b|settings couldn''t open$|something went wrong\b|an error occurred\b|no (?:internet|connection)\b|network error\b|try again(?:[.!]|$)|retry$)'
    if ($ids.Contains("${Package}:id/shimmer_content") -or
        @($nodes | Where-Object { $_.GetAttribute('class') -match '(^|\.)ProgressBar$' }).Count -or
        @($labels | Where-Object { $_ -match $statePattern }).Count) {
        return Get-WalkContentFailure 'Loading or error content covers the destination.'
    }
    # SettingsDialog.java:154-199,276-279 creates a Back/title bar outside the preference
    # ListView. HushgramPreferenceFragment Heading/Row/Toggle bind android.R.id.title.
    # Requiring that context prevents a feed caption or preference summary imitating a row.
    $preferenceLists = @(Get-WalkIdNode 'android:id/list' | Where-Object { $_.GetAttribute('class') -ceq 'android.widget.ListView' })
    $barTitles = @($nodes | Where-Object {
        $_.GetAttribute('class') -ceq 'android.widget.TextView' -and -not $_.GetAttribute('resource-id') -and
        (Test-WalkNodeLabel $_) -and
        $_.SelectNodes('ancestor::node[@resource-id="android:id/list"]').Count -eq 0 -and
        @($_.ParentNode.SelectNodes('./node') | Where-Object {
            $visibleSet.Contains($_) -and $_.GetAttribute('class') -ceq 'android.widget.Button' -and
            $_.GetAttribute('content-desc') -ceq 'Back'
        }).Count -gt 0
    })
    $isSettingsSurface = $preferenceLists.Count -eq 1 -and $barTitles.Count -eq 1
    function Test-WalkPreferenceTitle([string]$Title, [switch]$RequireEnabled) {
        foreach ($titleNode in @(Get-WalkIdNode 'android:id/title')) {
            if ($titleNode.GetAttribute('class') -cne 'android.widget.TextView' -or $titleNode.GetAttribute('text') -ine $Title) { continue }
            $ancestors = @($titleNode.SelectNodes('ancestor::node[@resource-id="android:id/list"]') | Where-Object {
                $visibleSet.Contains($_) -and $_.GetAttribute('class') -ceq 'android.widget.ListView'
            })
            if (-not $ancestors.Count) { continue }
            if ($RequireEnabled -and @($titleNode.SelectNodes('ancestor-or-self::node') | Where-Object {
                $_.GetAttribute('enabled') -cne 'true'
            }).Count) { continue }
            return $true
        }
        return $false
    }
    if ($Destination.kind -cin @('settings-root','settings')) {
        if (-not $isSettingsSurface) { return Get-WalkContentFailure 'The HushGram settings surface is not visible.' }
        $barTitle = $barTitles[0].GetAttribute('text')
        $isRootBar = $barTitle -ceq 'HushGram'
        if ($Destination.kind -ceq 'settings-root' -and -not $isRootBar) {
            return Get-WalkContentFailure 'The settings overview is not visible.'
        }
        if ($Destination.kind -ceq 'settings' -and
            (-not $isRootBar -and ($barTitle -cne $Destination.title -or -not $Destination.page) -or
             $isRootBar -and -not (Test-WalkPreferenceTitle $Destination.title))) {
            return Get-WalkContentFailure 'The required settings section is not visible.'
        }
        if ($Destination.kind -ceq 'settings' -and $isRootBar) {
            # On the overview a row belongs to the heading above it, up to the next heading, so an
            # anchor shared by two categories (or a section left open) can't stand in for another.
            $listTitles = @($preferenceLists[0].SelectNodes('.//node[@resource-id="android:id/title"]') | Where-Object {
                $visibleSet.Contains($_) -and $_.GetAttribute('class') -ceq 'android.widget.TextView'
            })
            $owned = $false
            for ($headingIndex=0; $headingIndex -lt $listTitles.Count -and -not $owned; $headingIndex++) {
                if ($listTitles[$headingIndex].GetAttribute('text') -ine $Destination.title) { continue }
                for ($rowIndex=$headingIndex+1; $rowIndex -lt $listTitles.Count; $rowIndex++) {
                    $rowText = $listTitles[$rowIndex].GetAttribute('text')
                    if (@($Destination.headings | Where-Object { $_ -ieq $rowText }).Count) { break }
                    if (@($Destination.anchors | Where-Object { $_ -ieq $rowText }).Count -and
                        -not @($listTitles[$rowIndex].SelectNodes('ancestor-or-self::node') | Where-Object {
                            $_.GetAttribute('enabled') -cne 'true'
                        }).Count) { $owned = $true; break }
                }
            }
            if (-not $owned) { return Get-WalkContentFailure 'The settings section has no verified enabled content row under its heading.' }
        } elseif (-not @($Destination.anchors | Where-Object { Test-WalkPreferenceTitle $_ -RequireEnabled }).Count) {
            return Get-WalkContentFailure 'The settings section has no verified enabled content row.'
        }
    } elseif ($Destination.kind -ceq 'native') {
        if ($isSettingsSurface -or $labels.Contains('Search settings') -or $labels.Contains('HushGram settings') -or
            ($labels.Contains('Pause HushGram') -and $labels.Contains('Debug logging'))) {
            return Get-WalkContentFailure 'HushGram settings cover the native destination.'
        }
        $tabId = "${Package}:id/$($Destination.tab)"
        $selectedTabs = @($nodes | Where-Object {
            $_.GetAttribute('resource-id').StartsWith("${Package}:id/", [StringComparison]::Ordinal) -and
            $_.GetAttribute('resource-id') -cmatch '_tab$' -and
            ($_.GetAttribute('selected') -ceq 'true' -or
                @($_.SelectNodes('.//node[@selected="true"]') | Where-Object { $visibleSet.Contains($_) }).Count -gt 0)
        })
        if (@(Get-WalkIdNode $Destination.tab).Count -ne 1 -or $selectedTabs.Count -ne 1 -or
            $selectedTabs[0].GetAttribute('resource-id') -cne $tabId) {
            return Get-WalkContentFailure 'The required native tab is not the sole selected tab.'
        }
        foreach ($requiredId in $Destination.requiredIds) {
            if (@(Get-WalkIdNode $requiredId).Count -ne 1) { return Get-WalkContentFailure 'The native destination header is missing or duplicated.' }
        }
        # The native explicit empty state is legitimate, including a profile with zero posts.
        # IDs/title/body are present in audit-2026-10-25/02-baseline-profile.xml. Scope it
        # to the selected destination, and reject loading/error states before accepting it.
        $emptyScope = switch -CaseSensitive ($Destination.id) {
            'home' { 'android:id/list' }; 'explore' { 'recycler_view' }; 'reels' { 'clips_viewer_view_pager' }
            'inbox' { 'inbox_refreshable_thread_list_recyclerview' }; 'profile' { 'user_detail_fragment' }
        }
        $hasEmptyState = $false
        if ($emptyScope) {
            foreach ($scope in @(Get-WalkIdNode $emptyScope)) {
                foreach ($empty in @(Get-WalkDescendant $scope | Where-Object { $_.GetAttribute('resource-id') -ceq "${Package}:id/empty_state_view_root" })) {
                    $emptyChildren = @(Get-WalkDescendant $empty)
                    $hasTitle = @($emptyChildren | Where-Object { $_.GetAttribute('resource-id') -ceq "${Package}:id/igds_empty_state_title" -and
                        $_.GetAttribute('class') -ceq 'android.widget.TextView' -and -not [string]::IsNullOrWhiteSpace($_.GetAttribute('text')) }).Count -gt 0
                    $hasBody = @($emptyChildren | Where-Object { $_.GetAttribute('resource-id') -ceq "${Package}:id/igds_empty_state_body_text" -and
                        $_.GetAttribute('class') -ceq 'android.widget.TextView' -and -not [string]::IsNullOrWhiteSpace($_.GetAttribute('text')) }).Count -gt 0
                    $hasEmptyState = $hasEmptyState -or ($hasTitle -and $hasBody)
                }
            }
        }
        $hasContent = $false
        switch -CaseSensitive ($Destination.id) {
            'home' {
                $hasHeader = @(Get-WalkIdNode 'row_feed_profile_header' | Where-Object {
                    (Test-WalkNodeLabel $_) -or @(Get-WalkDescendant $_ | Where-Object { Test-WalkNodeLabel $_ }).Count -gt 0
                }).Count -gt 0
                $hasContent = $hasHeader -and ($ids.Contains("${Package}:id/row_feed_photo_imageview") -or $ids.Contains("${Package}:id/video_container"))
            }
            'explore' {
                foreach ($grid in @(Get-WalkIdNode 'recycler_view')) {
                    $hasContent = $hasContent -or @(Get-WalkDescendant $grid | Where-Object {
                        $_.GetAttribute('class') -match '(ImageView|Button|FrameLayout|ViewGroup)$' -and
                        $_.GetAttribute('content-desc') -match '\b(photo|video|reel)s?\b'
                    }).Count -gt 0
                }
            }
            'reels' {
                foreach ($pager in @(Get-WalkIdNode 'clips_viewer_view_pager')) {
                    $children = @(Get-WalkDescendant $pager)
                    $info = @($children | Where-Object { $_.GetAttribute('resource-id') -cin @(
                        "${Package}:id/clips_media_info_component", "${Package}:id/clips_viewer_media_info_container", "${Package}:id/clips_viewer_media_info") })
                    $controls = @($children | Where-Object { $_.GetAttribute('resource-id') -ceq "${Package}:id/clips_ufi_component" })
                    $hasInfo = @($info | Where-Object { (Test-WalkNodeLabel $_) -or @(Get-WalkDescendant $_ | Where-Object { Test-WalkNodeLabel $_ }).Count -gt 0 }).Count -gt 0
                    $hasControls = @($controls | Where-Object { (Test-WalkNodeLabel $_) -or @(Get-WalkDescendant $_ | Where-Object { Test-WalkNodeLabel $_ }).Count -gt 0 }).Count -gt 0
                    $hasContent = $hasContent -or ($hasInfo -and $hasControls)
                }
            }
            'inbox' {
                # A thread's composite accessible description, not Search, tabs or the notes
                # row, is the witness in audit-2026-10-25/25-final-inbox.xml. No names are fixed.
                foreach ($list in @(Get-WalkIdNode 'inbox_refreshable_thread_list_recyclerview')) {
                    $hasContent = $hasContent -or @(Get-WalkDescendant $list | Where-Object {
                        $_.GetAttribute('content-desc') -match '^[^,]+,\s*[^,]+,\s*\S' -and
                        $_.SelectNodes("ancestor::node[@resource-id='${Package}:id/cf_hub_recycler_view' or @resource-id='${Package}:id/pog_root_view']").Count -eq 0
                    }).Count -gt 0
                }
            }
            'profile' {
                foreach ($header in @(Get-WalkIdNode 'row_profile_header')) {
                    $children = @(Get-WalkDescendant $header)
                    $counts = @('profile_header_familiar_post_count_value','profile_header_familiar_followers_value','profile_header_familiar_following_value')
                    $found = @($counts | Where-Object {
                        $countId = "${Package}:id/$_"
                        @($children | Where-Object { $_.GetAttribute('resource-id') -ceq $countId -and $_.GetAttribute('text') -match '^\d[\d,.KMkm]*$' }).Count -gt 0
                    })
                    $hasContent = $hasContent -or $found.Count -eq $counts.Count
                }
            }
        }
        if (-not $hasContent -and -not $hasEmptyState) { return Get-WalkContentFailure 'The native destination has no verified loaded content or explicit empty state.' }
    } else { return Get-WalkContentFailure 'Unknown walkthrough destination type.' }
    [pscustomobject]@{ Valid=$true; Reason=$null }
}
