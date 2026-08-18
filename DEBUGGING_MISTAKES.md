# Provider Debugging Mistakes

## SPOTIFY-PROGRESS-001: 下一首控制帧覆盖 Provider SDK 的当前 Song 重连缓存

- 状态：已解决（2026-08-13，用户真机确认）。修复在 Core 侧 `140097`：`ActivePlayerCoordinator` 在抖音瞬态音频冲突解除后补发活动 recorder 的缓存 Song；Provider 侧无需修改，本仓库保持现状。
- 症状与复现：问题只在 Spotify 进程启动后的第一次播放出现，系统媒体通知和递增 PlaybackState 均存在，但岛上没有当前 Song，表现为进度不跟随；第一次切歌后立即恢复，后续播放正常。本条只处理当前 Song 与位置的连接同步，不修改 Spotify 歌词接口或下一首歌曲列表的业务数据。
- 可靠运行证据：Spotify PID `17109` 于 `2026-08-13 00:58:20` 明确加载 Core `140090` 与 Spotify Provider `1.0.10 (11)`；Central 持续收到 Spotify 的递增 PlaybackState。用户仍复现首次播放无进度，切歌后 `central_song_callback` 与进度立即恢复，证明 `1.0.10` 的 metadata 启动缓存保护没有改变真实行为。
- `2026-08-13 11:12` 实时 PID 窄日志（Spotify `21284`、SystemUI `16842`，Core `140096`、Provider `1.0.11 (12)`）：`11:12:33.050` Metadata Hook 首次命中并发布占位 Song，`11:12:34.270` 内部歌词请求开始，`11:12:34.294` 成功 `lines=47`，Provider 侧无 `RemotePlayerProxy` 异常。但 SystemUI 切歌前没有任何 `central_song_callback`；`11:12:34.656` `ActivePlayerCoordinator` 决策 `suppressed_active_audio_output, audioConflict=true, audioSuppressed=true, broadcast=false`，`ActiveAudioPlayback` 显示抖音音频 `STARTED`，冲突 `11:12:34.782` 才清除。因此“首曲从未发布 Song、失败在 metadata 进入 Provider 之前”的结论正式撤回：Provider 发布正常，丢失点在 Core 的 Central 音频冲突压制，且解除后没有补发。
- 代码与二进制证据：Provider SDK `0.1.70` 的 `CachedRemotePlayer` 只有一个 `lastLyricType` 槽位；`setSong()` 将其设为 `SONG`，`sendText()` 将其改为 `TEXT`，连接同步 `syncs()` 只回放最后一种内容，再回放 PlaybackState。Spotify Provider 又每 5 秒通过同一个 `player.sendText()` 发送下一首控制帧。因此在 Provider 尚未连接或 Central 重建的窗口中，即使首曲 Song 已缓存，后到的下一首控制帧也会把重连内容类型改成 `TEXT`，最终只恢复控制帧和进度，不恢复 Song；切歌后的新 `setSong()` 直接发送到已连接 Central，因而自愈。
- 已失败方向：Core `140090` 的 MediaSession metadata 快照回放、Provider `1.0.10` 的有效 metadata 防覆盖均已被真机结果否定。没有新证据不得继续增加 metadata 轮询、延迟读取、空值判定或扩大启动缓存。
- 已失败方向补充：Provider `1.0.11 (12)` 的独立 Song 重放无法修复本问题，因为 11:12 证据显示 Song 已被 Core 的音频冲突压制丢弃，与 SDK 单槽覆盖无关；不得继续以“SDK 单槽被控制帧覆盖”解释首曲无 Song。Core `140096` 的 `media_session_metadata_snapshot result=missing_retryable` 同样不是根因，因为 `setMetadata` Hook 在 `11:12:33.050` 已真实命中并发布占位。
- 当前未知：Core 候选“冲突解除时补发缓存 Song”构建安装后，冷启动首曲能否在抖音音频冲突结束的瞬间恢复超级岛与 AOD 歌词，且正常切歌、暂停恢复、下一首预览无回归。
- 下一个判别性证据：新 Core 真机验收时记录 `ActivePlayerCoordinator` 从 `suppressed_active_audio_output` 到 `accepted_active` 的决策序列，并确认过渡后出现 SystemUI 首个 `song_updated`/`central_song_callback`；缺该补发证据不得关闭。
- 验收条件：重启 Spotify 后第一次播放不需要切歌即可收到非空 Song 并持续显示递增进度；SystemUI/Central 重建后同一首歌也能恢复；暂停恢复、首次切歌、歌词与下一首预览均无回归。编译、打包、安装和 Hook 日志不能关闭本条。
- 验收结果：Core `140097` 真机日志满足关闭条件：`2026-08-13 11:41` `suppressed_active_audio_output`（`11:41:39.668`）→ 冲突清除（`11:41:39.915`）→ `central_song_callback` + `song_updated`（`11:41:39.939/943`）；用户确认重启 Spotify 后首播正常，超级岛与 AOD 均正常。本仓库不因本条再改。

## SPOTIFY-LYRICS-001: 等待歌词页面请求并从非必经 cla0 捕获客户端

- 状态：已解决（2026-08-13）。Provider `1.0.11 (12)` 捕获 DI 创建的 v2/v3 歌词客户端后已稳定取得多行歌词（`11:12` `lines=47`、`11:41` `lines=78`）；此后观察到的首曲空白位于 Core 的瞬时音频冲突压制，已由 Core `140097` 修复，用户真机确认正常。本仓库歌词取数方向不再修改。
- 症状与复现：Spotify `9.1.72.1891 (144716725)` 正常播放时，超级岛与大岛可能只有覆盖整曲的单行占位；切歌后曾偶发恢复多行官方歌词，但更新 Provider、重启 Spotify 和 SystemUI 后仍能复现。用户当前无法提供本轮日志。Spotify 自 2026-08-09 安装后没有更新，不能将回归归因于 Spotify 更新。
- 可靠运行与二进制证据：既有真机日志证明 `p.am80.b`/`p.lg80.b` 结果 Hook 安装后，冷启动当前曲可能一次也不调用，后续切歌却能返回多行 `p.s2e`；Provider `1.0.7` 从 `p.cla0(DataPool, kg80)` 构造参数捕获仓库并主动请求，真机仍未恢复，说明 `cla0` 不是稳定实例来源。Spotify 原始 `classes6.dex` 进一步确认 DI 直接构造 `p.am80(p.xl80,p.q2m,p.xhe)` 与 `p.lg80(p.g980,p.q2m,p.q2m,p.xhe)`；`am80` 封装 `color-lyrics/v3/track/{trackId}`，`lg80` 封装 v2，二者的 `b(String,String)` 会自行生成 vocal-removal、preview 和语言参数并把 protobuf 映射为 `p.s2e`。同一 DEX 确认 `p.hx3.b()` 是 `enable_v3_lyrics_endpoint`，`true` 选择 `am80`，`false` 选择 `lg80`。
- 已尝试方向：先后尝试通用缓存 Hook、`p.v581` 构造、只 Hook `lg80`、同时 Hook `am80/lg80` 被动结果、提前安装结果 Hook、启动缓存，以及从 `cla0` 捕获 `kg80` 后主动请求；构建和单测均不能改变真机冷启动/重启后仍缺歌词的事实。
- 不得重复的方向：不得继续扩大 `cla0`、追加 `kf80` 或调整同一请求延迟；不得裸拼 Spotify URL、读取 token、自建 Retrofit/OkHttp 或硬编码四参数接口的 preview/vocal-removal；不得让下一首队列 ID 参与歌词身份或触发；不得把 Spotify 未发生的更新作为解释。
- 当前未知：Spotify 进程中 `am80`/`lg80` 构造 Hook 是否会在真实 DI 创建时命中；观察到的 `hx3.b()` 选择是否与捕获客户端对齐；直接调用所选包装器后是否稳定返回当前 track 的多行 `p.s2e`。
- 下一个判别性证据：只替换歌词主动请求实例来源，记录两个构造 Hook 的安装和首次命中、`hx3.b()` 选择、当前 track 请求开始、Rx 成功/错误、解析行数与最终歌词发布；歌曲列表 Hook 保持不变。
- 验收条件：重启 Spotify/SystemUI 后的当前歌曲以及后续切歌都能收到与 MediaMetadata ID 对齐的多行官方歌词；旧请求在切歌时取消，迟到响应不能覆盖新歌；大岛和超级岛均实际显示并随进度推进。编译、安装、Hook 安装和请求日志都不能单独关闭本条。
- 验收结果：Provider `1.0.11 (12)` 在 `2026-08-13` 日志中记录 `Spotify color-lyrics 异步成功首次命中 lines=78`（11:41）与 `11:12` 的 `lines=47`，SystemUI 随后收到 78 行 `song_updated`；用户确认超级岛与 AOD 歌词显示正常。剩余首曲空白已确认在 Core 侧并修复，本条关闭。

## SALT-INLINE-TRANSLATION-001: 将同行双语内嵌歌词误当成重复时间戳格式

- 状态：已按椒盐 12.1.1 原始 DEX 重写并通过本地校验，等待真机验收。
- 症状与复现：椒盐 Provider 1.0.3 已改为读取本地音频文件，但 Taylor Swift《Paris》的中文翻译仍直接跟在英文主歌词末尾，而不是进入独立翻译字段。
- 可靠运行与文件证据：2026-08-07 只读 ADB 显示椒盐 `12.1.1 (2026070502)` 当前 MediaSession 为《Paris》，MediaStore 唯一匹配 `/storage/emulated/0/Download/netease/cloudmusic/Music/Taylor Swift - Paris.FLAC`，时长 `196259 ms`。该文件 `LYRICS` Vorbis Comment 有 65 个物理行、64 个唯一时间戳、0 个重复时间戳组；直接读取原始标签确认其中有 54 个 `U+2009 THIN SPACE`，正好是 54 条主歌词与翻译的边界。椒盐原始 APK 的 `androidx.obf.cx0` 构造器会把 `CRLF`/`LF`/`CR`/`U+2009` 都作为段分隔符；无时间戳的后续段复用上一个时间戳前缀，同时间戳首段写入 `LyricsLine.mainText`，后续段写入 `LyricsLine.translation`。
- 已尝试方向：1.0.3 只把相同时间戳下的第二条独立歌词行识别成翻译，真机结果证伪了“本地内嵌翻译已普遍分离”的结论。随后实验实现以“至少 3 行且占 60%”的整份歌词脚本方向来拆中英文，虽然单测、Release 编译和 Lint 通过，但这不是椒盐的规则，已在发布前撤回。
- 不得重复的方向：不得继续把重复时间戳当作椒盐内嵌双语的唯一格式；不得再按文字脚本、行数、占比、任意斜杠或 ASCII 空格猜测翻译边界。
- 当前未知：Provider 按 DEX 规则处理原始《Paris》文件后，岛上的主歌词、翻译和逐字单元是否与椒盐 App 实际显示一致。
- 下一个判别性证据：`U+2009` 翻译、ASCII 空格中英混排、Enhanced LRC 和多个后续段的单测已通过，Release Kotlin 编译和 Debug Lint 也已通过；下一步由用户用原始《Paris》文件真机验收。
- 验收条件：《Paris》主歌词只保留英文，中文进入 `translation` 并在独立翻译位置显示；最后一个逐字单元不包含翻译；普通单语或偶发中英文混排歌词不被拆分；下一首预览不回归。

## SALT-NEXT-TRACK-001: 后台队列轮询触发 MusicController 非主线程初始化

- 状态：修复中，等待真机验收
- 症状：官方 Pack 真正加载后，椒盐音乐启动即崩溃，表现为应用无法打开。
- 已确认运行证据：`140022` Debug 日志显示 Pack 1.0.1 已加载、生命周期 Hook 命中并注册 Provider；随后 `SaltPlayerNextTrackResolver.resolve()` 在 `HLE-SaltPlayer-NextTrack` 线程读取静态队列字段，触发 `MusicController.<clinit>`。椒盐内部因 `addObserver must be called on the main thread` 抛出 `ExceptionInInitializerError`，最终主线程以 `NoClassDefFoundError: MusicController` 崩溃。
- 已证伪方向：Pack 加载失败、作用域缺失、Provider 未注册和 Central 优先级不是本次启动崩溃断点；不得继续修改这些层来掩盖崩溃。
- 修复边界：`MusicController` 必须在 Application 回调的主线程完成初始化；队列 StateFlow 的反射读取与轮询也统一由主线程 Handler 调度，禁止恢复后台 `ScheduledExecutorService`。
- 验收条件：椒盐可稳定启动；日志不再出现 `addObserver must be called on the main thread`、`ExceptionInInitializerError` 或 `NoClassDefFoundError: MusicController`；下一首采集、完整歌词 Hook 和 Provider 注册仍能正常工作。

## SALT-NEXT-PREVIEW-002: 普通 LRC 末行伪逐字时间阻塞下一首预览

- 状态：修复中，等待真机验收。
- 症状与复现：椒盐 Provider 能持续更新下一首信息，但歌曲接近结尾时“下一首预览”不显示。设置为完整预览、预览时长 4 秒、强制结尾显示关闭时，播放 Taylor Swift《Paris》可稳定复现。
- 可靠运行证据：只读设备日志持续出现 `PlayerBinder: Next-track control: result=UPDATED`，下一首 ID 会变化，说明队列读取、Provider 控制帧和 Central 缓存均已成功。该曲媒体时长为 `196259 ms`，最后一条真实歌词开始时间为 `167340 ms`，预览窗口从 `192259 ms` 开始；真实歌词末行不与预览窗口重合。
- 代码证据与因果链：`SaltPlayerLrcParser` 原先在没有下一行时间戳时把末行结束时间设为 `durationMs`；`SaltPlayerLyricsMapper` 随后为普通 LRC 生成覆盖整段行时长的伪 `LyricWord`。核心 `NextSongPreviewPolicy.shouldShow()` 读取最后逐字单元结束时间，因此错误的 `196259 ms` 满足“仍在末行/逐字保护区”条件，直接禁止预览。椒盐 12.1.1 原始 DEX 的普通末行默认结束规则是 `lastBegin + 3000 ms`。
- 已证伪方向：不得继续修改下一首队列 Hook、Provider 优先级或 Central 缓存；这些环节已被 `result=UPDATED` 和变化的下一首 ID 证明正常。不得用强制结尾显示或全局 UI 特判掩盖末行时间错误。
- 当前未知：修复后的 Provider 在真机播放到普通 LRC 歌曲末尾时，预览是否按椒盐 App 规则出现；含逐字 LRC、末尾不足 3 秒的歌曲是否保持边界正确。
- 下一个判别性证据：单测验证末行默认只延续 3000ms，并在媒体剩余时间不足 3000ms 时截断到媒体时长；真机播放至预览窗口确认岛上出现下一首信息。
- 验收条件：普通 LRC 末行不再延长到整曲结束；预览窗口不再被伪逐字时间阻塞；下一首 ID/标题仍正常更新；逐字歌词和翻译拆分不回归。

## KUWO-STARTUP-001: 启动阶段急切执行 DexKit 解析导致酷我开屏卡死

- 状态：修复中，等待真机验收。
- 症状与复现：LSPosed 勾选酷我音乐作用域并启用酷我官方 Provider 插件后，酷我音乐启动卡在开屏首屏无法进入。保留作用域但禁用/移除插件后即可正常进入。
- 可靠运行与代码证据：早期 v1.0.1 使用纯 Java 反射（`KuwoNextTrackResolver.create`），静态反射失败时直接返回 null，冷启动开销 < 1ms，测试正常；后接入 `OfficialProviderHookHost.resolveDexMethods` 并在 `Application.onCreate` 时立即在主线程 post `startNextTrackCapture()`。非 12.1.8.2 版本静态反射失败后唤醒 DexKit 加载 `libdexkit.so` 并全量扫描 100MB+ APK 的全部 classes.dex，引发 CPU/IO 争用与 GC 阻塞并触碰酷我开屏自检；若特征未命中还会触发 `requestDexBatchRepair(forceFresh = true)` 二次全量扫描。
- 已尝试方向：保留作用域但移除插件已证明根因在插件启动阶段急切执行的 DexKit 检索。
- 不得重复的方向：不得在 `Application.onCreate` 或开屏阶段急切触发 DexKit 全量扫描；不得因一次失败彻底放弃自修复与重试能力。
- 修复边界：将下一首 Dex 检索与解析推迟到首次有效播放（`onMetadata`/`PlaybackState.STATE_PLAYING`）时懒加载；放宽查询包名前缀约束为 `cn.kuwo.`；保持播放时的正常重试与自修复通道。
- 验收条件：酷我音乐在启用官方插件且勾选作用域下能秒进 App 首页；播放音乐后正常获取歌词与逐字；下一首预览在播放后异步就绪。
