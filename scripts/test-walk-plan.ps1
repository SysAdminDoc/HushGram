<#
.SYNOPSIS
    Verify category selection and visible destination evidence without a device.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only. CaptureWorkRoot optionally points to saved local work captures. Root is the
    checkout the push gate tests, the one above this script by default.
#>
[CmdletBinding()]
param([string]$CaptureWorkRoot, [string]$Root)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'walk-plan.ps1')
$script:checks = 0
$script:package = 'com.instagram.android'
$repo = if ($Root) { $Root } else { Split-Path $PSScriptRoot -Parent }
$catalogPath = Join-Path $repo 'patches-list.json'
$policyPath = Join-Path $PSScriptRoot 'walk-categories.json'
$catalogText = Get-Content -LiteralPath $catalogPath -Raw
$policyText = Get-Content -LiteralPath $policyPath -Raw
$catalog = $catalogText | ConvertFrom-Json
$policy = $policyText | ConvertFrom-Json
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('hushgram-walk-plan-' + [guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $tempRoot)

function Assert-PlanTest([bool]$Condition, [string]$Message) {
    $script:checks++
    if (-not $Condition) { throw $Message }
}
function Assert-PlanRejected([scriptblock]$Action, [string]$Expected = '*') {
    $failure = $null
    try { & $Action | Out-Null } catch { $failure = $_.Exception.Message }
    Assert-PlanTest ($null -ne $failure -and $failure -like $Expected) "Expected '$Expected', received '$failure'."
}
function Get-PlanSelection([string[]]$Names) {
    $set = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($name in $Names) { [void]$set.Add($name) }
    return ,$set
}
function Get-PlanNode {
    param([string]$Id = '', [string]$Text = '', [string]$Description = '',
        [string]$Class = 'android.widget.FrameLayout', [string]$Children = '',
        [string]$Selected = 'false', [string]$Visible = 'true', [string]$Enabled = 'true',
        [string]$Bounds = '[0,0][720,1600]')
    $resourceId = if ($Id -and -not $Id.Contains(':id/')) { "${script:package}:id/$Id" } else { $Id }
    '<node package="{0}" resource-id="{1}" class="{2}" text="{3}" content-desc="{4}" hint="" selected="{5}" visible-to-user="{6}" enabled="{7}" bounds="{8}">{9}</node>' -f
        $script:package, [Security.SecurityElement]::Escape($resourceId), $Class,
        [Security.SecurityElement]::Escape($Text), [Security.SecurityElement]::Escape($Description),
        $Selected, $Visible, $Enabled, $Bounds, $Children
}
function Get-PlanDocument([string]$Children) {
    return ,([xml]('<hierarchy>' + (Get-PlanNode -Children $Children) + '</hierarchy>'))
}
function Get-NativePlanDocument {
    param($Destination, [switch]$Bare, [switch]$Empty)
    # IDs/structure are from work/audit-2026-10-25/{16-baseline-home-dark,
    # 46-input-control-instagram-explore,25-final-inbox,02-baseline-profile}.xml.
    # Reels' pager/info/control IDs are from BurnInGuard.java:107-134. Text below is
    # synthetic test data. No personal name, message, follower count or post is required.
    $tabs = foreach ($tab in @('feed_tab','clips_tab','direct_tab','search_tab','profile_tab')) {
        Get-PlanNode -Id $tab -Children (Get-PlanNode -Id 'tab_icon' -Class 'android.widget.ImageView' -Selected $(if ($tab -ceq $Destination.tab) { 'true' } else { 'false' }))
    }
    $body = @(foreach ($id in $Destination.requiredIds) { Get-PlanNode -Id $id }) -join ''
    $emptyNodes = Get-PlanNode -Id 'empty_state_view_root' -Children (
        (Get-PlanNode -Id 'igds_empty_state_title' -Class 'android.widget.TextView' -Text 'Create your first post') +
        (Get-PlanNode -Id 'igds_empty_state_body_text' -Class 'android.widget.TextView' -Text 'Make this space your own.'))
    $content = switch -CaseSensitive ($Destination.id) {
        'home' {
            if ($Empty) { Get-PlanNode -Id 'android:id/list' -Children $emptyNodes }
            elseif ($Bare) { (Get-PlanNode -Id 'row_feed_profile_header') + (Get-PlanNode -Id 'reels_tray_container') }
            else { (Get-PlanNode -Id 'row_feed_profile_header' -Description 'Test account posted a photo') +
                (Get-PlanNode -Id 'row_feed_photo_imageview' -Description 'Photo') }
        }
        'explore' {
            $children = if ($Empty) { $emptyNodes } elseif (-not $Bare) { Get-PlanNode -Class 'android.widget.ImageView' -Description 'Photo' } else { '' }
            Get-PlanNode -Id 'recycler_view' -Children $children
        }
        'reels' {
            $children = if ($Empty) { $emptyNodes } elseif (-not $Bare) {
                (Get-PlanNode -Id 'clips_media_info_component' -Children (Get-PlanNode -Class 'android.widget.TextView' -Text 'Reel caption')) +
                (Get-PlanNode -Id 'clips_ufi_component' -Children (Get-PlanNode -Class 'android.widget.Button' -Description 'Like'))
            } else { '' }
            Get-PlanNode -Id 'clips_viewer_view_pager' -Children $children
        }
        'inbox' {
            $children = if ($Empty) { $emptyNodes } elseif (-not $Bare) {
                Get-PlanNode -Class 'android.view.View' -Description 'Test account, message preview, 3d'
            } else { '' }
            Get-PlanNode -Id 'inbox_refreshable_thread_list_recyclerview' -Children $children
        }
        'profile' {
            $children = if ($Empty) { $emptyNodes } elseif (-not $Bare) {
                $counts = foreach ($id in @('profile_header_familiar_post_count_value','profile_header_familiar_followers_value','profile_header_familiar_following_value')) {
                    Get-PlanNode -Id $id -Class 'android.widget.TextView' -Text '0'
                }
                Get-PlanNode -Id 'row_profile_header' -Children ($counts -join '')
            } else { Get-PlanNode -Id 'row_profile_header' }
            Get-PlanNode -Id 'user_detail_fragment' -Children $children
        }
    }
    $document = Get-PlanDocument ($body + $content + (Get-PlanNode -Id 'tab_bar' -Children ($tabs -join '')))
    # Separate the toolbar/content/tab regions. Full-screen siblings in every fixture would
    # themselves be ambiguous occluders once coverage is checked.
    foreach ($uiNode in @($document.SelectNodes('/hierarchy/node/node/descendant-or-self::node'))) {
        $uiNode.SetAttribute('bounds','[0,240][720,1456]')
    }
    foreach ($id in $Destination.requiredIds) {
        $document.SelectSingleNode("//node[@resource-id='${script:package}:id/$id']").SetAttribute('bounds','[0,128][720,240]')
    }
    $boxes = [ordered]@{
        row_feed_profile_header='[0,240][720,360]'; row_feed_photo_imageview='[0,360][720,1456]'
        clips_media_info_component='[0,1180][600,1320]'; clips_ufi_component='[600,300][720,1180]'
        row_profile_header='[0,240][720,600]'
        profile_header_familiar_post_count_value='[0,400][200,460]'
        profile_header_familiar_followers_value='[200,400][400,460]'
        profile_header_familiar_following_value='[400,400][600,460]'
        igds_empty_state_title='[0,700][720,800]'; igds_empty_state_body_text='[0,800][720,900]'
    }
    foreach ($id in $boxes.Keys) {
        foreach ($uiNode in @($document.SelectNodes("//node[@resource-id='${script:package}:id/$id']/descendant-or-self::node"))) {
            $uiNode.SetAttribute('bounds',$boxes[$id])
        }
    }
    $tabBar = $document.SelectSingleNode("//node[@resource-id='${script:package}:id/tab_bar']")
    $tabBar.SetAttribute('bounds','[0,1456][720,1600]')
    $column = 0
    foreach ($tabNode in @($tabBar.ChildNodes)) {
        $tabBounds = '[{0},1456][{1},1600]' -f ($column * 144), (($column + 1) * 144)
        foreach ($uiNode in @($tabNode.SelectNodes('descendant-or-self::node'))) { $uiNode.SetAttribute('bounds',$tabBounds) }
        $column++
    }
    return ,$document
}
function Get-SettingsPlanDocument {
    param($Destination, [switch]$Page, [string[]]$Headings, [string[]]$Rows)
    # SettingsDialog.java:154-199,276-279 and HushgramPreferenceFragment.java:5173-5249.
    # The preference title and summary have distinct framework IDs in 04-baseline-settings.xml.
    # -Rows lays out the overview list top to bottom; an entry also in -Headings is drawn as a heading.
    $title = if ($Page) { $Destination.title } else { 'HushGram' }
    $bar = Get-PlanNode -Class 'android.widget.LinearLayout' -Children (
        (Get-PlanNode -Class 'android.widget.Button' -Description 'Back') +
        (Get-PlanNode -Class 'android.widget.TextView' -Text $title))
    if ($Rows) {
        $rows = ($Rows | ForEach-Object {
            if ($_ -cin $Headings) { Get-PlanNode -Id 'android:id/title' -Class 'android.widget.TextView' -Text $_.ToUpperInvariant() }
            else { Get-PlanNode -Class 'android.widget.LinearLayout' -Children (Get-PlanNode -Id 'android:id/title' -Class 'android.widget.TextView' -Text $_) }
        }) -join ''
    } else {
        $rows = if ($Destination.kind -ceq 'settings' -and -not $Page) {
            Get-PlanNode -Id 'android:id/title' -Class 'android.widget.TextView' -Text $Destination.title.ToUpperInvariant()
        } else { '' }
        $rows += Get-PlanNode -Class 'android.widget.LinearLayout' -Children (
            Get-PlanNode -Id 'android:id/title' -Class 'android.widget.TextView' -Text $Destination.anchors[0])
    }
    $document = Get-PlanDocument ($bar + (Get-PlanNode -Id 'android:id/list' -Class 'android.widget.ListView' -Children $rows))
    foreach ($uiNode in @($document.DocumentElement.FirstChild.FirstChild.SelectNodes('descendant-or-self::node'))) {
        $uiNode.SetAttribute('bounds','[0,0][720,128]')
    }
    $document.DocumentElement.FirstChild.FirstChild.FirstChild.SetAttribute('bounds','[0,0][96,128]')
    $document.DocumentElement.FirstChild.FirstChild.LastChild.SetAttribute('bounds','[96,0][720,128]')
    $list = $document.SelectSingleNode('//node[@resource-id="android:id/list"]')
    $list.SetAttribute('bounds','[0,128][720,1600]')
    $rowTop = 128
    foreach ($row in @($list.ChildNodes)) {
        foreach ($uiNode in @($row.SelectNodes('descendant-or-self::node'))) {
            $uiNode.SetAttribute('bounds', ('[0,{0}][720,{1}]' -f $rowTop, ($rowTop + 160)))
        }
        $rowTop += 160
    }
    return ,$document
}
function Assert-PlanContent($Destination, [xml]$Document, [bool]$Expected, [string]$Case) {
    $result = Test-WalkDestinationContent -Destination $Destination -Document $Document -Package $script:package
    Assert-PlanTest ($result.Valid -eq $Expected) "$Case returned $($result.Valid): $($result.Reason)"
}
function Invoke-PlanPolicyMutation([scriptblock]$Mutation) {
    $copy = $policyText | ConvertFrom-Json
    & $Mutation $copy
    $path = Join-Path $tempRoot 'policy.json'
    $copy | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $path
    Get-WalkRequiredDestinations -CatalogPath $catalogPath -SelectedPatches @() -PolicyPath $path
}

try {
    $allNames = @($catalog.patches.name)
    $known = Get-PlanSelection $allNames
    $all = @(Get-WalkRequiredDestinations -CatalogPath $catalogPath -SelectedPatches $allNames)
    $defaults = @(Get-WalkRequiredDestinations -CatalogPath $catalogPath -SelectedPatches @($catalog.patches | Where-Object use | ForEach-Object name))
    Assert-PlanTest (@($all | Where-Object kind -CEQ 'settings').Count -eq 23) 'All patches must require all 23 headings.'
    # A renamed heading or row leaves the walk looking for text settings no longer draw.
    $settingsSource = (Get-ChildItem -LiteralPath (Join-Path $repo 'extensions\instagram\src\main\java\app\hushgram\extension\instagram\settings') -Filter '*.java' |
        ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
    foreach ($category in $policy.categories) {
        foreach ($title in @($category.title) + @($category.anchors.title)) {
            Assert-PlanTest ($settingsSource.Contains('L10n.t("' + $title + '"')) "Walk policy text '$title' ($($category.id)) is not a settings title in the source."
        }
    }
    # The walk expands a heading and wants a verified row within a screen of it; an anchor 14 rows down failed 0.0.17's walk.
    foreach ($selection in @(, $all) + @(, $defaults)) {
        foreach ($destination in @($selection | Where-Object { $_.kind -ceq 'settings' -and $_.page })) {
            $builder = ($policy.categories | Where-Object id -CEQ $destination.id).builder
            $builderPath = Join-Path $repo "extensions\instagram\src\main\java\app\hushgram\extension\instagram\settings\$builder.java"
            $texts = @([regex]::Matches((Get-Content -LiteralPath $builderPath -Raw), 'L10n\.t\("((?:[^"\\]|\\.)*)"') | ForEach-Object { $_.Groups[1].Value })
            $first = @($destination.anchors | ForEach-Object { [Array]::IndexOf($texts, [string]$_) } | Where-Object { $_ -ge 0 } | Sort-Object)[0]
            Assert-PlanTest ($null -ne $first -and $first -le 10) "Walk anchors for '$($destination.id)' start at text $first of $builder; one must sit in the first rows under the heading."
        }
    }
    Assert-PlanTest (@($defaults | Where-Object kind -CEQ 'settings').Count -eq 23) 'Default patches must require all 23 headings.'
    Assert-PlanTest (@($all | Where-Object { $_.kind -ceq 'settings' -and $_.page }).Count -eq 23) 'Exactly 23 settings pages are expected.'
    $pause = $all | Where-Object id -CEQ 'settings-pause'
    Assert-PlanTest ($pause.page -is [bool] -and $pause.page -and $pause.title -ceq 'Pause and diagnostics') 'Pause must fold like the other categories.'
    Assert-PlanTest (@(Get-WalkRequiredDestinations -CatalogPath $catalogPath -SelectedPatches @()).Count -eq 5) 'A build without settings should require only native destinations.'
    $privacy = $policy.categories | Where-Object id -CEQ 'settings-privacy'
    $ghost = $privacy.when.conditions[1]
    Assert-PlanTest (-not (Test-WalkPatchCondition $ghost (Get-PlanSelection @()) -Known $known)) 'Zero Ghost mode families must not qualify.'
    for ($i = 0; $i -lt $ghost.patches.Count; $i++) {
        Assert-PlanTest (-not (Test-WalkPatchCondition $ghost (Get-PlanSelection @($ghost.patches[$i])) -Known $known)) 'One Ghost mode family must not qualify.'
        for ($j = $i + 1; $j -lt $ghost.patches.Count; $j++) {
            Assert-PlanTest (Test-WalkPatchCondition $ghost (Get-PlanSelection @($ghost.patches[$i], $ghost.patches[$j])) -Known $known) 'Every distinct Ghost mode pair must qualify.'
        }
    }
    foreach ($name in $privacy.when.conditions[0].patches) {
        Assert-PlanTest (Test-WalkPatchCondition $privacy.when (Get-PlanSelection @($name)) -Known $known) "Direct privacy patch '$name' must qualify alone."
    }
    $oneGhost = @(Get-WalkRequiredDestinations -CatalogPath $catalogPath -SelectedPatches @('HushGram settings','View stories anonymously'))
    Assert-PlanTest (@($oneGhost | Where-Object id -CEQ 'settings-privacy').Count -eq 0) 'One ghost-only patch must not create privacy.'
    $twoGhost = @(Get-WalkRequiredDestinations -CatalogPath $catalogPath -SelectedPatches @('HushGram settings','View stories anonymously','View live anonymously'))
    Assert-PlanTest (@($twoGhost | Where-Object id -CEQ 'settings-privacy').Count -eq 1) 'A ghost pair must require privacy.'
    Assert-PlanRejected { Get-WalkRequiredDestinations -CatalogPath $catalogPath -SelectedPatches @('HushGram settings','Show all recent searches') } '*no applicable verified anchor*'
    Assert-PlanRejected { Get-WalkRequiredDestinations -CatalogPath $catalogPath -SelectedPatches @('HushGram settings','View DM photos and videos anonymously') } '*no applicable verified anchor*'
    Assert-PlanRejected { Get-WalkRequiredDestinations -CatalogPath $catalogPath -SelectedPatches @('Unknown public patch') } '*unknown or duplicate*'
    Assert-PlanRejected { Get-WalkRequiredDestinations -CatalogPath $catalogPath -SelectedPatches @('Hide ads','Hide ads') } '*unknown or duplicate*'
    Assert-PlanRejected { Get-WalkRequiredDestinations -CatalogPath $catalogPath -SelectedPatches @('hide ads') } '*unknown or duplicate*'
    $badConditions = @('null','{"op":"ANY","patches":["Hide ads"]}','{"op":"any","patches":"Hide ads"}',
        '{"op":"any","patches":[]}','{"op":"all","patches":["Hide ads","Hide ads"]}',
        '{"op":"any","patches":["Unknown patch"]}','{"op":"unconditional","patches":[]}',
        '{"op":"any_of","conditions":[]}','{"op":"all_of","conditions":[null]}',
        '{"op":"any_of","conditions":{"op":"unconditional"}}','{"patches":["Hide ads"]}')
    foreach ($count in @('0','-1','2','true','"1"','1.5')) { $badConditions += '{"op":"at_least","count":' + $count + ',"patches":["Hide ads"]}' }
    foreach ($json in $badConditions) {
        $condition = $json | ConvertFrom-Json
        Assert-PlanRejected { Test-WalkPatchCondition -Condition $condition -Selected $known -Known $known }
    }
    $deep = @{op='unconditional'}
    1..10 | ForEach-Object { $deep = @{op='any_of'; conditions=@($deep)} }
    Assert-PlanRejected { Test-WalkPatchCondition $deep $known -Known $known } '*Invalid settings category condition*'
    $allCondition = @{op='all'; patches=@('Hide ads','View stories anonymously')}
    Assert-PlanTest (Test-WalkPatchCondition $allCondition $known -Known $known) 'All must require every listed patch.'
    Assert-PlanTest (-not (Test-WalkPatchCondition $allCondition (Get-PlanSelection @('Hide ads')) -Known $known)) 'All cannot behave as any.'
    $mutations = @(
        { param($p) $p.schema = '1' }, { param($p) $p.prerequisite = 'Unknown patch' },
        { param($p) $p.categories[2].id = $p.categories[1].id },
        { param($p) $p.categories[2].title = $p.categories[1].title },
        { param($p) $p.categories[2].id = 'settings-INVALID' },
        { param($p) $p.categories[2].builder = 'Missing' },
        { param($p) $p.categories[2].page = 'false' },
        { param($p) $p.categories[2].anchors[0].title = ' ' },
        { param($p) $p.categories[2].anchors[1].title = $p.categories[2].anchors[0].title },
        { param($p) $p.categories[2].when = @{op='any';patches=@('Unknown patch')} },
        { param($p) $p.categories[2].anchors[0].when = @{op='any';patches=@('Unknown patch')} },
        { param($p) $p.categories[2].anchors = @() })
    foreach ($mutation in $mutations) { Assert-PlanRejected { Invoke-PlanPolicyMutation $mutation } }
    foreach ($badName in @('', 'Hide ads')) {
        $copy = $catalogText | ConvertFrom-Json
        $copy.patches[0].name = $badName
        $badCatalog = Join-Path $tempRoot 'catalog.json'
        $copy | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $badCatalog
        Assert-PlanRejected { Get-WalkRequiredDestinations -CatalogPath $badCatalog -SelectedPatches @() } '*missing or duplicate*'
    }

    foreach ($destination in @($all | Where-Object kind -CEQ 'native')) {
        Assert-PlanContent $destination (Get-NativePlanDocument $destination) $true "$($destination.id) loaded witness"
        Assert-PlanContent $destination (Get-NativePlanDocument $destination -Empty) $true "$($destination.id) explicit empty state"
        Assert-PlanContent $destination (Get-NativePlanDocument $destination -Bare) $false "$($destination.id) generic container"
        $document = Get-NativePlanDocument $destination -Bare
        $container = $document.SelectSingleNode("//node[@resource-id='${script:package}:id/$($destination.contentIds[0])']")
        $fragment = $document.CreateDocumentFragment()
        $fragment.InnerXml = Get-PlanNode -Class 'android.widget.TextView' -Text 'Container is present'
        [void]$container.AppendChild($fragment)
        Assert-PlanContent $destination $document $false "$($destination.id) arbitrary container text"
        foreach ($state in @('shimmer','progress','loading','error')) {
            $document = Get-NativePlanDocument $destination
            $overlay = switch ($state) {
                'shimmer' { Get-PlanNode -Id 'shimmer_content' }
                'progress' { Get-PlanNode -Class 'android.widget.ProgressBar' }
                'loading' { Get-PlanNode -Class 'android.widget.TextView' -Text 'Loading...' }
                'error' { Get-PlanNode -Class 'android.widget.TextView' -Text "Couldn't load" }
            }
            $fragment = $document.CreateDocumentFragment(); $fragment.InnerXml = $overlay
            [void]$document.DocumentElement.FirstChild.AppendChild($fragment)
            Assert-PlanContent $destination $document $false "$($destination.id) $state overlay"
        }
        $document = Get-NativePlanDocument $destination
        $otherTab = @('feed_tab','clips_tab','direct_tab','search_tab','profile_tab') | Where-Object { $_ -cne $destination.tab } | Select-Object -First 1
        $document.SelectSingleNode("//node[@resource-id='${script:package}:id/$otherTab']").SetAttribute('selected','true')
        Assert-PlanContent $destination $document $false "$($destination.id) simultaneous selected tabs"
        $document = Get-NativePlanDocument $destination
        [void]$document.DocumentElement.AppendChild($document.DocumentElement.FirstChild.CloneNode($true))
        Assert-PlanContent $destination $document $false "$($destination.id) extra active root"
        $document.DocumentElement.LastChild.SetAttribute('visible-to-user','false')
        Assert-PlanContent $destination $document $true "$($destination.id) hidden inactive root"
        $document = Get-NativePlanDocument $destination
        $document.SelectSingleNode("//node[@resource-id='${script:package}:id/$($destination.tab)']/node").SetAttribute('visible-to-user','false')
        Assert-PlanContent $destination $document $false "$($destination.id) hidden selected descendant"
        $document = Get-NativePlanDocument $destination
        $document.DocumentElement.FirstChild.LastChild.SetAttribute('package','other.package')
        Assert-PlanContent $destination $document $false "$($destination.id) foreign visible descendant"
        $document = Get-NativePlanDocument $destination -Empty
        $document.SelectSingleNode("//node[@resource-id='${script:package}:id/igds_empty_state_body_text']").SetAttribute('text','')
        Assert-PlanContent $destination $document $false "$($destination.id) ambiguous empty container"
    }
    $homeDestination = $all | Where-Object id -CEQ 'home'
    # Home on an account that follows nobody draws the IGDS headline form (release walk 2026-10-31).
    $document = Get-NativePlanDocument $homeDestination -Empty
    foreach ($idPair in @(@('igds_empty_state_title','igds_headline_emphasized_headline'), @('igds_empty_state_body_text','igds_headline_body'))) {
        $document.SelectSingleNode("//node[@resource-id='${script:package}:id/$($idPair[0])']").SetAttribute('resource-id', "${script:package}:id/$($idPair[1])")
    }
    Assert-PlanContent $homeDestination $document $true 'Home headline empty state'
    $document.SelectSingleNode("//node[@resource-id='${script:package}:id/igds_headline_body']").SetAttribute('text','')
    Assert-PlanContent $homeDestination $document $false 'Home headline empty state without a body'
    $reelsDestination = $all | Where-Object id -CEQ 'reels'
    # Hide the tab bar on Reels slides the bar below the window while a reel plays (release walk 2026-10-31).
    foreach ($case in @('slid','slid wrong tab','slid two tabs','covered','partly shown')) {
        $document = Get-NativePlanDocument $reelsDestination
        $tabBar = $document.SelectSingleNode("//node[@resource-id='${script:package}:id/tab_bar']")
        foreach ($uiNode in @($tabBar.SelectNodes('descendant-or-self::node'))) {
            $uiNode.SetAttribute('visible-to-user','false')
            if ($case -cne 'covered') { $uiNode.SetAttribute('bounds','[0,1623][720,1600]') }
        }
        if ($case -ceq 'slid wrong tab') {
            $document.SelectSingleNode("//node[@resource-id='${script:package}:id/clips_tab']/node").SetAttribute('selected','false')
            $document.SelectSingleNode("//node[@resource-id='${script:package}:id/feed_tab']/node").SetAttribute('selected','true')
        }
        if ($case -ceq 'slid two tabs') {
            $document.SelectSingleNode("//node[@resource-id='${script:package}:id/feed_tab']").SetAttribute('selected','true')
        }
        if ($case -ceq 'partly shown') {
            $tabBar.SetAttribute('visible-to-user','true'); $tabBar.SetAttribute('bounds','[0,1580][720,1600]')
        }
        Assert-PlanContent $reelsDestination $document ($case -ceq 'slid') "Reels with its tab bar $case"
        $barSlid = $null -ne (Get-WalkSlidTabBar -Document $document -Package $script:package)
        Assert-PlanTest ($barSlid -eq ($case -clike 'slid*')) "Reels slid bar detection, $case."
    }
    Assert-PlanTest ($null -eq (Get-WalkSlidTabBar -Document (Get-NativePlanDocument $reelsDestination) -Package $script:package)) 'A shown tab bar is not slid.'
    $document = Get-NativePlanDocument $homeDestination
    $fragment = $document.CreateDocumentFragment()
    $fragment.InnerXml = Get-PlanNode -Class 'android.widget.FrameLayout' -Text 'Screen unavailable'
    [void]$document.DocumentElement.FirstChild.AppendChild($fragment)
    Assert-PlanContent $homeDestination $document $false 'Later full-screen sibling covers verified Home content'
    foreach($shape in @('halves','overlap','quadrants','gap','lower','hidden')) {
        $document=Get-PlanDocument (Get-PlanNode -Id 'coverage_target' -Bounds '[0,0][100,100]')
        $document.DocumentElement.FirstChild.FirstChild.SetAttribute('drawing-order','5')
        $boxes=switch($shape) {
            'quadrants' { @('[0,0][50,50]','[50,0][100,50]','[0,50][50,100]','[50,50][100,100]') }
            'overlap' { @('[0,0][60,100]','[40,0][100,100]') }
            'gap' { @('[0,0][49,100]','[51,0][100,100]') }
            default { @('[0,0][50,100]','[50,0][100,100]') }
        }
        foreach($box in $boxes) {
            $fragment=$document.CreateDocumentFragment(); $fragment.InnerXml=Get-PlanNode -Bounds $box -Text 'Cover'
            $fragment.FirstChild.SetAttribute('drawing-order',$(if($shape -ceq 'lower'){'3'}else{'8'}))
            if($shape -ceq 'hidden') { $fragment.FirstChild.SetAttribute('visible-to-user','false') }
            [void]$document.DocumentElement.FirstChild.AppendChild($fragment)
        }
        $visibleTargets=@(Get-WalkVisibleNodes $document | Where-Object { $_.GetAttribute('resource-id') -ceq "${script:package}:id/coverage_target" })
        Assert-PlanTest (($visibleTargets.Count -eq 1) -eq ($shape -cin @('gap','lower','hidden'))) "Combined sibling coverage failed for $shape."
    }
    foreach ($destination in @($all | Where-Object kind -CEQ 'native')) {
        $document=Get-NativePlanDocument $destination
        $fragment=$document.CreateDocumentFragment()
        $fragment.InnerXml=Get-PlanNode -Class 'android.widget.FrameLayout' -Text 'Screen unavailable'
        [void]$document.DocumentElement.FirstChild.AppendChild($fragment)
        Assert-PlanContent $destination $document $false "$($destination.id) covered sibling"
    }
    foreach ($order in @('8','5','')) {
        $document=Get-NativePlanDocument $homeDestination
        foreach($child in @($document.SelectNodes('/hierarchy/node/node'))) { $child.SetAttribute('drawing-order','5') }
        $fragment=$document.CreateDocumentFragment(); $fragment.InnerXml=Get-PlanNode -Text 'Cover'
        $fragment.FirstChild.SetAttribute('drawing-order',$order)
        [void]$document.DocumentElement.FirstChild.AppendChild($fragment)
        Assert-PlanContent $homeDestination $document $false "Higher, equal or unknown sibling order $order"
    }
    foreach ($control in @('lower','hidden','small','ancestor')) {
        $document=Get-NativePlanDocument $homeDestination
        if ($control -ceq 'ancestor') {
            $wrapper=$document.CreateElement('node')
            foreach($attribute in @('package','visible-to-user','enabled','bounds')) {
                $wrapper.SetAttribute($attribute,$document.DocumentElement.FirstChild.GetAttribute($attribute))
            }
            foreach($child in @($document.DocumentElement.FirstChild.ChildNodes)) { [void]$wrapper.AppendChild($child) }
            [void]$document.DocumentElement.FirstChild.AppendChild($wrapper)
        } else {
            foreach($child in @($document.SelectNodes('/hierarchy/node/node'))) { $child.SetAttribute('drawing-order','5') }
            $fragment=$document.CreateDocumentFragment()
            $fragment.InnerXml=Get-PlanNode -Text 'Decoration' -Bounds $(if($control -ceq 'small'){'[0,0][20,20]'}else{'[0,0][720,1600]'}) `
                -Visible $(if($control -ceq 'hidden'){'false'}else{'true'})
            $fragment.FirstChild.SetAttribute('drawing-order',$(if($control -ceq 'lower'){'3'}else{'8'}))
            [void]$document.DocumentElement.FirstChild.AppendChild($fragment)
        }
        Assert-PlanContent $homeDestination $document $true "Harmless $control structure"
    }
    # Instagram's loaded inbox exposes a contentless semantic Button alongside its
    # composite thread description. Its exact capture-backed structure can coexist.
    $inboxDestination=$all | Where-Object id -CEQ 'inbox'
    $document=Get-NativePlanDocument $inboxDestination
    $thread=$document.SelectSingleNode('//node[@content-desc="Test account, message preview, 3d"]')
    $row=$document.CreateElement('node')
    foreach($attribute in @('package','visible-to-user','enabled','bounds')) { $row.SetAttribute($attribute,$thread.GetAttribute($attribute)) }
    $row.SetAttribute('class','android.view.View')
    [void]$thread.ParentNode.AppendChild($row); [void]$row.AppendChild($thread)
    $fragment=$document.CreateDocumentFragment()
    $fragment.InnerXml=Get-PlanNode -Class 'android.widget.Button' -Bounds $thread.GetAttribute('bounds')
    $button=$fragment.FirstChild
    $button.SetAttribute('clickable','false'); $button.SetAttribute('scrollable','false')
    [void]$row.AppendChild($fragment)
    Assert-PlanContent $inboxDestination $document $true 'Exact inbox semantic scaffold'
    $button.SetAttribute('text','Cover')
    Assert-PlanContent $inboxDestination $document $false 'Labeled control cannot use scaffold allowance'
    # Loaded Explore draws an anonymous ComposeView, holding one contentless View of the same
    # bounds, above refreshable_container (cold-control walk 8a838eb8 capture-0004).
    $exploreDestination=$all | Where-Object id -CEQ 'explore'
    function Get-ExploreScaffoldDocument([scriptblock]$Mutation) {
        $document=Get-NativePlanDocument $exploreDestination
        $grid=$document.SelectSingleNode("//node[@resource-id='${script:package}:id/recycler_view']")
        $bounds=$grid.GetAttribute('bounds')
        $fragment=$document.CreateDocumentFragment()
        $fragment.InnerXml=Get-PlanNode -Id 'layout_recyclerview_parent_container' -Bounds $bounds -Children (
            (Get-PlanNode -Class 'androidx.compose.ui.platform.ComposeView' -Bounds $bounds -Children (Get-PlanNode -Class 'android.view.View' -Bounds $bounds)) +
            (Get-PlanNode -Id 'refreshable_container' -Bounds $bounds))
        $container=$fragment.FirstChild
        [void]$grid.ParentNode.ReplaceChild($fragment,$grid)
        $compose=$container.FirstChild; $refreshable=$container.LastChild
        [void]$refreshable.AppendChild($grid)
        foreach($uiNode in @($compose,$compose.FirstChild)) {
            foreach($flag in @('clickable','scrollable')) { $uiNode.SetAttribute($flag,'false') }
        }
        $compose.SetAttribute('drawing-order','8'); $compose.FirstChild.SetAttribute('drawing-order','0')
        $refreshable.SetAttribute('drawing-order','2'); $grid.SetAttribute('drawing-order','1')
        if ($Mutation) { & $Mutation $compose $container }
        return ,$document
    }
    Assert-PlanContent $exploreDestination (Get-ExploreScaffoldDocument) $true 'Exact Explore Compose scaffold'
    $scaffoldControls=[ordered]@{
        'labeled ComposeView'={ param($c) $c.SetAttribute('content-desc','Cover') }
        'labeled inner View'={ param($c) $c.FirstChild.SetAttribute('text','Cover') }
        'clickable ComposeView'={ param($c) $c.SetAttribute('clickable','true') }
        'clickable inner View'={ param($c) $c.FirstChild.SetAttribute('clickable','true') }
        'scrollable inner View'={ param($c) $c.FirstChild.SetAttribute('scrollable','true') }
        'selected ComposeView'={ param($c) $c.SetAttribute('selected','true') }
        'disabled inner View'={ param($c) $c.FirstChild.SetAttribute('enabled','false') }
        'missing click flag'={ param($c) $c.FirstChild.RemoveAttribute('clickable') }
        'identified ComposeView'={ param($c) $c.SetAttribute('resource-id',"${script:package}:id/explore_overlay") }
        'populated inner View'={ param($c) [void]$c.FirstChild.AppendChild($c.FirstChild.CloneNode($false)) }
        'second inner View'={ param($c) [void]$c.AppendChild($c.FirstChild.CloneNode($true)) }
        'childless ComposeView'={ param($c) [void]$c.RemoveChild($c.FirstChild) }
        'inner ImageView'={ param($c) $c.FirstChild.SetAttribute('class','android.widget.ImageView') }
        'unequal inner bounds'={ param($c) $c.FirstChild.SetAttribute('bounds','[0,240][720,800]') }
        'other parent'={ param($c,$p) $p.SetAttribute('resource-id',"${script:package}:id/other_container") }
        'no refreshable sibling'={ param($c,$p) $p.LastChild.SetAttribute('resource-id',"${script:package}:id/other_container") }
    }
    # Legacy UIAutomator dumps also carry these flags (audit-2026-10-25 46-input-control-instagram-explore).
    $legacyFlags=@('long-clickable','checkable','checked','focusable','focused','password')
    Assert-PlanContent $exploreDestination (Get-ExploreScaffoldDocument {
        param($c) foreach($uiNode in @($c,$c.FirstChild)) { foreach($flag in $legacyFlags) { $uiNode.SetAttribute($flag,'false') } }
    }) $true 'Exact Explore Compose scaffold with legacy flags off'
    foreach($flag in $legacyFlags) {
        $scaffoldControls["$flag ComposeView"]=[scriptblock]::Create("param(`$c) `$c.SetAttribute('$flag','true')")
        $scaffoldControls["$flag inner View"]=[scriptblock]::Create("param(`$c) `$c.FirstChild.SetAttribute('$flag','true')")
    }
    foreach($name in $scaffoldControls.Keys) {
        Assert-PlanContent $exploreDestination (Get-ExploreScaffoldDocument $scaffoldControls[$name]) $false "Explore Compose cover: $name"
    }
    foreach($flag in $legacyFlags) {
        $button.RemoveAttribute('text'); $button.SetAttribute($flag,'true')
        Assert-PlanContent $inboxDestination $document $false "Inbox scaffold Button with $flag cannot use scaffold allowance"
        $button.RemoveAttribute($flag)
    }
    Assert-PlanContent $inboxDestination $document $true 'Inbox semantic scaffold with its flags restored'
    foreach ($overlay in @(
        (Get-PlanNode -Id 'android:id/alertTitle' -Class 'android.widget.TextView' -Text 'Dialog'),
        (Get-PlanNode -Class 'android.widget.TextView' -Text 'Your messages are locked'),
        (Get-PlanNode -Class 'android.widget.TextView' -Text 'Unlock asked'),
        (Get-PlanNode -Class 'android.widget.TextView' -Text 'Enter your PIN'))) {
        $document = Get-NativePlanDocument $homeDestination
        $fragment = $document.CreateDocumentFragment(); $fragment.InnerXml = $overlay
        [void]$document.DocumentElement.FirstChild.AppendChild($fragment)
        Assert-PlanContent $homeDestination $document $false 'Dialog/lock/PIN covers native content'
    }
    $section = $all | Where-Object id -CEQ 'settings-privacy'
    $overview = $all | Where-Object id -CEQ 'settings-root'
    Assert-PlanContent $overview (Get-SettingsPlanDocument $overview) $true 'Settings root with Search scrolled offscreen'
    Assert-PlanContent $section (Get-SettingsPlanDocument $section) $true 'Long-list settings section'
    Assert-PlanContent $section (Get-SettingsPlanDocument $section -Page) $true 'Category page toolbar'
    Assert-PlanContent $pause (Get-SettingsPlanDocument $pause) $true 'Open Pause section on the overview'
    Assert-PlanContent $pause (Get-SettingsPlanDocument $pause -Page) $true 'Pause category toolbar'
    foreach($destination in @($section,$overview)) {
        $document=Get-SettingsPlanDocument $destination
        $fragment=$document.CreateDocumentFragment(); $fragment.InnerXml=Get-PlanNode -Text 'Screen unavailable'
        [void]$document.DocumentElement.FirstChild.AppendChild($fragment)
        Assert-PlanContent $destination $document $false 'Sibling covers settings content'
    }
    $document = Get-SettingsPlanDocument $section
    $document.SelectSingleNode('//node[@resource-id="android:id/list"]').SetAttribute('enabled','false')
    Assert-PlanContent $section $document $false 'Disabled ancestor cannot verify a row'
    $document = Get-SettingsPlanDocument $section
    @($document.SelectNodes('//node[@resource-id="android:id/title"]'))[-1].SetAttribute('enabled','false')
    Assert-PlanContent $section $document $false 'Disabled title cannot verify a row'
    foreach ($lockLabel in @('Ask to unlock','Unlock asked','Enter your PIN')) {
        $document = Get-SettingsPlanDocument $section
        $fragment = $document.CreateDocumentFragment()
        $fragment.InnerXml = Get-PlanNode -Class 'android.widget.TextView' -Text $lockLabel
        [void]$document.DocumentElement.FirstChild.AppendChild($fragment)
        Assert-PlanContent $section $document $false "Settings covered by $lockLabel"
    }
    $document = Get-SettingsPlanDocument $section
    $anchorNode = @($document.SelectNodes('//node[@resource-id="android:id/title"]'))[-1]
    $anchorNode.SetAttribute('resource-id','android:id/summary')
    Assert-PlanContent $section $document $false 'Summary cannot impersonate a title'
    # Expanded rows sit under their own heading, up to the next one. Feed and Reels share an anchor.
    $feed = $all | Where-Object id -CEQ 'settings-feed'
    $reels = $all | Where-Object id -CEQ 'settings-reels'
    $shared = 'Hide suggested accounts'
    Assert-PlanTest ($feed.anchors -ccontains $shared -and $reels.anchors -ccontains $shared -and $section.headings -ccontains $feed.title) 'The shared-anchor fixture no longer matches the policy.'
    Assert-PlanContent $feed (Get-SettingsPlanDocument $feed -Headings $section.headings -Rows @($reels.title,$feed.title,$shared)) $true 'Open section under a folded heading'
    Assert-PlanContent $reels (Get-SettingsPlanDocument $reels -Headings $section.headings -Rows @($reels.title,$feed.title,$shared)) $false 'Another section''s row under a later heading'
    Assert-PlanContent $reels (Get-SettingsPlanDocument $reels -Headings $section.headings -Rows @($shared,$reels.title)) $false 'A row above its heading'
    Assert-PlanContent $reels (Get-SettingsPlanDocument $reels -Headings $section.headings -Rows @($reels.title,$feed.title)) $false 'A folded heading with no rows'
    Assert-PlanContent $reels (Get-SettingsPlanDocument $reels -Headings $section.headings -Rows @($feed.title,$shared,$reels.title,$shared)) $true 'Both open, each row under its own heading'
    $headingTitles = [Collections.Generic.HashSet[string]]::new([string[]]@($policy.categories.title), [StringComparer]::OrdinalIgnoreCase)
    foreach ($category in $policy.categories) {
        foreach ($anchor in @($category.anchors.title)) {
            Assert-PlanTest (-not $headingTitles.Contains($anchor)) "Anchor '$anchor' ($($category.id)) shares a heading's title, which would end its own section."
        }
    }
    $document = Get-PlanDocument ((Get-PlanNode -Class 'android.widget.TextView' -Text $section.title) +
        (Get-PlanNode -Class 'android.widget.TextView' -Text $section.anchors[0]))
    Assert-PlanContent $section $document $false 'Native caption cannot impersonate settings'
    $document = Get-NativePlanDocument $homeDestination
    $surface = Get-SettingsPlanDocument $section -Page
    foreach ($child in @($surface.DocumentElement.FirstChild.ChildNodes)) {
        [void]$document.DocumentElement.FirstChild.AppendChild($document.ImportNode($child, $true))
    }
    Assert-PlanContent $homeDestination $document $false 'Settings page covers native content without Search'
    if ($CaptureWorkRoot) {
        $actual = Join-Path $CaptureWorkRoot 'walk-binding/native-capture/a7907be0a5284f6e8bdc47265eb0e455/home.xml'
        Assert-PlanContent $homeDestination (Read-WalkUiDocument $actual) $false 'Actual native Home loading skeleton regression'
        # Older UIAutomator dumps predate the native snapshot's explicit visibility field.
        # Normalize only that absent field in test memory; production visibility stays strict.
        foreach ($capture in @(
            @{ id='home'; file='16-baseline-home-dark.xml'; valid=$true },
            @{ id='inbox'; file='25-final-inbox.xml'; valid=$true },
            @{ id='profile'; file='02-baseline-profile.xml'; valid=$true },
            @{ id='explore'; file='46-input-control-instagram-explore.xml'; valid=$false })) {
            $document = Read-WalkUiDocument (Join-Path $CaptureWorkRoot ('audit-2026-10-25/' + $capture.file))
            foreach ($uiNode in @($document.SelectNodes('//node'))) {
                if (-not $uiNode.HasAttribute('visible-to-user')) { $uiNode.SetAttribute('visible-to-user','true') }
            }
            $destination = $all | Where-Object id -CEQ $capture.id
            Assert-PlanContent $destination $document $capture.valid "Saved $($capture.file) evidence"
        }
        # Native snapshots of the loaded Explore grid, before and after the capture.
        foreach ($file in @('capture-0004.xml','capture-0004-after.xml')) {
            $path = Join-Path $CaptureWorkRoot ('walk-binding/cold-control/b839a0c34a784c70b67636342c7136fd/walk/8a838eb895b046d7b96cae33638eaf0f/' + $file)
            Assert-PlanContent $exploreDestination (Read-WalkUiDocument $path) $true "Saved cold-control Explore $file"
        }
    }
    Write-Output "walk-plan: $script:checks checks passed."
} finally {
    $resolved = [IO.Path]::GetFullPath($tempRoot)
    $parent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolved.StartsWith($parent, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolved) -cmatch '^hushgram-walk-plan-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
