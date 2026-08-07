# Provider Debugging Mistakes

## SALT-NEXT-TRACK-001: 后台队列轮询触发 MusicController 非主线程初始化

- 状态：修复中，等待真机验收
- 症状：官方 Pack 真正加载后，椒盐音乐启动即崩溃，表现为应用无法打开。
- 已确认运行证据：`140022` Debug 日志显示 Pack 1.0.1 已加载、生命周期 Hook 命中并注册 Provider；随后 `SaltPlayerNextTrackResolver.resolve()` 在 `HLE-SaltPlayer-NextTrack` 线程读取静态队列字段，触发 `MusicController.<clinit>`。椒盐内部因 `addObserver must be called on the main thread` 抛出 `ExceptionInInitializerError`，最终主线程以 `NoClassDefFoundError: MusicController` 崩溃。
- 已证伪方向：Pack 加载失败、作用域缺失、Provider 未注册和 Central 优先级不是本次启动崩溃断点；不得继续修改这些层来掩盖崩溃。
- 修复边界：`MusicController` 必须在 Application 回调的主线程完成初始化；队列 StateFlow 的反射读取与轮询也统一由主线程 Handler 调度，禁止恢复后台 `ScheduledExecutorService`。
- 验收条件：椒盐可稳定启动；日志不再出现 `addObserver must be called on the main thread`、`ExceptionInInitializerError` 或 `NoClassDefFoundError: MusicController`；下一首采集、完整歌词 Hook 和 Provider 注册仍能正常工作。
