package com.sayeh.aifilm;

import android.app.Activity;
import android.app.AlertDialog;
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

public class MainActivityV52 extends Activity {

    private EditText hostInput;
    private EditText passwordInput;
    private Button connectButton;
    private Button refreshButton;
    private Button emergencyButton;
    private Button allowOnlyButton;
    private TextView statusText;
    private TextView managerText;
    private LinearLayout devicesContainer;

    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean busy;
    private Router router;
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
        try {
            if (router != null) router.close();
        } catch (Throwable ignored) {}
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = vertical();
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root);

        root.addView(text("WiFi Control CLI v5.2", 28, true));
        TextView sub = text("TP-Link TD-W8961N V4 • اتصال پایدار Telnet • Block/Unblock/Allow-List", 14, false);
        sub.setAlpha(.72f);
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

        LinearLayout topRow = horizontal();
        connectButton = button("اتصال واقعی");
        refreshButton = button("تازه‌سازی");
        topRow.addView(connectButton, weighted());
        topRow.addView(refreshButton, weighted());
        root.addView(topRow, fullWrap());

        statusText = text("رمز روتر را وارد کنید و «اتصال واقعی» را بزنید.", 15, false);
        statusText.setPadding(0, dp(10), 0, dp(8));
        root.addView(statusText);

        managerText = text("دستگاه مدیر: مشخص نشده", 14, true);
        root.addView(managerText);

        emergencyButton = button("بازکردن اضطراری همه دستگاه‌ها");
        allowOnlyButton = button("فعال‌سازی ضد QR برای دستگاه‌های مجاز");
        root.addView(emergencyButton, fullWrap());
        root.addView(allowOnlyButton, fullWrap());

        TextView title = text("دستگاه‌ها", 22, true);
        title.setPadding(0, dp(16), 0, 0);
        root.addView(title);

        TextView note = text("نسخه 5.2 یک نشست Telnet را نگه می‌دارد، فرمان درست «rt node index 1» را استفاده می‌کند و پاسخ کامل روتر را قبل از تغییر ACL می‌خواند.", 13, false);
        note.setAlpha(.65f);
        root.addView(note);

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
        setBusy(true, "در حال ایجاد اتصال پایدار Telnet…");
        final String finalHost = host;
        executor.execute(() -> {
            try {
                if (router != null) router.close();
                Router r = new Router(finalHost, password, 23, 1);
                r.connect();
                Snapshot s = r.probe();
                router = r;
                snapshot = s;
                autoDetectManager(finalHost, s);
                runOnUiThread(() -> {
                    setBusy(false, connectedMessage(s));
                    enableControls(true);
                    render(s);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    setBusy(false, friendlyError(t));
                    enableControls(false);
                });
            }
        });
    }

    private void refreshReal() {
        if (router == null || busy) return;
        setBusy(true, "در حال خواندن مستقیم دستگاه‌ها از همان نشست Telnet…");
        executor.execute(() -> {
            try {
                Snapshot s = router.probe();
                snapshot = s;
                autoDetectManager(hostInput.getText().toString().trim(), s);
                runOnUiThread(() -> {
                    setBusy(false, connectedMessage(s));
                    enableControls(true);
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
        if (managerMac != null && mac.equals(managerMac)) {
            statusText.setText("دستگاه مدیر محافظت شده و قابل قطع نیست.");
            return;
        }
        setBusy(true, block ? "در حال قطع واقعی و Verify از روتر…" : "در حال وصل‌کردن واقعی و Verify از روتر…");
        String protectedMac = managerMac;
        executor.execute(() -> {
            ActionResult result = router.setBlocked(mac, block, protectedMac, snapshot);
            runOnUiThread(() -> consume(result));
        });
    }

    private void confirmEmergency() {
        if (router == null || busy) return;
        new AlertDialog.Builder(this)
                .setTitle("بازکردن اضطراری")
                .setMessage("فیلتر MAC روی روتر خاموش و ذخیره می‌شود.")
                .setNegativeButton("لغو", null)
                .setPositiveButton("باز کن", (d, w) -> {
                    setBusy(true, "در حال خاموش‌کردن فیلتر MAC…");
                    executor.execute(() -> {
                        ActionResult result = router.disableFilter();
                        runOnUiThread(() -> consume(result));
                    });
                }).show();
    }

    private void confirmAllowOnly() {
        if (router == null || busy) return;
        if (managerMac == null || managerMac.isEmpty()) {
            statusText.setText("اول دستگاه مدیر را مشخص کنید.");
            return;
        }
        Set<String> approved = approvedMacs();
        approved.add(managerMac);
        new AlertDialog.Builder(this)
                .setTitle("فعال‌سازی ضد QR")
                .setMessage("فقط MACهای مجاز اجازه اتصال خواهند داشت.")
                .setNegativeButton("لغو", null)
                .setPositiveButton("فعال کن", (d, w) -> {
                    setBusy(true, "در حال اعمال Allow-List واقعی…");
                    String protectedMac = managerMac;
                    executor.execute(() -> {
                        ActionResult result = router.enableAllowOnly(approved, protectedMac);
                        runOnUiThread(() -> consume(result));
                    });
                }).show();
    }

    private void consume(ActionResult result) {
        if (result.snapshot != null) snapshot = result.snapshot;
        setBusy(false, result.message);
        if (result.snapshot != null) render(result.snapshot);
    }

    private void enableControls(boolean enabled) {
        refreshButton.setEnabled(enabled);
        emergencyButton.setEnabled(enabled);
        allowOnlyButton.setEnabled(enabled);
    }

    private void render(Snapshot s) {
        devicesContainer.removeAllViews();
        managerText.setText(managerMac == null
                ? "دستگاه مدیر: مشخص نشده — یکی را مدیر تعیین کنید."
                : "دستگاه مدیر (محافظت‌شده): " + managerMac);

        List<Device> list = new ArrayList<>(s.devices);
        list.sort(Comparator
                .comparing((Device d) -> managerMac == null || !normalizeMac(d.mac).equals(managerMac))
                .thenComparing(d -> !d.online)
                .thenComparing(d -> aliasFor(d.mac)));

        if (list.isEmpty()) {
            devicesContainer.addView(text("هیچ دستگاه Wi-Fi از CLI گزارش نشد.", 15, false));
            return;
        }
        for (Device d : list) devicesContainer.addView(deviceCard(d, s));
    }

    private View deviceCard(Device d, Snapshot s) {
        String mac = normalizeMac(d.mac);
        boolean isManager = managerMac != null && mac.equals(managerMac);
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
        String head = isManager ? (alias.isEmpty() ? "مدیر" : alias) + " • مدیر"
                : !alias.isEmpty() ? alias
                : blocked ? "دستگاه مسدود"
                : d.online ? "دستگاه متصل" : "دستگاه آفلاین";
        card.addView(text(head, 18, true));

        String state = isManager ? "محافظت‌شده" : blocked ? "BLOCK واقعی" : d.online ? "آنلاین" : "آفلاین";
        TextView info = text(state + "\nMAC: " + mac
                + (d.ip == null ? "" : "\nIP: " + d.ip)
                + (d.signalDbm == null ? "" : "\nSignal: " + d.signalDbm + " dBm"), 13, false);
        info.setTextDirection(View.TEXT_DIRECTION_LTR);
        info.setGravity(Gravity.START);
        card.addView(info);

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
        String mode = s.policy == 0 ? "فیلتر خاموش" : s.policy == 1 ? "Allow-List" : "Block-List";
        return "اتصال پایدار برقرار شد • " + s.devices.size() + " دستگاه • " + mode;
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        statusText.setText(message);
        connectButton.setEnabled(!value);
        if (value) {
            refreshButton.setEnabled(false);
            emergencyButton.setEnabled(false);
            allowOnlyButton.setEnabled(false);
        } else if (router != null) {
            enableControls(true);
        }
    }

    private String friendlyError(Throwable t) {
        String m = clean(t);
        String l = m.toLowerCase(Locale.US);
        if (l.contains("password") || l.contains("login")) return "رمز مدیریت روتر پذیرفته نشد. جزئیات: " + m;
        if (l.contains("refused")) return "Telnet موقتاً اتصال را رد کرد؛ نسخه 5.2 چند بار خودکار تلاش می‌کند. جزئیات: " + m;
        if (l.contains("timeout")) return "روتر پاسخ کامل نداد. جزئیات: " + m;
        return m.isEmpty() ? "ارتباط CLI ناموفق بود." : m;
    }

    private String aliasFor(String mac) {
        return prefs.getString("alias_" + normalizeMac(mac), "").trim();
    }

    private boolean isApproved(String mac) {
        return prefs.getBoolean("approved_" + normalizeMac(mac), false);
    }

    private void setApproved(String mac, boolean approved) {
        prefs.edit().putBoolean("approved_" + normalizeMac(mac), approved).apply();
    }

    private Set<String> approvedMacs() {
        Set<String> out = new LinkedHashSet<>();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (!e.getKey().startsWith("approved_")) continue;
            if (!(e.getValue() instanceof Boolean) || !((Boolean) e.getValue())) continue;
            String mac = normalizeMac(e.getKey().substring("approved_".length()));
            if (Router.MAC.matcher(mac).matches()) out.add(mac);
        }
        return out;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
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

    private LinearLayout.LayoutParams fullWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String normalizeMac(String mac) {
        return mac == null ? "" : mac.trim().toLowerCase(Locale.US);
    }

    private static String clean(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m.trim();
    }

    static class Device {
        String mac;
        String ip;
        Integer signalDbm;
        boolean online;

        Device(String mac, String ip, Integer signalDbm, boolean online) {
            this.mac = mac;
            this.ip = ip;
            this.signalDbm = signalDbm;
            this.online = online;
        }
    }

    static class Snapshot {
        List<Device> devices = new ArrayList<>();
        int policy;
        Set<String> acl = new LinkedHashSet<>();
    }

    static class ActionResult {
        boolean success;
        String message;
        Snapshot snapshot;

        ActionResult(boolean success, String message, Snapshot snapshot) {
            this.success = success;
            this.message = message;
            this.snapshot = snapshot;
        }
    }

    static class Router implements AutoCloseable {
        static final Pattern MAC = Pattern.compile("(?i)([0-9a-f]{2}(?::[0-9a-f]{2}){5})");
        private static final Pattern IP = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3})");
        private static final Pattern RSSI = Pattern.compile("(-?\\d+)/(-?\\d+)/(-?\\d+)");
        private static final Pattern POLICY = Pattern.compile("(?i)WLAN\\s+policy\\([^\\r\\n]*\\)\\s*:\\s*([0-2])");
        private static final Pattern POLICY_ALT = Pattern.compile("(?i)AccessControlList\\.Policy[^\\r\\n]*:\\s*([0-2])");
        private static final Pattern ACL_BLOCK = Pattern.compile("(?is)WLAN\\s+AccessControlList\\s*:\\s*(.*?)(?:\\r?\\n\\s*WLAN\\s+wpapsk|\\r?\\n\\s*WLAN\\s+StaIdleTimeout|$)");

        final String host;
        final String password;
        final int port;
        final int node;
        private Session session;

        Router(String host, String password, int port, int node) {
            this.host = host;
            this.password = password;
            this.port = port;
            this.node = node;
        }

        synchronized void connect() throws Exception {
            ensureSession();
            selectWlan();
        }

        synchronized Snapshot probe() throws Exception {
            Exception last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    ensureSession();
                    selectWlan();
                    String wireless = run("read clients", "rtwlan showmactable", 9000, false);
                    String arp = run("read ARP", "ip arp status", 9000, false);
                    String nodeText = run("read WLAN config", "rt node display", 10000, false);
                    return parse(wireless, arp, nodeText);
                } catch (Exception e) {
                    last = e;
                    closeSession(false);
                    if (attempt < 2) Thread.sleep(700L + attempt * 600L);
                }
            }
            throw last == null ? new Exception("probe failed") : last;
        }

        synchronized ActionResult setBlocked(String mac, boolean blocked, String protectedMac, Snapshot known) {
            try {
                String target = normalizeMac(mac);
                String manager = normalizeMac(protectedMac);
                Snapshot before = probe();
                if (before.policy == 1) {
                    return new ActionResult(false, "Allow-List فعال است؛ اول «بازکردن اضطراری» را بزنید.", before);
                }
                Set<String> desired = new LinkedHashSet<>();
                if (before.policy == 2) desired.addAll(before.acl);
                if (!manager.isEmpty()) desired.remove(manager);
                if (blocked) desired.add(target); else desired.remove(target);
                if (!manager.isEmpty()) desired.remove(manager);
                return applyPolicy(desired.isEmpty() ? 0 : 2, desired, manager);
            } catch (Throwable t) {
                return new ActionResult(false, "Block/Unblock ناموفق: " + clean(t), null);
            }
        }

        synchronized ActionResult enableAllowOnly(Set<String> allowedMacs, String managerMac) {
            try {
                String manager = normalizeMac(managerMac);
                Set<String> desired = new LinkedHashSet<>();
                for (String m : allowedMacs) {
                    String n = normalizeMac(m);
                    if (MAC.matcher(n).matches()) desired.add(n);
                }
                if (!manager.isEmpty()) desired.add(manager);
                if (desired.isEmpty()) return new ActionResult(false, "هیچ دستگاه مجازی انتخاب نشده است.", null);
                return applyPolicy(1, desired, manager);
            } catch (Throwable t) {
                return new ActionResult(false, "Allow-List ناموفق: " + clean(t), null);
            }
        }

        synchronized ActionResult disableFilter() {
            try {
                return applyPolicy(0, new LinkedHashSet<>(), "");
            } catch (Throwable t) {
                return new ActionResult(false, "خاموش‌کردن فیلتر ناموفق: " + clean(t), null);
            }
        }

        private ActionResult applyPolicy(int policy, Set<String> acl, String protectedMac) throws Exception {
            Set<String> desired = new LinkedHashSet<>();
            for (String m : acl) {
                String n = normalizeMac(m);
                if (MAC.matcher(n).matches()) desired.add(n);
            }
            String manager = normalizeMac(protectedMac);
            if (policy == 2 && !manager.isEmpty()) desired.remove(manager);
            if (policy == 1 && !manager.isEmpty()) desired.add(manager);

            ensureSession();
            selectWlan();
            run("disable filter before ACL replace", "rt node accesspolicy 0", 7000, false);
            String aclCommand = desired.isEmpty()
                    ? "rt node acladdentry \"\""
                    : "rt node acladdentry " + String.join(";", desired);
            run("replace ACL", aclCommand, 8000, false);
            run("apply access policy", "rt node accesspolicy " + policy, 8000, false);
            run("save WLAN config", "rt node save", 11000, true);

            // Some TrendChip telnetd builds keep only one session slot. Release it cleanly after save,
            // then reconnect with backoff for verification instead of opening rapid parallel sessions.
            closeSession(true);
            Thread.sleep(1500);

            Snapshot after = probeRetry();
            boolean ok = after.policy == policy;
            if (policy == 0) ok = ok && after.acl.isEmpty();
            else ok = ok && after.acl.equals(desired);

            if (ok) {
                String msg = policy == 0
                        ? "فیلتر MAC خاموش و از خود روتر Verify شد."
                        : policy == 1
                        ? "Allow-List واقعی فعال و Verify شد."
                        : "قطع/وصل واقعی اعمال و ACL از خود روتر Verify شد.";
                return new ActionResult(true, msg, after);
            }

            return new ActionResult(false,
                    "فرمان اجرا شد اما Verify نهایی برابر نبود. policy=" + after.policy + " acl=" + after.acl,
                    after);
        }

        private Snapshot probeRetry() throws Exception {
            Exception last = null;
            for (int i = 0; i < 6; i++) {
                try {
                    return probe();
                } catch (Exception e) {
                    last = e;
                    Thread.sleep(900L + i * 550L);
                }
            }
            throw last == null ? new Exception("verify timeout") : last;
        }

        private void ensureSession() throws Exception {
            if (session != null && session.isConnected()) return;
            closeSession(false);
            session = new Session(host, port, password);
            session.openWithRetry();
        }

        private void selectWlan() throws Exception {
            // TD-W8961N/TrendChip CLI uses "rt node index", not "rtwlan node index".
            run("select WLAN node", "rt node index " + node, 7000, false);
        }

        private String run(String stage, String command, long timeout, boolean allowClose) throws Exception {
            try {
                if (session == null || !session.isConnected()) ensureSession();
                return session.command(command, timeout, allowClose);
            } catch (Exception e) {
                String m = clean(e);
                throw new Exception(stage + " | " + command + " | " + m, e);
            }
        }

        private Snapshot parse(String wireless, String arp, String nodeText) {
            Map<String, String> ipByMac = new LinkedHashMap<>();
            for (String line : arp.split("\\r?\\n")) {
                Matcher mm = MAC.matcher(line);
                Matcher im = IP.matcher(line);
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
                Integer signal = null;
                Matcher rm = RSSI.matcher(line);
                if (rm.find()) {
                    try { signal = Integer.parseInt(rm.group(1)); } catch (Throwable ignored) {}
                }
                devices.put(mac, new Device(mac, ipByMac.get(mac), signal, true));
            }

            Snapshot out = new Snapshot();
            Matcher pm = POLICY.matcher(nodeText);
            if (pm.find()) out.policy = Integer.parseInt(pm.group(1));
            else {
                Matcher alt = POLICY_ALT.matcher(nodeText);
                out.policy = alt.find() ? Integer.parseInt(alt.group(1)) : 0;
            }

            Matcher ab = ACL_BLOCK.matcher(nodeText);
            String aclText = ab.find() ? ab.group(1) : "";
            Matcher am = MAC.matcher(aclText);
            while (am.find()) out.acl.add(normalizeMac(am.group(1)));

            if (out.policy != 0) {
                for (String mac : out.acl) {
                    if (!devices.containsKey(mac)) devices.put(mac, new Device(mac, ipByMac.get(mac), null, false));
                }
            }
            out.devices.addAll(devices.values());
            return out;
        }

        private void closeSession(boolean graceful) {
            if (session == null) return;
            try {
                if (graceful) session.closeGracefully(); else session.close();
            } catch (Throwable ignored) {}
            session = null;
        }

        @Override
        public synchronized void close() {
            closeSession(true);
        }
    }

    static class Session implements AutoCloseable {
        private static final Pattern PROMPT = Pattern.compile("(?m)(?:^|\\r?\\n)\\s*[A-Za-z0-9._-]+>\\s*$");
        final String host;
        final int port;
        final String password;
        TelnetClient telnet;
        BufferedInputStream input;
        BufferedOutputStream output;

        Session(String host, int port, String password) {
            this.host = host;
            this.port = port;
            this.password = password;
        }

        boolean isConnected() {
            return telnet != null && telnet.isConnected();
        }

        void openWithRetry() throws Exception {
            Exception last = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                try {
                    telnet = new TelnetClient();
                    telnet.setConnectTimeout(6000);
                    telnet.setDefaultTimeout(6000);
                    telnet.connect(host, port);
                    input = new BufferedInputStream(telnet.getInputStream());
                    output = new BufferedOutputStream(telnet.getOutputStream());
                    login();
                    return;
                } catch (Exception e) {
                    last = e;
                    try { close(); } catch (Throwable ignored) {}
                    if (attempt < 7) Thread.sleep(500L + attempt * 450L);
                }
            }
            throw last == null ? new Exception("Telnet connect failed") : last;
        }

        private void login() throws Exception {
            String hello = readLogin(8000);
            String lower = hello.toLowerCase(Locale.US);
            if (lower.contains("password:")) {
                send(password);
                String reply = readLogin(8000);
                String rl = reply.toLowerCase(Locale.US);
                if (rl.contains("password:") || !hasPrompt(reply)) throw new Exception("Password/login rejected");
            } else if (!hasPrompt(hello)) {
                throw new Exception("Login timeout");
            }
        }

        String command(String command, long timeout, boolean allowClose) throws Exception {
            drain();
            send(command);
            return readCommand(timeout, allowClose);
        }

        private void send(String value) throws Exception {
            output.write((value + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        private void drain() {
            if (input == null) return;
            try {
                while (input.available() > 0) {
                    byte[] b = new byte[Math.min(input.available(), 4096)];
                    int n = input.read(b);
                    if (n <= 0) break;
                }
            } catch (Throwable ignored) {}
        }

        private String readLogin(long timeout) throws Exception {
            long end = System.currentTimeMillis() + timeout;
            StringBuilder sb = new StringBuilder();
            while (System.currentTimeMillis() < end) {
                int available = input.available();
                if (available > 0) {
                    byte[] buf = new byte[Math.min(available, 4096)];
                    int n = input.read(buf);
                    if (n < 0) break;
                    if (n > 0) {
                        sb.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                        String txt = sb.toString();
                        if (txt.toLowerCase(Locale.US).contains("password:") || hasPrompt(txt)) return txt;
                    }
                } else {
                    Thread.sleep(30);
                }
            }
            throw new Exception("Login timeout");
        }

        private String readCommand(long timeout, boolean allowClose) throws Exception {
            long end = System.currentTimeMillis() + timeout;
            long lastData = System.currentTimeMillis();
            boolean gotData = false;
            StringBuilder sb = new StringBuilder();

            while (System.currentTimeMillis() < end) {
                int available;
                try {
                    available = input.available();
                } catch (Throwable t) {
                    if (allowClose) return sb.toString();
                    throw t;
                }
                if (available > 0) {
                    byte[] buf = new byte[Math.min(available, 4096)];
                    int n;
                    try {
                        n = input.read(buf);
                    } catch (Throwable t) {
                        if (allowClose) return sb.toString();
                        throw t;
                    }
                    if (n < 0) {
                        if (allowClose) return sb.toString();
                        throw new Exception("CLI stream closed");
                    }
                    if (n > 0) {
                        gotData = true;
                        lastData = System.currentTimeMillis();
                        sb.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                        if (hasPrompt(sb.toString())) return sb.toString();
                    }
                } else {
                    long idle = System.currentTimeMillis() - lastData;
                    // Only SAVE is allowed to complete without a prompt. Normal display/state commands
                    // always wait for the prompt so a split response is never parsed as complete.
                    if (allowClose && gotData && idle > 1200) return sb.toString();
                    Thread.sleep(30);
                }
            }
            if (allowClose) return sb.toString();
            throw new Exception("CLI timeout");
        }

        private boolean hasPrompt(String text) {
            return text.contains("TP-LINK>") || PROMPT.matcher(text).find();
        }

        void closeGracefully() {
            try {
                if (isConnected() && output != null) {
                    output.write("exit\r\n".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    Thread.sleep(120);
                }
            } catch (Throwable ignored) {}
            close();
        }

        @Override
        public void close() {
            try {
                if (telnet != null && telnet.isConnected()) telnet.disconnect();
            } catch (Throwable ignored) {}
            telnet = null;
            input = null;
            output = null;
        }
    }
}
