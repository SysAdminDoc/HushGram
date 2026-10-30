<#
.SYNOPSIS
    Exclusive device ownership and data-preserving ADB operations.

.NOTES
    Taken from Hushfacebook's scripts/device-install.ps1
    (https://github.com/SysAdminDoc/Hushfacebook, commit c15d4f7930505824789684d35039d3c78c8b0903).
    GPL-3.0-only. Modified for HushGram (Instagram), 2026.
#>

function Remove-AndroidPackageIfInstalled {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName
    )

    throw 'Replacement uninstalls are disabled. Preserve the installed app and use its existing signing key for an update.'
}

function Enter-HushgramDeviceLease {
    [CmdletBinding()]
    param([string]$Serial, [string]$ExpectedModel, [string]$ExpectedAvd,
        [string]$ChatIdentity = $env:HUSHGRAM_CHAT_ID,
        [string]$Directory = $(if ($env:HUSHGRAM_DEVICE_LEASE_DIR) { $env:HUSHGRAM_DEVICE_LEASE_DIR } else { throw 'Set HUSHGRAM_DEVICE_LEASE_DIR to the shared device lease folder.' }))
    if ($Serial -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$') { throw 'An exact ADB serial is required.' }
    if ([string]::IsNullOrWhiteSpace($ExpectedModel)) { throw 'Pass -ExpectedModel with the device model you intend to test.' }
    if ($Serial.StartsWith('emulator-') -and [string]::IsNullOrWhiteSpace($ExpectedAvd)) { throw 'An emulator also needs -ExpectedAvd.' }
    if ([string]::IsNullOrWhiteSpace($ChatIdentity)) { $ChatIdentity = "HushGram/device-tools/$PID" }
    [void][IO.Directory]::CreateDirectory($Directory)
    $path = Join-Path $Directory "$Serial.json"
    # Never take over an expired marker here. Another test must be confirmed stopped first.
    try { $file = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::Read) }
    catch { throw "Device $Serial is leased or its lease cannot be created. Continue non-device work and retry later." }
    $lease = [pscustomobject]@{ File = $file; Path = [IO.Path]::GetFullPath($path); Verified = $false; InstructionSet = $null
        Record = [ordered]@{schemaVersion=1;serial=$Serial;project='HushGram';chatIdentity=$ChatIdentity
            ownershipToken=[guid]::NewGuid().ToString();processId=$PID;expectedModel=$ExpectedModel;expectedAvd=$ExpectedAvd
            acquiredUtc=[DateTime]::UtcNow.ToString('o');expiresUtc=''} }
    try { Update-HushgramDeviceLease $lease; return $lease }
    catch { $file.Dispose(); Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue; throw }
}

function Update-HushgramDeviceLease {
    param($DeviceLease)
    if ($null -eq $DeviceLease -or $DeviceLease.File -isnot [IO.FileStream] -or -not $DeviceLease.File.CanWrite -or
        [IO.Path]::GetFullPath($DeviceLease.File.Name) -cne $DeviceLease.Path) { throw 'No active owned device lease.' }
    # A Z time, not +00:00: ConvertFrom-Json reads an offset time as local, so another session's
    # lease reader saw this lease as hours expired and could take the device over.
    $DeviceLease.Record.expiresUtc = [DateTime]::UtcNow.AddMinutes(15).ToString('o')
    $bytes = [Text.Encoding]::UTF8.GetBytes(($DeviceLease.Record | ConvertTo-Json -Compress))
    $DeviceLease.File.Position = 0
    $DeviceLease.File.SetLength(0)
    $DeviceLease.File.Write($bytes, 0, $bytes.Length)
    $DeviceLease.File.Flush($true)
}

function Assert-HushgramDeviceLease {
    param($DeviceLease, [string]$Serial, [switch]$IdentityQuery)
    if ($null -eq $DeviceLease -or $DeviceLease.Record.serial -cne $Serial) { throw 'No active owned lease for the selected device.' }
    Update-HushgramDeviceLease $DeviceLease
    if (-not $IdentityQuery -and -not $DeviceLease.Verified) { throw 'Verify the leased device identity before testing it.' }
}

function Exit-HushgramDeviceLease {
    param($DeviceLease)
    if ($null -eq $DeviceLease) { return }
    # The held handle excludes replacement/deletion until the complete test has stopped.
    $path = $DeviceLease.Path
    $token = $DeviceLease.Record.ownershipToken
    try {
        if ($DeviceLease.File -isnot [IO.FileStream] -or -not $DeviceLease.File.CanWrite -or
            [IO.Path]::GetFullPath($DeviceLease.File.Name) -cne $path) { throw 'No active owned device lease.' }
        $current = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        if ($current.ownershipToken -cne $token) { throw 'Device lease ownership changed; leave its marker in place.' }
        # Keep the marker active across closing the handle and deleting it. A cooperating
        # chat cannot reclaim it in that interval, even when this test ran a long cleanup.
        Update-HushgramDeviceLease $DeviceLease
    } finally { $DeviceLease.File.Dispose() }
    Remove-Item -LiteralPath $path -ErrorAction Stop
}

function Confirm-HushgramDevice {
    param([string]$Adb, $DeviceLease, [scriptblock]$AdbInvoker)
    Assert-HushgramDeviceLease -DeviceLease $DeviceLease -Serial $DeviceLease.Record.serial -IdentityQuery
    $DeviceLease.Verified = $false
    $DeviceLease.InstructionSet = $null
    $serial = $DeviceLease.Record.serial
    $queries = @(@('get-serialno'), @('shell', 'getprop', 'ro.product.model'), @('shell', 'getprop', 'ro.product.cpu.abi'))
    $answers = @()
    foreach ($query in $queries) {
        $result = Invoke-HushgramAdbCommand -Adb $Adb -Arguments (@('-s', $serial) + $query) -Invoker $AdbInvoker -DeviceLease $DeviceLease
        if ($result.ExitCode -ne 0) { throw 'Could not verify the selected device identity.' }
        $answers += (@($result.Stdout) -join "`n").Trim()
    }
    if ($answers[0] -cne $serial -or $answers[1] -cne $DeviceLease.Record.expectedModel) { throw 'Selected device identity does not match the requested device.' }
    $instructionSets = @{'arm64-v8a'='arm64';'armeabi-v7a'='arm';'x86_64'='x86_64';'x86'='x86'}
    if (-not $instructionSets.ContainsKey($answers[2])) { throw 'Unsupported device instruction set.' }
    if ($serial.StartsWith('emulator-')) {
        $result = Invoke-HushgramAdbCommand -Adb $Adb -Arguments @('-s', $serial, 'emu', 'avd', 'name') -Invoker $AdbInvoker -DeviceLease $DeviceLease
        if ($result.ExitCode -ne 0 -or @($result.Stdout).Count -ne 2 -or $result.Stdout[1] -cne 'OK' -or
            $result.Stdout[0] -cne $DeviceLease.Record.expectedAvd) { throw 'Selected emulator profile does not match the requested profile.' }
    }
    $DeviceLease.InstructionSet = $instructionSets[$answers[2]]
    $DeviceLease.Verified = $true
}
