package com.sayeh.aifilm;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.apache.commons.net.telnet.TelnetClient;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private EditText hostInput, passwordInput;
    private Button connectButton, refreshButton, emergencyButton, allowOnlyButton;
    private TextView statusText, managerText;
    private LinearLayout devicesContainer;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean busy = false;
    private TelnetRouter router;
    private Snapshot snapshot;
    private String managerMac;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("wifi_cli_v5", Context.MODE_PRIVATE);
        managerMac = normalizeMac(prefs.getString("manager_mac", ""));
        if (managerMac.isEmpty()) managerMac = null;
        buildUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = vertical();
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root);

        TextView title = text("WiFi Control CLI v5", 28, true);
        root.addView(title);
        TextView sub = text("TP-Link TD-W8961N V4 • Telnet CLI واقعی • Block/Unblock تاییدشده", 14, false);
        sub.setAlpha(.7f);
        root.addView(sub);

        hostInput = new EditText(this);
        hostInput.setHint("IP روتر");
        hostInput.setText(prefs.getString("router_host", "192.168.1.1"));
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        root.addView(hostInput, fullWrap());

        passwordInput = new EditText(this);
        passwordInput.setHint("رمز مدیریت روتر");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        root.addView(passwordInput, fullWrap());

        LinearLayout row = horizontal();
        connectButton = button("اتصال واقعی");
        refreshButton = button("تازه‌سازی");
        row.addView(connectButton, weighted());
        row.addView(refreshButton, weighted());
        root.addView(row, fullWrap());

        statusText = text("رمز روتر را وارد کنید و «اتصال واقعی» را بزنید.", 15, false);
        statusText.setPadding(0, dp(10), 0, dp(6));
        root.addView(statusText);

        managerText = text("دستگاه مدیر: مشخص نشده", 14, true);
        root.addView(managerText);

        emergencyButton = button("بازکردن اضطراری همه دستگاه‌ها");
        allowOnlyButton = button("فعال‌سازی ضد QR برای دستگاه‌های مجاز");
        root.addView(emergencyButton, fullWrap());
        root.addView(allowOnlyButton, fullWrap());

        TextView devicesTitle = text("دستگاه‌ها", 22, true);
        devicesTitle.setPadding(0, dp(16), 0, 0);
        root.addView(devicesTitle);

        TextView help = text("نام‌گذاری، محافظت مدیر، قطع/وصل واقعی و Allow‑List فقط با فرمان‌های CLI تاییدشده.", 13, false);
        help.setAlpha(.65f);
        root.addView(help);

        devicesContainer = vertical();
        root.addView(devicesContainer, fullWrap());

        connectButton.setOnClickListener(v -> connectReal());
        refreshButton.setOnClickListener(v -> refreshReal());
        emergencyButton.setOnClickListener(v -> confirmEmergency());
        allowOnlyButton.setOnClickListener(v -> confirmAllowOnly());

        refreshButton.setEnabled(false);
        emergencyButton.setEnabled(false);
        allowOnlyButton.setEnabled(false);
        setContentView(scroll);
    }

    private void connectReal() {
        if (busy) return;
        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) host = "192.168.1.1";
        String password = passwordInput.getText().toString();
        if (password.isEmpty()) {
            statusText.setText("رمز مدیریت روتر را وارد کنید.");
            return;
        }
        prefs.edit().putString("router_host", host).apply();
        router = new TelnetRouter(host, password, 23, 1);
        setBusy(true, "در حال اتصال مستقیم به Telnet/CLI واقعی روتر…");
        String finalHost = host;
        executor.execute(() -> {
            try {
                Snapshot s = router.probe();
                snapshot = s;
                autoDetectManager(finalHost, s);
                runOnUiThread(() -> {
                    setBusy(false, connectedMessage(s));
                    refreshButton.setEnabled(true);
                    emergencyButton.setEnabled(true);
                    allowOnlyButton.setEnabled(true);
                    render(s);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    setBusy(false, friendlyError(t));
                    refreshButton.setEnabled(false);
                    emergencyButton.setEnabled(false);
                    allowOnlyButton.setEnabled(false);
                });
            }
        });
    }

    private void refreshReal() {
        if (router == null || busy) return;
        setBusy(true, "در حال خواندن مستقیم دستگاه‌ها از چیپ Wi‑Fi…");
        executor.execute(() -> {
            try {
                Snapshot s = router.probe();
                snapshot = s;
                autoDetectManager(hostInput.getText().toString().trim(), s);
                runOnUiThread(() -> {
                    setBusy(false, connectedMessage(s));
                    render(s);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> setBusy(false, friendlyError(t)));
            }
        });
    }

    private void runBlock(Device d, boolean block) {
        if (router == null || busy) return;
        String mac = normalizeMac(d.mac);
        if (mac.equals(managerMac)) {
            statusText.setText("دستگاه مدیر محافظت شده و قابل Block نیست.");
            return;
        }
        setBusy(true, block ? "در حال Block واقعی و Verify…" : "در حال Unblock واقعی و Verify…");
        executor.execute(() -> {
            ActionResult result = router.setBlocked(mac, block);
            runOnUiThread(() -> consume(result));
        });
    }

    private void confirmEmergency() {
        if (router == null || busy) return;
        new AlertDialog.Builder(this)
                .setTitle("بازکردن اضطراری")
                .setMessage("فیلتر MAC روی خود روتر خاموش و ذخیره می‌شود تا همه دوباره امکان اتصال داشته باشند.")
                .setNegativeButton("لغو", null)
                .setPositiveButton("باز کن", (d, w) -> {
                    setBusy(true, "در حال خاموش‌کردن فیلتر MAC و Verify…");
                    executor.execute(() -> {
                        ActionResult result = router.disableMacFilter();
                        runOnUiThread(() -> consume(result));
                    });
                }).show();
    }

    private void confirmAllowOnly() {
        if (router == null || busy) return;
        if (managerMac == null || managerMac.isEmpty()) {
            statusText.setText("اول دستگاه مدیر را مشخص کنید؛ Allow‑List بدون محافظت مدیر فعال نمی‌شود.");
            return;
        }
        Set<String> approved = approvedMacs();
        approved.add(managerMac);
        new AlertDialog.Builder(this)
                .setTitle("فعال‌سازی ضد QR")
                .setMessage("فقط دستگاه‌های تیک‌شده اجازه اتصال خواهند داشت. دستگاه جدید حتی با داشتن رمز Wi‑Fi رد می‌شود.")
                .setNegativeButton("لغو", null)
                .setPositiveButton("فعال کن", (d, w) -> {
                    setBusy(true, "در حال اعمال Allow‑List واقعی و Verify…");
                    executor.execute(() -> {
                        ActionResult result = router.enableAllowOnly(approved, managerMac);
                        runOnUiThread(() -> consume(result));
                    });
                }).show();
    }

    private void consume(ActionResult r) {
        if (r.snapshot != null) snapshot = r.snapshot;
        setBusy(false, r.message);
        if (r.snapshot != null) render(r.snapshot);
    }

    private void render(Snapshot s) {
        devicesContainer.removeAllViews();
        managerText.setText(managerMac == null ?
                "دستگاه مدیر: مشخص نشده — یکی را به عنوان مدیر تعیین کنید." :
                "دستگاه مدیر (محافظت‌شده): " + managerMac);

        List<Device> list = new ArrayList<>(s.devices);
        list.sort(Comparator
                .comparing((Device d) -> !normalizeMac(d.mac).equals(managerMac))
                .thenComparing(d -> !d.online)
                .thenComparing(d -> aliasFor(d.mac)));

        if (list.isEmpty()) {
            devicesContainer.addView(text("هیچ دستگاه Wi‑Fi از CLI گزارش نشد.", 15, false));
            return;
        }
        for (Device d : list) devicesContainer.addView(deviceCard(d, s));
    }

    private View deviceCard(Device d, Snapshot s) {
        String mac = normalizeMac(d.mac);
        boolean isManager = mac.equals(managerMac);
        boolean blocked = s.policy == 2 && s.acl.contains(mac);

        LinearLayout card = vertical();
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cp = fullWrap();
        cp.setMargins(0, dp(6), 0, dp(6));
        card.setLayoutParams(cp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(247, 247, 247));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.rgb(220, 220, 220));
        card.setBackground(bg);

        String alias = aliasFor(mac);
        String head = isManager ? (alias.isEmpty() ? "مدیر" : alias) + " • مدیر" :
                (!alias.isEmpty() ? alias : (d.online ? "دستگاه متصل" : "دستگاه آفلاین/مسدود"));
        card.addView(text(head, 18, true));

        String state = isManager ? "محافظت‌شده" : blocked ? "BLOCK واقعی" : d.online ? "آنلاین" : "آفلاین";
        String info = state + "\nMAC: " + mac + (d.ip == null ? "" : "\nIP: " + d.ip) +
                (d.signalDbm == null ? "" : "\nSignal: " + d.signalDbm + " dBm");
        TextView infoView = text(info, 13, false);
        infoView.setTextDirection(View.TEXT_DIRECTION_LTR);
        infoView.setGravity(Gravity.START);
        card.addView(infoView);

        LinearLayout aliasRow = horizontal();
        EditText aliasInput = new EditText(this);
        aliasInput.setHint("نام دستگاه/محصل");
        aliasInput.setText(alias);
        Button saveAlias = button("ثبت نام");
        saveAlias.setOnClickListener(v -> {
            prefs.edit().putString("alias_" + mac, aliasInput.getText().toString().trim()).apply();
            if (snapshot != null) render(snapshot);
        });
        aliasRow.addView(saveAlias, wrapWrap());
        aliasRow.addView(aliasInput, weighted());
        card.addView(aliasRow, fullWrap());

        CheckBox approved = new CheckBox(this);
        approved.setText("مجاز در حالت ضد QR");
        approved.setChecked(isApproved(mac) || isManager);
        approved.setEnabled(!isManager);
        approved.setOnCheckedChangeListener((b, checked) -> setApproved(mac, checked));
        card.addView(approved);

        Button managerButton = button(isManager ? "این دستگاه مدیر است ✓" : "این دستگاه مدیر است");
        managerButton.setEnabled(!isManager);
        managerButton.setOnClickListener(v -> {
            managerMac = mac;
            prefs.edit().putString("manager_mac", mac).apply();
            setApproved(mac, true);
            if (snapshot != null) render(snapshot);
        });
        card.addView(managerButton, fullWrap());

        Button action = button(blocked ? "وصل کردن واقعی" : "قطع کردن واقعی");
        action.setEnabled(!isManager && s.policy != 1 && !busy);
        action.setOnClickListener(v -> runBlock(d, !blocked));
        card.addView(action, fullWrap());

        return card;
    }

    private void autoDetectManager(String host, Snapshot s) {
        if (managerMac != null) {
            for (Device d : s.devices) if (normalizeMac(d.mac).equals(managerMac)) return;
        }
        String local = localIpv4(host);
        if (local == null) return;
        for (Device d : s.devices) {
            if (local.equals(d.ip)) {
                managerMac = normalizeMac(d.mac);
                prefs.edit().putString("manager_mac", managerMac).apply();
                setApproved(managerMac, true);
                return;
            }
        }
    }

    private String localIpv4(String host) {
        String prefix = host.contains(".") ? host.substring(0, host.lastIndexOf('.')) + "." : "";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address) {
                        String ip = a.getHostAddress();
                        if (!prefix.isEmpty() && ip.startsWith(prefix)) return ip;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String connectedMessage(Snapshot s) {
        int online = 0;
        for (Device d : s.devices) if (d.online) online++;
        String mode = s.policy == 0 ? "MAC Filter خاموش" : s.policy == 1 ? "Allow‑List فعال" : "Reject/Block فعال (" + s.acl.size() + ")";
        return "اتصال CLI موفق • " + online + " دستگاه آنلاین • " + mode;
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        statusText.setText(message);
        connectButton.setEnabled(!value);
        boolean connected = router != null;
        refreshButton.setEnabled(!value && connected);
        emergencyButton.setEnabled(!value && connected);
        allowOnlyButton.setEnabled(!value && connected);
    }

    private String aliasFor(String mac) {
        return prefs.getString("alias_" + normalizeMac(mac), "");
    }

    private Set<String> approvedMacs() {
        Set<String> raw = prefs.getStringSet("approved_macs", Collections.emptySet());
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw) out.add(normalizeMac(s));
        return out;
    }

    private boolean isApproved(String mac) { return approvedMacs().contains(normalizeMac(mac)); }

    private void setApproved(String mac, boolean value) {
        Set<String> set = approvedMacs();
        String m = normalizeMac(mac);
        if (value) set.add(m); else set.remove(m);
        prefs.edit().putStringSet("approved_macs", set).apply();
    }

    private String friendlyError(Throwable t) {
        String m = t.getMessage() == null ? "" : t.getMessage();
        String l = m.toLowerCase(Locale.US);
        if (l.contains("refused")) return "Telnet روتر اتصال را رد کرد. به Wi‑Fi همین روتر وصل شوید.";
        if (l.contains("password") || l.contains("login")) return "رمز مدیریت روتر پذیرفته نشد.";
        if (l.contains("timeout")) return "پاسخ CLI روتر Timeout شد. دوباره تلاش کنید.";
        return m.isEmpty() ? "اتصال CLI ناموفق بود." : "خطای CLI: " + m;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return l;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(28, 28, 28));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams fullWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams wrapWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String normalizeMac(String mac) {
        if (mac == null) return "";
        return mac.trim().toLowerCase(Locale.US);
    }

    // ---------- Verified TD-W8961N V4 Telnet/CLI engine ----------

    static class Device {
        String mac;
        String ip;
        Integer signalDbm;
        boolean online;
        Device(String mac, String ip, Integer signalDbm, boolean online) {
            this.mac = mac; this.ip = ip; this.signalDbm = signalDbm; this.online = online;
        }
    }

    static class Snapshot {
        List<Device> devices = new ArrayList<>();
        int policy;
        Set<String> acl = new LinkedHashSet<>();
        String rawWireless, rawArp, rawNode;
    }

    static class ActionResult {
        boolean success;
        String message;
        Snapshot snapshot;
        ActionResult(boolean success, String message, Snapshot snapshot) {
            this.success = success; this.message = message; this.snapshot = snapshot;
        }
    }

    static class TelnetRouter {
        private static final Pattern MAC = Pattern.compile("(?i)([0-9a-f]{2}(?::[0-9a-f]{2}){5})");
        private static final Pattern IP = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3})");
        private static final Pattern RSSI = Pattern.compile("(-?\\d+)/(-?\\d+)/(-?\\d+)");
        private static final Pattern POLICY = Pattern.compile("(?i)WLAN\\s+policy\\([^)]*\\)\\s*:\\s*([0-2])");

        final String host, password;
        final int port, node;

        TelnetRouter(String host, String password, int port, int node) {
            this.host = host; this.password = password; this.port = port; this.node = node;
        }

        Snapshot probe() throws Exception {
            try (Session s = new Session(host, port, password)) {
                s.open();
                s.command("rtwlan node index " + node, 5000, false);
                String wireless = s.command("rtwlan showmactable", 7000, false);
                String arp = s.command("ip arp status", 7000, false);
                String nodeText = s.command("rt node display", 7000, false);
                return parse(wireless, arp, nodeText);
            }
        }

        ActionResult setBlocked(String mac, boolean blocked) {
            try {
                String target = normalizeMac(mac);
                Snapshot before = probe();
                if (before.policy == 1) return new ActionResult(false, "Allow‑List فعال است؛ اول آن را با «بازکردن اضطراری» خاموش کنید.", before);

                Set<String> desired = new LinkedHashSet<>();
                if (blocked) {
                    if (before.policy == 2) desired.addAll(before.acl);
                    desired.add(target);
                } else if (before.policy == 2) {
                    desired.addAll(before.acl);
                    desired.remove(target);
                }
                return applyPolicy(desired.isEmpty() ? 0 : 2, desired);
            } catch (Throwable t) {
                return new ActionResult(false, clean(t), null);
            }
        }

        ActionResult enableAllowOnly(Set<String> allowedMacs, String manager) {
            try {
                Set<String> desired = new LinkedHashSet<>();
                for (String m : allowedMacs) desired.add(normalizeMac(m));
                desired.add(normalizeMac(manager));
                return applyPolicy(1, desired);
            } catch (Throwable t) {
                return new ActionResult(false, clean(t), null);
            }
        }

        ActionResult disableMacFilter() {
            try { return applyPolicy(0, Collections.emptySet()); }
            catch (Throwable t) { return new ActionResult(false, clean(t), null); }
        }

        private ActionResult applyPolicy(int policy, Set<String> acl) throws Exception {
            Set<String> desired = new LinkedHashSet<>();
            for (String m : acl) if (MAC.matcher(normalizeMac(m)).matches()) desired.add(normalizeMac(m));

            try (Session s = new Session(host, port, password)) {
                s.open();
                s.command("rtwlan node index " + node, 5000, false);
                if (policy != 0 && !desired.isEmpty()) {
                    String joined = String.join(",", desired);
                    s.command("rt node acladdentry " + joined, 5000, false);
                }
                s.command("rt node accesspolicy " + policy, 5000, false);
                s.command("rt node display", 5000, false);
                s.command("rt node save", 6000, true);
            }

            Thread.sleep(2000);
            Snapshot after = probeRetry();
            boolean ok = after.policy == policy;
            if (policy != 0) ok = ok && after.acl.containsAll(desired);
            if (ok) {
                String msg = policy == 0 ? "فیلتر MAC خاموش شد؛ اتصال همه آزاد است." :
                        policy == 1 ? "Allow‑List واقعی فعال و Verify شد." :
                                "Block/Unblock واقعی روی خود روتر اعمال و Verify شد.";
                return new ActionResult(true, msg, after);
            }

            // Fail-safe: if verification fails, reopen the network instead of leaving an uncertain filter state.
            try { forceDisable(); } catch (Throwable ignored) {}
            Snapshot safe = null;
            try { safe = probeRetry(); } catch (Throwable ignored) {}
            return new ActionResult(false, "Verify نهایی موفق نبود؛ برای ایمنی فیلتر MAC خاموش شد.", safe);
        }

        private void forceDisable() throws Exception {
            try (Session s = new Session(host, port, password)) {
                s.open();
                s.command("rtwlan node index " + node, 5000, false);
                s.command("rt node accesspolicy 0", 5000, false);
                s.command("rt node save", 6000, true);
            }
            Thread.sleep(1500);
        }

        private Snapshot probeRetry() throws Exception {
            Exception last = null;
            for (int i = 0; i < 4; i++) {
                try { return probe(); }
                catch (Exception e) { last = e; Thread.sleep(900L + (i * 450L)); }
            }
            throw last == null ? new Exception("Verify timeout") : last;
        }

        private Snapshot parse(String wireless, String arp, String nodeText) {
            Map<String, String> ipByMac = new LinkedHashMap<>();
            for (String line : arp.split("\\r?\\n")) {
                Matcher mm = MAC.matcher(line); Matcher im = IP.matcher(line);
                if (mm.find() && im.find()) {
                    String mac = normalizeMac(mm.group(1));
                    if (!"ff:ff:ff:ff:ff:ff".equals(mac)) ipByMac.put(mac, im.group(1));
                }
            }

            Map<String, Device> devices = new LinkedHashMap<>();
            for (String line : wireless.split("\\r?\\n")) {
                Matcher mm = MAC.matcher(line);
                if (!mm.find()) continue;
                String mac = normalizeMac(mm.group(1));
                if ("ff:ff:ff:ff:ff:ff".equals(mac)) continue;
                if (!line.trim().toLowerCase(Locale.US).startsWith(mac)) continue;
                Integer signal = null;
                Matcher rm = RSSI.matcher(line);
                if (rm.find()) try { signal = Integer.parseInt(rm.group(1)); } catch (Throwable ignored) {}
                devices.put(mac, new Device(mac, ipByMac.get(mac), signal, true));
            }

            Snapshot out = new Snapshot();
            out.rawWireless = wireless; out.rawArp = arp; out.rawNode = nodeText;
            Matcher pm = POLICY.matcher(nodeText);
            out.policy = pm.find() ? Integer.parseInt(pm.group(1)) : 0;
            out.acl = parseAcl(nodeText);
            if (out.policy != 0) {
                for (String mac : out.acl) if (!devices.containsKey(mac)) devices.put(mac, new Device(mac, ipByMac.get(mac), null, false));
            }
            out.devices.addAll(devices.values());
            return out;
        }

        private Set<String> parseAcl(String nodeText) {
            Set<String> out = new LinkedHashSet<>();
            String[] lines = nodeText.split("\\r?\\n");
            boolean inAcl = false;
            for (String line : lines) {
                if (line.toLowerCase(Locale.US).contains("wlan accesscontrollist")) { inAcl = true; continue; }
                if (!inAcl) continue;
                String trim = line.trim();
                if (trim.toLowerCase(Locale.US).startsWith("wlan ")) break;
                Matcher m = MAC.matcher(trim);
                if (m.find()) {
                    String mac = normalizeMac(m.group(1));
                    if (!"ff:ff:ff:ff:ff:ff".equals(mac)) out.add(mac);
                }
            }
            return out;
        }

        private String clean(Throwable t) {
            String m = t.getMessage() == null ? "" : t.getMessage();
            String l = m.toLowerCase(Locale.US);
            if (l.contains("password") || l.contains("login")) return "رمز مدیریت روتر پذیرفته نشد.";
            if (l.contains("refused")) return "اتصال Telnet رد شد.";
            if (l.contains("timeout")) return "روتر در زمان تعیین‌شده پاسخ نداد.";
            return m.isEmpty() ? "ارتباط CLI ناموفق بود." : m;
        }

        static class Session implements AutoCloseable {
            final String host, password; final int port;
            final TelnetClient telnet = new TelnetClient();
            BufferedInputStream input; BufferedOutputStream output;

            Session(String host, int port, String password) { this.host = host; this.port = port; this.password = password; }

            void open() throws Exception {
                telnet.setConnectTimeout(5000);
                telnet.setDefaultTimeout(5000);
                telnet.connect(host, port);
                try { telnet.setSoTimeout(1000); } catch (Throwable ignored) {}
                input = new BufferedInputStream(telnet.getInputStream());
                output = new BufferedOutputStream(telnet.getOutputStream());
                String hello = readUntil(new String[]{"Password:", "TP-LINK>"}, 6000, false);
                if (hello.toLowerCase(Locale.US).contains("password:")) {
                    send(password);
                    String login = readUntil(new String[]{"TP-LINK>", "Password:"}, 6000, false);
                    if (!login.contains("TP-LINK>")) throw new Exception("Password/login rejected");
                } else if (!hello.contains("TP-LINK>")) throw new Exception("Login timeout");
            }

            String command(String command, long timeout, boolean allowClose) throws Exception {
                send(command);
                return readUntil(new String[]{"TP-LINK>"}, timeout, allowClose);
            }

            void send(String s) throws Exception {
                output.write((s + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();
            }

            String readUntil(String[] needles, long timeout, boolean allowClose) throws Exception {
                long end = System.currentTimeMillis() + timeout;
                long last = System.currentTimeMillis();
                StringBuilder sb = new StringBuilder();
                while (System.currentTimeMillis() < end) {
                    int available;
                    try { available = input.available(); } catch (Throwable t) { if (allowClose) return sb.toString(); else throw t; }
                    if (available > 0) {
                        byte[] buf = new byte[Math.min(available, 4096)];
                        int n;
                        try { n = input.read(buf); } catch (Throwable t) { if (allowClose) return sb.toString(); else throw t; }
                        if (n < 0) { if (allowClose) return sb.toString(); break; }
                        if (n > 0) {
                            sb.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                            last = System.currentTimeMillis();
                            String txt = sb.toString();
                            for (String needle : needles) if (txt.toLowerCase(Locale.US).contains(needle.toLowerCase(Locale.US))) return txt;
                        }
                    } else {
                        if (allowClose && System.currentTimeMillis() - last > 900) return sb.toString();
                        Thread.sleep(35);
                    }
                }
                if (allowClose) return sb.toString();
                throw new Exception("CLI timeout");
            }

            @Override public void close() {
                try { if (telnet.isConnected()) telnet.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }
}
