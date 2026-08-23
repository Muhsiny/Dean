package org.wifiusagemeter.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private SecureStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private int taps;
    private long tapStart;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        store = new SecureStore(this);
        route();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void route() {
        String role = store.get("role", "");
        if (role.isEmpty()) firstSetup();
        else if ("manager".equals(role)) pinGate(this::managerHome);
        else studentHome();
    }

    private void screen() {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setBackgroundColor(0xFFF4F6F8);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(32));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        s.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(s);
    }

    private void firstSetup() {
        screen();
        root.addView(title("راه‌اندازی نخست"));
        root.addView(body("این برنامه مصرف Wi-Fi همین تلفن را اندازه‌گیری می‌کند."));
        Button a = primary("این تلفن محصل است");
        a.setOnClickListener(v -> studentSetup());
        root.addView(a);
        Button b = secondary("این تلفن مدیر است");
        b.setOnClickListener(v -> managerSetup());
        root.addView(b);
    }

    private void studentSetup() {
        screen();
        root.addView(title("تنظیم تلفن محصل"));
        EditText id = input("نام یا شناسه محصل");
        EditText pin = pinInput("PIN مدیر، حداقل ۴ رقم");
        String key = SecureStore.randomKey();
        TextView keyView = body("کد اتصال مدیر: " + key);
        keyView.setTextIsSelectable(true);
        root.addView(id); root.addView(pin); root.addView(keyView);
        Button access = secondary("فعال‌کردن Usage Access");
        access.setOnClickListener(v -> openUsageAccess());
        root.addView(access);
        Button save = primary("ذخیره و ادامه");
        save.setOnClickListener(v -> {
            String sid = id.getText().toString().trim();
            String p = pin.getText().toString().trim();
            if (sid.isEmpty() || p.length() < 4) { toast("شناسه و PIN معتبر وارد کنید."); return; }
            store.put("role", "student");
            store.put("student_id", sid);
            store.put("pairing_key", key);
            store.put("pin_record", PinUtil.createRecord(p));
            log("راه‌اندازی تلفن محصل");
            studentHome();
        });
        root.addView(save);
    }

    private void managerSetup() {
        screen();
        root.addView(title("تنظیم تلفن مدیر"));
        EditText pin = pinInput("PIN مدیر، حداقل ۴ رقم");
        root.addView(pin);
        Button save = primary("ایجاد پنل مدیر");
        save.setOnClickListener(v -> {
            String p = pin.getText().toString().trim();
            if (p.length() < 4) { toast("PIN حداقل ۴ رقم باشد."); return; }
            store.put("role", "manager");
            store.put("pin_record", PinUtil.createRecord(p));
            managerHome();
        });
        root.addView(save);
    }

    private void studentHome() {
        screen();
        TextView h = title("مصرف Wi-Fi");
        h.setOnClickListener(v -> adminTap());
        root.addView(h);
        root.addView(body("نمایش مصرف این دستگاه"));
        if (!UsageReader.hasUsageAccess(this)) {
            root.addView(warning("برای اندازه‌گیری مصرف، Usage Access را در تنظیمات Android فعال کنید."));
            Button b = primary("فعال‌کردن Usage Access");
            b.setOnClickListener(v -> openUsageAccess());
            root.addView(b);
            return;
        }
        TextView loading = body("در حال خواندن آمار Android…");
        root.addView(loading);
        readUsage(this::studentUsage, e -> loading.setText("خواندن آمار ممکن نشد: " + e));
    }

    private void studentUsage(UsageBundle u) {
        screen();
        TextView h = title("مصرف Wi-Fi");
        h.setOnClickListener(v -> adminTap());
        root.addView(h);
        boolean test = hasOverride();
        root.addView(body(test ? "نمایش آزمایشی مصرف این دستگاه" : "مصرف ثبت‌شده این دستگاه"));
        root.addView(metric("امروز", shown("day", u.day.total())));
        root.addView(metric("این هفته", shown("week", u.week.total())));
        root.addView(metric("این ماه", shown("month", u.month.total())));
        if (test) root.addView(small("حالت نمایش آزمایشی فعال است."));
        Button r = secondary("تازه‌سازی");
        r.setOnClickListener(v -> studentHome());
        root.addView(r);
    }

    private void adminTap() {
        long now = System.currentTimeMillis();
        if (now - tapStart > 2200) { tapStart = now; taps = 1; } else taps++;
        if (taps >= 5) { taps = 0; pinGate(this::adminHome); }
    }

    private void pinGate(Runnable ok) {
        EditText pin = pinInput("PIN مدیر");
        LinearLayout box = dialogBox(); box.addView(pin);
        new AlertDialog.Builder(this).setTitle("ورود مدیر").setView(box)
                .setNegativeButton("لغو", null)
                .setPositiveButton("ورود", (d,w) -> {
                    if (PinUtil.verify(pin.getText().toString(), store.get("pin_record", ""))) ok.run();
                    else toast("PIN نادرست است.");
                }).show();
    }

    private void adminHome() {
        if (!UsageReader.hasUsageAccess(this)) {
            screen(); root.addView(title("پنل خصوصی مدیر"));
            root.addView(warning("Usage Access فعال نیست."));
            Button b = primary("فعال‌کردن Usage Access"); b.setOnClickListener(v -> openUsageAccess()); root.addView(b);
            return;
        }
        readUsage(this::renderAdmin, e -> toast("خطا در آمار حقیقی: " + e));
    }

    private void renderAdmin(UsageBundle u) {
        screen();
        root.addView(title("پنل خصوصی مدیر"));
        root.addView(body("آمار حقیقی Android — فقط خواندنی"));
        root.addView(realMetric("امروز", u.day));
        root.addView(realMetric("این هفته", u.week));
        root.addView(realMetric("این ماه", u.month));
        root.addView(small("آخرین خواندن: " + date(u.readAt)));

        root.addView(section("نمایش آزمایشی"));
        root.addView(body("این اعداد فقط صفحه عادی را تغییر می‌دهند و آمار حقیقی Android دست‌نخورده می‌ماند."));
        EditText day = numberInput("امروز MB", mb(shown("day", u.day.total())));
        EditText week = numberInput("هفته MB", mb(shown("week", u.week.total())));
        EditText month = numberInput("ماه MB", mb(shown("month", u.month.total())));
        root.addView(day); root.addView(week); root.addView(month);
        Button save = primary("ذخیره نمایش آزمایشی");
        save.setOnClickListener(v -> {
            try {
                store.put("display_day", Long.toString(mbBytes(day.getText().toString())));
                store.put("display_week", Long.toString(mbBytes(week.getText().toString())));
                store.put("display_month", Long.toString(mbBytes(month.getText().toString())));
                log("مقادیر نمایش آزمایشی تغییر کرد");
                renderAdmin(u);
            } catch (Exception e) { toast("عدد معتبر وارد کنید."); }
        });
        root.addView(save);
        Button restore = secondary("بازگرداندن نمایش به مقدار حقیقی");
        restore.setOnClickListener(v -> {
            store.remove("display_day"); store.remove("display_week"); store.remove("display_month");
            log("نمایش به مقدار حقیقی بازگردانده شد"); renderAdmin(u);
        });
        root.addView(restore);

        root.addView(section("ارسال آمار حقیقی به تلفن مدیر"));
        Button snap = secondary("ساخت کد امضاشده");
        snap.setOnClickListener(v -> {
            String code = SnapshotCodec.encode(store.get("student_id", "Student"), store.get("pairing_key", ""), u);
            copy(code); showText("کد آمار حقیقی", code);
        });
        root.addView(snap);
        TextView key = small("کد اتصال: " + store.get("pairing_key", "—")); key.setTextIsSelectable(true); root.addView(key);

        Button pin = secondary("تغییر PIN مدیر"); pin.setOnClickListener(v -> changePin()); root.addView(pin);
        Button logs = secondary("ثبت تغییرات"); logs.setOnClickListener(v -> showText("ثبت تغییرات", store.get("log", "هنوز تغییری ثبت نشده است."))); root.addView(logs);
        Button back = secondary("بازگشت به صفحه عادی"); back.setOnClickListener(v -> studentHome()); root.addView(back);
    }

    private void managerHome() {
        screen();
        root.addView(title("پنل مجموع مدیر"));
        root.addView(body("کد امضاشده هر محصل را همراه با کد اتصال همان تلفن وارد کنید."));
        managerTotals();
        root.addView(section("واردکردن گزارش"));
        EditText key = input("کد اتصال محصل");
        EditText code = input("کد آمار حقیقی"); code.setMinLines(4);
        root.addView(key); root.addView(code);
        Button add = primary("بررسی و ثبت");
        add.setOnClickListener(v -> {
            SnapshotCodec.Decoded d = SnapshotCodec.decodeAndVerify(code.getText().toString().trim(), key.getText().toString().trim());
            if (d == null) { toast("کد یا کلید اتصال معتبر نیست."); return; }
            saveManager(d); toast("آمار حقیقی " + d.studentId + " ثبت شد."); managerHome();
        });
        root.addView(add);
        Button reset = secondary("پاک‌کردن گزارش‌ها"); reset.setOnClickListener(v -> resetManager()); root.addView(reset);
        Button pin = secondary("تغییر PIN مدیر"); pin.setOnClickListener(v -> changePin()); root.addView(pin);
    }

    private void managerTotals() {
        List<String> ids = managerKeys(); long day=0, week=0, month=0;
        for (String k : ids) { day += getLong("m_"+k+"_d"); week += getLong("m_"+k+"_w"); month += getLong("m_"+k+"_m"); }
        root.addView(section("مجموع " + ids.size() + " محصل"));
        root.addView(metric("امروز", day)); root.addView(metric("این هفته", week)); root.addView(metric("این ماه", month));
        if (ids.isEmpty()) { root.addView(small("هنوز گزارشی ثبت نشده است.")); return; }
        root.addView(section("تفکیک محصلین"));
        for (String k : ids) {
            LinearLayout c = card();
            c.addView(section(store.get("m_"+k+"_id", k)));
            c.addView(small("امروز: " + bytes(getLong("m_"+k+"_d")) + " | هفته: " + bytes(getLong("m_"+k+"_w")) + " | ماه: " + bytes(getLong("m_"+k+"_m"))));
            c.addView(small("ثبت: " + date(getLong("m_"+k+"_t")))); root.addView(c);
        }
    }

    private void saveManager(SnapshotCodec.Decoded d) {
        String k = safeKey(d.studentId); List<String> ids = managerKeys();
        if (!ids.contains(k)) ids.add(k); store.put("mgr_ids", String.join(",", ids));
        store.put("m_"+k+"_id", d.studentId); store.put("m_"+k+"_d", Long.toString(d.day.total()));
        store.put("m_"+k+"_w", Long.toString(d.week.total())); store.put("m_"+k+"_m", Long.toString(d.month.total()));
        store.put("m_"+k+"_t", Long.toString(d.timestamp));
    }

    private void resetManager() {
        new AlertDialog.Builder(this).setTitle("پاک‌کردن گزارش‌ها").setMessage("تمام گزارش‌ها پاک شود؟")
                .setNegativeButton("لغو", null).setPositiveButton("پاک شود", (d,w) -> {
                    for (String k : managerKeys()) { store.remove("m_"+k+"_id"); store.remove("m_"+k+"_d"); store.remove("m_"+k+"_w"); store.remove("m_"+k+"_m"); store.remove("m_"+k+"_t"); }
                    store.remove("mgr_ids"); managerHome();
                }).show();
    }

    private void changePin() {
        EditText pin = pinInput("PIN جدید، حداقل ۴ رقم"); LinearLayout box = dialogBox(); box.addView(pin);
        new AlertDialog.Builder(this).setTitle("تغییر PIN").setView(box).setNegativeButton("لغو", null)
                .setPositiveButton("ذخیره", (d,w) -> {
                    String p = pin.getText().toString().trim();
                    if (p.length() < 4) toast("PIN حداقل ۴ رقم باشد.");
                    else { store.put("pin_record", PinUtil.createRecord(p)); log("PIN مدیر تغییر کرد"); toast("PIN تغییر کرد."); }
                }).show();
    }

    private void readUsage(UsageOk ok, UsageFail fail) {
        executor.submit(() -> {
            try { UsageBundle u = UsageReader.readWifi(this); runOnUiThread(() -> ok.done(u)); }
            catch (Exception e) { runOnUiThread(() -> fail.done(e.getMessage()==null ? e.getClass().getSimpleName() : e.getMessage())); }
        });
    }
    private interface UsageOk { void done(UsageBundle u); }
    private interface UsageFail { void done(String e); }

    private void openUsageAccess() {
        try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
        catch (Exception e) { toast("تنظیمات Usage Access باز نشد."); }
    }

    private boolean hasOverride() { return !store.get("display_day", "").isEmpty() || !store.get("display_week", "").isEmpty() || !store.get("display_month", "").isEmpty(); }
    private long shown(String p, long real) { try { String x=store.get("display_"+p, ""); return x.isEmpty()?real:Math.max(0,Long.parseLong(x)); } catch(Exception e){ return real; } }
    private long mbBytes(String x) { return (long)(Math.max(0, Double.parseDouble(x.trim()))*1048576d); }
    private String mb(long x) { return String.format(Locale.US,"%.1f",x/1048576d); }
    private long getLong(String k) { try { return Long.parseLong(store.get(k,"0")); } catch(Exception e){ return 0; } }
    private String safeKey(String x) { return Integer.toHexString(x.hashCode())+"_"+x.length(); }

    private List<String> managerKeys() {
        List<String> out = new ArrayList<>(); String raw=store.get("mgr_ids","");
        if (!raw.isEmpty()) for(String x:raw.split(",")) if(!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }

    private void log(String text) {
        String old=store.get("log",""); String line=date(System.currentTimeMillis())+" — "+text;
        store.put("log", line+(old.isEmpty()?"":"\n"+old));
    }

    private TextView title(String x){ TextView v=new TextView(this); v.setText(x); v.setTextSize(25); v.setTypeface(Typeface.DEFAULT_BOLD); v.setTextColor(0xFF16212E); v.setGravity(Gravity.RIGHT); v.setPadding(0,0,0,dp(10)); return v; }
    private TextView section(String x){ TextView v=new TextView(this); v.setText(x); v.setTextSize(18); v.setTypeface(Typeface.DEFAULT_BOLD); v.setTextColor(0xFF16212E); v.setGravity(Gravity.RIGHT); v.setPadding(0,dp(18),0,dp(8)); return v; }
    private TextView body(String x){ TextView v=new TextView(this); v.setText(x); v.setTextSize(15); v.setTextColor(0xFF687386); v.setGravity(Gravity.RIGHT); v.setLineSpacing(0,1.15f); v.setPadding(0,0,0,dp(14)); return v; }
    private TextView small(String x){ TextView v=new TextView(this); v.setText(x); v.setTextSize(13); v.setTextColor(0xFF687386); v.setGravity(Gravity.RIGHT); v.setPadding(0,dp(5),0,dp(7)); return v; }
    private TextView warning(String x){ TextView v=body(x); v.setTextColor(0xFF9A3E11); v.setBackground(round(0xFFFFE9D9)); v.setPadding(dp(12),dp(12),dp(12),dp(12)); margins(v,0,0,0,12); return v; }

    private Button primary(String x){ Button b=new Button(this); b.setText(x); b.setTextSize(15); b.setTextColor(0xFFFFFFFF); b.setAllCaps(false); b.setBackground(round(0xFF0F766E)); b.setPadding(dp(14),dp(10),dp(14),dp(10)); margins(b,0,6,0,8); return b; }
    private Button secondary(String x){ Button b=new Button(this); b.setText(x); b.setTextSize(15); b.setTextColor(0xFF16212E); b.setAllCaps(false); b.setBackground(round(0xFFFFFFFF)); b.setPadding(dp(14),dp(10),dp(14),dp(10)); margins(b,0,6,0,8); return b; }
    private EditText input(String x){ EditText e=new EditText(this); e.setHint(x); e.setTextSize(15); e.setGravity(Gravity.RIGHT); e.setBackground(round(0xFFFFFFFF)); e.setPadding(dp(14),dp(12),dp(14),dp(12)); margins(e,0,5,0,8); return e; }
    private EditText pinInput(String x){ EditText e=input(x); e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD); e.setSingleLine(true); return e; }
    private EditText numberInput(String h,String x){ EditText e=input(h); e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL); e.setText(x); return e; }
    private LinearLayout card(){ LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(15),dp(14),dp(15),dp(14)); c.setBackground(round(0xFFFFFFFF)); margins(c,0,5,0,10); return c; }
    private LinearLayout dialogBox(){ LinearLayout b=new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(20),dp(5),dp(20),0); b.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); return b; }

    private View metric(String label,long total){ LinearLayout c=card(); c.addView(small(label)); TextView t=new TextView(this); t.setText(bytes(total)); t.setTextSize(27); t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextColor(0xFF16212E); c.addView(t); return c; }
    private View realMetric(String label,UsageTotals u){ LinearLayout c=card(); c.addView(small(label+" — واقعی")); TextView t=new TextView(this); t.setText(bytes(u.total())); t.setTextSize(23); t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextColor(0xFF0F766E); c.addView(t); c.addView(small("دانلود: "+bytes(u.rx)+"   آپلود: "+bytes(u.tx))); return c; }

    private GradientDrawable round(int color){ GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(14)); return d; }
    private void margins(View v,int l,int t,int r,int b){ LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); p.setMargins(dp(l),dp(t),dp(r),dp(b)); v.setLayoutParams(p); }
    private int dp(int x){ return Math.round(x*getResources().getDisplayMetrics().density); }

    private void copy(String x){ ClipboardManager c=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE); if(c!=null)c.setPrimaryClip(ClipData.newPlainText("WiFi Usage",x)); toast("در حافظه کپی شد."); }
    private void showText(String title,String text){ TextView v=body(text); v.setTextIsSelectable(true); v.setPadding(dp(20),dp(10),dp(20),dp(10)); new AlertDialog.Builder(this).setTitle(title).setView(v).setPositiveButton("بستن",null).show(); }
    private String date(long t){ if(t<=0)return "—"; return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,new Locale("fa","AF")).format(new Date(t)); }
    private String bytes(long x){ if(x<1024)return x+" B"; double k=x/1024d; if(k<1024)return String.format(Locale.US,"%.1f KB",k); double m=k/1024d; if(m<1024)return String.format(Locale.US,"%.1f MB",m); return String.format(Locale.US,"%.2f GB",m/1024d); }
    private void toast(String x){ Toast.makeText(this,x,Toast.LENGTH_LONG).show(); }
}
