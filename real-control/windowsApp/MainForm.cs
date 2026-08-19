using Microsoft.Web.WebView2.WinForms;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace WiFiControl.Real.Windows;

public sealed class MainForm : Form
{
    private enum Purpose { None, ConnectRoot, VerifyClients, VerifyWireless, VerifyStats, VerifyGuest, RefreshClients, PrepareBlock, PrepareUnblock, VerifyBlockConfig, VerifyBlockOnline, PrepareAllowList, VerifyAllowList, PrepareFilterOff, VerifyFilterOff, ReadStats, PrepareGuestOn, PrepareGuestOff, VerifyGuest }

    private const string DevicePath = "/status/status_deviceinfo.htm";
    private const string WirelessPath = "/basic/home_wlan.htm";
    private const string StatsPath = "/status/status_statistics.htm";
    private const string GuestPath = "/basic/home_guest_network.htm";
    private const string RouterMac = "78:8C:B5:DD:8E:F0";

    private readonly TextBox routerUrl = new() { Text = "http://192.168.1.1", Width = 210 };
    private readonly TextBox username = new() { Text = "admin", Width = 110 };
    private readonly TextBox password = new() { Width = 130, UseSystemPasswordChar = true };
    private readonly Button connectBtn = new() { Text = "اتصال + Verify", AutoSize = true };
    private readonly Button refreshBtn = new() { Text = "تازه‌سازی", AutoSize = true, Enabled = false };
    private readonly Label status = new() { AutoSize = false, Height = 60, Dock = DockStyle.Fill, Text = "آماده" };
    private readonly Label capabilities = new() { AutoSize = false, Height = 45, Dock = DockStyle.Fill, Text = "قابلیت‌ها هنوز Verify نشده‌اند." };
    private readonly CheckedListBox deviceList = new() { Dock = DockStyle.Fill, CheckOnClick = true };
    private readonly Button managerBtn = new() { Text = "دستگاه انتخاب‌شده = مدیر", AutoSize = true };
    private readonly Button renameBtn = new() { Text = "نام‌گذاری", AutoSize = true };
    private readonly Button blockBtn = new() { Text = "قطع واقعی", AutoSize = true, Enabled = false };
    private readonly Button unblockBtn = new() { Text = "وصل", AutoSize = true, Enabled = false };
    private readonly Button allowBtn = new() { Text = "ضد QR / Allow-List", AutoSize = true, Enabled = false };
    private readonly Button filterOffBtn = new() { Text = "خاموش‌کردن MAC Filter", AutoSize = true, Enabled = false };
    private readonly Button statsBtn = new() { Text = "خواندن آمار", AutoSize = true, Enabled = false };
    private readonly TextBox packageGb = new() { Width = 90, PlaceholderText = "Package GB" };
    private readonly Label usage = new() { AutoSize = true, Text = "مصرف: خوانده نشده" };
    private readonly Button guestOnBtn = new() { Text = "Guest ON", AutoSize = true, Enabled = false };
    private readonly Button guestOffBtn = new() { Text = "Guest OFF", AutoSize = true, Enabled = false };
    private readonly WebView2 web = new() { Width = 2, Height = 2, Visible = false };

    private readonly Dictionary<string,string> displayToMac = new();
    private readonly Dictionary<string,string> aliases = new(StringComparer.OrdinalIgnoreCase);
    private string adapter = "";
    private Purpose purpose = Purpose.None;
    private string expectedPath = "";
    private string targetMac = "";
    private bool targetBlocked;
    private List<string> targetAllowed = new();
    private bool connected, clientsReady, wirelessReady, statsReady, guestReady;
    private int wirelessCapacity;
    private int loginAttempts;
    private string protectedMac = "";
    private readonly string statePath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "WiFiControlReal", "state.json");

    public MainForm()
    {
        Text = "WiFi Control Real — TP-Link TD-W8961N V4";
        Width = 980; Height = 720; MinimumSize = new Size(850, 620);
        RightToLeft = RightToLeft.Yes; RightToLeftLayout = true;
        BuildUi();
        LoadState();
        Shown += async (_,__) => await InitWebAsync();
        connectBtn.Click += (_,__) => StartConnection();
        refreshBtn.Click += (_,__) => { if (connected) Navigate(DevicePath, Purpose.RefreshClients); };
        managerBtn.Click += (_,__) => MarkManager();
        renameBtn.Click += (_,__) => RenameSelected();
        blockBtn.Click += (_,__) => StartBlock(true);
        unblockBtn.Click += (_,__) => StartBlock(false);
        allowBtn.Click += (_,__) => ActivateAllowList();
        filterOffBtn.Click += (_,__) => { if (MessageBox.Show("Wireless MAC Filter خاموش شود؟", "Emergency", MessageBoxButtons.YesNo) == DialogResult.Yes) Navigate(WirelessPath, Purpose.PrepareFilterOff); };
        statsBtn.Click += (_,__) => { if (statsReady) Navigate(StatsPath, Purpose.ReadStats); };
        guestOnBtn.Click += (_,__) => Navigate(GuestPath, Purpose.PrepareGuestOn);
        guestOffBtn.Click += (_,__) => Navigate(GuestPath, Purpose.PrepareGuestOff);
        deviceList.SelectedIndexChanged += (_,__) => UpdateButtons();
    }

    private void BuildUi()
    {
        var root = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 6, ColumnCount = 1, Padding = new Padding(12) };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 65));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        var top = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, WrapContents = true };
        top.Controls.Add(new Label { Text = "Router:", AutoSize = true, Padding = new Padding(0,8,0,0) }); top.Controls.Add(routerUrl);
        top.Controls.Add(new Label { Text = "User:", AutoSize = true, Padding = new Padding(0,8,0,0) }); top.Controls.Add(username);
        top.Controls.Add(new Label { Text = "Password:", AutoSize = true, Padding = new Padding(0,8,0,0) }); top.Controls.Add(password);
        top.Controls.Add(connectBtn); top.Controls.Add(refreshBtn);

        root.Controls.Add(top,0,0); root.Controls.Add(status,0,1); root.Controls.Add(capabilities,0,2);
        var group = new GroupBox { Text = "دستگاه‌های متصل — تیک = مجاز در Allow-List", Dock = DockStyle.Fill };
        group.Controls.Add(deviceList); root.Controls.Add(group,0,3);

        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, WrapContents = true };
        actions.Controls.Add(managerBtn); actions.Controls.Add(renameBtn); actions.Controls.Add(blockBtn); actions.Controls.Add(unblockBtn); actions.Controls.Add(allowBtn); actions.Controls.Add(filterOffBtn);
        root.Controls.Add(actions,0,4);

        var bottom = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, WrapContents = true };
        bottom.Controls.Add(new Label { Text = "بسته GB:", AutoSize = true, Padding = new Padding(0,8,0,0) }); bottom.Controls.Add(packageGb); bottom.Controls.Add(statsBtn); bottom.Controls.Add(usage); bottom.Controls.Add(guestOnBtn); bottom.Controls.Add(guestOffBtn);
        root.Controls.Add(bottom,0,5);
        Controls.Add(root); Controls.Add(web);
    }

    private async Task InitWebAsync()
    {
        adapter = await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "router_adapter.js"));
        await web.EnsureCoreWebView2Async();
        web.CoreWebView2.Settings.AreDevToolsEnabled = false;
        web.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
        web.CoreWebView2.NavigationCompleted += async (_,__) => await HandlePageAsync();
    }

    private string BaseUrl => (routerUrl.Text.Trim().Length == 0 ? "http://192.168.1.1" : routerUrl.Text.Trim()).TrimEnd('/');

    private void StartConnection()
    {
        if (password.Text.Length == 0) { SetStatus("رمز ادمین را وارد کن."); return; }
        SaveState(); connected=clientsReady=wirelessReady=statsReady=guestReady=false; wirelessCapacity=0; loginAttempts=0; deviceList.Items.Clear(); displayToMac.Clear();
        purpose=Purpose.ConnectRoot; expectedPath=""; SetStatus("در حال ورود واقعی و Verify firmware…"); UpdateButtons(); web.CoreWebView2.Navigate(BaseUrl);
    }

    private async Task HandlePageAsync()
    {
        if (await IsLoginAsync()) { await AutoLoginAsync(); return; }
        loginAttempts=0;
        var url=web.Source?.ToString()??"";
        if (expectedPath.Length>0 && !UrlPathMatches(url, expectedPath)) { web.CoreWebView2.Navigate(BaseUrl+expectedPath); return; }
        switch(purpose)
        {
            case Purpose.ConnectRoot: Navigate(DevicePath,Purpose.VerifyClients); break;
            case Purpose.VerifyClients: await ReadClientsAsync(async()=>{Navigate(WirelessPath,Purpose.VerifyWireless);await Task.CompletedTask;}); break;
            case Purpose.VerifyWireless: await VerifyWirelessAsync(); break;
            case Purpose.VerifyStats: await VerifyStatsAsync(); break;
            case Purpose.VerifyGuest: await VerifyGuestAsync(); break;
            case Purpose.RefreshClients: await ReadClientsAsync(async()=>{SetStatus("فهرست از خود روتر تازه شد.");await Task.CompletedTask;}); break;
            case Purpose.PrepareBlock: await PrepareBlockAsync(true); break;
            case Purpose.PrepareUnblock: await PrepareBlockAsync(false); break;
            case Purpose.VerifyBlockConfig: await VerifyBlockConfigAsync(); break;
            case Purpose.VerifyBlockOnline: await VerifyBlockOnlineAsync(); break;
            case Purpose.PrepareAllowList: await PrepareAllowListAsync(); break;
            case Purpose.VerifyAllowList: await VerifyAllowListAsync(); break;
            case Purpose.PrepareFilterOff: await PrepareFilterOffAsync(); break;
            case Purpose.VerifyFilterOff: await VerifyFilterOffAsync(); break;
            case Purpose.ReadStats: await ReadStatsAsync(); break;
            case Purpose.PrepareGuestOn: await PrepareGuestAsync(true); break;
            case Purpose.PrepareGuestOff: await PrepareGuestAsync(false); break;
            case Purpose.VerifyGuest: await VerifyGuestChangeAsync(); break;
        }
    }

    private async Task<bool> IsLoginAsync()
    {
        var r=await ScriptAsync("(function(){return !!document.querySelector('input[type=password]')||location.href.toLowerCase().indexOf('login_security')>=0;})()",false);
        return r=="true";
    }

    private async Task AutoLoginAsync()
    {
        if (++loginAttempts>3) { SetStatus("ورود تأیید نشد؛ نام کاربری/رمز را بررسی کن."); return; }
        string uq=JsonSerializer.Serialize(username.Text.Trim()), pq=JsonSerializer.Serialize(password.Text);
        string js=$@"(function(){{try{{var p=document.querySelector('input[type=password]');var u=document.querySelector('input[name*=user i],input[id*=user i],input[type=text]');if(!u||!p)return 'NO_LOGIN_FORM';u.value={uq};p.value={pq};['input','change'].forEach(function(n){{u.dispatchEvent(new Event(n,{{bubbles:true}}));p.dispatchEvent(new Event(n,{{bubbles:true}}));}});var f=p.form||u.form||document.forms[0];if(!f)return 'NO_LOGIN_FORM';var bs=f.querySelectorAll('input[type=submit],input[type=button],button');for(var i=0;i<bs.length;i++){{var t=((bs[i].value||bs[i].innerText||'')+'').toLowerCase();if(t.indexOf('login')>=0){{bs[i].click();return 'CLICKED';}}}}if(bs.length){{bs[0].click();return 'CLICKED';}}f.submit();return 'SUBMITTED';}}catch(e){{return 'ERR:'+e;}}}})()";
        var r=await ScriptAsync(js,false); if(r.Contains("NO_LOGIN_FORM")||r.Contains("ERR:"))SetStatus("فرم ورود firmware شناخته نشد؛ هیچ تنظیمی تغییر نکرد.");
    }

    private void Navigate(string path, Purpose p){purpose=p;expectedPath=path;web.CoreWebView2.Navigate(BaseUrl+path);}
    private static bool UrlPathMatches(string url,string path){try{return new Uri(url).AbsolutePath.Equals(path,StringComparison.OrdinalIgnoreCase);}catch{return url.Contains(path,StringComparison.OrdinalIgnoreCase);}}

    private async Task<string> ScriptAsync(string expression,bool withAdapter=true)
    {
        string js=(withAdapter?adapter+"\n;":"")+"try{JSON.stringify("+expression+")}catch(e){JSON.stringify({ok:false,error:String(e)})}";
        string raw=await web.CoreWebView2.ExecuteScriptAsync(js);
        try{return JsonSerializer.Deserialize<string>(raw)??raw;}catch{return raw.Trim('"');}
    }

    private async Task ReadClientsAsync(Func<Task> then)
    {
        string json=await ScriptAsync($"RouterAdapter.scanClients({JsonSerializer.Serialize(RouterMac)})");
        try{
            using var doc=JsonDocument.Parse(json);var arr=doc.RootElement.GetProperty("clients");deviceList.Items.Clear();displayToMac.Clear();
            foreach(var c in arr.EnumerateArray()){
                var mac=c.GetProperty("mac").GetString()?.ToUpperInvariant()??"";if(mac.Length==0||mac==RouterMac)continue;
                var row=c.TryGetProperty("row",out var rr)?rr.GetString()??"":"";var ip=Regex.Match(row,@"\b(?:\d{1,3}\.){3}\d{1,3}\b").Value;
                var alias=aliases.TryGetValue(mac,out var a)?a:"";var display=(alias.Length>0?alias+" — ":"")+mac+(ip.Length>0?" — "+ip:"")+(mac==protectedMac?" — مدیر":"");
                displayToMac[display]=mac;deviceList.Items.Add(display, mac==protectedMac);
            }
            clientsReady=true;UpdateButtons();await then();
        }catch(Exception ex){SetStatus("خواندن کلاینت‌ها شکست خورد: "+ex.Message);}
    }

    private async Task VerifyWirelessAsync(){var json=await ScriptAsync("RouterAdapter.wirelessState()");try{using var d=JsonDocument.Parse(json);wirelessReady=d.RootElement.GetProperty("ok").GetBoolean();wirelessCapacity=d.RootElement.TryGetProperty("capacity",out var c)?c.GetInt32():0;}catch{wirelessReady=false;}Navigate(StatsPath,Purpose.VerifyStats);}
    private async Task VerifyStatsAsync(){var json=await ScriptAsync("RouterAdapter.scanStats()");try{using var d=JsonDocument.Parse(json);statsReady=d.RootElement.GetProperty("ok").GetBoolean();}catch{statsReady=false;}Navigate(GuestPath,Purpose.VerifyGuest);}
    private async Task VerifyGuestAsync(){var json=await ScriptAsync("RouterAdapter.scanGuest()");try{using var d=JsonDocument.Parse(json);guestReady=d.RootElement.GetProperty("ok").GetBoolean();}catch{guestReady=false;}connected=clientsReady;purpose=Purpose.None;expectedPath="";UpdateButtons();SetStatus(connected?"اتصال واقعی برقرار شد؛ قابلیت‌های موجود از firmware Verify شدند.":"اتصال کامل Verify نشد.");}

    private string? SelectedMac(){if(deviceList.SelectedItem is null)return null;return displayToMac.TryGetValue(deviceList.SelectedItem.ToString()??"",out var m)?m:null;}
    private void MarkManager(){var m=SelectedMac();if(m is null){SetStatus("یک دستگاه را انتخاب کن.");return;}protectedMac=m;SaveState();SetStatus(m+" به‌عنوان مدیر محافظت شد.");Navigate(DevicePath,Purpose.RefreshClients);}
    private void RenameSelected(){var m=SelectedMac();if(m is null){SetStatus("یک دستگاه را انتخاب کن.");return;}var name=Prompt("نام دستگاه",aliases.TryGetValue(m,out var a)?a:"");if(name is null)return;aliases[m]=name.Trim();SaveState();Navigate(DevicePath,Purpose.RefreshClients);}

    private void StartBlock(bool blocked){var m=SelectedMac();if(m is null){SetStatus("یک دستگاه را انتخاب کن.");return;}if(blocked&&m==protectedMac){SetStatus("دستگاه مدیر Block نمی‌شود.");return;}targetMac=m;targetBlocked=blocked;Navigate(WirelessPath,blocked?Purpose.PrepareBlock:Purpose.PrepareUnblock);}
    private async Task PrepareBlockAsync(bool blocked){string expr=(blocked?"RouterAdapter.prepareBlock(":"RouterAdapter.prepareUnblock(")+JsonSerializer.Serialize(targetMac)+")";string json=await ScriptAsync(expr);using var d=JsonDocument.Parse(json);if(!d.RootElement.GetProperty("ok").GetBoolean()){SetStatus("فرمان آماده نشد: "+(d.RootElement.TryGetProperty("error",out var e)?e.GetString():"UNKNOWN"));return;}bool save=d.RootElement.TryGetProperty("needsSave",out var n)&&n.GetBoolean();purpose=Purpose.VerifyBlockConfig;expectedPath=WirelessPath;if(!save){await VerifyBlockConfigAsync();return;}var s=await ScriptAsync("RouterAdapter.saveWireless()");if(!JsonOk(s)){SetStatus("SAVE واقعی اجرا نشد.");return;}await Task.Delay(1400);if(purpose==Purpose.VerifyBlockConfig)web.CoreWebView2.Navigate(BaseUrl+WirelessPath);}
    private async Task VerifyBlockConfigAsync(){var json=await ScriptAsync("({ok:true,blocked:RouterAdapter.isBlocked("+JsonSerializer.Serialize(targetMac)+")})");using var d=JsonDocument.Parse(json);bool actual=d.RootElement.GetProperty("blocked").GetBoolean();if(actual!=targetBlocked){SetStatus("وضعیت Block بعد از SAVE از خود روتر تأیید نشد.");return;}Navigate(DevicePath,Purpose.VerifyBlockOnline);}
    private async Task VerifyBlockOnlineAsync(){string json=await ScriptAsync($"RouterAdapter.scanClients({JsonSerializer.Serialize(RouterMac)})");using var d=JsonDocument.Parse(json);var set=new HashSet<string>(StringComparer.OrdinalIgnoreCase);foreach(var c in d.RootElement.GetProperty("clients").EnumerateArray()){var m=c.GetProperty("mac").GetString();if(!string.IsNullOrWhiteSpace(m))set.Add(m);}purpose=Purpose.None;expectedPath="";if(targetBlocked){SetStatus(!set.Contains(targetMac)?"قطع واقعی تأیید شد؛ دستگاه دیگر در Wireless Clients نیست.":"قانون Block Verify شد اما دستگاه هنوز در Wireless Clients است؛ این firmware deauth فوری را تأیید نکرد و اپ آن را قطع کامل ثبت نمی‌کند.");}else SetStatus("قانون Block برداشته و از خود روتر Verify شد؛ دستگاه اجازه اتصال دارد.");Navigate(DevicePath,Purpose.RefreshClients);}

    private void ActivateAllowList(){if(string.IsNullOrWhiteSpace(protectedMac)){SetStatus("اول دستگاه مدیر را مشخص کن.");return;}var set=new HashSet<string>(StringComparer.OrdinalIgnoreCase){protectedMac};for(int i=0;i<deviceList.Items.Count;i++)if(deviceList.GetItemChecked(i)){var s=deviceList.Items[i]?.ToString()??"";if(displayToMac.TryGetValue(s,out var m))set.Add(m);}if(MessageBox.Show($"فقط {set.Count} دستگاه اجازه Association داشته باشند؟","Allow-List",MessageBoxButtons.YesNo)!=DialogResult.Yes)return;targetAllowed=set.ToList();Navigate(WirelessPath,Purpose.PrepareAllowList);}
    private async Task PrepareAllowListAsync(){var arr=JsonSerializer.Serialize(targetAllowed);var json=await ScriptAsync("RouterAdapter.prepareAllowList("+arr+")");if(!JsonOk(json)){SetStatus("Allow-List آماده نشد: "+JsonError(json));return;}purpose=Purpose.VerifyAllowList;expectedPath=WirelessPath;var s=await ScriptAsync("RouterAdapter.saveWireless()");if(!JsonOk(s)){SetStatus("SAVE Allow-List اجرا نشد.");return;}await Task.Delay(1400);if(purpose==Purpose.VerifyAllowList)web.CoreWebView2.Navigate(BaseUrl+WirelessPath);}
    private async Task VerifyAllowListAsync(){var json=await ScriptAsync("RouterAdapter.wirelessState()");using var d=JsonDocument.Parse(json);var r=d.RootElement;bool enabled=r.GetProperty("enabled").GetBoolean();string mode=r.GetProperty("mode").GetString()??"";var set=new HashSet<string>(StringComparer.OrdinalIgnoreCase);foreach(var m in r.GetProperty("macs").EnumerateArray())set.Add(m.GetString()??"");bool ok=enabled&&mode.Contains("allow association",StringComparison.OrdinalIgnoreCase)&&targetAllowed.All(set.Contains);purpose=Purpose.None;expectedPath="";SetStatus(ok?"ضد QR واقعی فعال و Verify شد.":"Allow-List بعد از SAVE تأیید نشد؛ موفق ثبت نشد.");}

    private async Task PrepareFilterOffAsync(){var json=await ScriptAsync("RouterAdapter.prepareFilterOff()");if(!JsonOk(json)){SetStatus("Filter Off آماده نشد: "+JsonError(json));return;}purpose=Purpose.VerifyFilterOff;expectedPath=WirelessPath;var s=await ScriptAsync("RouterAdapter.saveWireless()");if(!JsonOk(s)){SetStatus("SAVE اجرا نشد.");return;}await Task.Delay(1400);if(purpose==Purpose.VerifyFilterOff)web.CoreWebView2.Navigate(BaseUrl+WirelessPath);}
    private async Task VerifyFilterOffAsync(){var json=await ScriptAsync("RouterAdapter.wirelessState()");using var d=JsonDocument.Parse(json);bool ok=d.RootElement.GetProperty("ok").GetBoolean()&&!d.RootElement.GetProperty("enabled").GetBoolean();purpose=Purpose.None;expectedPath="";SetStatus(ok?"MAC Filter واقعاً خاموش و Verify شد.":"خاموش‌شدن MAC Filter تأیید نشد.");}

    private async Task ReadStatsAsync(){var json=await ScriptAsync("RouterAdapter.scanStats()");using var d=JsonDocument.Parse(json);var r=d.RootElement;if(!r.GetProperty("ok").GetBoolean()){SetStatus("Statistics خوانده نشد.");return;}if(!r.TryGetProperty("rxBytes",out var rxE)||rxE.ValueKind==JsonValueKind.Null||!r.TryGetProperty("txBytes",out var txE)||txE.ValueKind==JsonValueKind.Null){usage.Text="Byte counter قابل‌تشخیص نیست؛ مصرف جعلی نمایش داده نمی‌شود.";return;}long rx=rxE.GetInt64(),tx=txE.GetInt64();var state=LoadStateObject();long lastRx=state.LastRx,lastTx=state.LastTx,carried=state.Carried;if(lastRx>=0)carried+=rx>=lastRx?rx-lastRx:rx;if(lastTx>=0)carried+=tx>=lastTx?tx-lastTx:tx;state.LastRx=rx;state.LastTx=tx;state.Carried=carried;SaveStateObject(state);double used=carried/1073741824.0;double? pkg=double.TryParse(packageGb.Text,out var p)?p:null;usage.Text=$"مصرف ثبت‌شده: {used:F3} GB"+(pkg.HasValue?$" | باقی‌مانده تخمینی: {Math.Max(0,pkg.Value-used):F3} GB":"");SetStatus("Statistics واقعی خوانده شد.");}

    private async Task PrepareGuestAsync(bool on){var json=await ScriptAsync("RouterAdapter.setGuestEnabled("+(on?"true":"false")+")");if(!JsonOk(json)){SetStatus("Guest control آماده نشد: "+JsonError(json));return;}purpose=Purpose.VerifyGuest;expectedPath=GuestPath;var s=await ScriptAsync("RouterAdapter.saveGuest()");if(!JsonOk(s)){SetStatus("SAVE Guest اجرا نشد.");return;}await Task.Delay(1400);if(purpose==Purpose.VerifyGuest)web.CoreWebView2.Navigate(BaseUrl+GuestPath);}
    private async Task VerifyGuestChangeAsync(){var json=await ScriptAsync("RouterAdapter.scanGuest()");purpose=Purpose.None;expectedPath="";SetStatus(JsonOk(json)?"SAVE Guest اجرا شد و فرم واقعی دوباره خوانده شد.":"فرم Guest بعد از SAVE دوباره خوانده نشد.");}

    private void UpdateButtons(){refreshBtn.Enabled=connected&&clientsReady;allowBtn.Enabled=connected&&wirelessReady;filterOffBtn.Enabled=connected&&wirelessReady;statsBtn.Enabled=connected&&statsReady;guestOnBtn.Enabled=connected&&guestReady;guestOffBtn.Enabled=connected&&guestReady;var selected=SelectedMac()!=null;blockBtn.Enabled=selected&&wirelessReady&&SelectedMac()!=protectedMac;unblockBtn.Enabled=selected&&wirelessReady;capabilities.Text=$"Devices: {(clientsReady?"✓":"✗")} | MAC Filter: {(wirelessReady?"✓":"✗")}{(wirelessCapacity>0?$" ({wirelessCapacity})":"")} | Statistics: {(statsReady?"✓":"✗")} | Guest: {(guestReady?"✓":"✗")}";}
    private void SetStatus(string text){status.Text=text;UpdateButtons();}
    private static bool JsonOk(string json){try{using var d=JsonDocument.Parse(json);return d.RootElement.TryGetProperty("ok",out var o)&&o.GetBoolean();}catch{return false;}}
    private static string JsonError(string json){try{using var d=JsonDocument.Parse(json);return d.RootElement.TryGetProperty("error",out var e)?e.GetString()??"UNKNOWN":"UNKNOWN";}catch{return "INVALID_RESPONSE";}}

    private sealed class LocalState { public string RouterUrl {get;set;}="http://192.168.1.1"; public string User {get;set;}="admin"; public string ProtectedMac {get;set;}=""; public Dictionary<string,string> Aliases {get;set;}=new(); public long LastRx {get;set;}=-1; public long LastTx {get;set;}=-1; public long Carried {get;set;}=0; }
    private LocalState LoadStateObject(){try{return File.Exists(statePath)?JsonSerializer.Deserialize<LocalState>(File.ReadAllText(statePath))??new():new();}catch{return new();}}
    private void LoadState(){var s=LoadStateObject();routerUrl.Text=s.RouterUrl;username.Text=s.User;protectedMac=s.ProtectedMac;aliases.Clear();foreach(var kv in s.Aliases)aliases[kv.Key]=kv.Value;}
    private void SaveState(){var s=LoadStateObject();s.RouterUrl=BaseUrl;s.User=username.Text.Trim();s.ProtectedMac=protectedMac;s.Aliases=new Dictionary<string,string>(aliases);SaveStateObject(s);}
    private void SaveStateObject(LocalState s){Directory.CreateDirectory(Path.GetDirectoryName(statePath)!);File.WriteAllText(statePath,JsonSerializer.Serialize(s,new JsonSerializerOptions{WriteIndented=true}));}

    private static string? Prompt(string title,string initial){using var f=new Form{Text=title,Width=380,Height=150,StartPosition=FormStartPosition.CenterParent};var t=new TextBox{Left=15,Top=15,Width=335,Text=initial};var ok=new Button{Text="OK",Left=190,Top=50,Width=75,DialogResult=DialogResult.OK};var cancel=new Button{Text="Cancel",Left=275,Top=50,Width=75,DialogResult=DialogResult.Cancel};f.Controls.AddRange(new Control[]{t,ok,cancel});f.AcceptButton=ok;f.CancelButton=cancel;return f.ShowDialog()==DialogResult.OK?t.Text:null;}
}
