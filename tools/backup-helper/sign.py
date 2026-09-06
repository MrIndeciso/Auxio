#!/usr/bin/env python3
"""Sign with local terminal prompts or private files; require the installed certificate."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
from build import EXPECTED_CERT


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ["jdk", "sdk", "apk", "out", "keystore"]:
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--store-password-file", type=Path)
    p.add_argument("--key-password-file", type=Path)
    p.add_argument("--alias", required=True)
    a = p.parse_args()
    password_args = []
    if bool(a.store_password_file) != bool(a.key_password_file):
        p.error("Provide both password-file options, or omit both for private terminal prompts")
    for file in filter(None, [a.store_password_file, a.key_password_file]):
        if not file.is_file() or file.stat().st_mode & 0o077:
            p.error("Password files must exist and be readable only by their owner (chmod 600)")
    if a.store_password_file:
        password_args = ["--ks-pass", "file:" + str(a.store_password_file),
                         "--key-pass", "file:" + str(a.key_password_file)]
    elif not sys.stdin.isatty():
        p.error("Run this command in your own terminal for private password prompts")
    if a.out.exists():
        p.error("Output already exists; choose a new path")
    signer = [str(a.jdk / "bin/java"), "-jar", str(a.sdk / "build-tools/36.1.0/lib/apksigner.jar")]
    # Only publish the requested output after signing AND certificate verification succeed.
    # A wrong password leaves no partial output to obstruct a manual retry.
    with tempfile.TemporaryDirectory(prefix="signing-", dir=a.out.resolve().parent) as temp:
        candidate = Path(temp) / "candidate.apk"
        try:
            # Passwords go directly to apksigner via its terminal prompt or private files.
            subprocess.run(signer + ["sign", "--ks", str(a.keystore), "--ks-key-alias", a.alias]
                           + password_args + ["--v4-signing-enabled", "false", "--out",
                                              str(candidate), str(a.apk)], check=True)
            result = subprocess.check_output(signer + ["verify", "--verbose", "--print-certs",
                                                       str(candidate)], text=True)
        except subprocess.CalledProcessError:
            raise SystemExit("Signing did not complete. No final APK was created. You can rerun to retry.")
        if f"certificate SHA-256 digest: {EXPECTED_CERT}" not in result:
            raise SystemExit("Signing certificate mismatch. No final APK was created; do not install.")
        # Exclusive creation also protects against another signing process publishing meanwhile.
        with candidate.open("rb") as source, a.out.open("xb") as target:
            import shutil
            shutil.copyfileobj(source, target)
    print(result)
    with a.out.open("rb") as stream:
        print("SHA-256:", hashlib.file_digest(stream, "sha256").hexdigest())


if __name__ == "__main__":
    main()
