package com.wifiaudio.sender

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wifiaudio.sender.databinding.ActivityMainBinding
import android.text.style.ForegroundColorSpan

class MainActivity : AppCompatActivity() {

    data class Target(val host: String, val port: Int, val name: String, val code: String)

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private var discovery: Discovery? = null
    private var receivers: List<Discovery.ReceiverInfo> = emptyList()
    private var pending: Target? = null
    private var searchStartedAt = 0L
    private var localError = ""

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasAudioPermission()) requestProjection()
            else fail("Audio permission was denied. Android requires it to capture system audio.")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val target = pending
            pending = null
            val data = r.data
            if (target != null && r.resultCode == Activity.RESULT_OK && data != null) {
                startStreaming(target, r.resultCode, data)
            } else {
                fail("Audio capture was not allowed, so nothing can be streamed.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        if (StreamStatus.state == StreamState.IDLE) VolumeGuard.restore(this, prefs)

        b.inputCode.setText(prefs.code)
        b.inputIp.setText(prefs.manualIp)
        b.switchAuto.isChecked = prefs.autoReconnect
        b.switchMute.isChecked = prefs.muteLocal
        b.switchAuto.setOnCheckedChangeListener { _, v -> prefs.autoReconnect = v }
        b.switchMute.setOnCheckedChangeListener { _, v -> prefs.muteLocal = v }

        b.spinnerReceivers.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updateReceiverIp()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        b.btnConnect.setOnClickListener {
            val r = receivers.getOrNull(b.spinnerReceivers.selectedItemPosition)
            if (r == null) {
                fail("No receiver selected. Wait for your PC to appear, or use “Can't find your PC?”.")
            } else {
                beginConnect(Target(r.ip, r.port, r.name, b.inputCode.text.toString().trim()))
            }
        }
        b.btnCantFind.setOnClickListener { b.panelManual.visibility = View.VISIBLE }
        b.btnManualConnect.setOnClickListener {
            val ip = b.inputIp.text.toString().trim()
            if (ip.isEmpty()) { fail("Enter your PC's IP address, e.g. 192.168.1.100."); return@setOnClickListener }
            prefs.manualIp = ip
            beginConnect(Target(ip, Protocol.DEFAULT_TCP_PORT, ip, b.inputCode.text.toString().trim()))
        }
        b.btnDisconnect.setOnClickListener {
            startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
        }
    }

    override fun onStart() {
        super.onStart()
        searchStartedAt = System.currentTimeMillis()
        StreamStatus.listener = { render() }
        discovery = Discovery(applicationContext, deviceName(),
            { StreamStatus.state != StreamState.IDLE },
            { list -> receivers = list; updateReceiverList() },
            { info, code -> onInvite(info, code) }).also { it.start() }
        ui.postDelayed({ render() }, 8000)      // reveals the "No receiver found" help if still empty
        render()
    }

    override fun onStop() {
        super.onStop()
        StreamStatus.listener = null
        discovery?.stop(); discovery = null
    }

    // ---------- connect flow: permissions -> capture consent -> service ----------

    private fun onInvite(info: Discovery.ReceiverInfo, code: String) {
        if (StreamStatus.state != StreamState.IDLE || pending != null) return
        Toast.makeText(this, "${info.name} wants to connect", Toast.LENGTH_SHORT).show()
        b.inputCode.setText(code)
        beginConnect(Target(info.ip, info.port, info.name, code))
    }

    private fun beginConnect(t: Target) {
        if (StreamStatus.state != StreamState.IDLE) return
        localError = ""
        if (t.code.isEmpty()) { fail("Enter the pairing code shown on your PC."); return }
        prefs.code = t.code
        pending = t
        if (hasAudioPermission()) { requestProjection(); return }
        AlertDialog.Builder(this)
            .setTitle("Audio permission")
            .setMessage("Android requires the “microphone” permission for any app that captures system audio. " +
                "This app captures what your phone is playing — it never records the microphone.")
            .setPositiveButton("Continue") { _, _ ->
                val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                permissionLauncher.launch(perms.toTypedArray())
            }
            .setNegativeButton("Cancel") { _, _ -> pending = null }
            .show()
    }

    private fun requestProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startStreaming(t: Target, resultCode: Int, data: Intent) {
        val intent = Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_HOST, t.host)
            putExtra(StreamService.EXTRA_PORT, t.port)
            putExtra(StreamService.EXTRA_NAME, t.name)
            putExtra(StreamService.EXTRA_CODE, t.code)
            putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun fail(msg: String) { pending = null; localError = msg; render() }

    // ---------- UI ----------

    private fun updateReceiverList() {
        val labels = receivers.map { it.name }
        val previous = receivers.getOrNull(b.spinnerReceivers.selectedItemPosition)?.ip
        b.spinnerReceivers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val preferred = listOfNotNull(previous, prefs.lastReceiverIp.ifEmpty { null })
            .firstNotNullOfOrNull { ip -> receivers.indexOfFirst { it.ip == ip }.takeIf { it >= 0 } }
        if (preferred != null) b.spinnerReceivers.setSelection(preferred)
        render()
    }

    private fun updateReceiverIp() {
        b.txtReceiverIp.text = receivers.getOrNull(b.spinnerReceivers.selectedItemPosition)?.ip ?: ""
    }

    private fun dot(text: String, colorRes: Int): SpannableString {
        val s = SpannableString("● $text")
        s.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, colorRes)), 0, 1, 0)
        return s
    }

    private fun render() {
        if (!::b.isInitialized) return
        val st = StreamStatus.state
        val active = st != StreamState.IDLE
        b.panelIdle.visibility = if (active) View.GONE else View.VISIBLE
        b.panelStreaming.visibility = if (active) View.VISIBLE else View.GONE

        when (st) {
            StreamState.STREAMING -> b.txtStatus.text = dot("Connected", R.color.green)
            StreamState.CONNECTING -> b.txtStatus.text = dot("Connecting…", R.color.amber)
            StreamState.RECONNECTING -> b.txtStatus.text = dot("Connection lost.\nTrying to reconnect...", R.color.amber)
            StreamState.IDLE -> b.txtStatus.text = dot("Not connected", R.color.amber)
        }
        b.txtStreamReceiver.text = StreamStatus.receiverName
        b.txtStreamIp.text = StreamStatus.host

        val found = receivers.isNotEmpty()
        b.txtSearching.visibility = if (!found && !longSearch()) View.VISIBLE else View.GONE
        b.txtNoReceiver.visibility = if (!found && longSearch()) View.VISIBLE else View.GONE
        b.spinnerReceivers.visibility = if (found) View.VISIBLE else View.GONE
        b.txtReceiverIp.visibility = if (found) View.VISIBLE else View.GONE
        b.btnConnect.isEnabled = found
        if (!found && longSearch()) b.panelManual.visibility = View.VISIBLE
        updateReceiverIp()

        val err = localError.ifEmpty { if (st == StreamState.IDLE) StreamStatus.message else "" }
        b.txtError.text = err
        b.txtError.visibility = if (err.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun longSearch() = System.currentTimeMillis() - searchStartedAt > 8000

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
