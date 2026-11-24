package life.ssalmuk.smsfwd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** 일반 문자(SMS, 단문). 분할 수신된 조각은 발신번호별로 합침 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        val subId = intent.getIntExtra("subscription", intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1))
        var slot = intent.getIntExtra("android.telephony.extra.SLOT_INDEX", intent.getIntExtra("slot", -1))
        if (slot < 0) slot = Forwarder.slotOfSub(subId)

        val texts = parts.groupBy { it.displayOriginatingAddress ?: "알 수 없음" }.map { (sender, list) ->
            sender to Forwarder.format(
                ctx, "SMS", sender, list.first().timestampMillis, slot,
                list.joinToString("") { it.displayMessageBody ?: "" },
            )
        }

        texts.forEach { (sender, text) -> Forwarder.forward(ctx, sender, subId, text) }
    }
}
