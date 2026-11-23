# ssalmuk_mine — 쌀먹 회선 관리기

ssalmuk.life 이벤트 페이지의 요금제·사은품 데이터를 가져와서, 내 회선별로
**가입일·유지기간 기준 월별 요금 / 사은품 / 순이익**을 계산한다. (Python 3 표준 라이브러리만 사용)

## 사용법

```bash
# 1) 이벤트 스냅샷 저장 + 요금제 이름 확인
python3 ssalmuk.py fetch https://ssalmuk.life/events/hellomobile/8231/2026-11-09

# 2) 회선 등록 (요금제 이름은 fetch 출력 그대로)
python3 ssalmuk.py add --name 폰1 --url https://ssalmuk.life/events/hellomobile/8231/2026-11-09 \
  --plan "현대홈쇼핑 유심 7GB" --join 2026-11-12 --hold 8
#   해지일을 알면 --hold 대신 --cancel 2027-06-23

# 3) 리포트 / 다가오는 일정
python3 ssalmuk.py report --out reports/report.md   # --cash-only: Hmall 적립금 등 제외
python3 ssalmuk.py upcoming --days 45
```

회선은 `lines.json`에 저장됨. 직접 편집도 가능 (`lines.example.json` 참고).

| 필드 | 설명 |
| --- | --- |
| `name` | 회선 이름 (폰1, 탭1 …). 전화번호는 넣지 말 것 |
| `event_url` | 가입한 ssalmuk.life 이벤트 URL |
| `plan` | 요금제 이름 |
| `join_date` | 개통일 |
| `hold_months` / `cancel_date` | 유지 개월 수 또는 실제(예정) 해지일 |
| `exclude_gifts` | 못 받는 사은품 이름 일부 (예: `["Hmall", "친구추천"]`) |
| `memo` | 메모 |

## 계산 규칙 (사이트와 동일, 125개 조합 검증 일치)

- 가입월·해지월 요금은 일할 (월요금 × 사용일수 / 그 달 일수, 반올림)
- `hold_months`만 주면 해지일 = 마지막 달의 마지막 지급일(보통 23일)
- 해지월 사은품은 지급일 ≤ 해지일일 때만 인정
- 지급일이 고정 날짜인 사은품은 사은품 목록의 날짜 사용

## 한계 / 리스크

- **사이트는 항상 "오늘 가입" 기준으로 계산함.** 예전에 가입한 회선은 이벤트 페이지가 살아있을 때
  스냅샷(`data/events/`)을 떠 둬야 정확. 스냅샷은 한 번 저장되면 재사용 (`fetch --force`로 갱신).
- 사은품 월차(M+k)는 가입월 기준 상대값. "26년 1월부터 매월 23일"처럼 절대 날짜로 묶인 사은품은
  가입월이 사이트 기준월과 다르면 1개월 어긋날 수 있음 → 공식 유의사항 확인.
- 사이트 합계에는 Hmall 적립금(라이프케어)도 현금처럼 포함됨.
- 개통 기간 조건(예: 5만원 추가증정은 11/2~11/15 개통만)은 자동 반영 안 됨 → `exclude_gifts`로 제외.
