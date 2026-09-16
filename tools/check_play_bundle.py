#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import struct
import sys
import zipfile

MAX_BUNDLE_BYTES = 200 * 1024 * 1024
MIN_16K_ALIGNMENT = 16 * 1024
MIN_16K_AGP = (8, 5, 1)
PLAY_64_BIT_ABIS = {"arm64-v8a", "x86_64"}
PT_LOAD = 1
PN_XNUM = 0xFFFF


def parse_version(value: str) -> tuple[int, int, int]:
    numbers = [int(part) for part in re.findall(r"\d+", value)[:3]]
    return tuple((numbers + [0, 0, 0])[:3])  # type: ignore[return-value]


def read_agp_version(root: Path) -> str:
    catalog = root / "gradle/libs.versions.toml"
    try:
        text = catalog.read_text(encoding="utf-8")
    except OSError as exc:
        raise ValueError(f"cannot read {catalog}: {exc}") from exc
    match = re.search(r'^\s*agp\s*=\s*"([^"]+)"\s*$', text, flags=re.MULTILINE)
    if not match:
        raise ValueError("AGP version missing from gradle/libs.versions.toml")
    return match.group(1)


def elf_load_alignments(blob: bytes) -> tuple[int, list[int]]:
    if len(blob) < 52 or blob[:4] != b"\x7fELF":
        raise ValueError("not a valid ELF file")

    elf_class = blob[4]
    data_encoding = blob[5]
    if data_encoding == 1:
        endian = "<"
    elif data_encoding == 2:
        endian = ">"
    else:
        raise ValueError(f"unsupported ELF data encoding {data_encoding}")

    if elf_class == 1:  # ELF32
        phoff = struct.unpack_from(f"{endian}I", blob, 28)[0]
        phentsize = struct.unpack_from(f"{endian}H", blob, 42)[0]
        phnum = struct.unpack_from(f"{endian}H", blob, 44)[0]
        minimum_phentsize = 32
        align_offset = 28
        align_format = "I"
        bits = 32
    elif elf_class == 2:  # ELF64
        if len(blob) < 64:
            raise ValueError("truncated ELF64 header")
        phoff = struct.unpack_from(f"{endian}Q", blob, 32)[0]
        phentsize = struct.unpack_from(f"{endian}H", blob, 54)[0]
        phnum = struct.unpack_from(f"{endian}H", blob, 56)[0]
        minimum_phentsize = 56
        align_offset = 48
        align_format = "Q"
        bits = 64
    else:
        raise ValueError(f"unsupported ELF class {elf_class}")

    if phnum == PN_XNUM:
        raise ValueError("extended ELF program-header count is unsupported")
    if phentsize < minimum_phentsize:
        raise ValueError(f"invalid ELF program-header size {phentsize}")

    alignments: list[int] = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if offset < 0 or offset + phentsize > len(blob):
            raise ValueError("truncated ELF program-header table")
        segment_type = struct.unpack_from(f"{endian}I", blob, offset)[0]
        if segment_type != PT_LOAD:
            continue
        alignment = struct.unpack_from(f"{endian}{align_format}", blob, offset + align_offset)[0]
        alignments.append(alignment)

    if not alignments:
        raise ValueError("ELF file has no PT_LOAD segments")
    return bits, alignments


def native_abi(name: str) -> str | None:
    parts = name.split("/")
    if len(parts) == 4 and parts[0] == "base" and parts[1] == "lib" and parts[3].endswith(".so"):
        return parts[2]
    return None


def main() -> int:
    root = Path.cwd()
    bundles = sorted((root / "app/build/outputs/bundle/play").glob("*.aab"))
    if len(bundles) != 1:
        print(f"PLAY_BUNDLE=FAIL expected exactly one AAB, found {len(bundles)}", file=sys.stderr)
        return 1
    bundle = bundles[0]
    if bundle.stat().st_size > MAX_BUNDLE_BYTES:
        print(f"PLAY_BUNDLE=FAIL size={bundle.stat().st_size} exceeds 200 MiB preflight ceiling", file=sys.stderr)
        return 1

    try:
        agp_version = read_agp_version(root)
    except ValueError as exc:
        print(f"PLAY_BUNDLE=FAIL {exc}", file=sys.stderr)
        return 2
    if parse_version(agp_version) < MIN_16K_AGP:
        print(
            f"PLAY_BUNDLE=FAIL AGP {agp_version} is below 8.5.1; 16 KiB zip alignment is not guaranteed",
            file=sys.stderr,
        )
        return 1

    checked_64_bit = 0
    native_count = 0
    try:
        with zipfile.ZipFile(bundle) as archive:
            names = set(archive.namelist())
            if "base/manifest/AndroidManifest.xml" not in names:
                print("PLAY_BUNDLE=FAIL base manifest missing", file=sys.stderr)
                return 1
            native = sorted(name for name in names if native_abi(name) is not None)
            native_count = len(native)
            for name in native:
                abi = native_abi(name)
                if abi not in PLAY_64_BIT_ABIS:
                    continue
                try:
                    bits, alignments = elf_load_alignments(archive.read(name))
                except (KeyError, OSError, ValueError, struct.error) as exc:
                    print(f"PLAY_BUNDLE=FAIL native validation failed for {name}: {exc}", file=sys.stderr)
                    return 1
                if bits != 64:
                    print(f"PLAY_BUNDLE=FAIL {name} is {bits}-bit in 64-bit ABI directory", file=sys.stderr)
                    return 1
                minimum = min(alignments)
                if minimum < MIN_16K_ALIGNMENT:
                    print(
                        f"PLAY_BUNDLE=FAIL {name} has PT_LOAD alignment {minimum} (< {MIN_16K_ALIGNMENT})",
                        file=sys.stderr,
                    )
                    return 1
                checked_64_bit += 1
    except (OSError, zipfile.BadZipFile) as exc:
        print(f"PLAY_BUNDLE=FAIL {exc}", file=sys.stderr)
        return 2

    if native_count and checked_64_bit == 0:
        print("PLAY_BUNDLE=FAIL native libraries present but no 64-bit ABI libraries were validated", file=sys.stderr)
        return 1

    print(
        f"PLAY_BUNDLE=PASS path={bundle} size={bundle.stat().st_size} "
        f"native_libs={native_count} native_64bit_16k={checked_64_bit} agp={agp_version}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
