package life.ssalmuk.smsfwd

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object Forwarder {
    /** 분할 문자 최대 조각 수. 한글은 조각당 약 67자, 조각마다 문자 1건으로 과금 */
    const val MAX_PARTS = 10
    /** 이 길이(문자 2건) 이하면 원문 그대로, 넘으면 요약 */
    private const val FULL_LIMIT = 67 * 2
    /** 요약본 최대 길이 (문자 3건) */
    private const val SUMMARY_LIMIT = 67 * 3
    private const val MAX_RETRY = 5
    private const val MAX_INPUT = 3000

    /**
     * 문자 건수를 아끼려고 머리말은 한 줄로 짧게.
     * 요약 켜짐: 2건 안에 들어가면 원문, 넘으면 핵심 줄만 뽑아 3건 이내로.
     */
    fun format(
        ctx: Context, kind: String, sender: String, tsMillis: Long, slot: Int, body: String, images: Int = 0,
    ): String {
        val label = Prefs.label(ctx).ifEmpty { Build.MODEL }
        val sim = if (slot >= 0) "/SIM${slot + 1}" else ""
        val k = if (kind == "SMS") "" else " $kind"
        val time = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(tsMillis))
        val header = "[$label$sim$k] $sender $time"

        var full = Summarizer.compact(body).ifEmpty { "(본문 없음)" }
        if (images > 0) full += "\n(이미지 ${images}개, 서브폰 확인)"
        val content = if (!Prefs.summarize(ctx) || header.length + 1 + full.length <= FULL_LIMIT) full
        else Summarizer.summarize(body, SUMMARY_LIMIT - header.length - 1, images)
        return "$header\n$content".take(MAX_INPUT)
    }

    fun slotOfSub(subId: Int): Int {
        if (subId < 0 || Build.VERSION.SDK_INT < 29) return -1
        return SubscriptionManager.getSlotIndex(subId)
    }

    /** 받은 문자를 메인폰으로 전달. subId = 문자를 받은 SIM (그 SIM으로 발송) */
    fun forward(ctx: Context, sender: String, subId: Int, text: String) {
        if (!Prefs.enabled(ctx)) return
        if (!Prefs.configured(ctx)) { Prefs.log(ctx, "받을 번호 미설정  $sender"); return }
        if (samePhone(sender, Prefs.target(ctx))) { Prefs.log(ctx, "메인폰 발신 문자 건너뜀"); return }
        send(ctx, Prefs.target(ctx), subId, text, 0, sender)
    }

    private fun digits(n: String): String {
        val d = n.filter { it.isDigit() }
        return if (d.startsWith("82")) "0" + d.drop(2) else d
    }

    private fun samePhone(a: String, b: String): Boolean {
        val x = digits(a); val y = digits(b)
        return x.length >= 8 && y.length >= 8 && x.takeLast(8) == y.takeLast(8)
    }

    private fun smsManager(ctx: Context, subId: Int): SmsManager {
        val valid = subId >= 0 && (Build.VERSION.SDK_INT < 29 || SubscriptionManager.isValidSubscriptionId(subId))
        return if (Build.VERSION.SDK_INT >= 31) {
            val base = ctx.getSystemService(SmsManager::class.java)
            if (valid) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (valid) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    private val reqCode = AtomicInteger((System.currentTimeMillis() and 0xFFFFFF).toInt())

    fun send(ctx: Context, dest: String, subId: Int, text: String, attempt: Int, who: String) {
        try {
            val sm = smsManager(ctx, subId)
            var parts = sm.divideMessage(text)
            if (parts.size > MAX_PARTS) {
                val suffix = "\n…(생략, 서브폰 확인)"
                var len = text.length
                while (parts.size > MAX_PARTS && len > 0) {
                    len = len * 9 / 10
                    parts = sm.divideMessage(text.take(len) + suffix)
                }
            }
            val key = "${System.currentTimeMillis()}-${reqCode.get()}"
            val sent = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                val intent = Intent(ctx, SentReceiver::class.java)
                    .putExtra("dest", dest).putExtra("sub", subId).putExtra("text", text)
                    .putExtra("attempt", attempt).putExtra("who", who).putExtra("key", key)
                    .putExtra("part", i).putExtra("total", parts.size)
                sent.add(
                    PendingIntent.getBroadcast(
                        ctx, reqCode.incrementAndGet(), intent,
                        (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_ONE_SHOT,
                    )
                )
            }
            if (parts.size == 1) sm.sendTextMessage(dest, null, parts[0], sent[0], null)
            else sm.sendMultipartTextMessage(dest, null, parts, sent, null)
        } catch (e: Exception) {
            Prefs.log(ctx, "발송 오류  $who  ${e.javaClass.simpleName}")
            scheduleRetry(ctx, dest, subId, text, attempt + 1, who)
        }
    }

    /** 2, 4, 8, 16, 32분 뒤 재시도 */
    fun scheduleRetry(ctx: Context, dest: String, subId: Int, text: String, attempt: Int, who: String) {
        if (attempt > MAX_RETRY) { Prefs.log(ctx, "재시도 포기  $who"); return }
        val data = Data.Builder()
            .putString("dest", dest).putInt("sub", subId).putString("text", text)
            .putInt("attempt", attempt).putString("who", who).build()
        val req = OneTimeWorkRequestBuilder<RetryWorker>()
            .setInitialDelay(1L shl attempt, TimeUnit.MINUTES)
            .setInputData(data)
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    class RetryWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result {
            val d = inputData
            send(
                applicationContext, d.getString("dest") ?: return Result.failure(), d.getInt("sub", -1),
                d.getString("text") ?: return Result.failure(), d.getInt("attempt", 1), d.getString("who") ?: "",
            )
            return Result.success()
        }
    }
}

/** 발송 결과. 실패(서비스 지역 아님 등)면 재시도 예약 */
class SentReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val who = intent.getStringExtra("who") ?: ""
        val attempt = intent.getIntExtra("attempt", 0)
        if (resultCode == Activity.RESULT_OK) {
            if (intent.getIntExtra("part", 0) == intent.getIntExtra("total", 1) - 1) {
                Prefs.log(ctx, (if (attempt > 0) "재시도 성공  " else "전달 성공  ") + who)
            }
            return
        }
        if (!Prefs.markFailed(ctx, intent.getStringExtra("key") ?: "")) return
        Prefs.log(ctx, "발송 실패(코드 $resultCode)  $who")
        Forwarder.scheduleRetry(
            ctx, intent.getStringExtra("dest") ?: return, intent.getIntExtra("sub", -1),
            intent.getStringExtra("text") ?: return, attempt + 1, who,
        )
    }
}
