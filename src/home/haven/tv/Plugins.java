package home.haven.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import tv.projectivy.plugin.wallpaperprovider.api.Event;
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService;
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper;

/**
 * Wallpaper plugins made for Projectivy Launcher. They're ordinary apps with a service that
 * answers {@link #ACTION} over Projectivy's open plugin API, so Haven can use the same ones
 * (Bing photo of the day, Apple aerials, movie art, Immich, Jellyfin and others).
 */
final class Plugins {

    static final String ACTION = "tv.projectivy.plugin.WALLPAPER_PROVIDER";
    private static final int API_VERSION = 1;
    private static final long CONNECT_MS = 5000;

    static final class Info {
        final ComponentName service;
        final String name, settingsActivity;
        final long cacheMs;
        final int updateMode;

        Info(ComponentName service, String name, String settingsActivity, long cacheMs, int updateMode) {
            this.service = service;
            this.name = name;
            this.settingsActivity = settingsActivity;
            this.cacheMs = cacheMs;
            this.updateMode = updateMode;
        }

        /** Stored in Haven's settings to remember the choice. */
        String id() {
            return service.flattenToString();
        }

        boolean wants(int eventType) {
            return (updateMode & eventType) != 0;
        }

        Intent settingsIntent() {
            if (settingsActivity == null || settingsActivity.isEmpty()) return null;
            String cls = settingsActivity.startsWith(".") ? service.getPackageName() + settingsActivity : settingsActivity;
            return new Intent().setClassName(service.getPackageName(), cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
    }

    private Plugins() { }

    /** Installed plugins that speak API version 1, by name. */
    private static List<Info> cached;
    private static int cachedAt = -1;

    /** Remembered until apps change (see Lookups). */
    static synchronized List<Info> find(Context c) {
        if (cached != null && cachedAt == Lookups.generation()) return new ArrayList<>(cached);
        cachedAt = Lookups.generation();
        cached = scan(c);
        return new ArrayList<>(cached);
    }

    private static List<Info> scan(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Info> out = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentServices(new Intent(ACTION), PackageManager.GET_META_DATA)) {
            ServiceInfo s = r.serviceInfo;
            if (s == null || !s.exported) continue;
            Bundle m = s.metaData != null ? s.metaData : new Bundle();
            if (m.getInt("apiVersion", API_VERSION) != API_VERSION) continue;
            String name = m.get("name") != null ? String.valueOf(m.get("name")) : String.valueOf(r.loadLabel(pm));
            Object cache = m.get("itemsCacheDurationMillis");
            long cacheMs = cache instanceof Number ? ((Number) cache).longValue() : 3600_000L;
            out.add(new Info(new ComponentName(s.packageName, s.name), name,
                    m.getString("settingsActivity"), Math.max(60_000L, cacheMs), m.getInt("updateMode", Event.TIME_ELAPSED)));
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    static Info byId(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Info i : find(c)) if (i.id().equals(id)) return i;
        return null;
    }

    /**
     * A binding to one plugin. {@link #get} blocks, so call it off the main thread; it connects on
     * first use and again if the plugin's process was killed.
     */
    static final class Connection {
        private final Context c;
        final Info info;
        private volatile IWallpaperProviderService service;
        private volatile CountDownLatch ready;
        private boolean bound;

        private final ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service = IWallpaperProviderService.Stub.asInterface(binder);
                CountDownLatch l = ready;
                if (l != null) l.countDown();
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                service = null;
            }

            @Override public void onBindingDied(ComponentName name) {
                service = null;
                close();
            }
        };

        Connection(Context c, Info info) {
            this.c = c.getApplicationContext();
            this.info = info;
        }

        /** The plugin's wallpapers for {@code event}; empty if it has none or doesn't answer. */
        List<Wallpaper> get(Event event) {
            IWallpaperProviderService s = connect();
            if (s == null) return Collections.emptyList();
            try {
                List<Wallpaper> list = s.getWallpapers(event);
                return list != null ? list : Collections.emptyList();
            } catch (RemoteException | RuntimeException e) {
                service = null;
                return Collections.emptyList();
            }
        }

        private IWallpaperProviderService connect() {
            IWallpaperProviderService s = service;
            if (s != null && s.asBinder().isBinderAlive()) return s;
            CountDownLatch l = new CountDownLatch(1);
            ready = l;
            synchronized (this) {
                if (!bound) {
                    try {
                        bound = c.bindService(new Intent(ACTION).setComponent(info.service), conn, Context.BIND_AUTO_CREATE);
                    } catch (SecurityException e) {
                        bound = false;
                    }
                    if (!bound) return null;
                }
            }
            try {
                l.await(CONNECT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return service;
        }

        synchronized void close() {
            if (!bound) return;
            bound = false;
            try {
                c.unbindService(conn);
            } catch (IllegalArgumentException ignored) { }
        }
    }
}
