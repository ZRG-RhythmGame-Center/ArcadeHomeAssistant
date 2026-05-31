package com.maimai.home.data

/**
 * Abstraction for WifiManager.MulticastLock to enable testing.
 */
interface MulticastLock {
    fun acquire()
    fun release()
    fun setReferenceCounted(counted: Boolean)
}

/**
 * Factory for creating MulticastLock instances.
 */
interface MulticastLockFactory {
    fun createMulticastLock(tag: String): MulticastLock
}

/**
 * Real implementation wrapping WifiManager.MulticastLock.
 */
class RealMulticastLockFactory(private val wifiManager: android.net.wifi.WifiManager) : MulticastLockFactory {
    override fun createMulticastLock(tag: String): MulticastLock {
        return RealMulticastLockAdapter(wifiManager.createMulticastLock(tag))
    }
}

/**
 * Adapter to wrap WifiManager.MulticastLock as our interface.
 */
private class RealMulticastLockAdapter(
    private val lock: android.net.wifi.WifiManager.MulticastLock
) : MulticastLock {
    override fun acquire() = lock.acquire()
    override fun release() = lock.release()
    override fun setReferenceCounted(counted: Boolean) = lock.setReferenceCounted(counted)
}
