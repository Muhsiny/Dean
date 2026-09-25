from pathlib import Path
import re,sys

root=Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()

def edit(rel, fn):
    p=root/rel
    s=p.read_text(encoding='utf-8')
    ns=fn(s)
    if ns==s:
        print('WARN no change:', rel)
    p.write_text(ns,encoding='utf-8')
    print('stability patched:',rel)

def patch_manifest_stability(s):
    s=s.replace('android:allowBackup="true"', 'android:allowBackup="false"')
    if 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE' not in s:
        s=s.replace('<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />',
                    '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />\n'
                    '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />')
    s=s.replace('android:foregroundServiceType="dataSync" />',
                'android:foregroundServiceType="dataSync|specialUse">\n'
                '            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"\n'
                '                android:value="SAYEH VPN encrypted tunnel runtime" />\n'
                '        </service>')
    return s
edit('gui/build/android/app/src/main/AndroidManifest.xml', patch_manifest_stability)

def patch_service(s):
    # Drop experimental radio forcing/pinning from 1.0 Turbo.
    s=s.replace('import android.net.wifi.WifiManager;\n','')
    if 'import android.net.NetworkRequest;' not in s:
        s=s.replace('import android.net.NetworkCapabilities;\n',
                    'import android.net.NetworkCapabilities;\nimport android.net.NetworkRequest;\n')
    if 'import android.os.Handler;' not in s:
        s=s.replace('import android.os.IBinder;\n',
                    'import android.os.IBinder;\nimport android.os.Handler;\nimport android.os.Looper;\nimport android.os.SystemClock;\n')

    s=s.replace('    private WifiManager.WifiLock sayehWifiLock;\n','')
    field='''    private ConnectivityManager sayehConnectivityManager;
    private ConnectivityManager.NetworkCallback sayehNetworkCallback;
    private volatile Network sayehPhysicalNetwork;
    private volatile boolean sayehPhysicalNetworkWasLost = false;
    private volatile boolean sayehRestartPending = false;
    private volatile long sayehVpnStartedMs = 0L;
    private final Handler sayehHandler = new Handler(Looper.getMainLooper());
'''
    if 'private ConnectivityManager sayehConnectivityManager;' not in s:
        s=s.replace('    private static volatile WarpVpnService sInstance;\n',
                    '    private static volatile WarpVpnService sInstance;\n'+field)

    s=s.replace('''        String physicalDns = collectPhysicalDns();
        Network physicalNetwork = activePhysicalNetwork();
        int sayehTunMtu = chooseSayehTunMtu(physicalNetwork);
        acquireSayehWifiPerformanceLock(physicalNetwork);

        VpnService.Builder builder = new VpnService.Builder();
        builder.setSession("SAYEH VPN");''',
                '''        String physicalDns = collectPhysicalDns();

        VpnService.Builder builder = new VpnService.Builder();
        builder.setSession("SAYEH VPN");''')
    s=re.sub(r'''        builder\.setMtu\(sayehTunMtu\);\n        if \(Build\.VERSION\.SDK_INT >= 22 && physicalNetwork != null\) \{\n            try \{\n                builder\.setUnderlyingNetworks\(new Network\[\]\{physicalNetwork\}\);\n                Log\.i\(TAG, "SAYEH Turbo: underlying physical network pinned"\);\n            \} catch \(Throwable t\) \{\n                Log\.w\(TAG, "SAYEH Turbo: setUnderlyingNetworks failed", t\);\n            \}\n        \}''',
             '        builder.setMtu(1400);',s)

    s=s.replace('''    private void closeNative() {
        releaseSayehWifiPerformanceLock();
        ParcelFileDescriptor pfd = vpnPfd;''',
                '''    private void closeNative() {
        ParcelFileDescriptor pfd = vpnPfd;''')

    # Remove 1.0 Turbo helper block, preserve a tiny physical-network helper.
    start=s.find('    private Network activePhysicalNetwork() {')
    end=s.find('    private String collectPhysicalDns() {')
    if start>=0 and end>start:
        helper='''    private Network activePhysicalNetwork() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network current = cm.getActiveNetwork();
            if (current == null) return null;
            NetworkCapabilities caps = cm.getNetworkCapabilities(current);
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                return current;
            }
            return sayehPhysicalNetwork;
        } catch (Throwable t) {
            return sayehPhysicalNetwork;
        }
    }

'''
        s=s[:start]+helper+s[end:]

    # Install a NOT_VPN network monitor. Unlike setUnderlyingNetworks(), this
    # does not pin the VPN to a stale Wi-Fi network. Handoff triggers a clean
    # tunnel rebuild with the already-granted VpnService permission.
    if 'private void registerSayehNetworkMonitor()' not in s:
        marker='    private String collectPhysicalDns() {'
        monitor=r'''    private void registerSayehNetworkMonitor() {
        try {
            sayehConnectivityManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (sayehConnectivityManager == null) return;

            sayehPhysicalNetwork = activePhysicalNetwork();
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();

            sayehNetworkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    final Network n = network;
                    sayehHandler.post(() -> onSayehPhysicalNetworkAvailable(n));
                }

                @Override
                public void onLost(Network network) {
                    final Network n = network;
                    sayehHandler.post(() -> {
                        if (n != null && n.equals(sayehPhysicalNetwork)) {
                            sayehPhysicalNetworkWasLost = true;
                        }
                    });
                }
            };
            sayehConnectivityManager.registerNetworkCallback(request, sayehNetworkCallback);
        } catch (Throwable t) {
            Log.w(TAG, "SAYEH Stability: network monitor unavailable", t);
        }
    }

    private void unregisterSayehNetworkMonitor() {
        ConnectivityManager cm = sayehConnectivityManager;
        ConnectivityManager.NetworkCallback cb = sayehNetworkCallback;
        sayehConnectivityManager = null;
        sayehNetworkCallback = null;
        if (cm != null && cb != null) {
            try {
                cm.unregisterNetworkCallback(cb);
            } catch (Throwable ignored) {}
        }
        sayehHandler.removeCallbacksAndMessages(null);
    }

    private void onSayehPhysicalNetworkAvailable(Network network) {
        if (network == null) return;
        Network old = sayehPhysicalNetwork;
        boolean changed = sayehPhysicalNetworkWasLost ||
                (old != null && !old.equals(network));
        sayehPhysicalNetwork = network;
        sayehPhysicalNetworkWasLost = false;

        if (!changed || !nativeRunning || vpnPfd == null) return;
        long age = SystemClock.elapsedRealtime() - sayehVpnStartedMs;
        if (age < 2500L) return;
        scheduleSayehHandoffRestart();
    }

    private void scheduleSayehHandoffRestart() {
        if (sayehRestartPending) return;
        sayehRestartPending = true;
        MainActivity.nativeLogMessage("info",
                "شبکه تغییر کرد؛ SAYEH اتصال را روی مسیر جدید بازسازی می‌کند…");
        sayehHandler.postDelayed(() -> {
            sayehRestartPending = false;
            if (!nativeRunning) return;
            stopNativeAndClose();
            sayehHandler.postDelayed(() -> {
                try {
                    Intent restart = new Intent(this, WarpVpnService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(restart);
                    } else {
                        startService(restart);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "SAYEH Stability: handoff restart failed", t);
                }
            }, 300L);
        }, 250L);
    }

'''
        s=s.replace(marker,monitor+marker)

    s=s.replace('''    public void onCreate() {
        super.onCreate();
        sInstance = this;
    }''',
                '''    public void onCreate() {
        super.onCreate();
        sInstance = this;
        registerSayehNetworkMonitor();
    }''')

    # Record age only after Java has handed the fd to the native engine.
    s=s.replace('''        Log.i(TAG, "VPN accepted async start (fd=" + fd + ")");
        MainActivity.nativeLogMessage("info", "✓ VPN 已受理启动（fd=" + fd + "）");''',
                '''        sayehVpnStartedMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "VPN accepted async start (fd=" + fd + ")");
        MainActivity.nativeLogMessage("info", "✓ VPN 已受理启动（fd=" + fd + "）");''')

    s=s.replace('''        stopNativeAndClose();
        sInstance = null;
        super.onDestroy();''',
                '''        stopNativeAndClose();
        unregisterSayehNetworkMonitor();
        sInstance = null;
        super.onDestroy();''')
    return s

edit('gui/build/android/app/src/main/java/com/wails/app/WarpVpnService.java', patch_service)

def patch_fgs_java(s):
    old='''        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }'''
    new='''        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }'''
    return s.replace(old,new)
edit('gui/build/android/app/src/main/java/com/wails/app/WarpVpnService.java', patch_fgs_java)
edit('gui/build/android/app/src/main/java/com/wails/app/WailsForegroundService.java', patch_fgs_java)


def patch_bridge(s):
    for imp in ['\t"io"\n','\t"net"\n','\t"net/http"\n','\t"syscall"\n']:
        s=s.replace(imp,'')
    s=s.replace('\tbuilt.cfg.TunnelConnections = sayehAutoTunnelConnections(ctx)\n','')
    s=s.replace('\tlog.Printf("SAYEH Turbo：自动选择 %d 条 MASQUE 连接", built.cfg.TunnelConnections)\n','')
    s=re.sub(r'// sayehAutoTunnelConnections measures.*?\nfunc sayehAutoTunnelConnections\(parent context\.Context\) int \{.*?\n\}\n\n',
             '',s,flags=re.S)
    return s
edit('gui/androidbridge.go', patch_bridge)

def patch_client(s):
    s=s.replace('egressProbeInterval = 20 * time.Second',
                'egressProbeInterval = 10 * time.Second')
    s=s.replace('probeEgressTarget  = "8.8.8.8:443"\n\tprobeEgressTimeout = 5 * time.Second',
                'probeEgressTimeout = 4 * time.Second')
    if 'var probeEgressTargets = []string{' not in s:
        s=s.replace('const connectionIDLength = 20\n',
                    'const connectionIDLength = 20\n\nvar probeEgressTargets = []string{"1.1.1.1:443", "8.8.8.8:443"}\n')
    s=s.replace('InitialConnectionReceiveWindow: 16_000_000,\n\t\tMaxConnectionReceiveWindow:     32_000_000,\n\t\tInitialStreamReceiveWindow:     2_000_000,\n\t\tMaxStreamReceiveWindow:         8_000_000,',
                'InitialConnectionReceiveWindow: 10_000_000,\n'
                '\t\tMaxConnectionReceiveWindow:     10_000_000,\n'
                '\t\tInitialStreamReceiveWindow:     1_000_000,\n'
                '\t\tMaxStreamReceiveWindow:         1_000_000,')
    s=s.replace('\t\t\tgo c.sayehPrewarmDoH()\n','')
    s=s.replace('''\t// SAYEH Turbo: absorb bursts on high-bandwidth/high-RTT links. The OS may
\t// clamp these values; failure is non-fatal and QUIC continues normally.
\t_ = udpConn.SetReadBuffer(4 << 20)
\t_ = udpConn.SetWriteBuffer(4 << 20)
''','')
    s=re.sub(r'// sayehPrewarmDoH pays.*?\nfunc \(c \*MasqueClient\) sayehPrewarmDoH\(\) \{.*?\n\}\n\n',
             '',s,flags=re.S)
    pattern=r'func \(c \*MasqueClient\) probeInternationalEgress\(ctx context\.Context, bundle \*connBundle\) error \{.*?\n\}'
    replacement=r'''func (c *MasqueClient) probeInternationalEgress(ctx context.Context, bundle *connBundle) error {
	if bundle == nil || bundle.h3Client == nil {
		return errors.New("international egress probe: bundle not ready")
	}
	var errs []string
	for _, target := range probeEgressTargets {
		probeCtx, cancel := context.WithTimeout(ctx, probeEgressTimeout)
		req := &http.Request{
			Method: "CONNECT",
			Host:   target,
			URL:    &url.URL{Scheme: "https", Host: target},
			Header: make(http.Header),
		}
		req.Header.Set("Authorization", "Bearer "+c.token)
		stream, err := bundle.h3Client.OpenRequestStream(probeCtx)
		if err != nil {
			cancel()
			errs = append(errs, fmt.Sprintf("%s open: %v", target, err))
			continue
		}
		resp, err := connectThroughEdge(stream, req, connectDeadline(probeCtx, probeEgressTimeout))
		releaseStream(stream)
		cancel()
		if err == nil && resp.StatusCode == 200 {
			return nil
		}
		if err != nil {
			errs = append(errs, fmt.Sprintf("%s: %v", target, err))
		} else {
			errs = append(errs, fmt.Sprintf("%s: status %d", target, resp.StatusCode))
		}
	}
	return fmt.Errorf("international egress probes failed: %s", strings.Join(errs, "; "))
}'''
    s=re.sub(pattern,replacement,s,count=1,flags=re.S)
    return s
edit('tunnel/client_conn.go', patch_client)

def patch_androidvpn(s):
    s=re.sub(r'// SAYEH Turbo: shared relay buffers reduce GC pressure during parallel downloads\.\nvar sayehRelayBufferPool = sync\.Pool\{New: func\(\) any \{\n\tb := make\(\[\]byte, 64\*1024\)\n\treturn &b\n\}\}\n\n','',s)
    s=s.replace('''\t\t\tbp := sayehRelayBufferPool.Get().(*[]byte)
\t\t\tbuf := *bp
\t\t\tdefer sayehRelayBufferPool.Put(bp)
\t\t\tfor {''',
                '''\t\t\tbuf := make([]byte, 32*1024)
\t\t\tfor {''')
    return s
edit('androidvpn/androidvpn.go', patch_androidvpn)

# Restore the upstream field-tested two-connection Android pool. The 1.0
# direct-link speed heuristic was not a valid proxy for tunnel capacity.
edit('core/config.go', lambda s: s.replace('TunnelConnections: 1,','TunnelConnections: 2,'))


# Hide the undocumented shared third-party front-proxy control from the SAYEH UI.
# The upstream core remains untouched for regression compatibility, but users
# cannot accidentally route their traffic through it.
def hide_front_proxy_ui(s):
    start=s.find('            {/* 百度中转保命通道（front proxy） */}')
    if start >= 0:
        end=s.find('          </div>\n        ) : (', start)
        if end > start:
            s=s[:start]+s[end:]
    return s
edit('gui/frontend/src/pages/SettingsPage.tsx', hide_front_proxy_ui)

print('SAYEH Stability 1.1 patch complete.')
