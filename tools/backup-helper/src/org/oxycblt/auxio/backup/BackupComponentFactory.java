package org.oxycblt.auxio.backup;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;

/** Intercepts ALL manifest entrypoints before any original Auxio class is instantiated. */
public final class BackupComponentFactory extends AppComponentFactory {
    @Override public Application instantiateApplication(ClassLoader cl, String name) {
        return new Application();
    }

    @Override public Activity instantiateActivity(ClassLoader cl, String name, Intent intent) {
        return new BackupActivity();
    }

    @Override public Service instantiateService(ClassLoader cl, String name, Intent intent) {
        return new InactiveService();
    }

    @Override public BroadcastReceiver instantiateReceiver(ClassLoader cl, String name, Intent intent) {
        return new InactiveReceiver();
    }

    @Override public ContentProvider instantiateProvider(ClassLoader cl, String name) {
        return new InactiveProvider();
    }

    public static final class InactiveService extends Service {
        @Override public IBinder onBind(Intent intent) { return null; }
        @Override public int onStartCommand(Intent intent, int flags, int id) {
            stopSelf(id);
            return START_NOT_STICKY;
        }
    }

    public static final class InactiveReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {}
    }

    public static final class InactiveProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public String getType(Uri u) { return null; }
        @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    }
}
