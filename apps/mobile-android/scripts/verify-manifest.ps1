# Verify Android Manifest security configuration
# RED phase: checks for cleartext traffic, network security config, and NEARBY_WIFI_DEVICES permission

param(
    [string]$ManifestPath = "app/src/main/AndroidManifest.xml"
)

$errors = @()

# Check if manifest exists
if (-not (Test-Path $ManifestPath)) {
    Write-Error "Manifest not found at $ManifestPath"
    exit 1
}

# Load manifest XML
try {
    [xml]$manifest = Get-Content $ManifestPath
} catch {
    Write-Error "Failed to parse manifest XML: $_"
    exit 1
}

# Helper: get attribute value by local name (avoids XPath namespace issues)
function Get-AttrValue([System.Xml.XmlElement]$node, [string]$localName) {
    foreach ($attr in $node.Attributes) {
        if ($attr.LocalName -eq $localName) { return $attr.Value }
    }
    return $null
}

# Check 1: usesCleartextTraffic attribute on <application>
$appNode = $manifest.manifest.application
if ($null -eq $appNode) {
    $errors += "ERROR: <application> element not found"
} else {
    $usesCleartext = Get-AttrValue $appNode "usesCleartextTraffic"
    if ($usesCleartext -ne "true") {
        $errors += "ERROR: android:usesCleartextTraffic not set to 'true' (current: '$usesCleartext')"
    } else {
        Write-Host "PASS: android:usesCleartextTraffic = true"
    }
}

# Check 2: networkSecurityConfig attribute on <application>
if ($null -ne $appNode) {
    $networkSecConfig = Get-AttrValue $appNode "networkSecurityConfig"
    if ($networkSecConfig -ne "@xml/network_security_config") {
        $errors += "ERROR: android:networkSecurityConfig not set to '@xml/network_security_config' (current: '$networkSecConfig')"
    } else {
        Write-Host "PASS: android:networkSecurityConfig = @xml/network_security_config"
    }
}

# Check 3: NEARBY_WIFI_DEVICES permission with usesPermissionFlags='neverForLocation'
$nearbyWifiFound = $false
$nearbyWifiFlags = $null
foreach ($perm in $manifest.manifest.'uses-permission') {
    $permName = Get-AttrValue $perm "name"
    if ($permName -eq "android.permission.NEARBY_WIFI_DEVICES") {
        $nearbyWifiFound = $true
        $nearbyWifiFlags = Get-AttrValue $perm "usesPermissionFlags"
        break
    }
}
if (-not $nearbyWifiFound) {
    $errors += "ERROR: NEARBY_WIFI_DEVICES permission not declared"
} elseif ($nearbyWifiFlags -ne "neverForLocation") {
    $errors += "ERROR: NEARBY_WIFI_DEVICES usesPermissionFlags not 'neverForLocation' (current: '$nearbyWifiFlags')"
} else {
    Write-Host "PASS: NEARBY_WIFI_DEVICES declared with usesPermissionFlags = neverForLocation"
}

# Check 4: CHANGE_WIFI_MULTICAST_STATE still present
$changeWifiFound = $false
foreach ($perm in $manifest.manifest.'uses-permission') {
    $permName = Get-AttrValue $perm "name"
    if ($permName -eq "android.permission.CHANGE_WIFI_MULTICAST_STATE") {
        $changeWifiFound = $true
        break
    }
}
if (-not $changeWifiFound) {
    $errors += "ERROR: CHANGE_WIFI_MULTICAST_STATE permission was removed (should be kept)"
} else {
    Write-Host "PASS: CHANGE_WIFI_MULTICAST_STATE permission still present"
}

# Check 4: network_security_config.xml exists, is valid XML,
#          and declares <base-config cleartextTrafficPermitted='true'>.
$nscPath = Join-Path (Join-Path $PSScriptRoot "..") "app/src/main/res/xml/network_security_config.xml"
if (-not (Test-Path $nscPath)) {
    $errors += "ERROR: network_security_config.xml not found at $nscPath"
} else {
    try {
        [xml]$nsc = Get-Content $nscPath
        $baseConfig = $nsc.SelectSingleNode("/network-security-config/base-config[@cleartextTrafficPermitted='true']")
        if ($null -eq $baseConfig) {
            $errors += "ERROR: NSC missing <base-config cleartextTrafficPermitted='true'>"
        } else {
            Write-Host "PASS: NSC has <base-config cleartextTrafficPermitted='true'>"
        }
    } catch {
        $errors += "ERROR: NSC is not valid XML: $($_.Exception.Message)"
    }
}

# Report results
Write-Host ""
if ($errors.Count -gt 0) {
    Write-Host "VERIFICATION FAILED ($($errors.Count) error(s)):"
    foreach ($err in $errors) {
        Write-Host $err
    }
    exit 1
} else {
    Write-Host "VERIFICATION PASSED: All manifest security checks passed"
    exit 0
}
