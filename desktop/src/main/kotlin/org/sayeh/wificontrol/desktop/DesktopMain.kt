package org.sayeh.wificontrol.desktop

import org.sayeh.wificontrol.core.RouterClient
import org.sayeh.wificontrol.core.RouterHttpEngine
import org.sayeh.wificontrol.core.RouterProfile
import org.sayeh.wificontrol.core.RouterProfileCodec
import org.sayeh.wificontrol.core.UsageLedger
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import java.util.Properties
import javax.swing.*

private class DesktopController : JFrame("WiFi Control - TP-Link TD-W8961N") {
    private val root = JPanel(BorderLayout(10, 10))
    private val urlField = JTextField("http://192.168.1.1", 22)
    private val userField = JTextField("admin", 12)
    private val passField = JPasswordField(12)
    private val profileField = JTextField(28)
    private val ownerField = JTextField(18)
    private val packageField = JTextField(8)
    private val status = JTextArea(5, 50)
    private val model = DefaultListModel<String>()
    private val clientsList = JList(model)
    private var clients: List<RouterClient> = emptyList()
    private var profile: RouterProfile? = null
    private var engine: RouterHttpEngine? = null
    private val stateDir = File(System.getProperty("user.home"), ".wifi-control").apply { mkdirs() }
    private val propsFile = File(stateDir, "desktop.properties")
    private val usageFile = File(stateDir, "usage.properties")
    private val props = Properties()

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        minimumSize = Dimension(900, 680)
        status.isEditable = false
        status.lineWrap = true
        status.wrapStyleWord = true
        clientsList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        loadPrefs()
        buildUi()
        pack()
        setLocationRelativeTo(null)
    }

    private fun buildUi() {
        val title = JLabel("مرکز مدیریت واقعی وای‌فای — Windows")
        title.font = title.font.deriveFont(Font.BOLD, 22f)
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(title)
            add(row(JLabel("Router:"), urlField, JLabel("User:"), userField, JLabel("Password:"), passField))
            add(row(JLabel("Firmware profile:"), profileField, JButton("انتخاب JSON").apply { addActionListener { chooseProfile() } }))
            add(row(JLabel("MAC تلفن مدیر:"), ownerField, JLabel("بسته کل (GB):"), packageField))
            add(row(
                JButton("اتصال + تازه‌سازی").apply { addActionListener { connectAndRefresh() } },
                JButton("مصرف/باقی‌مانده").apply { addActionListener { refreshUsage() } },
                JButton("خاموش‌کردن اضطراری MAC Filter").apply { addActionListener { emergencyOff() } }
            ))
        }

        val center = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createTitledBorder("دستگاه‌ها")
            add(JScrollPane(clientsList), BorderLayout.CENTER)
            add(row(
                JButton("قطع واقعی انتخاب‌شده").apply { addActionListener { blockSelected() } },
                JButton("وصل‌کردن دوباره").apply { addActionListener { unblockSelected() } },
                JButton("ضد QR: فقط انتخاب‌شده‌ها").apply { addActionListener { allowSelectedOnly() } }
            ), BorderLayout.SOUTH)
        }

        val bottom = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("نتیجه و Verification")
            add(JScrollPane(status), BorderLayout.CENTER)
        }
        root.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        root.add(top, BorderLayout.NORTH)
        root.add(center, BorderLayout.CENTER)
        root.add(bottom, BorderLayout.SOUTH)
        contentPane = root
    }

    private fun row(vararg items: java.awt.Component): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply { items.forEach(::add) }

    private fun chooseProfile() {
        val chooser = JFileChooser().apply { dialogTitle = "Firmware profile JSON" }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            profileField.text = chooser.selectedFile.absolutePath
            savePrefs()
        }
    }

    private fun connectAndRefresh() = runAsync("در حال اتصال واقعی به روتر…") {
        val p = loadProfile() ?: return@runAsync
        val e = RouterHttpEngine(urlField.text.trim(), p, userField.text.trim(), String(passField.password))
        val login = e.login()
        if (!login.ok || !login.verified) {
            showStatus(login.message)
            return@runAsync
        }
        val found = e.clients()
        profile = p
        engine = e
        clients = found
        SwingUtilities.invokeLater {
            model.clear()
            found.forEach { c -> model.addElement(if (c.ip.isNullOrBlank()) c.mac else "${c.mac}   ${c.ip}") }
            showStatus("${login.message}\n${found.size} دستگاه از جدول واقعی روتر خوانده شد.")
            savePrefs()
        }
    }

    private fun selectedMacs(): List<String> = clientsList.selectedIndices.toList().mapNotNull { idx -> clients.getOrNull(idx)?.mac }

    private fun blockSelected() {
        val macs = selectedMacs()
        if (macs.isEmpty()) { showStatus("یک دستگاه را انتخاب کن."); return }
        val owner = ownerField.text.trim().uppercase()
        if (owner.isNotBlank() && owner in macs.map { it.uppercase() }) { showStatus("تلفن مدیر محافظت‌شده است و Block نمی‌شود."); return }
        runAsync("در حال Block و Verify…") {
            val e = requireEngine() ?: return@runAsync
            val results = macs.map { e.block(it) }
            showStatus(results.joinToString("\n") { it.message })
        }
    }

    private fun unblockSelected() {
        val macs = selectedMacs()
        if (macs.isEmpty()) { showStatus("یک دستگاه را انتخاب کن."); return }
        runAsync("در حال Unblock و Verify…") {
            val e = requireEngine() ?: return@runAsync
            val results = macs.map { e.unblock(it) }
            showStatus(results.joinToString("\n") { it.message })
        }
    }

    private fun allowSelectedOnly() {
        val selected = selectedMacs().toMutableSet()
        val owner = ownerField.text.trim().uppercase()
        if (owner.isBlank()) { showStatus("اول MAC تلفن مدیر را وارد کن؛ Allow‑List بدون مدیر اجرا نمی‌شود."); return }
        selected.add(owner)
        if (selected.isEmpty()) { showStatus("هیچ دستگاه مجازی انتخاب نشده."); return }
        val confirm = JOptionPane.showConfirmDialog(this, "فقط ${selected.size} دستگاه اجازه اتصال داشته باشند؟\nMAC مدیر حتماً داخل لیست است.", "فعال‌سازی ضد QR", JOptionPane.YES_NO_OPTION)
        if (confirm != JOptionPane.YES_OPTION) return
        runAsync("در حال فعال‌کردن Allow‑List و Verify…") {
            val e = requireEngine() ?: return@runAsync
            showStatus(e.setAllowList(selected).message)
        }
    }

    private fun emergencyOff() {
        val confirm = JOptionPane.showConfirmDialog(this, "Wireless MAC Filter خاموش شود؟ WAN/ADSL تغییر نمی‌کند.", "Emergency", JOptionPane.YES_NO_OPTION)
        if (confirm != JOptionPane.YES_OPTION) return
        runAsync("در حال خاموش‌کردن Filter و Verify…") {
            val e = requireEngine() ?: return@runAsync
            showStatus(e.disableMacFilter().message)
        }
    }

    private fun refreshUsage() = runAsync("در حال خواندن شمارنده‌های واقعی…") {
        val e = requireEngine() ?: return@runAsync
        val counters = e.trafficCounters()
        if (counters == null) { showStatus("این Firmware Profile هنوز صفحه Statistics/Byte Counter دقیق ندارد؛ مصرف جعلی نشان داده نمی‌شود."); return@runAsync }
        val ledger = loadUsage()
        val gb = packageField.text.trim().toDoubleOrNull()
        if (gb != null && gb > 0) ledger.packageBytes = (gb * 1024 * 1024 * 1024).toLong()
        ledger.ingest(counters)
        saveUsage(ledger)
        val usedGb = ledger.usedBytes().toDouble() / (1024.0 * 1024 * 1024)
        val remain = ledger.remainingBytes()?.toDouble()?.div(1024.0 * 1024 * 1024)
        showStatus("مصرف ثبت‌شده: %.3f GB%s".format(usedGb, if (remain != null) "\nباقی‌مانده از بسته: %.3f GB".format(remain) else "\nبرای محاسبه باقی‌مانده، حجم کل بسته را وارد کن."))
    }

    private fun loadProfile(): RouterProfile? {
        val file = File(profileField.text.trim())
        if (!file.isFile) { showStatus("Firmware Profile JSON انتخاب نشده یا وجود ندارد."); return null }
        return try { RouterProfileCodec.read(file) } catch (e: Exception) { showStatus("Profile نامعتبر است: ${e.message}"); null }
    }

    private fun requireEngine(): RouterHttpEngine? {
        engine?.let { return it }
        val p = loadProfile() ?: return null
        val e = RouterHttpEngine(urlField.text.trim(), p, userField.text.trim(), String(passField.password))
        val login = e.login()
        if (!login.ok || !login.verified) { showStatus(login.message); return null }
        engine = e
        return e
    }

    private fun runAsync(initial: String, work: () -> Unit) {
        showStatus(initial)
        Thread { try { work() } catch (e: Exception) { showStatus("خطا: ${e.message ?: e.javaClass.simpleName}") } }.start()
    }

    private fun showStatus(text: String) { SwingUtilities.invokeLater { status.text = text } }

    private fun loadPrefs() {
        if (propsFile.isFile) propsFile.inputStream().use(props::load)
        urlField.text = props.getProperty("url", "http://192.168.1.1")
        userField.text = props.getProperty("user", "admin")
        profileField.text = props.getProperty("profile", "")
        ownerField.text = props.getProperty("owner", "")
        packageField.text = props.getProperty("packageGb", "")
    }

    private fun savePrefs() {
        props["url"] = urlField.text.trim(); props["user"] = userField.text.trim(); props["profile"] = profileField.text.trim(); props["owner"] = ownerField.text.trim(); props["packageGb"] = packageField.text.trim()
        propsFile.outputStream().use { props.store(it, "WiFi Control desktop state") }
    }

    private fun loadUsage(): UsageLedger {
        val p = Properties(); if (usageFile.isFile) usageFile.inputStream().use(p::load)
        return UsageLedger(
            packageBytes = p.getProperty("packageBytes", "0").toLongOrNull() ?: 0,
            carriedRxBytes = p.getProperty("carriedRx", "0").toLongOrNull() ?: 0,
            carriedTxBytes = p.getProperty("carriedTx", "0").toLongOrNull() ?: 0,
            lastRxBytes = p.getProperty("lastRx", "-1").toLongOrNull() ?: -1,
            lastTxBytes = p.getProperty("lastTx", "-1").toLongOrNull() ?: -1
        )
    }

    private fun saveUsage(l: UsageLedger) {
        val p = Properties().apply {
            setProperty("packageBytes", l.packageBytes.toString()); setProperty("carriedRx", l.carriedRxBytes.toString()); setProperty("carriedTx", l.carriedTxBytes.toString()); setProperty("lastRx", l.lastRxBytes.toString()); setProperty("lastTx", l.lastTxBytes.toString())
        }
        usageFile.outputStream().use { p.store(it, "WiFi usage ledger") }
    }
}

fun main() = SwingUtilities.invokeLater { DesktopController().isVisible = true }
