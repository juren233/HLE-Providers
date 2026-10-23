#!/usr/bin/env python3

import hashlib
import io
import json
import pathlib
import unittest
import zipfile
from unittest import mock

from backfill_catalog_releases import apply_backfill, seed_release
from update_catalog_release import main as update_catalog_release_main
from verify_catalog_assets import (
    disable_missing_assets,
    recompute_top_level,
    refresh_top_levels,
    validate_provider_structure,
    verify_packs,
)


def make_release(version_code: int, **overrides) -> dict:
    release = {
        "versionCode": version_code,
        "versionName": f"1.0.{version_code}",
        "pluginApiVersion": 3,
        "minCoreVersionCode": 0,
        "assetUrl": (
            "https://github.com/juren233/HLE-Providers/releases/download/"
            f"netease-v1.0.{version_code}/netease-1.0.{version_code}.hlp"
        ),
        "sha256": f"{version_code:064d}",
    }
    release.update(overrides)
    return release


def make_pack(release: dict, dex_names: tuple[str, ...] = ("classes.dex",)) -> bytes:
    manifest = {
        "schemaVersion": 1,
        "pluginApiVersion": release.get("pluginApiVersion", 3),
        "pluginId": "netease",
        "versionCode": release["versionCode"],
        "versionName": release["versionName"],
        "minCoreVersionCode": release.get("minCoreVersionCode", 0),
    }
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as pack:
        pack.writestr("manifest.json", json.dumps(manifest))
        for name in dex_names:
            pack.writestr(name, b"dex")
    return buffer.getvalue()


class VerifyCatalogAssetsTest(unittest.TestCase):
    def test_keeps_available_entry_when_release_asset_exists(self) -> None:
        catalog = {
            "providers": [{
                "id": "qqmusic",
                "available": True,
                "versionName": "1.0.1",
                "versionCode": 2,
                "assetUrl": "https://github.com/juren233/HLE-Providers/releases/download/qqmusic-v1.0.1/qqmusic-1.0.1.hlp",
                "sha256": "abc",
            }],
        }

        pruned, disabled = disable_missing_assets(
            catalog,
            "juren233/HLE-Providers",
            asset_loader=lambda _repository, _tag, _token: {"qqmusic-1.0.1.hlp"},
        )

        self.assertEqual([], pruned)
        self.assertEqual([], disabled)
        self.assertTrue(catalog["providers"][0]["available"])

    def test_disables_entry_and_removes_download_metadata_when_release_is_missing(self) -> None:
        catalog = {
            "providers": [{
                "id": "kuwo",
                "available": True,
                "versionName": "1.0.0",
                "versionCode": 1,
                "assetUrl": "https://github.com/juren233/HLE-Providers/releases/download/kuwo-v1.0.0/kuwo-1.0.0.hlp",
                "sha256": "abc",
            }],
        }

        pruned, disabled = disable_missing_assets(
            catalog,
            "juren233/HLE-Providers",
            asset_loader=lambda _repository, _tag, _token: None,
        )

        self.assertEqual([], pruned)
        self.assertEqual(["kuwo"], disabled)
        self.assertEqual({"id": "kuwo", "available": False}, catalog["providers"][0])

    def test_does_not_query_entries_already_marked_unavailable(self) -> None:
        catalog = {"providers": [{"id": "kugou", "available": False}]}

        pruned, disabled = disable_missing_assets(
            catalog,
            "juren233/HLE-Providers",
            asset_loader=lambda *_args: self.fail("loader must not be called"),
        )

        self.assertEqual([], pruned)
        self.assertEqual([], disabled)

    def test_pruned_release_keeps_provider_available_and_reports_pruned(self) -> None:
        catalog = {
            "providers": [{
                "id": "netease",
                "displayName": "网易云音乐",
                "targetPackages": ["com.netease.cloudmusic"],
                "available": True,
                "versionName": "1.0.30",
                "versionCode": 30,
                "assetUrl": make_release(30)["assetUrl"],
                "sha256": make_release(30)["sha256"],
                "releases": [make_release(30), make_release(29)],
                "revokedVersionCodes": [],
            }],
        }

        pruned, disabled = disable_missing_assets(
            catalog,
            "juren233/HLE-Providers",
            asset_loader=lambda _repository, tag, _token: (
                {"netease-1.0.29.hlp"} if tag == "netease-v1.0.29" else None
            ),
        )

        self.assertEqual(["netease"], pruned)
        self.assertEqual([], disabled)
        provider = catalog["providers"][0]
        self.assertEqual(29, provider["versionCode"])
        self.assertEqual([29], [r["versionCode"] for r in provider["releases"]])
        self.assertTrue(provider["available"])


class ReleasesFormatTest(unittest.TestCase):
    def test_disables_provider_when_all_releases_missing(self) -> None:
        catalog = {
            "providers": [{
                "id": "netease",
                "available": True,
                "releases": [make_release(30), make_release(29)],
                "revokedVersionCodes": [],
            }],
        }

        pruned, disabled = disable_missing_assets(
            catalog,
            "juren233/HLE-Providers",
            asset_loader=lambda _repository, _tag, _token: None,
        )

        self.assertEqual([], pruned)
        self.assertEqual(["netease"], disabled)
        provider = catalog["providers"][0]
        self.assertFalse(provider["available"])
        self.assertNotIn("releases", provider)
        self.assertNotIn("assetUrl", provider)

    def test_top_level_points_to_newest_release_usable_by_oldest_core(self) -> None:
        provider = {
            "available": True,
            "releases": [
                make_release(30, minCoreVersionCode=999999),
                make_release(29, pluginApiVersion=9),
                make_release(28),
            ],
        }

        found = recompute_top_level(provider)

        self.assertTrue(found)
        self.assertEqual(28, provider["versionCode"])

    def test_top_level_skips_revoked_releases(self) -> None:
        provider = {
            "available": True,
            "releases": [make_release(30), make_release(29)],
            "revokedVersionCodes": [30],
        }

        found = recompute_top_level(provider)

        self.assertTrue(found)
        self.assertEqual(29, provider["versionCode"])

    def test_recompute_top_level_clears_fields_when_nothing_qualifies(self) -> None:
        provider = {
            "available": True,
            "versionName": "1.0.30",
            "versionCode": 30,
            "assetUrl": make_release(30)["assetUrl"],
            "sha256": make_release(30)["sha256"],
            "releases": [make_release(30, minCoreVersionCode=999999)],
        }

        found = recompute_top_level(provider)

        self.assertFalse(found)
        self.assertFalse(provider["available"])
        self.assertNotIn("versionCode", provider)


class RefreshTopLevelsTest(unittest.TestCase):
    def make_provider(self) -> dict:
        top = make_release(29)
        return {
            "id": "netease",
            "displayName": "网易云音乐",
            "targetPackages": ["com.netease.cloudmusic"],
            "available": True,
            "versionName": top["versionName"],
            "versionCode": 29,
            "assetUrl": top["assetUrl"],
            "sha256": top["sha256"],
            "releases": [top, make_release(28)],
            "revokedVersionCodes": [],
        }

    def test_noop_when_top_level_is_healthy(self) -> None:
        catalog = {"providers": [self.make_provider()]}

        errors = refresh_top_levels(catalog)

        self.assertEqual([], errors)
        self.assertEqual(29, catalog["providers"][0]["versionCode"])

    def test_recomputes_when_top_level_is_revoked(self) -> None:
        # 手工把顶层版本加进撤回列表后，不经过发布也应自动换到下一个候选。
        provider = self.make_provider()
        provider["revokedVersionCodes"] = [29]
        catalog = {"providers": [provider]}

        errors = refresh_top_levels(catalog)

        self.assertEqual([], errors)
        self.assertEqual(28, provider["versionCode"])

    def test_errors_when_no_candidate_remains(self) -> None:
        provider = self.make_provider()
        provider["revokedVersionCodes"] = [29, 28]
        catalog = {"providers": [provider]}

        errors = refresh_top_levels(catalog)

        self.assertEqual(1, len(errors))
        self.assertIn("netease", errors[0])

    def test_corrects_top_level_manually_set_to_older_eligible_release(self) -> None:
        # 手工把顶层改回旧版本（旧版本本身合法）也必须纠正回最新可用候选。
        provider = self.make_provider()
        older = provider["releases"][1]
        provider.update(
            versionName=older["versionName"],
            versionCode=older["versionCode"],
            assetUrl=older["assetUrl"],
            sha256=older["sha256"],
        )
        catalog = {"providers": [provider]}

        errors = refresh_top_levels(catalog)

        self.assertEqual([], errors)
        self.assertEqual(29, provider["versionCode"])

    def test_corrects_top_level_field_drift(self) -> None:
        # 顶层 versionCode 正确但字段被改过：重算为候选条目的精确投影。
        provider = self.make_provider()
        provider["sha256"] = "0" * 64
        catalog = {"providers": [provider]}

        errors = refresh_top_levels(catalog)
        expected = provider["releases"][0]

        self.assertEqual([], errors)
        self.assertEqual(expected["sha256"], provider["sha256"])

    def test_structure_validation_accepts_consistent_top_level(self) -> None:
        provider = {
            "id": "netease",
            "available": True,
            **{k: v for k, v in make_release(29).items()
               if k in ("versionName", "versionCode", "assetUrl", "sha256")},
            "releases": [make_release(30, minCoreVersionCode=999999), make_release(29)],
            "revokedVersionCodes": [27],
        }

        self.assertEqual([], validate_provider_structure(provider))

    def test_structure_validation_flags_inconsistent_top_level(self) -> None:
        release = make_release(29)
        provider = {
            "id": "netease",
            "available": True,
            "versionName": "1.0.29",
            "versionCode": 29,
            "assetUrl": release["assetUrl"],
            "sha256": "f" * 64,
            "releases": [release],
        }

        errors = validate_provider_structure(provider)

        self.assertTrue(any("sha256" in error for error in errors))

    def test_structure_validation_flags_duplicate_release_codes(self) -> None:
        provider = {
            "id": "netease",
            "available": True,
            "releases": [make_release(29), make_release(29)],
        }

        errors = validate_provider_structure(provider)

        self.assertTrue(any("重复" in error for error in errors))


class VerifyPacksTest(unittest.TestCase):
    def test_verifies_local_pack_digest_and_manifest_fields(self) -> None:
        release = make_release(29)
        pack = make_pack(release)
        release["sha256"] = hashlib.sha256(pack).hexdigest()
        catalog = {"providers": [{
            "id": "netease",
            "available": True,
            "releases": [release],
        }]}
        with __import__("tempfile").TemporaryDirectory() as tmp:
            pack_dir = pathlib.Path(tmp)
            (pack_dir / "netease-1.0.29.hlp").write_bytes(pack)

            errors = verify_packs(catalog, pack_dir=pack_dir)

        self.assertEqual([], errors)

    def test_legacy_entry_checks_digest_and_version_only(self) -> None:
        # 旧格式目录没有 pluginApiVersion / minCoreVersionCode 可比，
        # 只能校验 versionCode 与 sha256（阻塞问题 1 的回归测试）。
        release = {"versionCode": 29, "assetUrl": make_release(29)["assetUrl"]}
        pack = make_pack(make_release(29, minCoreVersionCode=140139))
        release["sha256"] = hashlib.sha256(pack).hexdigest()
        catalog = {"providers": [{
            "id": "netease",
            "available": True,
            "versionCode": 29,
            "versionName": "1.0.29",
            "assetUrl": release["assetUrl"],
            "sha256": release["sha256"],
        }]}

        errors = verify_packs(catalog, fetch_bytes=lambda _url, _token: pack)

        self.assertEqual([], errors)

    def test_unavailable_provider_with_releases_still_audits_newest_pack(self) -> None:
        # 新核心专属插件没有旧版可用的顶层版本，但仍要校验最新发行版的包。
        release = make_release(1, minCoreVersionCode=230029)
        pack = make_pack(release)
        release["sha256"] = hashlib.sha256(pack).hexdigest()
        catalog = {"providers": [{
            "id": "applemusic",
            "available": False,
            "releases": [release],
        }]}
        fetched: list[str] = []

        def fetch(url: str, _token) -> bytes:
            fetched.append(url)
            return pack

        errors = verify_packs(catalog, fetch_bytes=fetch)

        self.assertEqual([], errors)
        self.assertEqual([release["assetUrl"]], fetched)

    def test_unavailable_provider_with_releases_fails_on_bad_pack(self) -> None:
        release = make_release(1, minCoreVersionCode=230029)
        release["sha256"] = "0" * 64
        catalog = {"providers": [{
            "id": "applemusic",
            "available": False,
            "releases": [release],
        }]}

        errors = verify_packs(
            catalog,
            fetch_bytes=lambda _url, _token: b"not a zip",
        )

        self.assertTrue(errors)

    def test_only_newest_remote_release_is_downloaded(self) -> None:
        newest = make_release(30)
        newest_pack = make_pack(newest)
        newest["sha256"] = hashlib.sha256(newest_pack).hexdigest()
        catalog = {"providers": [{
            "id": "netease",
            "available": True,
            "releases": [newest, make_release(29)],
        }]}
        fetched: list[str] = []

        def fetch(url: str, _token) -> bytes:
            fetched.append(url)
            return newest_pack

        errors = verify_packs(catalog, fetch_bytes=fetch)

        self.assertEqual([], errors)
        self.assertEqual(1, len(fetched))
        self.assertTrue(fetched[0].endswith("netease-1.0.30.hlp"))

    def test_top_level_release_is_also_verified_when_distinct_from_newest(self) -> None:
        # 顶层 ≠ 最新版时（旧 App 实际下载的正是顶层），两者都要完整校验。
        newest = make_release(30, minCoreVersionCode=999999)
        newest_pack = make_pack(newest)
        newest["sha256"] = hashlib.sha256(newest_pack).hexdigest()
        top = make_release(11)
        top_pack = make_pack(top)
        top["sha256"] = hashlib.sha256(top_pack).hexdigest()
        catalog = {"providers": [{
            "id": "netease",
            "available": True,
            "versionName": top["versionName"],
            "versionCode": 11,
            "assetUrl": top["assetUrl"],
            "sha256": top["sha256"],
            "releases": [newest, top],
        }]}
        fetched: dict[str, bytes] = {}
        packs = {
            newest["assetUrl"]: newest_pack,
            top["assetUrl"]: top_pack,
        }

        def fetch(url: str, _token) -> bytes:
            fetched[url] = packs[url]
            return packs[url]

        errors = verify_packs(catalog, fetch_bytes=fetch)

        self.assertEqual([], errors)
        self.assertEqual(set(packs), set(fetched))

    def test_corrupt_zip_is_reported_not_raised(self) -> None:
        release = make_release(29)
        release["sha256"] = hashlib.sha256(b"not a zip").hexdigest()
        catalog = {"providers": [{
            "id": "netease",
            "available": True,
            "releases": [release],
        }]}

        errors = verify_packs(catalog, fetch_bytes=lambda _url, _token: b"not a zip")

        self.assertTrue(any("包读取失败" in error for error in errors))

    def test_flags_digest_mismatch(self) -> None:
        release = make_release(29)
        catalog = {"providers": [{
            "id": "netease",
            "available": True,
            "releases": [release],
        }]}

        errors = verify_packs(catalog, fetch_bytes=lambda _url, _token: make_pack(release))

        self.assertTrue(any("sha256" in error for error in errors))

    def test_flags_manifest_core_version_mismatch(self) -> None:
        release = make_release(29, minCoreVersionCode=200000)
        pack = make_pack(make_release(29, minCoreVersionCode=0))
        release["sha256"] = hashlib.sha256(pack).hexdigest()
        catalog = {"providers": [{
            "id": "netease",
            "available": True,
            "releases": [release],
        }]}

        errors = verify_packs(catalog, fetch_bytes=lambda _url, _token: pack)

        self.assertTrue(any("minCoreVersionCode" in error for error in errors))

    def test_multi_dex_pack_requires_core_with_multi_dex_support(self) -> None:
        release = make_release(29, minCoreVersionCode=160102)
        pack = make_pack(release, dex_names=("classes.dex", "classes2.dex"))
        release["sha256"] = hashlib.sha256(pack).hexdigest()
        catalog = {"providers": [{
            "id": "netease",
            "available": True,
            "releases": [release],
        }]}

        errors = verify_packs(catalog, fetch_bytes=lambda _url, _token: pack)

        self.assertTrue(
            any("多 DEX" in error and "230006" in error for error in errors),
        )

    def test_multi_dex_pack_with_multi_dex_core_version_passes(self) -> None:
        release = make_release(29, minCoreVersionCode=230006)
        pack = make_pack(release, dex_names=("classes.dex", "classes2.dex"))
        release["sha256"] = hashlib.sha256(pack).hexdigest()
        catalog = {"providers": [{
            "id": "netease",
            "available": True,
            "releases": [release],
        }]}

        errors = verify_packs(catalog, fetch_bytes=lambda _url, _token: pack)

        self.assertEqual([], errors)

    def test_top_level_field_must_not_point_to_multi_dex_pack(self) -> None:
        # 旧版 App 不解析 releases，顶层字段是多 DEX 包就算 minCore 再高也装不上。
        release = make_release(29, minCoreVersionCode=230006)
        pack = make_pack(release, dex_names=("classes.dex", "classes2.dex"))
        release["sha256"] = hashlib.sha256(pack).hexdigest()
        catalog = {"providers": [{
            "id": "netease",
            "available": True,
            "versionName": release["versionName"],
            "versionCode": 29,
            "assetUrl": release["assetUrl"],
            "sha256": release["sha256"],
            "releases": [release],
        }]}

        errors = verify_packs(catalog, fetch_bytes=lambda _url, _token: pack)

        self.assertTrue(any("顶层字段指向多 DEX 包" in error for error in errors))

    def test_legacy_top_level_entry_rejects_multi_dex_pack(self) -> None:
        # 旧格式目录没有 release 元数据，顶层字段不能指向多 DEX 包。
        release = {"versionCode": 29, "assetUrl": make_release(29)["assetUrl"]}
        pack = make_pack(
            make_release(29, minCoreVersionCode=230006),
            dex_names=("classes.dex", "classes2.dex"),
        )
        release["sha256"] = hashlib.sha256(pack).hexdigest()
        catalog = {"providers": [{
            "id": "netease",
            "available": True,
            "versionCode": 29,
            "versionName": "1.0.29",
            "assetUrl": release["assetUrl"],
            "sha256": release["sha256"],
        }]}

        errors = verify_packs(catalog, fetch_bytes=lambda _url, _token: pack)

        self.assertTrue(any("多 DEX" in error for error in errors))


class UpdateCatalogReleaseTest(unittest.TestCase):
    def test_upserts_release_and_recomputes_top_level(self) -> None:
        catalog = {
            "schemaVersion": 1,
            "providers": [{
                "id": "netease",
                "displayName": "网易云音乐",
                "targetPackages": ["com.netease.cloudmusic"],
                "available": True,
                "versionName": "1.0.28",
                "versionCode": 28,
                "assetUrl": make_release(28)["assetUrl"],
                "sha256": make_release(28)["sha256"],
                "releases": [make_release(28)],
                "revokedVersionCodes": [],
            }],
        }
        manifest = {
            "versionCode": 29,
            "versionName": "1.0.29",
            "pluginApiVersion": 3,
            "minCoreVersionCode": 0,
            "targetPackages": ["com.netease.cloudmusic", "com.hihonor.cloudmusic"],
        }
        with __import__("tempfile").TemporaryDirectory() as tmp:
            catalog_path = pathlib.Path(tmp) / "catalog.json"
            manifest_path = pathlib.Path(tmp) / "provider.json"
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            argv = [
                "update_catalog_release.py",
                "--catalog", str(catalog_path),
                "--provider", "netease",
                "--provider-manifest", str(manifest_path),
                "--asset-url", make_release(29)["assetUrl"],
                "--sha256", make_release(29)["sha256"],
            ]
            with mock.patch("sys.argv", argv):
                update_catalog_release_main()

            updated = json.loads(catalog_path.read_text(encoding="utf-8"))
        provider = updated["providers"][0]
        self.assertEqual([29, 28], [r["versionCode"] for r in provider["releases"]])
        self.assertEqual(29, provider["versionCode"])
        self.assertEqual(
            ["com.netease.cloudmusic", "com.hihonor.cloudmusic"],
            provider["targetPackages"],
        )

    def test_target_packages_not_overwritten_when_top_level_stays_old(self) -> None:
        # 新版本 minCore 超过下限、顶层仍指向旧版本时，目录 targetPackages
        # 必须保留旧版本的集合（次要问题 6 的回归测试）。
        old_packages = ["com.tencent.qqmusic"]
        new_packages = ["com.tencent.qqmusic", "com.miui.player"]
        catalog = {
            "schemaVersion": 1,
            "providers": [{
                "id": "qqmusic",
                "displayName": "QQ音乐",
                "targetPackages": old_packages,
                "available": True,
                "versionName": "1.0.11",
                "versionCode": 11,
                "assetUrl": make_release(11)["assetUrl"],
                "sha256": make_release(11)["sha256"],
                "releases": [make_release(11)],
                "revokedVersionCodes": [],
            }],
        }
        manifest = {
            "versionCode": 31,
            "versionName": "1.0.31",
            "pluginApiVersion": 3,
            "minCoreVersionCode": 999999,
            "targetPackages": new_packages,
        }
        with __import__("tempfile").TemporaryDirectory() as tmp:
            catalog_path = pathlib.Path(tmp) / "catalog.json"
            manifest_path = pathlib.Path(tmp) / "provider.json"
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            argv = [
                "update_catalog_release.py",
                "--catalog", str(catalog_path),
                "--provider", "qqmusic",
                "--provider-manifest", str(manifest_path),
                "--asset-url", make_release(31)["assetUrl"],
                "--sha256", make_release(31)["sha256"],
            ]
            with mock.patch("sys.argv", argv):
                update_catalog_release_main()

            updated = json.loads(catalog_path.read_text(encoding="utf-8"))
        provider = updated["providers"][0]
        self.assertEqual(11, provider["versionCode"])
        self.assertEqual(old_packages, provider["targetPackages"])
        self.assertEqual([31, 11], [r["versionCode"] for r in provider["releases"]])

    def _run_new_core_only_publish(self, allow_unavailable: bool) -> dict:
        catalog = {
            "schemaVersion": 1,
            "providers": [{
                "id": "applemusic",
                "displayName": "Apple Music",
                "targetPackages": ["com.apple.android.music"],
                "available": False,
            }],
        }
        manifest = {
            "versionCode": 1,
            "versionName": "1.0.0",
            "pluginApiVersion": 3,
            "minCoreVersionCode": 230029,
            "targetPackages": ["com.apple.android.music"],
        }
        with __import__("tempfile").TemporaryDirectory() as tmp:
            catalog_path = pathlib.Path(tmp) / "catalog.json"
            manifest_path = pathlib.Path(tmp) / "provider.json"
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            argv = [
                "update_catalog_release.py",
                "--catalog", str(catalog_path),
                "--provider", "applemusic",
                "--provider-manifest", str(manifest_path),
                "--asset-url", make_release(1)["assetUrl"],
                "--sha256", make_release(1)["sha256"],
            ]
            if allow_unavailable:
                argv.append("--allow-unavailable-top-level")
            with mock.patch("sys.argv", argv):
                update_catalog_release_main()

            return json.loads(catalog_path.read_text(encoding="utf-8"))

    def test_new_core_only_provider_stays_unavailable_when_allowed(self) -> None:
        updated = self._run_new_core_only_publish(allow_unavailable=True)

        provider = updated["providers"][0]
        self.assertFalse(provider["available"])
        self.assertNotIn("versionCode", provider)
        self.assertEqual([1], [r["versionCode"] for r in provider["releases"]])
        self.assertEqual(["com.apple.android.music"], provider["targetPackages"])

    def test_new_core_only_provider_without_flag_fails(self) -> None:
        with self.assertRaises(SystemExit):
            self._run_new_core_only_publish(allow_unavailable=False)


class BackfillCatalogReleasesTest(unittest.TestCase):
    def test_seed_release_reads_metadata_from_manifest(self) -> None:
        entry = seed_release(
            "netease",
            {"versionCode": 29, "versionName": "1.0.29", "assetUrl": "u", "sha256": "s"},
            {"versionCode": 29, "versionName": "1.0.29",
             "pluginApiVersion": 3, "minCoreVersionCode": 140139},
        )

        self.assertEqual(
            {
                "versionCode": 29,
                "versionName": "1.0.29",
                "pluginApiVersion": 3,
                "minCoreVersionCode": 140139,
                "assetUrl": "u",
                "sha256": "s",
            },
            entry,
        )

    def test_seed_release_rejects_version_mismatch(self) -> None:
        with self.assertRaises(ValueError):
            seed_release(
                "netease",
                {"versionCode": 29, "versionName": "1.0.29", "assetUrl": "u", "sha256": "s"},
                {"versionCode": 30, "versionName": "1.0.30",
                 "pluginApiVersion": 3, "minCoreVersionCode": 0},
            )

    def test_backfill_seeds_all_legacy_providers(self) -> None:
        catalog = {"providers": [{
            "id": "netease",
            "displayName": "网易云音乐",
            "targetPackages": ["com.netease.cloudmusic"],
            "available": True,
            "versionName": "1.0.29",
            "versionCode": 29,
            "assetUrl": "u",
            "sha256": "s",
        }]}
        manifests = {"netease": {
            "versionCode": 29, "versionName": "1.0.29",
            "pluginApiVersion": 3, "minCoreVersionCode": 140139,
        }}

        errors = apply_backfill(catalog, manifests, history_entries=[])

        self.assertEqual([], errors)
        provider = catalog["providers"][0]
        self.assertEqual(1, len(provider["releases"]))
        self.assertEqual(140139, provider["releases"][0]["minCoreVersionCode"])
        self.assertEqual(29, provider["versionCode"])

    def test_backfill_uses_history_entry_when_current_exceeds_floor(self) -> None:
        # 当前版 minCore 超下限的场景：回填旧版后顶层指向旧版。
        catalog = {"providers": [{
            "id": "qqmusic",
            "displayName": "QQ音乐",
            "targetPackages": ["com.tencent.qqmusic"],
            "available": True,
            "versionName": "1.0.30",
            "versionCode": 30,
            "assetUrl": make_release(30)["assetUrl"],
            "sha256": make_release(30)["sha256"],
        }]}
        manifests = {"qqmusic": {
            "versionCode": 30, "versionName": "1.0.30",
            "pluginApiVersion": 3, "minCoreVersionCode": 999999,
        }}
        history = make_release(11, minCoreVersionCode=140078)

        errors = apply_backfill(
            catalog,
            manifests,
            history_entries=[("qqmusic", history)],
        )

        self.assertEqual([], errors)
        provider = catalog["providers"][0]
        self.assertEqual([30, 11], [r["versionCode"] for r in provider["releases"]])
        self.assertEqual(11, provider["versionCode"])
        self.assertEqual("1.0.11", provider["versionName"])

    def test_backfill_errors_when_no_release_meets_floor(self) -> None:
        catalog = {"providers": [{
            "id": "qqmusic",
            "displayName": "QQ音乐",
            "targetPackages": ["com.tencent.qqmusic"],
            "available": True,
            "versionName": "1.0.30",
            "versionCode": 30,
            "assetUrl": make_release(30)["assetUrl"],
            "sha256": make_release(30)["sha256"],
        }]}
        manifests = {"qqmusic": {
            "versionCode": 30, "versionName": "1.0.30",
            "pluginApiVersion": 3, "minCoreVersionCode": 999999,
        }}

        errors = apply_backfill(catalog, manifests, history_entries=[])

        self.assertTrue(any("下限" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
