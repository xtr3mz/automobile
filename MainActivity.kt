package com.example.btgpscontrol

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvBluetoothState: TextView
    private lateinit var tvGpsState: TextView
    private lateinit var tvCountdownState: TextView
    private lateinit var etDelaySeconds: EditText
    private lateinit var btnRefresh: Button

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    // 倒计时任务
    private var countdownRunnable: Runnable? = null
    private var countdownSeconds = 0

    // 蓝牙广播接收器
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    // 蓝牙已连接 → 取消倒计时
                    cancelCountdown()
                    updateBluetoothUi()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    // 蓝牙已断开 → 开始倒计时
                    startCountdown()
                    updateBluetoothUi()
                }
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                    // 兜底：连接状态变化（部分设备可能不发 ACL 广播）
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        BluetoothAdapter.STATE_DISCONNECTED
                    )
                    if (state == BluetoothAdapter.STATE_CONNECTED) {
                        cancelCountdown()
                    } else if (state == BluetoothAdapter.STATE_DISCONNECTED) {
                        startCountdown()
                    }
                    updateBluetoothUi()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    // 蓝牙开关本身被关闭 → 视为断开
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR
                    )
                    if (state == BluetoothAdapter.STATE_OFF) {
                        startCountdown()
                    }
                    updateBluetoothUi()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvBluetoothState = findViewById(R.id.tvBluetoothState)
        tvGpsState = findViewById(R.id.tvGpsState)
        tvCountdownState = findViewById(R.id.tvCountdownState)
        etDelaySeconds = findViewById(R.id.etDelaySeconds)
        btnRefresh = findViewById(R.id.btnRefresh)

        // 初始化蓝牙
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // 初始化定位
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 请求权限
        requestPermissions()

        // 刷新按钮
        btnRefresh.setOnClickListener {
            updateBluetoothUi()
            updateGpsUi()
        }
    }

    override fun onResume() {
        super.onResume()
        registerBluetoothReceiver()
        updateBluetoothUi()
        updateGpsUi()
        // 启动后立即检查一次蓝牙状态，决定是否开始倒计时
        checkInitialBluetoothState()
    }

    override fun onPause() {
        super.onPause()
        unregisterBluetoothReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelCountdown()
        handler.removeCallbacksAndMessages(null)
    }

    // ---------- 权限请求 ----------

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, permissions.toTypedArray(), 100
            )
        }
    }

    // ---------- 蓝牙广播注册 ----------

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
    }

    private fun unregisterBluetoothReceiver() {
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) { }
    }

    // ---------- 蓝牙状态检测 ----------

    private fun isBluetoothConnected(): Boolean {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return false
        // 通过 A2DP 和 HEADSET 两个常用协议判断是否已连接
        val profiles = intArrayOf(
            BluetoothProfile.A2DP,
            BluetoothProfile.HEADSET
        )
        for (profile in profiles) {
            val state = bluetoothAdapter.getProfileConnectionState(profile)
            if (state == BluetoothProfile.STATE_CONNECTED) return true
        }
        return false
    }

    private fun checkInitialBluetoothState() {
        if (!isBluetoothConnected()) {
            startCountdown()
        } else {
            cancelCountdown()
        }
        updateBluetoothUi()
    }

    private fun updateBluetoothUi() {
        runOnUiThread {
            val connected = isBluetoothConnected()
            tvBluetoothState.text = if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                "蓝牙状态：蓝牙已关闭"
            } else if (connected) {
                "蓝牙状态：✅ 已连接"
            } else {
                "蓝牙状态：❌ 未连接"
            }
        }
    }

    // ---------- GPS 状态检测 ----------

    private fun isGpsEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    private fun updateGpsUi() {
        runOnUiThread {
            tvGpsState.text = if (isGpsEnabled()) {
                "GPS 状态：✅ 已开启"
            } else {
                "GPS 状态：❌ 已关闭"
            }
        }
    }

    // ---------- 倒计时逻辑 ----------

    /**
     * 读取用户设置的等待秒数（默认 30 秒）
     */
    private fun getDelaySeconds(): Int {
        val text = etDelaySeconds.text.toString().trim()
        val value = text.toIntOrNull() ?: 30
        return value.coerceIn(1, 600) // 限制 1~600 秒
    }

    private fun startCountdown() {
        cancelCountdown() // 先取消已有任务，避免重复
        countdownSeconds = getDelaySeconds()
        if (countdownSeconds <= 0) return

        tvCountdownState.text = "倒计时：${countdownSeconds} 秒后关闭 GPS"

        countdownRunnable = object : Runnable {
            override fun run() {
                if (countdownSeconds > 0) {
                    tvCountdownState.text = "倒计时：${countdownSeconds} 秒后关闭 GPS"
                    countdownSeconds--
                    handler.postDelayed(this, 1000)
                } else {
                    // 倒计时结束 → 执行关闭 GPS 操作
                    tvCountdownState.text = "倒计时：已结束，正在关闭 GPS…"
                    turnOffGps()
                }
            }
        }
        handler.postDelayed(countdownRunnable!!, 1000)
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        countdownSeconds = 0
        tvCountdownState.text = "倒计时：无"
    }

    // ---------- 关闭 GPS ----------

    /**
     * Android 10+ 无法通过代码直接关闭 GPS。
     * 这里跳转到系统的定位设置页面，引导用户手动关闭。
     */
    private fun turnOffGps() {
        updateGpsUi()
        if (isGpsEnabled()) {
            Toast.makeText(
                this,
                "倒计时结束，即将打开系统设置，请手动关闭 GPS",
                Toast.LENGTH_LONG
            ).show()
            try {
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "GPS 已经是关闭状态", Toast.LENGTH_SHORT).show()
        }
    }
}
