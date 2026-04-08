# Android P0 闪退根因报告

日期: 2026-04-09

范围:
- 仅针对 Android 端“偶发闪退”做 P0 根因定位
- 不把坏声音识别、音频参数、协议、PC 端逻辑混入本次结论

结论:
- 真正主因是前台重连窗口触发的 Android 硬件加速渲染线程崩溃
- 更准确地说，是华为 Mali GPU 驱动路径在 `RenderThread` 中发生 native `SIGSEGV`
- 最终最小修复为: 给 `MainActivity` 关闭硬件加速

不属于主因:
- AudioTrack 生命周期本身
- RTP/UDP socket 生命周期本身
- Opus decoder/native decode 本身
- SessionManager 状态跃迁本身
- ResourceGuard 资源释放顺序本身

## 1. 问题背景

应用链路涉及:
- Kotlin
- Jetpack Compose
- Foreground Service
- StreamCoordinator
- SessionManager
- ResourceGuard
- AudioPlaybackEngine
- JitterBuffer
- RTP/UDP socket
- Opus decoder

用户现象:
- 前台快速重连更容易闪退
- 自动恢复窗口更容易闪退
- 后台场景或静止播放时，不容易触发

## 2. 证据采集能力

为避免“猜”，本次先补齐并使用了以下诊断能力:

### 2.1 全局崩溃采集

文件:
- `apps/android-app/app/src/main/java/com/mobilespeaker/app/diagnostics/CrashDiagnostics.kt`
- `apps/android-app/app/src/main/java/com/mobilespeaker/app/MobileSpeakerApplication.kt`

能力:
- `Thread.setDefaultUncaughtExceptionHandler`
- `CoroutineExceptionHandler`
- 最近事件 ring buffer
- `latest_events.json`
- `latest_crash.json`

### 2.2 压力复现入口

文件:
- `apps/android-app/app/src/main/java/com/mobilespeaker/app/stream/StreamCoordinator.kt`

入口:
- `runDebugStress(testCase, targetIp, requestedName)`

测试:
- A: 快速点击压力
- B: 短间隔重连
- C: 恢复中再次点击
- D: 首连坏状态后自动恢复

## 3. 修复前复现与证据

### 3.1 测试 A 可复现

修复前关键日志:
- `artifacts/android-crash-p0-suiteA-long/test_A_logcat.txt`

现象:
- 测试 A 可复现 native crash

### 3.2 第一组强证据: Compose 绘制链

文件:
- `artifacts/android-crash-p0-suiteA-long/test_A_logcat.txt`

关键行:
- `1081`: `Fatal signal 11 (SIGSEGV)`
- `1125`: `androidx.compose.ui.graphics.AndroidCanvas.restore`
- `1173`: `androidx.compose.ui.platform.GraphicsLayerOwnerLayer.drawLayer`
- `1323`: `androidx.compose.foundation.DrawGlowOverscrollModifier.draw`

说明:
- 崩点在前台 UI 绘制链
- 不是 AudioTrack/socket/decoder 的 Java 业务栈

### 3.3 第二组强证据: RenderThread / Mali 驱动

文件:
- `artifacts/android-crash-p0-overscrollfix/test_A_logcat.txt`

关键行:
- `2251`: `Fatal signal 11 (SIGSEGV)`
- `2260`: `tid: RenderThread`
- `2275-2290`: backtrace 全部落在 `/vendor/lib64/egl/libGLES_mali.so`

说明:
- 在切掉一层 Compose glow 触发面后，崩溃收敛成更纯的 GPU 渲染线程 native crash
- 这证明真正的主因不是某个 Compose 业务组件本身，而是硬件加速渲染线程

### 3.4 崩溃前最后 10 条生命周期事件

来源:
- `artifacts/android-crash-p0-suiteA-long/test_A_logcat.txt`

最后 10 条:
1. `disconnect | reset_resources_done`
2. `recovery | reset_session`
3. `connect | discovery_stop`
4. `session | mark_connected`
5. `disconnect | reset_resources_start`
6. `disconnect | reset_resources_done`
7. `service | foreground_onStartCommand`
8. `connect | perform_connect_ok`
9. `disconnect | reset_resources_start`
10. `disconnect | reset_resources_done`

说明:
- 触发窗口明确在 connect/disconnect/reconnect 高频切换期间
- 但最终炸点不是这些生命周期函数直接抛出异常
- 它们只是触发了前台 UI 高频重绘

## 4. 根因结论

### Root cause

前台重连窗口触发 UI 高频无效化，命中华为 Mali 硬件加速渲染路径，导致 `RenderThread` 在 `libGLES_mali.so` 中 native `SIGSEGV`。

### Trigger path

`runDebugStress(A/B/C/D)`
-> `connect / disconnect / reconnect`
-> session 与 UI 状态频繁切换
-> 前台 Compose 页面持续重绘
-> Android 硬件加速渲染线程工作
-> `RenderThread / libGLES_mali.so` native crash

### Crash stack

修复前关键堆栈:
- `AndroidCanvas.restore`
- `GraphicsLayerOwnerLayer.drawLayer`
- `DrawGlowOverscrollModifier.draw`
- 最终收敛到 `RenderThread -> /vendor/lib64/egl/libGLES_mali.so`

### Why it crashes

- 这是 native 渲染线程崩溃
- 因此不会表现成普通 Kotlin/Java 业务异常
- 进程会直接被 tombstone 接管

### Why previous fixes did not solve it

- `sessionToken`、`awaitCleanDisconnect`、`ResourceGuard` 解决的是生命周期竞态和旧 session 污染
- 它们可以降频，但没有移除 GPU 渲染线程
- 去掉文件日志修掉了一个次级 crash 面，但不是最终主因

### Exact code path

关键文件:
- `apps/android-app/app/src/main/java/com/mobilespeaker/app/stream/StreamCoordinator.kt`
- `apps/android-app/app/src/main/java/com/mobilespeaker/app/ui/MainViewModel.kt`
- `apps/android-app/app/src/main/java/com/mobilespeaker/app/MainActivity.kt`

入口:
- `StreamCoordinator.runDebugStress(...)`
- `connect(...)`
- `disconnect(...)`
- `reconnect(...)`

## 5. 最小修复

最终保留的唯一主修复:

文件:
- `apps/android-app/app/src/main/AndroidManifest.xml`

改动:
- 给 `MainActivity` 增加:

```xml
android:hardwareAccelerated="false"
```

原因:
- 直接切断问题执行器: `RenderThread / Mali GPU`
- 不改音频参数
- 不改网络协议
- 不改恢复策略
- 不改 PC 端

## 6. 为什么这次修复是充分的

修复后，使用同一台华为设备、同一 PC、同一压力入口重新验证:

### 测试 A
- 轮数: 30
- 日志: `artifacts/android-crash-p0-hwaccel-fix/test_A_logcat.txt`
- 结果:
  - 出现 `debug_stress_finish | case=A`
  - 无 `Fatal signal`

### 测试 B
- 轮数: 20
- 日志: `artifacts/android-crash-p0-hwaccel-fix/test_B_logcat.txt`
- 结果:
  - 出现 `debug_stress_finish | case=B`
  - 无 `Fatal signal`

### 测试 C
- 轮数: 20
- 日志: `artifacts/android-crash-p0-hwaccel-fix/test_C_logcat.txt`
- 结果:
  - 出现 `debug_stress_finish | case=C`
  - 无 `Fatal signal`

### 测试 D
- 轮数: 10
- 日志: `artifacts/android-crash-p0-hwaccel-fix/test_D_logcat.txt`
- 结果:
  - 出现 `debug_stress_finish | case=D`
  - 无 `Fatal signal`

结论:
- 修复前 A 可复现 native crash
- 修复后 A/B/C/D 全通过
- 所以这次不是“没复现”，而是主因路径已被切断

## 7. 修改文件清单

### 7.1 根因修复

1. `apps/android-app/app/src/main/AndroidManifest.xml`
- 改了什么: `MainActivity` 关闭硬件加速
- 为什么改: 直接针对 `RenderThread / libGLES_mali.so`
- 对应根因: UI 渲染线程 native crash

### 7.2 证据与复现基础设施

2. `apps/android-app/app/src/main/java/com/mobilespeaker/app/diagnostics/CrashDiagnostics.kt`
- 改了什么: crash json + event ring buffer
- 为什么改: 让崩溃可证明

3. `apps/android-app/app/src/main/java/com/mobilespeaker/app/MobileSpeakerApplication.kt`
- 改了什么: 启动时安装 crash 诊断
- 为什么改: 保证采集全局生效

4. `apps/android-app/app/src/main/java/com/mobilespeaker/app/stream/StreamCoordinator.kt`
- 改了什么: 注册状态快照 + 压力测试入口 A/B/C/D
- 为什么改: 建立稳定复现路径

5. `apps/android-app/app/src/main/java/com/mobilespeaker/app/ui/MainViewModel.kt`
- 改了什么: reconnect window 的 UI freeze
- 为什么改: 降低重连窗口 UI 扰动，辅助隔离问题

6. `apps/android-app/app/src/main/java/com/mobilespeaker/app/util/AppLogger.kt`
- 改了什么: 去掉文件追加日志
- 为什么改: 修掉一个已证实的次级 native crash 面

## 8. 副作用评估

唯一已知代价:
- `MainActivity` 使用软件渲染
- 对大多数页面功能无影响
- 理论上动画流畅度可能比硬件加速略弱

但对当前 P0 目标来说:
- 稳定性优先级高于动画性能

## 9. 发布建议

基于当前验证结果:
- Android 端可以基于此修复继续打 `release` 包做对外测试
- 后续如果要重新启用硬件加速，必须单独做设备分级或更细粒度的页面渲染隔离，不能直接全量恢复
