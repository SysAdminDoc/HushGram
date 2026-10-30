<#
.SYNOPSIS
    Exercise both wrapper launchers before their JAR can execute.
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
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('hushgram-wrapper-' + [guid]::NewGuid().ToString('N'))
$caseRoot = Join-Path $scratch 'checkout with spaces'
$wrapperDir = Join-Path $caseRoot 'gradle/wrapper'
$scriptDir = Join-Path $caseRoot 'scripts'
$marker = Join-Path $scratch 'jar-executed.txt'
$previousMarker = $env:HUSHGRAM_WRAPPER_TEST_MARKER
$passed = 0

function Invoke-Launcher {
    param([string]$Launcher, [string[]]$Arguments)
    Push-Location $caseRoot
    try {
        $output = @(& $Launcher @Arguments 2>&1 | ForEach-Object { "$_" }) -join "`n"
        [pscustomobject]@{ Exit = $LASTEXITCODE; Output = $output }
    } finally { Pop-Location }
}

function Assert-Refused {
    param([string]$Launcher, [string[]]$Arguments, [string]$Reason)
    $result = Invoke-Launcher $Launcher $Arguments
    if ($result.Exit -eq 0 -or $result.Output -notlike "*$Reason*" -or (Test-Path -LiteralPath $marker)) {
        throw "The wrapper did not refuse before JAR execution ($Reason), exit=$($result.Exit), executed=$(Test-Path -LiteralPath $marker): $($result.Output)"
    }
    $script:passed++
}

try {
    New-Item -ItemType Directory -Path $wrapperDir, $scriptDir -Force | Out-Null
    foreach ($file in 'gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties') {
        Copy-Item -LiteralPath (Join-Path $Root $file) -Destination (Join-Path $caseRoot $file)
    }
    $verifier = Join-Path $Root 'scripts/VerifyGradleWrapper.java'
    if (Test-Path -LiteralPath $verifier) { Copy-Item -LiteralPath $verifier -Destination $scriptDir }
    $jar = Join-Path $wrapperDir 'gradle-wrapper.jar'
    $originalJar = [IO.File]::ReadAllBytes($jar)
    $properties = Join-Path $wrapperDir 'gradle-wrapper.properties'
    $originalProperties = [IO.File]::ReadAllText($properties)
    $javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { Split-Path -Parent (Get-Command java).Source }
    $java = Join-Path $javaBin $(if ($IsWindows) { 'java.exe' } else { 'java' })
    $javac = Join-Path $javaBin $(if ($IsWindows) { 'javac.exe' } else { 'javac' })
    $jarTool = Join-Path $javaBin $(if ($IsWindows) { 'jar.exe' } else { 'jar' })
    $fakeSource = Join-Path $scratch 'WrapperMarker.java'
    [IO.File]::WriteAllText($fakeSource, @'
import java.nio.file.*;
class WrapperMarker {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(System.getenv("HUSHGRAM_WRAPPER_TEST_MARKER")), "executed");
    }
}
'@)
    & $javac -d $scratch $fakeSource
    if ($LASTEXITCODE -ne 0) { throw 'The harmless wrapper stand-in did not compile.' }
    & $jarTool --create --file $jar --main-class WrapperMarker -C $scratch WrapperMarker.class
    if ($LASTEXITCODE -ne 0) { throw 'The harmless wrapper stand-in was not packaged.' }
    $env:HUSHGRAM_WRAPPER_TEST_MARKER = $marker
    $launchers = @()
    if ($IsWindows) { $launchers += , @((Join-Path $caseRoot 'gradlew.bat'), @('--version')) }
    $shell = if ($IsWindows) {
        # Hooks prepend Git's internal executable directory, so git.exe need not be in cmd/.
        $gitDirectory = Split-Path -Parent (Get-Command git).Source
        while ($gitDirectory) {
            $candidate = Join-Path $gitDirectory 'bin/bash.exe'
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { $candidate; break }
            $gitDirectory = Split-Path -Parent $gitDirectory
        }
    } else { '/bin/sh' }
    if (-not $shell -or -not (Test-Path -LiteralPath $shell -PathType Leaf)) {
        throw 'The POSIX wrapper test needs Bash in the resolved Git installation.'
    }
    $launchers += , @($shell, @((Join-Path $caseRoot 'gradlew'), '--version'))

    # A valid stand-in writes a marker if either launcher reaches the untrusted JAR.
    foreach ($launcher in $launchers) { Assert-Refused $launcher[0] $launcher[1] 'wrapper JAR SHA-256' }
    [IO.File]::WriteAllBytes($jar, $originalJar)
    foreach ($launcher in $launchers) {
        $result = Invoke-Launcher $launcher[0] $launcher[1]
        if ($result.Exit -ne 0 -or $result.Output -notlike '*Gradle 9.8.2*') { throw "The authentic wrapper failed: $($result.Output)" }
        $passed++
    }
    # The authentic JAR stays structurally executable when an extra byte is appended.
    [IO.File]::WriteAllBytes($jar, [byte[]]($originalJar + [byte]0))
    foreach ($launcher in $launchers) { Assert-Refused $launcher[0] $launcher[1] 'wrapper JAR SHA-256' }
    [IO.File]::WriteAllBytes($jar, $originalJar)
    [IO.File]::WriteAllText($properties, $originalProperties.Replace('gradle-9.8.2-bin.zip', 'gradle-9.8.0-bin.zip'))
    foreach ($launcher in $launchers) { Assert-Refused $launcher[0] $launcher[1] 'reviewed Gradle 9.8.2' }
    # 9.8.0 with its own authentic checksum is still the vulnerable release (GHSA-xwqc-3h47-hg64).
    [IO.File]::WriteAllText($properties, $originalProperties.Replace('gradle-9.8.2-bin.zip', 'gradle-9.8.0-bin.zip').Replace(
        '21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607', 'bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c'))
    foreach ($launcher in $launchers) { Assert-Refused $launcher[0] $launcher[1] 'reviewed Gradle 9.8.2' }
    [IO.File]::WriteAllText($properties, $originalProperties.Replace('21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607', ('0' * 64)))
    foreach ($launcher in $launchers) { Assert-Refused $launcher[0] $launcher[1] 'distribution ZIP SHA-256' }
    [IO.File]::WriteAllText($properties, $originalProperties)
    Remove-Item -LiteralPath $jar
    foreach ($launcher in $launchers) { Assert-Refused $launcher[0] $launcher[1] 'wrapper JAR is missing' }
    [IO.File]::WriteAllBytes($jar, $originalJar)
    Remove-Item -LiteralPath $properties
    foreach ($launcher in $launchers) { Assert-Refused $launcher[0] $launcher[1] 'wrapper properties are unreadable' }
    . (Join-Path $PSScriptRoot 'script-wiring.ps1')
    if (-not (Test-PushGateRunsSuite (Join-Path $Root 'scripts/pre-push.ps1') 'scripts/test-gradle-wrapper.ps1')) {
        throw 'The push gate does not run the wrapper verification suite.'
    }
    $passed++
    Write-Host "[wrapper] $passed checks passed; altered JARs never executed."
} finally {
    $env:HUSHGRAM_WRAPPER_TEST_MARKER = $previousMarker
    $resolvedScratch = [IO.Path]::GetFullPath($scratch)
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $resolvedScratch.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe wrapper fixture cleanup path.' }
    if (Test-Path -LiteralPath $resolvedScratch) { Remove-Item -LiteralPath $resolvedScratch -Recurse -Force }
}
