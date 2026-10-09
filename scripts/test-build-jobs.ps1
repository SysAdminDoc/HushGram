<#
.SYNOPSIS
    Checks how build-jobs.ps1 finds the machine's build queue and Gradle wrapper, and that the push
    gate and the release scripts run their heavy work through it.
.DESCRIPTION
    Stand-in queue and wrapper scripts record what they were asked, so nothing here waits for a
    real slot or builds anything. When the machine has a real queue (BUILD_QUEUE_SCRIPT), one case
    takes and gives back a slot in a private copy of its queue folder.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
$Root = [IO.Path]::GetFullPath($Root)
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('hushgram-build-jobs-' + [guid]::NewGuid().ToString('N'))
$passed = 0
$saved = @{}
foreach ($name in @('BUILD_QUEUE_SCRIPT', 'HUSHGRAM_BUILD_WRAPPER', 'BUILD_QUEUE_PRIORITY', 'BUILD_QUEUE_TICKET', 'BUILD_QUEUE_DIR')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, [EnvironmentVariableTarget]::Process)
}
$realQueue = [Environment]::GetEnvironmentVariable('BUILD_QUEUE_SCRIPT', [EnvironmentVariableTarget]::Process)
if (-not $realQueue -and ($IsWindows -or $env:OS -eq 'Windows_NT')) {
    $realQueue = [Environment]::GetEnvironmentVariable('BUILD_QUEUE_SCRIPT', [EnvironmentVariableTarget]::User)
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:passed++
}

. (Join-Path $PSScriptRoot 'build-jobs.ps1')
. (Join-Path $PSScriptRoot 'script-wiring.ps1')

try {
    New-Item -ItemType Directory -Path $scratch | Out-Null
    $log = Join-Path $scratch 'calls.log'
    $env:HUSHGRAM_BUILD_JOBS_LOG = $log
    function Read-Calls { if (Test-Path -LiteralPath $log) { @(Get-Content -LiteralPath $log) } else { @() } }
    function Clear-Calls { Remove-Item -LiteralPath $log -ErrorAction SilentlyContinue }

    # A queue that records each ticket. Its parameters are the real script's, so a dot-source that
    # leaks them into the caller would show up as a changed $Label below.
    $queue = Join-Path $scratch 'queue.ps1'
    Set-Content -LiteralPath $queue -Encoding UTF8 -Value @'
param([switch]$Status, [string]$Label, [string]$Priority, [string]$Run)
function Enter-BuildQueue {
    param([string]$Label, [string]$Priority)
    Add-Content -LiteralPath $env:HUSHGRAM_BUILD_JOBS_LOG -Value "enter $Label $Priority"
    $env:BUILD_QUEUE_TICKET = 'stand-in'
    [pscustomobject]@{ slot = 0; path = 'stand-in' }
}
function Exit-BuildQueue {
    param($Ticket)
    Add-Content -LiteralPath $env:HUSHGRAM_BUILD_JOBS_LOG -Value "exit $($Ticket.path)"
    Remove-Item Env:\BUILD_QUEUE_TICKET -ErrorAction SilentlyContinue
}
function Get-BuildQueueMask { param([int]$Slot) [System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity }
'@
    $wrapper = Join-Path $scratch 'wrapper.ps1'
    Set-Content -LiteralPath $wrapper -Encoding UTF8 -Value @'
param([string]$ProjectDir, [string[]]$Tasks)
Add-Content -LiteralPath $env:HUSHGRAM_BUILD_JOBS_LOG -Value "wrapper $ProjectDir|$($Tasks -join ' ')|$env:BUILD_QUEUE_PRIORITY"
exit 3
'@
    $project = Join-Path $scratch 'checkout with spaces'
    New-Item -ItemType Directory -Path $project | Out-Null
    Set-Content -LiteralPath (Join-Path $project 'gradlew.bat') -Encoding ASCII -Value "@echo gradlew %*>>`"%HUSHGRAM_BUILD_JOBS_LOG%`"`r`n@exit /b 4"
    Set-Content -LiteralPath (Join-Path $project 'gradlew') -Encoding ASCII -Value "#!/bin/sh`necho gradlew `"`$@`" >> `"`$HUSHGRAM_BUILD_JOBS_LOG`"`nexit 4"
    if (-not ($IsWindows -or $env:OS -eq 'Windows_NT')) { & chmod +x (Join-Path $project 'gradlew') }

    # The wrapper named outright runs the build and its exit code comes back.
    $env:HUSHGRAM_BUILD_WRAPPER = $wrapper
    $env:BUILD_QUEUE_SCRIPT = 'none'
    $env:BUILD_QUEUE_PRIORITY = 'release'
    Clear-Calls
    $said = @(Invoke-GradleBuild -ProjectDir $project -Tasks @('--console=plain', ':patches:test') 3>&1)
    Assert-True ($LASTEXITCODE -eq 3) "Invoke-GradleBuild left $LASTEXITCODE, not the wrapper's 3."
    Assert-True ((Read-Calls) -contains "wrapper $project|--console=plain :patches:test|release") `
        "The wrapper was not run with the checkout and tasks: $((Read-Calls) -join '; ')"
    Assert-True (@($said | Where-Object { $_ -is [System.Management.Automation.WarningRecord] }).Count -eq 0) `
        'A build through the wrapper warned.'

    # A wrapper named and missing is a broken setup, not a reason to build outside the queue.
    $env:HUSHGRAM_BUILD_WRAPPER = Join-Path $scratch 'missing.ps1'
    $threw = $false
    try { Invoke-GradleBuild -ProjectDir $project -Tasks @(':patches:test') } catch { $threw = $_.Exception.Message -like '*missing.ps1*' }
    Assert-True $threw 'A missing HUSHGRAM_BUILD_WRAPPER did not stop the build.'

    # The governor beside the queue script stands in when no wrapper is named.
    Remove-Item Env:\HUSHGRAM_BUILD_WRAPPER
    $env:BUILD_QUEUE_SCRIPT = $queue
    Copy-Item -LiteralPath $wrapper -Destination (Join-Path $scratch 'build-governor.ps1')
    Assert-True ((Resolve-GradleWrapper) -eq (Join-Path $scratch 'build-governor.ps1')) `
        "The governor beside BUILD_QUEUE_SCRIPT was not taken as the wrapper: $(Resolve-GradleWrapper)"
    Remove-Item -LiteralPath (Join-Path $scratch 'build-governor.ps1')

    # Switched off on purpose: the repository's gradlew, with no warning.
    $env:HUSHGRAM_BUILD_WRAPPER = 'none'
    $env:BUILD_QUEUE_SCRIPT = 'none'
    Clear-Calls
    $said = @(Invoke-GradleBuild -ProjectDir $project -Tasks @(':patches:test') 3>&1)
    Assert-True ($LASTEXITCODE -eq 4) "The gradlew fallback left $LASTEXITCODE, not 4."
    Assert-True (@(Read-Calls | Where-Object { $_ -like 'gradlew*:patches:test*' }).Count -eq 1) `
        "gradlew was not run with the tasks: $((Read-Calls) -join '; ')"
    Assert-True (@($said | Where-Object { $_ -is [System.Management.Automation.WarningRecord] }).Count -eq 0) `
        'A build with the queue switched off warned.'

    # Nothing set at all: gradlew still, and a warning that says the queue is missing. The user's
    # environment is read too, so an empty process value isn't enough to get here on a machine
    # that has a queue, and this case runs only where it doesn't.
    $userQueue = if ($IsWindows -or $env:OS -eq 'Windows_NT') {
        [Environment]::GetEnvironmentVariable('BUILD_QUEUE_SCRIPT', [EnvironmentVariableTarget]::User)
    }
    $userWrapper = if ($IsWindows -or $env:OS -eq 'Windows_NT') {
        [Environment]::GetEnvironmentVariable('HUSHGRAM_BUILD_WRAPPER', [EnvironmentVariableTarget]::User)
    }
    if (-not $userQueue -and -not $userWrapper) {
        Remove-Item Env:\HUSHGRAM_BUILD_WRAPPER, Env:\BUILD_QUEUE_SCRIPT
        $said = @(Invoke-GradleBuild -ProjectDir $project -Tasks @(':patches:test') 3>&1)
        Assert-True ($LASTEXITCODE -eq 4 -and @($said | Where-Object {
            $_ -is [System.Management.Automation.WarningRecord] -and "$_" -like '*No build queue*' }).Count -eq 1) `
            'A build with no queue on the machine did not warn and fall back to gradlew.'
    }

    # A heavy job takes a slot, at release priority when the push asks for it, keeps its caller's
    # variables, hands back what the block wrote and its exit code, and gives the slot back.
    $env:BUILD_QUEUE_SCRIPT = $queue
    $env:HUSHGRAM_BUILD_WRAPPER = 'none'
    Remove-Item Env:\BUILD_QUEUE_TICKET -ErrorAction SilentlyContinue
    Clear-Calls
    $Label = 'caller label'
    $Priority = 'caller priority'
    $output = @(Invoke-HeavyJob -Label 'verify a' -ScriptBlock { "inside $env:BUILD_QUEUE_TICKET"; $global:LASTEXITCODE = 7 })
    $code = $LASTEXITCODE
    Assert-True ($code -eq 7) "Invoke-HeavyJob left $code, not the block's 7."
    Assert-True (($output -join ',') -eq 'inside stand-in') "Invoke-HeavyJob did not hand back the block's output: $output"
    Assert-True (((Read-Calls) -join ',') -eq 'enter hushgram verify a release,exit stand-in') `
        "The job did not take and give back one release slot: $((Read-Calls) -join '; ')"
    Assert-True ($Label -eq 'caller label' -and $Priority -eq 'caller priority') `
        "Taking a slot changed the caller's variables: $Label, $Priority"
    Assert-True (-not $env:BUILD_QUEUE_TICKET) 'The slot was still marked held after the job.'

    # A block that throws still gives the slot back.
    Clear-Calls
    $env:BUILD_QUEUE_PRIORITY = $null
    try { Invoke-HeavyJob -Label 'fails' -ScriptBlock { throw 'stand-in failure' } } catch { }
    Assert-True (((Read-Calls) -join ',') -eq 'enter hushgram fails normal,exit stand-in') `
        "A job that threw did not give its everyday slot back: $((Read-Calls) -join '; ')"

    # Inside a slot already (the gate's), a job doesn't queue behind its own parent.
    Clear-Calls
    $env:BUILD_QUEUE_TICKET = 'parent'
    $job = Enter-HeavyJob -Label 'nested'
    Exit-HeavyJob $job
    Assert-True ($null -eq $job -and (Read-Calls).Count -eq 0) 'A job inside a held slot queued again.'
    Remove-Item Env:\BUILD_QUEUE_TICKET

    # A queue named and missing: the job runs with a warning rather than not at all.
    $env:BUILD_QUEUE_SCRIPT = Join-Path $scratch 'no-queue.ps1'
    $said = @(Invoke-HeavyJob -Label 'unqueued' -ScriptBlock { 'ran' } 3>&1)
    Assert-True ((@($said | Where-Object { $_ -eq 'ran' }).Count -eq 1) -and
        @($said | Where-Object { $_ -is [System.Management.Automation.WarningRecord] }).Count -ge 1) `
        "A missing queue script did not warn and run the job: $($said -join '; ')"

    # The machine's own queue, in a private queue folder so no other build is waited for.
    if ($realQueue -and (Test-Path -LiteralPath $realQueue -PathType Leaf)) {
        $env:BUILD_QUEUE_SCRIPT = $realQueue
        $env:BUILD_QUEUE_DIR = Join-Path $scratch 'queue-dir'
        $affinity = [System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity
        $job = Enter-HeavyJob -Label 'real queue'
        try {
            Assert-True ($null -ne $job -and $env:BUILD_QUEUE_TICKET -and (Test-Path -LiteralPath $env:BUILD_QUEUE_TICKET)) `
                'The machine queue gave no ticket.'
        } finally { Exit-HeavyJob $job }
        Assert-True (-not $env:BUILD_QUEUE_TICKET -and @(Get-ChildItem -LiteralPath $env:BUILD_QUEUE_DIR -File).Count -eq 0 -and
            [System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity -eq $affinity) `
            'The machine queue slot, or this process''s cores, were not given back.'
        Remove-Item Env:\BUILD_QUEUE_DIR
    } else {
        Write-Host '[build-jobs] no machine queue here (BUILD_QUEUE_SCRIPT), so the real-queue case was not run'
    }

    # The scripts themselves, read through the parser: no Gradle started straight, and each patch
    # run inside a slot it gives back in a finally block.
    function Get-Live([string]$Relative) { @(Get-LiveCommands (Get-ScriptAst (Join-Path $Root $Relative))) }
    foreach ($relative in @('scripts/pre-push.ps1', 'scripts/audit-dependencies.ps1', 'scripts/build-release-receipt.ps1',
            'scripts/verify-all-patches.ps1', 'scripts/release/patch-all-builds.ps1', 'scripts/release/preflight.ps1')) {
        $direct = @(Get-Live $relative | Where-Object {
            $_.Extent.Text -match '^&\s*(\$gradle|\$wrapper|\S*gradlew)' -or $_.GetCommandName() -match '(?i)^(\./)?gradlew(\.bat)?$' })
        Assert-True ($direct.Count -eq 0) "$relative starts Gradle straight: $($direct[0].Extent.Text)"
    }
    $gateBuilds = @(Get-Live 'scripts/pre-push.ps1' | Where-Object {
        $_.GetCommandName() -eq 'Invoke-GradleBuild' -and $_.Extent.Text -like '*-ProjectDir $gate*' })
    Assert-True ($gateBuilds.Count -ge 2) "pre-push.ps1 builds the gate through Invoke-GradleBuild $($gateBuilds.Count) time(s)."
    Assert-True (@(Get-Live 'scripts/audit-dependencies.ps1' | Where-Object {
        $_.GetCommandName() -eq 'Invoke-GradleBuild' -and $_.Extent.Text -like '*dependencyGraphReport*' }).Count -eq 1) `
        'audit-dependencies.ps1 does not resolve its graphs through Invoke-GradleBuild.'
    Assert-True (@(Get-Live 'scripts/release/preflight.ps1' | Where-Object {
        $_.GetCommandName() -eq 'Invoke-GradleBuild' -and $_.Extent.Text -like '*-ProjectDir $Root*' }).Count -eq 1) `
        'preflight.ps1 does not run its Gradle step through Invoke-GradleBuild.'
    foreach ($relative in @('scripts/pre-push.ps1', 'scripts/build-release-receipt.ps1', 'scripts/verify-all-patches.ps1',
            'scripts/release/patch-all-builds.ps1')) {
        $ast = Get-ScriptAst (Join-Path $Root $relative)
        $enter = @(Get-Live $relative | Where-Object { $_.GetCommandName() -eq 'Enter-HeavyJob' })
        $exits = @($ast.FindAll({ param($node)
            $node -is [System.Management.Automation.Language.TryStatementAst] -and $null -ne $node.Finally -and
                $node.Finally.Extent.Text -match 'Exit-HeavyJob' -and $node.Body.Extent.Text -match 'Enter-HeavyJob' }, $true))
        Assert-True ($enter.Count -eq 1 -and $exits.Count -ge 1) `
            "$relative does not take a queue slot inside a try whose finally gives it back."
    }
    Assert-True (@((Get-ScriptAst (Join-Path $Root 'scripts/pre-push.ps1')).FindAll({ param($node)
        $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
            $node.Left.Extent.Text -eq '$env:BUILD_QUEUE_PRIORITY' -and $node.Right.Extent.Text -eq "'release'" }, $true)).Count -eq 1) `
        'pre-push.ps1 no longer puts a release push ahead in the queue.'
    Assert-True (Test-PushGateRunsSuite (Join-Path $Root 'scripts/pre-push.ps1') 'scripts/test-build-jobs.ps1') `
        'The push gate does not run this suite when build-jobs.ps1 changes.'

    Write-Host "[build-jobs] $passed checks passed"
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], [EnvironmentVariableTarget]::Process) }
    Remove-Item Env:\HUSHGRAM_BUILD_JOBS_LOG -ErrorAction SilentlyContinue
    $resolved = [IO.Path]::GetFullPath($scratch)
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $resolved.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe build-jobs fixture cleanup path.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
