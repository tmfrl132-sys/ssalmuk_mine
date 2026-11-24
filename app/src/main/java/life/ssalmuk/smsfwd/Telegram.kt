package life.ssalmuk.smsfwd

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Telegram {
    sealed class Result {
        object Ok : Result()
        /** 네트워크 오류·429·5xx → 나중에 재시도 */
        data class Retry(val msg: String) : Result()
        /** 토큰/chat_id 오류 등 → 재시도해도 소용없음 */
        data class Fail(val msg: String) : Result()
    }

    private const val MAX_LEN = 4000 // 텔레그램 한도 4096자

    fun send(token: String, chatId: String, text: String): Result {
        if (token.isEmpty() || chatId.isEmpty()) return Result.Fail("토큰/chat_id 미설정")
        val body = if (text.length > MAX_LEN) text.take(MAX_LEN) + "\n…(잘림)" else text
        val form = listOf(
            "chat_id" to chatId,
            "text" to body,
            "disable_web_page_preview" to "true",
        ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

        return try {
            val conn = URL("https://api.telegram.org/bot$token/sendMessage").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            when {
                code == 200 -> Result.Ok
                code == 429 || code >= 500 -> Result.Retry("HTTP $code")
                else -> Result.Fail("HTTP $code ${describe(resp)}")
            }
        } catch (e: Exception) {
            Result.Retry(e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    /** 봇에게 마지막으로 말 건 대화의 chat_id 찾기 */
    fun findChatId(token: String): String {
        val conn = URL("https://api.telegram.org/bot$token/getUpdates").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val code = conn.responseCode
        val resp = (if (code < 400) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code != 200) throw IllegalStateException("HTTP $code ${describe(resp)}")
        val results = JSONObject(resp).getJSONArray("result")
        for (i in results.length() - 1 downTo 0) {
            val u = results.getJSONObject(i)
            val msg = u.optJSONObject("message") ?: u.optJSONObject("channel_post") ?: continue
            return msg.getJSONObject("chat").get("id").toString()
        }
        throw IllegalStateException("봇에게 받은 메시지가 없음. 텔레그램에서 봇에게 아무 말이나 보낸 뒤 다시 시도")
    }

    private fun describe(resp: String) = try {
        JSONObject(resp).optString("description")
    } catch (e: Exception) {
        resp.take(100)
    }
}
