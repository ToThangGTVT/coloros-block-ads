#!/usr/bin/env python3
"""Content fingerprint and JNI export verification for Android native packaging."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess


def source_files(root):
    fixed = ["nativecore/Cargo.toml", "nativecore/Cargo.lock", "rust-toolchain.toml",
             "scripts/build-native.sh", "scripts/native-stamp.py",
             "core/engine-bridge/src/main/java/com/utc/color/blockads/core/enginebridge/EngineBridge.kt"]
    return sorted([root / name for name in fixed] + list((root / "nativecore/src").rglob("*.rs")), key=lambda p: p.relative_to(root).as_posix())


def fingerprint(root):
    digest = hashlib.sha256()
    for path in source_files(root):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--seal", action="store_true")
    parser.add_argument("--expected")
    parser.add_argument("--ndk-bin", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    current = fingerprint(root)
    if not args.seal:
        print(current)
        return
    if current != args.expected:
        raise SystemExit("Rust/JNI sources changed during the native build; rebuild before packaging.")
    bridge = root / "core/engine-bridge/src/main/java/com/utc/color/blockads/core/enginebridge/EngineBridge.kt"
    methods = set(re.findall(r"external\s+fun\s+(native\w+)", bridge.read_text()))
    expected = {"Java_com_utc_color_blockads_core_enginebridge_EngineBridge_" + name for name in methods}
    directory = root / "core/engine-bridge/src/main/jniLibs"
    lines = ["sources=" + current]
    for abi in ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"]:
        binary = directory / abi / "libblockads_core.so"
        output = subprocess.check_output([str(args.ndk_bin / "llvm-nm"), "-D", str(binary)], text=True)
        exports = {line.split()[-1] for line in output.splitlines() if line.split()}
        missing = expected - exports
        if missing:
            raise SystemExit(f"Missing JNI exports for {abi}: {', '.join(sorted(missing))}")
        lines.append(abi + "=" + hashlib.sha256(binary.read_bytes()).hexdigest())
    temporary = directory / ".build.properties.tmp"
    temporary.write_text("\n".join(lines) + "\n")
    temporary.replace(directory / ".build.properties")
    print("Verified source fingerprint, four ABI binaries and every EngineBridge JNI export.")


if __name__ == "__main__":
    main()
