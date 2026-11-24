package life.ssalmuk.smsfwd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * 한국은 장문(LMS, 한글 약 45자 초과)이 MMS로 옴 → SMS_RECEIVED로는 안 잡힘.
 * 기본 문자앱이 아니면 MMS 본문을 직접 받을 수 없으므로,
 * 도착 알림(WAP Push)만 받고 → 기본 문자앱이 다운로드를 끝낸 뒤 → 문자 DB에서 읽어 전달.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        MmsScanner.scheduleBurst(ctx)
    }
}

object MmsScanner {
    private const val MSG_TYPE_NOTIFICATION_IND = 130 // 아직 다운로드 안 된 MMS
    private const val ADDR_TYPE_FROM = 137

    /** 도착 직후 10초 뒤부터, 새 글이 없으면 20초 간격으로 몇 번 더 확인 */
    fun scheduleBurst(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInitialDelay(10, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 20, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("mms-burst", ExistingWorkPolicy.REPLACE, req)
    }

    /** 알림을 놓쳤을 때 대비한 15분 주기 확인 */
    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<PeriodicWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("mms-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    class ScanWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
        override fun doWork(): Result {
            val r = scan(applicationContext)
            // 새 MMS를 못 찾았거나 다운로드 중이면 재확인 (최대 6번 ≈ 3분)
            return if ((r.forwarded == 0 || r.pending) && runAttemptCount < 6) Result.retry() else Result.success()
        }
    }

    class PeriodicWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
        override fun doWork(): Result {
            scan(applicationContext); return Result.success()
        }
    }

    data class ScanResult(val forwarded: Int, val pending: Boolean)

    /** 현재 가장 큰 MMS id를 기준점으로 저장 (설치 전 문자는 보내지 않기 위함) */
    fun initBaseline(ctx: Context) {
        if (Prefs.lastMmsId(ctx) >= 0) return
        try {
            ctx.contentResolver.query(Uri.parse("content://mms"), arrayOf("_id"), null, null, "_id DESC")?.use {
                Prefs.setLastMmsId(ctx, if (it.moveToFirst()) it.getLong(0) else 0L)
            }
        } catch (e: SecurityException) {
            // 권한 아직 없음
        }
    }

    @Synchronized
    fun scan(ctx: Context): ScanResult {
        if (!Prefs.enabled(ctx) || !Prefs.configured(ctx)) return ScanResult(0, false)
        if (Prefs.lastMmsId(ctx) < 0) { initBaseline(ctx); return ScanResult(0, false) }

        var last = Prefs.lastMmsId(ctx)
        var forwarded = 0
        var pending = false
        try {
            val cr = ctx.contentResolver
            cr.query(
                Uri.parse("content://mms/inbox"),
                arrayOf("_id", "date", "m_type", "sub", "sub_cs", "sub_id"),
                "_id > ?", arrayOf(last.toString()), "_id ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (c.getInt(2) == MSG_TYPE_NOTIFICATION_IND) { pending = true; break } // 다운로드 대기
                    val dateSec = c.getLong(1)
                    val subject = decodeSubject(c.getString(3), if (c.isNull(4)) 0 else c.getInt(4))
                    val subId = if (c.getColumnIndex("sub_id") >= 0 && !c.isNull(5)) c.getInt(5) else -1

                    val sender = senderOf(ctx, id)
                    val (text, images) = bodyOf(ctx, id)
                    val body = if (subject.isNotBlank() && !text.contains(subject)) "$subject\n$text" else text
                    val kind = if (images > 0) "MMS" else "LMS"
                    Forwarder.forward(
                        ctx, sender, subId,
                        Forwarder.format(ctx, kind, sender, dateSec * 1000, Forwarder.slotOfSub(subId), body, images),
                    )
                    last = id
                    Prefs.setLastMmsId(ctx, last)
                    forwarded++
                }
            }
        } catch (e: SecurityException) {
            Prefs.log(ctx, "MMS 읽기 권한 없음")
        } catch (e: Exception) {
            Prefs.log(ctx, "MMS 읽기 오류  ${e.javaClass.simpleName}")
        }
        return ScanResult(forwarded, pending)
    }

    private fun senderOf(ctx: Context, id: Long): String {
        ctx.contentResolver.query(
            Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"),
            "type = ?", arrayOf(ADDR_TYPE_FROM.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val a = c.getString(0)
                if (!a.isNullOrBlank() && a != "insert-address-token") return a
            }
        }
        return "알 수 없음"
    }

    private fun bodyOf(ctx: Context, id: Long): Pair<String, Int> {
        val sb = StringBuilder()
        var images = 0
        ctx.contentResolver.query(
            Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text", "_data"),
            "mid = ?", arrayOf(id.toString()), "_id ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val ct = c.getString(1) ?: ""
                when {
                    ct == "text/plain" -> {
                        val t = if (!c.isNull(3)) {
                            ctx.contentResolver.openInputStream(Uri.parse("content://mms/part/${c.getLong(0)}"))
                                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                        } else c.getString(2) ?: ""
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(t)
                    }
                    ct.startsWith("image/") || ct.startsWith("video/") -> images++
                }
            }
        }
        return sb.toString() to images
    }

    /** MMS 제목은 원본 바이트가 ISO-8859-1로 저장되는 경우가 있어 문자셋에 맞게 다시 디코딩 */
    private fun decodeSubject(raw: String?, charset: Int): String {
        if (raw.isNullOrEmpty()) return ""
        if (raw.any { it.code > 0xFF }) return raw // 이미 정상 디코딩된 문자열
        val cs = when (charset) {
            106 -> Charsets.UTF_8
            36, 38 -> Charset.forName("EUC-KR")
            else -> return raw
        }
        return String(raw.toByteArray(Charsets.ISO_8859_1), cs)
    }
}
