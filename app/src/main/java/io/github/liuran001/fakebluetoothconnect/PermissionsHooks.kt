package io.github.liuran001.fakebluetoothconnect

import android.content.pm.PackageManager
import android.os.Process
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Since Android 12 an app without `BLUETOOTH_CONNECT` never even asks about the connection
 * state, it guards the call with a permission check (or simply catches the
 * `SecurityException`). Reporting the permission as granted keeps those code paths alive;
 * the state queries themselves are answered by [AudioHooks] and [BluetoothHooks].
 *
 * Both entry points have to be covered: the platform helper is
 * `checkSelfPermission(String)`, but `ContextCompat.checkSelfPermission` (what most apps
 * actually call) goes straight to `checkPermission(String, int, int)`.
 */
internal object PermissionsHooks {

    private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"

    fun install(classLoader: ClassLoader) {
        hook("android.app.ContextImpl", classLoader)
        hook("android.content.ContextWrapper", classLoader)
    }

    private fun hook(className: String, classLoader: ClassLoader) {
        val target = runCatching { Class.forName(className, true, classLoader) }.getOrNull()
            ?: runCatching { Class.forName(className) }.getOrNull()
            ?: return

        safeHook("$className.checkSelfPermission") {
            XposedHelpers.findAndHookMethod(
                target,
                "checkSelfPermission",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.firstOrNull() == BLUETOOTH_CONNECT) {
                            param.result = PackageManager.PERMISSION_GRANTED
                        }
                    }
                },
            )
        }

        safeHook("$className.checkPermission") {
            XposedHelpers.findAndHookMethod(
                target,
                "checkPermission",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.firstOrNull() != BLUETOOTH_CONNECT) return
                        // Only answer for this process; a check against another uid must stay real.
                        val pid = param.args.getOrNull(1) as? Int ?: return
                        val uid = param.args.getOrNull(2) as? Int ?: return
                        if (pid != Process.myPid() || uid != Process.myUid()) return
                        param.result = PackageManager.PERMISSION_GRANTED
                    }
                },
            )
        }
    }
}
