package org.oxycblt.auxio.backup;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.*;
import android.os.storage.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses only the Android framework. No Room, Hilt, Auxio settings, or image initialization. */
public final class BackupActivity extends Activity {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile String lastOutcome;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button export;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (lastOutcome != null) status.setText(lastOutcome);
            export.setEnabled(!BUSY.get());
            if (BUSY.get()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(24 * getResources().getDisplayMetrics().density);
        box.setPadding(padding, padding * 2, padding, padding);
        TextView title = new TextView(this);
        title.setText("Auxio data backup");
        title.setTextSize(26);
        box.addView(title);
        status = new TextView(this);
        status.setText("This temporary app copies Auxio’s saved history, statistics, playlists, " +
                "settings, queue, metadata, and covers. Playback is paused while it is installed.\n\n" +
                "Choose Downloads on this device for the backup. Music files are separate. " +
                "Keep the app open until export finishes. The archive contains private listening data.");
        box.addView(status);
        export = new Button(this);
        export.setText("Save full backup");
        export.setEnabled(!BUSY.get());
        export.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, "auxio-backup-" + System.currentTimeMillis() + ".zip");
            startActivityForResult(intent, 1);
        });
        box.addView(export);
        setContentView(box);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null || data.getData() == null) return;
        if (!BUSY.compareAndSet(false, true)) return;
        Uri destination = data.getData();
        export.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lastOutcome = "Copying and checking saved data. Keep this screen open…";
        status.setText(lastOutcome);
        new Thread(() -> {
            String message;
            try {
                if (!"content".equals(destination.getScheme()) ||
                        !"com.android.providers.downloads.documents".equals(destination.getAuthority())) {
                    throw new IOException("Choose the device’s Downloads folder, not a cloud or app provider.");
                }
                Map<String, Path> roots = new LinkedHashMap<>();
                roots.put("ce", getDataDir().toPath());
                roots.put("de", createDeviceProtectedStorageContext().getDataDir().toPath());
                if (!Files.isRegularFile(getDataDir().toPath().resolve("databases/stats.db"),
                        LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Expected listening-history database is missing; verify the app and Android user.");
                }
                StorageManager storage = getSystemService(StorageManager.class);
                int index = 0;
                for (StorageVolume volume : storage.getStorageVolumes()) {
                    if (!Environment.MEDIA_MOUNTED.equals(volume.getState()) &&
                            !Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState())) {
                        throw new IOException("A storage volume is unavailable. Mount it before exporting.");
                    }
                    File directory = volume.getDirectory();
                    if (directory == null) throw new IOException("Cannot locate a storage volume");
                    roots.put("external-data-" + index,
                            new File(directory, "Android/data/" + getPackageName()).toPath());
                    roots.put("external-media-" + index,
                            new File(directory, "Android/media/" + getPackageName()).toPath());
                    index++;
                }
                PackageInfo pkg = getPackageManager().getPackageInfo(getPackageName(),
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                StringJoiner certs = new StringJoiner(",", "[", "]");
                for (android.content.pm.Signature cert : pkg.signingInfo.getApkContentsSigners()) {
                    certs.add(Snapshot.quote(Snapshot.hex(MessageDigest.getInstance("SHA-256")
                            .digest(cert.toByteArray()))));
                }
                StringJoiner grants = new StringJoiner(",", "[", "]");
                for (UriPermission grant : getContentResolver().getPersistedUriPermissions()) {
                    grants.add("{\"uri\":" + Snapshot.quote(grant.getUri().toString()) +
                            ",\"read\":" + grant.isReadPermission() +
                            ",\"write\":" + grant.isWritePermission() + "}");
                }
                String metadata = "{\"package\":" + Snapshot.quote(getPackageName()) +
                        ",\"versionCode\":" + pkg.getLongVersionCode() +
                        ",\"androidSdk\":" + Build.VERSION.SDK_INT +
                        ",\"certificateSha256\":" + certs +
                        ",\"persistedUriGrants\":" + grants + "}";
                try (OutputStream stream = getContentResolver().openOutputStream(destination, "wt")) {
                    if (stream == null) throw new IOException("Cannot open backup destination");
                    Snapshot.write(roots, stream, metadata);
                }
                message = "Export complete. Copy this archive to your computer for independent " +
                        "verification. Keep two copies before upgrading.\n\n" +
                        "Restoration into Android has not yet been verified.";
            } catch (Exception error) {
                message = "Backup incomplete: " + error.getClass().getSimpleName() + " — " +
                        error.getMessage() + "\n\nDo not use the partial archive or upgrade Auxio.";
            } finally {
                BUSY.set(false);
            }
            final String outcome = message;
            lastOutcome = outcome;
            runOnUiThread(() -> {
                status.setText(outcome);
                export.setEnabled(true);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }, "auxio-backup").start();
    }
}
