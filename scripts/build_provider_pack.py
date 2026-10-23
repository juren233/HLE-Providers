#!/usr/bin/env python3
"""Build and sign an HLE Provider Pack from an Android APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile
import zipfile


def dex_index(name: str) -> int | None:
    if name == "classes.dex":
        return 1
    match = re.fullmatch(r"classes([2-9][0-9]*)\.dex", name)
    return int(match.group(1)) if match else None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=pathlib.Path)
    parser.add_argument("--manifest", required=True, type=pathlib.Path)
    parser.add_argument("--private-key", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    with zipfile.ZipFile(args.apk) as apk:
        dex_names = sorted(
            (name for name in apk.namelist() if dex_index(name) is not None),
            key=dex_index,
        )
        if not dex_names or [dex_index(name) for name in dex_names] != list(
            range(1, len(dex_names) + 1)
        ):
            raise SystemExit(
                "Provider Pack requires classes.dex, classes2.dex... without gaps: "
                f"{dex_names}"
            )
        dex_files = {name: apk.read(name) for name in dex_names}
        forbidden_symbols = (
            b"io/github/libxposed/api/XposedModule",
            b"io/github/libxposed/api/XposedInterface",
        )
        if any(
            symbol in data
            for data in dex_files.values()
            for symbol in forbidden_symbols
        ):
            raise SystemExit(
                "Provider Pack plugin code must not reference libxposed API; "
                "use the static host callback API instead"
            )

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    manifest["classesSha256"] = hashlib.sha256(dex_files["classes.dex"]).hexdigest()
    manifest["dexSha256"] = {
        name: hashlib.sha256(data).hexdigest()
        for name, data in dex_files.items()
    }
    manifest_bytes = json.dumps(
        manifest,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")

    with tempfile.TemporaryDirectory() as temp_dir:
        temp = pathlib.Path(temp_dir)
        payload = temp / "payload.bin"
        signature = temp / "signature.ed25519"
        payload.write_bytes(manifest_bytes + b"\0" + b"\0".join(dex_files.values()))
        subprocess.run(
            [
                "openssl", "pkeyutl", "-sign", "-rawin",
                "-inkey", str(args.private_key),
                "-in", str(payload),
                "-out", str(signature),
            ],
            check=True,
        )

        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED) as pack:
            def write_entry(name: str, data: bytes) -> None:
                entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = 0
                pack.writestr(entry, data)

            write_entry("manifest.json", manifest_bytes)
            for name, data in dex_files.items():
                write_entry(name, data)
            write_entry("signature.ed25519", signature.read_bytes())

    print(args.output)


if __name__ == "__main__":
    main()
