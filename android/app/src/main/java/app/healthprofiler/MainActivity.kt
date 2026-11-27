package app.healthprofiler

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONArray
import org.json.JSONObject

/** Shows the dashboard (health.html bundled in the app) and links it to the background WatchService. */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private var svc: WatchService? = null
    private var visible = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pickAfterPermission = false

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_FILE = 2
        private const val PAGE = "https://appassets.androidplatform.net/assets/health.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try { startActivityForResult(Intent.createChooser(intent, "Choose STR.edf"), REQ_FILE); true }
                catch (e: Exception) { fileCallback = null; false }
            }
        }
        web.addJavascriptInterface(Bridge(), "HDNative")
        web.loadUrl(PAGE)

        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        if (Prefs.address(this) != null && hasPermissions()) WatchService.start(this)
        bindService(Intent(this, WatchService::class.java), conn, 0)
    }

    override fun onStop() {
        visible = false
        svc?.attachUi(null)
        try { unbindService(conn) } catch (_: Exception) {}
        svc = null
        super.onStop()
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as WatchService.LocalBinder).service()
            svc = s
            s.attachUi { evt -> main.post { sendToPage(evt) } }
            // Readings collected while the screen was closed.
            main.post { web.evaluateJavascript("window.__hdResume && window.__hdResume()", null) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
    }

    private fun sendToPage(evt: JSONObject) {
        web.evaluateJavascript("window.__hdNative && window.__hdNative(${evt})", null)
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            val uris: Array<Uri>? = when {
                resultCode != RESULT_OK || data == null -> null
                data.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }
    }

    // ---------------------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------------------
    private fun neededPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else list += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list
    }

    private fun hasPermissions(): Boolean = neededPermissions()
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestNeededPermissions() {
        val missing = neededPermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermissions()) {
            if (Prefs.address(this) != null) WatchService.start(this)
            if (pickAfterPermission) { pickAfterPermission = false; pickDevice() }
        } else if (pickAfterPermission) {
            pickAfterPermission = false
            AlertDialog.Builder(this)
                .setTitle("Bluetooth permission needed")
                .setMessage("Health Profiler needs the \"Nearby devices\" permission to find and connect to your watch. Open the app settings and allow it under Permissions.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ---------------------------------------------------------------------------------
    // Choose a watch: paired/connected devices first, then a 15-second scan
    // ---------------------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        if (!hasPermissions()) {
            pickAfterPermission = true
            val missing = neededPermissions().filter { it != Manifest.permission.POST_NOTIFICATIONS &&
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        val bm = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter
        if (adapter == null) { message("This phone doesn't support Bluetooth Low Energy."); return }
        if (!adapter.isEnabled) {
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            catch (_: Exception) { message("Turn on Bluetooth, then tap Connect again.") }
            return
        }

        val found = LinkedHashMap<String, String>()
        val items = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        fun add(d: BluetoothDevice, label: String?) {
            val name = label ?: d.name ?: return
            if (found.put(d.address, name) == null) items.add("$name   ${d.address}")
        }
        try { bm.getConnectedDevices(BluetoothProfile.GATT).forEach { add(it, null) } } catch (_: Exception) {}
        try { adapter.bondedDevices.filter { it.type != BluetoothDevice.DEVICE_TYPE_CLASSIC }.forEach { add(it, null) } } catch (_: Exception) {}

        val scanner = adapter.bluetoothLeScanner
        val scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post { add(result.device, result.scanRecord?.deviceName) }
            }
        }
        val stopScan = { try { scanner?.stopScan(scanCb) } catch (_: Exception) {} }

        AlertDialog.Builder(this)
            .setTitle(if (found.isEmpty()) "Choose your watch (searching…)" else "Choose your watch")
            .setAdapter(items) { _, which ->
                stopScan()
                val addr = found.keys.elementAt(which)
                Prefs.setDevice(this, addr, found[addr])
                WatchService.stop(this)
                main.postDelayed({
                    WatchService.start(this)
                    bindService(Intent(this, WatchService::class.java), conn, 0)
                }, 500)
            }
            .setNegativeButton("Cancel") { _, _ -> stopScan() }
            .setOnDismissListener { stopScan() }
            .show()
        try { scanner?.startScan(scanCb) } catch (_: Exception) {}
        main.postDelayed({ stopScan() }, 15_000)
    }

    private fun message(text: String) {
        AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show()
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this).setMessage("Background running is already allowed.").setPositiveButton("OK", null).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------------------------------------------------------------------------------
    // JavaScript bridge: window.HDNative in health.html
    // ---------------------------------------------------------------------------------
    inner class Bridge {
        private val ctx: Context get() = this@MainActivity

        @JavascriptInterface fun isApp(): Boolean = true

        @JavascriptInterface fun status(): String =
            (svc?.statusJson() ?: JSONObject().put("connected", false).put("name", Prefs.deviceName(ctx)))
                .put("hasDevice", Prefs.address(ctx) != null)
                .put("batteryExempt", (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName))
                .toString()

        @JavascriptInterface fun pickDevice() { main.post { this@MainActivity.pickDevice() } }

        @JavascriptInterface fun forget() {
            main.post { Prefs.setDevice(ctx, null, null); WatchService.stop(ctx); sendToPage(JSONObject().put("type", "conn").put("connected", false)) }
        }

        @JavascriptInterface fun send(cmd: Int, payloadJson: String) {
            val arr = JSONArray(payloadJson)
            svc?.forwardSend(cmd, ByteArray(arr.length()) { arr.getInt(it).toByte() })
        }

        @JavascriptInterface fun drain(): String = EventStore.drain(ctx).toString()

        @JavascriptInterface fun setProfile(json: String) { Prefs.setProfile(ctx, json) }

        @JavascriptInterface fun notify(title: String, body: String) {
            val s = svc
            if (s != null) s.phoneAlert(title, body)
        }

        @JavascriptInterface fun setBusy(b: Boolean) { svc?.uiBusy = b }

        @JavascriptInterface fun sync() { svc?.syncWatch() }

        @JavascriptInterface fun batteryExemption() { main.post { this@MainActivity.askBatteryExemption() } }
    }
}
