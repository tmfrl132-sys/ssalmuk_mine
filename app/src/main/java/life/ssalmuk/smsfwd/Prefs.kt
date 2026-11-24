package life.ssalmuk.smsfwd

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 설정·상태 저장 (앱 전용 저장소. 백업 꺼둠) */
object Prefs {
    private const val NAME = "fwd"
    private const val MAX_LOG = 40

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 전달받을 메인폰 번호 */
    fun target(ctx: Context) = sp(ctx).getString("target", "")!!.trim()
    fun label(ctx: Context) = sp(ctx).getString("label", "")!!.trim()
    fun enabled(ctx: Context) = sp(ctx).getBoolean("enabled", true)

    fun save(ctx: Context, target: String, label: String, enabled: Boolean) {
        sp(ctx).edit()
            .putString("target", target.filter { it.isDigit() || it == '+' })
            .putString("label", label.trim())
            .putBoolean("enabled", enabled)
            .apply()
    }

    fun configured(ctx: Context) = target(ctx).length >= 8

    /** 마지막으로 처리한 MMS _id. -1 = 아직 기준점 없음 */
    fun lastMmsId(ctx: Context) = sp(ctx).getLong("last_mms_id", -1L)
    fun setLastMmsId(ctx: Context, id: Long) {
        sp(ctx).edit().putLong("last_mms_id", id).commit()
    }

    /** 분할 문자는 조각마다 실패 결과가 와서, 한 번만 재시도하도록 처리 여부 기록. 처음이면 true */
    @Synchronized
    fun markFailed(ctx: Context, key: String): Boolean {
        val done = sp(ctx).getString("failed_keys", "")!!.split(",").filter { it.isNotBlank() }
        if (key in done) return false
        sp(ctx).edit().putString("failed_keys", (done.takeLast(30) + key).joinToString(",")).commit()
        return true
    }

    /** 로그에는 문자 본문을 남기지 않음 (발신번호·결과만) */
    @Synchronized
    fun log(ctx: Context, line: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        val old = sp(ctx).getString("log", "")!!.lines().filter { it.isNotBlank() }
        val merged = (listOf("$ts  $line") + old).take(MAX_LOG).joinToString("\n")
        sp(ctx).edit().putString("log", merged).apply()
    }

    fun logText(ctx: Context) = sp(ctx).getString("log", "")!!
}
