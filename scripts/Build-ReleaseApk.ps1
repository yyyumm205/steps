param(
    [switch]$InitializeSigning,
    [switch]$UpgradeFromDebug,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$androidProject = Join-Path $project 'android'
$signingDirectory = Join-Path $project '.local/signing'
$keystore = Join-Path $signingDirectory 'release.jks'
$passwordFile = Join-Path $signingDirectory 'release-password.txt'
$lineage = Join-Path $signingDirectory 'release.lineage'
$previousKeystore = Join-Path $signingDirectory 'previous-debug.keystore'
$properties = Get-Content (Join-Path $androidProject 'local.properties')
$sdkLine = $properties | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
if (!$sdkLine) { throw 'Configure sdk.dir in android/local.properties.' }
$sdk = [regex]::Replace(($sdkLine -replace '^sdk.dir=', ''), '\\u([0-9a-fA-F]{4})', {
    param($match) [char][Convert]::ToInt32($match.Groups[1].Value, 16)
}).Replace('\:', ':').Replace('\\', '\')
$buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$buildTools) { throw 'Android SDK build-tools are required.' }
$signer = Join-Path $buildTools.FullName 'apksigner.bat'
$align = Join-Path $buildTools.FullName 'zipalign.exe'
$jdkLine = Get-Content (Join-Path $androidProject 'gradle.properties') |
    Where-Object { $_ -match '^org.gradle.java.home=' } | Select-Object -First 1
$jdk = if ($jdkLine) { $jdkLine -replace '^org.gradle.java.home=', '' } else { $env:JAVA_HOME }
if (!$jdk -or !(Test-Path (Join-Path $jdk 'bin/keytool.exe'))) { throw 'Set JAVA_HOME to the project JDK.' }
$previousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $jdk
try {
    if ($InitializeSigning) {
        if ((Test-Path $keystore) -or (Test-Path $passwordFile)) { throw 'Signing identity already exists; reuse it.' }
        New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
        # Generated signing material remains in the ignored local directory; never print its password.
        $random = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($random) } finally { $rng.Dispose() }
        [IO.File]::WriteAllText($passwordFile, [Convert]::ToBase64String($random), [Text.Encoding]::ASCII)
        & (Join-Path $jdk 'bin/keytool.exe') -genkeypair -keystore $keystore -storetype JKS `
            -storepass:file $passwordFile -keypass:file $passwordFile -alias ringfitness-release `
            -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 `
            -dname 'CN=RingFitness Steps, O=RingFitness, C=CN'
        if ($LASTEXITCODE -ne 0) { throw 'Release key generation failed; inspect local signing material before retrying.' }
    }
    if (!(Test-Path $keystore) -or !(Test-Path $passwordFile)) { throw 'Initialize the local release signing identity first.' }
    if ($UpgradeFromDebug -and !(Test-Path $lineage)) {
        $debugKey = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android/debug.keystore'
        if (!(Test-Path $debugKey)) { throw 'The existing debug signing key is required for update continuity.' }
        if (!(Test-Path $previousKeystore)) { Copy-Item -LiteralPath $debugKey -Destination $previousKeystore }
        if ((Get-FileHash $debugKey).Hash -ne (Get-FileHash $previousKeystore).Hash) { throw 'The previous signing key changed; inspect it before rotating.' }
        & $signer rotate --out $lineage --old-signer --ks $previousKeystore --ks-key-alias androiddebugkey `
            --ks-pass pass:android --key-pass pass:android --set-installed-data true --new-signer --ks $keystore `
            --ks-key-alias ringfitness-release --ks-pass "file:$passwordFile"
        if ($LASTEXITCODE -ne 0) { throw 'Signing lineage creation failed.' }
    }
    if (!$SkipBuild) {
        Push-Location $androidProject
        try {
            & .\gradlew.bat assembleRelease lintVitalRelease --no-daemon --console=plain --quiet
            if ($LASTEXITCODE -ne 0) { throw 'Release build or validation failed.' }
        } finally { Pop-Location }
    }
    $metadata = Get-Content (Join-Path $androidProject 'app/build/outputs/apk/release/output-metadata.json') -Raw | ConvertFrom-Json
    $version = $metadata.elements[0].versionName
    $unsigned = Join-Path $androidProject ('app/build/outputs/apk/release/' + $metadata.elements[0].outputFile)
    $destination = Join-Path $androidProject "dist/$version"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $aligned = Join-Path $androidProject 'app/build/outputs/apk/release/app-release-aligned.apk'
    $apk = Join-Path $destination "RingFitness-Steps-$version.apk"
    & $align -f -p 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
    $signArgs = @('sign', '--ks', $keystore, '--ks-key-alias', 'ringfitness-release',
        '--ks-pass', "file:$passwordFile", '--out', $apk)
    if (Test-Path $lineage) {
        if (!(Test-Path $previousKeystore)) { throw 'Restore the previous signing key kept with the lineage.' }
        $signArgs = @('sign', '--ks', $previousKeystore, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android',
            '--key-pass', 'pass:android', '--next-signer') + $signArgs[1..($signArgs.Length - 1)] +
            @('--lineage', $lineage, '--rotation-min-sdk-version', '28')
    }
    & $signer @signArgs $aligned
    if ($LASTEXITCODE -ne 0) { throw 'Release APK signing failed.' }
    & $signer verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK verification failed.' }
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $destination 'SHA256SUMS.txt'), "$hash  $([IO.Path]::GetFileName($apk))`n")
    Write-Output "APK: $apk"
    Write-Output "SHA256: $hash"
} finally { $env:JAVA_HOME = $previousJavaHome }
