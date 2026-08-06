# HLE Providers

HyperLyrics Enhanced 的官方 Lyricon Provider Pack 仓库。

本仓库保存 Provider 源码、目录、Pack 构建工具与发布工作流。Provider Pack 不需要作为独立 APK 安装；HyperLyrics Enhanced 下载并验签后，通过 libxposed Remote Files交给目标音乐软件进程加载。

## 安全边界

- Pack 只允许包含 `manifest.json`、`classes.dex` 和 `signature.ed25519`。
- HyperLyrics Enhanced 内置签名公钥与目标包名允许列表。
- 禁止原生库和插件二次下载代码。
- Pack API v2 不暴露 libxposed 类型；Pack 只通过稳定的宿主回调声明行为，所有 `module.hook()` 调用由主 APK 静态宿主执行，因此兼容启用 `PROP_RT_API_PROTECTION` 的 libxposed 运行时。
- 私钥由CI 通过 `HLE_PROVIDER_SIGNING_KEY_PEM` Secret 注入。

## 本地构建

```bash
provider=netease
./gradlew --no-daemon --max-workers=2 ":providers:$provider:assembleRelease"
python3 scripts/build_provider_pack.py \
  --apk "providers/$provider/build/outputs/apk/release/${provider}-release-unsigned.apk" \
  --manifest "providers/$provider/provider.json" \
  --private-key /path/to/signing-private.pem \
  --output "dist/$provider-1.0.0.hlp"
```

Pack ZIP 条目使用固定时间戳，重复构建会得到相同摘要；目录中的 `sha256` 必须与 Pack 文件一致。

## 来源

首批 Provider 逻辑移植自 Apache-2.0 许可的
`tomakino/LyricProvider`，修改后的文件保留原作者署名并追加 `juren233`，新文件将只署名为 `juren233`。
