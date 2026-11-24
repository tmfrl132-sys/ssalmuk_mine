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

    fun token(ctx: Context) = sp(ctx).getString("token", "")!!.trim()
    fun chatId(ctx: Context) = sp(ctx).getString("chat_id", "")!!.trim()
    fun label(ctx: Context) = sp(ctx).getString("label", "")!!.trim()
    fun enabled(ctx: Context) = sp(ctx).getBoolean("enabled", true)

    fun save(ctx: Context, token: String, chatId: String, label: String, enabled: Boolean) {
        sp(ctx).edit()
            .putString("token", token.trim())
            .putString("chat_id", chatId.trim())
            .putString("label", label.trim())
            .putBoolean("enabled", enabled)
            .apply()
    }

    fun configured(ctx: Context) = token(ctx).isNotEmpty() && chatId(ctx).isNotEmpty()

    /** 마지막으로 처리한 MMS _id. -1 = 아직 기준점 없음 */
    fun lastMmsId(ctx: Context) = sp(ctx).getLong("last_mms_id", -1L)
    fun setLastMmsId(ctx: Context, id: Long) {
        sp(ctx).edit().putLong("last_mms_id", id).commit()
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
