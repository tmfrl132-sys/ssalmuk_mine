package life.ssalmuk.smsfwd

/**
 * 긴 안내 문자(사은품·기프티콘 등)에서 핵심 줄만 뽑아 짧게 만든다.
 * 안드로이드 의존성 없음 → 단위 테스트 가능.
 *
 * 우선순위: 쿠폰/핀/인증번호·코드·링크 > 상품명·유효기간·금액 > 안내 제목(도착/지급 등)·발신 회사
 * 버림: [주의사항]·[제한사항] 등 구간, 약관·환불·고객센터·통화료 같은 줄
 */
object Summarizer {
    private const val LINE_CAP = 50

    private val NOISE_SECTION = Regex("""^[\[【<(]?\s*(주의|유의|제한|참고|약관|사용\s*장소|사용처|이용\s*안내|안내\s*사항|유의\s*사항|기타)""")
    private val SECTION_HEADER = Regex("""^[\[【<].{1,15}[\]】>]$""")
    private val NOISE_WORDS = Regex("""보증|약관|환불|고객센터|통화료|수신거부|무료거부|080-?\d|초과하는|선불전자|재산에\s*해당|휴무|점심시간|문의""")

    private val DATE = Regex("""^\d{4}-\d{1,2}-\d{1,2}$""")

    /** 쿠폰·핀 코드처럼 보이는 토큰: 영숫자(하이픈 허용) 8자 이상, 숫자 3개 이상, 날짜 아님 */
    private fun hasCode(t: String) = t.split(Regex("""[^A-Za-z0-9-]+""")).any { tok ->
        tok.length >= 8 && tok.count { it.isDigit() } >= 3 && !DATE.matches(tok)
    }
    private val URL = Regex("""https?://\S+|[a-z0-9.-]+\.(?:kr|com|me|ly|co)/\S+""", RegexOption.IGNORE_CASE)
    private val P3 = Regex("""쿠폰\s*번호|핀\s*번호|PIN|인증\s*번호|교환\s*번호|바코드\s*번호|주문\s*번호|기프티콘\s*번호|코드\s*[:：]""", RegexOption.IGNORE_CASE)
    private val P2 = Regex("""유효\s*기간|만료|상품명|금액|원권|포인트|캐시|요금|청구|납부""")
    private val P1 = Regex("""도착|당첨|지급|적립|발송|혜택|개통|해지|선물""")
    private val BRAND = Regex("""(?:모바일|텔레콤|통신|엠모바일|헬로|유플러스|KT|SK|LG).*입니다""")

    /** 앞에 붙는 장식 제거 */
    private fun clean(line: String): String =
        line.trim()
            .replace(Regex("""^\[Web발신]\s*"""), "")
            .replace(Regex("""^(?:[*\-·•■□▶▷◆◇※★☆>]+|\d+[.)])\s*"""), "")
            .replace(Regex("""^안녕하세요[.!]?\s*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun cap(s: String) = if (s.length > LINE_CAP) s.take(LINE_CAP - 1) + "…" else s

    /** 전체를 정리만 한 버전 (빈 줄·[Web발신] 제거) */
    fun compact(body: String): String =
        body.lines().map { clean(it) }.filter { it.isNotEmpty() }.joinToString("\n")

    /**
     * @param maxChars 요약 본문 최대 글자 수 (꼬리표 포함)
     * @param images 첨부 이미지 수 (꼬리표에 표시)
     */
    fun summarize(body: String, maxChars: Int, images: Int = 0): String {
        val tail = buildString {
            append("(요약·원문 ${body.length}자")
            if (images > 0) append("·이미지 ${images}개")
            append(")")
        }
        val budget = maxChars - tail.length - 1

        data class Cand(val idx: Int, val text: String, val prio: Int)

        val cands = mutableListOf<Cand>()
        var inNoise = false
        body.lines().forEachIndexed { i, raw ->
            val t = clean(raw)
            if (t.isEmpty()) return@forEachIndexed
            if (NOISE_SECTION.containsMatchIn(t) && (SECTION_HEADER.matches(t) || t.length <= 12)) {
                inNoise = true; return@forEachIndexed
            }
            if (SECTION_HEADER.matches(t)) { inNoise = false; return@forEachIndexed }
            // 주의사항 구간은 "* 상품명:" 같은 별표 항목이 나오면 끝난 것으로 봄
            if (inNoise && raw.trimStart().startsWith("*") && (P3.containsMatchIn(t) || P2.containsMatchIn(t))) inNoise = false
            if (inNoise) return@forEachIndexed
            if (NOISE_WORDS.containsMatchIn(t)) return@forEachIndexed

            val prio = when {
                P3.containsMatchIn(t) || hasCode(t) || URL.containsMatchIn(t) -> 3
                P2.containsMatchIn(t) -> 2
                P1.containsMatchIn(t) || (i < 4 && BRAND.containsMatchIn(t)) -> 1
                else -> 0
            }
            if (prio > 0) cands += Cand(i, if (prio == 3) t else cap(t), prio)
        }

        val picked = mutableListOf<Cand>()
        var used = 0
        for (c in cands.sortedWith(compareByDescending<Cand> { it.prio }.thenBy { it.idx })) {
            val cost = c.text.length + 1
            if (used + cost <= budget) { picked += c; used += cost }
        }

        val text = if (picked.isEmpty()) {
            compact(body).replace("\n", " ").take(budget.coerceAtLeast(10))
        } else {
            picked.sortedBy { it.idx }.joinToString("\n") { it.text }
        }
        return "$text\n$tail"
    }
}
