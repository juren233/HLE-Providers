#!/usr/bin/env python3
"""One-time migration: seed each provider's `releases` from legacy top-level fields.

For every legacy provider (no `releases` yet), the current top-level version is
converted into its first release entry; metadata (pluginApiVersion /
minCoreVersionCode) comes from providers/<id>/provider.json, which must match
the top-level versionCode.

--history-tag can additionally seed historical releases from git release tags
(the tagged provider.json supplies pluginApiVersion / minCoreVersionCode;
assetUrl / sha256 come from the first catalog commit after the tag whose
top-level versionCode matches). This capability is currently unused: the
supported-core floor is 160102, which every current pack satisfies, and an
earlier plan to backfill qqmusic-v1.0.10 / kuwo-v1.0.11 for a 151009 floor was
dropped — a lower top-level would have handed stale packs to old-app fresh
installs. Keep the option for any future decision to support older cores.

The catalog is Ed25519-signed: after rewriting catalog.json you MUST regenerate
catalog.sig. Pass --sign-key <private.pem> to do it in one step, or run the
signing commands from release.yml manually. The signature must be committed
together with catalog.json.
"""

from __future__ import annotations

import argparse
import base64
import json
import pathlib
import subprocess
import sys

from verify_catalog_assets import (
    DEFAULT_LEGACY_PLUGIN_API,
    DEFAULT_MIN_SUPPORTED_CORE,
    provider_releases,
    recompute_top_level,
    validate_provider_structure,
    write_catalog,
)


def seed_release(provider_id: str, top_level: dict, manifest: dict) -> dict:
    version_code = int(manifest["versionCode"])
    if version_code != top_level.get("versionCode"):
        raise ValueError(
            f"{provider_id}: provider.json versionCode {version_code} 与目录顶层 "
            f"{top_level.get('versionCode')} 不一致，无法回填"
        )
    return {
        "versionCode": version_code,
        "versionName": manifest.get("versionName") or top_level["versionName"],
        "pluginApiVersion": int(manifest["pluginApiVersion"]),
        "minCoreVersionCode": int(manifest.get("minCoreVersionCode", 0)),
        "assetUrl": top_level["assetUrl"],
        "sha256": top_level["sha256"],
    }


def read_history_release(repo_root: pathlib.Path, tag: str) -> tuple[str, dict]:
    """从 git 发布标签读取历史发行版条目。

    配对规则：标签处的 providers/<id>/provider.json 携带该版本的
    pluginApiVersion / minCoreVersionCode；但发布流程是「先打 tag 触发构建，
    目录更新提交在标签之后」，标签处的 catalog.json 还是上一个版本——
    assetUrl / sha256 要沿标签之后的 catalog 提交历史找 versionCode 匹配的
    顶层条目。
    """

    def git(*args: str) -> bytes:
        return subprocess.run(
            ["git", "-C", str(repo_root), *args],
            check=True,
            capture_output=True,
        ).stdout

    provider_id, _, _ = tag.rpartition("-v")
    if not provider_id:
        raise ValueError(f"{tag}: 标签不是 <provider>-v<version> 形式")

    manifest = json.loads(git("show", f"{tag}:providers/{provider_id}/provider.json"))
    version_code = int(manifest["versionCode"])

    def provider_entry_from(ref: str) -> dict | None:
        catalog = json.loads(git("show", f"{ref}:catalog/catalog.json"))
        entry = next(
            (item for item in catalog["providers"] if item["id"] == provider_id),
            None,
        )
        if entry is None or not entry.get("available"):
            return None
        return entry if entry.get("versionCode") == version_code else None

    provider = provider_entry_from(tag)
    if provider is None:
        commits = git(
            "log", "--format=%H", "--reverse", f"{tag}..HEAD",
            "--", "catalog/catalog.json",
        ).decode().split()
        for commit in commits:
            provider = provider_entry_from(commit)
            if provider is not None:
                break
    if provider is None:
        raise ValueError(
            f"{tag}: 目录历史中找不到 {provider_id} versionCode={version_code} 的发布记录"
        )
    return provider_id, seed_release(provider_id, provider, manifest)


def apply_backfill(
    catalog: dict,
    manifests: dict[str, dict],
    history_entries: list[tuple[str, dict]],
) -> list[str]:
    errors: list[str] = []
    for provider in catalog.get("providers", []):
        if provider_releases(provider) or not provider.get("available", False):
            continue
        provider_id = provider["id"]
        manifest = manifests.get(provider_id)
        if manifest is None:
            errors.append(f"{provider_id}: 缺少 providers/{provider_id}/provider.json")
            continue
        try:
            provider["releases"] = [seed_release(provider_id, provider, manifest)]
        except (KeyError, ValueError) as error:
            errors.append(str(error))

    providers_by_id = {p["id"]: p for p in catalog.get("providers", [])}
    for provider_id, entry in history_entries:
        provider = providers_by_id.get(provider_id)
        if provider is None:
            errors.append(f"{provider_id}: 目录中不存在，无法追加历史发行版")
            continue
        releases = provider.setdefault("releases", [])
        if any(r.get("versionCode") == entry["versionCode"] for r in releases):
            continue
        releases.append(entry)
        releases.sort(key=lambda item: item["versionCode"], reverse=True)

    for provider in catalog.get("providers", []):
        if not provider_releases(provider):
            continue
        if not recompute_top_level(
            provider,
            DEFAULT_LEGACY_PLUGIN_API,
            DEFAULT_MIN_SUPPORTED_CORE,
        ):
            errors.append(
                f"{provider['id']}: 回填后仍无满足下限的候选，顶层将被清空（应补充"
                " --history-tag 或提高 --min-supported-core）"
            )
        errors.extend(validate_provider_structure(provider))
    return errors


def sign_catalog(catalog_path: pathlib.Path, private_key: pathlib.Path) -> None:
    signature = subprocess.run(
        [
            "openssl", "pkeyutl", "-sign", "-rawin",
            "-inkey", str(private_key),
            "-in", str(catalog_path),
        ],
        check=True,
        capture_output=True,
    ).stdout
    signature_b64 = base64.b64encode(signature).decode("ascii")
    sig_path = catalog_path.with_suffix(".sig")
    sig_path.write_text(signature_b64 + "\n", encoding="ascii")
    public_key = private_key.parent / "keys" / "provider-signing-public.pem"
    if public_key.is_file():
        # catalog.sig 是 base64 文本（App 端解码后验签）；openssl 需要原始字节。
        raw_sig = catalog_path.parent / "catalog.sig.raw"
        raw_sig.write_bytes(signature)
        try:
            subprocess.run(
                [
                    "openssl", "pkeyutl", "-verify", "-rawin", "-pubin",
                    "-inkey", str(public_key),
                    "-in", str(catalog_path),
                    "-sigfile", str(raw_sig),
                ],
                check=True,
                capture_output=True,
            )
        finally:
            raw_sig.unlink(missing_ok=True)
        print(f"catalog.sig 已重新生成并通过公钥验证: {sig_path}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", required=True, type=pathlib.Path)
    parser.add_argument("--repo-root", required=True, type=pathlib.Path)
    parser.add_argument(
        "--history-tag",
        action="append",
        default=[],
        help="从该 git 发布标签回填历史发行版（可重复）",
    )
    parser.add_argument("--sign-key", type=pathlib.Path, default=None)
    args = parser.parse_args()

    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    manifests = {
        path.parent.name: json.loads(path.read_text(encoding="utf-8"))
        for path in sorted((args.repo_root / "providers").glob("*/provider.json"))
    }
    history_entries: list[tuple[str, dict]] = []
    for tag in args.history_tag:
        try:
            history_entries.append(read_history_release(args.repo_root, tag))
        except (subprocess.CalledProcessError, ValueError) as error:
            print(f"history tag error: {tag}: {error}", file=sys.stderr)
            raise SystemExit(1) from error

    errors = apply_backfill(catalog, manifests, history_entries)
    if errors:
        for error in errors:
            print(f"backfill error: {error}", file=sys.stderr)
        raise SystemExit(1)

    write_catalog(args.catalog, catalog)
    migrated = [p["id"] for p in catalog["providers"] if provider_releases(p)]
    print("已迁移为 releases 格式: " + ", ".join(migrated))
    if args.sign_key:
        sign_catalog(args.catalog, args.sign_key)
    else:
        print("注意: catalog.json 已改写，必须重新生成并提交 catalog.sig", file=sys.stderr)


if __name__ == "__main__":
    main()
