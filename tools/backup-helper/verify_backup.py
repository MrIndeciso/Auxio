#!/usr/bin/env python3
"""Independently verify an Auxio snapshot and inspect SQLite on a disposable extraction."""
import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path, PurePosixPath
import sqlite3
import tempfile
import zipfile


def safe_path(name):
    path = PurePosixPath(name)
    if not name or path.is_absolute() or any(p in ("", ".", "..") for p in name.split("/")):
        raise ValueError("Unsafe archive path")
    if "\\" in name or "\x00" in name:
        raise ValueError("Unsafe archive path")
    return path


def verify(archive, expected_package="org.oxycblt.auxio"):
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        if len(set(names)) != len(names):
            raise ValueError("Duplicate ZIP entries")
        if "manifest.json" not in names:
            raise ValueError("No completion manifest: export was incomplete")
        manifest = json.loads(z.read("manifest.json"))
        if manifest.get("format") != 1 or manifest.get("complete") is not True:
            raise ValueError("Unsupported or incomplete export")
        if manifest["metadata"]["package"] != expected_package:
            raise ValueError("Unexpected package")
        entries = manifest["entries"]
        expected = {"manifest.json"}
        seen = set()
        total = 0
        for entry in entries:
            path = entry["path"]
            safe_path(path)
            if path in seen:
                raise ValueError("Duplicate manifest path")
            seen.add(path)
            name = "data/" + path + ("/" if entry["directory"] else "")
            expected.add(name)
            info = z.getinfo(name)
            if info.is_dir() != entry["directory"] or info.file_size != entry["size"]:
                raise ValueError("Entry size/type mismatch: " + path)
            digest = hashlib.sha256()
            with z.open(info) as stream:
                while data := stream.read(1024 * 1024):
                    digest.update(data)
            if not entry["directory"] and digest.hexdigest() != entry["sha256"]:
                raise ValueError("Checksum mismatch: " + path)
            total += info.file_size
        if set(names) != expected:
            raise ValueError("Archive inventory does not match its contents")
        return manifest, total


def extract_verified(archive, manifest, destination):
    """Only called on a fresh temporary directory. Never restore over a real installation."""
    with zipfile.ZipFile(archive) as z:
        for entry in manifest["entries"]:
            target = destination.joinpath(*safe_path(entry["path"]).parts)
            if entry["directory"]:
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open("data/" + entry["path"]) as source, target.open("xb") as out:
                    while data := source.read(1024 * 1024):
                        out.write(data)


def sqlite_report(path):
    def identifier(s):
        return '"' + s.replace('"', '""') + '"'

    def encode(value):
        if isinstance(value, bytes):
            return {"blob": value.hex()}
        return value

    with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        db.execute("PRAGMA query_only=ON")
        integrity = [row[0] for row in db.execute("PRAGMA integrity_check")]
        if integrity != ["ok"]:
            raise ValueError("SQLite integrity check failed: " + path.name)
        foreign_keys = list(db.execute("PRAGMA foreign_key_check"))
        if foreign_keys:
            raise ValueError("SQLite foreign key check failed: " + path.name)
        tables = {}
        for (table,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
            rows = [json.dumps([encode(v) for v in row], ensure_ascii=True, separators=(",", ":"))
                    for row in db.execute("SELECT * FROM " + identifier(table))]
            digest = hashlib.sha256()
            for row in sorted(rows):
                digest.update(row.encode() + b"\n")
            tables[table] = {"rows": len(rows), "logicalSha256": digest.hexdigest()}
        report = {"userVersion": db.execute("PRAGMA user_version").fetchone()[0],
                  "integrity": "ok", "foreignKeys": "ok", "tables": tables}
        if "SongStats" in tables:
            report["statsTotals"] = list(db.execute(
                "SELECT COALESCE(SUM(playCount),0), COALESCE(SUM(totalListenTimeMs),0) FROM SongStats").fetchone())
        if "PlayEvent" in tables:
            report["eventTotals"] = list(db.execute(
                "SELECT COUNT(*), COALESCE(SUM(listenTimeMs),0), MIN(timestamp), MAX(timestamp) FROM PlayEvent").fetchone())
        return report


def inspect(archive):
    manifest, size = verify(archive)
    databases = {}
    with tempfile.TemporaryDirectory(prefix="auxio-backup-inspect-") as temp:
        dest = Path(temp)
        extract_verified(archive, manifest, dest)
        for entry in manifest["entries"]:
            if not entry["directory"] and "/databases/" in entry["path"]:
                path = dest / entry["path"]
                with path.open("rb") as f:
                    header = f.read(16)
                if header == b"SQLite format 3\x00":
                    databases[entry["path"]] = sqlite_report(path)
    with Path(archive).open("rb") as stream:
        archive_digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"archiveSha256": archive_digest,
            "archiveVerified": True, "androidRestoreVerified": False,
            "persistentBytes": size, "entryCount": len(manifest["entries"]),
            "metadata": manifest["metadata"], "absentRoots": manifest["absentRoots"],
            "excludedRuntimePaths": manifest["excludedRuntimePaths"], "databases": databases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    report = inspect(args.archive)
    with args.report.open("x") as out:
        json.dump(report, out, indent=2)
        out.write("\n")
    print("Archive checks passed; Android restoration remains unverified.")
    print("Archive SHA-256:", report["archiveSha256"])
    print("Persistent files and directories:", report["entryCount"])
    print("SQLite databases checked:", len(report["databases"]))
    print("Private detailed report:", args.report)


if __name__ == "__main__":
    main()
