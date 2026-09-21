<#
.SYNOPSIS
    Fills the winget manifest templates from a built Kubo MSI.

.DESCRIPTION
    Two values in a winget manifest cannot be written by hand, because they change with every build:
    the installer's SHA256, and the MSI's ProductCode. Getting either wrong fails validation with an
    error that does not say which one. This reads both out of the MSI itself.

    The SHA256 is taken from the local file. That is only the right answer if the file you point at is
    byte-for-byte the one attached to the GitHub release — so build once, upload that artifact, and run
    this against the same file.

.PARAMETER Msi
    The built installer, e.g. app\build\jpackage\Kubo-0.2.0.msi

.PARAMETER Version
    The release version, e.g. 0.2.0. Defaults to the version read from the MSI.

.EXAMPLE
    .\build-manifests.ps1 -Msi ..\..\app\build\jpackage\Kubo-0.2.0.msi
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Msi,
    [string]$Version,
    [string]$ReleaseDate = (Get-Date -Format 'yyyy-MM-dd')
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not (Test-Path $Msi)) { throw "No such installer: $Msi" }
$Msi = (Resolve-Path $Msi).Path

# --- read the MSI's own property table -------------------------------------------------------------
# The ProductCode is a fresh GUID on every build; the ProductVersion is what Windows will show in
# Apps & features. Both have to match the manifest exactly or winget will not recognise the install.
function Get-MsiProperty {
    param([string]$Path, [string]$Name)
    $installer = New-Object -ComObject WindowsInstaller.Installer
    try {
        $database = $installer.GetType().InvokeMember(
            'OpenDatabase', 'InvokeMethod', $null, $installer, @($Path, 0))
        $view = $database.GetType().InvokeMember(
            'OpenView', 'InvokeMethod', $null, $database,
            @("SELECT Value FROM Property WHERE Property = '$Name'"))
        # Swallow Execute's return value: anything left on the pipeline becomes part of what the
        # function returns, and a stray $null in front of the answer is what a caller then gets.
        [void]$view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null)
        $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
        if ($null -eq $record) { throw "The installer has no $Name property" }
        return $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, 1)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ReleaseComObject($installer) | Out-Null
    }
}

$productCode = (Get-MsiProperty -Path $Msi -Name 'ProductCode').ToUpperInvariant().Trim('{', '}')
$productVersion = Get-MsiProperty -Path $Msi -Name 'ProductVersion'
if (-not $Version) { $Version = $productVersion }
if ($Version -ne $productVersion) {
    throw "Version mismatch: you asked for $Version but the installer says $productVersion"
}

$sha256 = (Get-FileHash -Path $Msi -Algorithm SHA256).Hash.ToUpperInvariant()

# --- write the three manifests ---------------------------------------------------------------------
$out = Join-Path $here "manifests\$Version"
New-Item -ItemType Directory -Force -Path $out | Out-Null

Get-ChildItem (Join-Path $here 'template') -Filter '*.yaml' | ForEach-Object {
    $text = Get-Content $_.FullName -Raw -Encoding UTF8
    # Drop the "this is a template" notice; the generated file is the real thing.
    $text = $text -replace '(?m)^# Template\..*\r?\n(#.*\r?\n)*\r?\n', ''
    $text = $text.
        Replace('{{VERSION}}', $Version).
        Replace('{{SHA256}}', $sha256).
        Replace('{{PRODUCT_CODE}}', "{$productCode}").
        Replace('{{RELEASE_DATE}}', $ReleaseDate)
    if ($text -match '{{') { throw "A placeholder was left unfilled in $($_.Name)" }
    # winget-pkgs wants UTF-8 without a byte order mark.
    [IO.File]::WriteAllText((Join-Path $out $_.Name), $text, (New-Object Text.UTF8Encoding $false))
}

Write-Host ""
Write-Host "  version       $Version"
Write-Host "  product code  {$productCode}"
Write-Host "  sha256        $sha256"
Write-Host "  written to    $out"
Write-Host ""
Write-Host "  Next:  winget validate --manifest `"$out`""
Write-Host "         then copy the folder into winget-pkgs\manifests\g\Gemmie\Kubo\$Version"
Write-Host ""
