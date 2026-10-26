# 贡献指南 / Contributing Guide

中文在前，[English below](#contributing-guide)。

## 贡献指南

欢迎直接提 Pull Request。Apple Music 功能与适配提到本仓库的 `providers/applemusic`；协议变更需要两个仓库各提一个互相链接的 PR（见下）。

### 两个仓库怎么分工

- **HLE-Providers（本仓库）**：各 Provider 的 Hook 逻辑、Hook 档案、单测、校验脚本，以及 Pack 的构建与发布。
- **[HyperLyrics-Enhanced](https://github.com/juren233/HyperLyrics-Enhanced)（核心 App）**：App 与 Provider 之间的接口约定的**原件**（`contract/`、`common/lyric`、`common/bridge`、`lyric/model`、AIDL、`OfficialProviderPluginApi.kt`）。

Pack 单独编译，拿不到 App 的类，所以本仓库里带有这批文件的**副本**，必须与原件逐字节一致，由核心仓库的 `scripts/sync_shared_to_providers.py` 复制过来。

#### 只改 Provider 逻辑（绝大多数情况）

只在本仓库提 PR，不要改下面这些副本路径：

- `providers/*/src/main/kotlin/com/juren233/hyperlyricsenhanced/provider/OfficialProviderPluginApi.kt`
- `providers/applemusic/src/main/kotlin/com/juren233/hyperlyricsenhanced/**`
- `providers/applemusic/src/main/aidl/com/juren233/hyperlyricsenhanced/**`

这些副本必须与核心仓库的原件逐字节一致；手改过的副本会让 PR 检查失败。

#### 需要改协议（AIDL、契约、模型）

提一对 PR：

1. 在核心仓库提 PR，只改原件。
2. 在本仓库提 PR，包含同步后的副本和 Provider 侧改动，并在 PR 描述里写一行 `Core-PR: <核心仓库 PR 编号>`。两个 PR 描述里互相链接（两个 PR 不可能同时创建，先后补链接即可）。
3. 本仓库的检查会拉取该核心 PR，运行 `sync_shared_to_providers.py --check`，副本与原件一致才通过。

两边检查都通过后，等待维护者审核即可。

**自动提醒与联动**：PR 里缺了什么（没填 `Core-PR`、编号有误、对方还没补回链）会由机器人在 PR 里评论，补好后评论自动更新或消失。任意一边有新提交或描述被编辑，会自动重跑对方 PR 的配对检查，不需要手动触发。检查跑完后，结果（通过、失败或运行中，失败时附修复提示）也会更新到同一条评论里。

### Apple Music Hook 规则

- 所有用于安装或解析 Hook 的 Apple Music 运行时标识符（类名、方法名、需要精确匹配的字段名、参数与返回类型、静态/实例、synthetic/bridge 约束），只能声明在 `providers/applemusic/src/main/kotlin/com/juren233/hle/providers/applemusic/xposed/AppleMusicHookProfiles.kt`。
- 业务代码通过 `AppleMusicHookResolver` 取得版本相关的目标，不要把混淆名或版本相关标识符直接写进业务文件。
- 适配新版本时，从**原始 DEX** 核对标识符与完整方法/字段描述符，不要根据 Kotlin/JADX 源码形状、显示别名或返回对象的运行时类型推断。
- 每个档案目标组需要有 Hook 安装日志，诊断构建需要记录首次真实回调命中。
- 重构档案时保持原有已验证的运行时查找语义，除非有原始二进制证据并配有回归测试，否则不要收紧签名。

### 其他约定

- 复杂、高频或针对个别用户的诊断日志只能在 Debug 构建启用，用 `BuildConfig.DEBUG` 或等效的 Debug 源集隔离。
- Pack 只允许 `manifest.json`、`classes.dex`、`signature.ed25519`；禁止原生库和二次下载代码。
- 版本号、tag、`catalog/`、`keys/` 与发布由维护者操作，PR 里不要改。
- 修改前先看看 `scripts/` 下的校验脚本；`verify_apple_music_*.py` 需要原始 Apple Music APK，CI 里不会运行，请在本地验证后在 PR 里写明验证过的版本。

### 本地检查

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
./gradlew --no-daemon --max-workers=2 :providers:applemusic:testDebugUnitTest
```

---

## Contributing Guide

Pull requests are welcome. Apple Music features and adaptation go to `providers/applemusic` in this repository; a protocol change needs one PR in each of the two repositories, linked to each other (see below).

### How the two repositories divide the work

- **HLE-Providers (this repository)**: each provider's hook logic, hook profiles, unit tests, verification scripts, and the building and publishing of the packs.
- **[HyperLyrics-Enhanced](https://github.com/juren233/HyperLyrics-Enhanced) (the core app)**: the **sources** of the interface contract between the app and the providers (`contract/`, `common/lyric`, `common/bridge`, `lyric/model`, AIDL, `OfficialProviderPluginApi.kt`).

Packs are compiled separately and cannot see the app's classes, so this repository holds **copies** of those files that must be byte-identical to the sources; they are copied here by `scripts/sync_shared_to_providers.py` in the core repository.

#### Changing provider logic only (the vast majority of cases)

Open a PR in this repository only, and do not touch these copy paths:

- `providers/*/src/main/kotlin/com/juren233/hyperlyricsenhanced/provider/OfficialProviderPluginApi.kt`
- `providers/applemusic/src/main/kotlin/com/juren233/hyperlyricsenhanced/**`
- `providers/applemusic/src/main/aidl/com/juren233/hyperlyricsenhanced/**`

These copies must be byte-identical to the sources in the core repository; hand-edited copies make the PR check fail.

#### Changing the protocol (AIDL, contract, models)

Open a pair of PRs:

1. Open a PR in the core repository that changes the sources only.
2. Open a PR in this repository with the synced copies and the provider-side changes, and put a line `Core-PR: <core repository PR number>` in its description. Link the two PR descriptions to each other (the two PRs cannot be created at the same moment, so add the links one after the other).
3. The check in this repository fetches that core PR and runs `sync_shared_to_providers.py --check`; it passes only when the copies match the sources.

Once the checks on both sides pass, just wait for the maintainer's review.

**Automatic reminders and linked re-runs**: a bot comments on the PR about whatever is missing (no `Core-PR`, a wrong number, the other side has not linked back) and updates or removes the comment once it is fixed. New commits or a description edit on either side automatically re-run the paired PR's check, so there is no need to trigger anything by hand. When the checks finish, their results (passed, failed or running, with a fix hint on failure) are written into the same comment.

### Apple Music hook rules

- Every Apple Music runtime identifier used to install or resolve a hook (class names, method names, field names that must match exactly, parameter and return types, static/instance, synthetic/bridge constraints) may only be declared in `providers/applemusic/src/main/kotlin/com/juren233/hle/providers/applemusic/xposed/AppleMusicHookProfiles.kt`.
- Business code obtains version-sensitive targets through `AppleMusicHookResolver`; do not write obfuscated or version-specific identifiers directly into business files.
- When adapting to a new version, verify identifiers and full method/field descriptors from the **original DEX**; do not infer them from the shape of Kotlin/JADX source, displayed aliases, or the runtime type of a returned object.
- Every profile target group needs a hook installation log, and diagnostic builds must record the first real callback hit.
- When refactoring profiles, keep the previously verified runtime lookup semantics; do not tighten a signature unless there is evidence from the original binary and a regression test.

### Other conventions

- Complex, high-frequency or user-specific diagnostic logging may only be enabled in debug builds, isolated with `BuildConfig.DEBUG` or an equivalent debug source set.
- A pack may only contain `manifest.json`, `classes.dex` and `signature.ed25519`; native libraries and secondary code downloads are forbidden.
- Version numbers, tags, `catalog/`, `keys/` and publishing are handled by the maintainer; do not change them in a PR.
- Look at the verification scripts under `scripts/` before changing things; `verify_apple_music_*.py` needs the original Apple Music APK and is not run in CI, so verify locally and state the verified version in the PR.

### Local checks

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
./gradlew --no-daemon --max-workers=2 :providers:applemusic:testDebugUnitTest
```
