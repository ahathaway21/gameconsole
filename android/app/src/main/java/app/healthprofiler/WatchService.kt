package app.healthprofiler

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * Keeps the watch connected in the background, runs scheduled heart-rate and SpO2 checks,
 * and raises alerts (phone notification + message on the watch) even when the screen is off.
 */
class WatchService : Service() {

    companion object {
        const val CH_STATUS = "status"
        const val CH_ALERTS = "alerts"
        const val NOTIF_ID = 1
        private const val TICK_MS = 5 * 60_000L
        private const val MIN = 60_000L

        @Volatile var instance: WatchService? = null

        private fun uuid16(h: String): UUID = UUID.fromString("0000$h-0000-1000-8000-00805f9b34fb")
        val HR_SVC = uuid16("180d"); val HR_CHAR = uuid16("2a37")
        val BATT_SVC = uuid16("180f"); val BATT_CHAR = uuid16("2a19")
        val SPORT_SVC = uuid16("fee7"); val SPORT_CHAR = uuid16("fea1")
        val MOY_SVC = uuid16("feea"); val MOY_OUT = uuid16("fee2"); val MOY_IN = uuid16("fee3")
        val CCCD = uuid16("2902")

        // Moyoung (Da Fit) command ids, from the open-source Gadgetbridge project.
        const val CMD_MSG = 65; const val CMD_FIND = 97; const val CMD_BP = 105; const val CMD_SPO2 = 107
        const val CMD_HR = 109; const val CMD_TIME = 49; const val CMD_PAST = 51; const val CMD_HRHIST = 53
        const val CMD_USER = 18; const val CMD_GOAL = 22; const val CMD_HRAUTO = 31; const val CMD_SEDENTARY = 29
        const val CMD_MOVE = 115; const val CMD_ADV = 0xb9

        fun start(c: Context) {
            try { ContextCompat.startForegroundService(c, Intent(c, WatchService::class.java)) } catch (_: Exception) {}
        }
        fun stop(c: Context) { c.stopService(Intent(c, WatchService::class.java)) }

        fun hasBtPermission(c: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 31)
                ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            else true
    }

    inner class LocalBinder : Binder() { fun service(): WatchService = this@WatchService }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wake: PowerManager.WakeLock

    // ---- connection state ----
    private var gatt: BluetoothGatt? = null
    @Volatile var connected = false
        private set
    var battery: Int? = null
        private set
    private var cHr: BluetoothGattCharacteristic? = null
    private var cSport: BluetoothGattCharacteristic? = null
    private var cBatt: BluetoothGattCharacteristic? = null
    private var cOut: BluetoothGattCharacteristic? = null
    private var cIn: BluetoothGattCharacteristic? = null
    private var reconnectDelay = 5_000L

    // ---- live data ----
    private val samples = ArrayDeque<Pair<Long, Int>>()
    var lastHr: Int? = null
        private set
    var steps: Int? = null
        private set
    private var meters: Int? = null
    private var kcal: Int? = null
    private var lastStepChange = System.currentTimeMillis()
    private var lastStepsQueued = 0L
    private var lastRuleCheck = 0L
    private var minuteStart = 0L
    private var minuteVals = mutableListOf<Int>()

    // ---- checks ----
    private var busy = false
    @Volatile var uiBusy = false
    private var collect: MutableList<Pair<Long, Int>>? = null
    private var spo2Waiter: ((Int) -> Unit)? = null
    private var lastAutoHr = System.currentTimeMillis()
    private var lastAutoSpo2 = System.currentTimeMillis()

    // ---- screen (WebView) connection ----
    private var ui: ((JSONObject) -> Unit)? = null
    fun attachUi(listener: ((JSONObject) -> Unit)?) {
        main.post {
            ui = listener
            if (listener != null) listener(statusJson().put("type", "conn"))
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HealthProfiler:check")
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Prefs.address(this) == null || !hasBtPermission(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, statusNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (gatt == null) connect()
        scheduleTick()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        cancelTick()
        try { gatt?.close() } catch (_: SecurityException) {}
        gatt = null
        connected = false
        if (wake.isHeld) wake.release()
        super.onDestroy()
    }

    // =====================================================================================
    // Notifications
    // =====================================================================================
    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Watch connection", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Health alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun statusNotification(): Notification {
        val name = Prefs.deviceName(this) ?: "watch"
        val text = if (connected) {
            listOfNotNull(
                lastHr?.let { "♥ $it bpm" },
                steps?.let { "%,d steps".format(it) },
                battery?.let { "🔋 $it%" }
            ).joinToString(" · ").ifEmpty { "Monitoring" }
        } else "Reconnecting…"
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (connected) "Connected to $name" else "Looking for $name")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    private var lastNotifUpdate = 0L
    private fun updateStatus(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifUpdate < 15_000) return
        lastNotifUpdate = now
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, statusNotification()) } catch (_: SecurityException) {}
    }

    fun phoneAlert(title: String, body: String) {
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try { getSystemService(NotificationManager::class.java).notify(title.hashCode(), n) } catch (_: SecurityException) {}
    }

    // =====================================================================================
    // Bluetooth connection
    // =====================================================================================
    @SuppressLint("MissingPermission")
    private fun connect() {
        val addr = Prefs.address(this) ?: return
        if (!hasBtPermission(this)) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { scheduleReconnect(); return }
        try { gatt?.close() } catch (_: Exception) {}
        val device = adapter.getRemoteDevice(addr)
        gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val reconnectRunnable = Runnable { if (!connected) connect() }
    private fun scheduleReconnect() {
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, reconnectDelay)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(120_000L)
    }

    // One Bluetooth operation at a time (Android requirement).
    private val ops = ArrayDeque<() -> Boolean>()
    private var opBusy = false
    private val opTimeout = Runnable { nextOp() }
    private fun enqueue(op: () -> Boolean) = main.post { ops.addLast(op); if (!opBusy) nextOp() }
    private fun nextOp() {
        main.removeCallbacks(opTimeout)
        opBusy = false
        while (ops.isNotEmpty()) {
            val op = ops.removeFirst()
            val started = try { op() } catch (_: Exception) { false }
            if (started) { opBusy = true; main.postDelayed(opTimeout, 4_000); return }
        }
    }
    private fun opDone() = main.post { nextOp() }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = false
                    ops.clear(); opBusy = false
                    try { g.close() } catch (_: Exception) {}
                    if (gatt === g) gatt = null
                    if (busy) { busy = false; collect = null; spo2Waiter = null; if (wake.isHeld) wake.release() }
                    emit(statusJson().put("type", "conn"))
                    updateStatus(true)
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { main.post { setup(g) } }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { opDone() }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { opDone() }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33 && status == BluetoothGatt.GATT_SUCCESS) {
                val v = c.value?.clone() ?: ByteArray(0); main.post { onData(c.uuid, v) }
            }
            opDone()
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) { val v = value.clone(); main.post { onData(c.uuid, v) } }
            opDone()
        }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) { val v = c.value?.clone() ?: return; main.post { onData(c.uuid, v) } }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val v = value.clone(); main.post { onData(c.uuid, v) }
        }
    }

    private fun setup(g: BluetoothGatt) {
        cHr = g.getService(HR_SVC)?.getCharacteristic(HR_CHAR)
        cBatt = g.getService(BATT_SVC)?.getCharacteristic(BATT_CHAR)
        cSport = g.getService(SPORT_SVC)?.getCharacteristic(SPORT_CHAR)
        cOut = g.getService(MOY_SVC)?.getCharacteristic(MOY_OUT)
        cIn = g.getService(MOY_SVC)?.getCharacteristic(MOY_IN)
        connected = true
        reconnectDelay = 5_000L
        listOf(cHr, cSport, cBatt, cIn).forEach { it?.let { c -> enableNotify(g, c) } }
        cBatt?.let { read(g, it) }
        cSport?.let { read(g, it) }
        emit(statusJson().put("type", "conn"))
        updateStatus(true)
        syncWatch()
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) = enqueue {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return@enqueue false
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        else legacyWriteDescriptor(g, d, v)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun legacyWriteDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray): Boolean {
        d.value = v
        return g.writeDescriptor(d)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun legacyWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, type: Int, bytes: ByteArray): Boolean {
        c.writeType = type
        c.value = bytes
        return g.writeCharacteristic(c)
    }

    @SuppressLint("MissingPermission")
    private fun read(g: BluetoothGatt, c: BluetoothGattCharacteristic) = enqueue { g.readCharacteristic(c) }

    @SuppressLint("MissingPermission")
    private fun write(c: BluetoothGattCharacteristic, bytes: ByteArray) = enqueue {
        val g = gatt ?: return@enqueue false
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
        else legacyWrite(g, c, type, bytes)
    }

    /** Moyoung packet: FE EA 10 <len> <cmd> <payload>, sent in 20-byte pieces. */
    fun moySend(cmd: Int, payload: ByteArray = ByteArray(0)): Boolean {
        val out = cOut ?: return false
        if (!connected) return false
        val len = payload.size + 5
        val pkt = ByteArray(len)
        pkt[0] = 0xfe.toByte(); pkt[1] = 0xea.toByte()
        pkt[2] = (0x10 or ((len shr 8) and 0x0f)).toByte(); pkt[3] = (len and 0xff).toByte(); pkt[4] = cmd.toByte()
        payload.copyInto(pkt, 5)
        var i = 0
        while (i < pkt.size) { write(out, pkt.copyOfRange(i, minOf(i + 20, pkt.size))); i += 20 }
        return true
    }
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // =====================================================================================
    // Incoming data
    // =====================================================================================
    private var inBuf = ByteArray(0)

    private fun onData(uuid: UUID, v: ByteArray) {
        when (uuid) {
            HR_CHAR -> if (v.size >= 2) {
                val flags = v[0].toInt() and 0xff
                val bpm = if (flags and 1 != 0 && v.size >= 3) (v[1].toInt() and 0xff) or ((v[2].toInt() and 0xff) shl 8) else v[1].toInt() and 0xff
                onHr(bpm)
            }
            SPORT_CHAR -> if (v.size >= 4) {
                val u24 = { o: Int -> (v[o].toInt() and 0xff) or ((v[o + 1].toInt() and 0xff) shl 8) or ((v[o + 2].toInt() and 0xff) shl 16) }
                val flags = v[0].toInt() and 0xff
                var o = 1
                var st = 0; var m: Int? = null; var k: Int? = null
                if (flags and 1 != 0) { st = u24(o); o += 3 }
                if (flags and 2 != 0 && v.size >= o + 3) { m = u24(o); o += 3 }
                if (flags and 4 != 0 && v.size >= o + 3) { k = u24(o) }
                onSteps(st, m, k)
            }
            BATT_CHAR -> if (v.isNotEmpty()) {
                battery = v[0].toInt() and 0xff
                emit(JSONObject().put("type", "batt").put("v", battery))
                updateStatus()
            }
            MOY_IN -> {
                if (v.size >= 2 && v[0] == 0xfe.toByte() && v[1] == 0xea.toByte()) inBuf = ByteArray(0)
                inBuf += v
                if (inBuf.size < 5 || inBuf[0] != 0xfe.toByte() || inBuf[1] != 0xea.toByte()) return
                val len = ((inBuf[2].toInt() and 0x0f) shl 8) or (inBuf[3].toInt() and 0xff)
                if (inBuf.size < len) return
                val cmd = inBuf[4].toInt() and 0xff
                val payload = inBuf.copyOfRange(5, len)
                inBuf = inBuf.copyOfRange(len, inBuf.size)
                onPacket(cmd, payload)
            }
        }
    }

    private fun onHr(bpm: Int) {
        if (bpm < 25 || bpm > 240) return
        val now = System.currentTimeMillis()
        lastHr = bpm
        samples.addLast(now to bpm)
        while (samples.isNotEmpty() && samples.first().first < now - 15 * MIN) samples.removeFirst()
        collect?.add(now to bpm)
        if (ui != null) {
            emit(JSONObject().put("type", "hr").put("bpm", bpm))
        } else {
            // Store one averaged value per minute while the screen is closed.
            val minute = now - now % MIN
            if (minute != minuteStart) { flushMinute(); minuteStart = minute }
            minuteVals.add(bpm)
        }
        updateStatus()
        if (now - lastRuleCheck > 30_000) { lastRuleCheck = now; liveRules() }
    }

    private fun flushMinute() {
        if (minuteVals.isEmpty()) return
        val avg = minuteVals.average()
        EventStore.append(this, JSONObject().put("type", "hrm").put("t", minuteStart)
            .put("avg", avg).put("n", minuteVals.size).put("min", minuteVals.min()).put("max", minuteVals.max()))
        minuteVals = mutableListOf()
    }

    private fun onSteps(st: Int, m: Int?, k: Int?) {
        val now = System.currentTimeMillis()
        val prev = steps
        if (prev == null || st > prev) lastStepChange = now
        steps = st; meters = m; kcal = k
        if (ui != null) emit(JSONObject().put("type", "steps").put("steps", st).put("m", m).put("kcal", k))
        else if (now - lastStepsQueued > 2 * MIN) { lastStepsQueued = now; queueSteps() }
        updateStatus()
    }
    private fun queueSteps() {
        val st = steps ?: return
        EventStore.append(this, JSONObject().put("type", "steps").put("t", System.currentTimeMillis())
            .put("steps", st).put("m", meters).put("kcal", kcal))
    }

    private fun onPacket(cmd: Int, p: ByteArray) {
        val arr = JSONArray(); p.forEach { arr.put(it.toInt() and 0xff) }
        val evt = JSONObject().put("type", "pkt").put("cmd", cmd).put("payload", arr).put("t", System.currentTimeMillis())
        if (ui != null) emit(evt)
        else if (cmd == CMD_PAST || cmd == CMD_HRHIST || cmd == CMD_ADV || cmd == CMD_BP) EventStore.append(this, evt)

        val first = if (p.isNotEmpty()) p[0].toInt() and 0xff else -1
        when (cmd) {
            CMD_HR -> if (first in 26..239) onHr(first)
            CMD_SPO2 -> if (first in 70..100) { val w = spo2Waiter; spo2Waiter = null; w?.invoke(first) }
            CMD_HRHIST -> if (first in 0..6) moySend(CMD_HRHIST, bytes(first + 1))
        }
    }

    // =====================================================================================
    // Sync profile/settings to the watch and request its stored history
    // =====================================================================================
    fun syncWatch() = main.post {
        if (cOut == null || !connected) return@post
        // The watch keeps time as "local wall clock expressed in GMT+8".
        val now = System.currentTimeMillis()
        val wall = now + TimeZone.getDefault().getOffset(now)
        val wt = ((wall - 8 * 3_600_000L) / 1000).toInt()
        moySend(CMD_TIME, bytes((wt ushr 24) and 255, (wt ushr 16) and 255, (wt ushr 8) and 255, wt and 255, 8))
        val pr = Prefs.profile(this)
        val age = pr.optInt("age", 0)
        val weight = pr.optDouble("weight", 0.0)
        if (age > 0 && weight > 0) {
            val inches = pr.optDouble("ft", 0.0) * 12 + pr.optDouble("inch", 0.0)
            val cm = Math.round(inches * 2.54).toInt()
            val kg = minOf(250, Math.round(weight * 0.4536).toInt())
            moySend(CMD_USER, bytes(cm and 255, kg and 255, age and 255, if (pr.optString("sex") == "f") 0 else 1))
            val goal = pr.optInt("stepGoal", if (age < 60) 8000 else 6000)
            moySend(CMD_GOAL, bytes((goal ushr 24) and 255, (goal ushr 16) and 255, (goal ushr 8) and 255, goal and 255))
        }
        moySend(CMD_HRAUTO, bytes(if (pr.optBoolean("autoHR", true)) 2 else 0))
        val sedentary = pr.optBoolean("sedentary", false)
        moySend(CMD_SEDENTARY, bytes(if (sedentary) 1 else 0))
        if (sedentary) moySend(CMD_MOVE, bytes(60, 50, 8, 21))
        moySend(CMD_PAST, bytes(1)); moySend(CMD_PAST, bytes(2))
        moySend(CMD_ADV, bytes(0x11, 0x03, 0x00)); moySend(CMD_ADV, bytes(0x11, 0x03, 0x01))
        moySend(CMD_HRHIST, bytes(0))
        emit(JSONObject().put("type", "synced").put("t", System.currentTimeMillis()))
    }

    fun forwardSend(cmd: Int, payload: ByteArray) = main.post { moySend(cmd, payload) }

    // =====================================================================================
    // Scheduled checks
    // =====================================================================================
    private fun tickIntent(): PendingIntent = PendingIntent.getBroadcast(this, 1,
        Intent(this, TickReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun scheduleTick() {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + TICK_MS, tickIntent())
    }
    private fun cancelTick() { (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(tickIntent()) }

    fun tick() = main.post {
        scheduleTick()
        if (!connected) { if (gatt == null) connect(); return@post }
        if (ui == null) { flushMinute(); queueSteps() }
        inactivityRule()
        if (busy || uiBusy) return@post
        val pr = Prefs.profile(this)
        val now = System.currentTimeMillis()
        val hrEvery = pr.optInt("hrEvery", 15)
        val spo2Every = pr.optInt("spo2Every", 60)
        if (hrEvery > 0 && now - lastAutoHr >= hrEvery * MIN - 30_000) { lastAutoHr = now; runHrCheck() }
        else if (spo2Every > 0 && cOut != null && now - lastAutoSpo2 >= spo2Every * MIN - 30_000) { lastAutoSpo2 = now; runSpo2Check() }
    }

    private fun isResting() = System.currentTimeMillis() - lastStepChange > 3 * MIN

    private fun runHrCheck() {
        busy = true
        wake.acquire(90_000)
        collect = mutableListOf()
        val resting = isResting()
        moySend(CMD_HR, bytes(0))
        main.postDelayed({
            moySend(CMD_HR, bytes(255))
            val pts = collect ?: mutableListOf()
            collect = null
            val cutoff = System.currentTimeMillis() - 15_000
            val lastHalf = pts.filter { it.first >= cutoff }.map { it.second }
            val hr = Health.median(if (lastHalf.size >= 3) lastHalf else pts.map { it.second })
            if (hr != null) {
                record(JSONObject().put("type", "check").put("t", System.currentTimeMillis()).put("hr", hr).put("resting", resting).put("auto", true))
                if (resting) raise(Health.restingHR(hr))
            }
            busy = false
            if (wake.isHeld) wake.release()
        }, 30_000)
    }

    private fun runSpo2Check() {
        busy = true
        wake.acquire(90_000)
        var finished = false
        val finish = { v: Int? ->
            if (!finished) {
                finished = true
                spo2Waiter = null
                moySend(CMD_SPO2, bytes(255))
                if (v != null) {
                    record(JSONObject().put("type", "spo2").put("t", System.currentTimeMillis()).put("v", v).put("src", "auto"))
                    raise(Health.spo2(v))
                }
                busy = false
                if (wake.isHeld) wake.release()
            }
        }
        spo2Waiter = { v -> main.post { finish(v) } }
        moySend(CMD_SPO2, bytes(0))
        main.postDelayed({ finish(null) }, 70_000)
    }

    private fun liveRules() {
        val now = System.currentTimeMillis()
        val recent = samples.filter { it.first > now - 2 * MIN }.map { it.second }
        if (recent.size < 10) return
        val med = Health.median(recent) ?: return
        if (isResting()) { if (med > 100 || med < 45) raise(Health.restingHR(med)) }
        else raise(Health.activeHR(med, Prefs.profile(this).optInt("age", 0)), cooldownMin = 10)
    }

    private fun inactivityRule() {
        val pr = Prefs.profile(this)
        if (!pr.optBoolean("inactive", true)) return
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (h < 8 || h >= 21) return
        if (System.currentTimeMillis() - lastStepChange > 60 * MIN) {
            raise(Alert("inactive", "caution", "Time to move",
                "You haven't moved for an hour. A 2–5 minute walk breaks up sitting time, which helps blood sugar and circulation.",
                "Sitting 1 hr. Take a 3-5 min walk."), cooldownMin = 60)
        }
    }

    private fun raise(a: Alert?, cooldownMin: Int = 30) {
        if (a == null || a.level == "ok") return
        val now = System.currentTimeMillis()
        if (a.level != "urgent" && now - Prefs.lastAlert(this, a.type) < cooldownMin * MIN) return
        Prefs.setLastAlert(this, a.type, now)
        val pr = Prefs.profile(this)
        if (pr.optBoolean("phone", true)) phoneAlert(a.title, a.detail)
        if (pr.optBoolean("watch", true) && a.watch != null) {
            val text = a.watch.toByteArray(Charsets.UTF_8).take(120).toByteArray()
            moySend(CMD_MSG, byteArrayOf(1) + text)
        }
        record(JSONObject().put("type", "alert").put("t", now).put("level", a.level).put("title", a.title).put("detail", a.detail))
    }

    /** Send to the open screen, or keep for later if it's closed. */
    private fun record(evt: JSONObject) { if (ui != null) emit(evt) else EventStore.append(this, evt) }
    private fun emit(evt: JSONObject) { ui?.invoke(evt) }

    fun statusJson(): JSONObject = JSONObject()
        .put("connected", connected)
        .put("name", Prefs.deviceName(this))
        .put("address", Prefs.address(this))
        .put("battery", battery)
        .put("hr", lastHr)
        .put("steps", steps)
        .put("m", meters)
        .put("kcal", kcal)
        .put("hasCommands", cOut != null)
}
