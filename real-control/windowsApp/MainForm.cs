using Microsoft.Web.WebView2.WinForms;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace WiFiControl.Real.Windows;

public sealed class MainForm : Form
{
    private enum Purpose
    {
        None,
        ConnectRoot,
        VerifyClients,
        VerifyWireless,
        VerifyStats,
        VerifyGuestCaps,
        RefreshClients,
        PrepareBlock,
        PrepareUnblock,
        VerifyBlockConfig,
        VerifyBlockOnline,
        PrepareAllowList,
        VerifyAllowList,
        PrepareFilterOff,
        VerifyFilterOff,
        ReadStats,
        PrepareGuestOn,
        PrepareGuestOff,
        VerifyGuestChange
    }

    private const string DevicePath = "/status/status_deviceinfo.htm";
    private const string WirelessPath = "/basic/home_wlan.htm";
    private const string StatsPath = "/status/status_statistics.htm";
    private const string GuestPath = "/basic/home_guest_network.htm";
    private const string RouterMac = "78:8C:B5:DD:8E:F0";

    private readonly TextBox routerUrl = new() { Text = "http://192.168.1.1", Width = 210 };
    private readonly TextBox username = new() { Text = "admin", Width = 110 };
    private readonly TextBox password = new() { Width = 130, UseSystemPasswordChar = true };
    private readonly Button connectBtn = new() { Text = "اتصال + Verify", AutoSize = true, Enabled = false };
    private readonly Button refreshBtn = new() { Text = "تازه‌سازی", AutoSize = true, Enabled = false };
    private readonly Label status = new() { AutoSize = false, Height = 60, Dock = DockStyle.Fill, Text = "در حال آماده‌سازی موتور…" };
    private readonly Label capabilities = new() { AutoSize = false, Height = 48, Dock = DockStyle.Fill, Text = "قابلیت‌ها هنوز Verify نشده‌اند." };
    private readonly CheckedListBox deviceList = new() { Dock = DockStyle.Fill, CheckOnClick = true };
    private readonly Button managerBtn = new() { Text = "انتخاب = مدیر", AutoSize = true, Enabled = false };
    private readonly Button renameBtn = new() { Text = "نام‌گذاری", AutoSize = true, Enabled = false };
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

    private readonly Dictionary<string, string> displayToMac = new();
    private readonly Dictionary<string, string> aliases = new(StringComparer.OrdinalIgnoreCase);

    private string adapter = "";
    private Purpose purpose = Purpose.None;
    private string expectedPath = "";
    private string targetMac = "";
    private bool targetBlocked;
    private List<string> targetAllowed = new();
    private bool connected;
    private bool clientsReady;
    private bool wirelessReady;
    private bool statsReady;
    private bool guestReady;
    private int wirelessCapacity;
    private int loginAttempts;
    private string protectedMac = "";

    private readonly string statePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "WiFiControlReal",
        "state.json"
    );

    public MainForm()
    {
        Text = "WiFi Control Real — TP-Link TD-W8961N V4";
        Width = 980;
        Height = 720;
        MinimumSize = new Size(850, 620);
        RightToLeft = RightToLeft.Yes;
        RightToLeftLayout = true;

        BuildUi();
        LoadState();

        Shown += async (_, _) => await InitWebAsync();
        connectBtn.Click += (_, _) => StartConnection();
        refreshBtn.Click += (_, _) => { if (connected && clientsReady) Navigate(DevicePath, Purpose.RefreshClients); };
        managerBtn.Click += (_, _) => MarkManager();
        renameBtn.Click += (_, _) => RenameSelected();
        blockBtn.Click += (_, _) => StartBlock(true);
        unblockBtn.Click += (_, _) => StartBlock(false);
        allowBtn.Click += (_, _) => ActivateAllowList();
        filterOffBtn.Click += (_, _) => ConfirmFilterOff();
        statsBtn.Click += (_, _) => { if (connected && statsReady) Navigate(StatsPath, Purpose.ReadStats); };
        guestOnBtn.Click += (_, _) => { if (connected && guestReady) Navigate(GuestPath, Purpose.PrepareGuestOn); };
        guestOffBtn.Click += (_, _) => { if (connected && guestReady) Navigate(GuestPath, Purpose.PrepareGuestOff); };
        deviceList.SelectedIndexChanged += (_, _) => UpdateUi();
    }

    private void BuildUi()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            RowCount = 6,
            ColumnCount = 1,
            Padding = new Padding(12)
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 65));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 50));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        var top = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, WrapContents = true };
        top.Controls.Add(new Label { Text = "Router:", AutoSize = true, Padding = new Padding(0, 8, 0, 0) });
        top.Controls.Add(routerUrl);
        top.Controls.Add(new Label { Text = "User:", AutoSize = true, Padding = new Padding(0, 8, 0, 0) });
        top.Controls.Add(username);
        top.Controls.Add(new Label { Text = "Password:", AutoSize = true, Padding = new Padding(0, 8, 0, 0) });
        top.Controls.Add(password);
        top.Controls.Add(connectBtn);
        top.Controls.Add(refreshBtn);

        var deviceGroup = new GroupBox
        {
            Text = "دستگاه‌های متصل — تیک = مجاز در Allow-List",
            Dock = DockStyle.Fill
        };
        deviceGroup.Controls.Add(deviceList);

        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, WrapContents = true };
        actions.Controls.Add(managerBtn);
        actions.Controls.Add(renameBtn);
        actions.Controls.Add(blockBtn);
        actions.Controls.Add(unblockBtn);
        actions.Controls.Add(allowBtn);
        actions.Controls.Add(filterOffBtn);

        var bottom = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, WrapContents = true };
        bottom.Controls.Add(new Label { Text = "بسته GB:", AutoSize = true, Padding = new Padding(0, 8, 0, 0) });
        bottom.Controls.Add(packageGb);
        bottom.Controls.Add(statsBtn);
        bottom.Controls.Add(usage);
        bottom.Controls.Add(guestOnBtn);
        bottom.Controls.Add(guestOffBtn);

        root.Controls.Add(top, 0, 0);
        root.Controls.Add(status, 0, 1);
        root.Controls.Add(capabilities, 0, 2);
        root.Controls.Add(deviceGroup, 0, 3);
        root.Controls.Add(actions, 0, 4);
        root.Controls.Add(bottom, 0, 5);

        Controls.Add(root);
        Controls.Add(web);
    }

    private async Task InitWebAsync()
    {
        try
        {
            adapter = await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "router_adapter.js"));
            await web.EnsureCoreWebView2Async();
            web.CoreWebView2.Settings.AreDevToolsEnabled = false;
            web.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
            web.CoreWebView2.NavigationCompleted += async (_, _) => await HandlePageAsync();
            connectBtn.Enabled = true;
            SetStatus("آماده — موتور WebView2 داخلی فعال است.");
        }
        catch (Exception ex)
        {
            SetStatus("WebView2 آماده نشد: " + ex.Message);
        }
    }

    private string BaseUrl => (string.IsNullOrWhiteSpace(routerUrl.Text) ? "http://192.168.1.1" : routerUrl.Text.Trim()).TrimEnd('/');

    private void StartConnection()
    {
        if (string.IsNullOrWhiteSpace(username.Text) || string.IsNullOrEmpty(password.Text))
        {
            SetStatus("نام کاربری و رمز ادمین را وارد کن.");
            return;
        }
        if (web.CoreWebView2 is null)
        {
            SetStatus("موتور WebView2 هنوز آماده نیست.");
            return;
        }

        SaveState();
        connected = false;
        clientsReady = false;
        wirelessReady = false;
        statsReady = false;
        guestReady = false;
        wirelessCapacity = 0;
        loginAttempts = 0;
        deviceList.Items.Clear();
        displayToMac.Clear();
        purpose = Purpose.ConnectRoot;
        expectedPath = "";
        SetStatus("در حال ورود واقعی و Verify firmware…");
        web.CoreWebView2.Navigate(BaseUrl);
    }

    private async Task HandlePageAsync()
    {
        if (purpose == Purpose.None) return;

        if (await IsLoginAsync())
        {
            await AutoLoginAsync();
            return;
        }

        loginAttempts = 0;
        string url = web.Source?.ToString() ?? "";
        if (!string.IsNullOrEmpty(expectedPath) && !UrlPathMatches(url, expectedPath))
        {
            web.CoreWebView2.Navigate(BaseUrl + expectedPath);
            return;
        }

        switch (purpose)
        {
            case Purpose.ConnectRoot:
                Navigate(DevicePath, Purpose.VerifyClients);
                break;
            case Purpose.VerifyClients:
                await ReadClientsAsync(() => Navigate(WirelessPath, Purpose.VerifyWireless));
                break;
            case Purpose.VerifyWireless:
                await VerifyWirelessAsync();
                break;
            case Purpose.VerifyStats:
                await VerifyStatsAsync();
                break;
            case Purpose.VerifyGuestCaps:
                await VerifyGuestCapabilitiesAsync();
                break;
            case Purpose.RefreshClients:
                await ReadClientsAsync(() => { purpose = Purpose.None; expectedPath = ""; SetStatus("فهرست از خود روتر تازه شد."); });
                break;
            case Purpose.PrepareBlock:
                await PrepareBlockAsync(true);
                break;
            case Purpose.PrepareUnblock:
                await PrepareBlockAsync(false);
                break;
            case Purpose.VerifyBlockConfig:
                await VerifyBlockConfigAsync();
                break;
            case Purpose.VerifyBlockOnline:
                await VerifyBlockOnlineAsync();
                break;
            case Purpose.PrepareAllowList:
                await PrepareAllowListAsync();
                break;
            case Purpose.VerifyAllowList:
                await VerifyAllowListAsync();
                break;
            case Purpose.PrepareFilterOff:
                await PrepareFilterOffAsync();
                break;
            case Purpose.VerifyFilterOff:
                await VerifyFilterOffAsync();
                break;
            case Purpose.ReadStats:
                await ReadStatsAsync();
                break;
            case Purpose.PrepareGuestOn:
                await PrepareGuestAsync(true);
                break;
            case Purpose.PrepareGuestOff:
                await PrepareGuestAsync(false);
                break;
            case Purpose.VerifyGuestChange:
                await VerifyGuestChangeAsync();
                break;
        }
    }

    private async Task<bool> IsLoginAsync()
    {
        string r = await ScriptAsync("(function(){return !!document.querySelector('input[type=password]')||location.href.toLowerCase().indexOf('login_security')>=0;})()", false);
        return r == "true";
    }

    private async Task AutoLoginAsync()
    {
        if (++loginAttempts > 3)
        {
            purpose = Purpose.None;
            SetStatus("ورود تأیید نشد؛ نام کاربری یا رمز را بررسی کن.");
            return;
        }

        string uq = JsonSerializer.Serialize(username.Text.Trim());
        string pq = JsonSerializer.Serialize(password.Text);
        string js = $@"(function(){{try{{
            var p=document.querySelector('input[type=password]');
            var u=document.querySelector('input[name*=user i],input[id*=user i],input[type=text]');
            if(!u||!p)return 'NO_LOGIN_FORM';
            u.value={uq};p.value={pq};
            ['input','change'].forEach(function(n){{u.dispatchEvent(new Event(n,{{bubbles:true}}));p.dispatchEvent(new Event(n,{{bubbles:true}}));}});
            var f=p.form||u.form||document.forms[0];if(!f)return 'NO_LOGIN_FORM';
            var bs=f.querySelectorAll('input[type=submit],input[type=button],button');
            for(var i=0;i<bs.length;i++){{var t=((bs[i].value||bs[i].innerText||'')+'').toLowerCase();if(t.indexOf('login')>=0){{bs[i].click();return 'CLICKED';}}}}
            if(bs.length){{bs[0].click();return 'CLICKED';}}
            f.submit();return 'SUBMITTED';
        }}catch(e){{return 'ERR:'+e;}}}})()";

        string r = await ScriptAsync(js, false);
        if (r.Contains("NO_LOGIN_FORM") || r.Contains("ERR:"))
        {
            purpose = Purpose.None;
            SetStatus("فرم ورود firmware شناخته نشد؛ هیچ تنظیمی تغییر نکرد.");
        }
    }

    private void Navigate(string path, Purpose next)
    {
        purpose = next;
        expectedPath = path;
        web.CoreWebView2.Navigate(BaseUrl + path);
    }

    private static bool UrlPathMatches(string url, string path)
    {
        try { return new Uri(url).AbsolutePath.Equals(path, StringComparison.OrdinalIgnoreCase); }
        catch { return url.Contains(path, StringComparison.OrdinalIgnoreCase); }
    }

    private async Task<string> ScriptAsync(string expression, bool withAdapter = true)
    {
        string js = (withAdapter ? adapter + "\n;" : "") +
                    "try{JSON.stringify(" + expression + ")}catch(e){JSON.stringify({ok:false,error:String(e)})}";
        string raw = await web.CoreWebView2.ExecuteScriptAsync(js);
        try { return JsonSerializer.Deserialize<string>(raw) ?? raw; }
        catch { return raw.Trim('"'); }
    }

    private async Task ReadClientsAsync(Action after)
    {
        string json = await ScriptAsync($"RouterAdapter.scanClients({JsonSerializer.Serialize(RouterMac)})");
        try
        {
            using JsonDocument doc = JsonDocument.Parse(json);
            if (!doc.RootElement.TryGetProperty("ok", out var ok) || !ok.GetBoolean())
            {
                purpose = Purpose.None;
                SetStatus("جدول دستگاه‌ها از firmware خوانده نشد.");
                return;
            }

            deviceList.Items.Clear();
            displayToMac.Clear();
            foreach (var c in doc.RootElement.GetProperty("clients").EnumerateArray())
            {
                string mac = c.GetProperty("mac").GetString()?.ToUpperInvariant() ?? "";
                if (string.IsNullOrWhiteSpace(mac) || mac == RouterMac) continue;
                string row = c.TryGetProperty("row", out var rr) ? rr.GetString() ?? "" : "";
                string ip = Regex.Match(row, @"\b(?:\d{1,3}\.){3}\d{1,3}\b").Value;
                string alias = aliases.TryGetValue(mac, out var a) ? a : "";
                string display = (alias.Length > 0 ? alias + " — " : "") + mac +
                                 (ip.Length > 0 ? " — " + ip : "") +
                                 (mac == protectedMac ? " — مدیر" : "");
                displayToMac[display] = mac;
                deviceList.Items.Add(display, mac == protectedMac);
            }
            clientsReady = true;
            UpdateUi();
            after();
        }
        catch (Exception ex)
        {
            purpose = Purpose.None;
            SetStatus("خواندن کلاینت‌ها شکست خورد: " + ex.Message);
        }
    }

    private async Task VerifyWirelessAsync()
    {
        string json = await ScriptAsync("RouterAdapter.wirelessState()");
        try
        {
            using JsonDocument d = JsonDocument.Parse(json);
            wirelessReady = d.RootElement.TryGetProperty("ok", out var ok) && ok.GetBoolean();
            wirelessCapacity = d.RootElement.TryGetProperty("capacity", out var c) ? c.GetInt32() : 0;
        }
        catch { wirelessReady = false; wirelessCapacity = 0; }
        Navigate(StatsPath, Purpose.VerifyStats);
    }

    private async Task VerifyStatsAsync()
    {
        string json = await ScriptAsync("RouterAdapter.scanStats()");
        try
        {
            using JsonDocument d = JsonDocument.Parse(json);
            statsReady = d.RootElement.TryGetProperty("ok", out var ok) && ok.GetBoolean();
        }
        catch { statsReady = false; }
        Navigate(GuestPath, Purpose.VerifyGuestCaps);
    }

    private async Task VerifyGuestCapabilitiesAsync()
    {
        string json = await ScriptAsync("RouterAdapter.scanGuest()");
        try
        {
            using JsonDocument d = JsonDocument.Parse(json);
            guestReady = d.RootElement.TryGetProperty("ok", out var ok) && ok.GetBoolean();
        }
        catch { guestReady = false; }

        connected = clientsReady;
        purpose = Purpose.None;
        expectedPath = "";
        SetStatus(connected
            ? "اتصال واقعی برقرار شد؛ قابلیت‌های قابل‌تشخیص از firmware خوانده شدند."
            : "اتصال کامل Verify نشد.");
    }

    private string? SelectedMac()
    {
        string display = deviceList.SelectedItem?.ToString() ?? "";
        return displayToMac.TryGetValue(display, out var mac) ? mac : null;
    }

    private void MarkManager()
    {
        string? mac = SelectedMac();
        if (mac is null) { SetStatus("یک دستگاه را انتخاب کن."); return; }
        protectedMac = mac;
        SaveState();
        SetStatus(mac + " به‌عنوان دستگاه مدیر محافظت شد.");
        Navigate(DevicePath, Purpose.RefreshClients);
    }

    private void RenameSelected()
    {
        string? mac = SelectedMac();
        if (mac is null) { SetStatus("یک دستگاه را انتخاب کن."); return; }
        string? name = Prompt("نام دستگاه", aliases.TryGetValue(mac, out var a) ? a : "");
        if (name is null) return;
        aliases[mac] = name.Trim();
        SaveState();
        Navigate(DevicePath, Purpose.RefreshClients);
    }

    private void StartBlock(bool blocked)
    {
        string? mac = SelectedMac();
        if (mac is null) { SetStatus("یک دستگاه را انتخاب کن."); return; }
        if (blocked && mac.Equals(protectedMac, StringComparison.OrdinalIgnoreCase))
        {
            SetStatus("دستگاه مدیر Block نمی‌شود.");
            return;
        }
        targetMac = mac;
        targetBlocked = blocked;
        Navigate(WirelessPath, blocked ? Purpose.PrepareBlock : Purpose.PrepareUnblock);
    }

    private async Task PrepareBlockAsync(bool blocked)
    {
        string expr = (blocked ? "RouterAdapter.prepareBlock(" : "RouterAdapter.prepareUnblock(") +
                      JsonSerializer.Serialize(targetMac) + ")";
        string json = await ScriptAsync(expr);
        if (!JsonOk(json))
        {
            purpose = Purpose.None;
            SetStatus("فرمان آماده نشد: " + JsonError(json));
            return;
        }

        using JsonDocument d = JsonDocument.Parse(json);
        bool needsSave = d.RootElement.TryGetProperty("needsSave", out var n) && n.GetBoolean();
        purpose = Purpose.VerifyBlockConfig;
        expectedPath = WirelessPath;

        if (!needsSave)
        {
            await VerifyBlockConfigAsync();
            return;
        }

        string save = await ScriptAsync("RouterAdapter.saveWireless()");
        if (!JsonOk(save))
        {
            purpose = Purpose.None;
            SetStatus("SAVE واقعی اجرا نشد: " + JsonError(save));
            return;
        }
        await Task.Delay(1400);
        if (purpose == Purpose.VerifyBlockConfig) web.CoreWebView2.Navigate(BaseUrl + WirelessPath);
    }

    private async Task VerifyBlockConfigAsync()
    {
        string json = await ScriptAsync("({ok:true,blocked:RouterAdapter.isBlocked(" + JsonSerializer.Serialize(targetMac) + ")})");
        using JsonDocument d = JsonDocument.Parse(json);
        bool actual = d.RootElement.TryGetProperty("blocked", out var b) && b.GetBoolean();
        if (actual != targetBlocked)
        {
            purpose = Purpose.None;
            SetStatus("وضعیت Block بعد از SAVE از خود روتر تأیید نشد.");
            return;
        }
        Navigate(DevicePath, Purpose.VerifyBlockOnline);
    }

    private async Task VerifyBlockOnlineAsync()
    {
        string json = await ScriptAsync($"RouterAdapter.scanClients({JsonSerializer.Serialize(RouterMac)})");
        using JsonDocument d = JsonDocument.Parse(json);
        var online = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var c in d.RootElement.GetProperty("clients").EnumerateArray())
        {
            string? m = c.GetProperty("mac").GetString();
            if (!string.IsNullOrWhiteSpace(m)) online.Add(m);
        }

        purpose = Purpose.None;
        expectedPath = "";
        if (targetBlocked)
        {
            SetStatus(!online.Contains(targetMac)
                ? "قطع واقعی تأیید شد؛ دستگاه دیگر در Wireless Clients نیست."
                : "قانون Block Verify شد اما دستگاه هنوز در Wireless Clients است؛ اپ این حالت را قطع کامل ثبت نمی‌کند.");
        }
        else
        {
            SetStatus("قانون Block برداشته شد و روتر آن را Verify کرد؛ دستگاه اجازه اتصال دارد.");
        }
    }

    private void ActivateAllowList()
    {
        if (string.IsNullOrWhiteSpace(protectedMac))
        {
            SetStatus("اول دستگاه مدیر را مشخص کن.");
            return;
        }

        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { protectedMac };
        for (int i = 0; i < deviceList.Items.Count; i++)
        {
            if (!deviceList.GetItemChecked(i)) continue;
            string display = deviceList.Items[i]?.ToString() ?? "";
            if (displayToMac.TryGetValue(display, out var mac)) set.Add(mac);
        }

        if (wirelessCapacity > 0 && set.Count > wirelessCapacity)
        {
            SetStatus($"ظرفیت واقعی MAC Filter فقط {wirelessCapacity} دستگاه است.");
            return;
        }

        if (MessageBox.Show($"فقط {set.Count} دستگاه اجازه Association داشته باشند؟",
                "Allow-List", MessageBoxButtons.YesNo) != DialogResult.Yes) return;

        targetAllowed = set.ToList();
        Navigate(WirelessPath, Purpose.PrepareAllowList);
    }

    private async Task PrepareAllowListAsync()
    {
        string json = await ScriptAsync("RouterAdapter.prepareAllowList(" + JsonSerializer.Serialize(targetAllowed) + ")");
        if (!JsonOk(json))
        {
            purpose = Purpose.None;
            SetStatus("Allow-List آماده نشد: " + JsonError(json));
            return;
        }
        purpose = Purpose.VerifyAllowList;
        expectedPath = WirelessPath;
        string save = await ScriptAsync("RouterAdapter.saveWireless()");
        if (!JsonOk(save))
        {
            purpose = Purpose.None;
            SetStatus("SAVE واقعی Allow-List اجرا نشد.");
            return;
        }
        await Task.Delay(1400);
        if (purpose == Purpose.VerifyAllowList) web.CoreWebView2.Navigate(BaseUrl + WirelessPath);
    }

    private async Task VerifyAllowListAsync()
    {
        string json = await ScriptAsync("RouterAdapter.wirelessState()");
        using JsonDocument d = JsonDocument.Parse(json);
        var r = d.RootElement;
        bool enabled = r.TryGetProperty("enabled", out var en) && en.GetBoolean();
        string mode = r.TryGetProperty("mode", out var mo) ? mo.GetString() ?? "" : "";
        var actual = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (r.TryGetProperty("macs", out var macs))
        {
            foreach (var m in macs.EnumerateArray()) actual.Add(m.GetString() ?? "");
        }
        bool ok = enabled && mode.Contains("allow association", StringComparison.OrdinalIgnoreCase) && targetAllowed.All(actual.Contains);
        purpose = Purpose.None;
        expectedPath = "";
        SetStatus(ok
            ? "ضد QR واقعی فعال شد و Allow-List از خود روتر Verify شد."
            : "Allow-List بعد از SAVE تأیید نشد؛ موفق ثبت نشد.");
    }

    private void ConfirmFilterOff()
    {
        if (MessageBox.Show("Wireless MAC Filter خاموش شود؟ WAN/ADSL تغییر نمی‌کند.",
                "Emergency", MessageBoxButtons.YesNo) == DialogResult.Yes)
        {
            Navigate(WirelessPath, Purpose.PrepareFilterOff);
        }
    }

    private async Task PrepareFilterOffAsync()
    {
        string json = await ScriptAsync("RouterAdapter.prepareFilterOff()");
        if (!JsonOk(json))
        {
            purpose = Purpose.None;
            SetStatus("Filter Off آماده نشد: " + JsonError(json));
            return;
        }
        purpose = Purpose.VerifyFilterOff;
        expectedPath = WirelessPath;
        string save = await ScriptAsync("RouterAdapter.saveWireless()");
        if (!JsonOk(save))
        {
            purpose = Purpose.None;
            SetStatus("SAVE واقعی اجرا نشد.");
            return;
        }
        await Task.Delay(1400);
        if (purpose == Purpose.VerifyFilterOff) web.CoreWebView2.Navigate(BaseUrl + WirelessPath);
    }

    private async Task VerifyFilterOffAsync()
    {
        string json = await ScriptAsync("RouterAdapter.wirelessState()");
        using JsonDocument d = JsonDocument.Parse(json);
        bool ok = d.RootElement.TryGetProperty("ok", out var good) && good.GetBoolean() &&
                  d.RootElement.TryGetProperty("enabled", out var enabled) && !enabled.GetBoolean();
        purpose = Purpose.None;
        expectedPath = "";
        SetStatus(ok ? "MAC Filter واقعاً خاموش شد و Verify شد." : "خاموش‌شدن MAC Filter تأیید نشد.");
    }

    private async Task ReadStatsAsync()
    {
        string json = await ScriptAsync("RouterAdapter.scanStats()");
        using JsonDocument d = JsonDocument.Parse(json);
        var r = d.RootElement;
        if (!r.TryGetProperty("ok", out var ok) || !ok.GetBoolean())
        {
            purpose = Purpose.None;
            SetStatus("Statistics خوانده نشد.");
            return;
        }
        if (!r.TryGetProperty("rxBytes", out var rxE) || rxE.ValueKind == JsonValueKind.Null ||
            !r.TryGetProperty("txBytes", out var txE) || txE.ValueKind == JsonValueKind.Null)
        {
            purpose = Purpose.None;
            usage.Text = "Byte counter قابل‌تشخیص نیست؛ مصرف جعلی نمایش داده نمی‌شود.";
            return;
        }

        long rx = rxE.GetInt64();
        long tx = txE.GetInt64();
        LocalState st = LoadStateObject();
        if (st.LastRx >= 0) st.Carried += rx >= st.LastRx ? rx - st.LastRx : rx;
        if (st.LastTx >= 0) st.Carried += tx >= st.LastTx ? tx - st.LastTx : tx;
        st.LastRx = rx;
        st.LastTx = tx;
        SaveStateObject(st);

        double used = st.Carried / 1073741824.0;
        double? pkg = double.TryParse(packageGb.Text, out var p) ? p : null;
        usage.Text = $"مصرف ثبت‌شده: {used:F3} GB" +
                     (pkg.HasValue ? $" | باقی‌مانده تخمینی: {Math.Max(0, pkg.Value - used):F3} GB" : "");
        purpose = Purpose.None;
        expectedPath = "";
        SetStatus("Statistics واقعی خوانده شد.");
    }

    private async Task PrepareGuestAsync(bool on)
    {
        string json = await ScriptAsync("RouterAdapter.setGuestEnabled(" + (on ? "true" : "false") + ")");
        if (!JsonOk(json))
        {
            purpose = Purpose.None;
            SetStatus("Guest control آماده نشد: " + JsonError(json));
            return;
        }
        purpose = Purpose.VerifyGuestChange;
        expectedPath = GuestPath;
        string save = await ScriptAsync("RouterAdapter.saveGuest()");
        if (!JsonOk(save))
        {
            purpose = Purpose.None;
            SetStatus("SAVE واقعی Guest اجرا نشد.");
            return;
        }
        await Task.Delay(1400);
        if (purpose == Purpose.VerifyGuestChange) web.CoreWebView2.Navigate(BaseUrl + GuestPath);
    }

    private async Task VerifyGuestChangeAsync()
    {
        string json = await ScriptAsync("RouterAdapter.scanGuest()");
        purpose = Purpose.None;
        expectedPath = "";
        SetStatus(JsonOk(json)
            ? "فرم Guest بعد از SAVE دوباره از خود روتر خوانده شد."
            : "فرم Guest بعد از SAVE دوباره خوانده نشد.");
    }

    private void UpdateUi()
    {
        refreshBtn.Enabled = connected && clientsReady;
        allowBtn.Enabled = connected && wirelessReady;
        filterOffBtn.Enabled = connected && wirelessReady;
        statsBtn.Enabled = connected && statsReady;
        guestOnBtn.Enabled = connected && guestReady;
        guestOffBtn.Enabled = connected && guestReady;

        string? selected = SelectedMac();
        managerBtn.Enabled = selected is not null;
        renameBtn.Enabled = selected is not null;
        blockBtn.Enabled = selected is not null && wirelessReady && !string.Equals(selected, protectedMac, StringComparison.OrdinalIgnoreCase);
        unblockBtn.Enabled = selected is not null && wirelessReady;

        capabilities.Text = $"Devices: {(clientsReady ? "✓" : "✗")} | " +
                            $"MAC Filter: {(wirelessReady ? "✓" : "✗")}{(wirelessCapacity > 0 ? $" ({wirelessCapacity})" : "")} | " +
                            $"Statistics: {(statsReady ? "✓" : "✗")} | Guest: {(guestReady ? "✓" : "✗")}";
    }

    private void SetStatus(string text)
    {
        status.Text = text;
        UpdateUi();
    }

    private static bool JsonOk(string json)
    {
        try
        {
            using JsonDocument d = JsonDocument.Parse(json);
            return d.RootElement.TryGetProperty("ok", out var ok) && ok.GetBoolean();
        }
        catch { return false; }
    }

    private static string JsonError(string json)
    {
        try
        {
            using JsonDocument d = JsonDocument.Parse(json);
            return d.RootElement.TryGetProperty("error", out var e) ? e.GetString() ?? "UNKNOWN" : "UNKNOWN";
        }
        catch { return "INVALID_RESPONSE"; }
    }

    private sealed class LocalState
    {
        public string RouterUrl { get; set; } = "http://192.168.1.1";
        public string User { get; set; } = "admin";
        public string ProtectedMac { get; set; } = "";
        public Dictionary<string, string> Aliases { get; set; } = new();
        public long LastRx { get; set; } = -1;
        public long LastTx { get; set; } = -1;
        public long Carried { get; set; } = 0;
    }

    private LocalState LoadStateObject()
    {
        try
        {
            return File.Exists(statePath)
                ? JsonSerializer.Deserialize<LocalState>(File.ReadAllText(statePath)) ?? new LocalState()
                : new LocalState();
        }
        catch { return new LocalState(); }
    }

    private void LoadState()
    {
        LocalState s = LoadStateObject();
        routerUrl.Text = s.RouterUrl;
        username.Text = s.User;
        protectedMac = s.ProtectedMac;
        aliases.Clear();
        foreach (var kv in s.Aliases) aliases[kv.Key] = kv.Value;
    }

    private void SaveState()
    {
        LocalState s = LoadStateObject();
        s.RouterUrl = BaseUrl;
        s.User = username.Text.Trim();
        s.ProtectedMac = protectedMac;
        s.Aliases = new Dictionary<string, string>(aliases);
        SaveStateObject(s);
    }

    private void SaveStateObject(LocalState s)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(statePath)!);
        File.WriteAllText(statePath, JsonSerializer.Serialize(s, new JsonSerializerOptions { WriteIndented = true }));
    }

    private static string? Prompt(string title, string initial)
    {
        using var f = new Form { Text = title, Width = 380, Height = 150, StartPosition = FormStartPosition.CenterParent };
        var t = new TextBox { Left = 15, Top = 15, Width = 335, Text = initial };
        var ok = new Button { Text = "OK", Left = 190, Top = 50, Width = 75, DialogResult = DialogResult.OK };
        var cancel = new Button { Text = "Cancel", Left = 275, Top = 50, Width = 75, DialogResult = DialogResult.Cancel };
        f.Controls.AddRange(new Control[] { t, ok, cancel });
        f.AcceptButton = ok;
        f.CancelButton = cancel;
        return f.ShowDialog() == DialogResult.OK ? t.Text : null;
    }
}
