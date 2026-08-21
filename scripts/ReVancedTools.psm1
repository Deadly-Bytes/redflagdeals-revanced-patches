Set-StrictMode -Version Latest

function Invoke-ReVancedPatchChecked {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [string] $CliJar,
        [Parameter(Mandatory)] [string] $PatchBundle,
        [Parameter(Mandatory)] [string] $InputApk,
        [Parameter(Mandatory)] [string] $OutputApk,
        [Parameter(Mandatory)] [string] $KeystorePath,
        [Parameter(Mandatory)] [string] $TemporaryFilesPath,
        [Parameter(Mandatory)] [string] $LogPath
    )

    if (Test-Path -LiteralPath $OutputApk) {
        throw "Refusing to overwrite output: $OutputApk"
    }

    $logText = (& java -jar $CliJar patch `
        -p $PatchBundle -b `
        --exclusive -e 'Fix RedFlagDeals Forums' `
        -o $OutputApk `
        --keystore $KeystorePath `
        -t $TemporaryFilesPath --purge `
        $InputApk 2>&1 | Out-String)
    $exitCode = $LASTEXITCODE
    Set-Content -LiteralPath $LogPath -Value $logText -Encoding utf8NoBOM

    $reportedFailure =
        $exitCode -ne 0 -or
        $logText -match '(?m)^SEVERE:' -or
        $logText -match '"Fix RedFlagDeals Forums" failed' -or
        $logText -notmatch '"Fix RedFlagDeals Forums" succeeded'

    if ($reportedFailure) {
        if (Test-Path -LiteralPath $OutputApk) {
            Remove-Item -LiteralPath $OutputApk -Force
        }
        throw "ReVanced patch failed closed. See $LogPath"
    }
    if (-not (Test-Path -LiteralPath $OutputApk)) {
        throw "ReVanced CLI reported success but did not create $OutputApk"
    }

    return $logText
}

Export-ModuleMember -Function Invoke-ReVancedPatchChecked
