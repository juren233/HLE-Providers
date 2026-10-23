#!/usr/bin/env python3
"""Insert a freshly built release into the provider catalog.

Reads the provider manifest (providers/<id>/provider.json) for version fields
and compatibility constraints, upserts the release entry, then recomputes the
top-level fields to the newest release usable by the oldest still-supported
core build.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

from verify_catalog_assets import (
    DEFAULT_LEGACY_PLUGIN_API,
    DEFAULT_MIN_SUPPORTED_CORE,
    recompute_top_level,
    validate_provider_structure,
    write_catalog,
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", required=True, type=pathlib.Path)
    parser.add_argument("--provider", required=True)
    parser.add_argument("--provider-manifest", required=True, type=pathlib.Path)
    parser.add_argument("--asset-url", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--legacy-plugin-api", type=int, default=DEFAULT_LEGACY_PLUGIN_API)
    parser.add_argument("--min-supported-core", type=int, default=DEFAULT_MIN_SUPPORTED_CORE)
    parser.add_argument(
        "--allow-unavailable-top-level",
        action="store_true",
        help="新核心专属插件没有旧版可用的顶层版本时，保留 releases 并保持 available=false",
    )
    args = parser.parse_args()

    manifest = json.loads(args.provider_manifest.read_text(encoding="utf-8"))
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))

    provider = next(
        (item for item in catalog["providers"] if item["id"] == args.provider),
        None,
    )
    if provider is None:
        print(f"Provider not found in catalog: {args.provider}", file=sys.stderr)
        raise SystemExit(1)

    release = {
        "versionCode": int(manifest["versionCode"]),
        "versionName": manifest["versionName"],
        "pluginApiVersion": int(manifest["pluginApiVersion"]),
        "minCoreVersionCode": int(manifest.get("minCoreVersionCode", 0)),
        "assetUrl": args.asset_url,
        "sha256": args.sha256,
    }
    releases = [
        existing
        for existing in provider.get("releases", [])
        if existing.get("versionCode") != release["versionCode"]
    ]
    releases.append(release)
    releases.sort(key=lambda item: item["versionCode"], reverse=True)
    provider["releases"] = releases

    if not recompute_top_level(provider, args.legacy_plugin_api, args.min_supported_core):
        if not args.allow_unavailable_top_level:
            print(
                f"No release qualifies for top-level fields: {args.provider} "
                "(check pluginApiVersion / minCoreVersionCode)",
                file=sys.stderr,
            )
            raise SystemExit(1)
        # 新核心专属插件：没有旧版 App 能用的发行版，顶层保持不可用；
        # releases 仍会写入，新版 App 通过 releases 选择候选。
        print(
            f"Provider has no legacy-compatible release: {args.provider} "
            "(available=false, releases kept)",
            file=sys.stderr,
        )

    # targetPackages 描述的是顶层版本的目标集合。新版本因 minCore 提高而无法
    # 成为顶层、顶层仍指向旧版本时，不得用新版本的 targetPackages 覆盖目录，
    # 否则旧 App 会看到「旧包 + 新目标包名」的组合。
    if provider.get("versionCode") == release["versionCode"]:
        provider["targetPackages"] = manifest["targetPackages"]

    errors = validate_provider_structure(provider)
    if errors:
        for error in errors:
            print(f"catalog error: {error}", file=sys.stderr)
        raise SystemExit(1)

    write_catalog(args.catalog, catalog)
    top_level_name = provider.get("versionName")
    top_level = f"v{top_level_name}" if top_level_name else "unavailable"
    print(
        f"Catalog updated: {args.provider} v{release['versionName']} "
        f"({release['versionCode']}), top-level -> {top_level}"
    )


if __name__ == "__main__":
    main()
