# Fake Bluetooth Connect

LSPosed module that makes scoped apps always think a Bluetooth A2DP device is connected.

## Hook coverage

Legacy state queries:

- `AudioManager.isBluetoothA2dpOn()`
- `BluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP | HEADSET)`

Adapter state:

- `BluetoothAdapter.isEnabled()`
- `BluetoothAdapter.getState()`
- `BluetoothAdapter.getBondedDevices()`

Profile proxies (apps that bind A2DP/Headset instead of asking `AudioManager`):

- `BluetoothAdapter.getProfileProxy(...)` — delivers a proxy even when Bluetooth is off
- `BluetoothA2dp.getConnectedDevices()` / `getDevicesMatchingConnectionStates()` / `getConnectionState()` / `isA2dpPlaying()`
- `BluetoothHeadset.getConnectedDevices()` / `getDevicesMatchingConnectionStates()` / `getConnectionState()` / `isAudioConnected()`

Audio routing (what most modern music apps actually read):

- `AudioManager.getDevices(int)` — one output device is reported as an A2DP sink
- `AudioDeviceInfo.getType()` / `getAddress()` / `getProductName()` / `isSink()` for that device
- `AudioManager.registerAudioDeviceCallback(...)` — triggers `onAudioDevicesAdded` with the fake A2DP device

Permission guard (Android 12+):

- `Context.checkSelfPermission(android.permission.BLUETOOTH_CONNECT)` — reported as granted
  so apps that skip Bluetooth queries without it still run the query
- `Context.checkPermission(android.permission.BLUETOOTH_CONNECT, pid, uid)` — the path
  `ContextCompat.checkSelfPermission` takes; answered for this process only

The goal is to let some music apps enable external lyric display features even when no Bluetooth device is actually connected.

## Usage

1. Install the APK
2. Enable this module in LSPosed
3. Add the target app to the module scope in LSPosed
4. Force stop the target app
5. Reopen the target app
6. Turn on the External Device Bluetooth Lyrics / Car Bluetooth Lyrics feature inside the target app

## Notes

- This module is designed to be minimally invasive
- It only changes app-side query results
- It does not fake global Bluetooth connection state for the whole system
- Compatibility may vary across apps if they rely on additional audio device callbacks or their own cached state

## Tested

- NetEase Cloud Music: V9.4.70 working
- QQ Music: V20.1.5.8 working

## Analysed (reverse engineered, awaiting on-device confirmation)

- KuGou Music — state comes from `BluetoothAdapter.getProfileProxy()` plus
  `BluetoothHeadset.getConnectedDevices()` / `isAudioConnected()`
- KuWo Music — never calls `isBluetoothA2dpOn()`; it enumerates
  `AudioManager.getDevices()` and listens to `AudioDeviceCallback`
- QiShui Music — `OutputDeviceManager` reads `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`
  and matches `AudioDeviceInfo.getType()` against `TYPE_BLUETOOTH_A2DP`(8) / `TYPE_BLUETOOTH_SCO`(7)

## Build

This project uses:

- Android Studio / Gradle
- legacy Xposed API 82 (compatible with LSPosed legacy module loading)
- Kotlin

## License

MIT

---

# 伪蓝牙连接

一个 LSPosed 模块，让被加入作用域的应用始终认为当前已连接蓝牙 A2DP 设备。

## Hook 范围

旧版状态查询：

- `AudioManager.isBluetoothA2dpOn()`
- `BluetoothAdapter.getProfileConnectionState(BluetoothProfile.A2DP | HEADSET)`

适配器状态：

- `BluetoothAdapter.isEnabled()`
- `BluetoothAdapter.getState()`
- `BluetoothAdapter.getBondedDevices()`

Profile 代理（不查 AudioManager、改为绑定 A2DP/Headset 的应用）：

- `BluetoothAdapter.getProfileProxy(...)`：蓝牙关闭时也会回调一个代理
- `BluetoothA2dp.getConnectedDevices()` / `getDevicesMatchingConnectionStates()` / `getConnectionState()` / `isA2dpPlaying()`
- `BluetoothHeadset.getConnectedDevices()` / `getDevicesMatchingConnectionStates()` / `getConnectionState()` / `isAudioConnected()`

音频路由（多数新版音乐应用实际读取的路径）：

- `AudioManager.getDevices(int)`：把一个输出设备伪装成 A2DP
- `AudioDeviceInfo.getType()` / `getAddress()` / `getProductName()` / `isSink()`
- `AudioManager.registerAudioDeviceCallback(...)`：主动向回调投递一个伪 A2DP 设备

权限守卫（Android 12+）：

- `Context.checkSelfPermission(android.permission.BLUETOOTH_CONNECT)`：返回已授权，
  避免应用因为没有该权限而根本不去查询蓝牙状态
- `Context.checkPermission(android.permission.BLUETOOTH_CONNECT, pid, uid)`：
  `ContextCompat.checkSelfPermission` 实际调用的是这个三参数版本，仅对当前进程返回已授权

目的是让部分音乐应用在没有真正连接蓝牙设备时，仍然启用外显歌词功能。

## 使用方法

1. 安装 APK
2. 在 LSPosed 中启用本模块
3. 在 LSPosed 中给本模块勾选目标应用作用域
4. 强制停止目标应用
5. 重新打开目标应用
6. 在目标应用内部开启 外接设备蓝牙歌词 / 车载蓝牙歌词 功能

## 说明

- 本模块尽量保持最小侵入
- 只修改应用侧查询结果
- 不会伪造整个系统的全局蓝牙状态
- 如果某些应用依赖额外的音频设备回调或自身缓存状态，兼容性可能存在差异

## 已测试

- 网易云音乐: V9.4.70 可用
- QQ音乐: V20.1.5.8 可用

## 已逆向分析（等待真机确认）

- 酷狗音乐：状态来自 `BluetoothAdapter.getProfileProxy()` 与
  `BluetoothHeadset.getConnectedDevices()` / `isAudioConnected()`
- 酷我音乐：完全没有调用 `isBluetoothA2dpOn()`，改为遍历
  `AudioManager.getDevices()` 并监听 `AudioDeviceCallback`
- 汽水音乐：`OutputDeviceManager` 读取 `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`，
  再判断 `AudioDeviceInfo.getType()` 是否为 `TYPE_BLUETOOTH_A2DP`(8) / `TYPE_BLUETOOTH_SCO`(7)

## 构建

项目使用：

- Android Studio / Gradle
- legacy Xposed API 82（通过 LSPosed 的 legacy 模块兼容路径加载）
- Kotlin

## 协议

MIT
