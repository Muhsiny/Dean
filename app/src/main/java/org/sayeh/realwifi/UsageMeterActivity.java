package org.sayeh.realwifi;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.DatePickerDialog;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import java.text.SimpleDateFormat;
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
    private int taps=0;
    private long tapStart=0;
    private boolean waitingForUsageAccess=false;

    static final class Totals {
        final long rx,tx;
        Totals(long rx,long tx){this.rx=Math.max(0,rx);this.tx=Math.max(0,tx);}
        long total(){return rx+tx;}
    }
    static final class Usage {
        final Totals day,week,month; final long at;
        Usage(Totals d,Totals w,Totals m,long at){day=d;week=w;month=m;this.at=at;}
    }
    static final class Range {
        final long start,end; final String label; final int mode;
        Range(long s,long e,String l,int m){start=s;end=e;label=l;mode=m;}
    }
    static final class DashboardData {
        final Usage usage; final Range range; final Totals rangeReal;
        DashboardData(Usage u,Range r,Totals t){usage=u;range=r;rangeReal=t;}
    }
    static final class Report {
        final String id; final long day,week,month,at;
        Report(String i,long d,long w,long m,long a){id=i;day=d;week=w;month=m;at=a;}
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        p=getSharedPreferences("wifi_usage_meter_v1",MODE_PRIVATE);
        initialiseHiddenDefaults();
        dashboard();
    }
    @Override protected void onResume(){super.onResume();if(waitingForUsageAccess){waitingForUsageAccess=false;dashboard();}}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}

    private void initialiseHiddenDefaults(){
        SharedPreferences.Editor e=p.edit();
        String pair=p.getString("pair","");
        if(pair.isEmpty()){pair=randomKey();e.putString("pair",pair);}
        if(p.getString("student","").isEmpty())e.putString("student","Device-"+pair.substring(0,4));
        e.apply();
    }

    private void dashboard(){
        screen();
        TextView h=title("WiFi Usage");h.setOnClickListener(v->tapAdmin());root.addView(h);
        TextView s=body("مصرف اینترنت این دستگاه");s.setOnClickListener(v->tapAdmin());root.addView(s);
        if(!hasAccess()){
            Usage z=new Usage(new Totals(0,0),new Totals(0,0),new Totals(0,0),System.currentTimeMillis());
            Range r=selectedRange(System.currentTimeMillis());
            renderDashboardValues(new DashboardData(z,r,new Totals(0,0)),false);
            TextView note=small("برای نمایش آمار دقیق، دسترسی اندازه‌گیری را یک‌بار فعال کنید.");note.setGravity(Gravity.CENTER);root.addView(note);
            Button b=secondary("فعال‌سازی اندازه‌گیری");b.setOnClickListener(v->openAccess());root.addView(b);
            return;
        }
        TextView loading=small("در حال تازه‌سازی…");loading.setGravity(Gravity.CENTER);root.addView(loading);
        readDashboard(this::renderDashboard,e->loading.setText("آمار در دسترس نیست. دوباره برنامه را باز کنید."));
    }

    private void renderDashboard(DashboardData d){
        screen();
        TextView h=title("WiFi Usage");h.setOnClickListener(v->tapAdmin());root.addView(h);
        TextView s=body("مصرف اینترنت این دستگاه");s.setOnClickListener(v->tapAdmin());root.addView(s);
        renderDashboardValues(d,true);
        TextView updated=small("آخرین تازه‌سازی: "+DateFormat.getTimeInstance(DateFormat.SHORT,new Locale("fa","AF")).format(new Date(d.usage.at)));
        updated.setGravity(Gravity.CENTER);root.addView(updated);
    }

    private void renderDashboardValues(DashboardData d,boolean active){
        Totals shownRange=active?displayRange(d.rangeReal,d.range,d.usage):new Totals(0,0);
        LinearLayout hero=card();hero.setOnClickListener(v->tapAdmin());
        TextView lab=small(d.range.label);lab.setGravity(Gravity.CENTER);hero.addView(lab);
        TextView big=strong(active?fmt(shownRange.total()):"—");big.setTextSize(38);big.setGravity(Gravity.CENTER);big.setTextColor(0xFF0F766E);hero.addView(big);
        TextView flow=small(active?("دانلود  "+fmt(shownRange.rx)+"     آپلود  "+fmt(shownRange.tx)):"دانلود  —     آپلود  —");flow.setGravity(Gravity.CENTER);hero.addView(flow);
        root.addView(hero);

        Button filter=secondary("بازه زمانی: "+d.range.label+"  ▾");filter.setOnClickListener(v->showRangeMenu());root.addView(filter);

        long week=shown("week",d.usage.week.total()),month=shown("month",d.usage.month.total());
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setWeightSum(2f);row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.addView(miniMetric("این هفته",active?fmt(week):"—"),new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);mp.setMarginStart(dp(8));
        row.addView(miniMetric("این ماه",active?fmt(month):"—"),mp);root.addView(row);
    }

    private Totals displayRange(Totals real,Range range,Usage u){
        if(range.mode==0){long t=shown("day",u.day.total());return split(u.day,t);}
        if(range.mode==4){long t=shown("month",u.month.total());return split(u.month,t);}
        double ratio=displayRatioForRange(range,u);
        long rx=Math.round(real.rx*ratio),tx=Math.round(real.tx*ratio);
        return new Totals(rx,tx);
    }

    private double displayRatioForRange(Range r,Usage u){
        long duration=Math.max(1,r.end-r.start);
        if(duration<=36L*60*60*1000)return ratio("day",u.day.total());
        if(duration<=9L*24*60*60*1000)return ratio("week",u.week.total());
        return ratio("month",u.month.total());
    }
    private double ratio(String k,long real){long v=p.getLong("display_"+k,-1);if(v<0||real<=0)return 1d;return Math.max(0d,v/(double)real);}

    private View miniMetric(String label,String value){LinearLayout c=card();TextView l=small(label);l.setGravity(Gravity.CENTER);c.addView(l);TextView n=strong(value);n.setTextSize(21);n.setGravity(Gravity.CENTER);c.addView(n);return c;}

    private void showRangeMenu(){
        String[] items={"امروز","دیروز","۷ روز اخیر","۳۰ روز اخیر","این ماه","ماه گذشته","بازه دلخواه"};
        new AlertDialog.Builder(this).setTitle("بازه زمانی").setItems(items,(d,which)->{
            if(which==6){pickCustomStart();return;}
            p.edit().putInt("filter_mode",which).apply();dashboard();
        }).setNegativeButton("انصراف",null).show();
    }

    private void pickCustomStart(){
        Calendar c=Calendar.getInstance();
        new DatePickerDialog(this,(v,y,m,d)->{
            Calendar start=Calendar.getInstance();start.set(y,m,d,0,0,0);start.set(Calendar.MILLISECOND,0);
            pickCustomEnd(start.getTimeInMillis());
        },c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();
    }
    private void pickCustomEnd(long start){
        Calendar c=Calendar.getInstance();
        new DatePickerDialog(this,(v,y,m,d)->{
            Calendar end=Calendar.getInstance();end.set(y,m,d,23,59,59);end.set(Calendar.MILLISECOND,999);
            if(end.getTimeInMillis()<start){toast("تاریخ پایان قبل از تاریخ آغاز است");return;}
            p.edit().putInt("filter_mode",6).putLong("filter_start",start).putLong("filter_end",Math.min(end.getTimeInMillis(),System.currentTimeMillis())).apply();dashboard();
        },c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private Range selectedRange(long now){
        int mode=p.getInt("filter_mode",0);Calendar c=Calendar.getInstance();c.setTimeInMillis(now);
        if(mode==1){long today=startDay(now);return new Range(today-86400000L,today-1,"دیروز",1);}
        if(mode==2)return new Range(startDay(now)-6L*86400000L,now,"۷ روز اخیر",2);
        if(mode==3)return new Range(startDay(now)-29L*86400000L,now,"۳۰ روز اخیر",3);
        if(mode==4)return new Range(startMonth(now),now,"این ماه",4);
        if(mode==5){Calendar x=Calendar.getInstance();x.setTimeInMillis(startMonth(now));x.add(Calendar.MONTH,-1);long st=x.getTimeInMillis();x.add(Calendar.MONTH,1);return new Range(st,x.getTimeInMillis()-1,"ماه گذشته",5);}
        if(mode==6){long st=p.getLong("filter_start",startDay(now)),en=p.getLong("filter_end",now);return new Range(st,Math.min(en,now),dateLabel(st,en),6);}
        return new Range(startDay(now),now,"امروز",0);
    }
    private String dateLabel(long s,long e){SimpleDateFormat f=new SimpleDateFormat("yyyy/MM/dd",new Locale("fa","AF"));return f.format(new Date(s))+" تا "+f.format(new Date(e));}

    private void tapAdmin(){long now=System.currentTimeMillis();if(taps==0||now-tapStart>2200){tapStart=now;taps=1;}else taps++;if(taps>=5){taps=0;openHiddenAdmin();}}
    private void openHiddenAdmin(){if(p.getString("pin","").isEmpty())createFirstPin();else pinGate(this::adminHome);}

    private void createFirstPin(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),0,dp(20),0);
        EditText a=pinInput("PIN جدید، حداقل ۴ رقم"),b=pinInput("تکرار PIN");box.addView(a);box.addView(b);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("تنظیم دسترسی").setView(box).setNegativeButton("انصراف",null).setPositiveButton("ذخیره",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String s=a.getText().toString(),t=b.getText().toString();if(s.length()<4||!s.equals(t)){b.setError("PIN معتبر و یکسان وارد کنید");return;}p.edit().putString("pin",hashPin(s)).apply();d.dismiss();adminHome();}));d.show();
    }
    private void pinGate(Runnable ok){
        EditText e=pinInput("PIN");LinearLayout box=new LinearLayout(this);box.setPadding(dp(20),0,dp(20),0);box.addView(e);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("ورود").setView(box).setNegativeButton("انصراف",null).setPositiveButton("ورود",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(checkPin(e.getText().toString())){d.dismiss();ok.run();}else e.setError("PIN نادرست");}));d.show();
    }

    private void adminHome(){
        screen();root.addView(title("تنظیمات خصوصی"));
        if(!hasAccess()){root.addView(warn("دسترسی آمار مصرف هنوز فعال نیست."));Button a=primary("فعال‌کردن دسترسی");a.setOnClickListener(v->openAccess());root.addView(a);Button b=secondary("بازگشت");b.setOnClickListener(v->dashboard());root.addView(b);return;}
        TextView wait=body("در حال خواندن داده‌ها…");root.addView(wait);read(this::renderAdmin,e->wait.setText("خواندن آمار ممکن نشد."));
    }

    private void renderAdmin(Usage u){
        screen();root.addView(title("تنظیمات خصوصی"));
        root.addView(section("آمار واقعی"));root.addView(realMetric("امروز",u.day));root.addView(realMetric("این هفته",u.week));root.addView(realMetric("این ماه",u.month));
        Button range=secondary("بررسی مصرف واقعی یک بازه");range.setOnClickListener(v->pickPrivateRangeStart());root.addView(range);

        root.addView(section("آمار صفحه اصلی"));
        EditText d=numInput("امروز به MB — خالی = واقعی",overrideText("day")),w=numInput("هفته به MB — خالی = واقعی",overrideText("week")),m=numInput("ماه به MB — خالی = واقعی",overrideText("month"));
        root.addView(d);root.addView(w);root.addView(m);
        Button save=primary("ذخیره");save.setOnClickListener(v->{savePrev();saveOverride("day",d.getText().toString());saveOverride("week",w.getText().toString());saveOverride("month",m.getText().toString());log("مقادیر نمایشی تغییر کرد");renderAdmin(u);});root.addView(save);
        Button real=secondary("برگرداندن صفحه به آمار واقعی");real.setOnClickListener(v->{p.edit().remove("display_day").remove("display_week").remove("display_month").apply();log("نمایش به مقدار واقعی برگشت");renderAdmin(u);});root.addView(real);
        Button undo=secondary("برگرداندن آخرین تغییر");undo.setOnClickListener(v->{undo();renderAdmin(u);});root.addView(undo);

        root.addView(section("این دستگاه"));
        EditText name=input("نام یا شناسه دستگاه");name.setText(p.getString("student",""));root.addView(name);
        Button ns=secondary("ذخیره نام");ns.setOnClickListener(v->{String n=name.getText().toString().trim();if(!n.isEmpty())p.edit().putString("student",n).apply();toast("ذخیره شد");});root.addView(ns);
        String pair=p.getString("pair","");String code=reportCode(u);
        Button report=secondary("کپی گزارش واقعی این دستگاه");report.setOnClickListener(v->copy(code));root.addView(report);
        Button key=secondary("کپی کلید اتصال");key.setOnClickListener(v->copy(pair));root.addView(key);
        Button manager=secondary("پنل مجموع دستگاه‌ها");manager.setOnClickListener(v->managerHome());root.addView(manager);
        root.addView(section("امنیت"));Button pin=secondary("تغییر PIN");pin.setOnClickListener(v->changePin());root.addView(pin);
        Button back=primary("بازگشت به آمار");back.setOnClickListener(v->dashboard());root.addView(back);
    }

    private void pickPrivateRangeStart(){
        Calendar c=Calendar.getInstance();
        new DatePickerDialog(this,(v,y,m,d)->{Calendar st=Calendar.getInstance();st.set(y,m,d,0,0,0);st.set(Calendar.MILLISECOND,0);pickPrivateRangeEnd(st.getTimeInMillis());},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();
    }
    private void pickPrivateRangeEnd(long start){
        Calendar c=Calendar.getInstance();
        new DatePickerDialog(this,(v,y,m,d)->{Calendar en=Calendar.getInstance();en.set(y,m,d,23,59,59);en.set(Calendar.MILLISECOND,999);long end=Math.min(en.getTimeInMillis(),System.currentTimeMillis());if(end<start){toast("تاریخ پایان قبل از تاریخ آغاز است");return;}worker.execute(()->{Totals t=query(start,end);runOnUiThread(()->showPrivateRangeResult(start,end,t));});},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();
    }
    private void showPrivateRangeResult(long s,long e,Totals t){
        String msg="مجموع: "+fmt(t.total())+"\nدانلود: "+fmt(t.rx)+"\nآپلود: "+fmt(t.tx);
        new AlertDialog.Builder(this).setTitle(dateLabel(s,e)).setMessage(msg).setPositiveButton("بستن",null).show();
    }

    private void managerHome(){
        screen();root.addView(title("مجموع مصرف"));String ids=p.getString("mgr_ids","");long td=0,tw=0,tm=0;int count=0;
        if(!ids.isEmpty())for(String id:ids.split("\\n")){if(id.trim().isEmpty())continue;count++;String k=safe(id);long d=p.getLong("mgr_"+k+"_day",0),w=p.getLong("mgr_"+k+"_week",0),m=p.getLong("mgr_"+k+"_month",0);td+=d;tw+=w;tm+=m;LinearLayout c=card();c.addView(strong(id));c.addView(small("امروز: "+fmt(d)+"   هفته: "+fmt(w)+"   ماه: "+fmt(m)));root.addView(c);}
        root.addView(section("مجموع "+count+" دستگاه"));root.addView(totalCard("امروز",td));root.addView(totalCard("این هفته",tw));root.addView(totalCard("این ماه",tm));
        Button imp=primary("واردکردن گزارش دستگاه");imp.setOnClickListener(v->importDialog());root.addView(imp);
        Button clear=secondary("پاک‌کردن فهرست");clear.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("همه گزارش‌های ذخیره‌شده پاک شوند؟").setNegativeButton("نه",null).setPositiveButton("بلی",(x,y)->{SharedPreferences.Editor ed=p.edit();for(String key:p.getAll().keySet())if(key.startsWith("mgr_"))ed.remove(key);ed.remove("mgr_ids").apply();managerHome();}).show());root.addView(clear);
        Button back=secondary("بازگشت به تنظیمات خصوصی");back.setOnClickListener(v->adminHome());root.addView(back);
    }

    private void importDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),0,dp(20),0);EditText code=input("کد گزارش"),key=input("کلید اتصال");box.addView(code);box.addView(key);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("ورود گزارش").setView(box).setNegativeButton("انصراف",null).setPositiveButton("بررسی و ذخیره",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{Report r=parseReport(code.getText().toString().trim(),key.getText().toString().trim());if(r==null){code.setError("کد یا کلید معتبر نیست");return;}saveReport(r);d.dismiss();managerHome();}));d.show();
    }

    private String reportCode(Usage u){String id=p.getString("student","");String enc=Base64.encodeToString(id.getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);String body="WUM1|"+enc+"|"+u.at+"|"+u.day.total()+"|"+u.week.total()+"|"+u.month.total();return body+"|"+hmac(body,p.getString("pair",""));}
    private Report parseReport(String code,String key){try{String[] a=code.split("\\|");if(a.length!=7||!"WUM1".equals(a[0]))return null;String body=String.join("|",a[0],a[1],a[2],a[3],a[4],a[5]);if(!MessageDigest.isEqual(hmac(body,key).getBytes(StandardCharsets.UTF_8),a[6].getBytes(StandardCharsets.UTF_8)))return null;String id=new String(Base64.decode(a[1],Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),StandardCharsets.UTF_8);return new Report(id,Long.parseLong(a[3]),Long.parseLong(a[4]),Long.parseLong(a[5]),Long.parseLong(a[2]));}catch(Exception e){return null;}}
    private String hmac(String body,String key){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.encodeToString(m.doFinal(body.getBytes(StandardCharsets.UTF_8)),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}catch(Exception e){return "";}}
    private void saveReport(Report r){String ids=p.getString("mgr_ids","");boolean found=false;if(!ids.isEmpty())for(String x:ids.split("\\n"))if(x.equals(r.id))found=true;if(!found)ids=ids.isEmpty()?r.id:ids+"\n"+r.id;String k=safe(r.id);p.edit().putString("mgr_ids",ids).putLong("mgr_"+k+"_day",r.day).putLong("mgr_"+k+"_week",r.week).putLong("mgr_"+k+"_month",r.month).putLong("mgr_"+k+"_at",r.at).apply();}

    private boolean hasAccess(){AppOpsManager a=(AppOpsManager)getSystemService(APP_OPS_SERVICE);return a!=null&&a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),getPackageName())==AppOpsManager.MODE_ALLOWED;}
    private void openAccess(){waitingForUsageAccess=true;startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));}

    interface Ok{void done(Usage u);} interface Fail{void bad(Exception e);} interface DashOk{void done(DashboardData d);}
    private void read(Ok ok,Fail fail){worker.execute(()->{try{Usage u=readNow();runOnUiThread(()->ok.done(u));}catch(Exception e){runOnUiThread(()->fail.bad(e));}});}
    private void readDashboard(DashOk ok,Fail fail){worker.execute(()->{try{Usage u=readNow();Range r=selectedRange(u.at);Totals t=query(r.start,r.end);DashboardData d=new DashboardData(u,r,t);runOnUiThread(()->ok.done(d));}catch(Exception e){runOnUiThread(()->fail.bad(e));}});}
    private Usage readNow(){long n=System.currentTimeMillis();return new Usage(query(startDay(n),n),query(startWeek(n),n),query(startMonth(n),n),n);}
    private Totals query(long s,long e){try{NetworkStatsManager m=(NetworkStatsManager)getSystemService(NETWORK_STATS_SERVICE);if(m==null)return new Totals(0,0);NetworkStats.Bucket b=m.querySummaryForDevice(ConnectivityManager.TYPE_WIFI,null,s,e);if(b==null)return new Totals(0,0);return new Totals(b.getRxBytes(),b.getTxBytes());}catch(Exception ex){return new Totals(0,0);}}

    private long startDay(long n){Calendar c=Calendar.getInstance();c.setTimeInMillis(n);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
    private long startWeek(long n){Calendar c=Calendar.getInstance();c.setTimeInMillis(n);c.setFirstDayOfWeek(Calendar.SATURDAY);c.set(Calendar.DAY_OF_WEEK,Calendar.SATURDAY);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);if(c.getTimeInMillis()>n)c.add(Calendar.DAY_OF_MONTH,-7);return c.getTimeInMillis();}
    private long startMonth(long n){Calendar c=Calendar.getInstance();c.setTimeInMillis(n);c.set(Calendar.DAY_OF_MONTH,1);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}

    private long shown(String k,long real){long x=p.getLong("display_"+k,-1);return x<0?real:x;}
    private String overrideText(String k){long x=p.getLong("display_"+k,-1);return x<0?"":String.format(Locale.US,"%.1f",x/(1024d*1024d));}
    private void savePrev(){SharedPreferences.Editor e=p.edit();for(String x:new String[]{"day","week","month"})e.putLong("prev_"+x,p.getLong("display_"+x,-1));e.apply();}
    private void saveOverride(String k,String v){if(v==null||v.trim().isEmpty()){p.edit().remove("display_"+k).apply();return;}try{double mb=Double.parseDouble(v.trim());p.edit().putLong("display_"+k,(long)Math.max(0,mb*1024d*1024d)).apply();}catch(Exception ignored){}}
    private void undo(){SharedPreferences.Editor e=p.edit();for(String x:new String[]{"day","week","month"}){long v=p.getLong("prev_"+x,-1);if(v<0)e.remove("display_"+x);else e.putLong("display_"+x,v);}e.apply();}
    private Totals split(Totals r,long total){long t=r.total();if(t<=0)return new Totals(total,0);long rx=Math.round(total*(r.rx/(double)t));return new Totals(rx,total-rx);}

    private String hashPin(String pin){try{MessageDigest d=MessageDigest.getInstance("SHA-256");return Base64.encodeToString(d.digest(("wum-v1:"+pin).getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);}catch(Exception e){return pin;}}
    private boolean checkPin(String pin){return MessageDigest.isEqual(hashPin(pin).getBytes(StandardCharsets.UTF_8),p.getString("pin","").getBytes(StandardCharsets.UTF_8));}
    private void changePin(){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(20),0,dp(20),0);EditText a=pinInput("PIN جدید"),c=pinInput("تکرار PIN");b.addView(a);b.addView(c);new AlertDialog.Builder(this).setTitle("تغییر PIN").setView(b).setNegativeButton("انصراف",null).setPositiveButton("ذخیره",(x,y)->{String s=a.getText().toString(),t=c.getText().toString();if(s.length()>=4&&s.equals(t)){p.edit().putString("pin",hashPin(s)).apply();toast("PIN تغییر کرد");}else toast("PIN معتبر و یکسان وارد کنید");}).show();}
    private String randomKey(){String chars="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";SecureRandom r=new SecureRandom();StringBuilder b=new StringBuilder();for(int i=0;i<20;i++)b.append(chars.charAt(r.nextInt(chars.length())));return b.toString();}
    private String safe(String s){return Integer.toHexString(s.hashCode())+"_"+s.length();}
    private void log(String s){String old=p.getString("log","");String line=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,new Locale("fa","AF")).format(new Date())+" — "+s;p.edit().putString("log",line+(old.isEmpty()?"":"\n"+old)).apply();}
    private void copy(String s){ClipboardManager c=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(c!=null)c.setPrimaryClip(ClipData.newPlainText("WiFi Usage",s));toast("کپی شد");}

    private void screen(){ScrollView s=new ScrollView(this);s.setFillViewport(true);s.setBackgroundColor(0xFFF6F8FA);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(24),dp(18),dp(30));root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);s.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(s);}
    private TextView title(String s){TextView v=new TextView(this);v.setText(s);v.setTextSize(25);v.setTypeface(Typeface.DEFAULT_BOLD);v.setTextColor(0xFF182230);v.setGravity(Gravity.CENTER);v.setPadding(0,0,0,dp(5));return v;}
    private TextView section(String s){TextView v=title(s);v.setGravity(Gravity.RIGHT);v.setTextSize(18);v.setPadding(0,dp(18),0,dp(8));return v;}
    private TextView body(String s){TextView v=new TextView(this);v.setText(s);v.setTextSize(14);v.setTextColor(0xFF667085);v.setGravity(Gravity.CENTER);v.setPadding(0,0,0,dp(14));return v;}
    private TextView small(String s){TextView v=body(s);v.setTextSize(13);v.setPadding(0,dp(3),0,dp(6));return v;}
    private TextView strong(String s){TextView v=body(s);v.setTextSize(17);v.setTypeface(Typeface.DEFAULT_BOLD);v.setTextColor(0xFF182230);v.setPadding(0,0,0,dp(4));return v;}
    private TextView warn(String s){TextView v=body(s);v.setTextColor(0xFFB42318);v.setBackground(bg(0xFFFFE9E7));v.setPadding(dp(12),dp(12),dp(12),dp(12));margin(v,0,0,0,10);return v;}
    private EditText input(String h){EditText e=new EditText(this);e.setHint(h);e.setTextSize(15);e.setGravity(Gravity.RIGHT);e.setBackground(bg(0xFFFFFFFF));e.setPadding(dp(14),dp(12),dp(14),dp(12));margin(e,0,5,0,8);return e;}
    private EditText pinInput(String h){EditText e=input(h);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);return e;}
    private EditText numInput(String h,String v){EditText e=input(h);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);e.setText(v);return e;}
    private Button primary(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(0xFFFFFFFF);b.setTextSize(15);b.setBackground(bg(0xFF0F766E));margin(b,0,5,0,8);return b;}
    private Button secondary(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(0xFF182230);b.setTextSize(15);b.setBackground(bg(0xFFFFFFFF));margin(b,0,5,0,8);return b;}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(15),dp(14),dp(15),dp(14));c.setBackground(bg(0xFFFFFFFF));margin(c,0,4,0,10);return c;}
    private View realMetric(String label,Totals t){LinearLayout c=card();TextView a=small(label);a.setGravity(Gravity.RIGHT);c.addView(a);TextView n=strong(fmt(t.total()));n.setTextSize(23);n.setTextColor(0xFF0F766E);n.setGravity(Gravity.RIGHT);c.addView(n);TextView b=small("دانلود: "+fmt(t.rx)+"     آپلود: "+fmt(t.tx));b.setGravity(Gravity.RIGHT);c.addView(b);return c;}
    private View totalCard(String label,long t){LinearLayout c=card();c.addView(small(label));TextView n=strong(fmt(t));n.setTextSize(25);n.setGravity(Gravity.CENTER);c.addView(n);return c;}
    private GradientDrawable bg(int c){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(16));return d;}
    private void margin(View v,int l,int t,int r,int b){LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);q.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(q);}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private String fmt(long b){if(b<1024)return b+" B";double k=b/1024d;if(k<1024)return String.format(Locale.US,"%.1f KB",k);double m=k/1024d;if(m<1024)return String.format(Locale.US,"%.1f MB",m);return String.format(Locale.US,"%.2f GB",m/1024d);}
}
