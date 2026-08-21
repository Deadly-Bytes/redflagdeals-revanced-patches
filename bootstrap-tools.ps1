[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$lock = Get-Content -LiteralPath (Join-Path $projectRoot 'toolchain-lock.json') -Raw | ConvertFrom-Json
$toolsDirectory = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Path $toolsDirectory -Force | Out-Null

function Install-PinnedTool {
    param(
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [string] $Url,
        [Parameter(Mandatory)] [string] $Sha256,
        [Parameter(Mandatory)] [string] $Destination
    )

    if (Test-Path -LiteralPath $Destination) {
        $existingHash = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($existingHash -eq $Sha256) {
            Write-Host "$Name already verified."
            return
        }
        throw "$Name exists with the wrong SHA-256: $existingHash"
    }

    $temporary = "$Destination.download"
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -LiteralPath $temporary -Force
    }
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $temporary
    $downloadHash = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($downloadHash -ne $Sha256) {
        Remove-Item -LiteralPath $temporary -Force
        throw "$Name download SHA-256 mismatch: $downloadHash"
    }
    Move-Item -LiteralPath $temporary -Destination $Destination
    Write-Host "$Name downloaded and verified."
}

Install-PinnedTool `
    -Name 'ReVanced CLI 6.0.0' `
    -Url $lock.revancedCli.url `
    -Sha256 $lock.revancedCli.sha256 `
    -Destination (Join-Path $toolsDirectory 'revanced-cli-6.0.0-all.jar')

Install-PinnedTool `
    -Name 'Apktool 3.0.3' `
    -Url $lock.apktool.url `
    -Sha256 $lock.apktool.sha256 `
    -Destination (Join-Path $toolsDirectory 'apktool_3.0.3.jar')
