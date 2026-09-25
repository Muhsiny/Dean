from pathlib import Path
import sys,re

root=Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()

def edit(rel, fn):
    p=root/rel
    s=p.read_text(encoding='utf-8')
    ns=fn(s)
    if ns==s:
        print('WARN no change:', rel)
    p.write_text(ns, encoding='utf-8')
    print('patched:', rel)

# Visible Android brand. Keep Java/JNI namespace com.wails.app intact.
edit('gui/build/android/app/src/main/res/values/strings.xml',
     lambda s: re.sub(r'<string name="app_name">.*?</string>',
                      '<string name="app_name">SAYEH VPN</string>', s))

# Unique install package without touching Java/JNI class package.
def patch_gradle(s):
    s=s.replace('applicationId "com.wails.app"', 'applicationId "org.sayeh.vpn"')
    return s
edit('gui/build/android/app/build.gradle', patch_gradle)

def patch_manifest(s):
    s=s.replace('android:name=".MainActivity"', 'android:name="com.wails.app.MainActivity"')
    s=s.replace('android:name=".WailsForegroundService"', 'android:name="com.wails.app.WailsForegroundService"')
    s=s.replace('android:name=".WarpVpnService"', 'android:name="com.wails.app.WarpVpnService"')
    return s
edit('gui/build/android/app/src/main/AndroidManifest.xml', patch_manifest)

# Runtime per-app self package must match installed applicationId.
edit('gui/androidbridge.go',
     lambda s: s.replace('const androidSelfPackage = "com.wails.app"',
                         'const androidSelfPackage = "org.sayeh.vpn"'))
edit('gui/frontend/src/pages/SettingsPage.tsx',
     lambda s: s.replace('const SELF_PACKAGE = "com.wails.app";',
                         'const SELF_PACKAGE = "org.sayeh.vpn";'))

# Persian / RTL shell.
def patch_html(s):
    s=re.sub(r'<html[^>]*>', '<html lang="fa" dir="rtl">', s, count=1)
    s=re.sub(r'<title>.*?</title>', '<title>SAYEH VPN</title>', s, count=1)
    return s
edit('gui/frontend/index.html', patch_html)

def patch_nav(s):
    repl={
      '"状态"':'"خانه"','"规则"':'"مسیرها"','"扫描"':'"بهینه‌سازی"','"设置"':'"تنظیمات"','"日志"':'"گزارش"',
      'status: "状态"':'status: "خانه"','rules: "路由规则"':'rules: "قواعد مسیر"',
      'geo: "GEO 数据库"':'geo: "پایگاه GEO"','scan: "边缘扫描"':'scan: "بهینه‌سازی مسیر"',
      'settings: "设置"':'settings: "تنظیمات"','logs: "运行日志"':'logs: "گزارش اتصال"'
    }
    for a,b in repl.items(): s=s.replace(a,b)
    return s
edit('gui/frontend/src/lib/nav.ts', patch_nav)

# App shell: iOS-inspired, soft surfaces, SAYEH brand. No API/state logic changes.
def patch_app(s):
    s=s.replace('warp-go GUI','SAYEH VPN')
    s=s.replace('Cloudflare WARP','Private Network')
    s=s.replace('MASQUE over QUIC · SOCKS5 代理','MASQUE · QUIC · Secure Tunnel')
    s=s.replace('title={collapsed ? "展开侧边栏" : "收起侧边栏"}',
                'title={collapsed ? "نمایش نوار" : "بستن نوار"}')
    s=s.replace('aria-label={collapsed ? "展开侧边栏" : "收起侧边栏"}',
                'aria-label={collapsed ? "نمایش نوار" : "بستن نوار"}')
    s=s.replace('bg-slate-100 text-slate-900 dark:bg-slate-950 dark:text-slate-100',
                'sayeh-shell text-slate-900 dark:text-slate-100')
    s=s.replace('border-r border-slate-200 bg-white',
                'border-r border-white/60 bg-white/70 backdrop-blur-2xl')
    s=s.replace('border-b border-slate-200 bg-white/60',
                'border-b border-white/50 bg-white/55')
    s=s.replace('border-t border-slate-200 bg-white/95',
                'border-t border-white/60 bg-white/80')
    # Replace orange logo with a blue SAYEH "S" mark.
    s=re.sub(r'src="data:image/svg\\+xml,[^"]+"',
       'src="data:image/svg+xml,%3Csvg xmlns=%27http://www.w3.org/2000/svg%27 viewBox=%270 0 32 32%27%3E%3Crect width=%2732%27 height=%2732%27 rx=%279%27 fill=%27%232F6BFF%27/%3E%3Cpath d=%27M22.5 10.2c-1.8-1.5-4-2.2-6.4-2.2-3.8 0-6.4 1.8-6.4 4.6 0 2.7 2 3.9 6 4.6 2.7.5 3.6 1 3.6 2.2 0 1.2-1.2 2-3.1 2-2.2 0-4.3-.8-6-2.3%27 fill=%27none%27 stroke=%27white%27 stroke-width=%272.7%27 stroke-linecap=%27round%27/%3E%3C/svg%3E"',
       s, count=1)
    return s
edit('gui/frontend/src/App.tsx', patch_app)

def patch_css(_s):
    return '''@import "tailwindcss";

@custom-variant dark (&:where(.dark, .dark *));

:root {
  --sayeh-accent:#2F6BFF;
  --sayeh-bg:#F2F2F7;
  --sayeh-card:rgba(255,255,255,.78);
}
html,body,#root { height:100%; }
html { direction:rtl; }
body {
  margin:0;
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans Arabic","Noto Sans",sans-serif;
  -webkit-font-smoothing:antialiased;
  text-rendering:optimizeLegibility;
  background:var(--sayeh-bg);
}
.sayeh-shell {
  background:
    radial-gradient(1100px 520px at 100% -10%, rgba(47,107,255,.12), transparent 58%),
    radial-gradient(800px 420px at -10% 100%, rgba(118,118,128,.08), transparent 58%),
    var(--sayeh-bg);
}
.dark .sayeh-shell {
  background:radial-gradient(1000px 500px at 100% -10%, rgba(47,107,255,.16), transparent 58%),#090A0D;
}
.font-mono,code,pre { direction:ltr; text-align:left; unicode-bidi:plaintext; }
input { unicode-bidi:plaintext; }
button,input { -webkit-tap-highlight-color:transparent; }
* { scrollbar-width:thin; }
'''
edit('gui/frontend/src/index.css', patch_css)

def patch_ui(s):
    s=s.replace(
      'rounded-xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900',
      'rounded-[22px] border border-white/70 bg-white/75 shadow-[0_12px_35px_rgba(15,23,42,0.07)] backdrop-blur-2xl dark:border-white/10 dark:bg-white/[0.06]')
    s=s.replace('rounded-lg px-4 py-2','rounded-[14px] px-4 py-2.5')
    s=s.replace('focus-visible:ring-orange-500/50','focus-visible:ring-[#2F6BFF]/35')
    s=s.replace('bg-orange-500 text-white hover:bg-orange-600 dark:bg-orange-600 dark:hover:bg-orange-500',
                'bg-[#2F6BFF] text-white shadow-sm hover:bg-[#245CE6] dark:bg-[#4B7CFF] dark:hover:bg-[#5A87FF]')
    s=s.replace('checked ? "bg-orange-500" :','checked ? "bg-[#2F6BFF]" :')
    s=s.replace('focus:border-orange-500','focus:border-[#2F6BFF]')
    s=s.replace('focus:ring-1 focus:ring-orange-500/40','focus:ring-2 focus:ring-[#2F6BFF]/20')
    s=s.replace('rounded-lg border border-slate-300 bg-white px-3 py-2',
                'rounded-[14px] border border-black/10 bg-white/80 px-3.5 py-2.5 shadow-inner')
    return s
edit('gui/frontend/src/components/ui.tsx', patch_ui)

translations={
'尚未注册 WARP':'WARP هنوز ثبت نشده است',
'首次使用需注册（创建 Cloudflare WARP 账号）后才能启动代理。':'برای نخستین اتصال، ثبت WARP لازم است.',
'一键注册':'ثبت و آماده‌سازی','运行状态':'وضعیت اتصال','运行中':'متصل','已停止':'قطع',
'监听地址':'آدرس محلی','启动时间':'زمان شروع','规则文件':'فایل قواعد','停止':'قطع اتصال','启动':'اتصال',
'正在初始化（默认规则 / GEO 数据库下载中），完成后即可启动':'در حال آماده‌سازی اولیه…',
'注销（-del）':'پاک‌کردن ثبت','确认注销？（再次点击执行）':'تأیید پاک‌کردن؟',
'注册信息':'اطلاعات اتصال','设备 ID':'شناسه دستگاه','账号':'حساب','密钥类型':'نوع کلید',
'分配的 IPv4':'IPv4 اختصاص‌یافته','分配的 IPv6':'IPv6 اختصاص‌یافته','边缘 IPv4':'Edge IPv4','边缘 IPv6':'Edge IPv6','边缘端口':'پورت Edge','隧道类型':'نوع تونل',
'流量统计':'آمار ترافیک','走隧道（proxy）':'از تونل','直连（direct）':'مستقیم','未命中（miss）':'بدون قاعده','拦截（reject）':'مسدود',
'系统代理':'پروکسی سیستم','已就绪':'آماده','未下载':'دانلود نشده','加载中':'در حال بارگذاری',
'上游仓库':'منبع داده','自动更新':'به‌روزرسانی خودکار','下载地址':'آدرس دانلود','立即更新':'به‌روزرسانی',
'运行日志':'گزارش اتصال','演示模式':'حالت نمایشی','自动滚动':'پیمایش خودکار','清空':'پاک‌کردن',
'暂无日志':'هنوز گزارشی نیست','每秒刷新，最多显示最近 200 条':'به‌روزرسانی زنده؛ حداکثر ۲۰۰ رویداد اخیر',
'路由规则':'قواعد مسیر','保存':'ذخیره','重新加载':'بارگذاری دوباره','规则已保存':'قواعد ذخیره شد',
'规则已热重载':'قواعد بدون قطع اتصال دوباره بارگذاری شد',
'边缘扫描':'بهینه‌سازی مسیر','扫描 IPv4 边缘':'اسکن IPv4','扫描 IPv6 边缘':'اسکن IPv6',
'应用':'انتخاب','已应用':'فعال','无结果':'بدون نتیجه',
'关于':'درباره','外观':'ظاهر','主题模式':'حالت نمایش','浅色':'روشن','深色':'تیره','跟随系统':'مطابق سیستم',
'基本设置':'تنظیمات پایه','检查更新':'بررسی نسخه','检查中…':'در حال بررسی…','前往下载':'دریافت نسخه',
'全部应用':'همه برنامه‌ها','所有应用走代理（默认，与旧版一致）':'همه برنامه‌ها از VPN عبور کنند',
'仅指定应用':'فقط برنامه‌های انتخابی','白名单：只有列表中的应用走代理':'فقط برنامه‌های فهرست‌شده از VPN عبور کنند',
'排除指定应用':'به‌جز برنامه‌های انتخابی','黑名单：列表外的应用走代理':'همه به‌جز برنامه‌های فهرست‌شده از VPN عبور کنند',
'配置已保存（重启后生效）':'تنظیمات ذخیره شد؛ پس از راه‌اندازی دوباره اعمال می‌شود',
'已开启开机自启':'شروع خودکار فعال شد','已关闭开机自启':'شروع خودکار غیرفعال شد',
'已保存':'ذخیره شد','分应用代理':'VPN برای برنامه‌ها',
'选择应用':'انتخاب برنامه','关闭':'بستن','搜索应用名/包名':'جست‌وجوی برنامه یا package',
'加载中…':'در حال بارگذاری…','未找到应用':'برنامه‌ای پیدا نشد','系统应用':'برنامه‌های سیستمی',
'确定':'تأیید','开机自启':'شروع خودکار','登录后自动启动':'اجرای خودکار پس از ورود',
'保存配置':'ذخیره تنظیمات','重置配置':'بازخوانی تنظیمات','下载加速前缀':'پیشوند دانلود',
'代理服务器':'سرور واسط','自定义 User-Agent':'User-Agent سفارشی','已启用':'فعال','已关闭':'غیرفعال',
'扫描结果':'نتیجه اسکن','尚无扫描结果。点击上方按钮开始扫描（需已注册 WARP）。':'هنوز نتیجه‌ای نیست. برای یافتن مسیر بهتر، اسکن را آغاز کنید.',
'暂无':'موجود نیست','重试':'تلاش دوباره','清空':'پاک‌کردن','已选':'انتخاب‌شده'
}
for rel in [
 'gui/frontend/src/pages/StatusPage.tsx','gui/frontend/src/pages/GeoPage.tsx',
 'gui/frontend/src/pages/LogsPage.tsx','gui/frontend/src/pages/RulesPage.tsx',
 'gui/frontend/src/pages/ScanPage.tsx','gui/frontend/src/pages/SettingsPage.tsx',
 'gui/frontend/src/components/PerAppPicker.tsx']:
    p=root/rel
    s=p.read_text(encoding='utf-8')
    for a,b in sorted(translations.items(), key=lambda x:-len(x[0])):
        s=s.replace(a,b)
    s=s.replace('warp-go {version}','SAYEH VPN {version}')
    s=s.replace('Cloudflare WARP 客户端（MASQUE over QUIC/HTTP-3）',
                'SAYEH VPN · MASQUE over QUIC/HTTP-3')
    p.write_text(s,encoding='utf-8')
    print('translated:',rel)

# Guardrails: Java/JNI package stays com.wails.app; install package is org.sayeh.vpn.
assert "namespace 'com.wails.app'" in (root/'gui/build/android/app/build.gradle').read_text()
assert 'applicationId "org.sayeh.vpn"' in (root/'gui/build/android/app/build.gradle').read_text()
assert 'const androidSelfPackage = "org.sayeh.vpn"' in (root/'gui/androidbridge.go').read_text()

# Report remaining CJK in visible frontend string literals (comments may still contain Chinese).
cjk=re.compile(r'["\x60][^"\x60]*[\u4e00-\u9fff][^"\x60]*["\x60]')
for p in (root/'gui/frontend/src').rglob('*.tsx'):
    found=cjk.findall(p.read_text(encoding='utf-8'))
    if found:
        print('WARN visible CJK may remain in',p,found[:8])

print('SAYEH VPN source patch complete; JNI namespace preserved.')
