package org.sayeh.realwifi;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Process;
import java.util.Calendar;

public final class UsageMeterEngine {
  public static final class Totals { public final long rx, tx; public Totals(long rx,long tx){this.rx=Math.max(0,rx);this.tx=Math.max(0,tx);} public long total(){return rx+tx;} }
  public static final class Bundle { public final Totals day,week,month; public final long at; public Bundle(Totals d,Totals w,Totals m,long a){day=d;week=w;month=m;at=a;} }
  private UsageMeterEngine(){}
  public static boolean hasAccess(Context c){ AppOpsManager a=(AppOpsManager)c.getSystemService(Context.APP_OPS_SERVICE); return a!=null && a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())==AppOpsManager.MODE_ALLOWED; }
  public static Bundle read(Context c){ long now=System.currentTimeMillis(); return new Bundle(query(c,startDay(now),now),query(c,startWeek(now),now),query(c,startMonth(now),now),now); }
  private static Totals query(Context c,long s,long e){ NetworkStatsManager m=(NetworkStatsManager)c.getSystemService(Context.NETWORK_STATS_SERVICE); if(m==null)return new Totals(0,0); NetworkStats.Bucket b=m.querySummaryForDevice(ConnectivityManager.TYPE_WIFI,null,s,e); return new Totals(b.getRxBytes(),b.getTxBytes()); }
  private static long startDay(long n){ Calendar c=Calendar.getInstance();c.setTimeInMillis(n);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis(); }
  private static long startWeek(long n){ Calendar c=Calendar.getInstance();c.setTimeInMillis(n);c.setFirstDayOfWeek(Calendar.SATURDAY);c.set(Calendar.DAY_OF_WEEK,Calendar.SATURDAY);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);if(c.getTimeInMillis()>n)c.add(Calendar.DAY_OF_MONTH,-7);return c.getTimeInMillis(); }
  private static long startMonth(long n){ Calendar c=Calendar.getInstance();c.setTimeInMillis(n);c.set(Calendar.DAY_OF_MONTH,1);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis(); }
}
