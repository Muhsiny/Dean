package org.sayeh.wificontrol.core

import com.google.gson.GsonBuilder
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

data class LoginSpec(
    val pageUrl: String = "/login_security.html",
    val formSelector: String = "form",
    val usernameField: String = "Username",
    val passwordField: String = "Password",
    val submitField: String? = null,
    val submitValue: String? = null,
    val successMarkers: List<String> = listOf("Status", "Interface Setup")
)

data class ClientTableSpec(
    val pageUrl: String,
    val headingMarkers: List<String> = listOf("Current Connected Wireless Clients")
)

data class MacFilterSpec(
    val pageUrl: String,
    val formSelector: String = "form",
    val activeField: String,
    val activeOnValue: String,
    val activeOffValue: String,
    val actionField: String,
    val allowValue: String,
    val denyValue: String,
    val macFields: List<String>,
    val submitField: String? = null,
    val submitValue: String? = null
)

data class StatsSpec(
    val pageUrl: String,
    val rxLabels: List<String> = listOf("Receive total Bytes", "Rx Bytes", "Receive Bytes"),
    val txLabels: List<String> = listOf("Transmit total Bytes", "Tx Bytes", "Transmit Bytes")
)

data class RouterProfile(
    val profileVersion: Int = 1,
    val vendor: String = "TP-Link",
    val model: String = "TD-W8961N",
    val hardwareVersion: String = "V4",
    val firmwareVersion: String = "",
    val login: LoginSpec,
    val clients: ClientTableSpec,
    val macFilter: MacFilterSpec? = null,
    val stats: StatsSpec? = null
)

data class RouterClient(val mac: String, val ip: String? = null, val name: String? = null)
data class TrafficCounters(val rxBytes: Long, val txBytes: Long, val capturedAtMillis: Long = System.currentTimeMillis())
data class CommandResult(val ok: Boolean, val verified: Boolean, val message: String)

data class UsageLedger(
    var packageBytes: Long = 0,
    var carriedRxBytes: Long = 0,
    var carriedTxBytes: Long = 0,
    var lastRxBytes: Long = -1,
    var lastTxBytes: Long = -1
) {
    fun ingest(now: TrafficCounters) {
        if (lastRxBytes >= 0) carriedRxBytes += if (now.rxBytes >= lastRxBytes) now.rxBytes - lastRxBytes else now.rxBytes
        if (lastTxBytes >= 0) carriedTxBytes += if (now.txBytes >= lastTxBytes) now.txBytes - lastTxBytes else now.txBytes
        lastRxBytes = now.rxBytes
        lastTxBytes = now.txBytes
    }

    fun usedBytes(): Long = carriedRxBytes + carriedTxBytes
    fun remainingBytes(): Long? = if (packageBytes > 0) (packageBytes - usedBytes()).coerceAtLeast(0) else null
}

object RouterProfileCodec {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    fun toJson(profile: RouterProfile): String = gson.toJson(profile)
    fun fromJson(json: String): RouterProfile = gson.fromJson(json, RouterProfile::class.java)
    fun read(file: File): RouterProfile = fromJson(file.readText())
    fun write(file: File, profile: RouterProfile) = file.writeText(toJson(profile))
}

class MemoryCookieJar : CookieJar {
    private val store = linkedMapOf<String, MutableList<Cookie>>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.apply {
            cookies.forEach { incoming -> removeAll { it.name == incoming.name }; add(incoming) }
        }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host]?.filter { it.matches(url) } ?: emptyList()
}

class RouterHttpEngine(
    private val baseUrl: String,
    private val profile: RouterProfile,
    private val username: String,
    private val password: String
) {
    private val cookies = MemoryCookieJar()
    private val http = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun login(): CommandResult {
        return try {
            val loginDoc = getDoc(profile.login.pageUrl)
            val form = selectForm(loginDoc, profile.login.formSelector)
                ?: return CommandResult(false, false, "فرم ورود firmware پیدا نشد.")
            val usernameName = resolveFieldName(form, profile.login.usernameField, "text")
                ?: return CommandResult(false, false, "فیلد Username در فرم ورود پیدا نشد.")
            val passwordName = resolveFieldName(form, profile.login.passwordField, "password")
                ?: return CommandResult(false, false, "فیلد Password در فرم ورود پیدا نشد.")
            val overrides = linkedMapOf(usernameName to username, passwordName to password)
            profile.login.submitField?.let { overrides[it] = profile.login.submitValue.orEmpty() }
            submitForm(loginDoc, form, overrides)
            val root = getDoc("/")
            val text = root.text().lowercase(Locale.US)
            val ok = profile.login.successMarkers.any { text.contains(it.lowercase(Locale.US)) } || !looksLikeLogin(root)
            if (ok) CommandResult(true, true, "ورود واقعی به روتر تأیید شد.")
            else CommandResult(false, false, "روتر دوباره صفحه ورود را برگرداند؛ احراز هویت تأیید نشد.")
        } catch (e: Exception) {
            CommandResult(false, false, "ورود ناموفق: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun clients(): List<RouterClient> {
        val doc = getDoc(profile.clients.pageUrl)
        val macRegex = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")
        val ipRegex = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
        var best: Element? = null
        var bestScore = -1
        doc.select("table").forEach { table ->
            val text = table.text()
            val macCount = macRegex.findAll(text).count()
            if (macCount == 0) return@forEach
            val parentText = generateSequence(table.parent()) { it.parent() }.take(4).joinToString(" ") { it.text() }
            var score = macCount * 10
            if (profile.clients.headingMarkers.any { parentText.contains(it, ignoreCase = true) }) score += 100
            if (score > bestScore) { best = table; bestScore = score }
        }
        val result = linkedMapOf<String, RouterClient>()
        best?.select("tr")?.forEach { row ->
            val text = row.text()
            val mac = macRegex.find(text)?.value?.replace('-', ':')?.uppercase(Locale.US) ?: return@forEach
            val ip = ipRegex.findAll(text).map { it.value }.firstOrNull { it != "255.255.255.0" }
            result[mac] = RouterClient(mac, ip)
        }
        return result.values.toList()
    }

    fun block(mac: String): CommandResult = mutateDeny(mac, add = true)
    fun unblock(mac: String): CommandResult = mutateDeny(mac, add = false)

    private fun mutateDeny(macRaw: String, add: Boolean): CommandResult {
        val spec = profile.macFilter ?: return CommandResult(false, false, "این پروفایل MAC Filter واقعی ندارد.")
        val mac = normalizeMac(macRaw)
        return try {
            val doc = getDoc(spec.pageUrl)
            val form = selectForm(doc, spec.formSelector) ?: return CommandResult(false, false, "فرم MAC Filter پیدا نشد.")
            val current = spec.macFields.map { field -> field to inputValue(form, field) }.toMutableList()
            val normalized = current.map { normalizeMac(it.second) }
            if (add && normalized.contains(mac)) return CommandResult(true, true, "$mac از قبل در Deny Association ثبت است.")
            if (!add && !normalized.contains(mac)) return CommandResult(true, true, "$mac در Deny Association وجود ندارد.")

            val overrides = linkedMapOf<String, String>()
            overrides[spec.activeField] = spec.activeOnValue
            overrides[spec.actionField] = spec.denyValue
            current.forEach { overrides[it.first] = it.second }
            if (add) {
                val slot = current.firstOrNull { normalizeMac(it.second).isBlank() || normalizeMac(it.second) == "00:00:00:00:00:00" }
                    ?: return CommandResult(false, false, "خانه خالی در MAC Filter وجود ندارد.")
                overrides[slot.first] = mac
            } else {
                current.firstOrNull { normalizeMac(it.second) == mac }?.let { overrides[it.first] = "00:00:00:00:00:00" }
            }
            spec.submitField?.let { overrides[it] = spec.submitValue.orEmpty() }
            submitForm(doc, form, overrides)
            val verified = verifyDeny(mac, shouldExist = add)
            if (verified) CommandResult(true, true, if (add) "$mac واقعاً Block و Verify شد." else "$mac واقعاً Unblock و Verify شد.")
            else CommandResult(false, false, "SAVE ارسال شد اما نتیجه از خود روتر Verify نشد.")
        } catch (e: Exception) {
            CommandResult(false, false, "فرمان MAC Filter شکست خورد: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun setAllowList(macs: Collection<String>): CommandResult {
        val spec = profile.macFilter ?: return CommandResult(false, false, "این پروفایل MAC Filter واقعی ندارد.")
        val wanted = macs.map(::normalizeMac).filter { it.isNotBlank() }.distinct()
        if (wanted.size > spec.macFields.size) return CommandResult(false, false, "ظرفیت MAC Filter ${spec.macFields.size} دستگاه است؛ ${wanted.size} دستگاه انتخاب شده.")
        return try {
            val doc = getDoc(spec.pageUrl)
            val form = selectForm(doc, spec.formSelector) ?: return CommandResult(false, false, "فرم MAC Filter پیدا نشد.")
            val overrides = linkedMapOf<String, String>()
            overrides[spec.activeField] = spec.activeOnValue
            overrides[spec.actionField] = spec.allowValue
            spec.macFields.forEachIndexed { index, field -> overrides[field] = wanted.getOrNull(index) ?: "00:00:00:00:00:00" }
            spec.submitField?.let { overrides[it] = spec.submitValue.orEmpty() }
            submitForm(doc, form, overrides)
            val state = readMacFilterState()
            val verified = state.first && state.second.equals(spec.allowValue, ignoreCase = true) && wanted.all { it in state.third }
            if (verified) CommandResult(true, true, "Allow‑List واقعی فعال و Verify شد.") else CommandResult(false, false, "Allow‑List ذخیره شد اما Verify کامل نشد.")
        } catch (e: Exception) {
            CommandResult(false, false, "فعال‌سازی Allow‑List شکست خورد: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun disableMacFilter(): CommandResult {
        val spec = profile.macFilter ?: return CommandResult(false, false, "این پروفایل MAC Filter واقعی ندارد.")
        return try {
            val doc = getDoc(spec.pageUrl)
            val form = selectForm(doc, spec.formSelector) ?: return CommandResult(false, false, "فرم MAC Filter پیدا نشد.")
            val overrides = linkedMapOf(spec.activeField to spec.activeOffValue)
            spec.submitField?.let { overrides[it] = spec.submitValue.orEmpty() }
            submitForm(doc, form, overrides)
            val verified = !readMacFilterState().first
            if (verified) CommandResult(true, true, "MAC Filter واقعاً خاموش و Verify شد.") else CommandResult(false, false, "خاموش‌کردن Filter از روتر Verify نشد.")
        } catch (e: Exception) {
            CommandResult(false, false, "خاموش‌کردن Filter شکست خورد: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun trafficCounters(): TrafficCounters? {
        val spec = profile.stats ?: return null
        val doc = getDoc(spec.pageUrl)
        val text = doc.text()
        fun find(labels: List<String>): Long? {
            labels.forEach { label ->
                val rx = Regex("(?i)${Regex.escape(label)}\\s*[:：]?\\s*([0-9,]+)")
                val m = rx.find(text) ?: return@forEach
                return m.groupValues[1].replace(",", "").toLongOrNull()
            }
            return null
        }
        val rx = find(spec.rxLabels) ?: return null
        val tx = find(spec.txLabels) ?: return null
        return TrafficCounters(rx, tx)
    }

    fun readMacFilterState(): Triple<Boolean, String, Set<String>> {
        val spec = profile.macFilter ?: return Triple(false, "", emptySet())
        val doc = getDoc(spec.pageUrl)
        val form = selectForm(doc, spec.formSelector) ?: return Triple(false, "", emptySet())
        val active = inputValue(form, spec.activeField).equals(spec.activeOnValue, ignoreCase = true)
        val action = inputValue(form, spec.actionField)
        val macs = spec.macFields.map { normalizeMac(inputValue(form, it)) }.filter { it.isNotBlank() && it != "00:00:00:00:00:00" }.toSet()
        return Triple(active, action, macs)
    }

    private fun verifyDeny(mac: String, shouldExist: Boolean): Boolean {
        val spec = profile.macFilter ?: return false
        val state = readMacFilterState()
        val denyMode = state.first && state.second.equals(spec.denyValue, ignoreCase = true)
        return if (shouldExist) denyMode && mac in state.third else mac !in state.third
    }

    private fun getDoc(pathOrUrl: String): Document {
        val response = execute(Request.Builder().url(resolve(pathOrUrl)).get().build())
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
        return Jsoup.parse(body, response.request.url.toString())
    }

    private fun submitForm(doc: Document, form: Element, overrides: Map<String, String>): Document {
        val values = linkedMapOf<String, String>()
        form.select("input[name],select[name],textarea[name]").forEach { el ->
            val name = el.attr("name")
            if (name.isBlank()) return@forEach
            when (el.tagName()) {
                "select" -> values[name] = el.selectFirst("option[selected]")?.attr("value") ?: el.selectFirst("option")?.attr("value").orEmpty()
                "textarea" -> values[name] = el.text()
                else -> {
                    val type = el.attr("type").lowercase(Locale.US)
                    if ((type == "radio" || type == "checkbox") && !el.hasAttr("checked")) return@forEach
                    if (type != "submit" && type != "button" && type != "image" && type != "file") values[name] = el.attr("value")
                }
            }
        }
        values.putAll(overrides)
        val action = form.absUrl("action").ifBlank { doc.location() }
        val method = form.attr("method").ifBlank { "GET" }.uppercase(Locale.US)
        val request = if (method == "POST") {
            val fb = FormBody.Builder().apply { values.forEach { (k, v) -> add(k, v) } }.build()
            Request.Builder().url(action).post(fb).build()
        } else {
            val parsed = action.toHttpUrlOrNull() ?: throw IllegalStateException("Invalid form action")
            val ub = parsed.newBuilder()
            values.forEach { (k, v) -> ub.addQueryParameter(k, v) }
            Request.Builder().url(ub.build()).get().build()
        }
        val response = execute(request)
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} on form submit")
        return Jsoup.parse(body, response.request.url.toString())
    }

    private fun inputValue(form: Element, name: String): String {
        val els = form.select("[name]").filter { it.attr("name") == name }
        if (els.isEmpty()) return ""
        val checked = els.firstOrNull { (it.attr("type").equals("radio", true) || it.attr("type").equals("checkbox", true)) && it.hasAttr("checked") }
        if (checked != null) return checked.attr("value")
        val el = els.first()
        if (el.tagName() == "select") return el.selectFirst("option[selected]")?.attr("value") ?: el.selectFirst("option")?.attr("value").orEmpty()
        return el.attr("value")
    }

    private fun resolveFieldName(form: Element, preferred: String, type: String): String? {
        form.select("[name]").firstOrNull { it.attr("name").equals(preferred, true) }?.let { return it.attr("name") }
        return form.select("input[type=$type][name]").firstOrNull()?.attr("name")
    }

    private fun selectForm(doc: Document, selector: String): Element? = doc.selectFirst(selector) ?: doc.selectFirst("form")
    private fun looksLikeLogin(doc: Document): Boolean = doc.select("input[type=password]").isNotEmpty() && doc.text().contains("login", true)

    private fun execute(request: Request): Response = http.newCall(request).execute()

    private fun resolve(pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl
        val base = if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/"
        return URI(base).resolve(pathOrUrl.removePrefix("/")).toString()
    }

    private fun normalizeMac(value: String): String = value.trim().replace('-', ':').uppercase(Locale.US)
}
