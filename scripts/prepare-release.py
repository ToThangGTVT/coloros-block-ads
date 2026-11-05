#!/usr/bin/env python3
"""Package ARMv7/ARM64 APKs and metadata consumed by the Android updater."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
from urllib.parse import quote


def main():
    root = Path(__file__).resolve().parent.parent
    source = root / "app/build/outputs/apk/release"
    metadata = json.loads((source / "output-metadata.json").read_text())
    if metadata["applicationId"] != "com.utc.color.blockads":
        raise SystemExit("Unexpected Android application ID")
    elements = metadata["elements"]
    if len(elements) != 2:
        raise SystemExit("Expected separate ARMv7 and ARM64 APKs; build with -Pblockads.splitApks=true")
    element = elements[0]
    version = element["versionName"]
    code = element["versionCode"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version) or not isinstance(code, int) or code <= 0:
        raise SystemExit("Invalid release version")
    tag = "v" + version
    if os.environ.get("GITHUB_REF_TYPE") == "tag" and os.environ.get("GITHUB_REF_NAME") != tag:
        raise SystemExit("Git tag must match versionName in app/build.gradle.kts")
    repository = os.environ.get("GITHUB_REPOSITORY", "ToThangGTVT/open-adblock")
    if repository != "ToThangGTVT/open-adblock":
        raise SystemExit("Configure the updater repository before publishing a fork")
    output = root / "dist"
    output.mkdir(exist_ok=True)
    for old in output.iterdir():
        if old.is_file():
            old.unlink()
    variants = []
    checksums = []
    seen = set()
    for element in elements:
        filters = element.get("filters", [])
        if len(filters) != 1 or filters[0].get("filterType") != "ABI":
            raise SystemExit("Expected exactly one ABI per APK")
        abi = filters[0]["value"]
        if abi not in {"armeabi-v7a", "arm64-v8a"} or abi in seen:
            raise SystemExit("Only one ARMv7 and one ARM64 APK may be published")
        if element["versionName"] != version or element["versionCode"] != code:
            raise SystemExit("Both APKs must have the same version")
        seen.add(abi)
        filename = element["outputFile"]
        if filename != f"app-{abi}-release.apk":
            raise SystemExit("Expected signed split APKs; configure release signing first")
        name = f"BlockAds-{version}-{abi}.apk"
        apk = output / name
        shutil.copyfile(source / filename, apk)
        import zipfile
        with zipfile.ZipFile(apk) as archive:
            packaged = {path.split('/')[1] for path in archive.namelist() if path.startswith('lib/') and path.endswith('.so')}
            if packaged != {abi}:
                raise SystemExit(f"Wrong native ABIs in {name}: {packaged}")
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        variants.append({"abi": abi,
            "apkUrl": f"https://github.com/{repository}/releases/download/{quote(tag, safe='')}/{quote(name, safe='')}",
            "sha256": digest, "size": apk.stat().st_size})
        checksums.append(f"{digest}  {name}")
    descriptor = {"versionCode": code, "versionName": version, "apks": variants}
    (output / "update.json").write_text(json.dumps(descriptor, indent=2) + "\n")
    (output / "SHA256SUMS.txt").write_text("\n".join(checksums) + "\n")
    print(f"Prepared ARMv7 and ARM64 APKs for {tag}, SHA256SUMS.txt and update.json")


if __name__ == "__main__":
    main()
