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

public class MainActivityV51 extends Activity {

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
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root);

        TextView title = text("WiFi Control CLI v5.1", 28, true);
        root.addView(title);
        TextView sub = text("TP-Link TD-W8961N V4 • کنترل واقعی MAC • Block/Unblock/Allow-List", 14, false);
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

        LinearLayout row = horizontal();
        connectButton = button("اتصال واقعی");
        refreshButton = button("تازه‌سازی");
        row.addView(connectButton, weighted());
        row.addView(refreshButton, weighted());
        root.addView(row, fullWrap());

        statusText = text("رمز روتر را وارد کنید و «اتصال واقعی» را بزنید.", 15, false);
        statusText.setPadding(0, dp(10), 0, dp(8));
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

        TextView note = text("نسخه 5.1 فهرست ACL را به شکل کامل جایگزین می‌کند، از جداکننده صحیح «;» استفاده می‌کند و نتیجه را از خود روتر Verify می‌کند.", 13, false);
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
        router = new TelnetRouter(host, password, 23, 1);
        setBusy(true, "در حال اتصال مستقیم به Telnet/CLI روتر…");
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
        setBusy(true, "در حال خواندن مستقیم دستگاه‌ها از روتر…");
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
        if (managerMac != null && mac.equals(managerMac)) {
            statusText.setText("دستگاه مدیر محافظت شده و قابل قطع نیست.");
            return;
        }
        setBusy(true, block ? "در حال قطع واقعی دستگاه و Verify…" : "در حال وصل‌کردن واقعی دستگاه و Verify…");
        String protectedMac = managerMac;
        executor.execute(() -> {
            ActionResult result = router.setBlocked(mac, block, protectedMac);
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
            statusText.setText("اول دستگاه مدیر را مشخص کنید؛ Allow-List بدون محافظت مدیر فعال نمی‌شود.");
            return;
        }
        Set<String> approved = approvedMacs();
        approved.add(managerMac);
        new AlertDialog.Builder(this)
                .setTitle("فعال‌سازی ضد QR")
                .setMessage("فقط دستگاه‌های تیک‌شده اجازه اتصال خواهند داشت. دستگاه جدید حتی با داشتن رمز Wi-Fi رد می‌شود.")
                .setNegativeButton("لغو", null)
                .setPositiveButton("فعال کن", (d, w) -> {
                    setBusy(true, "در حال اعمال Allow-List واقعی و Verify…");
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

    private void render(Snapshot s) {
        devicesContainer.removeAllViews();
        managerText.setText(managerMac == null ?
                "دستگاه مدیر: مشخص نشده — یکی را به عنوان مدیر تعیین کنید." :
                "دستگاه مدیر (محافظت‌شده): " + managerMac);

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
            for (Device d : s.devices) {
                if (normalizeMac(d.mac).equals(managerMac)) return;
            }
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
        return "اتصال واقعی برقرار شد • " + s.devices.size() + " دستگاه • " + mode;
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        statusText.setText(message);
        connectButton.setEnabled(!value);
        refreshButton.setEnabled(!value && router != null);
        emergencyButton.setEnabled(!value && router != null);
        allowOnlyButton.setEnabled(!value && router != null);
    }

    private String friendlyError(Throwable t) {
        String m = t.getMessage() == null ? "" : t.getMessage();
        String l = m.toLowerCase(Locale.US);
        if (l.contains("password") || l.contains("login")) return "رمز مدیریت روتر پذیرفته نشد.";
        if (l.contains("refused")) return "اتصال Telnet رد شد.";
        if (l.contains("timeout")) return "روتر در مرحله «" + m + "» پاسخ کامل نداد.";
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
        if (snapshot == null) return out;
        for (Device d : snapshot.devices) if (isApproved(d.mac)) out.add(normalizeMac(d.mac));
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
        if (mac == null) return "";
        return mac.trim().toLowerCase(Locale.US);
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
        String rawWireless;
        String rawArp;
        String rawNode;
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

    static class TelnetRouter {
        private static final Pattern MAC = Pattern.compile("(?i)([0-9a-f]{2}(?::[0-9a-f]{2}){5})");
        private static final Pattern IP = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3})");
        private static final Pattern RSSI = Pattern.compile("(-?\\d+)/(-?\\d+)/(-?\\d+)");
        private static final Pattern POLICY = Pattern.compile("(?i)WLAN\\s+policy\\([^\\r\\n]*\\)\\s*:\\s*([0-2])");
        private static final Pattern POLICY_ALT = Pattern.compile("(?i)AccessControlList\\.Policy[^\\r\\n]*:\\s*([0-2])");

        final String host;
        final String password;
        final int port;
        final int node;

        TelnetRouter(String host, String password, int port, int node) {
            this.host = host;
            this.password = password;
            this.port = port;
            this.node = node;
        }

        Snapshot probe() throws Exception {
            try (Session s = new Session(host, port, password)) {
                s.open();
                run(s, "select WLAN", "rtwlan node index " + node, 5500, false);
                String wireless = run(s, "read clients", "rtwlan showmactable", 8000, false);
                String arp = run(s, "read ARP", "ip arp status", 8000, false);
                String nodeText = run(s, "read WLAN config", "rt node display", 8000, false);
                return parse(wireless, arp, nodeText);
            }
        }

        ActionResult setBlocked(String mac, boolean blocked, String protectedMac) {
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
                return new ActionResult(false, clean(t), null);
            }
        }

        ActionResult enableAllowOnly(Set<String> allowedMacs, String managerMac) {
            try {
                String manager = normalizeMac(managerMac);
                Set<String> desired = new LinkedHashSet<>();
                for (String m : allowedMacs) {
                    String n = normalizeMac(m);
                    if (MAC.matcher(n).matches()) desired.add(n);
                }
                if (!manager.isEmpty()) desired.add(manager);
                if (desired.isEmpty()) return new ActionResult(false, "هیچ دستگاه مجازی برای Allow-List انتخاب نشده است.", null);
                return applyPolicy(1, desired, manager);
            } catch (Throwable t) {
                return new ActionResult(false, clean(t), null);
            }
        }

        ActionResult disableMacFilter() {
            try {
                return applyPolicy(0, new LinkedHashSet<>(), "");
            } catch (Throwable t) {
                return new ActionResult(false, clean(t), null);
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

            try (Session s = new Session(host, port, password)) {
                s.open();
                run(s, "select WLAN", "rtwlan node index " + node, 5500, false);

                // Fail-safe while the ACL is being replaced: filtering is temporarily disabled.
                run(s, "disable filter before ACL replace", "rt node accesspolicy 0", 5500, false);

                // TD-W8961N/TrendChip CLI: acladdentry replaces the ENTIRE list.
                // Entries must be separated by semicolons. Empty list is represented by "".
                String aclCommand = desired.isEmpty()
                        ? "rt node acladdentry \"\""
                        : "rt node acladdentry " + String.join(";", desired);
                run(s, "replace ACL", aclCommand, 6500, false);

                run(s, "apply access policy", "rt node accesspolicy " + policy, 6500, false);
                run(s, "save WLAN config", "rt node save", 9000, true);
            }

            Thread.sleep(1800);
            Snapshot after = probeRetry();
            boolean ok = after.policy == policy;
            if (policy == 0) ok = ok && after.acl.isEmpty();
            else ok = ok && after.acl.equals(desired);

            if (ok) {
                String msg = policy == 0
                        ? "فیلتر MAC خاموش و Verify شد؛ اتصال همه آزاد است."
                        : policy == 1
                        ? "Allow-List واقعی فعال و از خود روتر Verify شد."
                        : "قطع/وصل واقعی روی خود روتر اعمال و Verify شد.";
                return new ActionResult(true, msg, after);
            }

            try {
                forceDisable();
            } catch (Throwable ignored) {}
            Snapshot safe = null;
            try {
                safe = probeRetry();
            } catch (Throwable ignored) {}
            return new ActionResult(false,
                    "Verify نهایی برابر نبود؛ برای جلوگیری از قطع دستگاه مدیر، فیلتر MAC به حالت امن خاموش شد.", safe);
        }

        private void forceDisable() throws Exception {
            try (Session s = new Session(host, port, password)) {
                s.open();
                run(s, "safe select WLAN", "rtwlan node index " + node, 5000, false);
                run(s, "safe disable filter", "rt node accesspolicy 0", 5000, false);
                run(s, "safe clear ACL", "rt node acladdentry \"\"", 5500, false);
                run(s, "safe save", "rt node save", 8500, true);
            }
            Thread.sleep(1200);
        }

        private Snapshot probeRetry() throws Exception {
            Exception last = null;
            for (int i = 0; i < 5; i++) {
                try {
                    return probe();
                } catch (Exception e) {
                    last = e;
                    Thread.sleep(700L + i * 450L);
                }
            }
            throw last == null ? new Exception("verify timeout") : last;
        }

        private String run(Session s, String stage, String command, long timeout, boolean allowClose) throws Exception {
            try {
                return s.command(command, timeout, allowClose);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                throw new Exception(stage + " | " + command + " | " + msg, e);
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
                if (!line.trim().toLowerCase(Locale.US).startsWith(mac)) continue;
                Integer signal = null;
                Matcher rm = RSSI.matcher(line);
                if (rm.find()) {
                    try {
                        signal = Integer.parseInt(rm.group(1));
                    } catch (Throwable ignored) {}
                }
                devices.put(mac, new Device(mac, ipByMac.get(mac), signal, true));
            }

            Snapshot out = new Snapshot();
            out.rawWireless = wireless;
            out.rawArp = arp;
            out.rawNode = nodeText;

            Matcher pm = POLICY.matcher(nodeText);
            if (pm.find()) out.policy = Integer.parseInt(pm.group(1));
            else {
                Matcher alt = POLICY_ALT.matcher(nodeText);
                out.policy = alt.find() ? Integer.parseInt(alt.group(1)) : 0;
            }

            out.acl = parseAcl(nodeText);
            if (out.policy != 0) {
                for (String mac : out.acl) {
                    if (!devices.containsKey(mac)) devices.put(mac, new Device(mac, ipByMac.get(mac), null, false));
                }
            }
            out.devices.addAll(devices.values());
            return out;
        }

        private Set<String> parseAcl(String nodeText) {
            Set<String> out = new LinkedHashSet<>();
            String[] lines = nodeText.split("\\r?\\n");
            boolean inAcl = false;
            for (String line : lines) {
                String lower = line.toLowerCase(Locale.US);
                if (lower.contains("wlan accesscontrollist")) {
                    inAcl = true;
                    addMacs(line, out);
                    continue;
                }
                if (!inAcl) continue;
                String trim = line.trim();
                if (trim.toLowerCase(Locale.US).startsWith("wlan ")) break;
                addMacs(line, out);
            }
            return out;
        }

        private void addMacs(String text, Set<String> out) {
            Matcher m = MAC.matcher(text);
            while (m.find()) {
                String mac = normalizeMac(m.group(1));
                if (!"ff:ff:ff:ff:ff:ff".equals(mac)) out.add(mac);
            }
        }

        private String clean(Throwable t) {
            String m = t.getMessage() == null ? "" : t.getMessage();
            String l = m.toLowerCase(Locale.US);
            if (l.contains("password") || l.contains("login")) return "رمز مدیریت روتر پذیرفته نشد.";
            if (l.contains("refused")) return "اتصال Telnet رد شد.";
            if (l.contains("timeout")) return "Timeout در مرحله: " + m;
            return m.isEmpty() ? "ارتباط CLI ناموفق بود." : m;
        }

        static class Session implements AutoCloseable {
            private static final Pattern PROMPT = Pattern.compile("(?im)(?:^|[\\r\\n])[^\\r\\n]{0,32}(?:>|#|\\$)\\s*$");
            final String host;
            final String password;
            final int port;
            final TelnetClient telnet = new TelnetClient();
            BufferedInputStream input;
            BufferedOutputStream output;

            Session(String host, int port, String password) {
                this.host = host;
                this.port = port;
                this.password = password;
            }

            void open() throws Exception {
                telnet.setConnectTimeout(6000);
                telnet.setDefaultTimeout(6000);
                telnet.connect(host, port);
                try {
                    telnet.setSoTimeout(1200);
                } catch (Throwable ignored) {}
                input = new BufferedInputStream(telnet.getInputStream());
                output = new BufferedOutputStream(telnet.getOutputStream());

                String hello = readLogin(7000);
                String lower = hello.toLowerCase(Locale.US);
                if (lower.contains("password:")) {
                    send(password);
                    String login = readLogin(7000);
                    String loginLower = login.toLowerCase(Locale.US);
                    if (loginLower.contains("password:") || !hasPrompt(login)) {
                        throw new Exception("Password/login rejected");
                    }
                } else if (!hasPrompt(hello)) {
                    throw new Exception("Login timeout");
                }
            }

            String command(String command, long timeout, boolean allowClose) throws Exception {
                drain();
                send(command);
                return readCommand(timeout, allowClose);
            }

            void send(String value) throws Exception {
                output.write((value + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();
            }

            private void drain() {
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
                            break;
                        }
                        if (n > 0) {
                            gotData = true;
                            lastData = System.currentTimeMillis();
                            sb.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                            if (hasPrompt(sb.toString())) return sb.toString();
                        }
                    } else {
                        long idle = System.currentTimeMillis() - lastData;
                        // Some TD-W8961N firmware commands complete without echoing TP-LINK>.
                        // If bytes were received and the stream becomes idle, treat the command as complete.
                        if (gotData && idle > 650) return sb.toString();
                        if (allowClose && idle > 900) return sb.toString();
                        Thread.sleep(30);
                    }
                }

                if (allowClose) return sb.toString();
                throw new Exception("CLI timeout");
            }

            private boolean hasPrompt(String text) {
                return PROMPT.matcher(text).find() || text.contains("TP-LINK>");
            }

            @Override
            public void close() {
                try {
                    if (telnet.isConnected()) telnet.disconnect();
                } catch (Throwable ignored) {}
            }
        }
    }
}
