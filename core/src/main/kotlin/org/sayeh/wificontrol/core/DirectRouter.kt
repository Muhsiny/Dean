package org.sayeh.wificontrol.core

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Direct HTTP controller for the old TrendChip-based TP-Link web UI used by
 * TD-W8961N V4. No WebView/menu clicking is used in the control path.
 * Every mutation is followed by a fresh GET and state verification.
 */
class DirectRouter(
    baseUrl: String,
    private val username: String,
    private val password: String,
    private val protectedMac: String? = null
) {
    data class Client(val mac: String, val ip: String? = null, val row: String = "")
    data class Capabilities(
        val devices: Boolean = false,
        val wirelessMacFilter: Boolean = false,
        val internetMacFilter: Boolean = false,
        val qos: Boolean = false,
        val guest: Boolean = false,
        val guestBandwidth: Boolean = false,
        val statistics: Boolean = false
    )
    data class Snapshot(
        val ok: Boolean,
        val message: String,
        val firmware: String = "",
        val clients: List<Client> = emptyList(),
        val capabilities: Capabilities = Capabilities(),
        val wirelessCapacity: Int = 0
    )
    data class ActionResult(val ok: Boolean, val verified: Boolean, val message: String)
    data class Traffic(val rxBytes: Long, val txBytes: Long)
    data class GuestState(
        val supported: Boolean,
        val enabled: Boolean? = null,
        val ssid: String? = null,
        val upstream: String? = null,
        val downstream: String? = null,
        val bandwidthSupported: Boolean = false
    )

    private data class Page(val doc: Document, val finalUrl: String, val code: Int)
    private data class OptionChoice(val value: String, val text: String, val selected: Boolean)
    private data class Choice(
        val name: String,
        val current: String,
        val options: List<OptionChoice>,
        val rowText: String
    )
    private data class WirelessForm(
        val page: Page,
        val form: Element,
        val active: Choice,
        val action: Choice,
        val macFields: List<String>,
        val macValues: List<String>,
        val onValue: String,
        val offValue: String,
        val allowValue: String,
        val denyValue: String
    )
    private data class AccessForm(
        val page: Page,
        val form: Element,
        val filterType: Choice?,
        val setIndex: Choice?,
        val iface: Choice?,
        val direction: Choice?,
        val ruleIndex: Choice?,
        val ruleType: Choice,
        val active: Choice,
        val macField: String,
        val unmatched: Choice?,
        val macRules: List<String>
    )

    private val rootUrl = baseUrl.trim().ifBlank { "http://192.168.1.1" }.trimEnd('/')
    private val jar = MemoryJar()
    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .writeTimeout(7, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val devicePath = "/status/status_deviceinfo.htm"
    private val wirelessPaths = listOf("/basic/home_wlan.htm", "/basic/home_wlan.html")
    private val accessPaths = listOf("/access/access_ipfilter.htm", "/access/access_filter.htm")
    private val qosPaths = listOf("/advanced/adv_qos.htm", "/advanced/adv_qos.html")
    private val guestPaths = listOf("/basic/home_guest_network.htm", "/basic/home_guest_network.html")
    private val statsPaths = listOf("/status/status_statistics.htm", "/status/status_statistics.html")

    fun connectAndProbe(): Snapshot {
        val auth = authenticate()
        if (!auth.ok) return Snapshot(false, auth.message)
        return try {
            val device = get(devicePath)
            if (looksLikeLogin(device)) return Snapshot(false, "ورود روتر حفظ نشد؛ session معتبر نیست.")
            val clients = parseClients(device.doc)
            val firmware = findFirmware(device.doc)
            val wf = discoverWireless(false)
            val af = discoverAccess(false)
            val qos = firstWorking(qosPaths)?.let { p -> !looksLikeLogin(p) && p.doc.text().contains("QoS", true) } == true
            val guest = discoverGuest(false)
            val stats = firstWorking(statsPaths)
            val traffic = stats?.let { parseTraffic(it.doc) }
            Snapshot(
                ok = true,
                message = "ورود مستقیم تأیید شد؛ ${clients.size} دستگاه از خود روتر خوانده شد.",
                firmware = firmware,
                clients = clients,
                capabilities = Capabilities(
                    devices = true,
                    wirelessMacFilter = wf != null,
                    internetMacFilter = af != null,
                    qos = qos,
                    guest = guest?.supported == true,
                    guestBandwidth = guest?.bandwidthSupported == true,
                    statistics = traffic != null
                ),
                wirelessCapacity = wf?.macFields?.size ?: 0
            )
        } catch (e: Exception) {
            Snapshot(false, humanError("خواندن روتر", e))
        }
    }

    fun clients(): List<Client> {
        requireSession()
        return parseClients(get(devicePath).doc)
    }

    fun blockWifi(macRaw: String): ActionResult = mutateWireless(macRaw, true)
    fun unblockWifi(macRaw: String): ActionResult = mutateWireless(macRaw, false)

    private fun mutateWireless(macRaw: String, block: Boolean): ActionResult {
        val mac = normalizeMac(macRaw)
        if (!validMac(mac)) return ActionResult(false, false, "MAC نامعتبر است.")
        if (block && normalizeMac(protectedMac.orEmpty()) == mac) return ActionResult(false, false, "دستگاه مدیر محافظت‌شده است.")
        return try {
            requireSession()
            val w = discoverWireless(true) ?: return ActionResult(false, false, "فرم واقعی Wireless MAC Filter پیدا نشد.")
            val values = w.macValues.map(::normalizeMac).toMutableList()
            val currentModeAllow = w.action.current == w.allowValue
            val currentModeDeny = w.action.current == w.denyValue
            val overrides = linkedMapOf<String, String>()
            overrides[w.active.name] = w.onValue

            if (currentModeAllow) {
                overrides[w.action.name] = w.allowValue
                if (block) {
                    var changed = false
                    for (i in values.indices) if (values[i] == mac) { values[i] = "00:00:00:00:00:00"; changed = true }
                    if (!changed) return ActionResult(true, true, "$mac از قبل خارج Allow-List است.")
                } else {
                    if (mac in values) return ActionResult(true, true, "$mac از قبل مجاز است.")
                    val idx = values.indexOfFirst { it.isBlank() || it == "00:00:00:00:00:00" }
                    if (idx < 0) return ActionResult(false, false, "خانه خالی در MAC Filter وجود ندارد.")
                    values[idx] = mac
                }
            } else {
                // Disabled/unknown/deny -> use explicit Deny Association for individual blocking.
                overrides[w.action.name] = w.denyValue
                if (block) {
                    if (mac in values && currentModeDeny) return ActionResult(true, true, "$mac از قبل در Deny Association است.")
                    val idx = values.indexOfFirst { it.isBlank() || it == "00:00:00:00:00:00" }
                    if (idx < 0) return ActionResult(false, false, "خانه خالی در MAC Filter وجود ندارد.")
                    values[idx] = mac
                } else {
                    var changed = false
                    for (i in values.indices) if (values[i] == mac) { values[i] = "00:00:00:00:00:00"; changed = true }
                    if (!changed) return ActionResult(true, true, "$mac در Deny Association نیست.")
                }
            }
            w.macFields.forEachIndexed { i, name -> overrides[name] = values.getOrElse(i) { "00:00:00:00:00:00" } }
            submit(w.page, w.form, overrides)
            val after = discoverWireless(true) ?: return ActionResult(false, false, "SAVE ارسال شد ولی صفحه برای Verify بازخوانی نشد.")
            val blocked = isBlocked(after, mac)
            if (blocked == block) ActionResult(true, true, if (block) "$mac روی خود روتر Block و Verify شد." else "$mac روی خود روتر Unblock و Verify شد.")
            else ActionResult(false, false, "SAVE ارسال شد اما نتیجه از خود روتر Verify نشد.")
        } catch (e: Exception) {
            ActionResult(false, false, humanError("فرمان Wireless", e))
        }
    }

    fun setAllowList(macsRaw: Collection<String>): ActionResult {
        return try {
            requireSession()
            val w = discoverWireless(true) ?: return ActionResult(false, false, "فرم واقعی Wireless MAC Filter پیدا نشد.")
            val manager = normalizeMac(protectedMac.orEmpty())
            val wanted = macsRaw.map(::normalizeMac).filter(::validMac).toMutableSet()
            if (validMac(manager)) wanted.add(manager)
            if (wanted.isEmpty()) return ActionResult(false, false, "Allow-List خالی است؛ برای جلوگیری از قطع مدیر اجرا نشد.")
            if (wanted.size > w.macFields.size) return ActionResult(false, false, "ظرفیت MAC Filter ${w.macFields.size} دستگاه است.")
            val list = wanted.toList()
            val overrides = linkedMapOf(w.active.name to w.onValue, w.action.name to w.allowValue)
            w.macFields.forEachIndexed { i, field -> overrides[field] = list.getOrNull(i) ?: "00:00:00:00:00:00" }
            submit(w.page, w.form, overrides)
            val after = discoverWireless(true) ?: return ActionResult(false, false, "Allow-List ذخیره شد اما Verify ممکن نشد.")
            val afterSet = after.macValues.map(::normalizeMac).filter(::validMac).toSet()
            val ok = after.active.current == after.onValue && after.action.current == after.allowValue && wanted.all { it in afterSet }
            if (ok) ActionResult(true, true, "ضد QR واقعی روی Allow Association فعال و Verify شد.")
            else ActionResult(false, false, "Allow-List از خود روتر Verify نشد.")
        } catch (e: Exception) {
            ActionResult(false, false, humanError("Allow-List", e))
        }
    }

    fun disableWirelessFilter(): ActionResult {
        return try {
            requireSession()
            val w = discoverWireless(true) ?: return ActionResult(false, false, "فرم واقعی Wireless MAC Filter پیدا نشد.")
            submit(w.page, w.form, mapOf(w.active.name to w.offValue))
            val after = discoverWireless(true) ?: return ActionResult(false, false, "خاموش‌کردن Filter قابل Verify نبود.")
            if (after.active.current == after.offValue) ActionResult(true, true, "Wireless MAC Filter خاموش و Verify شد.")
            else ActionResult(false, false, "Filter بعد از SAVE هنوز فعال است.")
        } catch (e: Exception) {
            ActionResult(false, false, humanError("خاموش‌کردن Filter", e))
        }
    }

    /** Internet-only block through Access Management -> IP/MAC Filter when discoverable. */
    fun blockInternet(macRaw: String): ActionResult = mutateInternet(macRaw, true)
    fun unblockInternet(macRaw: String): ActionResult = mutateInternet(macRaw, false)

    private fun mutateInternet(macRaw: String, block: Boolean): ActionResult {
        val mac = normalizeMac(macRaw)
        if (!validMac(mac)) return ActionResult(false, false, "MAC نامعتبر است.")
        if (block && normalizeMac(protectedMac.orEmpty()) == mac) return ActionResult(false, false, "دستگاه مدیر محافظت‌شده است.")
        return try {
            requireSession()
            val a = discoverAccess(true) ?: return ActionResult(false, false, "MAC Filter اینترنت در firmware قابل کنترل پیدا نشد.")
            val existing = a.macRules.any { normalizeMac(it) == mac }
            if (block && existing) return ActionResult(true, true, "$mac از قبل در فهرست MAC اینترنت است.")
            if (!block && !existing) return ActionResult(true, true, "$mac در فهرست Block اینترنت نیست.")

            val overrides = linkedMapOf<String, String>()
            a.filterType?.let { c -> choose(c, listOf("ip / mac", "ip/mac"))?.let { overrides[c.name] = it } }
            choose(a.ruleType, listOf("mac"))?.let { overrides[a.ruleType.name] = it }
                ?: return ActionResult(false, false, "Rule Type=MAC پیدا نشد.")
            choose(a.active, if (block) listOf("yes", "activated", "enable") else listOf("no", "deactivated", "disable"))?.let { overrides[a.active.name] = it }
                ?: return ActionResult(false, false, "Active Yes/No پیدا نشد.")
            a.direction?.let { c -> choose(c, listOf("outgoing", "both"))?.let { overrides[c.name] = it } }
            a.iface?.let { c -> choose(c, listOf("pvc0", "internet", "wan"))?.let { overrides[c.name] = it } }
            a.unmatched?.let { c -> choose(c, listOf("next"))?.let { overrides[c.name] = it } }
            overrides[a.macField] = mac
            // Use the first visible rule slot when no existing row can be selected reliably.
            a.setIndex?.let { c -> firstUseful(c)?.let { overrides[c.name] = it } }
            a.ruleIndex?.let { c -> firstUseful(c)?.let { overrides[c.name] = it } }
            submit(a.page, a.form, overrides)
            val after = discoverAccess(true) ?: return ActionResult(false, false, "SAVE انجام شد اما Access Filter بازخوانی نشد.")
            val now = after.macRules.any { normalizeMac(it) == mac }
            if (now == block) ActionResult(true, true, if (block) "قطع اینترنت MAC روی Access Management ثبت و Verify شد." else "فیلتر اینترنت MAC برداشته و Verify شد.")
            else ActionResult(false, false, "نتیجه Access Management بعد از SAVE Verify نشد.")
        } catch (e: Exception) {
            ActionResult(false, false, humanError("فیلتر اینترنت", e))
        }
    }

    fun traffic(): Traffic? {
        requireSession()
        val p = firstWorking(statsPaths) ?: return null
        return parseTraffic(p.doc)
    }

    fun guestState(): GuestState {
        requireSession()
        return discoverGuest(true) ?: GuestState(false)
    }

    private fun authenticate(): ActionResult {
        if (username.isBlank() || password.isBlank()) return ActionResult(false, false, "نام کاربری یا رمز خالی است.")
        return try {
            val root = get("/")
            if (looksLikeLogin(root)) {
                val loginForm = root.doc.select("form").firstOrNull { f -> f.selectFirst("input[type=password]") != null }
                    ?: return ActionResult(false, false, "صفحه Login باز شد اما فرم ورود پیدا نشد.")
                val userInput = loginForm.select("input").firstOrNull { e ->
                    val t = e.attr("type").lowercase(Locale.US)
                    val m = (e.attr("name") + " " + e.id()).lowercase(Locale.US)
                    (t == "text" || t.isBlank()) && (m.contains("user") || m.contains("login"))
                } ?: loginForm.selectFirst("input[type=text]")
                    ?: return ActionResult(false, false, "فیلد Username پیدا نشد.")
                val passInput = loginForm.selectFirst("input[type=password]")
                    ?: return ActionResult(false, false, "فیلد Password پیدا نشد.")
                val overrides = linkedMapOf(userInput.attr("name") to username, passInput.attr("name") to password)
                submit(root, loginForm, overrides)
            }
            val verify = get(devicePath)
            if (looksLikeLogin(verify)) ActionResult(false, false, "رمز/نام کاربری پذیرفته نشد؛ روتر دوباره Login خواست.")
            else ActionResult(true, true, "ورود مستقیم به روتر تأیید شد.")
        } catch (e: Exception) {
            ActionResult(false, false, humanError("ورود", e))
        }
    }

    private fun requireSession() {
        val p = get(devicePath)
        if (looksLikeLogin(p)) {
            val a = authenticate()
            if (!a.ok) throw IllegalStateException(a.message)
        }
    }

    private fun discoverWireless(require: Boolean): WirelessForm? {
        val page = firstWorking(wirelessPaths) ?: return null
        if (looksLikeLogin(page)) return null
        val forms = page.doc.select("form")
        var best: Element? = null
        var bestScore = -1
        for (f in forms) {
            val text = f.text().lowercase(Locale.US)
            val action = f.attr("action").lowercase(Locale.US)
            var score = 0
            if (text.contains("wireless mac address filter")) score += 500
            if (action.contains("home_wlan_1")) score += 250
            score += f.select("input").count { validMac(normalizeMac(it.attr("value"))) || it.closest("tr")?.text()?.contains("Mac Address", true) == true } * 20
            if (score > bestScore) { bestScore = score; best = f }
        }
        val form = best ?: return null
        if (bestScore < 200 && require) return null
        val rows = form.select("tr")
        val start = rows.indexOfFirst { it.text().contains("Wireless MAC Address Filter", true) }
        val section = if (start >= 0) rows.drop(start) else rows
        val activeRow = section.firstOrNull { r -> r.text().contains("Active", true) && r.select("input,select").isNotEmpty() }
        val actionRow = section.firstOrNull { r -> r.text().contains("Action", true) && r.select("input,select").isNotEmpty() }
        val active = activeRow?.let(::choiceInRow) ?: return null
        val action = actionRow?.let(::choiceInRow) ?: return null
        val macInputs = section.flatMap { it.select("input[name]") }
            .filter { e -> e.closest("tr")?.text()?.contains("Mac Address", true) == true || validMac(normalizeMac(e.attr("value"))) }
            .distinctBy { it.attr("name") }
        if (macInputs.isEmpty()) return null
        val on = choose(active, listOf("activated", "enabled", "yes", "on")) ?: inferBinary(active, true)
        val off = choose(active, listOf("deactivated", "disabled", "no", "off")) ?: inferBinary(active, false)
        val allow = choose(action, listOf("allow association", "allow")) ?: inferAssociation(action, true)
        val deny = choose(action, listOf("deny association", "deny")) ?: inferAssociation(action, false)
        if (on == null || off == null || allow == null || deny == null) return null
        return WirelessForm(page, form, active, action, macInputs.map { it.attr("name") }, macInputs.map { it.attr("value") }, on, off, allow, deny)
    }

    private fun discoverAccess(require: Boolean): AccessForm? {
        val page = firstWorking(accessPaths) ?: return null
        if (looksLikeLogin(page)) return null
        var best: Element? = null
        var scoreBest = -1
        for (f in page.doc.select("form")) {
            val text = f.text().lowercase(Locale.US)
            var s = 0
            if (text.contains("ip / mac filter") || text.contains("ip/mac filter")) s += 250
            if (text.contains("filter set index")) s += 80
            if (text.contains("rule type")) s += 100
            if (text.contains("mac address")) s += 100
            if (s > scoreBest) { scoreBest = s; best = f }
        }
        val f = best ?: return null
        if (require && scoreBest < 250) return null
        fun rowContains(vararg terms: String): Element? = f.select("tr").firstOrNull { row -> terms.any { row.text().contains(it, true) } && row.select("input,select").isNotEmpty() }
        val ruleType = rowContains("Rule Type")?.let(::choiceInRow) ?: return null
        val active = rowContains("Active")?.let(::choiceInRow) ?: return null
        val macRow = f.select("tr").firstOrNull { it.text().contains("MAC Address", true) && it.select("input[name]").isNotEmpty() } ?: return null
        val macField = macRow.select("input[name]").firstOrNull { e -> e.attr("type").lowercase(Locale.US) !in setOf("radio", "checkbox", "submit", "button") }?.attr("name") ?: return null
        val filterType = rowContains("Filter Type Selection", "Filter Type")?.let(::choiceInRow)
        val setIdx = rowContains("Filter Set Index")?.let(::choiceInRow)
        val iface = rowContains("Interface")?.let(::choiceInRow)
        val direction = rowContains("Direction")?.let(::choiceInRow)
        val ruleIdx = rowContains("Filter Rule Index", "Rule Index")?.let(::choiceInRow)
        val unmatchedRows = f.select("tr").filter { it.text().contains("Rule Unmatched", true) && it.select("input,select").isNotEmpty() }
        val unmatched = unmatchedRows.lastOrNull()?.let(::choiceInRow)
        val macRegex = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")
        val rules = macRegex.findAll(page.doc.text()).map { normalizeMac(it.value) }.filter(::validMac).distinct().toList()
        return AccessForm(page, f, filterType, setIdx, iface, direction, ruleIdx, ruleType, active, macField, unmatched, rules)
    }

    private fun discoverGuest(require: Boolean): GuestState? {
        val p = firstWorking(guestPaths) ?: return null
        if (looksLikeLogin(p)) return null
        val text = p.doc.text()
        if (!text.contains("Guest", true) && require) return null
        val form = p.doc.select("form").maxByOrNull { f -> if (f.text().contains("Guest", true)) f.text().length else -1 }
        val inputs = form?.select("input[name],select[name]").orEmpty()
        val ssid = inputs.firstOrNull { e -> (e.attr("name") + " " + e.id() + " " + (e.closest("tr")?.text() ?: "")).contains("ssid", true) }?.attr("value")
        val up = inputs.firstOrNull { e -> (e.attr("name") + " " + (e.closest("tr")?.text() ?: "")).contains("upstream", true) }?.attr("value")
        val down = inputs.firstOrNull { e -> (e.attr("name") + " " + (e.closest("tr")?.text() ?: "")).contains("downstream", true) }?.attr("value")
        val enabledRow = form?.select("tr")?.firstOrNull { it.text().contains("Guest Network", true) && it.select("input,select").isNotEmpty() }
        val enabled = enabledRow?.let(::choiceInRow)?.let { c ->
            val on = choose(c, listOf("activated", "enabled", "yes", "on")) ?: inferBinary(c, true)
            on?.let { c.current == it }
        }
        return GuestState(true, enabled, ssid, up, down, up != null || down != null)
    }

    private fun parseClients(doc: Document): List<Client> {
        val macRegex = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")
        val ipRegex = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
        val router = Regex("(?i)MAC\\s*Address\\s*[:：]?\\s*((?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2})").find(doc.text())?.groupValues?.getOrNull(1)?.let(::normalizeMac)
        var best: Element? = null
        var scoreBest = -1
        for (t in doc.select("table")) {
            val ms = macRegex.findAll(t.text()).count()
            if (ms == 0) continue
            var near = t.text()
            var p: Element? = t.parent()
            repeat(3) { if (p != null) { near += " " + p!!.ownText(); p = p!!.parent() } }
            var s = ms * 20
            if (near.contains("Current Connected Wireless Clients", true)) s += 500
            if (near.contains("Wireless", true)) s += 50
            if (s > scoreBest) { scoreBest = s; best = t }
        }
        val out = linkedMapOf<String, Client>()
        best?.select("tr")?.forEach { row ->
            val text = row.text()
            val mac = macRegex.find(text)?.value?.let(::normalizeMac) ?: return@forEach
            if (!validMac(mac) || mac == router || mac == "78:8C:B5:DD:8E:F0") return@forEach
            val ip = ipRegex.findAll(text).map { it.value }.firstOrNull { it != "255.255.255.0" && it != "0.0.0.0" }
            out[mac] = Client(mac, ip, text)
        }
        return out.values.toList()
    }

    private fun parseTraffic(doc: Document): Traffic? {
        val text = doc.text()
        fun num(vararg labels: String): Long? {
            for (label in labels) {
                val m = Regex("(?i)${Regex.escape(label)}\\s*[:：]?\\s*([0-9,]+)").find(text)
                if (m != null) return m.groupValues[1].replace(",", "").toLongOrNull()
            }
            return null
        }
        val rx = num("Receive total Bytes", "Receive Bytes", "Rx Bytes") ?: return null
        val tx = num("Transmit total Bytes", "Transmit Bytes", "Tx Bytes") ?: return null
        return Traffic(rx, tx)
    }

    private fun isBlocked(w: WirelessForm, mac: String): Boolean {
        if (w.active.current != w.onValue) return false
        val set = w.macValues.map(::normalizeMac).toSet()
        return if (w.action.current == w.allowValue) mac !in set else if (w.action.current == w.denyValue) mac in set else false
    }

    private fun choiceInRow(row: Element): Choice? {
        val selects = row.select("select[name]")
        if (selects.isNotEmpty()) {
            val s = selects.first()!!
            val opts = s.select("option").map { OptionChoice(it.attr("value"), it.text().trim(), it.hasAttr("selected")) }
            val current = opts.firstOrNull { it.selected }?.value ?: s.attr("value").ifBlank { opts.firstOrNull()?.value.orEmpty() }
            return Choice(s.attr("name"), current, opts, row.text())
        }
        val radios = row.select("input[type=radio][name]")
        if (radios.isNotEmpty()) {
            val name = radios.first()!!.attr("name")
            val opts = radios.mapIndexed { i, e -> OptionChoice(e.attr("value"), nearbyLabel(e, i, row), e.hasAttr("checked")) }
            val current = opts.firstOrNull { it.selected }?.value ?: opts.firstOrNull()?.value.orEmpty()
            return Choice(name, current, opts, row.text())
        }
        return null
    }

    private fun nearbyLabel(e: Element, index: Int, row: Element): String {
        if (e.id().isNotBlank()) {
            val l = row.selectFirst("label[for=${e.id()}]")
            if (l != null && l.text().isNotBlank()) return l.text().trim()
        }
        val parent = e.parent()
        if (parent != null) {
            val nodes = parent.childNodes()
            val pos = nodes.indexOf(e)
            val parts = mutableListOf<String>()
            for (j in (pos + 1) until minOf(nodes.size, pos + 4)) {
                val n = nodes[j]
                if (n is Element && n.tagName().equals("input", true)) break
                if (n is TextNode && n.text().isNotBlank()) parts.add(n.text().trim())
                if (n is Element && n.text().isNotBlank()) parts.add(n.text().trim())
            }
            if (parts.isNotEmpty()) return parts.joinToString(" ")
        }
        // Stable fallback: labels appear in DOM order on this legacy UI.
        val t = row.text()
        return "#$index $t"
    }

    private fun choose(c: Choice, wants: List<String>): String? {
        for (want in wants) {
            val w = want.lowercase(Locale.US)
            c.options.firstOrNull { (it.text + " " + it.value).lowercase(Locale.US).contains(w) }?.let { return it.value }
        }
        return null
    }

    private fun inferBinary(c: Choice, on: Boolean): String? {
        if (c.options.size < 2) return null
        val vals = c.options.map { it.value.lowercase(Locale.US) }
        val direct = if (on) listOf("1", "on", "yes", "enable", "enabled", "activated") else listOf("0", "off", "no", "disable", "disabled", "deactivated")
        for (d in direct) {
            val i = vals.indexOf(d)
            if (i >= 0) return c.options[i].value
        }
        val row = c.rowText.lowercase(Locale.US)
        if (row.contains("activated") && row.contains("deactivated")) return if (on) c.options.first().value else c.options[1].value
        return if (on) c.options.first().value else c.options[1].value
    }

    private fun inferAssociation(c: Choice, allow: Boolean): String? {
        if (c.options.size < 2) return null
        val row = c.rowText.lowercase(Locale.US)
        if (row.contains("allow") || row.contains("deny")) return if (allow) c.options.first().value else c.options[1].value
        return null
    }

    private fun firstUseful(c: Choice): String? = c.options.firstOrNull { it.value.isNotBlank() && !it.text.contains("select", true) }?.value

    private fun submit(page: Page, form: Element, overrides: Map<String, String>): Page {
        val values = collectForm(form)
        values.putAll(overrides.filterKeys { it.isNotBlank() })
        val save = form.select("input[type=submit],button[type=submit],input[type=button],button").firstOrNull { b ->
            val t = (b.attr("value") + " " + b.text()).trim()
            t.equals("SAVE", true) || t.equals("Apply", true) || t.contains("save", true)
        }
        if (save != null && save.attr("name").isNotBlank()) values[save.attr("name")] = save.attr("value").ifBlank { save.text() }
        val action = form.absUrl("action").ifBlank { resolve(page.finalUrl, form.attr("action")) }
        val method = form.attr("method").ifBlank { "post" }.lowercase(Locale.US)
        val builder = FormBody.Builder()
        values.forEach { (k, v) -> builder.add(k, v) }
        val req = if (method == "get") {
            val u = okhttp3.HttpUrl.parse(action)?.newBuilder() ?: throw IllegalStateException("URL فرم نامعتبر است")
            values.forEach { (k, v) -> u.addQueryParameter(k, v) }
            Request.Builder().url(u.build()).get().build()
        } else Request.Builder().url(action).post(builder.build()).build()
        return executePage(req)
    }

    private fun collectForm(form: Element): LinkedHashMap<String, String> {
        val out = linkedMapOf<String, String>()
        for (el in form.select("input[name],select[name],textarea[name]")) {
            val name = el.attr("name")
            if (name.isBlank()) continue
            when (el.tagName().lowercase(Locale.US)) {
                "select" -> {
                    val opt = el.select("option").firstOrNull { it.hasAttr("selected") } ?: el.selectFirst("option")
                    if (opt != null) out[name] = opt.attr("value")
                }
                "textarea" -> out[name] = el.text()
                else -> {
                    val type = el.attr("type").lowercase(Locale.US)
                    if (type in setOf("submit", "button", "reset", "file", "image")) continue
                    if (type in setOf("radio", "checkbox") && !el.hasAttr("checked")) continue
                    out[name] = el.attr("value")
                }
            }
        }
        return out
    }

    private fun get(pathOrUrl: String): Page = executePage(Request.Builder().url(resolve(rootUrl, pathOrUrl)).get().build())

    private fun firstWorking(paths: List<String>): Page? {
        for (path in paths) {
            try {
                val p = get(path)
                if (p.code in 200..399 && !looksLikeNotFound(p.doc)) return p
            } catch (_: Exception) { }
        }
        return null
    }

    private fun executePage(req: Request): Page {
        http.newCall(req).execute().use { r ->
            val body = r.body()?.string().orEmpty()
            if (r.code() !in 200..399) throw IllegalStateException("HTTP ${r.code()}")
            val url = r.request().url().toString()
            return Page(Jsoup.parse(body, url), url, r.code())
        }
    }

    private fun looksLikeLogin(p: Page): Boolean {
        val url = p.finalUrl.lowercase(Locale.US)
        if (url.contains("login_security")) return true
        val d = p.doc
        if (d.selectFirst("input[type=password]") != null && d.select("form").any { it.text().contains("login", true) || it.attr("action").contains("login", true) }) return true
        val t = d.text().lowercase(Locale.US)
        return t.contains("username") && t.contains("password") && t.contains("login") && d.select("input").size < 20
    }

    private fun looksLikeNotFound(d: Document): Boolean {
        val t = d.text().lowercase(Locale.US)
        return t.contains("404 not found") || t.contains("file not found")
    }

    private fun findFirmware(d: Document): String = Regex("(?i)Firmware\\s*Version\\s*[:：]?\\s*([^\\n\\r]+)").find(d.text())?.groupValues?.getOrNull(1)?.trim().orEmpty()

    private fun resolve(base: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return try { URI(base).resolve(path.ifBlank { "/" }).toString() } catch (_: Exception) { rootUrl + if (path.startsWith('/')) path else "/$path" }
    }

    private fun normalizeMac(v: String): String = v.trim().replace('-', ':').uppercase(Locale.US)
    private fun validMac(v: String): Boolean = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(normalizeMac(v))

    private fun humanError(prefix: String, e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("timeout", true) -> "$prefix ناموفق: روتر تا ۱۲ ثانیه پاسخ نداد."
            m.contains("Failed to connect", true) || m.contains("connect", true) -> "$prefix ناموفق: اتصال به 192.168.1.1 برقرار نشد."
            else -> "$prefix ناموفق: ${m.ifBlank { e.javaClass.simpleName }}"
        }
    }

    private class MemoryJar : CookieJar {
        private val map = linkedMapOf<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val l = map.getOrPut(url.host()) { mutableListOf() }
            for (c in cookies) { l.removeAll { it.name() == c.name() }; l.add(c) }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = map[url.host()]?.filter { it.matches(url) } ?: emptyList()
    }
}
