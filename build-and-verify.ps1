[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $StockApk,

    [string] $OutputDirectory = (Join-Path $PSScriptRoot 'artifacts\local'),

    [string] $KeystorePath = (Join-Path $PSScriptRoot '.local\rfd-stage2.keystore')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$expectedStockHash = 'e826029890e2c4e5193b75381061a353953a9e4c92e7601498cc01e2c997ad1d'
$expectedStockSize = 6511556
$expectedPackage = 'com.ypg.rfdforums'
$expectedVersion = '1.11.7'
$projectRoot = $PSScriptRoot
$lock = Get-Content -LiteralPath (Join-Path $projectRoot 'toolchain-lock.json') -Raw | ConvertFrom-Json

& (Join-Path $projectRoot 'bootstrap-tools.ps1')

$javaVersion = (& java -version 2>&1 | Out-String)
if ($javaVersion -notmatch 'version "21\.0\.6') {
    throw "Java 21.0.6 is required. Detected:`n$javaVersion"
}

$sdkRoot = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$buildTools = Join-Path $sdkRoot ("build-tools\" + [string] $lock.android.sdkBuildTools)
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$zipalign = Join-Path $buildTools 'zipalign.exe'
foreach ($tool in @($aapt2, $apksigner, $zipalign)) {
    if (-not (Test-Path -LiteralPath $tool)) {
        throw "Missing pinned Android SDK tool: $tool"
    }
}

$stock = (Resolve-Path -LiteralPath $StockApk).Path
$stockInfo = Get-Item -LiteralPath $stock
$stockHashBefore = (Get-FileHash -LiteralPath $stock -Algorithm SHA256).Hash.ToLowerInvariant()
if ($stockHashBefore -ne $expectedStockHash -or $stockInfo.Length -ne $expectedStockSize) {
    throw "Unsupported stock APK. SHA-256=$stockHashBefore size=$($stockInfo.Length)"
}

$badging = (& $aapt2 dump badging $stock 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) {
    throw "aapt2 could not inspect the stock APK:`n$badging"
}
$packageMatch = [regex]::Match($badging, "package: name='([^']+)'[^\r\n]*versionName='([^']+)'")
if (-not $packageMatch.Success -or
    $packageMatch.Groups[1].Value -ne $expectedPackage -or
    $packageMatch.Groups[2].Value -ne $expectedVersion
) {
    throw "Unsupported package/version in stock APK."
}

$oldAndroidHome = $env:ANDROID_HOME
$env:ANDROID_HOME = $sdkRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') :patches:buildAndroid --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle patch-bundle build failed."
    }
} finally {
    $env:ANDROID_HOME = $oldAndroidHome
}

$bundles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'patches\build\libs') -Filter '*.rvp')
if ($bundles.Count -ne 1) {
    throw "Expected exactly one RVP bundle, found $($bundles.Count)."
}
$bundle = $bundles[0].FullName
$bundleHash = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash.ToLowerInvariant()

$workDirectory = Join-Path $projectRoot ("build\work\" + [guid]::NewGuid().ToString('N'))
$cliTemporary = Join-Path $workDirectory 'cli'
$decodedDirectory = Join-Path $workDirectory 'decoded'
$temporaryApk = Join-Path $workDirectory 'candidate.apk'
$cliLog = Join-Path $workDirectory 'revanced-cli.log'
New-Item -ItemType Directory -Path $workDirectory -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $KeystorePath) -Force | Out-Null

Import-Module (Join-Path $projectRoot 'scripts\ReVancedTools.psm1') -Force
Invoke-ReVancedPatchChecked `
    -CliJar (Join-Path $projectRoot '.tools\revanced-cli-6.0.0-all.jar') `
    -PatchBundle $bundle `
    -InputApk $stock `
    -OutputApk $temporaryApk `
    -KeystorePath $KeystorePath `
    -TemporaryFilesPath $cliTemporary `
    -LogPath $cliLog | Out-Null

$stockHashAfter = (Get-FileHash -LiteralPath $stock -Algorithm SHA256).Hash.ToLowerInvariant()
if ($stockHashAfter -ne $expectedStockHash) {
    throw "The stock APK changed during patching: $stockHashAfter"
}

$apktoolLog = (& java -jar (Join-Path $projectRoot '.tools\apktool_3.0.3.jar') `
    d -f $temporaryApk -o $decodedDirectory 2>&1 | Out-String)
Set-Content -LiteralPath (Join-Path $workDirectory 'apktool.log') -Value $apktoolLog -Encoding utf8NoBOM
if ($LASTEXITCODE -ne 0) {
    throw "Pinned Apktool could not decode the patched APK."
}

function Get-SingleDecodedClass([string] $Name) {
    $matches = @(Get-ChildItem -LiteralPath $decodedDirectory -Recurse -File -Filter $Name)
    if ($matches.Count -ne 1) {
        throw "Expected one decoded $Name, found $($matches.Count)."
    }
    return $matches[0].FullName
}

function Assert-Contains([string] $Path, [string] $Needle) {
    if (-not (Select-String -LiteralPath $Path -SimpleMatch $Needle -Quiet)) {
        throw "Missing required bytecode marker '$Needle' in $Path"
    }
}

function Assert-NotContains([string] $Path, [string] $Needle) {
    if (Select-String -LiteralPath $Path -SimpleMatch $Needle -Quiet) {
        throw "Forbidden bytecode marker '$Needle' remains in $Path"
    }
}

$yidAdapter = Get-SingleDecodedClass 'YidAdapter.smali'
$yidListener = Get-SingleDecodedClass 'YidAdapter$1.smali'
$accountManager = Get-SingleDecodedClass 'YidAccountManager.smali'
$topicFragment = Get-SingleDecodedClass 'TopicFragment.smali'
$topicActivity = Get-SingleDecodedClass 'TopicActivity.smali'
$refreshListener = Get-SingleDecodedClass 'TopicFragment$onRefresh$1.smali'
$topicListAdapter = Get-SingleDecodedClass 'TopicListAdapter.smali'
$diagnostics = Get-SingleDecodedClass 'Diagnostics.smali'
$replyBinding = Get-SingleDecodedClass 'PostItemBindingImpl.smali'
$replyVoting = Get-SingleDecodedClass 'ReplyVoting.smali'
$topicListRequests = Get-SingleDecodedClass 'TopicListRequests.smali'

Assert-Contains $topicListRequests '"rfd_unread_nonce"'
Assert-Contains $topicListRequests '"/api/topics"'
Assert-Contains $topicListRequests '"forums.redflagdeals.com"'
$requestMethod = [regex]::Match((Get-Content -LiteralPath $yidAdapter -Raw), '(?s)\.method public request\(Lcom/ypg/rfdapilib/api/ApiRequest;Lcom/ypg/rfdapilib/api/ApiResponseListener;\)V.*?\.end method').Value
$unreadHook = 'invoke-static/range \{p1 \.\. p1\}, Lapp/revanced/extension/redflagdeals/TopicListRequests;->bypassSharedCache\(Ljava/lang/Object;\)V'
if ([regex]::Matches($requestMethod, $unreadHook).Count -ne 1 -or
    $requestMethod -notmatch ('(?s)\.(?:registers 5|locals 2)\s+' + $unreadHook)) {
    throw 'Topic-list cache bypass must run once at request entry using the high-register-safe invoke form.'
}

Assert-Contains $replyBinding 'ReplyVoting;->bind(Ljava/lang/Object;)V'
Assert-Contains $replyVoting '"onVoteDown"'
Assert-Contains $replyVoting '"getVoteViewModel"'
Assert-Contains $replyVoting '"ic_thumb_down_outline"'
Assert-Contains $replyVoting '"vote_active"'
$replyBindingText = Get-Content -LiteralPath $replyBinding -Raw
$replyBindMethod = [regex]::Match($replyBindingText, '(?s)\.method protected executeBindings\(\)V.*?\.end method').Value
$replyHookPattern = 'invoke-static/range \{p0 \.\. p0\}, Lapp/revanced/extension/redflagdeals/ReplyVoting;->bind\(Ljava/lang/Object;\)V\s+return-void'
if ([regex]::Matches($replyBindMethod, $replyHookPattern).Count -ne 1 -or
    [regex]::Matches($replyBindMethod, '(?m)^\s*return-void\s*$').Count -ne 1) {
    throw 'Reply vote hook must run at the single binding return using the high-register-safe invoke form.'
}

Assert-Contains $diagnostics 'RFDLoginFix-20260820-4'
Assert-Contains $yidAdapter '^phpbb3_([a-z0-9-]+)_sid=([a-zA-Z0-9,-]+);?.*'
Assert-Contains $yidAdapter 'logAuthRequest'
Assert-Contains $yidListener 'logCookieParse'
Assert-Contains $accountManager 'logCookieTupleAccepted'
Assert-Contains $topicFragment 'hideQuickReply'
Assert-Contains $topicFragment 'isReplyAllowed'
Assert-Contains $topicFragment '->onRefresh()V'
Assert-NotContains $topicFragment 'LogoutDialog'
Assert-Contains $topicActivity '->getMTopic$rfd_forums_productionRelease()Lcom/ypg/rfdapilib/forums/model/Topic;'
$activityText = Get-Content -LiteralPath $topicActivity -Raw
$backResult = [regex]::Match($activityText, '(?s)\.method public onBackPressed\(\)V.*?\.end method').Value
$syncIndex = $backResult.IndexOf('iput-object v0, p0, Lcom/ypg/rfdforums/sections/topic/TopicActivity;->topic:', [StringComparison]::Ordinal)
$readIndex = $backResult.IndexOf('iget-object v1, p0, Lcom/ypg/rfdforums/sections/topic/TopicActivity;->topic:', [StringComparison]::Ordinal)
if ($syncIndex -lt 0 -or $readIndex -le $syncIndex) {
    throw 'Topic back result does not synchronize the current fragment model before returning it.'
}
Assert-Contains $refreshListener 'logExactRefreshFailed'
Assert-Contains $refreshListener '->setupQuickReplyUI()V'
Assert-NotContains $refreshListener 'HomeRoute;->go'
Assert-NotContains $refreshListener 'FragmentActivity;->finish'
Assert-NotContains $refreshListener 'FragmentActivity;->onBackPressed'

$accountText = Get-Content -LiteralPath $accountManager -Raw
$userCookieIndex = $accountText.IndexOf('const-string v5, "_u="', [StringComparison]::Ordinal)
$sidCookieIndex = $accountText.IndexOf('const-string v5, "_sid="', [StringComparison]::Ordinal)
if ($userCookieIndex -lt 0 -or $sidCookieIndex -le $userCookieIndex) {
    throw "phpBB cookie assembly order is not user then SID."
}

$paginationText = Get-Content -LiteralPath $topicListAdapter -Raw
if ($paginationText -match '(?m)^\s*[is]get-object .*->progressViewHolder:') {
    throw "TopicListAdapter still accesses the cached pagination holder."
}
$freshHolderCalls = [regex]::Matches(
    $paginationText,
    'ProgressViewHolder;->create\(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;\)'
).Count
if ($freshHolderCalls -ne 1) {
    throw "Expected one fresh ProgressViewHolder creation, found $freshHolderCalls."
}

$signatureLog = (& $apksigner verify --verbose --print-certs $temporaryApk 2>&1 | Out-String)
Set-Content -LiteralPath (Join-Path $workDirectory 'apksigner.log') -Value $signatureLog -Encoding utf8NoBOM
if ($LASTEXITCODE -ne 0) {
    throw "APK signature verification failed."
}
$alignmentLog = (& $zipalign -c -P 16 4 $temporaryApk 2>&1 | Out-String)
Set-Content -LiteralPath (Join-Path $workDirectory 'zipalign.log') -Value $alignmentLog -Encoding utf8NoBOM
if ($LASTEXITCODE -ne 0) {
    throw "APK alignment verification failed."
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
$archiveStem = "RedFlagDeals-Forums-v1.11.7-revanced-$timestamp"
$finalApk = Join-Path $OutputDirectory "$archiveStem.apk"
$finalBundle = Join-Path $OutputDirectory "$archiveStem.rvp"
$manifestPath = Join-Path $OutputDirectory "$archiveStem.json"
if ((Test-Path -LiteralPath $finalApk) -or
    (Test-Path -LiteralPath $finalBundle) -or
    (Test-Path -LiteralPath $manifestPath)
) {
    throw "Archive name collision for $archiveStem"
}

Move-Item -LiteralPath $temporaryApk -Destination $finalApk
Copy-Item -LiteralPath $bundle -Destination $finalBundle
$outputHash = (Get-FileHash -LiteralPath $finalApk -Algorithm SHA256).Hash.ToLowerInvariant()
$archivedBundleHash = (Get-FileHash -LiteralPath $finalBundle -Algorithm SHA256).Hash.ToLowerInvariant()
if ($archivedBundleHash -ne $bundleHash) {
    throw "Archived patch bundle hash mismatch."
}

$repoRoot = $projectRoot
$sourceCommit = (git -C $repoRoot rev-parse HEAD).Trim()
$sourceDirty = [bool](git -C $repoRoot status --porcelain)
$manifest = [ordered]@{
    schemaVersion = 1
    createdUtc = [DateTime]::UtcNow.ToString('o')
    sourceCommit = $sourceCommit
    sourceDirty = $sourceDirty
    supportedPackage = $expectedPackage
    supportedVersion = $expectedVersion
    stockApkSha256 = $expectedStockHash
    patchBundleSha256 = $bundleHash
    outputApkSha256 = $outputHash
    outputApk = [IO.Path]::GetFileName($finalApk)
    patchBundle = [IO.Path]::GetFileName($finalBundle)
    buildMarker = 'RFDLoginFix-20260820-4'
    toolchain = $lock
    verification = [ordered]@{
        everyPatchReportedSuccess = $true
        stockHashPreserved = $true
        decodedSuccessfully = $true
        staticPostconditionsPassed = $true
        signatureVerified = $true
        alignmentVerified = $true
        androidRuntimeVerified = $false
        emulatorValidated = $false
        physicalDeviceValidated = $false
    }
}
$manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM

Write-Host "Verified APK: $finalApk"
Write-Host "Patch bundle: $finalBundle"
Write-Host "Manifest: $manifestPath"
Write-Host "Output SHA-256: $outputHash"
