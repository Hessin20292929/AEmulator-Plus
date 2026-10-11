package app.aemu.stub;

import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Guest process of the emulated Wi-Fi (started by the host before zygote, like the Bluetooth service):
 *  - serves "wifi" (IWifiManager, see {@link WifiService}),
 *  - Android 5.0+: registers the connection as a Wi-Fi network with ConnectivityService ({@link WifiNet}).
 *
 * Usage: app_process -Djava.class.path=/system/framework/aemu-wifi.jar /system/bin app.aemu.stub.WifiStub
 */
public final class WifiStub {
    private static WifiNet net;

    private WifiStub() { }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.flush();
        }
    }


    private static IBinder getService(String name) {
        try {
            return (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"), "getService", name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static IBinder checkService(String name) {
        try {
            return (IBinder) Refl.callStatic(Refl.cls("android.os.ServiceManager"), "checkService", name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isOurs(IBinder b, WifiService svc) {
        if (b == svc) return true;
        if (b == null) return false;
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            return b.transact(WifiService.PROBE, data, reply, 0) && reply.readInt() == WifiService.PROBE_ANSWER;
        } catch (Throwable t) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void addService(String name, IBinder b) {
        Refl.callStatic(Refl.cls("android.os.ServiceManager"), "addService", name, b);
    }

    private static void run() throws Exception {
        Looper.prepare();
        final Looper looper = Looper.myLooper();
        final WifiConfig cfg = WifiConfig.load();
        if (cfg == null) {
            Refl.log("no settings file, nothing to do");
            return;
        }
        final WifiService svc = new WifiService(cfg, looper);
        final Handler main = new Handler(looper);
        final boolean agent = Build.VERSION.SDK_INT >= 21;
        final LegacyWifi legacy = Build.VERSION.SDK_INT < 21 ? new LegacyWifi(cfg, svc) : null;
        svc.setLegacy(legacy);

        if (agent) {
            svc.setListener(new WifiService.Listener() {
                @Override public void onConnectionChanged(final boolean connected) {
                    main.post(new Runnable() { @Override public void run() { applyConnection(looper, cfg, connected); } });
                }
            });
        }

        Thread t = new Thread("aemu-wifi-register") {
            @Override public void run() {
                try {
                    watch(svc, main, looper, cfg, agent, legacy);
                } catch (Throwable e) {
                    e.printStackTrace(System.out);
                    System.out.flush();
                }
            }
        };
        t.setDaemon(true);
        t.start();
        Looper.loop();
    }

    // IBinder of ConnectivityService, once system_server has published it
    private static volatile IBinder connectivity;

    /** Registers "wifi" and keeps it ours: system_server may add its own WifiService later, which would replace ours. */
    private static void watch(final WifiService svc, Handler main, final Looper looper, final WifiConfig cfg, boolean agent, LegacyWifi legacy) throws Exception {
        long agentDeadline = System.currentTimeMillis() + 180000L;
        boolean agentDone = !agent;
        int takeovers = 0;
        for (;;) {
            if (!isOurs(checkService("wifi"), svc)) {
                addService("wifi", svc);
                if (takeovers++ == 0) Refl.log("service \"wifi\" registered");
                else if (takeovers <= 3) Refl.log("service \"wifi\" taken back from the framework's own");
            }
            if (!agentDone) {
                IBinder cs = connectivity != null ? connectivity : getService("connectivity");
                if (cs != null) {
                    connectivity = cs;
                    agentDone = true;
                    main.post(new Runnable() { @Override public void run() { applyConnection(looper, cfg, svc.isConnected()); } });
                } else if (System.currentTimeMillis() > agentDeadline) {
                    Refl.log("ConnectivityService did not appear, no Wi-Fi network registered");
                    agentDone = true;
                }
            }
            if (legacy != null) legacy.tick(checkService("connectivity") != null);
            Thread.sleep(agentDone ? 1000L : 500L);
        }
    }

    /** On the main thread: bring the registered network in line with the connection state. */
    private static void applyConnection(Looper looper, WifiConfig cfg, boolean connected) {
        if (connectivity == null) return; // registered once ConnectivityService is up (see watch)
        try {
            if (connected && net == null) {
                net = WifiNet.register(looper, connectivity, cfg);
            } else if (!connected && net != null) {
                net.disconnect();
                net = null;
            }
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.flush();
        }
    }
}
