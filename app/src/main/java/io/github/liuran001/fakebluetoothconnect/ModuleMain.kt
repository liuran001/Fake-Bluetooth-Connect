package io.github.liuran001.fakebluetoothconnect

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.Collections

class ModuleMain : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == APPLICATION_ID) return
        if (!hookedProcesses.add(lpparam.processName ?: lpparam.packageName)) return

        runCatching {
            AudioHooks.install()
            BluetoothHooks.install(lpparam.classLoader)
            PermissionsHooks.install(lpparam.classLoader)
            FakeState.log("hooks installed for package=${lpparam.packageName}")
        }.onFailure {
            FakeState.logError("failed to install hooks for package=${lpparam.packageName}", it)
        }
    }

    companion object {
        private const val APPLICATION_ID = "io.github.liuran001.fakebluetoothconnect"

        private val hookedProcesses = Collections.synchronizedSet(mutableSetOf<String>())
    }
}
