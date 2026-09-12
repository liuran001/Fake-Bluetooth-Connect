package io.github.liuran001.fakebluetoothconnect

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap

internal object FakeState {

    const val TAG = "FakeBluetoothConnect"
    const val FAKE_ADDRESS = "00:11:22:33:44:55"
    const val FAKE_DEVICE_NAME = "Fake A2DP"

    private val spoofedAudioDevices = Collections.synchronizedMap(WeakHashMap<AudioDeviceInfo, Boolean>())
    private val injectedCallbacks = Collections.newSetFromMap(WeakHashMap<AudioDeviceCallback, Boolean>())
    private val callbackLock = Any()

    @Volatile
    private var cachedFakeDevice: BluetoothDevice? = null

    fun markSpoofed(device: AudioDeviceInfo) {
        spoofedAudioDevices[device] = true
    }

    fun isSpoofed(device: Any?): Boolean {
        return device is AudioDeviceInfo && spoofedAudioDevices.containsKey(device)
    }

    /** Returns true when this callback has not been fed with a fake device yet. */
    fun claimCallback(callback: AudioDeviceCallback): Boolean {
        return synchronized(callbackLock) { injectedCallbacks.add(callback) }
    }

    fun fakeDevice(): BluetoothDevice? {
        cachedFakeDevice?.let { return it }
        val created = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(FAKE_ADDRESS)
        }.onFailure {
            log("cannot build fake BluetoothDevice: ${it.message}")
        }.getOrNull()
        cachedFakeDevice = created
        return created
    }

    fun isFakeDevice(device: Any?): Boolean {
        val fake = fakeDevice() ?: return false
        if (device === fake) return true
        if (device !is BluetoothDevice) return false
        return runCatching { device.address == FAKE_ADDRESS }.getOrDefault(false)
    }

    fun log(message: String) {
        XposedBridge.log("$TAG: $message")
    }

    fun logError(message: String, throwable: Throwable) {
        XposedBridge.log("$TAG: $message")
        XposedBridge.log(throwable)
    }
}

internal inline fun safeHook(name: String, block: () -> Unit) {
    runCatching(block).onFailure { FakeState.logError("failed to hook $name", it) }
}
