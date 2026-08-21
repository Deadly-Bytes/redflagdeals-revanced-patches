[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $PatchedApk,

    [string] $KeystorePath = (Join-Path $PSScriptRoot '..\.local\rfd-stage2.keystore')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$inputApk = (Resolve-Path -LiteralPath $PatchedApk).Path
$bundles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'patches\build\libs') -Filter '*.rvp')
if ($bundles.Count -ne 1) {
    throw "Expected exactly one built RVP bundle, found $($bundles.Count)."
}

$workDirectory = Join-Path $projectRoot ("build\fail-closed-test\" + [guid]::NewGuid().ToString('N'))
$outputApk = Join-Path $workDirectory 'must-not-survive.apk'
$logPath = Join-Path $workDirectory 'revanced-cli.log'
$cliTemporary = Join-Path $workDirectory 'cli'
New-Item -ItemType Directory -Path $workDirectory -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $KeystorePath) -Force | Out-Null

Import-Module (Join-Path $projectRoot 'scripts\ReVancedTools.psm1') -Force
$rejected = $false
try {
    Invoke-ReVancedPatchChecked `
        -CliJar (Join-Path $projectRoot '.tools\revanced-cli-6.0.0-all.jar') `
        -PatchBundle $bundles[0].FullName `
        -InputApk $inputApk `
        -OutputApk $outputApk `
        -KeystorePath $KeystorePath `
        -TemporaryFilesPath $cliTemporary `
        -LogPath $logPath | Out-Null
} catch {
    if ($_.Exception.Message -like 'ReVanced patch failed closed.*') {
        $rejected = $true
    } else {
        throw
    }
}

if (-not $rejected) {
    throw 'Expected the already-patched bytecode fixture to be rejected.'
}
if (Test-Path -LiteralPath $outputApk) {
    throw "Fail-closed wrapper left a partial APK behind: $outputApk"
}
if (-not (Test-Path -LiteralPath $logPath)) {
    throw 'Expected a diagnostic CLI log for the rejected fixture.'
}
$logText = Get-Content -LiteralPath $logPath -Raw
if ($logText -notmatch '(?m)^SEVERE:' -and $logText -notmatch '"Fix RedFlagDeals Forums" failed') {
    throw 'The fixture was rejected without the expected ReVanced failure diagnostic.'
}

Write-Host 'PASS: altered bytecode was rejected and no partial output APK survived.'
Write-Host "Diagnostic log: $logPath"
