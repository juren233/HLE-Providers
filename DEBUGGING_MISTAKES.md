# Provider Debugging Mistakes

## SPOTIFY-LYRICS-001: 等待歌词页面请求并从非必经 cla0 捕获客户端

- 状态：Spotify Provider `1.0.8 (9)` 真机仍有歌词缺失，准备改为捕获 Spotify DI 实际创建的 v2/v3 歌词客户端；等待新候选真机验收。
- 症状与复现：Spotify `9.1.72.1891 (144716725)` 正常播放时，超级岛与大岛可能只有覆盖整曲的单行占位；切歌后曾偶发恢复多行官方歌词，但更新 Provider、重启 Spotify 和 SystemUI 后仍能复现。用户当前无法提供本轮日志。Spotify 自 2026-08-09 安装后没有更新，不能将回归归因于 Spotify 更新。
- 可靠运行与二进制证据：既有真机日志证明 `p.am80.b`/`p.lg80.b` 结果 Hook 安装后，冷启动当前曲可能一次也不调用，后续切歌却能返回多行 `p.s2e`；Provider `1.0.7` 从 `p.cla0(DataPool, kg80)` 构造参数捕获仓库并主动请求，真机仍未恢复，说明 `cla0` 不是稳定实例来源。Spotify 原始 `classes6.dex` 进一步确认 DI 直接构造 `p.am80(p.xl80,p.q2m,p.xhe)` 与 `p.lg80(p.g980,p.q2m,p.q2m,p.xhe)`；`am80` 封装 `color-lyrics/v3/track/{trackId}`，`lg80` 封装 v2，二者的 `b(String,String)` 会自行生成 vocal-removal、preview 和语言参数并把 protobuf 映射为 `p.s2e`。同一 DEX 确认 `p.hx3.b()` 是 `enable_v3_lyrics_endpoint`，`true` 选择 `am80`，`false` 选择 `lg80`。
- 已尝试方向：先后尝试通用缓存 Hook、`p.v581` 构造、只 Hook `lg80`、同时 Hook `am80/lg80` 被动结果、提前安装结果 Hook、启动缓存，以及从 `cla0` 捕获 `kg80` 后主动请求；构建和单测均不能改变真机冷启动/重启后仍缺歌词的事实。
- 不得重复的方向：不得继续扩大 `cla0`、追加 `kf80` 或调整同一请求延迟；不得裸拼 Spotify URL、读取 token、自建 Retrofit/OkHttp 或硬编码四参数接口的 preview/vocal-removal；不得让下一首队列 ID 参与歌词身份或触发；不得把 Spotify 未发生的更新作为解释。
- 当前未知：Spotify 进程中 `am80`/`lg80` 构造 Hook 是否会在真实 DI 创建时命中；观察到的 `hx3.b()` 选择是否与捕获客户端对齐；直接调用所选包装器后是否稳定返回当前 track 的多行 `p.s2e`。
- 下一个判别性证据：只替换歌词主动请求实例来源，记录两个构造 Hook 的安装和首次命中、`hx3.b()` 选择、当前 track 请求开始、Rx 成功/错误、解析行数与最终歌词发布；歌曲列表 Hook 保持不变。
- 验收条件：重启 Spotify/SystemUI 后的当前歌曲以及后续切歌都能收到与 MediaMetadata ID 对齐的多行官方歌词；旧请求在切歌时取消，迟到响应不能覆盖新歌；大岛和超级岛均实际显示并随进度推进。编译、安装、Hook 安装和请求日志都不能单独关闭本条。

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

## KUWO-WORD-TIMING-001: 将 LRCX 逐字缩放参数误写为全局常量

- 状态：修复中，等待真机验收。
- 症状与复现：Kuwo Provider 1.0.1 能发布逐字歌词，但高亮进度与酷我 App 内进度不一致。2026-08-07 真机的酷我 `12.1.8.2 (12182)` 在《Fortnight》（rid `363509566`）上可稳定复现。
- 可靠运行与二进制证据：该曲的 `newlyric.lrc` 响应头为 `[kuwo:013]`。原始 APK DEX 中的 `j6.f` 会将 `kuwo` 值以八进制解析，再用十位和个位分别控制起始时间与持续时间：`begin=(A+B)/(2*X)`、`end=begin+(A-B)/(2*Y)`。`013` 的八进制值为十进制 `11`，因此该曲必须使用 `X=1, Y=1`。
- 已尝试方向：1.0.1 把公式固定为 `begin=(A+B)/4`、`end=A/2`，等价于所有歌曲强制 `X=2, Y=2`；单一样本单测通过，但真机曲目已证明该假设错误。
- 不得重复的方向：不得再将任意单曲的倍率写成全局常量，也不得用整体 offset 遮盖行内逐字速率错误。除非有新的原始 DEX 或真机证据，不得恢复固定 `2/2` 公式。
- 当前未知：修复后的 Provider 在真机实时播放中是否与酷我可视高亮完全同步；其他合法的 `[kuwo:XY]` 参数组合是否还需要额外边界处理。
- 下一个判别性证据：安装包含新解析器的 Provider，分别播放至少一首 `[kuwo:013]` 和一首非 `013` 的逐字曲目，同时记录媒体位置、Provider 逐字边界与酷我 App 可视高亮。
- 验收条件：《Fortnight》中每个词的开始与结束边界与酷我 App 一致；另一个不同 `[kuwo:XY]` 的样本也同步；无逐字歌词仍保持行级滚动；下一首信息采集不回归。
