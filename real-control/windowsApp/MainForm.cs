using Microsoft.Web.WebView2.WinForms;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace WiFiControl.Real.Windows;

public sealed class MainForm : Form
{
    private enum Purpose
    {
        None, ConnectRoot, VerifyClients,
        DiscoverBasic, VerifyWireless, VerifyGuest,
        DiscoverAccess, VerifyAccess, DiscoverAdvanced, VerifyQos, VerifyStats,
        RefreshClients,
        PrepWifiBlock, PrepWifiUnblock, VerifyWifiRule, VerifyWifiPresence,
        PrepInternetBlock, PrepInternetUnblock, VerifyInternetRule,
        PrepAllowList, VerifyAllowList, PrepFilterOff, VerifyFilterOff,
        PrepWpsOff, VerifyWpsOff,
        PrepQos, PrepQosOff, VerifyQosRule,
        ReadStats,
        PrepGuestOn, PrepGuestOff, PrepGuestBandwidth,
        PrepGuestIsolationOn, PrepGuestIsolationOff,
        PrepGuestLocalOn, PrepGuestLocalOff,
        PrepGuestCredentials, VerifyGuestChange
    }

    private const string DevicePath = "/status/status_deviceinfo.htm";
    private const string StatsPath = "/status/status_statistics.htm";
    private const string BasicNav = "/navigation-basic.html";
    private const string AccessNav = "/navigation-access.html";
    private const string AdvancedNav = "/navigation-advanced.html";
    private const string RouterMac = "78:8C:B5:DD:8E:F0";

    private readonly TextBox routerUrl = new() { Text = "http://192.168.1.1", Width = 200 };
    private readonly TextBox username = new() { Text = "admin", Width = 100 };
    private readonly TextBox password = new() { Width = 120, UseSystemPasswordChar = true };
    private readonly Button connectBtn = new() { Text = "اتصال + کشف + Verify", AutoSize = true, Enabled = false };
    private readonly Button refreshBtn = new() { Text = "تازه‌سازی", AutoSize = true, Enabled = false };
    private readonly Label status = new() { AutoSize = false, Height = 58, Dock = DockStyle.Fill, Text = "در حال آماده‌سازی موتور…" };
    private readonly Label capabilities = new() { AutoSize = false, Height = 58, Dock = DockStyle.Fill, Text = "قابلیت‌ها هنوز Verify نشده‌اند." };
    private readonly CheckedListBox deviceList = new() { Dock = DockStyle.Fill, CheckOnClick = true };

    private readonly Button managerBtn = new() { Text = "انتخاب = مدیر", AutoSize = true, Enabled = false };
    private readonly Button renameBtn = new() { Text = "نام‌گذاری", AutoSize = true, Enabled = false };
    private readonly Button internetBlockBtn = new() { Text = "قطع اینترنت", AutoSize = true, Enabled = false };
    private readonly Button internetUnblockBtn = new() { Text = "وصل اینترنت", AutoSize = true, Enabled = false };
    private readonly Button wifiBlockBtn = new() { Text = "قطع Wi-Fi", AutoSize = true, Enabled = false };
    private readonly Button wifiUnblockBtn = new() { Text = "وصل Wi-Fi", AutoSize = true, Enabled = false };
    private readonly Button allowBtn = new() { Text = "ضد QR / Allow-List", AutoSize = true, Enabled = false };
    private readonly Button wpsOffBtn = new() { Text = "WPS OFF", AutoSize = true, Enabled = false };
    private readonly Button filterOffBtn = new() { Text = "MAC Filter OFF", AutoSize = true, Enabled = false };

    private readonly ComboBox qosLevelBox = new() { Width = 100, DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly Button qosApplyBtn = new() { Text = "اعمال QoS", AutoSize = true, Enabled = false };
    private readonly Button qosOffBtn = new() { Text = "QoS OFF", AutoSize = true, Enabled = false };

    private readonly TextBox packageGb = new() { Width = 85, PlaceholderText = "Package GB" };
    private readonly Button statsBtn = new() { Text = "آمار", AutoSize = true, Enabled = false };
    private readonly Label usage = new() { AutoSize = true, Text = "مصرف: خوانده نشده" };

    private readonly Button guestOnBtn = new() { Text = "Guest ON", AutoSize = true, Enabled = false };
    private readonly Button guestOffBtn = new() { Text = "Guest OFF", AutoSize = true, Enabled = false };
    private readonly TextBox guestUp = new() { Width = 80, PlaceholderText = "Upstream" };
    private readonly TextBox guestDown = new() { Width = 80, PlaceholderText = "Downstream" };
    private readonly Button guestBwBtn = new() { Text = "Guest BW", AutoSize = true, Enabled = false };
    private readonly Button guestIsoOnBtn = new() { Text = "Isolation ON", AutoSize = true, Enabled = false };
    private readonly Button guestIsoOffBtn = new() { Text = "Isolation OFF", AutoSize = true, Enabled = false };
    private readonly Button guestLanOffBtn = new() { Text = "Guest→LAN OFF", AutoSize = true, Enabled = false };
    private readonly Button guestLanOnBtn = new() { Text = "Guest→LAN ON", AutoSize = true, Enabled = false };
    private readonly TextBox guestSsid = new() { Width = 130, PlaceholderText = "Guest SSID" };
    private readonly TextBox guestPass = new() { Width = 130, PlaceholderText = "Guest password", UseSystemPasswordChar = true };
    private readonly Button guestCredBtn = new() { Text = "ثبت Guest", AutoSize = true, Enabled = false };

    private readonly WebView2 web = new() { Width = 2, Height = 2, Visible = false };
    private readonly Dictionary<string, string> displayToMac = new();
    private readonly Dictionary<string, string> aliases = new(StringComparer.OrdinalIgnoreCase);

    private string adapter = "";
    private Purpose purpose = Purpose.None;
    private string expectedPath = "";
    private int loginAttempts;

    private string wirelessPath = "/basic/home_wlan.htm";
    private string guestPath = "/basic/home_guest_network.htm";
    private string? accessPath;
    private string? qosPath;

    private bool connected, clientsReady, wirelessReady, wpsReady, accessReady, qosReady, statsReady, guestReady;
    private bool guestBandwidthReady, guestIsolationReady, guestLocalReady, guestCredentialsReady;
    private int wirelessCapacity;

    private string targetMac = "";
    private bool desiredBlock;
    private List<string> targetAllowed = new();
    private string qosLevel = "normal";
    private string guestVerifyKind = "";
    private string? guestExpectedText, guestExpectedUp, guestExpectedDown;
    private string protectedMac = "";

    private readonly string statePath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "WiFiControlReal", "state-v2.json");

    public MainForm()
    {
        Text = "WiFi Control Real v2 — TP-Link TD-W8961N V4";
        Width = 1100; Height = 780; MinimumSize = new Size(900, 650);
        RightToLeft = RightToLeft.Yes; RightToLeftLayout = true;
        qosLevelBox.Items.AddRange(new object[] { "High", "Normal", "Low" }); qosLevelBox.SelectedIndex = 1;
        BuildUi(); LoadState(); WireActions();
        Shown += async (_, _) => await InitWebAsync();
    }

    private void BuildUi()
    {
        var root = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 8, ColumnCount = 1, Padding = new Padding(10) };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.Absolute, 62));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 62)); root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        for (int i = 4; i < 8; i++) root.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        var top = Flow();
        top.Controls.AddRange(new Control[] { L("Router:"), routerUrl, L("User:"), username, L("Password:"), password, connectBtn, refreshBtn });
        root.Controls.Add(top, 0, 0); root.Controls.Add(status, 0, 1); root.Controls.Add(capabilities, 0, 2);

        var group = new GroupBox { Text = "دستگاه‌ها — تیک = مجاز در ضد QR", Dock = DockStyle.Fill };
        group.Controls.Add(deviceList); root.Controls.Add(group, 0, 3);

        var deviceActions = Flow();
        deviceActions.Controls.AddRange(new Control[] { managerBtn, renameBtn, internetBlockBtn, internetUnblockBtn, wifiBlockBtn, wifiUnblockBtn, allowBtn, wpsOffBtn, filterOffBtn });
        root.Controls.Add(deviceActions, 0, 4);

        var qos = Flow(); qos.Controls.AddRange(new Control[] { L("QoS انتخاب‌شده:"), qosLevelBox, qosApplyBtn, qosOffBtn, L(" | بسته GB:"), packageGb, statsBtn, usage });
        root.Controls.Add(qos, 0, 5);

        var guest1 = Flow(); guest1.Controls.AddRange(new Control[] { L("Guest:"), guestOnBtn, guestOffBtn, guestUp, guestDown, guestBwBtn, guestIsoOnBtn, guestIsoOffBtn, guestLanOffBtn, guestLanOnBtn });
        root.Controls.Add(guest1, 0, 6);
        var guest2 = Flow(); guest2.Controls.AddRange(new Control[] { guestSsid, guestPass, guestCredBtn, L("قابلیت غیرقابل‌تشخیص فعال نمی‌شود.") });
        root.Controls.Add(guest2, 0, 7);

        Controls.Add(root); Controls.Add(web);
    }

    private static FlowLayoutPanel Flow() => new() { Dock = DockStyle.Fill, AutoSize = true, WrapContents = true };
    private static Label L(string t) => new() { Text = t, AutoSize = true, Padding = new Padding(0, 8, 0, 0) };

    private void WireActions()
    {
        connectBtn.Click += (_, _) => StartConnection();
        refreshBtn.Click += (_, _) => { if (connected) Go(DevicePath, Purpose.RefreshClients); };
        managerBtn.Click += (_, _) => MarkManager(); renameBtn.Click += (_, _) => RenameSelected();
        internetBlockBtn.Click += (_, _) => StartInternetRule(true); internetUnblockBtn.Click += (_, _) => StartInternetRule(false);
        wifiBlockBtn.Click += (_, _) => StartWifiRule(true); wifiUnblockBtn.Click += (_, _) => StartWifiRule(false);
        allowBtn.Click += (_, _) => ActivateAllowList(); wpsOffBtn.Click += (_, _) => { if (wpsReady) Go(wirelessPath, Purpose.PrepWpsOff); };
        filterOffBtn.Click += (_, _) => ConfirmFilterOff();
        qosApplyBtn.Click += (_, _) => StartQos(false); qosOffBtn.Click += (_, _) => StartQos(true);
        statsBtn.Click += (_, _) => { if (statsReady) Go(StatsPath, Purpose.ReadStats); };
        guestOnBtn.Click += (_, _) => StartGuest("enabled", true); guestOffBtn.Click += (_, _) => StartGuest("enabled", false);
        guestBwBtn.Click += (_, _) => StartGuestBandwidth(); guestIsoOnBtn.Click += (_, _) => StartGuest("isolation", true); guestIsoOffBtn.Click += (_, _) => StartGuest("isolation", false);
        guestLanOffBtn.Click += (_, _) => StartGuest("local", false); guestLanOnBtn.Click += (_, _) => StartGuest("local", true); guestCredBtn.Click += (_, _) => StartGuestCredentials();
        deviceList.SelectedIndexChanged += (_, _) => UpdateUi();
    }

    private async Task InitWebAsync()
    {
        try
        {
            adapter = await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "router_adapter.js"));
            await web.EnsureCoreWebView2Async();
            web.CoreWebView2.Settings.AreDevToolsEnabled = false; web.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
            web.CoreWebView2.NavigationCompleted += async (_, _) => await HandlePageAsync();
            connectBtn.Enabled = true; SetStatus("آماده — موتور داخلی فعال است.");
        }
        catch (Exception ex) { SetStatus("WebView2 آماده نشد: " + ex.Message); }
    }

    private string BaseUrl => (string.IsNullOrWhiteSpace(routerUrl.Text) ? "http://192.168.1.1" : routerUrl.Text.Trim()).TrimEnd('/');

    private void ResetCaps()
    {
        connected = clientsReady = wirelessReady = wpsReady = accessReady = qosReady = statsReady = guestReady = false;
        guestBandwidthReady = guestIsolationReady = guestLocalReady = guestCredentialsReady = false; wirelessCapacity = 0;
        wirelessPath = "/basic/home_wlan.htm"; guestPath = "/basic/home_guest_network.htm"; accessPath = null; qosPath = null;
    }

    private void StartConnection()
    {
        if (string.IsNullOrWhiteSpace(username.Text) || string.IsNullOrEmpty(password.Text)) { SetStatus("نام کاربری و رمز ادمین را وارد کن."); return; }
        if (web.CoreWebView2 is null) { SetStatus("موتور WebView2 هنوز آماده نیست."); return; }
        SaveState(); ResetCaps(); loginAttempts = 0; deviceList.Items.Clear(); displayToMac.Clear(); purpose = Purpose.ConnectRoot; expectedPath = "";
        SetStatus("در حال ورود، کشف مسیرهای واقعی firmware و Verify قابلیت‌ها…"); web.CoreWebView2.Navigate(BaseUrl);
    }

    private async Task HandlePageAsync()
    {
        if (purpose == Purpose.None) return;
        if (await IsLoginAsync()) { await AutoLoginAsync(); return; }
        loginAttempts = 0; string url = web.Source?.ToString() ?? "";
        if (!string.IsNullOrWhiteSpace(expectedPath) && !UrlPathMatches(url, expectedPath)) { web.CoreWebView2.Navigate(BaseUrl + expectedPath); return; }

        switch (purpose)
        {
            case Purpose.ConnectRoot: Go(DevicePath, Purpose.VerifyClients); break;
            case Purpose.VerifyClients: await ReadClientsAsync(() => Go(BasicNav, Purpose.DiscoverBasic)); break;
            case Purpose.DiscoverBasic: await DiscoverBasicAsync(); break;
            case Purpose.VerifyWireless: await VerifyWirelessAsync(); break;
            case Purpose.VerifyGuest: await VerifyGuestAsync(); break;
            case Purpose.DiscoverAccess: await DiscoverAccessAsync(); break;
            case Purpose.VerifyAccess: await VerifyAccessAsync(); break;
            case Purpose.DiscoverAdvanced: await DiscoverAdvancedAsync(); break;
            case Purpose.VerifyQos: await VerifyQosAsync(); break;
            case Purpose.VerifyStats: await VerifyStatsFinishAsync(); break;
            case Purpose.RefreshClients: await ReadClientsAsync(() => Finish("فهرست دستگاه‌ها تازه شد.")); break;
            case Purpose.PrepWifiBlock: await PrepareWifiRuleAsync(true); break;
            case Purpose.PrepWifiUnblock: await PrepareWifiRuleAsync(false); break;
            case Purpose.VerifyWifiRule: await VerifyWifiRuleAsync(); break;
            case Purpose.VerifyWifiPresence: await VerifyWifiPresenceAsync(); break;
            case Purpose.PrepInternetBlock: await PrepareInternetRuleAsync(true); break;
            case Purpose.PrepInternetUnblock: await PrepareInternetRuleAsync(false); break;
            case Purpose.VerifyInternetRule: await VerifyInternetRuleAsync(); break;
            case Purpose.PrepAllowList: await PrepareAllowListAsync(); break;
            case Purpose.VerifyAllowList: await VerifyAllowListAsync(); break;
            case Purpose.PrepFilterOff: await PrepareFilterOffAsync(); break;
            case Purpose.VerifyFilterOff: await VerifyFilterOffAsync(); break;
            case Purpose.PrepWpsOff: await PrepareWpsOffAsync(); break;
            case Purpose.VerifyWpsOff: await VerifyWpsOffAsync(); break;
            case Purpose.PrepQos: await PrepareQosAsync(false); break;
            case Purpose.PrepQosOff: await PrepareQosAsync(true); break;
            case Purpose.VerifyQosRule: await VerifyQosRuleAsync(); break;
            case Purpose.ReadStats: await ReadStatsAsync(); break;
            case Purpose.PrepGuestOn: await PrepareGuestStateAsync("enabled", true); break;
            case Purpose.PrepGuestOff: await PrepareGuestStateAsync("enabled", false); break;
            case Purpose.PrepGuestBandwidth: await PrepareGuestBandwidthAsync(); break;
            case Purpose.PrepGuestIsolationOn: await PrepareGuestStateAsync("isolation", true); break;
            case Purpose.PrepGuestIsolationOff: await PrepareGuestStateAsync("isolation", false); break;
            case Purpose.PrepGuestLocalOn: await PrepareGuestStateAsync("local", true); break;
            case Purpose.PrepGuestLocalOff: await PrepareGuestStateAsync("local", false); break;
            case Purpose.PrepGuestCredentials: await PrepareGuestCredentialsAsync(); break;
            case Purpose.VerifyGuestChange: await VerifyGuestChangeAsync(); break;
        }
    }

    private async Task<bool> IsLoginAsync() => await RunRawAsync("(function(){return !!document.querySelector('input[type=password]')||location.href.toLowerCase().indexOf('login_security')>=0;})()") == "true";

    private async Task AutoLoginAsync()
    {
        if (++loginAttempts > 3) { purpose = Purpose.None; SetStatus("ورود تأیید نشد؛ نام کاربری یا رمز را بررسی کن."); return; }
        string uq = JsonSerializer.Serialize(username.Text.Trim()), pq = JsonSerializer.Serialize(password.Text);
        string js = $@"(function(){{try{{var p=document.querySelector('input[type=password]');var u=document.querySelector('input[name*=user i],input[id*=user i],input[type=text]');if(!u||!p)return 'NO_LOGIN_FORM';u.value={uq};p.value={pq};['input','change'].forEach(function(n){{u.dispatchEvent(new Event(n,{{bubbles:true}}));p.dispatchEvent(new Event(n,{{bubbles:true}}));}});var f=p.form||u.form||document.forms[0];if(!f)return 'NO_LOGIN_FORM';var bs=f.querySelectorAll('input[type=submit],input[type=button],button');for(var i=0;i<bs.length;i++){{var t=((bs[i].value||bs[i].innerText||'')+'').toLowerCase();if(t.indexOf('login')>=0){{bs[i].click();return 'CLICKED';}}}}if(bs.length){{bs[0].click();return 'CLICKED';}}f.submit();return 'SUBMITTED';}}catch(e){{return 'ERR:'+e;}}}})()";
        string r = await RunRawAsync(js); if (r.Contains("NO_LOGIN") || r.StartsWith("ERR")) { purpose = Purpose.None; SetStatus("فرم ورود شناخته نشد؛ هیچ تنظیمی تغییر نکرد."); }
    }

    private void Go(string path, Purpose next) { purpose = next; expectedPath = path; web.CoreWebView2.Navigate(BaseUrl + path); }
    private static bool UrlPathMatches(string url, string path) { try { return new Uri(url).AbsolutePath.Equals(path, StringComparison.OrdinalIgnoreCase); } catch { return url.Contains(path, StringComparison.OrdinalIgnoreCase); } }

    private async Task<string> RunRawAsync(string expression)
    {
        string raw = await web.CoreWebView2.ExecuteScriptAsync("try{JSON.stringify(" + expression + ")}catch(e){JSON.stringify({ok:false,error:String(e)})}");
        try { return JsonSerializer.Deserialize<string>(raw) ?? raw; } catch { return raw.Trim('"'); }
    }
    private async Task<string> AdapterAsync(string expression)
    {
        string raw = await web.CoreWebView2.ExecuteScriptAsync(adapter + "\n;try{JSON.stringify(" + expression + ")}catch(e){JSON.stringify({ok:false,error:String(e)})}");
        try { return JsonSerializer.Deserialize<string>(raw) ?? raw; } catch { return raw.Trim('"'); }
    }

    private async Task ReadClientsAsync(Action after)
    {
        string json = await AdapterAsync($"RouterAdapter.scanClients({JsonSerializer.Serialize(RouterMac)})");
        if (!JsonOk(json)) { purpose = Purpose.None; SetStatus("جدول دستگاه‌ها خوانده نشد."); return; }
        using var d = JsonDocument.Parse(json); deviceList.Items.Clear(); displayToMac.Clear();
        foreach (var c in d.RootElement.GetProperty("clients").EnumerateArray())
        {
            string mac = c.GetProperty("mac").GetString()?.ToUpperInvariant() ?? ""; if (!ValidMac(mac) || mac == RouterMac) continue;
            string row = c.TryGetProperty("row", out var rr) ? rr.GetString() ?? "" : ""; string ip = Regex.Match(row, @"\b(?:\d{1,3}\.){3}\d{1,3}\b").Value;
            string alias = aliases.TryGetValue(mac, out var a) ? a : ""; string display = (alias.Length > 0 ? alias + " — " : "") + mac + (ip.Length > 0 ? " — " + ip : "") + (mac == protectedMac ? " — مدیر" : "") + (IsPrivateMac(mac) ? " — MAC خصوصی" : "");
            displayToMac[display] = mac; deviceList.Items.Add(display, mac == protectedMac);
        }
        clientsReady = true; UpdateUi(); after();
    }

    private async Task DiscoverBasicAsync()
    {
        string json = await AdapterAsync("RouterAdapter.discoverNavigation()");
        using var d = Parse(json); if (d is not null && d.RootElement.TryGetProperty("routes", out var r)) { wirelessPath = FindRoute(r, "wireless") ?? wirelessPath; guestPath = FindRoute(r, "guest") ?? guestPath; }
        Go(wirelessPath, Purpose.VerifyWireless);
    }
    private async Task VerifyWirelessAsync()
    {
        string json = await AdapterAsync("RouterAdapter.wirelessState()"); using var d = Parse(json);
        if (d is not null) { var r = d.RootElement; wirelessReady = GetBool(r, "ok"); wirelessCapacity = GetInt(r, "capacity"); if (r.TryGetProperty("wps", out var w)) wpsReady = GetBool(w, "supported"); }
        Go(guestPath, Purpose.VerifyGuest);
    }
    private async Task VerifyGuestAsync()
    {
        string json = await AdapterAsync("RouterAdapter.guestState()"); using var d = Parse(json);
        if (d is not null) { var r = d.RootElement; guestReady = GetBool(r, "ok"); if (r.TryGetProperty("capabilities", out var c)) { guestBandwidthReady = GetBool(c, "bandwidth"); guestIsolationReady = GetBool(c, "isolation"); guestLocalReady = GetBool(c, "localAccess"); guestCredentialsReady = GetBool(c, "ssid"); } if (r.TryGetProperty("ssid", out var s) && string.IsNullOrWhiteSpace(guestSsid.Text)) guestSsid.Text = s.GetString() ?? ""; }
        Go(AccessNav, Purpose.DiscoverAccess);
    }
    private async Task DiscoverAccessAsync()
    {
        string json = await AdapterAsync("RouterAdapter.discoverNavigation()"); using var d = Parse(json); if (d is not null && d.RootElement.TryGetProperty("routes", out var r)) accessPath = FindRoute(r, "filter");
        if (accessPath is null) Go(AdvancedNav, Purpose.DiscoverAdvanced); else Go(accessPath, Purpose.VerifyAccess);
    }
    private async Task VerifyAccessAsync() { accessReady = JsonOk(await AdapterAsync("RouterAdapter.accessState()")); Go(AdvancedNav, Purpose.DiscoverAdvanced); }
    private async Task DiscoverAdvancedAsync()
    {
        string json = await AdapterAsync("RouterAdapter.discoverNavigation()"); using var d = Parse(json); if (d is not null && d.RootElement.TryGetProperty("routes", out var r)) qosPath = FindRoute(r, "qos");
        if (qosPath is null) Go(StatsPath, Purpose.VerifyStats); else Go(qosPath, Purpose.VerifyQos);
    }
    private async Task VerifyQosAsync() { qosReady = JsonOk(await AdapterAsync("RouterAdapter.qosState()")); Go(StatsPath, Purpose.VerifyStats); }
    private async Task VerifyStatsFinishAsync() { statsReady = JsonOk(await AdapterAsync("RouterAdapter.scanStats()")); connected = clientsReady; purpose = Purpose.None; expectedPath = ""; SetStatus("کشف firmware پایان یافت؛ فقط قابلیت‌های پیدا و Verify‌شده فعال‌اند."); }

    private string? SelectedMac() { string s = deviceList.SelectedItem?.ToString() ?? ""; return displayToMac.TryGetValue(s, out var m) ? m : null; }
    private void MarkManager() { var m = SelectedMac(); if (m is null) return; protectedMac = m; SaveState(); SetStatus(m + " به‌عنوان مدیر محافظت شد."); Go(DevicePath, Purpose.RefreshClients); }
    private void RenameSelected() { var m = SelectedMac(); if (m is null) return; string? name = Prompt("نام دستگاه", aliases.TryGetValue(m, out var a) ? a : ""); if (name is null) return; aliases[m] = name.Trim(); SaveState(); Go(DevicePath, Purpose.RefreshClients); }

    private void StartInternetRule(bool block) { var m = SelectedMac(); if (m is null || accessPath is null) return; if (block && m == protectedMac) { SetStatus("دستگاه مدیر Block نمی‌شود."); return; } targetMac = m; desiredBlock = block; Go(accessPath, block ? Purpose.PrepInternetBlock : Purpose.PrepInternetUnblock); }
    private async Task PrepareInternetRuleAsync(bool block)
    {
        string expr = (block ? "RouterAdapter.prepareInternetBlock(" : "RouterAdapter.prepareInternetUnblock(") + JsonSerializer.Serialize(targetMac) + ")"; string json = await AdapterAsync(expr);
        if (!JsonOk(json)) { purpose = Purpose.None; SetStatus("MAC Filter اینترنت آماده نشد: " + JsonError(json)); return; }
        bool save = GetBool(Parse(json)?.RootElement, "needsSave"); purpose = Purpose.VerifyInternetRule; expectedPath = accessPath!;
        if (!save) { await VerifyInternetRuleAsync(); return; }
        if (!JsonOk(await AdapterAsync("RouterAdapter.saveAccess()"))) { purpose = Purpose.None; SetStatus("SAVE فیلتر اینترنت اجرا نشد."); return; }
        await Task.Delay(1400); if (purpose == Purpose.VerifyInternetRule) web.CoreWebView2.Navigate(BaseUrl + accessPath);
    }
    private async Task VerifyInternetRuleAsync()
    {
        string json = await AdapterAsync("({ok:true,blocked:RouterAdapter.isInternetBlocked(" + JsonSerializer.Serialize(targetMac) + ")})"); using var d = Parse(json); bool actual = d is not null && GetBool(d.RootElement, "blocked"); purpose = Purpose.None; expectedPath = "";
        SetStatus(actual == desiredBlock ? (desiredBlock ? "قطع اینترنت ثبت و از روتر Verify شد." : "فیلتر اینترنت برداشته و Verify شد.") : "SAVE انجام شد اما وضعیت فیلتر اینترنت قطعی Verify نشد؛ موفق ثبت نشد.");
    }

    private void StartWifiRule(bool block) { var m = SelectedMac(); if (m is null) return; if (block && m == protectedMac) { SetStatus("دستگاه مدیر Block نمی‌شود."); return; } targetMac = m; desiredBlock = block; Go(wirelessPath, block ? Purpose.PrepWifiBlock : Purpose.PrepWifiUnblock); }
    private async Task PrepareWifiRuleAsync(bool block)
    {
        string expr = (block ? "RouterAdapter.prepareBlock(" : "RouterAdapter.prepareUnblock(") + JsonSerializer.Serialize(targetMac) + ")"; string json = await AdapterAsync(expr);
        if (!JsonOk(json)) { purpose = Purpose.None; SetStatus("Wireless MAC Filter آماده نشد: " + JsonError(json)); return; }
        bool save = GetBool(Parse(json)?.RootElement, "needsSave"); purpose = Purpose.VerifyWifiRule; expectedPath = wirelessPath;
        if (!save) { await VerifyWifiRuleAsync(); return; }
        if (!JsonOk(await AdapterAsync("RouterAdapter.saveWireless()"))) { purpose = Purpose.None; SetStatus("SAVE Wireless اجرا نشد."); return; }
        await Task.Delay(1400); if (purpose == Purpose.VerifyWifiRule) web.CoreWebView2.Navigate(BaseUrl + wirelessPath);
    }
    private async Task VerifyWifiRuleAsync()
    {
        string json = await AdapterAsync("({ok:true,blocked:RouterAdapter.isBlocked(" + JsonSerializer.Serialize(targetMac) + ")})"); using var d = Parse(json); bool actual = d is not null && GetBool(d.RootElement, "blocked");
        if (actual != desiredBlock) { purpose = Purpose.None; SetStatus("Wireless rule پس از SAVE Verify نشد."); return; } Go(DevicePath, Purpose.VerifyWifiPresence);
    }
    private async Task VerifyWifiPresenceAsync()
    {
        string json = await AdapterAsync($"RouterAdapter.scanClients({JsonSerializer.Serialize(RouterMac)})"); bool online = json.Contains(targetMac, StringComparison.OrdinalIgnoreCase); purpose = Purpose.None; expectedPath = "";
        SetStatus(desiredBlock && online ? "قانون قطع Wi-Fi Verify شد اما دستگاه هنوز در Wireless Clients است؛ deauth فوری تأیید نشد." : desiredBlock ? "قطع Wi-Fi در قانون و جدول کلاینت‌ها تأیید شد." : "قانون قطع Wi-Fi برداشته شد؛ دستگاه اجازه اتصال دارد.");
    }

    private void ActivateAllowList()
    {
        if (string.IsNullOrWhiteSpace(protectedMac)) { SetStatus("اول دستگاه مدیر را مشخص کن."); return; }
        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { protectedMac }; for (int i = 0; i < deviceList.Items.Count; i++) if (deviceList.GetItemChecked(i)) { var s = deviceList.Items[i]?.ToString() ?? ""; if (displayToMac.TryGetValue(s, out var m)) set.Add(m); }
        if (wirelessCapacity > 0 && set.Count > wirelessCapacity) { SetStatus($"ظرفیت MAC Filter فقط {wirelessCapacity} دستگاه است."); return; }
        if (MessageBox.Show($"فقط {set.Count} MAC اجازه اتصال داشته باشند؟", "ضد QR", MessageBoxButtons.YesNo) != DialogResult.Yes) return;
        targetAllowed = set.ToList(); Go(wirelessPath, Purpose.PrepAllowList);
    }
    private async Task PrepareAllowListAsync()
    {
        if (!JsonOk(await AdapterAsync("RouterAdapter.prepareAllowList(" + JsonSerializer.Serialize(targetAllowed) + ")"))) { purpose = Purpose.None; SetStatus("Allow-List آماده نشد."); return; }
        purpose = Purpose.VerifyAllowList; expectedPath = wirelessPath; if (!JsonOk(await AdapterAsync("RouterAdapter.saveWireless()"))) { purpose = Purpose.None; SetStatus("SAVE ضد QR اجرا نشد."); return; } await Task.Delay(1400); web.CoreWebView2.Navigate(BaseUrl + wirelessPath);
    }
    private async Task VerifyAllowListAsync()
    {
        string json = await AdapterAsync("RouterAdapter.wirelessState()"); using var d = Parse(json); bool ok = false; if (d is not null) { var r = d.RootElement; var actual = new HashSet<string>(StringComparer.OrdinalIgnoreCase); if (r.TryGetProperty("macs", out var ms)) foreach (var m in ms.EnumerateArray()) actual.Add(m.GetString() ?? ""); ok = GetBool(r, "enabled") && GetString(r, "mode").Contains("allow association", StringComparison.OrdinalIgnoreCase) && targetAllowed.All(actual.Contains); }
        purpose = Purpose.None; expectedPath = ""; SetStatus(ok ? "ضد QR فعال و Allow-List از خود روتر Verify شد." : "Allow-List پس از SAVE Verify نشد.");
    }

    private void ConfirmFilterOff() { if (MessageBox.Show("Wireless MAC Filter خاموش شود؟", "Emergency", MessageBoxButtons.YesNo) == DialogResult.Yes) Go(wirelessPath, Purpose.PrepFilterOff); }
    private async Task PrepareFilterOffAsync() { if (!JsonOk(await AdapterAsync("RouterAdapter.prepareFilterOff()"))) { purpose = Purpose.None; SetStatus("Filter Off آماده نشد."); return; } purpose = Purpose.VerifyFilterOff; expectedPath = wirelessPath; if (!JsonOk(await AdapterAsync("RouterAdapter.saveWireless()"))) { purpose = Purpose.None; SetStatus("SAVE اجرا نشد."); return; } await Task.Delay(1400); web.CoreWebView2.Navigate(BaseUrl + wirelessPath); }
    private async Task VerifyFilterOffAsync() { string json = await AdapterAsync("RouterAdapter.wirelessState()"); using var d = Parse(json); bool ok = d is not null && GetBool(d.RootElement, "ok") && !GetBool(d.RootElement, "enabled"); purpose = Purpose.None; expectedPath = ""; SetStatus(ok ? "Wireless MAC Filter خاموش و Verify شد." : "خاموش‌شدن Filter Verify نشد."); }

    private async Task PrepareWpsOffAsync() { if (!JsonOk(await AdapterAsync("RouterAdapter.prepareWps(false)"))) { purpose = Purpose.None; SetStatus("WPS قابل کنترل نبود."); return; } purpose = Purpose.VerifyWpsOff; expectedPath = wirelessPath; if (!JsonOk(await AdapterAsync("RouterAdapter.saveWireless()"))) { purpose = Purpose.None; SetStatus("SAVE WPS اجرا نشد."); return; } await Task.Delay(1400); web.CoreWebView2.Navigate(BaseUrl + wirelessPath); }
    private async Task VerifyWpsOffAsync() { string json = await AdapterAsync("RouterAdapter.wpsState()"); using var d = Parse(json); bool ok = d is not null && GetBool(d.RootElement, "supported") && !GetBool(d.RootElement, "enabled"); purpose = Purpose.None; expectedPath = ""; SetStatus(ok ? "WPS خاموش و Verify شد." : "خاموش‌شدن WPS Verify نشد."); }

    private void StartQos(bool off) { var m = SelectedMac(); if (m is null || qosPath is null) return; targetMac = m; qosLevel = qosLevelBox.Text.ToLowerInvariant(); Go(qosPath, off ? Purpose.PrepQosOff : Purpose.PrepQos); }
    private async Task PrepareQosAsync(bool off)
    {
        string expr = off ? "RouterAdapter.prepareQosOff(" + JsonSerializer.Serialize(targetMac) + ")" : "RouterAdapter.prepareQos(" + JsonSerializer.Serialize(targetMac) + "," + JsonSerializer.Serialize(qosLevel) + ")"; string json = await AdapterAsync(expr);
        if (!JsonOk(json)) { purpose = Purpose.None; SetStatus("QoS آماده نشد: " + JsonError(json)); return; } bool save = GetBool(Parse(json)?.RootElement, "needsSave"); if (!save) { purpose = Purpose.None; SetStatus("برای این MAC قاعده QoS فعال نبود."); return; }
        purpose = Purpose.VerifyQosRule; expectedPath = qosPath!; if (!JsonOk(await AdapterAsync("RouterAdapter.saveQos()"))) { purpose = Purpose.None; SetStatus("SAVE QoS اجرا نشد."); return; } await Task.Delay(1400); web.CoreWebView2.Navigate(BaseUrl + qosPath);
    }
    private async Task VerifyQosRuleAsync() { string json = await AdapterAsync("RouterAdapter.qosState()"); purpose = Purpose.None; expectedPath = ""; SetStatus(JsonOk(json) && json.Contains(targetMac, StringComparison.OrdinalIgnoreCase) ? "قاعده QoS برای MAC دوباره دیده شد. QoS اولویت است، نه سقف Mbps." : "SAVE QoS شد اما قاعده هدف قطعی Verify نشد."); }

    private async Task ReadStatsAsync()
    {
        string json = await AdapterAsync("RouterAdapter.scanStats()"); using var d = Parse(json); if (d is null || !GetBool(d.RootElement, "ok")) { purpose = Purpose.None; SetStatus("Statistics خوانده نشد."); return; }
        var r = d.RootElement; if (!r.TryGetProperty("rxBytes", out var rxE) || rxE.ValueKind == JsonValueKind.Null || !r.TryGetProperty("txBytes", out var txE) || txE.ValueKind == JsonValueKind.Null) { purpose = Purpose.None; usage.Text = "Byte counter قابل‌تشخیص نیست؛ مصرف جعلی نمایش داده نمی‌شود."; return; }
        long rx = rxE.GetInt64(), tx = txE.GetInt64(); var st = LoadStateObject(); if (st.LastRx >= 0) st.Carried += rx >= st.LastRx ? rx - st.LastRx : rx; if (st.LastTx >= 0) st.Carried += tx >= st.LastTx ? tx - st.LastTx : tx; st.LastRx = rx; st.LastTx = tx; SaveStateObject(st);
        double used = st.Carried / 1073741824.0; double? pkg = double.TryParse(packageGb.Text, out var p) ? p : null; usage.Text = $"مصرف ثبت‌شده: {used:F3} GB" + (pkg.HasValue ? $" | باقی‌مانده تخمینی: {Math.Max(0, pkg.Value - used):F3} GB" : ""); purpose = Purpose.None; expectedPath = ""; SetStatus("Statistics واقعی خوانده شد.");
    }

    private void StartGuest(string kind, bool on) { if (!guestReady) return; guestVerifyKind = kind; guestExpectedText = on ? "on" : "off"; Go(guestPath, kind == "enabled" ? (on ? Purpose.PrepGuestOn : Purpose.PrepGuestOff) : kind == "isolation" ? (on ? Purpose.PrepGuestIsolationOn : Purpose.PrepGuestIsolationOff) : (on ? Purpose.PrepGuestLocalOn : Purpose.PrepGuestLocalOff)); }
    private void StartGuestBandwidth() { if (!guestBandwidthReady) return; guestExpectedUp = string.IsNullOrWhiteSpace(guestUp.Text) ? null : guestUp.Text.Trim(); guestExpectedDown = string.IsNullOrWhiteSpace(guestDown.Text) ? null : guestDown.Text.Trim(); if (guestExpectedUp is null && guestExpectedDown is null) { SetStatus("Upstream یا Downstream را وارد کن."); return; } guestVerifyKind = "bandwidth"; Go(guestPath, Purpose.PrepGuestBandwidth); }
    private void StartGuestCredentials() { if (!guestCredentialsReady || string.IsNullOrWhiteSpace(guestSsid.Text)) { SetStatus("Guest SSID را وارد کن."); return; } guestVerifyKind = "credentials"; guestExpectedText = guestSsid.Text.Trim(); Go(guestPath, Purpose.PrepGuestCredentials); }
    private async Task PrepareGuestStateAsync(string kind, bool on) { string fn = kind == "enabled" ? "prepareGuestEnabled" : kind == "isolation" ? "prepareGuestIsolation" : "prepareGuestLocalAccess"; await SaveGuestPreparedAsync($"RouterAdapter.{fn}({(on ? "true" : "false")})"); }
    private async Task PrepareGuestBandwidthAsync() { await SaveGuestPreparedAsync("RouterAdapter.prepareGuestBandwidth(" + (guestExpectedUp ?? "null") + "," + (guestExpectedDown ?? "null") + ")"); }
    private async Task PrepareGuestCredentialsAsync() { await SaveGuestPreparedAsync("RouterAdapter.prepareGuestCredentials(" + JsonSerializer.Serialize(guestSsid.Text.Trim()) + "," + JsonSerializer.Serialize(guestPass.Text) + ")"); }
    private async Task SaveGuestPreparedAsync(string expr) { string json = await AdapterAsync(expr); if (!JsonOk(json)) { purpose = Purpose.None; SetStatus("Guest control آماده نشد: " + JsonError(json)); return; } purpose = Purpose.VerifyGuestChange; expectedPath = guestPath; if (!JsonOk(await AdapterAsync("RouterAdapter.saveGuest()"))) { purpose = Purpose.None; SetStatus("SAVE Guest اجرا نشد."); return; } await Task.Delay(1400); web.CoreWebView2.Navigate(BaseUrl + guestPath); }
    private async Task VerifyGuestChangeAsync()
    {
        string json = await AdapterAsync("RouterAdapter.guestState()"); using var d = Parse(json); bool ok = false; if (d is not null && GetBool(d.RootElement, "ok")) { var r = d.RootElement; ok = guestVerifyKind switch { "bandwidth" => (guestExpectedUp is null || GetString(r, "upstream") == guestExpectedUp) && (guestExpectedDown is null || GetString(r, "downstream") == guestExpectedDown), "credentials" => GetString(r, "ssid") == guestExpectedText, "enabled" => StateMatches(GetString(r, "enabled"), guestExpectedText == "on"), "isolation" => StateMatches(GetString(r, "isolation"), guestExpectedText == "on"), "local" => StateMatches(GetString(r, "localAccess"), guestExpectedText == "on"), _ => false }; }
        purpose = Purpose.None; expectedPath = ""; SetStatus(ok ? (guestVerifyKind == "credentials" ? "SSID مهمان Verify شد؛ متن رمز به دلیل mask شدن قابل readback نیست." : "تغییر Guest دوباره از روتر خوانده و Verify شد.") : "SAVE Guest شد اما مقدار هدف Verify نشد.");
    }

    private void UpdateUi()
    {
        refreshBtn.Enabled = connected && clientsReady; allowBtn.Enabled = connected && wirelessReady; filterOffBtn.Enabled = connected && wirelessReady; wpsOffBtn.Enabled = connected && wpsReady; statsBtn.Enabled = connected && statsReady;
        guestOnBtn.Enabled = guestOffBtn.Enabled = connected && guestReady; guestBwBtn.Enabled = connected && guestBandwidthReady; guestIsoOnBtn.Enabled = guestIsoOffBtn.Enabled = connected && guestIsolationReady; guestLanOffBtn.Enabled = guestLanOnBtn.Enabled = connected && guestLocalReady; guestCredBtn.Enabled = connected && guestCredentialsReady;
        string? selected = SelectedMac(); managerBtn.Enabled = renameBtn.Enabled = selected is not null; internetBlockBtn.Enabled = selected is not null && accessReady && selected != protectedMac; internetUnblockBtn.Enabled = selected is not null && accessReady; wifiBlockBtn.Enabled = selected is not null && wirelessReady && selected != protectedMac; wifiUnblockBtn.Enabled = selected is not null && wirelessReady; qosApplyBtn.Enabled = qosOffBtn.Enabled = selected is not null && qosReady;
        capabilities.Text = $"Devices {Mark(clientsReady)} | Wi-Fi MAC {Mark(wirelessReady)} | Internet MAC {Mark(accessReady)} | WPS {Mark(wpsReady)} | QoS {Mark(qosReady)}\nStatistics {Mark(statsReady)} | Guest {Mark(guestReady)} | Guest BW {Mark(guestBandwidthReady)} | Isolation {Mark(guestIsolationReady)} | Guest→LAN {Mark(guestLocalReady)} | Guest SSID {Mark(guestCredentialsReady)}";
    }

    private void Finish(string text) { purpose = Purpose.None; expectedPath = ""; connected = clientsReady; SetStatus(text); }
    private void SetStatus(string text) { status.Text = text; UpdateUi(); }
    private static string Mark(bool v) => v ? "✓" : "—";
    private static bool ValidMac(string m) => Regex.IsMatch(m, "^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$");
    private static bool IsPrivateMac(string m) { try { return (Convert.ToInt32(m[..2], 16) & 2) != 0; } catch { return false; } }
    private static bool StateMatches(string t, bool on) { t = t.ToLowerInvariant(); bool off = t.Contains("off") || t.Contains("disable") || t.Contains("deactivated") || t.Contains(" no") || t == "0"; return on ? !off && t.Length > 0 : off; }
    private static JsonDocument? Parse(string json) { try { return JsonDocument.Parse(json); } catch { return null; } }
    private static bool JsonOk(string json) { using var d = Parse(json); return d is not null && GetBool(d.RootElement, "ok"); }
    private static string JsonError(string json) { using var d = Parse(json); return d is not null ? GetString(d.RootElement, "error") : "INVALID_RESPONSE"; }
    private static bool GetBool(JsonElement? e, string name) => e.HasValue && e.Value.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.True;
    private static int GetInt(JsonElement e, string name) => e.TryGetProperty(name, out var v) && v.TryGetInt32(out var n) ? n : 0;
    private static string GetString(JsonElement e, string name) => e.TryGetProperty(name, out var v) ? v.ToString() : "";
    private static string? FindRoute(JsonElement routes, string term) { foreach (var p in routes.EnumerateObject()) if (p.Name.Contains(term, StringComparison.OrdinalIgnoreCase) && p.Value.ToString().StartsWith('/')) return p.Value.ToString(); return null; }

    private sealed class LocalState { public string RouterUrl { get; set; } = "http://192.168.1.1"; public string User { get; set; } = "admin"; public string ProtectedMac { get; set; } = ""; public Dictionary<string, string> Aliases { get; set; } = new(); public long LastRx { get; set; } = -1; public long LastTx { get; set; } = -1; public long Carried { get; set; } }
    private LocalState LoadStateObject() { try { return File.Exists(statePath) ? JsonSerializer.Deserialize<LocalState>(File.ReadAllText(statePath)) ?? new() : new(); } catch { return new(); } }
    private void LoadState() { var s = LoadStateObject(); routerUrl.Text = s.RouterUrl; username.Text = s.User; protectedMac = s.ProtectedMac; aliases.Clear(); foreach (var kv in s.Aliases) aliases[kv.Key] = kv.Value; }
    private void SaveState() { var s = LoadStateObject(); s.RouterUrl = BaseUrl; s.User = username.Text.Trim(); s.ProtectedMac = protectedMac; s.Aliases = new Dictionary<string, string>(aliases); SaveStateObject(s); }
    private void SaveStateObject(LocalState s) { Directory.CreateDirectory(Path.GetDirectoryName(statePath)!); File.WriteAllText(statePath, JsonSerializer.Serialize(s, new JsonSerializerOptions { WriteIndented = true })); }

    private static string? Prompt(string title, string initial)
    {
        using var f = new Form { Text = title, Width = 380, Height = 150, StartPosition = FormStartPosition.CenterParent };
        var t = new TextBox { Left = 15, Top = 15, Width = 335, Text = initial }; var ok = new Button { Text = "OK", Left = 190, Top = 50, Width = 75, DialogResult = DialogResult.OK }; var cancel = new Button { Text = "Cancel", Left = 275, Top = 50, Width = 75, DialogResult = DialogResult.Cancel };
        f.Controls.AddRange(new Control[] { t, ok, cancel }); f.AcceptButton = ok; f.CancelButton = cancel; return f.ShowDialog() == DialogResult.OK ? t.Text : null;
    }
}
