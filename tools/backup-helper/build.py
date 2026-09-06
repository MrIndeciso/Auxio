#!/usr/bin/env python3
"""Build a release backup helper from the exact installed APK. No Gradle/network required."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

FACTORY = "org.oxycblt.auxio.backup.BackupComponentFactory"
EXPECTED_APK = "56d7d00c53b3ef7b94782f591b606e366bbad801051ca130e9a2d4d0d0acee1a"
EXPECTED_CERT = "0a0a80b95b50102b662d3854572f4d85891d0b3b12a5e308bdcb27bd4f164d61"


def u32(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def length(data, offset, utf8):
    unit = 1 if utf8 else 2
    value = int.from_bytes(data[offset:offset + unit], "little")
    high = 0x80 if utf8 else 0x8000
    if value & high:
        extra = int.from_bytes(data[offset + unit:offset + 2 * unit], "little")
        return ((value & (high - 1)) << (8 if utf8 else 16)) | extra, offset + 2 * unit
    return value, offset + unit


def encoded_length(n, utf8):
    if utf8:
        if n < 128:
            return bytes([n])
        if n > 32767:
            raise ValueError("String too long")
        return bytes([0x80 | (n >> 8), n & 255])
    if n < 32768:
        return struct.pack("<H", n)
    return struct.pack("<HH", 0x8000 | (n >> 16), n & 65535)


def patch_manifest(data):
    """Preserve every manifest attribute except factory and minimum SDK (helper needs API 30)."""
    if data[:4] != b"\x03\x00\x08\x00" or u32(data, 4) != len(data):
        raise ValueError("Unexpected binary manifest")
    chunks, strings = [], None
    pos, factory_attributes, sdk_attributes = 8, 0, 0
    while pos < len(data):
        kind, header, size = struct.unpack_from("<HHI", data, pos)
        chunk = bytearray(data[pos:pos + size])
        if size < header or len(chunk) != size:
            raise ValueError("Invalid chunk")
        if kind == 1:
            count, styles, flags, start, styles_start = struct.unpack_from("<IIIII", chunk, 8)
            if strings is not None or styles or styles_start or header != 28:
                raise ValueError("Unexpected string-pool layout")
            utf8 = bool(flags & 256)
            strings = []
            for i in range(count):
                offset = start + u32(chunk, header + 4 * i)
                n, offset = length(chunk, offset, utf8)
                if utf8:
                    n, offset = length(chunk, offset, True)
                strings.append(bytes(chunk[offset:offset + n * (1 if utf8 else 2)])
                               .decode("utf-8" if utf8 else "utf-16le"))
            if strings.count("androidx.core.app.CoreComponentFactory") != 1:
                raise ValueError("Original factory differs from the inspected installed release")
            strings[strings.index("androidx.core.app.CoreComponentFactory")] = FACTORY
            payload, offsets = bytearray(), []
            for string in strings:
                offsets.append(len(payload))
                raw16 = string.encode("utf-16le")
                payload += encoded_length(len(raw16) // 2, utf8)
                if utf8:
                    raw = string.encode("utf-8")
                    payload += encoded_length(len(raw), True) + raw + b"\0"
                else:
                    payload += raw16 + b"\0\0"
            payload += bytes((-len(payload)) % 4)
            start = 28 + count * 4
            chunk = bytearray(struct.pack("<HHIIIIII", 1, 28, start + len(payload),
                                         count, 0, flags & ~1, start, 0))
            chunk += struct.pack("<" + "I" * count, *offsets) + payload
        elif kind == 0x102:
            if strings is None:
                raise ValueError("Element before string pool")
            tag = strings[u32(chunk, 20)]
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", chunk, 24)
            if attr_size != 20:
                raise ValueError("Unexpected attribute layout")
            for i in range(attr_count):
                offset = 16 + attr_start + i * attr_size
                name = strings[u32(chunk, offset + 4)]
                if tag == "application" and name == "appComponentFactory":
                    if strings[u32(chunk, offset + 16)] != FACTORY:
                        raise ValueError("Factory string reference mismatch")
                    factory_attributes += 1
                if tag == "uses-sdk" and name == "minSdkVersion":
                    if chunk[offset + 15] != 0x10 or u32(chunk, offset + 16) != 24:
                        raise ValueError("Unexpected original minimum SDK")
                    struct.pack_into("<I", chunk, offset + 8, 0xffffffff)
                    struct.pack_into("<I", chunk, offset + 16, 30)
                    sdk_attributes += 1
        chunks.append(chunk)
        pos += size
    if pos != len(data) or factory_attributes != 1 or sdk_attributes != 1:
        raise ValueError("Required manifest attributes missing or duplicated")
    body = b"".join(chunks)
    return struct.pack("<HHI", 3, 8, 8 + len(body)) + body


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    digest = hashlib.file_digest(args.original.open("rb"), "sha256").hexdigest()
    if digest != EXPECTED_APK:
        parser.error("Original APK differs from the actual installed APK; inspect it before adapting this tool")
    base = Path(__file__).resolve().parent
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    classes, dex = out / "classes", out / "dex"
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    java = args.jdk / "bin/java"
    bt = args.sdk / "build-tools/36.1.0"
    platform = args.sdk / "platforms/android-36/android.jar"
    verify = subprocess.check_output([str(java), "-jar", str(bt / "lib/apksigner.jar"),
                                      "verify", "--print-certs", str(args.original)], text=True)
    if f"certificate SHA-256 digest: {EXPECTED_CERT}" not in verify:
        raise ValueError("Original signing identity mismatch")
    run(args.jdk / "bin/javac", "-source", "11", "-target", "11", "-Xlint:-options",
        "-classpath", platform, "-d", classes, *sorted((base / "src").rglob("*.java")))
    run(java, "-cp", bt / "lib/d8.jar", "com.android.tools.r8.D8", "--release", "--min-api", "30",
        "--lib", platform, "--output", dex, *sorted(classes.rglob("*.class")))
    unaligned = out / "backup-helper-unaligned.apk"
    with zipfile.ZipFile(args.original) as original, zipfile.ZipFile(unaligned, "w") as result:
        names = original.namelist()
        if len(set(names)) != len(names):
            raise ValueError("Duplicate APK members")
        dex_ids = [int(m.group(1) or 1) for name in names
                   if (m := re.fullmatch(r"classes(\d*)\.dex", name))]
        for info in original.infolist():
            if info.filename.upper().startswith("META-INF/") and (
                    info.filename.upper().endswith((".RSA", ".DSA", ".EC", ".SF")) or
                    info.filename.upper() == "META-INF/MANIFEST.MF"):
                continue
            content = original.read(info.filename)
            if info.filename == "AndroidManifest.xml":
                content = patch_manifest(content)
            result.writestr(info, content)
        result.writestr(f"classes{max(dex_ids) + 1}.dex", (dex / "classes.dex").read_bytes(),
                        compress_type=zipfile.ZIP_STORED)
    unsigned = out / "auxio-backup-helper-unsigned.apk"
    run(bt / "zipalign", "-f", "-P", "16", "4", unaligned, unsigned)
    run(bt / "zipalign", "-c", "-P", "16", "4", unsigned)
    with zipfile.ZipFile(args.original) as original, zipfile.ZipFile(unsigned) as result:
        for name in original.namelist():
            if name in result.namelist() and name != "AndroidManifest.xml":
                if original.read(name) != result.read(name):
                    raise ValueError("Unexpected original payload change: " + name)
    report = {"originalSha256": digest, "requiredCertificateSha256": EXPECTED_CERT,
              "factory": FACTORY, "minimumSdk": 30, "versionCode": 69,
              "unsignedApk": str(unsigned),
              "unsignedSha256": hashlib.file_digest(unsigned.open("rb"), "sha256").hexdigest(),
              "androidRuntimeTested": False, "restoreTested": False,
              "safeForRealInstallationVerified": False}
    (out / "build-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
