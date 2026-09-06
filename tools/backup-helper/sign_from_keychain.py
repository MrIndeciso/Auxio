#!/usr/bin/env python3
"""Use only this keystore's two Android Studio native-keychain entries to sign the helper.

Entry names and serialization were checked against KeystoreStep, CredentialAttributesKt,
and CredentialStoreKt in the user's installed Android Studio. No keychain enumeration,
password guessing, vault-file access, or secret output is performed.
"""
from pathlib import Path
import os
import argparse
import subprocess
import sys
import tempfile

KEYSTORE = "/home/mrindeciso/Android/keystore.jks"
ALIAS = "key0"
PREFIX = "IntelliJ Platform APK Signing Keystore Step — "


def password_from_entry(data):
    """IntelliJ escapes the username before '@'; everything after it is the raw password."""
    index = 0
    while index < len(data):
        if data[index] == "\\":
            index += 2
        elif data[index] == "@":
            password = data[index + 1:]
            if not password or "\n" in password or "\r" in password or "\x00" in password:
                raise ValueError("Unsupported saved credential; use Android Studio's normal signing UI")
            return password.encode("utf-8")
        else:
            index += 1
    raise ValueError("Saved entry has no password; use Android Studio's normal signing UI")


def lookup(key):
    result = subprocess.run(["secret-tool", "lookup", "service", PREFIX + key],
                            capture_output=True, timeout=45)
    if result.returncode:
        if result.stderr:
            # Never forward credential-tool output, even on failure.
            raise SystemExit("Native keychain access failed. Unlock it through your desktop; no passwords were displayed.")
        raise SystemExit("The exact Android Studio signing entry was not found. No other entries were searched.")
    raw = result.stdout
    if raw.endswith(b"\n"):
        raw = raw[:-1]  # secret-tool's output terminator, not whitespace in the password
    try:
        return password_from_entry(raw.decode("utf-8"))
    except (ValueError, UnicodeError):
        raise SystemExit("The saved entry could not be used safely. No passwords were displayed.")


def main():
    base = Path(__file__).resolve().parent
    repo = base.parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=repo / "release/backup-helper/auxio-backup-helper-unsigned.apk")
    parser.add_argument("--out", type=Path, default=repo / "release/backup-helper/auxio-backup-helper-signed.apk")
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error("Input APK does not exist")
    output = args.out
    if output.exists():
        raise SystemExit("Signed output already exists; verify it before performing another signing operation")
    store_password = lookup("KEY_STORE_PASSWORD__" + KEYSTORE)
    key_password = lookup("KEY_PASSWORD__" + KEYSTORE + "__" + ALIAS)
    with tempfile.TemporaryDirectory(prefix="auxio-signing-") as directory:
        files = []
        for name, value in [("store", store_password), ("key", key_password)]:
            path = Path(directory) / name
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(value)
            files.append(path)
        del store_password, key_password
        subprocess.run([sys.executable, str(base / "sign.py"),
                        "--jdk", "/home/mrindeciso/Applications/android-studio/jbr",
                        "--sdk", "/home/mrindeciso/Android/Sdk",
                        "--keystore", KEYSTORE, "--alias", ALIAS,
                        "--apk", str(args.apk),
                        "--out", str(output),
                        "--store-password-file", str(files[0]),
                        "--key-password-file", str(files[1])], check=True)
    print("APK signed with the installed app's identity. Temporary password files removed. Nothing installed.")


if __name__ == "__main__":
    try:
        main()
    except subprocess.TimeoutExpired:
        raise SystemExit("Keychain access timed out. Unlock it through your desktop before retrying.")
