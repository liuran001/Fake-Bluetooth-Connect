package io.github.liuran001.fakebluetoothconnect

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Fakes the audio routing side of a Bluetooth A2DP connection.
 *
 * Music apps that ship a car/external Bluetooth lyric feature usually no longer call
 * [AudioManager.isBluetoothA2dpOn] only. They enumerate output devices with
 * [AudioManager.getDevices] and inspect [AudioDeviceInfo.getType] (or they wait for
 * [AudioDeviceCallback.onAudioDevicesAdded]). Those paths are covered here.
 */
internal object AudioHooks {

    private const val INJECT_DELAY_MS = 800L

    fun install() {
        hookA2dpStateQueries()
        hookDeviceList()
        hookDeviceInfo()
        hookDeviceCallbacks()
    }

    private fun hookA2dpStateQueries() {
        safeHook("AudioManager.isBluetoothA2dpOn") {
            XposedHelpers.findAndHookMethod(
                AudioManager::class.java,
                "isBluetoothA2dpOn",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result as? Boolean != true) {
                            param.result = true
                        }
                    }
                },
            )
        }
    }

    private fun hookDeviceList() {
        safeHook("AudioManager.getDevices") {
            XposedHelpers.findAndHookMethod(
                AudioManager::class.java,
                "getDevices",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // Only output queries can report an A2DP sink; leave input-only
                        // requests untouched so callers do not see a phantom microphone.
                        val flags = param.args.firstOrNull() as? Int ?: return
                        if (flags and AudioManager.GET_DEVICES_OUTPUTS == 0) return

                        val result = param.result as? Array<*> ?: return
                        val devices = result.filterIsInstance<AudioDeviceInfo>()
                        if (devices.isEmpty()) return
                        if (devices.any { isBluetoothOutput(it.type) }) return

                        val target = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                            ?: devices.firstOrNull { it.isSink }
                            ?: devices.first()
                        FakeState.markSpoofed(target)
                    }
                },
            )
        }
    }

    private fun hookDeviceInfo() {
        safeHook("AudioDeviceInfo.getType") {
            XposedHelpers.findAndHookMethod(
                AudioDeviceInfo::class.java,
                "getType",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (FakeState.isSpoofed(param.thisObject)) {
                            param.result = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        }
                    }
                },
            )
        }

        safeHook("AudioDeviceInfo.getAddress") {
            XposedHelpers.findAndHookMethod(
                AudioDeviceInfo::class.java,
                "getAddress",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (FakeState.isSpoofed(param.thisObject)) {
                            param.result = FakeState.FAKE_ADDRESS
                        }
                    }
                },
            )
        }

        safeHook("AudioDeviceInfo.getProductName") {
            XposedHelpers.findAndHookMethod(
                AudioDeviceInfo::class.java,
                "getProductName",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (FakeState.isSpoofed(param.thisObject) && param.result == null) {
                            param.result = FakeState.FAKE_DEVICE_NAME
                        }
                    }
                },
            )
        }

        safeHook("AudioDeviceInfo.isSink") {
            XposedHelpers.findAndHookMethod(
                AudioDeviceInfo::class.java,
                "isSink",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (FakeState.isSpoofed(param.thisObject)) {
                            param.result = true
                        }
                    }
                },
            )
        }
    }

    private fun hookDeviceCallbacks() {
        safeHook("AudioManager.registerAudioDeviceCallback") {
            XposedHelpers.findAndHookMethod(
                AudioManager::class.java,
                "registerAudioDeviceCallback",
                AudioDeviceCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val callback = param.args.firstOrNull() as? AudioDeviceCallback ?: return
                        if (!FakeState.claimCallback(callback)) return
                        val manager = param.thisObject as? AudioManager ?: return
                        val handler = param.args.getOrNull(1) as? Handler ?: Handler(Looper.getMainLooper())
                        handler.postDelayed({ injectFakeDevice(manager, callback) }, INJECT_DELAY_MS)
                    }
                },
            )
        }
    }

    private fun injectFakeDevice(manager: AudioManager, callback: AudioDeviceCallback) {
        runCatching {
            val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val fake = devices.firstOrNull { FakeState.isSpoofed(it) }
            if (fake == null) {
                FakeState.log("no audio device available to fake")
            } else {
                callback.onAudioDevicesAdded(arrayOf(fake))
                FakeState.log("injected fake A2DP device into AudioDeviceCallback")
            }
        }.onFailure {
            FakeState.logError("failed to inject fake audio device", it)
        }
    }

    private fun isBluetoothOutput(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }
}
