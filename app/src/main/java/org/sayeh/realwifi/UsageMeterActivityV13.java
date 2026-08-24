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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class UsageMeterActivityV13 extends Activity {
    private SharedPreferences p;
    private LinearLayout root;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int taps = 0;
    private long tapStart = 0;
    private boolean waitingForUsageAccess = false;

    private int selectedType = ConnectivityManager.TYPE_WIFI;
    private String rangeLabel = "امروز";
    private long rangeStart;
    private long rangeEnd;

    static final class Totals {
        final long rx, tx;
        Totals(long rx, long tx) { this.rx=Math.max(0,rx); this.tx=Math.max(0,tx); }
        long total(){ return rx+tx; }
    }

    static final class AppUse {
        final int uid;
        final String packageName;
        final String label;
        final Drawable icon;
        final Totals totals;
        AppUse(int uid,String pkg,String label,Drawable icon,Totals totals){
            this.uid=uid;this.packageName=pkg;this.label=label;this.icon=icon;this.totals=totals;
        }
    }

    static final class DashboardData {
        final Totals raw;
        final Totals shown;
        final long[] series;
        final List<AppUse> apps;
        final double scale;
        DashboardData(Totals raw,Totals shown,long[] series,List<AppUse> apps,double scale){
            this.raw=raw;this.shown=shown;this.series=series;this.apps=apps;this.scale=scale;
        }
    }

    static final class Report {
        final String id;
        final long wd,ww,wm,md,mw,mm,at;
        Report(String id,long wd,long ww,long wm,long md,long mw,long mm,long at){
            this.id=id;this.wd=wd;this.ww=ww;this.wm=wm;this.md=md;this.mw=mw;this.mm=mm;this.at=at;
        }
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        p=getSharedPreferences("wifi_usage_meter_v1",MODE_PRIVATE);
        initialiseDefaults();
        selectedType=p.getInt("ui_network",ConnectivityManager.TYPE_WIFI);
        setTodayRange();
        dashboard();
    }

    @Override protected void onResume(){
        super.onResume();
        if(waitingForUsageAccess){waitingForUsageAccess=false;dashboard();}
    }

    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}

    private void initialiseDefaults(){
        SharedPreferences.Editor e=p.edit();
        String pair=p.getString("pair","");
        if(pair.isEmpty()){pair=randomKey();e.putString("pair",pair);}
        if(p.getString("student","").isEmpty())e.putString("student","Device-"+pair.substring(0,4));
        // migrate v1.2 Wi-Fi overrides once
        for(String k:new String[]{"day","week","month"}){
            if(!p.contains("display_wifi_"+k) && p.contains("display_"+k))e.putLong("display_wifi_"+k,p.getLong("display_"+k,-1));
        }
        e.apply();
    }

    private void setTodayRange(){
        long now=System.currentTimeMillis();
        rangeLabel="امروز";rangeStart=startDay(now);rangeEnd=now;
    }

    private void dashboard(){
        screen();
        addTopBar();
        addFilterRow();

        if(!hasAccess()){
            root.addView(emptyChart());
            root.addView(summaryView(new Totals(0,0),false));
            TextView n=small("برای نمایش آمار واقعی، دسترسی اندازه‌گیری را یک‌بار فعال کنید.");n.setGravity(Gravity.CENTER);root.addView(n);
            Button b=secondary("فعال‌سازی اندازه‌گیری");b.setOnClickListener(v->openAccess());root.addView(b);
            return;
        }

        TextView loading=small("در حال خواندن مصرف…");loading.setGravity(Gravity.CENTER);root.addView(loading);
        long s=rangeStart,e=rangeEnd;int type=selectedType;
        worker.execute(()->{
            try{
                DashboardData data=loadDashboard(type,s,e);
                runOnUiThread(()->renderDashboard(data));
            }catch(Exception ex){
                runOnUiThread(()->loading.setText("خواندن آمار ممکن نشد. دوباره برنامه را باز کنید."));
            }
        });
    }

    private void renderDashboard(DashboardData data){
        screen();addTopBar();addFilterRow();
        ChartView chart=new ChartView(this,data.series);chart.setOnClickListener(v->tapAdmin());
        root.addView(chart,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(230)));
        root.addView(summaryView(data.shown,true));

        TextView appsTitle=section("مصرف برنامه‌ها");appsTitle.setTextSize(17);root.addView(appsTitle);
        if(data.apps.isEmpty()){
            TextView none=small("برای این بازه، مصرف برنامه‌ای ثبت نشده است.");none.setGravity(Gravity.CENTER);root.addView(none);
        }else{
            long max=1;
            for(AppUse a:data.apps) max=Math.max(max,(long)(a.totals.total()*data.scale));
            int shownCount=Math.min(12,data.apps.size());
            for(int i=0;i<shownCount;i++)root.addView(appRow(data.apps.get(i),data.scale,max));
        }
        TextView updated=small("آخرین تازه‌سازی: "+DateFormat.getTimeInstance(DateFormat.SHORT,new Locale("fa","AF")).format(new Date()));
        updated.setGravity(Gravity.CENTER);root.addView(updated);
    }

    private void addTopBar(){
        LinearLayout bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(0,0,0,dp(12));
        TextView search=new TextView(this);search.setText("⌕");search.setTextSize(34);search.setTextColor(0xFF607D86);search.setGravity(Gravity.CENTER);
        TextView title=new TextView(this);title.setText("Usage Meter");title.setTextSize(26);title.setTypeface(Typeface.DEFAULT_BOLD);title.setTextColor(0xFF5D747C);title.setGravity(Gravity.CENTER);title.setOnClickListener(v->tapAdmin());
        TextView logo=new TextView(this);logo.setText("⌁");logo.setTextSize(28);logo.setTextColor(0xFF31C8EB);logo.setGravity(Gravity.CENTER);logo.setBackground(roundBg(0xFFF2FCFF,0xFFBFDCE4,1));logo.setOnClickListener(v->tapAdmin());
        bar.addView(search,new LinearLayout.LayoutParams(dp(58),dp(52)));
        bar.addView(title,new LinearLayout.LayoutParams(0,dp(52),1f));
        bar.addView(logo,new LinearLayout.LayoutParams(dp(58),dp(52)));
        root.addView(bar);
    }

    private void addFilterRow(){
        LinearLayout filters=new LinearLayout(this);filters.setOrientation(LinearLayout.HORIZONTAL);filters.setGravity(Gravity.CENTER);filters.setPadding(0,0,0,dp(10));
        TextView range=filterText(rangeLabel+"  ▾");range.setOnClickListener(v->showRangeChooser());
        TextView network=filterText((selectedType==ConnectivityManager.TYPE_WIFI?"Wi‑Fi":"Mobile data")+"  ▾");network.setOnClickListener(v->showNetworkChooser());
        filters.addView(range,new LinearLayout.LayoutParams(0,dp(48),1f));
        filters.addView(network,new LinearLayout.LayoutParams(0,dp(48),1f));
        root.addView(filters);
        TextView dates=small(formatRange(rangeStart,rangeEnd));dates.setGravity(Gravity.CENTER);dates.setTextColor(0xFF8FA6AD);root.addView(dates);
    }

    private TextView filterText(String text){
        TextView v=new TextView(this);v.setText(text);v.setTextSize(17);v.setTextColor(0xFF607D86);v.setGravity(Gravity.CENTER);return v;
    }

    private void showNetworkChooser(){
        String[] items={"Wi‑Fi","Mobile data"};
        new AlertDialog.Builder(this).setTitle("نوع شبکه").setSingleChoiceItems(items,selectedType==ConnectivityManager.TYPE_WIFI?0:1,(d,w)->{
            selectedType=w==0?ConnectivityManager.TYPE_WIFI:ConnectivityManager.TYPE_MOBILE;
            p.edit().putInt("ui_network",selectedType).apply();d.dismiss();dashboard();
        }).show();
    }

    private void showRangeChooser(){
        String[] items={"امروز","دیروز","۷ روز اخیر","۳۰ روز اخیر","این ماه","ماه گذشته","بازهٔ دلخواه"};
        new AlertDialog.Builder(this).setTitle("بازهٔ زمانی").setItems(items,(d,w)->{
            long now=System.currentTimeMillis();
            if(w==0){rangeLabel="امروز";rangeStart=startDay(now);rangeEnd=now;dashboard();return;}
            if(w==1){rangeLabel="دیروز";rangeEnd=startDay(now);rangeStart=addDays(rangeEnd,-1);dashboard();return;}
            if(w==2){rangeLabel="۷ روز اخیر";rangeStart=addDays(startDay(now),-6);rangeEnd=now;dashboard();return;}
            if(w==3){rangeLabel="۳۰ روز اخیر";rangeStart=addDays(startDay(now),-29);rangeEnd=now;dashboard();return;}
            if(w==4){rangeLabel="این ماه";rangeStart=startMonth(now);rangeEnd=now;dashboard();return;}
            if(w==5){Calendar c=Calendar.getInstance();c.setTimeInMillis(startMonth(now));rangeEnd=c.getTimeInMillis();c.add(Calendar.MONTH,-1);rangeStart=c.getTimeInMillis();rangeLabel="ماه گذشته";dashboard();return;}
            pickCustomRange(false);
        }).show();
    }

    private void pickCustomRange(boolean realOnly){
        Calendar now=Calendar.getInstance();
        new DatePickerDialog(this,(a,y,m,d)->{
            Calendar from=Calendar.getInstance();from.set(y,m,d,0,0,0);from.set(Calendar.MILLISECOND,0);
            new DatePickerDialog(this,(b,y2,m2,d2)->{
                Calendar to=Calendar.getInstance();to.set(y2,m2,d2,23,59,59);to.set(Calendar.MILLISECOND,999);
                if(to.getTimeInMillis()<from.getTimeInMillis()){toast("تاریخ پایان باید بعد از تاریخ آغاز باشد");return;}
                long end=Math.min(to.getTimeInMillis(),System.currentTimeMillis());
                if(realOnly) showPrivateRealRange(from.getTimeInMillis(),end);
                else {rangeLabel="Custom";rangeStart=from.getTimeInMillis();rangeEnd=end;dashboard();}
            },now.get(Calendar.YEAR),now.get(Calendar.MONTH),now.get(Calendar.DAY_OF_MONTH)).show();
        },now.get(Calendar.YEAR),now.get(Calendar.MONTH),now.get(Calendar.DAY_OF_MONTH)).show();
    }

    private DashboardData loadDashboard(int type,long start,long end){
        Totals raw=query(type,start,end);
        double scale=displayScale(type,start,end,raw.total());
        Totals shown=scaleTotals(raw,scale);
        long[] series=querySeries(type,start,end,24,scale);
        List<AppUse> apps=queryApps(type,start,end);
        return new DashboardData(raw,shown,series,apps,scale);
    }

    private double displayScale(int type,long start,long end,long currentRaw){
        String net=type==ConnectivityManager.TYPE_WIFI?"wifi":"mobile";
        long now=System.currentTimeMillis();
        String period=null;long refStart=0,refEnd=now;
        if(near(start,startDay(now)) && end>=now-120000){period="day";refStart=startDay(now);}
        else if(near(start,startWeek(now)) && end>=now-120000){period="week";refStart=startWeek(now);}
        else if(near(start,startMonth(now)) && end>=now-120000){period="month";refStart=startMonth(now);}
        else if(p.getLong("display_"+net+"_month",-1)>=0){period="month";refStart=startMonth(now);}
        else if(p.getLong("display_"+net+"_week",-1)>=0){period="week";refStart=startWeek(now);}
        else if(p.getLong("display_"+net+"_day",-1)>=0){period="day";refStart=startDay(now);}
        if(period==null)return 1d;
        long override=p.getLong("display_"+net+"_"+period,-1);if(override<0)return 1d;
        long real=(near(start,refStart)&&end>=now-120000)?currentRaw:query(type,refStart,refEnd).total();
        if(real<=0)return override==0?0d:1d;
        double scale=override/(double)real;
        return Math.max(0d,Math.min(scale,1000d));
    }

    private boolean near(long a,long b){return Math.abs(a-b)<2000;}
    private Totals scaleTotals(Totals t,double scale){return new Totals(Math.round(t.rx*scale),Math.round(t.tx*scale));}

    private long[] querySeries(int type,long start,long end,int count,double scale){
        long[] out=new long[count];long span=Math.max(1,end-start);long step=Math.max(1,span/count);
        for(int i=0;i<count;i++){
            long s=start+i*step;long e=(i==count-1)?end:Math.min(end,s+step);
            out[i]=Math.round(query(type,s,e).total()*scale);
        }
        return out;
    }

    private List<AppUse> queryApps(int type,long start,long end){
        Map<Integer,long[]> byUid=new HashMap<>();NetworkStats stats=null;
        try{
            NetworkStatsManager n=(NetworkStatsManager)getSystemService(NETWORK_STATS_SERVICE);
            if(n==null)return new ArrayList<>();
            stats=n.querySummary(type,null,start,end);
            NetworkStats.Bucket b=new NetworkStats.Bucket();
            while(stats.hasNextBucket()){
                stats.getNextBucket(b);int uid=b.getUid();
                if(uid<0)continue;long[] x=byUid.get(uid);if(x==null){x=new long[]{0,0};byUid.put(uid,x);}x[0]+=Math.max(0,b.getRxBytes());x[1]+=Math.max(0,b.getTxBytes());
            }
        }catch(Exception ignored){} finally {if(stats!=null)try{stats.close();}catch(Exception ignored){}}
        PackageManager pm=getPackageManager();List<AppUse> out=new ArrayList<>();
        for(Map.Entry<Integer,long[]> en:byUid.entrySet()){
            long total=en.getValue()[0]+en.getValue()[1];if(total<=0)continue;
            int uid=en.getKey();String[] pkgs=pm.getPackagesForUid(uid);String pkg=(pkgs!=null&&pkgs.length>0)?pkgs[0]:null;String label="System / UID "+uid;Drawable icon=null;
            if(pkg!=null){try{ApplicationInfo ai=pm.getApplicationInfo(pkg,0);label=pm.getApplicationLabel(ai).toString();icon=pm.getApplicationIcon(ai);}catch(Exception ignored){label=pkg;}}
            out.add(new AppUse(uid,pkg,label,icon,new Totals(en.getValue()[0],en.getValue()[1])));
        }
        Collections.sort(out,(a,b)->Long.compare(b.totals.total(),a.totals.total()));return out;
    }

    private View emptyChart(){return new ChartView(this,new long[24]);}

    private View summaryView(Totals t,boolean active){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.HORIZONTAL);box.setGravity(Gravity.CENTER);box.setPadding(0,dp(4),0,dp(10));box.setOnClickListener(v->tapAdmin());
        TextView down=summarySide("↓",active?fmt(t.rx):"—",0xFF2EC4E6);
        DonutView donut=new DonutView(this,active?t:new Totals(0,0),active);donut.setOnClickListener(v->tapAdmin());
        TextView up=summarySide("↑",active?fmt(t.tx):"—",0xFF73EE00);
        box.addView(down,new LinearLayout.LayoutParams(0,dp(148),1f));
        box.addView(donut,new LinearLayout.LayoutParams(dp(160),dp(148)));
        box.addView(up,new LinearLayout.LayoutParams(0,dp(148),1f));
        return box;
    }

    private TextView summarySide(String arrow,String value,int color){
        TextView v=new TextView(this);v.setText(arrow+"\n"+value);v.setTextSize(17);v.setTextColor(0xFF62777D);v.setGravity(Gravity.CENTER);v.setLineSpacing(dp(4),1f);return v;
    }

    private View appRow(AppUse a,double scale,long maxShown){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(6),dp(10),dp(6),dp(10));
        ImageView icon=new ImageView(this);if(a.icon!=null)icon.setImageDrawable(a.icon);else icon.setImageDrawable(null);row.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout mid=new LinearLayout(this);mid.setOrientation(LinearLayout.VERTICAL);mid.setPadding(dp(10),0,dp(10),0);
        TextView name=new TextView(this);name.setText(a.label);name.setTextSize(17);name.setTextColor(0xFF263238);mid.addView(name);
        long shown=Math.round(a.totals.total()*scale);ProgressBarView bar=new ProgressBarView(this,maxShown==0?0:shown/(float)maxShown);mid.addView(bar,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(10)));
        row.addView(mid,new LinearLayout.LayoutParams(0,dp(62),1f));
        TextView amount=new TextView(this);amount.setText(fmt(shown));amount.setTextSize(15);amount.setTextColor(0xFF667B82);amount.setGravity(Gravity.CENTER);row.addView(amount,new LinearLayout.LayoutParams(dp(92),dp(54)));
        View line=new View(this);line.setBackgroundColor(0xFFE5ECEF);
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.addView(row);wrap.addView(line,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,1));return wrap;
    }

    private void tapAdmin(){
        long now=System.currentTimeMillis();if(taps==0||now-tapStart>2200){tapStart=now;taps=1;}else taps++;
        if(taps>=5){taps=0;if(p.getString("pin","").isEmpty())createFirstPin();else pinGate(this::adminHome);}
    }

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
        if(!hasAccess()){root.addView(warn("دسترسی آمار مصرف هنوز فعال نیست."));Button a=primary("فعال‌کردن دسترسی");a.setOnClickListener(v->openAccess());root.addView(a);return;}
        TextView wait=body("در حال خواندن دادهٔ واقعی…");root.addView(wait);
        worker.execute(()->{
            long n=System.currentTimeMillis();
            Totals wd=query(ConnectivityManager.TYPE_WIFI,startDay(n),n),ww=query(ConnectivityManager.TYPE_WIFI,startWeek(n),n),wm=query(ConnectivityManager.TYPE_WIFI,startMonth(n),n);
            Totals md=query(ConnectivityManager.TYPE_MOBILE,startDay(n),n),mw=query(ConnectivityManager.TYPE_MOBILE,startWeek(n),n),mm=query(ConnectivityManager.TYPE_MOBILE,startMonth(n),n);
            runOnUiThread(()->renderAdmin(wd,ww,wm,md,mw,mm));
        });
    }

    private void renderAdmin(Totals wd,Totals ww,Totals wm,Totals md,Totals mw,Totals mm){
        screen();root.addView(title("تنظیمات خصوصی"));
        root.addView(section("آمار واقعی Wi‑Fi"));root.addView(realMetric("امروز",wd));root.addView(realMetric("این هفته",ww));root.addView(realMetric("این ماه",wm));
        root.addView(section("آمار واقعی Mobile data"));root.addView(realMetric("امروز",md));root.addView(realMetric("این هفته",mw));root.addView(realMetric("این ماه",mm));

        root.addView(section("آمار نمایشی Wi‑Fi"));
        EditText wfd=numInput("امروز به MB — خالی = واقعی",overrideText("wifi","day"));EditText wfw=numInput("هفته به MB — خالی = واقعی",overrideText("wifi","week"));EditText wfm=numInput("ماه به MB — خالی = واقعی",overrideText("wifi","month"));root.addView(wfd);root.addView(wfw);root.addView(wfm);
        root.addView(section("آمار نمایشی Mobile data"));
        EditText mdd=numInput("امروز به MB — خالی = واقعی",overrideText("mobile","day"));EditText mdw=numInput("هفته به MB — خالی = واقعی",overrideText("mobile","week"));EditText mdm=numInput("ماه به MB — خالی = واقعی",overrideText("mobile","month"));root.addView(mdd);root.addView(mdw);root.addView(mdm);
        Button save=primary("ذخیره مقادیر نمایشی");save.setOnClickListener(v->{saveOverride("wifi","day",wfd.getText().toString());saveOverride("wifi","week",wfw.getText().toString());saveOverride("wifi","month",wfm.getText().toString());saveOverride("mobile","day",mdd.getText().toString());saveOverride("mobile","week",mdw.getText().toString());saveOverride("mobile","month",mdm.getText().toString());log("مقادیر نمایشی Wi‑Fi/Mobile تغییر کرد");adminHome();});root.addView(save);
        Button real=secondary("بازگرداندن همهٔ نمایش‌ها به واقعی");real.setOnClickListener(v->{SharedPreferences.Editor e=p.edit();for(String net:new String[]{"wifi","mobile"})for(String per:new String[]{"day","week","month"})e.remove("display_"+net+"_"+per);e.apply();log("همهٔ نمایش‌ها به واقعی برگشت");adminHome();});root.addView(real);

        Button range=secondary("بررسی مصرف واقعی یک بازه");range.setOnClickListener(v->choosePrivateNetworkForRange());root.addView(range);

        root.addView(section("این دستگاه"));EditText name=input("نام یا شناسه دستگاه");name.setText(p.getString("student",""));root.addView(name);Button ns=secondary("ذخیره نام");ns.setOnClickListener(v->{String s=name.getText().toString().trim();if(!s.isEmpty())p.edit().putString("student",s).apply();toast("ذخیره شد");});root.addView(ns);
        String report=reportCode(wd,ww,wm,md,mw,mm);Button copyReport=secondary("کپی گزارش واقعی این دستگاه");copyReport.setOnClickListener(v->copy(report));root.addView(copyReport);Button key=secondary("کپی کلید اتصال");key.setOnClickListener(v->copy(p.getString("pair","")));root.addView(key);Button manager=secondary("پنل مجموع دستگاه‌ها");manager.setOnClickListener(v->managerHome());root.addView(manager);
        root.addView(section("امنیت"));Button pin=secondary("تغییر PIN");pin.setOnClickListener(v->changePin());root.addView(pin);Button back=primary("بازگشت به آمار");back.setOnClickListener(v->dashboard());root.addView(back);
    }

    private int privateRangeType=ConnectivityManager.TYPE_WIFI;
    private void choosePrivateNetworkForRange(){
        String[] items={"Wi‑Fi","Mobile data"};new AlertDialog.Builder(this).setTitle("شبکهٔ واقعی").setItems(items,(d,w)->{privateRangeType=w==0?ConnectivityManager.TYPE_WIFI:ConnectivityManager.TYPE_MOBILE;pickCustomRange(true);}).show();
    }

    private void showPrivateRealRange(long s,long e){
        worker.execute(()->{Totals t=query(privateRangeType,s,e);runOnUiThread(()->{
            LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(22),dp(10),dp(22),dp(4));TextView n=strong(fmt(t.total()));n.setTextSize(30);n.setGravity(Gravity.CENTER);n.setTextColor(0xFF0F9FB9);box.addView(n);TextView f=small("دانلود: "+fmt(t.rx)+"     آپلود: "+fmt(t.tx));f.setGravity(Gravity.CENTER);box.addView(f);TextView q=small(formatRange(s,e));q.setGravity(Gravity.CENTER);box.addView(q);new AlertDialog.Builder(this).setTitle((privateRangeType==ConnectivityManager.TYPE_WIFI?"Wi‑Fi":"Mobile")+" — واقعی").setView(box).setPositiveButton("بستن",null).show();});});
    }

    private void managerHome(){
        screen();root.addView(title("مجموع مصرف"));String ids=p.getString("mgr_ids_v2","");long twd=0,tww=0,twm=0,tmd=0,tmw=0,tmm=0;int count=0;
        if(!ids.isEmpty())for(String id:ids.split("\\n")){if(id.trim().isEmpty())continue;count++;String k=safe(id);long wd=p.getLong("m2_"+k+"_wd",0),ww=p.getLong("m2_"+k+"_ww",0),wm=p.getLong("m2_"+k+"_wm",0),md=p.getLong("m2_"+k+"_md",0),mw=p.getLong("m2_"+k+"_mw",0),mm=p.getLong("m2_"+k+"_mm",0);twd+=wd;tww+=ww;twm+=wm;tmd+=md;tmw+=mw;tmm+=mm;LinearLayout c=card();c.addView(strong(id));c.addView(small("Wi‑Fi امروز: "+fmt(wd)+"   Mobile امروز: "+fmt(md)));c.addView(small("Wi‑Fi ماه: "+fmt(wm)+"   Mobile ماه: "+fmt(mm)));root.addView(c);}
        root.addView(section("مجموع "+count+" دستگاه"));root.addView(totalCard("امروز — Wi‑Fi",twd));root.addView(totalCard("امروز — Mobile",tmd));root.addView(totalCard("ماه — Wi‑Fi",twm));root.addView(totalCard("ماه — Mobile",tmm));Button imp=primary("واردکردن گزارش دستگاه");imp.setOnClickListener(v->importDialog());root.addView(imp);Button back=secondary("بازگشت");back.setOnClickListener(v->adminHome());root.addView(back);
    }

    private void importDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),0,dp(20),0);EditText code=input("کد گزارش"),key=input("کلید اتصال");box.addView(code);box.addView(key);AlertDialog d=new AlertDialog.Builder(this).setTitle("ورود گزارش").setView(box).setNegativeButton("انصراف",null).setPositiveButton("بررسی و ذخیره",null).create();d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{Report r=parseReport(code.getText().toString().trim(),key.getText().toString().trim());if(r==null){code.setError("کد یا کلید معتبر نیست");return;}saveReport(r);d.dismiss();managerHome();}));d.show();
    }

    private String reportCode(Totals wd,Totals ww,Totals wm,Totals md,Totals mw,Totals mm){
        String id=p.getString("student","");String enc=Base64.encodeToString(id.getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);String body="WUM2|"+enc+"|"+System.currentTimeMillis()+"|"+wd.total()+"|"+ww.total()+"|"+wm.total()+"|"+md.total()+"|"+mw.total()+"|"+mm.total();return body+"|"+hmac(body,p.getString("pair",""));
    }

    private Report parseReport(String code,String key){
        try{String[] a=code.split("\\|");if(a.length!=10||!"WUM2".equals(a[0]))return null;String body=String.join("|",a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7],a[8]);if(!MessageDigest.isEqual(hmac(body,key).getBytes(StandardCharsets.UTF_8),a[9].getBytes(StandardCharsets.UTF_8)))return null;String id=new String(Base64.decode(a[1],Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),StandardCharsets.UTF_8);return new Report(id,Long.parseLong(a[3]),Long.parseLong(a[4]),Long.parseLong(a[5]),Long.parseLong(a[6]),Long.parseLong(a[7]),Long.parseLong(a[8]),Long.parseLong(a[2]));}catch(Exception e){return null;}
    }

    private void saveReport(Report r){String ids=p.getString("mgr_ids_v2","");boolean found=false;if(!ids.isEmpty())for(String x:ids.split("\\n"))if(x.equals(r.id))found=true;if(!found)ids=ids.isEmpty()?r.id:ids+"\n"+r.id;String k=safe(r.id);p.edit().putString("mgr_ids_v2",ids).putLong("m2_"+k+"_wd",r.wd).putLong("m2_"+k+"_ww",r.ww).putLong("m2_"+k+"_wm",r.wm).putLong("m2_"+k+"_md",r.md).putLong("m2_"+k+"_mw",r.mw).putLong("m2_"+k+"_mm",r.mm).putLong("m2_"+k+"_at",r.at).apply();}

    private Totals query(int type,long s,long e){
        try{NetworkStatsManager m=(NetworkStatsManager)getSystemService(NETWORK_STATS_SERVICE);if(m==null||e<=s)return new Totals(0,0);NetworkStats.Bucket b=m.querySummaryForDevice(type,null,s,e);if(b==null)return new Totals(0,0);return new Totals(b.getRxBytes(),b.getTxBytes());}catch(Exception ex){return new Totals(0,0);}
    }

    private boolean hasAccess(){AppOpsManager a=(AppOpsManager)getSystemService(APP_OPS_SERVICE);return a!=null&&a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),getPackageName())==AppOpsManager.MODE_ALLOWED;}
    private void openAccess(){waitingForUsageAccess=true;startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));}

    private long startDay(long n){Calendar c=Calendar.getInstance();c.setTimeInMillis(n);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
    private long startWeek(long n){Calendar c=Calendar.getInstance();c.setTimeInMillis(n);c.setFirstDayOfWeek(Calendar.SATURDAY);c.set(Calendar.DAY_OF_WEEK,Calendar.SATURDAY);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);if(c.getTimeInMillis()>n)c.add(Calendar.DAY_OF_MONTH,-7);return c.getTimeInMillis();}
    private long startMonth(long n){Calendar c=Calendar.getInstance();c.setTimeInMillis(n);c.set(Calendar.DAY_OF_MONTH,1);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
    private long addDays(long base,int d){Calendar c=Calendar.getInstance();c.setTimeInMillis(base);c.add(Calendar.DAY_OF_MONTH,d);return c.getTimeInMillis();}

    private String overrideText(String net,String period){long x=p.getLong("display_"+net+"_"+period,-1);return x<0?"":String.format(Locale.US,"%.1f",x/(1024d*1024d));}
    private void saveOverride(String net,String period,String value){String k="display_"+net+"_"+period;if(value==null||value.trim().isEmpty()){p.edit().remove(k).apply();return;}try{double mb=Double.parseDouble(value.trim());p.edit().putLong(k,(long)Math.max(0,mb*1024d*1024d)).apply();}catch(Exception ignored){}}

    private String hashPin(String pin){try{MessageDigest d=MessageDigest.getInstance("SHA-256");return Base64.encodeToString(d.digest(("wum-v1:"+pin).getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);}catch(Exception e){return pin;}}
    private boolean checkPin(String pin){return MessageDigest.isEqual(hashPin(pin).getBytes(StandardCharsets.UTF_8),p.getString("pin","").getBytes(StandardCharsets.UTF_8));}
    private void changePin(){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(20),0,dp(20),0);EditText a=pinInput("PIN جدید"),c=pinInput("تکرار PIN");b.addView(a);b.addView(c);new AlertDialog.Builder(this).setTitle("تغییر PIN").setView(b).setNegativeButton("انصراف",null).setPositiveButton("ذخیره",(x,y)->{String s=a.getText().toString(),t=c.getText().toString();if(s.length()>=4&&s.equals(t)){p.edit().putString("pin",hashPin(s)).apply();toast("PIN تغییر کرد");}else toast("PIN معتبر و یکسان وارد کنید");}).show();}

    private String hmac(String body,String key){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.encodeToString(m.doFinal(body.getBytes(StandardCharsets.UTF_8)),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}catch(Exception e){return "";}}
    private String randomKey(){String chars="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";SecureRandom r=new SecureRandom();StringBuilder b=new StringBuilder();for(int i=0;i<20;i++)b.append(chars.charAt(r.nextInt(chars.length())));return b.toString();}
    private String safe(String s){return Integer.toHexString(s.hashCode())+"_"+s.length();}
    private void log(String s){String old=p.getString("log","");String line=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,new Locale("fa","AF")).format(new Date())+" — "+s;p.edit().putString("log",line+(old.isEmpty()?"":"\n"+old)).apply();}
    private void copy(String s){ClipboardManager c=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(c!=null)c.setPrimaryClip(ClipData.newPlainText("Usage Meter",s));toast("کپی شد");}

    private String formatRange(long s,long e){return DateFormat.getDateInstance(DateFormat.MEDIUM,new Locale("fa","AF")).format(new Date(s))+"  تا  "+DateFormat.getDateInstance(DateFormat.MEDIUM,new Locale("fa","AF")).format(new Date(e));}

    private void screen(){ScrollView s=new ScrollView(this);s.setFillViewport(true);s.setBackgroundColor(0xFFF9FEFF);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(18),dp(12),dp(28));root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);s.addView(root,new ScrollView.LayoutParams(-1,-2));setContentView(s);}
    private TextView title(String s){TextView v=new TextView(this);v.setText(s);v.setTextSize(24);v.setTypeface(Typeface.DEFAULT_BOLD);v.setTextColor(0xFF344E57);v.setGravity(Gravity.CENTER);v.setPadding(0,0,0,dp(8));return v;}
    private TextView section(String s){TextView v=title(s);v.setTextSize(18);v.setGravity(Gravity.RIGHT);v.setPadding(dp(6),dp(18),dp(6),dp(8));return v;}
    private TextView body(String s){TextView v=new TextView(this);v.setText(s);v.setTextSize(14);v.setTextColor(0xFF667D85);v.setGravity(Gravity.CENTER);v.setPadding(0,0,0,dp(12));return v;}
    private TextView small(String s){TextView v=body(s);v.setTextSize(13);v.setPadding(0,dp(2),0,dp(5));return v;}
    private TextView strong(String s){TextView v=body(s);v.setTextSize(17);v.setTypeface(Typeface.DEFAULT_BOLD);v.setTextColor(0xFF263C43);v.setPadding(0,0,0,dp(4));return v;}
    private TextView warn(String s){TextView v=body(s);v.setTextColor(0xFFB42318);v.setBackground(roundBg(0xFFFFE9E7,0,0));v.setPadding(dp(12),dp(12),dp(12),dp(12));margin(v,0,0,0,10);return v;}
    private EditText input(String h){EditText e=new EditText(this);e.setHint(h);e.setTextSize(15);e.setGravity(Gravity.RIGHT);e.setBackground(roundBg(0xFFFFFFFF,0xFFDCE7EA,1));e.setPadding(dp(14),dp(12),dp(14),dp(12));margin(e,0,5,0,8);return e;}
    private EditText pinInput(String h){EditText e=input(h);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);return e;}
    private EditText numInput(String h,String v){EditText e=input(h);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);e.setText(v);return e;}
    private Button primary(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(0xFFFFFFFF);b.setTextSize(15);b.setBackground(roundBg(0xFF2AAEC8,0,0));margin(b,0,5,0,8);return b;}
    private Button secondary(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(0xFF48646D);b.setTextSize(15);b.setBackground(roundBg(0xFFFFFFFF,0xFFD6E6EA,1));margin(b,0,5,0,8);return b;}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(15),dp(13),dp(15),dp(13));c.setBackground(roundBg(0xFFFFFFFF,0xFFE2ECEF,1));margin(c,0,4,0,8);return c;}
    private View realMetric(String label,Totals t){LinearLayout c=card();TextView a=small(label);a.setGravity(Gravity.RIGHT);c.addView(a);TextView n=strong(fmt(t.total()));n.setTextSize(22);n.setTextColor(0xFF169EB9);n.setGravity(Gravity.RIGHT);c.addView(n);TextView b=small("دانلود: "+fmt(t.rx)+"     آپلود: "+fmt(t.tx));b.setGravity(Gravity.RIGHT);c.addView(b);return c;}
    private View totalCard(String label,long t){LinearLayout c=card();c.addView(small(label));TextView n=strong(fmt(t));n.setTextSize(24);n.setGravity(Gravity.CENTER);c.addView(n);return c;}
    private GradientDrawable roundBg(int fill,int stroke,int width){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(18));if(width>0)d.setStroke(dp(width),stroke);return d;}
    private void margin(View v,int l,int t,int r,int b){LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);q.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(q);}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private String fmt(long b){if(b<1024)return b+" B";double k=b/1024d;if(k<1024)return String.format(Locale.US,"%.1f KB",k);double m=k/1024d;if(m<1024)return String.format(Locale.US,"%.1f MB",m);return String.format(Locale.US,"%.2f GB",m/1024d);}

    static final class ChartView extends View {
        final long[] values;final Paint cyan=new Paint(1),green=new Paint(1),grid=new Paint(1),text=new Paint(1);
        ChartView(android.content.Context c,long[] values){super(c);this.values=values;cyan.setColor(0xFF32C5E8);green.setColor(0xFF77EE00);grid.setColor(0xFFE0EEF2);grid.setStrokeWidth(1);text.setColor(0xFF8FA6AD);text.setTextSize(24);setPadding(12,16,12,12);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();float base=h-34;c.drawLine(0,base,w,base,grid);long max=1;for(long v:values)max=Math.max(max,v);float gap=7f;float bw=Math.max(5f,(w-gap*(values.length+1))/values.length);for(int i=0;i<values.length;i++){float x=gap+i*(bw+gap);float bh=(float)(values[i]/(double)max)*(h-78);float greenH=Math.max(5f,bh*.14f);RectF r=new RectF(x,base-bh,x+bw,base);cyan.setStrokeWidth(bw);cyan.setStrokeCap(Paint.Cap.ROUND);c.drawLine(x+bw/2,base-bh+greenH,x+bw/2,base,cyan);green.setStrokeWidth(bw);green.setStrokeCap(Paint.Cap.ROUND);c.drawLine(x+bw/2,base-greenH,x+bw/2,base,green);}c.drawText("0",8,h-5,text);c.drawText("12",w/2-12,h-5,text);c.drawText("24",w-36,h-5,text);}
    }

    static final class ProgressBarView extends View {
        final float fraction;final Paint bg=new Paint(1),cyan=new Paint(1),green=new Paint(1);
        ProgressBarView(android.content.Context c,float fraction){super(c);this.fraction=Math.max(0,Math.min(1,fraction));bg.setColor(0xFFE6F0F2);cyan.setColor(0xFF34C7E8);green.setColor(0xFF78EF00);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),r=h/2;c.drawRoundRect(0,0,w,h,r,r,bg);float used=w*fraction;float cyanEnd=used*.9f;if(cyanEnd>0)c.drawRoundRect(0,0,cyanEnd,h,r,r,cyan);if(used>cyanEnd)c.drawRoundRect(cyanEnd,0,used,h,r,r,green);}
    }

    static final class DonutView extends View {
        final Totals t;final boolean active;final Paint ring=new Paint(1),cyan=new Paint(1),green=new Paint(1),main=new Paint(1),sub=new Paint(1);
        DonutView(android.content.Context c,Totals t,boolean active){super(c);this.t=t;this.active=active;ring.setStyle(Paint.Style.STROKE);ring.setStrokeWidth(12);ring.setColor(0xFFE2EEF1);cyan.setStyle(Paint.Style.STROKE);cyan.setStrokeWidth(12);cyan.setStrokeCap(Paint.Cap.BUTT);cyan.setColor(0xFF32C5E8);green.setStyle(Paint.Style.STROKE);green.setStrokeWidth(12);green.setColor(0xFF77EE00);main.setTextAlign(Paint.Align.CENTER);main.setColor(0xFF435B63);main.setTextSize(28);main.setTypeface(Typeface.DEFAULT_BOLD);sub.setTextAlign(Paint.Align.CENTER);sub.setColor(0xFF8BA0A7);sub.setTextSize(18);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float cx=getWidth()/2f,cy=getHeight()/2f-4,r=Math.min(getWidth(),getHeight())*.36f;RectF o=new RectF(cx-r,cy-r,cx+r,cy+r);c.drawArc(o,0,360,false,ring);if(active&&t.total()>0){float rx=360f*t.rx/t.total();c.drawArc(o,-90,rx,false,cyan);c.drawArc(o,-90+rx,360-rx,false,green);}String total=active?fmtStatic(t.total()):"—";c.drawText(total,cx,cy+4,main);c.drawText("Total",cx,cy+30,sub);}
        static String fmtStatic(long b){if(b<1024)return b+" B";double k=b/1024d;if(k<1024)return String.format(Locale.US,"%.1f KB",k);double m=k/1024d;if(m<1024)return String.format(Locale.US,"%.1f MB",m);return String.format(Locale.US,"%.2f GB",m/1024d);}
    }
}
