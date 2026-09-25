from pathlib import Path
import sys

root=Path(sys.argv[1] if len(sys.argv)>1 else ".").resolve()

def edit(rel, fn):
    p=root/rel
    s=p.read_text(encoding="utf-8")
    ns=fn(s)
    if ns==s:
        print("WARN no change:", rel)
    p.write_text(ns, encoding="utf-8")
    print("REAL patched:", rel)

# 1) Never start the VPN before WARP registration exists.
# The old first-launch consent path could establish a TUN and then immediately
# tear it down because reg.json did not yet exist. That is exactly the
# "connects then instantly disconnects" failure mode.
def patch_main_activity(s):
    old='''        // First-run UX: prompt for VPN consent once. A JS-triggered path from
        // the React UI (connectVpn via WailsBridge) is a documented follow-up.
        if (!getSharedPreferences("warp_prefs", MODE_PRIVATE).getBoolean("vpn_consent_prompted", false)) {
            getSharedPreferences("warp_prefs", MODE_PRIVATE).edit().putBoolean("vpn_consent_prompted", true).apply();
            connectVpn();
        }

'''
    s=s.replace(old, '''        // SAYEH 1.2: do NOT auto-establish TUN on first launch.
        // Registration is completed by Service.Start() before VPN consent is requested.
        // This prevents the first-run "TUN up -> no reg.json -> immediate teardown" loop.

''')
    s=s.replace(
        'Getting reg.json into the sandbox is a MANUAL\\n     * step (documented in README); until it is present, establish() succeeds\\n     * but nativeStartVpn fails with "没有注册信息". A JS-triggered path from\\n     * the React UI is a documented follow-up; this method is also callable\\n     * from WailsBridge when that lands.',
        'SAYEH Service.Start() ensures a valid registration exists before this method\\n     * is called. This method only owns Android VPN consent + service startup.'
    )
    return s
edit("gui/build/android/app/src/main/java/com/wails/app/MainActivity.java", patch_main_activity)

# 2) One-tap start: registration is an actual prerequisite, so make it part of
# Start() rather than forcing the user through a separate fragile step.
def patch_service_start(s):
    old='''\tif runtime.GOOS == "android" {
\t\t// 幂等：VPN 已在运行则无操作。
\t\tif androidVpnRunning() {
\t\t\tlog.Println("✓ VPN 已在运行（幂等跳过）")
\t\t\treturn nil
\t\t}
\t\tlog.Println("正在启动 VPN（请求系统授权）...")
\t\tif err := androidRequestVpnStart(); err != nil {
\t\t\treturn err
\t\t}
\t\treturn nil
\t}
'''
    new='''\tif runtime.GOOS == "android" {
\t\t// 幂等：VPN 已在运行则无操作。
\t\tif androidVpnRunning() {
\t\t\tlog.Println("✓ VPN 已在运行（幂等跳过）")
\t\t\treturn nil
\t\t}

\t\t// SAYEH 1.2: registration is a hard prerequisite for nativeStartVpn.
\t\t// The previous UX allowed VpnService.establish() to run first and then
\t\t// fail immediately because reg.json was missing. Make Start transactional:
\t\t// ensure registration first, then ask Android for VPN consent.
\t\tsrv, err := s.serverInstance()
\t\tif err != nil {
\t\t\treturn fmt.Errorf("آماده‌سازی SAYEH ناموفق شد: %w", err)
\t\t}
\t\tregistered, err := srv.Registered()
\t\tif err != nil {
\t\t\treturn fmt.Errorf("اطلاعات ثبت SAYEH خراب است؛ ابتدا ثبت را پاک و دوباره ایجاد کنید: %w", err)
\t\t}
\t\tif !registered {
\t\t\tlog.Println("SAYEH: ثبت WARP به‌صورت خودکار در حال انجام است...")
\t\t\tif _, _, err := srv.Register(); err != nil {
\t\t\t\treturn fmt.Errorf("ثبت خودکار WARP ناموفق شد: %w", err)
\t\t\t}
\t\t\tlog.Println("✓ SAYEH: ثبت WARP آماده شد")
\t\t}

\t\tlog.Println("SAYEH: درخواست مجوز VPN از Android...")
\t\tif err := androidRequestVpnStart(); err != nil {
\t\t\treturn err
\t\t}
\t\treturn nil
\t}
'''
    if old not in s:
        raise RuntimeError("Android Service.Start block not found")
    return s.replace(old,new,1)
edit("gui/service.go", patch_service_start)

# 3) Correct the Android-side error text. Registration is supported in-app.
def patch_android_config(s):
    return s.replace(
        '没有注册信息（%s），请先在桌面端执行注册：%w',
        'اطلاعات ثبت WARP وجود ندارد (%s): %w'
    )
edit("gui/androidconfig.go", patch_android_config)

# 4) Do not make the UI show "connected" merely because nativeStartVpn was
# accepted asynchronously. The real state is "running" only after a Kernel
# exists. Keep "started" as an internal anti-reentry flag.
def patch_android_running(s):
    old='''func androidVpnRunning() bool {
\tandroidRuntime.mu.Lock()
\tdefer androidRuntime.mu.Unlock()
\treturn androidRuntime.started
}'''
    new='''func androidVpnRunning() bool {
\tandroidRuntime.mu.Lock()
\tdefer androidRuntime.mu.Unlock()
\treturn androidRuntime.started && androidRuntime.kernel != nil && androidRuntime.vpn != nil
}'''
    if old in s:
        s=s.replace(old,new,1)
    return s
edit("gui/androidbridge.go", patch_android_running)

# 5) A rejected asynchronous start must clear the user-facing last error on a
# fresh retry only after prerequisites are valid, not at native acceptance.
# Leave actual kernel errors visible until the next successful kernel publish.
def patch_kernel_publish(s):
    needle='''\tandroidRuntime.kernel = kernel
\tandroidRuntime.vpn = vpn
\tandroidRuntime.startTime = time.Now()'''
    repl='''\tandroidRuntime.kernel = kernel
\tandroidRuntime.vpn = vpn
\tandroidRuntime.lastErr = ""
\tandroidRuntime.startTime = time.Now()'''
    if needle in s:
        s=s.replace(needle,repl,1)
    return s
edit("gui/androidbridge.go", patch_kernel_publish)

print("SAYEH REAL 1.2 prerequisite/start-state fixes applied.")
