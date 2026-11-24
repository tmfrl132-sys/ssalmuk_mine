package life.ssalmuk.smsfwd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizerTest {
    private val budget = 160

    private val umobile = """
[Web발신]
안녕하세요. 유플러스 유모바일입니다.

이츠 요금제를 이용하시는 고객님께 드리는 기본 혜택(쿠팡이츠 5천원 기프트카드)이 도착했습니다!

* 상품명: 쿠팡 캐시 교환권 5천원권
* 쿠폰번호: 69QFW2HZNP3E8JJ4
* 유효기간: 2026.09.16 ~ 2026.11.15
[주의사항]
- 본 교환권은 기프트카드 등록화면에서 쿠팡캐시로 교환하여 사용할 수 있습니다.
- 본 교환권은 [유통사]가 발행한 선불전자지급수단 입니다.
- 등록된 쿠팡캐시는 5년 동안 쿠팡 및 쿠팡이츠에서 사용할 수 있습니다.
- 이미 보유 중인 쿠팡캐시 및 교환된 쿠팡캐시의 합이 50만원을 초과하는 경우, 50만원을 초과하는 부분은 예치되었다가, 쿠팡캐시 사용에 따라 예치된 금액은 쿠팡캐시로 자동 전환 됩니다.
- [유통사]는 본 교환권의 구매금액에 대하여 지급보증보험 가입 등 방법으로 선불충전금 보호 조치를 취하고 있고, 이에 따른 선불충전금은 이용자 재산에 해당합니다
- 기타 본 기프트카드 관련 환불 등 조건은 쿠팡페이 상품권 이용 약관에 따릅니다.

[제한사항]
- 쿠팡캐시 1회 사용최대액은 50만원으로 제한됩니다.
[사용장소]
쿠팡 APP
(등록 경로 : 쿠팡APP>마이쿠팡>쿠팡캐시/기프트카드/제휴포인트>기프트카드 등록하기>쿠폰번호 입력 후 등록)

* 지급보증 : 본 상품은 별도의 지급보증 및 피해보상 보험계약체결 없이 자체 신용으로 발행되었습니다. 
* 스퀘어노트 고객센터 평일 10:00~17:00 / 점심시간 11시 30분~13시 / 토,일 및 공휴일 휴무
* 문의번호: 070-4480-7400(스퀘어노트 고객센터 / 통화료 발생)
""".trimIndent()

    private val gifticon = """
[Web발신]
[헬로모바일] 개통 감사 사은품 안내
고객님, 신세계상품권 모바일교환권이 발송되었습니다.
상품명 : 신세계 모바일상품권 3만원
교환권번호 : 9012-3456-7890-12
유효기간 : 2027-11-23 까지
※ 유의사항
- 본 상품은 현금 환불이 불가합니다.
- 유효기간 연장은 고객센터 1588-0000 로 문의
무료수신거부 080-123-4567
""".trimIndent()

    private fun check(name: String, body: String, vararg must: String) {
        val s = Summarizer.summarize(body, budget, images = 1)
        println("---- $name (${s.length}자) ----\n$s")
        assertTrue("길이 초과: ${s.length}", s.length <= budget)
        must.forEach { assertTrue("'$it' 누락", s.contains(it)) }
    }

    @Test fun umobileCoupon() {
        check("유모바일 쿠팡", umobile, "69QFW2HZNP3E8JJ4", "2026.11.15", "쿠팡 캐시 교환권 5천원권")
        val s = Summarizer.summarize(umobile, budget)
        assertFalse(s.contains("50만원"))
        assertFalse(s.contains("070-4480"))
    }

    @Test fun shinsegaeGifticon() {
        check("헬로 신세계", gifticon, "9012-3456-7890-12", "2027-11-23", "3만원")
        val s = Summarizer.summarize(gifticon, budget)
        assertFalse(s.contains("1588-0000"))
        assertFalse(s.contains("080-123"))
    }

    @Test fun nothingMatchesFallsBackToHead() {
        val s = Summarizer.summarize("가".repeat(500), budget)
        assertTrue(s.length <= budget)
        assertTrue(s.startsWith("가가가"))
        assertTrue(s.contains("(앞부분·원문 500자)"))
    }

    /** 회사명·'안내' 제목만 걸리고 번호·상품명 같은 핵심이 없는 공지 → 앞부분 전송 */
    @Test fun onlyHeadlineMatchedSendsHead() {
        val notice = """
[Web발신]
안녕하세요. KT엠모바일입니다.
12월 3일(수) 02:00~06:00 시스템 정기 점검으로 셀프개통, 마이페이지 이용이 일시 중단됩니다.
점검 시간 동안 유심 교체, 번호 이동 신청은 진행되지 않으니 이용에 참고 부탁드립니다.
더 나은 서비스로 보답하겠습니다. 감사합니다.
※ 유의사항
- 점검 시간은 상황에 따라 변경될 수 있습니다.
""".trimIndent()
        val s = Summarizer.summarize(notice, budget)
        println("---- 점검 공지 (${s.length}자) ----\n$s")
        assertTrue(s.length <= budget)
        assertTrue(s.contains("12월 3일(수) 02:00~06:00"))
        assertTrue(s.contains("앞부분"))
    }

    @Test fun compactDropsWebTag() {
        val c = Summarizer.compact("[Web발신]\n인증번호 [123456] 입니다.\n\n")
        assertTrue(c == "인증번호 [123456] 입니다.")
    }
}
