<#
.SYNOPSIS
    Runs this repository's heavy jobs through the machine's build queue when it has one.

.DESCRIPTION
    Dot-source this beside common.ps1:

        . (Join-Path $PSScriptRoot 'build-jobs.ps1')

    A Gradle build, a desktop CLI patch run or the merge of a split bundle takes several cores and
    gigabytes for minutes. On a machine that shares its CPU between several builds they wait their
    turn in a queue, which these helpers find through two variables:

    - BUILD_QUEUE_SCRIPT names a PowerShell script that defines
      Invoke-InBuildQueue -Label <text> -Priority <release|normal> -ScriptBlock <block> and returns
      the block's exit code. Heavy jobs other than Gradle run inside it.
    - HUSHGRAM_BUILD_WRAPPER names a script called as <wrapper> -ProjectDir <checkout> -Tasks
      <task>... that runs Gradle there and passes its exit code through. Unset, the
      build-governor.ps1 beside BUILD_QUEUE_SCRIPT takes its place.

    Both are read from the process first and then from the user's environment, since a hook can
    start with an environment older than the user's. Either one set to none means no queue on
    purpose, which the self-tests use. With neither, the job runs straight away with a warning: the
    repository works on any machine, and only the waiting is lost.

    BUILD_QUEUE_PRIORITY=release puts a job ahead of everyday ones. The pre-push hook sets it for a
    release push.

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>

function Get-BuildJobSetting {
    <# A variable from this process, or from the user's environment when the process has none. #>
    param([Parameter(Mandatory = $true)][string]$Name)
    $value = [Environment]::GetEnvironmentVariable($Name, [EnvironmentVariableTarget]::Process)
    if (-not $value -and ($IsWindows -or $env:OS -eq 'Windows_NT')) {
        $value = [Environment]::GetEnvironmentVariable($Name, [EnvironmentVariableTarget]::User)
    }
    return $value
}

function Resolve-BuildQueueScript {
    <# The machine's queue script, or $null when there is none or it's switched off. #>
    $path = Get-BuildJobSetting 'BUILD_QUEUE_SCRIPT'
    if (-not $path -or $path -eq 'none') { return $null }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Write-Warning "BUILD_QUEUE_SCRIPT names $path, which isn't there, so heavy jobs run without the queue."
        return $null
    }
    return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($path)
}

function Resolve-GradleWrapper {
    <#
    .SYNOPSIS
        The script that runs Gradle on this machine, or $null to run the repository's gradlew.
    .DESCRIPTION
        A wrapper named and missing is a broken setup and throws, rather than building outside
        the queue the machine was set up with.
    #>
    $wrapper = Get-BuildJobSetting 'HUSHGRAM_BUILD_WRAPPER'
    if ($wrapper -eq 'none') { return $null }
    if ($wrapper) {
        if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
            throw "HUSHGRAM_BUILD_WRAPPER names $wrapper, which is not there."
        }
        return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($wrapper)
    }
    $queue = Resolve-BuildQueueScript
    if ($queue) {
        $governor = Join-Path (Split-Path -Parent $queue) 'build-governor.ps1'
        if (Test-Path -LiteralPath $governor -PathType Leaf) { return $governor }
    }
    return $null
}

function Test-BuildQueueSwitchedOff {
    <# Whether a variable says none, so running without the queue is a choice and not a gap. #>
    return (Get-BuildJobSetting 'BUILD_QUEUE_SCRIPT') -eq 'none' -or (Get-BuildJobSetting 'HUSHGRAM_BUILD_WRAPPER') -eq 'none'
}

function Invoke-GradleBuild {
    <#
    .SYNOPSIS
        Gradle in -ProjectDir with -Tasks, through the machine's wrapper when it has one.
    .DESCRIPTION
        The build's output goes where a plain gradlew call's would, and its exit code is left in
        $LASTEXITCODE for the caller to read.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$ProjectDir,
        [Parameter(Mandatory = $true)][string[]]$Tasks
    )
    $wrapper = Resolve-GradleWrapper
    $global:LASTEXITCODE = 0
    if ($wrapper) {
        & $wrapper -ProjectDir $ProjectDir -Tasks $Tasks
        return
    }
    if (-not (Test-BuildQueueSwitchedOff)) {
        Write-Warning ('No build queue on this machine (BUILD_QUEUE_SCRIPT or HUSHGRAM_BUILD_WRAPPER), so Gradle ' +
            'runs straight away.')
    }
    $launcher = Join-Path $ProjectDir $(if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'gradlew.bat' } else { 'gradlew' })
    & $launcher -p $ProjectDir @Tasks
}

function Enter-HeavyJob {
    <#
    .SYNOPSIS
        Waits for a queue slot and holds it until Exit-HeavyJob, or returns $null straight away
        when the machine has no queue or this process already holds a slot.
    .DESCRIPTION
        While the slot is held, this process runs below normal priority on the slot's cores, and
        everything it starts inherits both. A Gradle build or patch run started inside, through
        the governor or another script that queues, sees BUILD_QUEUE_TICKET and doesn't queue
        behind its own parent. Always pair it with Exit-HeavyJob in a finally block.
    #>
    param([Parameter(Mandatory = $true)][string]$Label)
    $queue = Resolve-BuildQueueScript
    if (-not $queue) {
        if (-not (Test-BuildQueueSwitchedOff)) {
            Write-Warning "No build queue on this machine (BUILD_QUEUE_SCRIPT), so hushgram $Label runs straight away."
        }
        return $null
    }
    if ($env:BUILD_QUEUE_TICKET) { return $null }
    $priority = if ($env:BUILD_QUEUE_PRIORITY -eq 'release') { 'release' } else { 'normal' }
    # Dot-sourced in a block of its own: the queue script's parameters (Label, Priority, Status,
    # Run) would otherwise overwrite variables of the same names here.
    $held = & {
        param($QueueScript, $JobLabel, $JobPriority)
        . $QueueScript
        $ticket = Enter-BuildQueue -Label $JobLabel -Priority $JobPriority
        if ($ticket) { [pscustomobject]@{ Ticket = $ticket; Mask = Get-BuildQueueMask -Slot $ticket.slot } }
    } $queue "hushgram $Label" $priority
    if (-not $held) { return $null }
    $process = [System.Diagnostics.Process]::GetCurrentProcess()
    $job = [pscustomobject]@{
        Queue = $queue; Ticket = $held.Ticket
        Affinity = $process.ProcessorAffinity; PriorityClass = $process.PriorityClass
    }
    $process.ProcessorAffinity = $held.Mask
    $process.PriorityClass = 'BelowNormal'
    return $job
}

function Exit-HeavyJob {
    <# Gives back the slot Enter-HeavyJob took, and this process's cores and priority. $null does nothing. #>
    param($Job)
    if (-not $Job) { return }
    $process = [System.Diagnostics.Process]::GetCurrentProcess()
    try {
        $process.ProcessorAffinity = $Job.Affinity
        $process.PriorityClass = $Job.PriorityClass
    } finally {
        & { param($QueueScript, $Ticket) . $QueueScript; Exit-BuildQueue -Ticket $Ticket } $Job.Queue $Job.Ticket
    }
}

function Invoke-HeavyJob {
    <#
    .SYNOPSIS
        Runs -ScriptBlock in a queue slot, or straight away when the machine has no queue.
    .DESCRIPTION
        The block's output comes back to the caller, and its exit code stays in $LASTEXITCODE.
        It runs in a scope below this function's, so it reads the caller's variables and what it
        assigns stays inside it.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][scriptblock]$ScriptBlock
    )
    $job = Enter-HeavyJob -Label $Label
    try {
        $global:LASTEXITCODE = 0
        & $ScriptBlock
    } finally {
        Exit-HeavyJob $job
    }
}
