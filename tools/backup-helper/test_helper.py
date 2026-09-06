#!/usr/bin/env python3
"""Host regressions for real archive bytes, WAL recovery, corruption and filesystem changes."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest
import zipfile
import sys
import build
import verify_backup

HARNESS = r'''
import org.oxycblt.auxio.backup.Snapshot;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class Harness {
  public static void main(String[] args) throws Exception {
    Map<String,Path> roots = new LinkedHashMap<>();
    roots.put("ce", Paths.get(args[0]));
    OutputStream out = Files.newOutputStream(Paths.get(args[1]));
    if (args.length > 2) {
      out = new FilterOutputStream(out) {
        boolean changed;
        @Override public void write(byte[] b, int o, int n) throws IOException {
          if (!changed) {
            changed = true;
            Files.write(Paths.get(args[0], "mutation"), new byte[]{1,2,3});
          }
          this.out.write(b,o,n);
        }
      };
    }
    Snapshot.write(roots, out, "{\"package\":\"org.oxycblt.auxio\"}");
  }
}
'''


class Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = tempfile.TemporaryDirectory(prefix="auxio-helper-tests-")
        cls.path = Path(cls.work.name)
        harness = cls.path / "Harness.java"
        harness.write_text(HARNESS)
        subprocess.run([str(JDK / "bin/javac"), "-d", str(cls.path), str(harness),
                        str(Path(__file__).parent / "src/org/oxycblt/auxio/backup/Snapshot.java")], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.work.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=self.path)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "root"
        self.root.mkdir()
        self.archive = Path(self.temp.name) / "snapshot.zip"

    def export(self, mutate=False, success=True):
        cmd = [str(JDK / "bin/java"), "-cp", str(self.path), "Harness", str(self.root), str(self.archive)]
        if mutate:
            cmd.append("mutate")
        result = subprocess.run(cmd, capture_output=True, text=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0)

    def test_round_trip_and_wal(self):
        directory = self.root / "databases"
        directory.mkdir()
        db = sqlite3.connect(directory / "stats.db")
        self.addCleanup(db.close)
        db.executescript('''PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0;
            PRAGMA user_version=2;
            CREATE TABLE SongStats(songUid TEXT PRIMARY KEY NOT NULL, playCount INTEGER NOT NULL,
              totalListenTimeMs INTEGER NOT NULL, lastPlayedTimestamp INTEGER NOT NULL);
            CREATE TABLE PlayEvent(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, songUid TEXT NOT NULL,
              timestamp INTEGER NOT NULL, listenTimeMs INTEGER NOT NULL);
            INSERT INTO SongStats VALUES('org.oxycblt.auxio:a10b-00000000-0000-0000-0000-000000000001',2,22000,2000);
            INSERT INTO PlayEvent(songUid,timestamp,listenTimeMs) SELECT songUid,1000,10000 FROM SongStats;
            INSERT INTO PlayEvent(songUid,timestamp,listenTimeMs) SELECT songUid,2000,12000 FROM SongStats;
        ''')
        db.commit()
        self.assertGreater((directory / "stats.db-wal").stat().st_size, 0)
        (self.root / "shared_prefs").mkdir()
        (self.root / 'shared_prefs/a "β".xml').write_text('<map><long name="test" value="9"/></map>')
        (self.root / "files/empty").mkdir(parents=True)
        (self.root / "no_backup").mkdir()
        (self.root / "no_backup/precious").write_bytes(b"\x00\xff\x01")
        (self.root / "cache").mkdir()
        (self.root / "cache/transient").write_text("not persistent")
        before = {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        self.export()
        manifest, _ = verify_backup.verify(self.archive)
        self.assertEqual(manifest["excludedRuntimePaths"], ["ce/cache"])
        report = verify_backup.inspect(self.archive)
        stats = report["databases"]["ce/databases/stats.db"]
        self.assertEqual(stats["userVersion"], 2)
        self.assertEqual(stats["statsTotals"], [2, 22000])
        self.assertEqual(stats["eventTotals"], [2, 22000, 1000, 2000])
        self.assertFalse(report["androidRestoreVerified"])
        for name, content in before.items():
            self.assertEqual((self.root / name).read_bytes(), content, name)

    def test_symlink_rejected(self):
        (self.root / "escape").symlink_to("/etc/passwd")
        self.export(success=False)

    def test_changes_rejected(self):
        import random
        (self.root / "large").write_bytes(random.Random(12).randbytes(300000))
        self.export(mutate=True, success=False)
        with self.assertRaises((ValueError, zipfile.BadZipFile)):
            verify_backup.verify(self.archive)

    def test_corruption_rejected(self):
        (self.root / "record").write_bytes(b"original")
        self.export()
        broken = Path(self.temp.name) / "broken.zip"
        with zipfile.ZipFile(self.archive) as src, zipfile.ZipFile(broken, "w") as dst:
            for name in src.namelist():
                dst.writestr(name, b"tampered" if name == "data/ce/record" else src.read(name))
        with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
            verify_backup.verify(broken)

    def test_missing_completion_rejected(self):
        with zipfile.ZipFile(self.archive, "w") as z:
            z.writestr("data/ce/file", "partial")
        with self.assertRaisesRegex(ValueError, "completion"):
            verify_backup.verify(self.archive)

    def test_unsafe_paths_rejected(self):
        for path in ["../outside", "/absolute", "a/../outside", "a\\outside", "a//b"]:
            with self.assertRaises(ValueError):
                verify_backup.safe_path(path)

    def test_manifest_patch(self):
        with zipfile.ZipFile(ORIGINAL) as z:
            data = z.read("AndroidManifest.xml")
        patched = build.patch_manifest(data)
        self.assertEqual(build.u32(patched, 4), len(patched))
        path = Path(self.temp.name) / "manifest.apk"
        with zipfile.ZipFile(ORIGINAL) as src, zipfile.ZipFile(path, "w") as dst:
            for name in src.namelist():
                dst.writestr(name, patched if name == "AndroidManifest.xml" else src.read(name))
        output = subprocess.check_output([str(SDK / "build-tools/36.1.0/aapt2"),
            "dump", "xmltree", "--file", "AndroidManifest.xml", str(path)], text=True)
        old = subprocess.check_output([str(SDK / "build-tools/36.1.0/aapt2"),
            "dump", "xmltree", "--file", "AndroidManifest.xml", str(ORIGINAL)], text=True)
        # Every decoded manifest line stays identical except the factory and the API floor.
        expected = old.replace("androidx.core.app.CoreComponentFactory", build.FACTORY)
        old_min = next(line for line in old.splitlines() if "minSdkVersion(" in line)
        new_min = old_min.replace("=24", "=30")
        expected = expected.replace(old_min, new_min)
        self.assertEqual(output, expected)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--jdk", type=Path, required=True)
    p.add_argument("--sdk", type=Path, required=True)
    p.add_argument("--original", type=Path, required=True)
    args, rest = p.parse_known_args()
    JDK, SDK, ORIGINAL = args.jdk, args.sdk, args.original
    unittest.main(argv=[sys.argv[0], *rest], verbosity=2)
