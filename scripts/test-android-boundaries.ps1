<#
.SYNOPSIS
    Exercise the real Android boundary gate against incomplete or wrong-platform provider results.
.NOTES
    Run after the unfiltered unit tests. The original generated report is restored byte for byte.
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
$Root = [IO.Path]::GetFullPath($Root)
$suite = 'app.hushgram.extension.instagram.misc.SameKeyProviderCallerTest'
$report = Join-Path $Root "extensions/instagram/build/test-results/testDebugUnitTest/TEST-$suite.xml"
if (-not (Test-Path -LiteralPath $report -PathType Leaf)) { throw 'Run the complete unfiltered Android unit tests before the boundary self-tests.' }
$original = [IO.File]::ReadAllBytes($report)
$wrapper = Join-Path $Root $(if ([Environment]::OSVersion.Platform -eq 'Win32NT') { 'gradlew.bat' } else { 'gradlew' })
$passed = 0
. (Join-Path $PSScriptRoot 'script-wiring.ps1')

function Test-BoundarySuiteWiring {
    param([string]$Path)
    $live = @(Get-LiveCommands (Get-ScriptAst $Path))
    $build = @($live | Where-Object {
        $_.GetCommandName() -eq 'Invoke-GradleBuild' -and $_.Extent.Text -like '*-ProjectDir $gate *:extensions:instagram:verifyAndroidBoundaries*'
    })
    $runs = @($live | Where-Object {
        $_.GetCommandName() -eq 'pwsh' -and $null -ne (Get-CommandArgument $_ 'File') -and
            (Test-NamesFile (Get-CommandArgument $_ 'File') 'test-android-boundaries.ps1')
    })
    $build.Count -eq 1 -and $runs.Count -eq 1 -and
        (Get-CommandArgument $runs[0] 'Root').Extent.Text -eq '$gate' -and
        $runs[0].Extent.StartOffset -gt $build[0].Extent.EndOffset
}

function Invoke-BoundaryGate {
    $previous = $ErrorActionPreference
    try {
        # Windows PowerShell reports expected native stderr as NativeCommandError.
        # Keep the task's exit code and refusal message as the result on both shells.
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = -1
        $output = @(& $wrapper -p $Root --offline --console=plain :extensions:instagram:verifyAndroidBoundaries `
            -x :extensions:instagram:testDebugUnitTest 2>&1 | ForEach-Object { "$_" }) -join "`n"
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    [pscustomobject]@{ Exit = $exitCode; Output = $output }
}

try {
    $result = Invoke-BoundaryGate
    if ($result.Exit -ne 0) { throw "The complete boundary results did not pass: $($result.Output)" }
    $passed++
    $caseFailure = "Android boundary case did not pass exactly once: $suite."
    $mutations = @(
        @{ Name = 'omitted suite'; Omit = $true; Reason = "Missing Android boundary results: $suite" },
        @{ Name = 'filtered API 28'; Reason = $caseFailure; Change = { param($document)
            foreach ($node in @($document.GetElementsByTagName('testcase'))) {
                if ($node.GetAttribute('name').EndsWith('[28]')) { $node.ParentNode.RemoveChild($node) | Out-Null }
            }
        } },
        @{ Name = 'filtered API 37'; Reason = $caseFailure; Change = { param($document)
            foreach ($node in @($document.GetElementsByTagName('testcase'))) {
                if ($node.GetAttribute('name').EndsWith('[37]')) { $node.ParentNode.RemoveChild($node) | Out-Null }
            }
        } },
        @{ Name = 'API 36 substituted for API 37'; Reason = $caseFailure; Change = { param($document)
            foreach ($node in @($document.GetElementsByTagName('testcase'))) {
                $name = $node.GetAttribute('name')
                if ($name.EndsWith('[37]')) { $node.SetAttribute('name', $name.Replace('[37]', '[36]')) }
            }
        } },
        @{ Name = 'highest API marker removed'; Reason = $caseFailure; Change = { param($document)
            foreach ($node in @($document.GetElementsByTagName('testcase'))) {
                $name = $node.GetAttribute('name')
                if ($name.EndsWith('[37]')) { $node.SetAttribute('name', $name.Substring(0, $name.Length - 4)) }
            }
        } },
        @{ Name = 'one missing case'; Reason = $caseFailure; Change = { param($document)
            $node = $document.GetElementsByTagName('testcase').Item(0)
            $node.ParentNode.RemoveChild($node) | Out-Null
        } },
        @{ Name = 'suite marked skipped'; Reason = "Android boundary suite $suite has skipped=1"; Change = { param($document)
            $document.DocumentElement.SetAttribute('skipped', '1')
        } },
        @{ Name = 'skipped case despite zero suite total'; Reason = $caseFailure; Change = { param($document)
            $document.GetElementsByTagName('testcase').Item(0).AppendChild($document.CreateElement('skipped')) | Out-Null
        } },
        @{ Name = 'failed case despite zero suite total'; Reason = $caseFailure; Change = { param($document)
            $document.GetElementsByTagName('testcase').Item(0).AppendChild($document.CreateElement('failure')) | Out-Null
        } },
        @{ Name = 'duplicate case'; Reason = $caseFailure; Change = { param($document)
            $node = $document.GetElementsByTagName('testcase').Item(0)
            $node.ParentNode.AppendChild($node.CloneNode($true)) | Out-Null
        } }
    )
    foreach ($mutation in $mutations) {
        [IO.File]::WriteAllBytes($report, $original)
        if ($mutation.Omit) { Remove-Item -LiteralPath $report }
        else {
            $document = New-Object System.Xml.XmlDocument
            $document.XmlResolver = $null
            $document.Load($report)
            & $mutation.Change $document
            $document.Save($report)
        }
        $result = Invoke-BoundaryGate
        if ($result.Exit -eq 0 -or $result.Output -notlike "*$($mutation.Reason)*") {
            throw "The boundary gate did not reject $($mutation.Name): $($result.Output)"
        }
        $passed++
    }
} finally { [IO.File]::WriteAllBytes($report, $original) }
$result = Invoke-BoundaryGate
if ($result.Exit -ne 0) { throw "The restored boundary results did not pass: $($result.Output)" }
$passed++
if (-not (Test-BoundarySuiteWiring (Join-Path $Root 'scripts/pre-push.ps1'))) {
    throw 'The push gate must run the boundary self-tests in its own worktree after the complete tests.'
}
$passed++
Write-Host "[android-boundaries] $passed checks passed; original provider results restored."
