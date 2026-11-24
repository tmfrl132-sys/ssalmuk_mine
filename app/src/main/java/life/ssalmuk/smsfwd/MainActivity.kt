package life.ssalmuk.smsfwd

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val perms = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_MMS,
        Manifest.permission.SEND_SMS,
    )

    private lateinit var label: EditText
    private lateinit var target: EditText
    private lateinit var enabled: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        label = findViewById(R.id.label)
        target = findViewById(R.id.target)
        enabled = findViewById(R.id.enabled)

        label.setText(Prefs.label(this))
        target.setText(Prefs.target(this))
        enabled.isChecked = Prefs.enabled(this)

        findViewById<Button>(R.id.btnPerm).setOnClickListener { requestPermissions(perms, 1) }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { askBatteryExemption() }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save(); toast("저장됨") }
        findViewById<Button>(R.id.btnTest).setOnClickListener { sendTest() }
        enabled.setOnCheckedChangeListener { _, _ -> save() }

        MmsScanner.schedulePeriodic(this)
    }

    override fun onResume() {
        super.onResume()
        if (hasPerms()) MmsScanner.initBaseline(this)
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!hasPerms()) {
            toast("권한이 막혔으면: [앱 정보 열기] → 우측 상단 ⋮ → '제한된 설정 허용' → 다시 1번")
        } else {
            MmsScanner.initBaseline(this)
        }
        refresh()
    }

    private fun hasPerms() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun batteryOk() = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun askBatteryExemption() {
        if (batteryOk()) { toast("이미 예외 등록됨"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun save() {
        Prefs.save(this, target.text.toString(), label.text.toString(), enabled.isChecked)
        refresh()
    }

    private fun sendTest() {
        save()
        if (!hasPerms()) { toast("문자 권한부터 허용"); return }
        if (!Prefs.configured(this)) { toast("받을 번호부터 입력"); return }
        val text = Forwarder.format(this, "테스트", "문자전달", System.currentTimeMillis(), -1, "연결 확인용 테스트 문자")
        Forwarder.send(this, Prefs.target(this), -1, text, 0, "테스트")
        toast("테스트 문자 발송 요청함. 결과는 아래 기록에 표시")
        label.postDelayed({ refresh() }, 5000)
    }

    private fun refresh() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        findViewById<TextView>(R.id.status).text = buildString {
            append("${mark(hasPerms())} 문자 권한\n")
            append("${mark(batteryOk())} 배터리 최적화 예외\n")
            append("${mark(Prefs.configured(this@MainActivity))} 받을 번호 설정\n")
            append("${mark(Prefs.enabled(this@MainActivity))} 전달 켜짐")
        }
        findViewById<TextView>(R.id.log).text = Prefs.logText(this).ifEmpty { "(기록 없음)" }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
