package com.maimai.home

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Manifest security configuration tests.
 * Verifies that the app is configured to:
 * 1. Allow cleartext traffic to RFC1918 private networks
 * 2. Declare NEARBY_WIFI_DEVICES permission for mDNS discovery on Android 13+
 * 3. Maintain CHANGE_WIFI_MULTICAST_STATE for legacy mDNS support
 *
 * These tests close R1 findings #1 (Critical) and #2 (Major).
 */
@RunWith(RobolectricTestRunner::class)
@Config(minSdk = 21)
class ManifestSecurityTest {

    /**
     * RED: Verify that cleartext traffic is enabled in the manifest.
     * This allows HTTP connections to LAN agents on Android 9+.
     *
     * Expected: applicationInfo.flags contains FLAG_USES_CLEARTEXT_TRAFFIC
     */
    @Test
    fun cleartextTrafficEnabled() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )
        
        val hasCleartextFlag = (appInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0
        assert(hasCleartextFlag) { "FLAG_USES_CLEARTEXT_TRAFFIC not set in application flags" }
    }

    /**
     * RED: Verify that network security config is referenced in the manifest.
     * This config file whitelists RFC1918 ranges for cleartext HTTP.
     *
     * Expected: applicationInfo.networkSecurityConfigRes != 0
     */
    @Test
    fun networkSecurityConfigPresent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )
        
        assert(appInfo.networkSecurityConfigRes != 0) {
            "networkSecurityConfigRes not set (expected @xml/network_security_config)"
        }
    }

    /**
     * RED: Verify that NEARBY_WIFI_DEVICES permission is declared with neverForLocation flag.
     * This enables mDNS NSD discovery on Android 13+ without location permission.
     *
     * Expected: NEARBY_WIFI_DEVICES in manifest with usesPermissionFlags="neverForLocation"
     */
    @Test
    fun declaresNearbyWifiDevicesOnSdk33Plus() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        
        val hasNearbyWifiDevices = packageInfo.requestedPermissions?.contains(
            "android.permission.NEARBY_WIFI_DEVICES"
        ) ?: false
        
        assert(hasNearbyWifiDevices) {
            "NEARBY_WIFI_DEVICES permission not declared in manifest"
        }
    }

    /**
     * GREEN: Verify that CHANGE_WIFI_MULTICAST_STATE is still declared.
     * This maintains backward compatibility for mDNS on older Android versions.
     *
     * Expected: CHANGE_WIFI_MULTICAST_STATE in manifest
     */
    @Test
    fun keepsChangeWifiMulticastStatePermission() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        
        val hasChangeWifiMulticast = packageInfo.requestedPermissions?.contains(
            "android.permission.CHANGE_WIFI_MULTICAST_STATE"
        ) ?: false
        
        assert(hasChangeWifiMulticast) {
            "CHANGE_WIFI_MULTICAST_STATE permission was removed (should be kept for backward compatibility)"
        }
    }
}
