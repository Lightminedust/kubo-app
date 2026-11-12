param(
    [string]$PackageKind, [long]$PreviousProcessId,
    [string]$TargetPath, [string]$StagedPath, [string]$BackupPath,
    [string]$LauncherPath, [string]$PackagePath, [string]$ReadyPath, [string]$ResultPath
)
$ErrorActionPreference = 'Stop'
$oldMoved = $false
$newMoved = $false
try {
    if ($PackageKind -notin @('exe', 'msi', 'portable-zip')) { throw 'Unsupported package type' }
    if ($PackageKind -eq 'portable-zip') {
        $target = [IO.Path]::GetFullPath($TargetPath)
        $staged = [IO.Path]::GetFullPath($StagedPath)
        $backup = [IO.Path]::GetFullPath($BackupPath)
        $parent = [IO.Path]::GetDirectoryName($target)
        $stagingRoot = [IO.Path]::GetDirectoryName($staged)
        if (-not $parent -or $target -eq [IO.Path]::GetPathRoot($target) -or
            [IO.Path]::GetDirectoryName($backup) -ne $parent -or
            [IO.Path]::GetDirectoryName($stagingRoot) -ne $parent -or
            -not [IO.Path]::GetFileName($stagingRoot).StartsWith('.kubo-update-') -or
            (Test-Path -LiteralPath $backup) -or
            -not (Test-Path -LiteralPath (Join-Path $target 'app\Kubo.cfg')) -or
            -not (Test-Path -LiteralPath (Join-Path $staged 'Kubo.exe'))) { throw 'Invalid update directories' }
    }
    Set-Content -LiteralPath $ReadyPath -Value 'ready' -Encoding UTF8
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        if (-not (Get-Process -Id $PreviousProcessId -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Seconds 1
    }
    if (Get-Process -Id $PreviousProcessId -ErrorAction SilentlyContinue) { throw 'Kubo did not close; installation cancelled' }
    if ($PackageKind -eq 'portable-zip') {
        Move-Item -LiteralPath $target -Destination $backup
        $oldMoved = $true
        Move-Item -LiteralPath $staged -Destination $target
        $newMoved = $true
    } else {
        if ($PackageKind -eq 'msi') {
            # -File parameters are values, never evaluated as PowerShell code. Start-Process requires
            # explicit quoting around a file argument containing spaces.
            $installer = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ('"' + $PackagePath + '"')) -Wait -PassThru
        } else {
            $installer = Start-Process -FilePath $PackagePath -Wait -PassThru
        }
        if ($installer.ExitCode -notin @(0, 3010, 1641)) { throw "Installer exited with code $($installer.ExitCode)" }
    }
    Set-Content -LiteralPath $ResultPath -Value 'ok' -Encoding UTF8
    if (Test-Path -LiteralPath $LauncherPath) {
        Start-Process -FilePath $LauncherPath -WorkingDirectory ([IO.Path]::GetDirectoryName($LauncherPath))
    }
} catch {
    $reason = $_.Exception.Message
    Write-Output $reason
    # Never delete the old application. Undo a failed portable replacement before relaunching it.
    if ($oldMoved) {
        if ($newMoved -and (Test-Path -LiteralPath $target)) { Move-Item -LiteralPath $target -Destination $staged }
        if (-not (Test-Path -LiteralPath $target)) { Move-Item -LiteralPath $backup -Destination $target }
    }
    Set-Content -LiteralPath $ResultPath -Value ('failed: ' + $reason) -Encoding UTF8
    if (-not (Get-Process -Id $PreviousProcessId -ErrorAction SilentlyContinue) -and (Test-Path -LiteralPath $LauncherPath)) {
        Start-Process -FilePath $LauncherPath -WorkingDirectory ([IO.Path]::GetDirectoryName($LauncherPath))
    }
    exit 1
}
