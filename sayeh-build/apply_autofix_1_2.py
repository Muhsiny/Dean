from pathlib import Path
import sys

root=Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()

def edit(rel, fn):
    p=root/rel
    s=p.read_text(encoding='utf-8')
    ns=fn(s)
    if ns==s:
        print('WARN no change:', rel)
    p.write_text(ns, encoding='utf-8')
    print('autofix patched:', rel)

def patch_api(s):
    old='''        const ns = mod.Service;
        if (!ns) return null;
        // Placeholder stand-in? -> use demo data instead of calling $Call.
        if (ns.__MOCK_BINDINGS__ === true) return null;
        return ns as unknown as ServiceAPI;
      } catch {
        return null;
      }'''
    new='''        const ns = mod.Service;
        if (!ns) {
          throw new Error("SAYEH native service binding is missing");
        }
        // Production Android builds must NEVER silently fall back to mock/demo.
        if (ns.__MOCK_BINDINGS__ === true) {
          throw new Error("SAYEH was built with placeholder bindings");
        }
        return ns as unknown as ServiceAPI;
      } catch (e) {
        console.error("SAYEH native bridge load failed", e);
        throw e;
      }'''
    if old not in s:
        raise RuntimeError('api loadService block not found')
    s=s.replace(old,new)
    s=s.replace('''export async function isDemoMode(): Promise<boolean> {
  return (await loadService()) === null;
}''',
                '''export async function isDemoMode(): Promise<boolean> {
  // SAYEH production builds never expose simulated state.
  // If the native bridge is broken, service calls fail loudly instead.
  await loadService();
  return false;
}''')
    return s
edit('gui/frontend/src/lib/api.ts', patch_api)

def patch_service(s):
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
\t}'''
    new='''\tif runtime.GOOS == "android" {
\t\t// Idempotent when the real native VPN kernel is already active.
\t\tif androidVpnRunning() {
\t\t\tlog.Println("✓ SAYEH VPN already running")
\t\t\treturn nil
\t\t}

\t\t// A fresh Android install has no reg.json. Older UI required a separate
\t\t// manual Register tap, so Start could establish a TUN and then immediately
\t\t// fail with ErrNoRegistration. Make first-connect self-contained.
\t\tsrv, err := s.serverInstance()
\t\tif err != nil {
\t\t\treturn fmt.Errorf("SAYEH core unavailable: %w", err)
\t\t}
\t\tregistered, regErr := srv.Registered()
\t\tif regErr != nil {
\t\t\t// Preserve a malformed state file for diagnosis, then recover with a
\t\t\t// clean registration instead of leaving the UI in a dead state.
\t\t\tif dir := cachedDataDir(); dir != "" {
\t\t\t\tbad := filepath.Join(dir, "reg.json")
\t\t\t\tbackup := filepath.Join(dir, fmt.Sprintf("reg.corrupt.%d.json", time.Now().Unix()))
\t\t\t\tif renameErr := os.Rename(bad, backup); renameErr == nil {
\t\t\t\t\tlog.Printf("⚠ SAYEH: corrupt registration preserved at %s", backup)
\t\t\t\t\tregistered = false
\t\t\t\t\tregErr = nil
\t\t\t\t}
\t\t\t}
\t\t\tif regErr != nil {
\t\t\t\treturn fmt.Errorf("SAYEH registration state is invalid: %w", regErr)
\t\t\t}
\t\t}
\t\tif !registered {
\t\t\tlog.Println("SAYEH: first connection — registering tunnel identity automatically...")
\t\t\t_, id, err := srv.Register()
\t\t\tif err != nil {
\t\t\t\treturn fmt.Errorf("SAYEH automatic registration failed: %w", err)
\t\t\t}
\t\t\tlog.Printf("✓ SAYEH automatic registration complete: %s", id)
\t\t}

\t\tlog.Println("SAYEH: requesting Android VPN permission/service...")
\t\tif err := androidRequestVpnStart(); err != nil {
\t\t\treturn err
\t\t}
\t\treturn nil
\t}'''
    if old not in s:
        raise RuntimeError('Android Service.Start block not found')
    return s.replace(old,new)
edit('gui/service.go', patch_service)

def patch_main_activity_comment(s):
    s=s.replace(
'''     * NOTE: the Go side (nativeStartVpn) reads config.json / reg.json from the
     * app sandbox (getFilesDir). Getting reg.json into the sandbox is a MANUAL
     * step (documented in README); until it is present, establish() succeeds
     * but nativeStartVpn fails with "没有注册信息". A JS-triggered path from
     * the React UI is a documented follow-up; this method is also callable
     * from WailsBridge when that lands.''',
'''     * NOTE: the Go Service.Start path now guarantees reg.json exists before
     * this method is called. Fresh installs auto-register before the TUN is
     * established, so the VPN no longer enters a fake connected/dead state.''')
    return s
edit('gui/build/android/app/src/main/java/com/wails/app/MainActivity.java', patch_main_activity_comment)

print('SAYEH 1.2 real-only autofix patch complete.')
