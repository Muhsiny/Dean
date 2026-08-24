package org.sayeh.realwifi;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class UsageMeterActivity extends Activity {
    private SharedPreferences p;
    private LinearLayout root;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int taps = 0;
    private long tapStart = 0;
    private Usage latest;

    static final class Totals {
        final long rx, tx;
        Totals(long rx, long tx) { this.rx=Math.max(0,rx); this.tx=Math.max(0,tx); }
        long total(){ return rx+tx; }
    }
    static final class Usage {
        final Totals day, week, month; final long at;
        Usage(Totals d, Totals w, Totals m, long at){day=d;week=w;month=m;this.at=at;}
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        p=getSharedPreferences("wifi_usage_meter_v1",MODE_PRIVATE);
        route();
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}

    private void route(){
        String role=p.getString("role","");
        if(role.isEmpty()) setup();
        else if("manager".equals(role)) pinGate(this::managerHome);
        else studentHome();
    }

    private void setup(){
        screen();
        root.addView(title("WiFi Usage Meter"));
        root.addView(body("اندازه‌گیری حجم مصرف Wi‑Fi این تلفن، به‌صورت روزانه، هفتگی و ماهانه."));
        Button s=primary("این تلفن محصل است"); s.setOnClickListener(v->studentSetup()); root.addView(s);
        Button m=secondary("این تلفن مدیر است"); m.setOnClickListener(v->managerSetup()); root.addView(m);
    }

    private void studentSetup(){
        screen(); root.addView(title("تنظیم تلفن"));
        EditText name=input("نام یا شناسه محصل"); EditText pin=pinInput("PIN مدیر، حداقل ۴ رقم");
        root.addView(name);root.addView(pin);
        Button access=secondary("فعال‌کردن دسترسی آمار مصرف"); access.setOnClickListener(v->openAccess()); root.addView(access);
        Button save=primary("ذخیره"); save.setOnClickListener(v->{
            String n=name.getText().toString().trim(), x=pin.getText().toString().trim();
            if(n.isEmpty()||x.length()<4){toast("نام و PIN لازم است.");return;}
            p.edit().putString("role","student").putString("student",n).putString("pin",hashPin(x)).putString("pair",randomKey()).apply();
            studentHome();
        });root.addView(save);
    }

    private void managerSetup(){
        screen();root.addView(title("پنل مدیر"));
        EditText pin=pinInput("PIN مدیر، حداقل ۴ رقم");root.addView(pin);
        Button save=primary("ایجاد پنل مدیر");save.setOnClickListener(v->{String x=pin.getText().toString().trim();if(x.length()<4){toast("PIN حداقل ۴ رقم باشد.");return;}p.edit().putString("role","manager").putString("pin",hashPin(x)).apply();managerHome();});root.addView(save);
    }

    private void studentHome(){
        screen();TextView h=title("مصرف Wi‑Fi");h.setOnClickListener(v->tapAdmin());root.addView(h);root.addView(body("مصرف این دستگاه"));
        if(!hasAccess()){root.addView(warn("برای آغاز اندازه‌گیری، دسترسی آمار مصرف را فعال کن."));Button b=primary("فعال‌کردن دسترسی");b.setOnClickListener(v->openAccess());root.addView(b);return;}
        TextView wait=body("در حال خواندن آمار…");root.addView(wait);
        read(u->{latest=u;renderStudent(u);},e->wait.setText("خواندن آمار ممکن نشد. دوباره تلاش کن."));
    }

    private void renderStudent(Usage u){
        screen();TextView h=title("مصرف Wi‑Fi");h.setOnClickListener(v->tapAdmin());root.addView(h);root.addView(body("مصرف این دستگاه"));
        long d=shown("day",u.day.total()), w=shown("week",u.week.total()), m=shown("month",u.month.total());
        root.addView(metric("امروز",d,split(u.day,d)));root.addView(metric("این هفته",w,split(u.week,w)));root.addView(metric("این ماه",m,split(u.month,m)));
        Button r=secondary("تازه‌سازی");r.setOnClickListener(v->studentHome());root.addView(r);
    }

    private void tapAdmin(){long n=System.currentTimeMillis();if(taps==0||n-tapStart>2200){tapStart=n;taps=1;}else taps++;if(taps>=5){taps=0;pinGate(this::studentAdmin);}}

    private void pinGate(Runnable ok){
        EditText e=pinInput("PIN مدیر");LinearLayout box=new LinearLayout(this);box.setPadding(dp(20),0,dp(20),0);box.addView(e);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("ورود مدیر").setView(box).setNegativeButton("انصراف",null).setPositiveButton("ورود",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(checkPin(e.getText().toString())){d.dismiss();ok.run();}else e.setError("PIN نادرست");}));d.show();
    }

    private void studentAdmin(){
        screen();root.addView(title("مدیریت دستگاه"));
        if(!hasAccess()){root.addView(warn("دسترسی آمار فعال نیست."));Button b=primary("فعال‌کردن دسترسی");b.setOnClickListener(v->openAccess());root.addView(b);return;}
        TextView wait=body("در حال خواندن دادهٔ واقعی…");root.addView(wait);read(u->{latest=u;renderAdmin(u);},e->wait.setText("خطا در خواندن آمار."));
    }

    private void renderAdmin(Usage u){
        screen();root.addView(title("مدیریت دستگاه"));
        root.addView(section("آمار واقعی"));root.addView(realMetric("امروز",u.day));root.addView(realMetric("این هفته",u.week));root.addView(realMetric("این ماه",u.month));
        root.addView(section("تنظیم مقدار صفحهٔ اصلی"));
        EditText d=numInput("امروز به MB — خالی یعنی واقعی",overrideText("day"));EditText w=numInput("هفته به MB — خالی یعنی واقعی",overrideText("week"));EditText m=numInput("ماه به MB — خالی یعنی واقعی",overrideText("month"));
        root.addView(d);root.addView(w);root.addView(m);
        Button save=primary("ذخیره مقادیر");save.setOnClickListener(v->{savePrev();saveOverride("day",d.getText().toString());saveOverride("week",w.getText().toString());saveOverride("month",m.getText().toString());log("مقادیر صفحه تغییر کرد");toast("ذخیره شد؛ آمار واقعی تغییر نکرد.");renderAdmin(u);});root.addView(save);
        Button undo=secondary("برگرداندن تغییر قبلی");undo.setOnClickListener(v->{undo();log("Undo");renderAdmin(u);});root.addView(undo);
        Button real=secondary("نمایش دوبارهٔ مقدار واقعی");real.setOnClickListener(v->{p.edit().remove("display_day").remove("display_week").remove("display_month").apply();log("نمایش به واقعی برگشت");renderAdmin(u);});root.addView(real);
        root.addView(section("گزارش واقعی برای تلفن مدیر"));
        String pair=p.getString("pair",""); TextView key=small("کلید اتصال: "+pair);key.setTextIsSelectable(true);root.addView(key);
        String code=reportCode(u);TextView report=small(code);report.setTextIsSelectable(true);root.addView(report);
        Button copy=secondary("کپی کد گزارش");copy.setOnClickListener(v->copy(code));root.addView(copy);
        Button copyKey=secondary("کپی کلید اتصال");copyKey.setOnClickListener(v->copy(pair));root.addView(copyKey);
        root.addView(section("امنیت"));
        Button pin=secondary("تغییر PIN مدیر");pin.setOnClickListener(v->changePin());root.addView(pin);
        root.addView(section("آخرین تغییرات"));root.addView(small(p.getString("log","هنوز تغییری ثبت نشده است.")));
        Button back=secondary("بازگشت به صفحه اصلی");back.setOnClickListener(v->studentHome());root.addView(back);
    }

    private void managerHome(){
        screen();root.addView(title("پنل مجموع مصرف"));
        String ids=p.getString("mgr_ids","");long td=0,tw=0,tm=0;int count=0;
        if(!ids.isEmpty()) for(String id:ids.split("\\n")){if(id.trim().isEmpty())continue;count++;String k=safe(id);long d=p.getLong("mgr_"+k+"_day",0),w=p.getLong("mgr_"+k+"_week",0),m=p.getLong("mgr_"+k+"_month",0);td+=d;tw+=w;tm+=m;LinearLayout c=card();c.addView(strong(id));c.addView(small("امروز: "+fmt(d)+"   هفته: "+fmt(w)+"   ماه: "+fmt(m)));root.addView(c);}
        root.addView(section("مجموع "+count+" محصل"));root.addView(totalCard("امروز",td));root.addView(totalCard("این هفته",tw));root.addView(totalCard("این ماه",tm));
        Button imp=primary("واردکردن گزارش محصل");imp.setOnClickListener(v->importDialog());root.addView(imp);
        Button clear=secondary("پاک‌کردن فهرست محصلین");clear.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("همه گزارش‌های ذخیره‌شده پاک شوند؟").setNegativeButton("نه",null).setPositiveButton("بلی",(x,y)->{SharedPreferences.Editor ed=p.edit();for(String key:p.getAll().keySet())if(key.startsWith("mgr_"))ed.remove(key);ed.remove("mgr_ids").apply();managerHome();}).show());root.addView(clear);
        Button pin=secondary("تغییر PIN مدیر");pin.setOnClickListener(v->changePin());root.addView(pin);
    }

    private void importDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),0,dp(20),0);
        EditText code=input("کد گزارش واقعی");EditText key=input("کلید اتصال همان محصل");box.addView(code);box.addView(key);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("ورود گزارش").setView(box).setNegativeButton("انصراف",null).setPositiveButton("بررسی و ذخیره",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{Report r=parseReport(code.getText().toString().trim(),key.getText().toString().trim());if(r==null){code.setError("کد یا کلید معتبر نیست");return;}saveReport(r);d.dismiss();managerHome();}));d.show();
    }

    static final class Report {String id;long day,week,month,at;Report(String i,long d,long w,long m,long a){id=i;day=d;week=w;month=m;at=a;}}
    private String reportCode(Usage u){String id=p.getString("student","");String enc=Base64.encodeToString(id.getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);String body="WUM1|"+enc+"|"+u.at+"|"+u.day.total()+"|"+u.week.total()+"|"+u.month.total();return body+"|"+hmac(body,p.getString("pair",""));}
    private Report parseReport(String code,String key){try{String[] a=code.split("\\|");if(a.length!=7||!"WUM1".equals(a[0]))return null;String body=String.join("|",a[0],a[1],a[2],a[3],a[4],a[5]);if(!MessageDigest.isEqual(hmac(body,key).getBytes(StandardCharsets.UTF_8),a[6].getBytes(StandardCharsets.UTF_8)))return null;String id=new String(Base64.decode(a[1],Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),StandardCharsets.UTF_8);return new Report(id,Long.parseLong(a[3]),Long.parseLong(a[4]),Long.parseLong(a[5]),Long.parseLong(a[2]));}catch(Exception e){return null;}}
    private String hmac(String body,String key){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.encodeToString(m.doFinal(body.getBytes(StandardCharsets.UTF_8)),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}catch(Exception e){return "";}}
    private void saveReport(Report r){String ids=p.getString("mgr_ids","");boolean found=false;if(!ids.isEmpty())for(String x:ids.split("\\n"))if(x.equals(r.id))found=true;if(!found)ids=ids.isEmpty()?r.id:ids+"\n"+r.id;String k=safe(r.id);p.edit().putString("mgr_ids",ids).putLong("mgr_"+k+"_day",r.day).putLong("mgr_"+k+"_week",r.week).putLong("mgr_"+k+"_month",r.month).putLong("mgr_"+k+"_at",r.at).apply();}

    private boolean hasAccess(){AppOpsManager a=(AppOpsManager)getSystemService(APP_OPS_SERVICE);return a!=null&&a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),getPackageName())==AppOpsManager.MODE_ALLOWED;}
    private void openAccess(){startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));}
    private void read(Ok ok,Fail fail){worker.execute(()->{try{Usage u=readNow();runOnUiThread(()->ok.done(u));}catch(Exception e){runOnUiThread(()->fail.bad(e));}});} interface Ok{void done(Usage u);} interface Fail{void bad(Exception e);}
    private Usage readNow(){long n=System.currentTimeMillis();return new Usage(query(startDay(n),n),query(startWeek(n),n),query(startMonth(n),n),n);}
    private Totals query(long s,long e){NetworkStatsManager m=(NetworkStatsManager)getSystemService(NETWORK_STATS_SERVICE);if(m==null)return new Totals(0,0);NetworkStats.Bucket b=m.querySummaryForDevice(ConnectivityManager.TYPE_WIFI,null,s,e);return new Totals(b.getRxBytes(),b.getTxBytes());}
    private long startDay(long n){Calendar c=Calendar.getInstance();c.setTimeInMillis(n);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
    private long startWeek(long n){Calendar c=Calendar.getInstance();c.setTimeInMillis(n);c.setFirstDayOfWeek(Calendar.SATURDAY);c.set(Calendar.DAY_OF_WEEK,Calendar.SATURDAY);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);if(c.getTimeInMillis()>n)c.add(Calendar.DAY_OF_MONTH,-7);return c.getTimeInMillis();}
    private long startMonth(long n){Calendar c=Calendar.getInstance();c.setTimeInMillis(n);c.set(Calendar.DAY_OF_MONTH,1);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}

    private long shown(String k,long real){long x=p.getLong("display_"+k,-1);return x<0?real:x;}
    private String overrideText(String k){long x=p.getLong("display_"+k,-1);return x<0?"":String.format(Locale.US,"%.1f",x/(1024d*1024d));}
    private void savePrev(){SharedPreferences.Editor e=p.edit();for(String x:new String[]{"day","week","month"})e.putLong("prev_"+x,p.getLong("display_"+x,-1));e.apply();}
    private void saveOverride(String k,String v){if(v==null||v.trim().isEmpty()){p.edit().remove("display_"+k).apply();return;}try{double mb=Double.parseDouble(v.trim());p.edit().putLong("display_"+k,(long)Math.max(0,mb*1024d*1024d)).apply();}catch(Exception e){}}
    private void undo(){SharedPreferences.Editor e=p.edit();for(String x:new String[]{"day","week","month"}){long v=p.getLong("prev_"+x,-1);if(v<0)e.remove("display_"+x);else e.putLong("display_"+x,v);}e.apply();}
    private Totals split(Totals r,long total){long t=r.total();if(t<=0)return new Totals(total,0);long rx=Math.round(total*(r.rx/(double)t));return new Totals(rx,total-rx);}

    private String hashPin(String pin){try{MessageDigest d=MessageDigest.getInstance("SHA-256");return Base64.encodeToString(d.digest(("wum-v1:"+pin).getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);}catch(Exception e){return pin;}}
    private boolean checkPin(String pin){return MessageDigest.isEqual(hashPin(pin).getBytes(StandardCharsets.UTF_8),p.getString("pin","").getBytes(StandardCharsets.UTF_8));}
    private void changePin(){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(20),0,dp(20),0);EditText a=pinInput("PIN جدید");EditText c=pinInput("تکرار PIN");b.addView(a);b.addView(c);new AlertDialog.Builder(this).setTitle("تغییر PIN").setView(b).setNegativeButton("انصراف",null).setPositiveButton("ذخیره",(x,y)->{String s=a.getText().toString(),t=c.getText().toString();if(s.length()>=4&&s.equals(t)){p.edit().putString("pin",hashPin(s)).apply();toast("PIN تغییر کرد.");}else toast("PIN معتبر و یکسان وارد کن.");}).show();}
    private String randomKey(){String chars="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";SecureRandom r=new SecureRandom();StringBuilder b=new StringBuilder();for(int i=0;i<20;i++)b.append(chars.charAt(r.nextInt(chars.length())));return b.toString();}
    private String safe(String s){return Integer.toHexString(s.hashCode())+"_"+s.length();}
    private void log(String s){String old=p.getString("log","");String line=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,new Locale("fa","AF")).format(new Date())+" — "+s;p.edit().putString("log",line+(old.isEmpty()?"":"\n"+old)).apply();}
    private void copy(String s){ClipboardManager c=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(c!=null)c.setPrimaryClip(ClipData.newPlainText("WiFi Usage Meter",s));toast("کپی شد.");}

    private void screen(){ScrollView s=new ScrollView(this);s.setFillViewport(true);s.setBackgroundColor(0xFFF4F6F8);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(20),dp(18),dp(32));root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);s.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(s);}
    private TextView title(String s){TextView v=new TextView(this);v.setText(s);v.setTextSize(26);v.setTypeface(Typeface.DEFAULT_BOLD);v.setTextColor(0xFF15202B);v.setGravity(Gravity.RIGHT);v.setPadding(0,0,0,dp(8));return v;}
    private TextView section(String s){TextView v=title(s);v.setTextSize(18);v.setPadding(0,dp(18),0,dp(8));return v;}
    private TextView body(String s){TextView v=new TextView(this);v.setText(s);v.setTextSize(15);v.setTextColor(0xFF667085);v.setGravity(Gravity.RIGHT);v.setPadding(0,0,0,dp(14));return v;}
    private TextView small(String s){TextView v=body(s);v.setTextSize(13);v.setPadding(0,dp(3),0,dp(6));return v;}
    private TextView strong(String s){TextView v=body(s);v.setTextSize(17);v.setTypeface(Typeface.DEFAULT_BOLD);v.setTextColor(0xFF15202B);v.setPadding(0,0,0,dp(4));return v;}
    private TextView warn(String s){TextView v=body(s);v.setTextColor(0xFFB42318);v.setBackground(bg(0xFFFFE9E7));v.setPadding(dp(12),dp(12),dp(12),dp(12));margin(v,0,0,0,10);return v;}
    private EditText input(String h){EditText e=new EditText(this);e.setHint(h);e.setTextSize(15);e.setGravity(Gravity.RIGHT);e.setBackground(bg(0xFFFFFFFF));e.setPadding(dp(14),dp(12),dp(14),dp(12));margin(e,0,5,0,8);return e;}
    private EditText pinInput(String h){EditText e=input(h);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);return e;}
    private EditText numInput(String h,String v){EditText e=input(h);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);e.setText(v);return e;}
    private Button primary(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(0xFFFFFFFF);b.setTextSize(15);b.setBackground(bg(0xFF0F766E));margin(b,0,5,0,8);return b;}
    private Button secondary(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(0xFF15202B);b.setTextSize(15);b.setBackground(bg(0xFFFFFFFF));margin(b,0,5,0,8);return b;}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(15),dp(14),dp(15),dp(14));c.setBackground(bg(0xFFFFFFFF));margin(c,0,4,0,10);return c;}
    private View metric(String label,long total,Totals split){LinearLayout c=card();c.addView(small(label));TextView n=strong(fmt(total));n.setTextSize(27);c.addView(n);c.addView(small("دانلود: "+fmt(split.rx)+"     آپلود: "+fmt(split.tx)));return c;}
    private View realMetric(String label,Totals t){LinearLayout c=card();c.addView(small(label+" — واقعی"));TextView n=strong(fmt(t.total()));n.setTextSize(23);n.setTextColor(0xFF0F766E);c.addView(n);c.addView(small("دانلود: "+fmt(t.rx)+"     آپلود: "+fmt(t.tx)));return c;}
    private View totalCard(String label,long t){LinearLayout c=card();c.addView(small(label));TextView n=strong(fmt(t));n.setTextSize(25);c.addView(n);return c;}
    private GradientDrawable bg(int c){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(14));return d;}
    private void margin(View v,int l,int t,int r,int b){LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);q.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(q);}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private String fmt(long b){if(b<1024)return b+" B";double k=b/1024d;if(k<1024)return String.format(Locale.US,"%.1f KB",k);double m=k/1024d;if(m<1024)return String.format(Locale.US,"%.1f MB",m);return String.format(Locale.US,"%.2f GB",m/1024d);}
}
