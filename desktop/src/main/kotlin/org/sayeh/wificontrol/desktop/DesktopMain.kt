package org.sayeh.wificontrol.desktop

import org.sayeh.wificontrol.core.DirectRouter
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import java.util.Locale
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*

private class DesktopController : JFrame("WiFi Control Direct v4") {
    private val urlField = JTextField("http://192.168.1.1", 22)
    private val userField = JTextField("admin", 10)
    private val passField = JPasswordField(12)
    private val managerField = JTextField(18)
    private val status = JTextArea(5, 65)
    private val capsLabel = JLabel("قابلیت‌ها: —")
    private val listModel = DefaultListModel<String>()
    private val list = JList(listModel)
    private val connectBtn = JButton("اتصال مستقیم")
    private val refreshBtn = JButton("تازه‌سازی")
    private val blockWifiBtn = JButton("قطع Wi‑Fi")
    private val unblockWifiBtn = JButton("وصل Wi‑Fi")
    private val blockInternetBtn = JButton("قطع اینترنت")
    private val unblockInternetBtn = JButton("وصل اینترنت")
    private val allowBtn = JButton("ضد QR: فقط انتخاب‌شده‌ها + مدیر")
    private val filterOffBtn = JButton("MAC Filter OFF")
    private val statsBtn = JButton("Statistics")

    private val stateDir = File(System.getProperty("user.home"), ".wifi-control-direct-v4").apply { mkdirs() }
    private val propsFile = File(stateDir, "desktop.properties")
    private val props = Properties()
    private val opId = AtomicInteger(0)

    private var clients: List<DirectRouter.Client> = emptyList()
    private var caps = DirectRouter.Capabilities()
    private var connected = false
    private var busy = false

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        minimumSize = Dimension(920, 680)
        status.isEditable = false
        status.lineWrap = true
        status.wrapStyleWord = true
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        loadPrefs()
        buildUi()
        wire()
        updateUi()
        pack()
        setLocationRelativeTo(null)
    }

    private fun buildUi() {
        val title = JLabel("WiFi Control Direct v4 — Windows")
        title.font = title.font.deriveFont(Font.BOLD, 22f)
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(title)
            add(JLabel("TP-Link TD-W8961N V4 • Direct HTTP Engine • بدون WebView/Profile JSON"))
            add(row(JLabel("Router:"), urlField, JLabel("User:"), userField, JLabel("Password:"), passField))
            add(row(JLabel("MAC مدیر:"), managerField, connectBtn, refreshBtn))
            add(capsLabel)
        }
        val center = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createTitledBorder("دستگاه‌های متصل")
            add(JScrollPane(list), BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(row(blockWifiBtn, unblockWifiBtn, blockInternetBtn, unblockInternetBtn))
                add(row(allowBtn, filterOffBtn, statsBtn))
            }, BorderLayout.SOUTH)
        }
        val bottom = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("نتیجه واقعی / Verification")
            add(JScrollPane(status), BorderLayout.CENTER)
        }
        contentPane = JPanel(BorderLayout(10, 10)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(top, BorderLayout.NORTH)
            add(center, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    private fun wire() {
        connectBtn.addActionListener { connect() }
        refreshBtn.addActionListener { refresh() }
        blockWifiBtn.addActionListener { selectedOne()?.let { runAction("در حال Block Wi‑Fi…") { router().blockWifi(it) } } }
        unblockWifiBtn.addActionListener { selectedOne()?.let { runAction("در حال Unblock Wi‑Fi…") { router().unblockWifi(it) } } }
        blockInternetBtn.addActionListener { selectedOne()?.let { runAction("در حال Block اینترنت…") { router().blockInternet(it) } } }
        unblockInternetBtn.addActionListener { selectedOne()?.let { runAction("در حال Unblock اینترنت…") { router().unblockInternet(it) } } }
        filterOffBtn.addActionListener {
            if (JOptionPane.showConfirmDialog(this, "Wireless MAC Filter خاموش شود؟", "Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
                runAction("در حال خاموش‌کردن MAC Filter…") { router().disableWirelessFilter() }
        }
        allowBtn.addActionListener { allowSelected() }
        statsBtn.addActionListener { readStats() }
    }

    private fun connect() {
        if (!credentialsReady()) return
        runOp("در حال اتصال مستقیم HTTP به روتر…", 30000,
            task = { router().connectAndProbe() },
            done = { s ->
                connected = s.ok
                caps = if (s.ok) s.capabilities else DirectRouter.Capabilities()
                clients = if (s.ok) s.clients else emptyList()
                status.text = s.message + if (s.firmware.isNotBlank()) "\nFirmware: ${s.firmware}" else ""
                renderClients(); savePrefs(); updateUi()
            })
    }

    private fun refresh() {
        runOp("در حال خواندن مستقیم دستگاه‌ها…", 18000,
            task = { router().clients() },
            done = { c -> clients = c; status.text = "${c.size} دستگاه از خود روتر خوانده شد."; renderClients(); updateUi() })
    }

    private fun runAction(label: String, work: () -> DirectRouter.ActionResult) {
        runOp(label, 22000, work) { r -> status.text = r.message; updateUi() }
    }

    private fun allowSelected() {
        val manager = managerField.text.trim().uppercase(Locale.US)
        if (!validMac(manager)) { status.text = "MAC مدیر را درست وارد کن تا خودش قطع نشود."; return }
        val selected = list.selectedIndices.mapNotNull { clients.getOrNull(it)?.mac }.toMutableSet()
        selected.add(manager)
        if (JOptionPane.showConfirmDialog(this, "فقط ${selected.size} MAC اجازه اتصال داشته باشند؟", "Allow-List", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return
        runAction("در حال ثبت Allow-List…") { router().setAllowList(selected) }
    }

    private fun readStats() {
        runOp("در حال خواندن Byte Counter…", 18000,
            task = { router().traffic() },
            done = { t ->
                status.text = if (t == null) "این firmware Byte Counter قابل‌تشخیص نشان نداد." else {
                    val total = t.rxBytes + t.txBytes
                    "RX: ${fmt(t.rxBytes)}\nTX: ${fmt(t.txBytes)}\nTotal: ${fmt(total)}"
                }
            })
    }

    private fun router() = DirectRouter(urlField.text.trim(), userField.text.trim(), String(passField.password), managerField.text.trim())

    private fun credentialsReady(): Boolean {
        if (userField.text.trim().isBlank() || passField.password.isEmpty()) { status.text = "نام کاربری و رمز ادمین را وارد کن."; return false }
        return true
    }

    private fun selectedOne(): String? {
        val idx = list.selectedIndex
        if (idx < 0) { status.text = "یک دستگاه را انتخاب کن."; return null }
        return clients.getOrNull(idx)?.mac
    }

    private fun renderClients() {
        listModel.clear()
        val manager = managerField.text.trim().uppercase(Locale.US)
        clients.forEach { c -> listModel.addElement(buildString { append(c.mac); c.ip?.let { append("   ").append(it) }; if (c.mac == manager) append("   مدیر") }) }
    }

    private fun updateUi() {
        val e = connected && !busy
        connectBtn.isEnabled = !busy
        refreshBtn.isEnabled = e
        blockWifiBtn.isEnabled = e && caps.wirelessMacFilter
        unblockWifiBtn.isEnabled = e && caps.wirelessMacFilter
        allowBtn.isEnabled = e && caps.wirelessMacFilter
        filterOffBtn.isEnabled = e && caps.wirelessMacFilter
        blockInternetBtn.isEnabled = e && caps.internetMacFilter
        unblockInternetBtn.isEnabled = e && caps.internetMacFilter
        statsBtn.isEnabled = e && caps.statistics
        capsLabel.text = "Devices ${m(caps.devices)} • Wi‑Fi MAC ${m(caps.wirelessMacFilter)} • Internet MAC ${m(caps.internetMacFilter)} • QoS ${m(caps.qos)} • Statistics ${m(caps.statistics)} • Guest ${m(caps.guest)} • Guest BW ${m(caps.guestBandwidth)}"
    }

    private fun m(v: Boolean) = if (v) "✓" else "—"
    private fun row(vararg items: java.awt.Component) = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply { items.forEach(::add) }
    private fun validMac(m: String) = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(m)
    private fun fmt(v: Long): String { val g = v / 1073741824.0; return if (g >= .01) "%.3f GB".format(Locale.US, g) else "%.2f MB".format(Locale.US, v / 1048576.0) }

    private fun <T> runOp(label: String, timeout: Int, task: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true; updateUi(); status.text = label
        val id = opId.incrementAndGet()
        val timer = Timer(timeout) {
            if (opId.compareAndSet(id, id + 1)) {
                busy = false; updateUi(); status.text = "Timeout: عملیات در ${timeout / 1000} ثانیه پایان نیافت."
            }
        }.apply { isRepeats = false; start() }
        Thread {
            try {
                val value = task()
                SwingUtilities.invokeLater {
                    if (opId.get() != id) return@invokeLater
                    timer.stop(); busy = false; done(value); updateUi()
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    if (opId.get() != id) return@invokeLater
                    timer.stop(); busy = false; updateUi(); status.text = "خطا: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun loadPrefs() {
        if (propsFile.isFile) propsFile.inputStream().use(props::load)
        urlField.text = props.getProperty("url", "http://192.168.1.1")
        userField.text = props.getProperty("user", "admin")
        managerField.text = props.getProperty("manager", "")
    }

    private fun savePrefs() {
        props["url"] = urlField.text.trim(); props["user"] = userField.text.trim(); props["manager"] = managerField.text.trim()
        propsFile.outputStream().use { props.store(it, "WiFi Control Direct v4") }
    }
}

fun main() = SwingUtilities.invokeLater { DesktopController().isVisible = true }
