package io.github.liuran001.fakebluetoothconnect

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Fakes the Bluetooth side of an A2DP connection.
 *
 * Besides [BluetoothAdapter.getProfileConnectionState] an app can also bind an A2DP/Headset
 * profile proxy and ask it for connected devices, or check the adapter state first. All of
 * those entry points are covered so that apps which never call the legacy
 * [android.media.AudioManager.isBluetoothA2dpOn] helper still see a connected device.
 */
internal object BluetoothHooks {

    private const val A2DP_CLASS = "android.bluetooth.BluetoothA2dp"
    private const val HEADSET_CLASS = "android.bluetooth.BluetoothHeadset"
    private const val SERVICE_LISTENER_CLASS = "android.bluetooth.BluetoothProfile\$ServiceListener"

    fun install(classLoader: ClassLoader) {
        hookAdapter(classLoader)
        hookProfileProxies(classLoader)
        hookDevice()
    }

    private fun hookAdapter(classLoader: ClassLoader) {
        safeHook("BluetoothAdapter.isEnabled") {
            XposedHelpers.findAndHookMethod(
                BluetoothAdapter::class.java,
                "isEnabled",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result as? Boolean != true) {
                            param.result = true
                        }
                    }
                },
            )
        }

        safeHook("BluetoothAdapter.getState") {
            XposedHelpers.findAndHookMethod(
                BluetoothAdapter::class.java,
                "getState",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result as? Int != BluetoothAdapter.STATE_ON) {
                            param.result = BluetoothAdapter.STATE_ON
                        }
                    }
                },
            )
        }

        safeHook("BluetoothAdapter.getProfileConnectionState") {
            XposedHelpers.findAndHookMethod(
                BluetoothAdapter::class.java,
                "getProfileConnectionState",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val profile = param.args.firstOrNull() as? Int ?: return
                        if (profile != BluetoothProfile.A2DP && profile != BluetoothProfile.HEADSET) return
                        if (param.result as? Int != BluetoothProfile.STATE_CONNECTED) {
                            param.result = BluetoothProfile.STATE_CONNECTED
                        }
                    }
                },
            )
        }

        safeHook("BluetoothAdapter.getBondedDevices") {
            XposedHelpers.findAndHookMethod(
                BluetoothAdapter::class.java,
                "getBondedDevices",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // getBondedDevices() is declared to return a Set, so a List here would
                        // make the caller fail with a ClassCastException.
                        param.result = fakeDeviceSet(param.result)
                    }
                },
            )
        }

        safeHook("BluetoothAdapter.getProfileProxy") {
            val listenerClass = runCatching { Class.forName(SERVICE_LISTENER_CLASS, true, classLoader) }
                .getOrNull()
                ?: runCatching { Class.forName(SERVICE_LISTENER_CLASS) }.getOrNull()
                ?: return
            XposedHelpers.findAndHookMethod(
                BluetoothAdapter::class.java,
                "getProfileProxy",
                Context::class.java,
                listenerClass,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result as? Boolean == true) return
                        val profile = param.args.getOrNull(2) as? Int ?: return
                        if (profile != BluetoothProfile.A2DP && profile != BluetoothProfile.HEADSET) return
                        val listener = param.args.getOrNull(1) as? BluetoothProfile.ServiceListener ?: return
                        val context = param.args.getOrNull(0) as? Context
                        val proxy = createProfileProxy(context, profile) ?: return
                        param.result = true
                        Handler(Looper.getMainLooper()).postDelayed({
                            runCatching { listener.onServiceConnected(profile, proxy) }
                                .onFailure { FakeState.logError("failed to dispatch fake profile proxy", it) }
                        }, 300L)
                    }
                },
            )
        }
    }

    private fun hookProfileProxies(classLoader: ClassLoader) {
        hookProfileProxy(classLoader, A2DP_CLASS, activeMethod = "isA2dpPlaying")
        hookProfileProxy(classLoader, HEADSET_CLASS, activeMethod = "isAudioConnected")
    }

    private fun hookProfileProxy(classLoader: ClassLoader, className: String, activeMethod: String) {
        val profileClass = runCatching { Class.forName(className, true, classLoader) }.getOrNull()
            ?: runCatching { Class.forName(className) }.getOrNull()
            ?: return

        safeHook("$className.getConnectedDevices") {
            XposedHelpers.findAndHookMethod(
                profileClass,
                "getConnectedDevices",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = fakeDeviceList(param.result)
                    }
                },
            )
        }

        safeHook("$className.getDevicesMatchingConnectionStates") {
            XposedHelpers.findAndHookMethod(
                profileClass,
                "getDevicesMatchingConnectionStates",
                IntArray::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = fakeDeviceList(param.result)
                    }
                },
            )
        }

        safeHook("$className.getConnectionState") {
            XposedHelpers.findAndHookMethod(
                profileClass,
                "getConnectionState",
                BluetoothDevice::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = BluetoothProfile.STATE_CONNECTED
                    }
                },
            )
        }

        safeHook("$className.$activeMethod") {
            XposedHelpers.findAndHookMethod(
                profileClass,
                activeMethod,
                BluetoothDevice::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                },
            )
        }
    }

    private fun hookDevice() {
        safeHook("BluetoothDevice.getName") {
            XposedHelpers.findAndHookMethod(
                BluetoothDevice::class.java,
                "getName",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (FakeState.isFakeDevice(param.thisObject)) {
                            param.result = FakeState.FAKE_DEVICE_NAME
                        }
                    }
                },
            )
        }

        safeHook("BluetoothDevice.getBondState") {
            XposedHelpers.findAndHookMethod(
                BluetoothDevice::class.java,
                "getBondState",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (FakeState.isFakeDevice(param.thisObject)) {
                            param.result = BluetoothDevice.BOND_BONDED
                        }
                    }
                },
            )
        }

        safeHook("BluetoothDevice.isConnected") {
            XposedHelpers.findAndHookMethod(
                BluetoothDevice::class.java,
                "isConnected",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (FakeState.isFakeDevice(param.thisObject)) {
                            param.result = true
                        }
                    }
                },
            )
        }
    }

    private fun createProfileProxy(context: Context?, profile: Int): BluetoothProfile? {
        val className = if (profile == BluetoothProfile.A2DP) A2DP_CLASS else HEADSET_CLASS
        return runCatching {
            val profileClass = Class.forName(className)
            val constructor = profileClass.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 2 || it.parameterTypes.size == 3
            }
            if (constructor == null) {
                null
            } else {
                constructor.isAccessible = true
                val args = if (constructor.parameterTypes.size == 3) {
                    arrayOf<Any?>(context, null, BluetoothAdapter.getDefaultAdapter())
                } else {
                    arrayOf<Any?>(context, null)
                }
                constructor.newInstance(*args) as? BluetoothProfile
            }
        }.onFailure {
            FakeState.log("cannot create $className proxy: ${it.message}")
        }.getOrNull()
    }

    /**
     * Merges the real result with the fake device, preserving the declared return type of the
     * hooked method. [LinkedHashSet] keeps the original order and drops duplicates; it is wrapped
     * in an [ArrayList] only for the methods that return a `List`.
     */
    private fun mergeFakeDevice(result: Any?): LinkedHashSet<BluetoothDevice> {
        val devices = LinkedHashSet<BluetoothDevice>()
        (result as? Iterable<*>)?.forEach { device ->
            if (device is BluetoothDevice) devices.add(device)
        }
        FakeState.fakeDevice()?.let { fake ->
            if (devices.none { FakeState.isFakeDevice(it) }) {
                devices.add(fake)
            }
        }
        return devices
    }

    private fun fakeDeviceSet(result: Any?): Set<BluetoothDevice> = mergeFakeDevice(result)

    private fun fakeDeviceList(result: Any?): List<BluetoothDevice> = ArrayList(mergeFakeDevice(result))
}
