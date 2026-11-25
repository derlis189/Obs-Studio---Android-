#!/usr/bin/env python3
"""Apply the pinned OBS Android patch series and verify source-manifest.json."""
from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / "source-lock.json").read_text(encoding="utf-8"))
MANIFEST = json.loads((ROOT / "android/source-manifest.json").read_text(encoding="utf-8-sig"))
PATCHES = sorted((ROOT / "patches/obs-studio").glob("*.patch"))


def run(*args: str, cwd: pathlib.Path | None = None) -> None:
    subprocess.run(args, cwd=cwd, check=True)


with tempfile.TemporaryDirectory(prefix="obs-android-patches-") as temp:
    source = pathlib.Path(temp) / "obs-studio"
    run("git", "clone", "--quiet", "--no-checkout", LOCK["repository"], str(source))
    run("git", "-C", str(source), "checkout", "--quiet", LOCK["commit"])

    for patch in PATCHES:
        run("git", "-C", str(source), "apply", "--check", str(patch))
        run("git", "-C", str(source), "apply", str(patch))

    failures: list[str] = []
    for entry in MANIFEST["files"]:
        path = source / entry["path"]
        if not path.is_file():
            failures.append(f"missing: {entry['path']}")
            continue
        normalized = path.read_bytes().replace(b"\r\n", b"\n")
        actual = hashlib.sha256(normalized).hexdigest()
        if actual != entry["sha256Lf"]:
            failures.append(f"hash mismatch: {entry['path']} (expected {entry['sha256Lf']}, got {actual})")

    if failures:
        raise SystemExit("Patch manifest verification failed:\n" + "\n".join(failures))

print(f"Verified {len(PATCHES)} OBS patches and {len(MANIFEST['files'])} manifest entries.")
