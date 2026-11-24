package life.ssalmuk.smsfwd

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Forwarder {
    private const val KEY_TEXT = "text"
    private const val KEY_WHO = "who"

    fun format(ctx: Context, kind: String, sender: String, tsMillis: Long, slot: Int, body: String): String {
        val label = Prefs.label(ctx).ifEmpty { Build.MODEL }
        val sim = if (slot >= 0) " · SIM${slot + 1}" else ""
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(tsMillis))
        return "📩 $label$sim [$kind]\n보낸사람: $sender\n$time\n──────────\n$body"
    }

    fun slotOfSub(subId: Int): Int {
        if (subId < 0 || Build.VERSION.SDK_INT < 29) return -1
        return SubscriptionManager.getSlotIndex(subId)
    }

    /** 바로 보내보고, 실패하면 WorkManager로 넘겨 네트워크 잡히면 재시도 */
    fun sendNowOrQueue(ctx: Context, sender: String, text: String) {
        if (!Prefs.enabled(ctx)) return
        when (val r = Telegram.send(Prefs.token(ctx), Prefs.chatId(ctx), text)) {
            is Telegram.Result.Ok -> Prefs.log(ctx, "전달 성공  $sender")
            is Telegram.Result.Fail -> Prefs.log(ctx, "전달 실패  $sender  ${r.msg}")
            is Telegram.Result.Retry -> {
                Prefs.log(ctx, "대기열 등록  $sender  ${r.msg}")
                enqueue(ctx, sender, text)
            }
        }
    }

    private fun enqueue(ctx: Context, sender: String, text: String) {
        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(Data.Builder().putString(KEY_TEXT, text.take(4000)).putString(KEY_WHO, sender).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result {
            val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
            val who = inputData.getString(KEY_WHO) ?: ""
            return when (val r = Telegram.send(Prefs.token(applicationContext), Prefs.chatId(applicationContext), text)) {
                is Telegram.Result.Ok -> {
                    Prefs.log(applicationContext, "재시도 성공  $who"); Result.success()
                }
                is Telegram.Result.Fail -> {
                    Prefs.log(applicationContext, "재시도 실패(중단)  $who  ${r.msg}"); Result.failure()
                }
                is Telegram.Result.Retry -> if (runAttemptCount < 15) Result.retry() else {
                    Prefs.log(applicationContext, "재시도 포기  $who  ${r.msg}"); Result.failure()
                }
            }
        }
    }
}
