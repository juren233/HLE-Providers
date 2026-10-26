## 改动说明 / Description

<!-- 改了什么、为什么 / What changed and why -->

## 检查项 / Checklist

- [ ] 没有手改副本路径（`OfficialProviderPluginApi.kt`、`providers/applemusic/.../hyperlyricsenhanced/**`）；协议变更已提核心仓库的配对 PR，并填写下方 `Core-PR` / I did not hand-edit the copy paths (`OfficialProviderPluginApi.kt`, `providers/applemusic/.../hyperlyricsenhanced/**`); a protocol change has a paired PR in the core repository, linked in `Core-PR` below
- [ ] 新增或修改的 Apple Music 标识符只在 `AppleMusicHookProfiles.kt` 中声明，并从原始 DEX 核对过 / New or changed Apple Music identifiers are declared only in `AppleMusicHookProfiles.kt` and verified from the original DEX
- [ ] 诊断日志仅在 Debug 构建启用 / Diagnostic logging is enabled in debug builds only
- [ ] 没有修改版本号、`catalog/`、`keys/`、发布工作流 / I did not change version numbers, `catalog/`, `keys/` or the release workflow

## 验证 / Verification

<!-- 验证过的 Apple Music 版本、设备、运行结果 / Verified Apple Music version, device and result -->

Core-PR:
