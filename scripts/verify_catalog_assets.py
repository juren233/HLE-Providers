#!/usr/bin/env python3
"""Catalog repair and audit for HLE provider releases.

- Prunes release entries (and legacy top-level entries) whose declared GitHub
  Release asset is missing, then recomputes the top-level fields.
- Validates structural consistency of the ``releases`` format: the top-level
  fields must mirror one release entry exactly.
- With ``--verify-packs``: fully checks (sha256 + embedded manifest's
  pluginApiVersion / minCoreVersionCode) each provider's newest release, the
  top-level release, and any pack found in ``--pack-dir``; older history
  releases are covered by the asset-existence check only.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import pathlib
import re
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections.abc import Callable


CATALOG_ASSET_FIELDS = ("versionName", "versionCode", "assetUrl", "sha256")

# 旧主程序（≤230005）的 ProviderPackVerifier 只接受单个 classes.dex；
# 多 DEX 包必须声明 minCoreVersionCode ≥ 230006，否则旧 App 会直接报
# “Provider Pack 文件集合无效”。
MULTI_DEX_MIN_CORE_VERSION_CODE = 230006

# 顶层字段指向「仍在支持的最低主程序版本也能用的最新版」。旧版 App 对
# pluginApiVersion 做精确等值校验，因此顶层只指向 legacy API 的发行版。
# 下限 160102（7.6.0 系列）：所有 8.x 正式版核心都满足该下限；把顶层指到
# 更老的包会让 8.x 用户的新装/重装拿到陈旧插件（且 targetPackages 与旧包
# 不一致），7.5.x 及更老核心维持「提示更新主程序」的现状。
DEFAULT_LEGACY_PLUGIN_API = 3
DEFAULT_MIN_SUPPORTED_CORE = 160102


def parse_release_asset_url(url: str, repository: str) -> tuple[str, str] | None:
    parsed = urllib.parse.urlparse(url)
    expected_prefix = f"/{repository}/releases/download/"
    if parsed.scheme != "https" or parsed.netloc != "github.com" or not parsed.path.startswith(expected_prefix):
        return None
    remainder = parsed.path[len(expected_prefix):]
    tag, separator, asset_name = remainder.partition("/")
    if not separator or not tag or not asset_name:
        return None
    return urllib.parse.unquote(tag), urllib.parse.unquote(asset_name)


def fetch_release_assets(repository: str, tag: str, token: str | None) -> set[str] | None:
    encoded_tag = urllib.parse.quote(tag, safe="")
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/releases/tags/{encoded_tag}",
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "HLE-Providers-catalog-verifier",
            **({"Authorization": f"Bearer {token}"} if token else {}),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            release = json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise
    return {asset["name"] for asset in release.get("assets", [])}


def provider_releases(provider: dict) -> list[dict]:
    releases = provider.get("releases") or []
    return releases if isinstance(releases, list) else []


def eligible_releases(
    provider: dict,
    legacy_plugin_api: int = DEFAULT_LEGACY_PLUGIN_API,
    min_supported_core: int = DEFAULT_MIN_SUPPORTED_CORE,
) -> list[dict]:
    """顶层可选的发行版：legacy API、满足下限、且未被撤回。"""
    revoked = set(provider.get("revokedVersionCodes") or [])
    return [
        release
        for release in provider_releases(provider)
        if release.get("pluginApiVersion") == legacy_plugin_api
        and release.get("minCoreVersionCode", 0) <= min_supported_core
        and release.get("versionCode") not in revoked
    ]


def recompute_top_level(
    provider: dict,
    legacy_plugin_api: int = DEFAULT_LEGACY_PLUGIN_API,
    min_supported_core: int = DEFAULT_MIN_SUPPORTED_CORE,
) -> bool:
    """把顶层字段重算为「最低仍支持的主程序也能用的最新版」。

    被撤回（revokedVersionCodes）的版本不得成为顶层——旧 App 不认识撤回
    字段，会照常安装顶层指向的版本。返回是否找到了可用的发行版；找不到时
    清空顶层字段并置 available=False。
    """
    eligible = eligible_releases(provider, legacy_plugin_api, min_supported_core)
    if not eligible:
        provider["available"] = False
        for field in CATALOG_ASSET_FIELDS:
            provider.pop(field, None)
        return False
    newest = max(eligible, key=lambda release: release["versionCode"])
    provider.update(
        available=True,
        versionName=newest["versionName"],
        versionCode=newest["versionCode"],
        assetUrl=newest["assetUrl"],
        sha256=newest["sha256"],
    )
    return True


def disable_missing_assets(
    catalog: dict,
    repository: str,
    asset_loader: Callable[[str, str, str | None], set[str] | None] = fetch_release_assets,
    token: str | None = None,
    legacy_plugin_api: int = DEFAULT_LEGACY_PLUGIN_API,
    min_supported_core: int = DEFAULT_MIN_SUPPORTED_CORE,
) -> tuple[list[str], list[str]]:
    """返回 (pruned, disabled)：

    - pruned：个别发行版资产缺失被剔除，Provider 仍可用（顶层可能已重算）；
    - disabled：所有发行版/顶层资产缺失，Provider 整体下线。
    """
    pruned: list[str] = []
    disabled: list[str] = []
    release_cache: dict[str, set[str] | None] = {}

    def assets_for(tag: str) -> set[str] | None:
        if tag not in release_cache:
            release_cache[tag] = asset_loader(repository, tag, token)
        return release_cache[tag]

    def asset_exists(url: str) -> bool:
        parsed = parse_release_asset_url(url, repository)
        if parsed is None:
            return False
        tag, asset_name = parsed
        assets = assets_for(tag)
        return assets is not None and asset_name in assets

    for provider in catalog.get("providers", []):
        releases = provider_releases(provider)
        if not releases:
            # 旧格式：只看顶层字段。
            if not provider.get("available", False):
                continue
            if asset_exists(provider.get("assetUrl", "")):
                continue
            provider["available"] = False
            for field in CATALOG_ASSET_FIELDS:
                provider.pop(field, None)
            disabled.append(provider["id"])
            continue

        # releases 格式：逐个发行版检查资产，缺失的发行版直接剔除，然后重算顶层。
        kept = [release for release in releases if asset_exists(release.get("assetUrl", ""))]
        if len(kept) == len(releases):
            continue
        if kept:
            provider["releases"] = kept
            recompute_top_level(provider, legacy_plugin_api, min_supported_core)
            pruned.append(provider["id"])
        else:
            provider.pop("releases", None)
            provider["available"] = False
            for field in CATALOG_ASSET_FIELDS:
                provider.pop(field, None)
            disabled.append(provider["id"])
    return pruned, disabled


def refresh_top_levels(
    catalog: dict,
    legacy_plugin_api: int = DEFAULT_LEGACY_PLUGIN_API,
    min_supported_core: int = DEFAULT_MIN_SUPPORTED_CORE,
) -> list[str]:
    """把顶层纠正为「最新可用候选的精确投影」，覆盖一切手工编辑漂移。

    顶层不等于 max(可用候选)（被撤回、不满足下限、被改回旧版本、字段
    与候选条目不一致、甚至不在 releases 中）都一律重算；没有任何可用
    候选则返回错误，让 CI 失败并要求人工处理，而不是静默下线。
    """
    errors: list[str] = []
    for provider in catalog.get("providers", []):
        if not provider_releases(provider) or not provider.get("available", False):
            continue
        eligible = eligible_releases(provider, legacy_plugin_api, min_supported_core)
        if not eligible:
            errors.append(
                f"{provider['id']}: 没有满足下限且未撤回的发行版，顶层无候选，请人工处理"
            )
            continue
        expected = max(eligible, key=lambda release: release["versionCode"])
        top_matches = all(
            provider.get(field) == expected.get(field)
            for field in ("versionCode", "versionName", "assetUrl", "sha256")
        )
        if not top_matches:
            recompute_top_level(provider, legacy_plugin_api, min_supported_core)
    return errors


def validate_provider_structure(provider: dict) -> list[str]:
    """目录结构校验；返回错误列表（非空即让 CI 失败，而不是静默修复）。"""
    errors: list[str] = []
    provider_id = provider.get("id", "<unknown>")
    releases = provider_releases(provider)

    if provider.get("available", False) and not releases:
        # 旧格式由旧路径负责，这里只校验 releases 格式。
        return errors

    if not provider.get("available", False) and not releases:
        return errors

    if releases:
        version_codes = [release.get("versionCode") for release in releases]
        if len(version_codes) != len(set(version_codes)):
            errors.append(f"{provider_id}: releases 存在重复 versionCode")
        for release in releases:
            code = release.get("versionCode")
            if not isinstance(code, int) or code <= 0:
                errors.append(f"{provider_id}: release versionCode 无效: {code!r}")
            for field in ("versionName", "assetUrl", "sha256"):
                if not release.get(field):
                    errors.append(f"{provider_id}: release {code} 缺少 {field}")
            api = release.get("pluginApiVersion")
            if not isinstance(api, int) or api <= 0:
                errors.append(f"{provider_id}: release {code} pluginApiVersion 无效: {api!r}")
            core = release.get("minCoreVersionCode")
            if not isinstance(core, int) or core < 0:
                errors.append(f"{provider_id}: release {code} minCoreVersionCode 无效: {core!r}")

        revoked = provider.get("revokedVersionCodes") or []
        if len(revoked) != len(set(revoked)):
            errors.append(f"{provider_id}: revokedVersionCodes 存在重复")
        if any(not isinstance(code, int) or code <= 0 for code in revoked):
            errors.append(f"{provider_id}: revokedVersionCodes 含无效值")

        # 顶层字段必须与某个 release 完全一致（顶层只是它的投影）。
        if provider.get("available", False):
            top_code = provider.get("versionCode")
            matched = next(
                (r for r in releases if r.get("versionCode") == top_code),
                None,
            )
            if matched is None:
                errors.append(f"{provider_id}: 顶层 versionCode={top_code} 不在 releases 中")
            else:
                for field in ("versionName", "assetUrl", "sha256"):
                    if provider.get(field) != matched.get(field):
                        errors.append(
                            f"{provider_id}: 顶层 {field} 与 releases 中 versionCode={top_code} 的条目不一致"
                        )
    return errors


def read_pack_manifest_and_dex_names(pack_bytes: bytes) -> tuple[dict, list[str]]:
    with zipfile.ZipFile(io.BytesIO(pack_bytes)) as pack:
        manifest = json.loads(pack.read("manifest.json").decode("utf-8"))
        dex_names = [
            name for name in pack.namelist()
            if re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex", name)
        ]
    return manifest, dex_names


def verify_packs(
    catalog: dict,
    pack_dir: pathlib.Path | None = None,
    fetch_bytes: Callable[[str, str | None], bytes] | None = None,
    token: str | None = None,
) -> list[str]:
    """包内容审计，返回错误列表。

    - 旧格式条目（无 releases）：目录里本来就没有 pluginApiVersion /
      minCoreVersionCode 元数据，只校验 versionCode 与 sha256。
    - releases 格式：完整校验（sha256 + manifest 兼容字段）的集合是
      「每个 Provider 的最新发行版 + 顶层版本（旧 App 实际下载的正是它）
      + pack_dir 里能命中的本地新包」，按 versionCode 去重；其余历史版本
      在其发布时已完整校验过，且资产存在性始终由 disable_missing_assets
      覆盖，不再逐个全量下载，避免随 releases 增长线性放大发布耗时。
    """
    if fetch_bytes is None:
        def fetch_bytes(url: str, _token: str | None) -> bytes:
            request = urllib.request.Request(
                url,
                headers={"User-Agent": "HLE-Providers-catalog-verifier"},
            )
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()

    errors: list[str] = []
    for provider in catalog.get("providers", []):
        if not provider.get("available", False):
            continue
        releases = provider_releases(provider)
        if not releases:
            # 旧格式：只验 versionCode 与 sha256，没有元数据可比。
            verify_one(
                provider=provider,
                release={
                    "versionCode": provider["versionCode"],
                    "assetUrl": provider["assetUrl"],
                    "sha256": provider["sha256"],
                },
                pack_dir=pack_dir,
                fetch_bytes=fetch_bytes,
                token=token,
                errors=errors,
                check_manifest_fields=False,
                require_single_dex=True,
            )
            continue

        newest = max(releases, key=lambda release: release["versionCode"])
        top_level = next(
            (
                release
                for release in releases
                if release["versionCode"] == provider.get("versionCode")
            ),
            None,
        )
        selected: dict[int, dict] = {newest["versionCode"]: newest}
        if top_level is not None:
            selected[top_level["versionCode"]] = top_level
        if pack_dir is not None:
            for release in releases:
                asset = pack_dir / release["assetUrl"].rstrip("/").rsplit("/", 1)[-1]
                if asset.is_file():
                    selected[release["versionCode"]] = release
        for release in selected.values():
            verify_one(
                provider=provider,
                release=release,
                pack_dir=pack_dir,
                fetch_bytes=fetch_bytes,
                token=token,
                errors=errors,
                check_manifest_fields=True,
                require_single_dex=top_level is not None
                and release["versionCode"] == top_level["versionCode"],
            )
    return errors


def verify_one(
    provider: dict,
    release: dict,
    pack_dir: pathlib.Path | None,
    fetch_bytes: Callable[[str, str | None], bytes],
    token: str | None,
    errors: list[str],
    check_manifest_fields: bool,
    require_single_dex: bool = False,
) -> None:
    url = release["assetUrl"]
    asset_name = url.rstrip("/").rsplit("/", 1)[-1]
    try:
        if pack_dir is not None and (pack_dir / asset_name).is_file():
            pack_bytes = (pack_dir / asset_name).read_bytes()
        else:
            pack_bytes = fetch_bytes(url, token)
        actual = hashlib.sha256(pack_bytes).hexdigest()
        if actual != release["sha256"]:
            errors.append(
                f"{provider['id']} v{release['versionCode']}: sha256 不一致 "
                f"(期望 {release['sha256'][:12]}, 实际 {actual[:12]})"
            )
            return
        manifest, dex_names = read_pack_manifest_and_dex_names(pack_bytes)
    except Exception as error:  # noqa: BLE001 - CI 需要汇总所有错误而非裸 traceback
        errors.append(f"{provider['id']} v{release['versionCode']}: 包读取失败 {error}")
        return
    if len(dex_names) > 1:
        for label, min_core in (
            ("manifest", manifest.get("minCoreVersionCode")),
            ("目录 release", release.get("minCoreVersionCode")),
        ):
            if not isinstance(min_core, int) or min_core < MULTI_DEX_MIN_CORE_VERSION_CODE:
                errors.append(
                    f"{provider['id']} v{release['versionCode']}: 多 DEX 包的 {label} "
                    f"minCoreVersionCode={min_core!r} 必须 ≥ "
                    f"{MULTI_DEX_MIN_CORE_VERSION_CODE}；旧版 App 只接受单个 classes.dex"
                )
        if require_single_dex:
            # 顶层字段是旧版 App（不解析 releases）实际下载的版本。
            errors.append(
                f"{provider['id']} v{release['versionCode']}: 顶层字段指向多 DEX 包，"
                "旧版 App 只接受单个 classes.dex"
            )
    if manifest.get("versionCode") != release["versionCode"]:
        errors.append(
            f"{provider['id']} v{release['versionCode']}: manifest versionCode="
            f"{manifest.get('versionCode')!r} 与目录不一致"
        )
    if not check_manifest_fields:
        return
    for field, expected in (
        ("pluginApiVersion", release.get("pluginApiVersion")),
        ("minCoreVersionCode", release.get("minCoreVersionCode", 0)),
    ):
        if manifest.get(field) != expected:
            errors.append(
                f"{provider['id']} v{release['versionCode']}: manifest {field}="
                f"{manifest.get(field)!r} 与目录 {expected!r} 不一致"
            )


def write_catalog(path: pathlib.Path, catalog: dict) -> None:
    lines = ["{", f'  "schemaVersion": {catalog["schemaVersion"]},', '  "providers": [']
    for index, item in enumerate(catalog["providers"]):
        suffix = "," if index + 1 < len(catalog["providers"]) else ""
        lines.append(f"    {json.dumps(item, ensure_ascii=False, separators=(',', ':'))}{suffix}")
    lines.extend(["  ]", "}"])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", required=True, type=pathlib.Path)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--verify-packs", action="store_true")
    parser.add_argument("--pack-dir", type=pathlib.Path, default=None)
    parser.add_argument("--legacy-plugin-api", type=int, default=DEFAULT_LEGACY_PLUGIN_API)
    parser.add_argument("--min-supported-core", type=int, default=DEFAULT_MIN_SUPPORTED_CORE)
    args = parser.parse_args()

    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))

    # 先处理手工编辑造成的顶层失效（撤回/下限变化）：无候选时让 CI 失败，
    # 而不是被随后的资产剔除路径静默下线。
    refresh_errors = refresh_top_levels(
        catalog,
        legacy_plugin_api=args.legacy_plugin_api,
        min_supported_core=args.min_supported_core,
    )

    pruned, disabled = disable_missing_assets(
        catalog,
        args.repository,
        token=os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN"),
        legacy_plugin_api=args.legacy_plugin_api,
        min_supported_core=args.min_supported_core,
    )

    structure_errors = [validate_provider_structure(p) for p in catalog.get("providers", [])]
    structure_errors = [error for provider_errors in structure_errors for error in provider_errors]

    pack_errors: list[str] = []
    if args.verify_packs:
        pack_errors = verify_packs(
            catalog,
            pack_dir=args.pack_dir,
            token=os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN"),
        )

    if refresh_errors or structure_errors or pack_errors:
        for error in refresh_errors + structure_errors + pack_errors:
            print(f"catalog error: {error}", file=os.sys.stderr)
        raise SystemExit(1)

    write_catalog(args.catalog, catalog)
    if pruned:
        print("Pruned missing releases (provider still available): " + ", ".join(pruned))
    if disabled:
        print("Disabled missing Provider assets: " + ", ".join(disabled))
    if not pruned and not disabled:
        print("All available Provider assets exist in GitHub Releases")


if __name__ == "__main__":
    main()
