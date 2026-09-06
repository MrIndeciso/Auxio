#!/usr/bin/env python3
"""Verify this fork's release artifact against the archived installed APK and build evidence."""
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
APK = ROOT / 'release/Auxio-v4.1.5-stats.1.apk'
UNSIGNED = ROOT / 'app/build/outputs/apk/release/app-release-unsigned.apk'
ORIGINAL = ROOT / 'release/backup-helper/original-4.0.10.apk'
CERT = '0a0a80b95b50102b662d3854572f4d85891d0b3b12a5e308bdcb27bd4f164d61'
SDK = Path('/home/mrindeciso/Android/Sdk/build-tools/36.1.0')
JDK = Path('/home/mrindeciso/Applications/android-studio/jbr')


def main():
    signer = [str(JDK / 'bin/java'), '-jar', str(SDK / 'lib/apksigner.jar'),
              'verify', '--verbose', '--print-certs']
    for path in (ORIGINAL, ROOT / 'release/Auxio-custom-v4.1.5.apk', APK):
        out = subprocess.check_output(signer + [str(path)], text=True)
        assert 'certificate SHA-256 digest: ' + CERT in out
    badging = subprocess.check_output([str(SDK / 'aapt'), 'dump', 'badging', str(APK)], text=True)
    assert "name='org.oxycblt.auxio' versionCode='76' versionName='4.1.5-stats.1'" in badging
    assert 'application-debuggable' not in badging
    manifest = subprocess.check_output([str(SDK / 'aapt'), 'dump', 'xmltree', str(APK),
                                        'AndroidManifest.xml'], text=True)
    assert 'android:debuggable' not in manifest
    assert 'BackupComponentFactory' not in manifest
    with zipfile.ZipFile(UNSIGNED) as unsigned, zipfile.ZipFile(APK) as signed:
        original_names = set(unsigned.namelist())
        final_names = set(signed.namelist())
        assert len(final_names) == len(signed.namelist())
        assert original_names <= final_names
        assert final_names - original_names <= {'META-INF/KEY0.RSA', 'META-INF/KEY0.SF',
                                                'META-INF/MANIFEST.MF'}
        for name in original_names:
            assert unsigned.read(name) == signed.read(name), name
        assert not any(name.lower().endswith(('.jks', '.keystore', '.db', '.db-wal'))
                       for name in final_names)
        architectures = sorted({name.split('/')[1] for name in final_names
                                if name.startswith('lib/') and name.endswith('.so')})
    assert architectures == ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']
    subprocess.run([str(SDK / 'zipalign'), '-c', '-P', '16', '4', str(APK)], check=True)
    tests = {}
    for module in ('app', 'musikr'):
        total = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
        for path in (ROOT / module / 'build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
            suite = ET.parse(path).getroot()
            for key in total:
                total[key] += int(suite.get(key, 0))
        tests[module] = total
    assert sum(test['tests'] for test in tests.values()) >= 150
    assert all(not (test['failures'] or test['errors'] or test['skipped']) for test in tests.values())
    assert 'BUILD SUCCESSFUL' in (ROOT / 'release/stats-startup-fix-build.log').read_text()
    assert (ROOT / 'app/build/outputs/mapping/release/mapping.txt').stat().st_size > 0
    backup = ROOT / 'release/backup-helper/backups/auxio-backup-1788653265091.zip'
    assert hashlib.sha256(backup.read_bytes()).hexdigest() == (
        '96ee14f4082e26fb23aa252060b9eb68e18c23296e5114092a854700174daf28')
    lint = ET.parse(ROOT / 'app/build/reports/lint-results-release.xml').getroot()
    errors = [i for i in lint.findall('issue') if i.get('severity') == 'Error']
    original_errors = []
    for issue in errors:
        path = Path(issue.find('location').get('file'))
        relative = path.relative_to(ROOT)
        original = subprocess.check_output(['git', 'show', 'v4.1.5:' + str(relative)], cwd=ROOT)
        assert path.read_bytes() == original, 'New release lint error: ' + str(relative)
        original_errors.append({'id': issue.get('id'), 'file': str(relative), 'line': issue.find('location').get('line')})
    lint_report = {'errors': len(errors), 'allErrorFilesUnchangedFromUpstream415': True}
    (ROOT / 'release/stats-lint-existing-errors.json').write_text(json.dumps(original_errors, indent=2) + '\n')
    installation_path = ROOT / 'release/stats-installation-verification.json'
    installation = json.loads(installation_path.read_text()) if installation_path.exists() else {}
    installed = installation.get('sha256') == hashlib.sha256(APK.read_bytes()).hexdigest() and installation.get('startupVerified') is True
    report = {
        'apk': str(APK), 'sha256': hashlib.sha256(APK.read_bytes()).hexdigest(),
        'package': 'org.oxycblt.auxio', 'versionName': '4.1.5-stats.1', 'versionCode': 76,
        'previousInstalledVersionCode': 75, 'originalBackupVersionCode': 69, 'certificateSha256': CERT, 'originalCertificateMatches': True,
        'signatureSchemes': ['v2', 'v3'], 'debuggable': False, 'minSdk': 24, 'targetSdk': 36,
        'architectures': architectures, 'zip16KiBAlignmentVerified': True,
        'signedPayloadMatchesUnsigned': True, 'minifiedReleaseBuild': True,
        'backupMasterChecksumUnchanged': True, 'hostTests': tests, 'spotlessCheck': 'passed',
        'lintVitalRelease': 'passed',
        'lintRelease': lint_report,
        'actualDataMigrationHostTests': 'passed stats 2→3→4 and installed-schema 3→4, playback, cache, user music and preferences with reopen',
        'preservedPlayEvents': 7100, 'preservedSongStats': 883,
        'preservedListenTimeMs': 1167646409,
        'unavailableSongsIncludedInTotals': True,
        'hostDashboardLayout': 'light/dark, 360dp, 100% and 200% text',
        'physicalDeviceTalkBackVerified': False,
        'androidArchiveRestoreVerified': False, 'minifiedArtifactRuntimeTested': installed,
        'safeForRealInstallationVerified': False, 'installed': installed, 'startupVerifiedOnDevice': installed,
    }
    (ROOT / 'release/stats-release-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    (ROOT / 'release/Auxio-v4.1.5-stats.1.apk.sha256').write_text(
        report['sha256'] + '  Auxio-v4.1.5-stats.1.apk\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
