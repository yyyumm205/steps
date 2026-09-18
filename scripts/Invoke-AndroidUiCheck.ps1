param(
    [string]$Serial,
    [ValidateSet('Inspect', 'Capture', 'Tap', 'SelfTest')][string]$Action = 'Inspect',
    [string]$Text,
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)*$')]
    [string]$ExpectedPackage = 'com.nexthci.ringfitness.steps',
    [ValidatePattern('^[A-Za-z0-9_-]+$')][string]$Step = 'screen',
    [string]$OutputDirectory = '.local/qa',
    [string]$Adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
)

# Evidence stays local. Taps always use a newly inspected UI node, never screenshot estimates.
$ErrorActionPreference = 'Stop'

function ConvertFrom-UiBounds {
    param([string]$Value)
    $match = [regex]::Match($Value, '\A\[([+-]?[0-9]+),([+-]?[0-9]+)\]\[([+-]?[0-9]+),([+-]?[0-9]+)\]\z')
    if (-not $match.Success) { throw 'UI bounds must contain exactly two coordinate pairs.' }
    $coordinates = foreach ($index in 1..4) {
        $coordinate = 0
        if (-not [int]::TryParse($match.Groups[$index].Value, [ref]$coordinate) -or $coordinate -lt 0) {
            throw 'UI bounds contain a negative or unsupported coordinate.'
        }
        $coordinate
    }
    if ($coordinates[2] -le $coordinates[0] -or $coordinates[3] -le $coordinates[1]) {
        throw 'UI bounds must have positive width and height.'
    }
    [pscustomobject]@{ Left = $coordinates[0]; Top = $coordinates[1]; Right = $coordinates[2]; Bottom = $coordinates[3] }
}

function Resolve-AndroidUiTap {
    param([xml]$Tree, [string]$ExactText, [string]$Package)
    if ([string]::IsNullOrEmpty($ExactText)) { throw 'Tap requires the exact visible Text.' }
    $candidates = @($Tree.SelectNodes('//node') | Where-Object {
        $_.GetAttribute('text') -ceq $ExactText -and
        $_.GetAttribute('package') -ceq $Package -and
        $_.GetAttribute('clickable') -ceq 'true' -and
        $_.GetAttribute('enabled') -ceq 'true' -and
        $_.GetAttribute('visible-to-user') -cne 'false'
    })
    if ($candidates.Count -ne 1) {
        throw "Expected one enabled clickable node in '$Package', found $($candidates.Count). Inspect or scroll before retrying."
    }
    $target = $candidates[0]
    $bounds = ConvertFrom-UiBounds $target.GetAttribute('bounds')
    $ancestor = $target.ParentNode
    $hasWindow = $false
    while ($ancestor -is [System.Xml.XmlElement] -and $ancestor.Name -eq 'node') {
        $window = ConvertFrom-UiBounds $ancestor.GetAttribute('bounds')
        if ($ancestor.GetAttribute('visible-to-user') -ceq 'false' -or
            $bounds.Left -lt $window.Left -or $bounds.Top -lt $window.Top -or
            $bounds.Right -gt $window.Right -or $bounds.Bottom -gt $window.Bottom) {
            throw 'Target is outside its visible UI window or clipped by an ancestor.'
        }
        $hasWindow = $true
        $ancestor = $ancestor.ParentNode
    }
    if (-not $hasWindow) { throw 'Target has no enclosing UI window; inspect before retrying.' }
    [pscustomobject]@{
        X = [int][math]::Floor($bounds.Left + ($bounds.Right - $bounds.Left) / 2.0)
        Y = [int][math]::Floor($bounds.Top + ($bounds.Bottom - $bounds.Top) / 2.0)
        Bounds = $target.GetAttribute('bounds')
        Package = $Package
    }
}

if ($Action -eq 'SelfTest') {
    # Pure XML fixtures: no device, output directory, or adb invocation.
    $appPackage = 'com.nexthci.ringfitness.steps'
    $node = '<node text="Confirm" package="{0}" clickable="true" enabled="true" bounds="{1}" />'
    $wrap = '<hierarchy><node package="com.nexthci.ringfitness.steps" bounds="[0,0][100,200]">{0}</node></hierarchy>'
    $valid = $node -f $appPackage, '[10,20][31,61]'
    $tap = Resolve-AndroidUiTap ([xml]($wrap -f $valid)) 'Confirm' $appPackage
    if ($tap.X -ne 20 -or $tap.Y -ne 40) { throw 'SelfTest: valid target center is incorrect.' }
    $rejected = @(
        @{ Name = 'other app with same text'; Body = ($node -f 'com.example.other', '[10,20][31,61]') },
        @{ Name = 'system package without explicit selection'; Body = ($node -f 'com.android.permissioncontroller', '[10,20][31,61]') },
        @{ Name = 'missing target'; Body = '' },
        @{ Name = 'duplicate target'; Body = ($valid + $valid) },
        @{ Name = 'negative X'; Body = ($node -f $appPackage, '[-1,20][31,61]') },
        @{ Name = 'negative Y'; Body = ($node -f $appPackage, '[10,-1][31,61]') },
        @{ Name = 'zero width'; Body = ($node -f $appPackage, '[10,20][10,61]') },
        @{ Name = 'zero height'; Body = ($node -f $appPackage, '[10,20][31,20]') },
        @{ Name = 'outside window width'; Body = ($node -f $appPackage, '[10,20][101,61]') },
        @{ Name = 'outside window height'; Body = ($node -f $appPackage, '[10,20][31,201]') },
        @{ Name = 'malformed trailing bounds'; Body = ($node -f $appPackage, '[10,20][31,61]extra') },
        @{ Name = 'trailing newline'; Body = ($node -f $appPackage, '[10,20][31,61]&#10;') },
        @{ Name = 'overflow coordinate'; Body = ($node -f $appPackage, '[10,20][2147483648,61]') },
        @{ Name = 'clipped by ancestor'; Body = ('<node bounds="[0,0][20,100]">' + $valid + '</node>') },
        @{ Name = 'invisible ancestor'; Body = ('<node bounds="[0,0][100,200]" visible-to-user="false">' + $valid + '</node>') }
    )
    foreach ($case in $rejected) {
        $didReject = $false
        try { $null = Resolve-AndroidUiTap ([xml]($wrap -f $case.Body)) 'Confirm' $appPackage }
        catch { $didReject = $true }
        if (-not $didReject) { throw "SelfTest: $($case.Name) was accepted." }
    }
    $permissionNode = $node -f 'com.android.permissioncontroller', '[10,20][31,61]'
    $permissionTap = Resolve-AndroidUiTap ([xml]($wrap -f $permissionNode)) 'Confirm' 'com.android.permissioncontroller'
    if ($permissionTap.X -ne 20 -or $permissionTap.Y -ne 40) { throw 'SelfTest: explicit system package failed.' }
    [pscustomobject]@{ Tests = $rejected.Count + 2; Passed = $rejected.Count + 2; Failed = 0; DeviceUsed = $false }
    return
}

if ([string]::IsNullOrWhiteSpace($Serial)) { throw 'Inspect, Capture, and Tap require an explicit device Serial.' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$raw = & $Adb -s $Serial exec-out uiautomator dump /dev/tty
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect device UI.' }
$output = $raw -join "`n"
$start = $output.IndexOf('<?xml')
$end = $output.IndexOf('</hierarchy>')
if ($start -lt 0 -or $end -lt $start) { throw 'Device UI is not ready; inspect the current app and retry.' }
$xmlText = $output.Substring($start, $end + '</hierarchy>'.Length - $start)
$xmlText | Set-Content -LiteralPath (Join-Path $OutputDirectory "$Step.xml") -Encoding utf8
[xml]$tree = $xmlText
$nodes = $tree.SelectNodes('//node')

if ($Action -eq 'Tap') {
    # System permission dialogs require their package via -ExpectedPackage explicitly.
    $tap = Resolve-AndroidUiTap $tree $Text $ExpectedPackage
    & $Adb -s $Serial shell input tap $tap.X $tap.Y
    if ($LASTEXITCODE -ne 0) { throw 'Device rejected tap.' }
    "Tapped '$Text' in '$ExpectedPackage' at UI-derived bounds $($tap.Bounds)"
} elseif ($Action -eq 'Capture') {
    & $Adb -s $Serial shell screencap -p /data/local/tmp/ringfitness_qa.png
    if ($LASTEXITCODE -ne 0) { throw 'Screenshot failed.' }
    & $Adb -s $Serial pull /data/local/tmp/ringfitness_qa.png (Join-Path $OutputDirectory "$Step.png")
    if ($LASTEXITCODE -ne 0) { throw 'Screenshot transfer failed.' }
} else {
    $nodes | Where-Object { $_.text -or $_.scrollable -eq 'true' } |
        Select-Object text, class, bounds, enabled, scrollable | ConvertTo-Json
}
