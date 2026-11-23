#!/usr/bin/env python3
"""쌀먹 회선 관리기 — ssalmuk.life 이벤트 데이터로 내 회선별 요금·사은품·순이익을 계산한다.

사용법
  python3 ssalmuk.py fetch <이벤트URL>          이벤트 스냅샷 저장 (요금제 목록 출력)
  python3 ssalmuk.py add --name 이름 --url URL --plan 요금제 --join YYYY-MM-DD [--hold N | --cancel YYYY-MM-DD]
  python3 ssalmuk.py list                       등록된 회선 목록
  python3 ssalmuk.py report [--out 파일.md] [--cash-only]   전체 리포트
  python3 ssalmuk.py upcoming [--days 45]       다가오는 지급·해지 일정

표준 라이브러리만 사용한다.
"""
import argparse
import calendar
import datetime as dt
import html
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EVENT_DIR = ROOT / "data" / "events"
import os
LINES_FILE = Path(os.environ.get("SSALMUK_LINES", ROOT / "lines.json"))
URL_RE = re.compile(r"https?://ssalmuk\.life/events/([^/]+)/([^/]+)/(\d{4}-\d{2}-\d{2})")
NON_CASH_KINDS = ("라이프케어",)  # 사이트가 현금성으로 합산하지만 실제론 적립금인 종류


# ---------------------------------------------------------------- 날짜 유틸
def parse_date(s):
    return dt.date.fromisoformat(s)


def add_months(d, n):
    y, m = divmod(d.month - 1 + n, 12)
    return dt.date(d.year + y, m + 1, 1)


def dim(d):
    return calendar.monthrange(d.year, d.month)[1]


def months_between(a, b):
    return (b.year - a.year) * 12 + (b.month - a.month)


def won(n):
    return f"{n:+,}" if n < 0 else f"{n:,}"


def signed(n):
    return f"{n:+,}"


# ---------------------------------------------------------------- 수집
def event_key(url):
    m = URL_RE.match(url)
    if not m:
        raise SystemExit(f"ssalmuk.life 이벤트 URL 형식이 아님: {url}")
    return f"{m.group(1)}_{m.group(2)}_{m.group(3)}", m


def _json_const(src, name):
    m = re.search(r"const " + name + r" = (\{.*?\});\n", src, flags=re.S)
    return json.loads(m.group(1)) if m else None


def _text(s):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", s))).strip()


def parse_event(src, url):
    key, m = event_key(url)
    title = re.search(r"<title>(.*?)</title>", src, flags=re.S)
    desc = re.search(r'<meta name="description" content="([^"]*)"', src)
    period = re.search(r"(\d{4}-\d{2}-\d{2})~(\d{4}-\d{2}-\d{2})", desc.group(1) if desc else "")
    months = _json_const(src, "monthsByPlan")
    opts = _json_const(src, "optionsByPlan")
    if not months:
        raise SystemExit("페이지에서 monthsByPlan 데이터를 못 찾음 (사이트 구조 변경 가능성)")

    # 사은품 목록 표: 이름 → 지급시작/대상/종류
    catalog = []
    cat = re.search(r'<table class="catalog">(.*?)</table>', src, flags=re.S)
    if cat:
        for row in re.findall(r"<tr>(.*?)</tr>", cat.group(1), flags=re.S):
            tds = re.findall(r"<td[^>]*>(.*?)</td>", row, flags=re.S)
            if len(tds) < 7:
                continue
            label = re.search(r'class="gift-label">(.*?)</span>', tds[0], flags=re.S)
            catalog.append({
                "label": _text(label.group(1) if label else tds[0]),
                "payout": " ".join(_text(x) for x in re.findall(r'gift-payout-line">(.*?)</span>', tds[1], flags=re.S)),
                "monthly": _text(tds[2]),
                "count": _text(tds[3]),
                "total": _text(tds[4]),
                "target": [t.strip() for t in html.unescape(re.sub(r"<br\s*/?>", "\n", tds[5])).split("\n") if t.strip()],
                "kind": _text(tds[6]),
            })

    notes = ""
    nm = re.search(r"유의사항(.*?)요금제별 요약", src, flags=re.S)
    if nm:
        notes = re.sub(r"\n\s*\n+", "\n", html.unescape(re.sub(r"<[^>]+>", "\n", nm.group(1)))).strip()

    code = re.search(r"코드 ([^\s<]+)", _text(src[src.find("<main"):src.find("요약")]) if "<main" in src else "")
    plans = {}
    for name, rows in months.items():
        cancel_day = {}
        for hold, o in (opts or {}).get(name, {}).items():
            cancel_day[hold] = o.get("cancel_day")
        plans[name] = {
            "months": [{
                "after_months": r["after_months"],
                "fee": r["full_fee"],
                "gifts_cash": r["gifts_cash"],
                "gifts_extra": [{"label": g["label"], "payout_day": g.get("payout_day")} for g in r.get("gifts_extra", [])],
            } for r in sorted(rows, key=lambda r: r["after_months"])],
            "cancel_day_by_hold": cancel_day,
        }
    return {
        "key": key,
        "url": url,
        "carrier": m.group(1),
        "source_id": m.group(2),
        "title": _text(title.group(1)).split(" · ")[0].replace(" 사은품 순이익", "") if title else key,
        "period": list(period.groups()) if period else None,
        "promo_code": code.group(1) if code else None,
        "fetched_at": dt.datetime.now().isoformat(timespec="seconds"),
        "site_basis_join_date": next(iter(next(iter(opts.values())).values()))["join_date"] if opts else None,
        "catalog": catalog,
        "notes": notes,
        "plans": plans,
    }


def fetch(url, force=False):
    key, _ = event_key(url)
    path = EVENT_DIR / f"{key}.json"
    if path.exists() and not force:
        return json.loads(path.read_text(encoding="utf-8"))
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (ssalmuk-mine personal)"})
    with urllib.request.urlopen(req, timeout=30) as r:
        src = r.read().decode("utf-8")
    ev = parse_event(src, url)
    EVENT_DIR.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(ev, ensure_ascii=False, indent=1), encoding="utf-8")
    return ev


def line_event(line):
    """회선의 이벤트 로드: ssalmuk URL이면 스냅샷, `event` 키면 data/events/<키>.json (수동 입력)."""
    if line.get("event"):
        path = EVENT_DIR / f"{line['event']}.json"
        if not path.exists():
            raise SystemExit(f"[{line['name']}] 수동 이벤트 파일 없음: {path}")
        return json.loads(path.read_text(encoding="utf-8"))
    return load_event(line["event_url"])


def load_event(url):
    key, _ = event_key(url)
    path = EVENT_DIR / f"{key}.json"
    if not path.exists():
        return fetch(url)
    return json.loads(path.read_text(encoding="utf-8"))


# ---------------------------------------------------------------- 회선
def load_lines():
    if not LINES_FILE.exists():
        return []
    return json.loads(LINES_FILE.read_text(encoding="utf-8")).get("lines", [])


def save_lines(lines):
    LINES_FILE.write_text(json.dumps({"lines": lines}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def gift_kind(ev, label):
    for c in ev.get("catalog", []):
        if c["label"] == label:
            return c["kind"]
    return ""


def gift_fixed_date(ev, label):
    """지급일이 날짜로 고정된 사은품(예: '27-02-23 일시지급)은 사은품 목록의 날짜를 쓴다."""
    for c in ev.get("catalog", []):
        if c["label"] == label:
            m = re.search(r"\d{4}-\d{2}-\d{2}", c["payout"])
            return parse_date(m.group(0)) if m else None
    return None


def compute(line, ev, cash_only=False, today=None):
    """회선 하나의 월별 요금·사은품 스케줄 계산.

    사이트와 같은 규칙: 가입월·해지월은 일할(월 일수 기준), 해지일 기본값은
    그 유지기간의 마지막 지급일(보통 23일). 해지월 지급분은 지급일 ≤ 해지일일 때만 인정.
    """
    today = today or dt.date.today()
    plan = ev["plans"].get(line["plan"])
    if not plan:
        raise SystemExit(f"[{line['name']}] 요금제 '{line['plan']}' 없음. 가능: {', '.join(ev['plans'])}")
    join = parse_date(line["join_date"])
    if line.get("cancel_date"):
        cancel = parse_date(line["cancel_date"])
        hold = months_between(join, cancel) + 1
    else:
        hold = int(line["hold_months"])
        cday = plan["cancel_day_by_hold"].get(str(hold)) or 23
        last = add_months(join, hold - 1)
        cancel = dt.date(last.year, last.month, min(cday, dim(last)))
    if cancel < join:
        raise SystemExit(f"[{line['name']}] 해지일이 가입일보다 빠름")
    months = plan["months"]
    if hold > len(months):
        raise SystemExit(f"[{line['name']}] 유지 {hold}개월 > 데이터 {len(months)}개월")
    excl = [s for s in line.get("exclude_gifts", [])]

    rows = []
    for k in range(hold):
        mrow = months[k]
        ms = add_months(join, k)
        d_in = dim(ms)
        start = join.day if k == 0 else 1
        end = cancel.day if k == hold - 1 else d_in
        fee = round(mrow["fee"] * (end - start + 1) / d_in)
        gifts = []
        for g in mrow["gifts_cash"]:
            if any(s in g["label"] for s in excl):
                continue
            kind = gift_kind(ev, g["label"])
            if cash_only and kind in NON_CASH_KINDS:
                continue
            pday = g.get("payout_day")
            if k == hold - 1 and pday and pday > cancel.day:
                continue  # 지급일 전에 해지
            pdate = dt.date(ms.year, ms.month, min(pday, d_in)) if pday else gift_fixed_date(ev, g["label"])
            gifts.append({"label": g["label"], "amount": g["amount"], "date": pdate, "kind": kind})
        for ig in plan.get("interval_gifts", []):
            if any(s in ig["label"] for s in excl):
                continue
            n = 0
            while True:
                d = join + dt.timedelta(days=ig["first_after_days"] + n * ig["every_days"])
                if d > cancel or (ig.get("times") and n >= ig["times"]):
                    break
                if (d.year, d.month) == (ms.year, ms.month):
                    gifts.append({"label": ig["label"], "amount": ig["amount"], "date": d, "kind": ig.get("kind", "")})
                n += 1
        gsum = sum(g["amount"] for g in gifts)
        rows.append({"k": k, "month": ms, "fee": fee, "gifts": gifts, "gift": gsum, "profit": gsum - fee})
    fee_t = sum(r["fee"] for r in rows)
    gift_t = sum(r["gift"] for r in rows)
    got = sum(g["amount"] for r in rows for g in r["gifts"] if g["date"] and g["date"] <= today)
    paid = sum(r["fee"] for r in rows if r["month"] <= dt.date(today.year, today.month, 1))
    return {
        "line": line, "event": ev, "join": join, "cancel": cancel, "hold": hold, "rows": rows,
        "fee_total": fee_t, "gift_total": gift_t, "profit": gift_t - fee_t,
        "monthly_avg": round((gift_t - fee_t) / hold),
        "received_so_far": got, "paid_so_far": paid,
    }


# ---------------------------------------------------------------- 출력
def md_table(head, rows, align=None):
    align = align or ["---"] * len(head)
    out = ["| " + " | ".join(head) + " |", "| " + " | ".join(align) + " |"]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


def report(results, today):
    o = [f"# 쌀먹 회선 리포트", f"기준일: {today} · 회선 {len(results)}개", ""]
    o.append("## 요약")
    tot = [0, 0, 0, 0, 0]
    rows = []
    for r in results:
        L = r["line"]
        status = "해지 예정" if r["cancel"] >= today else "해지됨"
        rows.append([L["name"], f"{r['event']['carrier']} · {L['plan']}", r["join"], r["cancel"], f"{r['hold']}개월",
                     f"{r['fee_total']:,}", f"{r['gift_total']:,}", signed(r["profit"]), signed(r["monthly_avg"]),
                     f"{r['received_so_far']:,}", status])
        for i, v in enumerate([r["fee_total"], r["gift_total"], r["profit"], r["received_so_far"], r["paid_so_far"]]):
            tot[i] += v
    rows.append(["**합계**", "", "", "", "", f"**{tot[0]:,}**", f"**{tot[1]:,}**", f"**{signed(tot[2])}**", "", f"**{tot[3]:,}**", ""])
    num = "---:"
    o.append(md_table(["회선", "요금제", "가입일", "해지(예정)일", "유지", "총요금", "총사은품", "순이익", "월평균", "수령(누적)", "상태"],
                      rows, ["---", "---", "---", "---", num, num, num, num, num, num, "---"]))
    o.append("")
    o.append(f"- 현재까지: 요금 납부(추정) {tot[4]:,}원 / 사은품 수령(예정일 경과분) {tot[3]:,}원 → 현재 손익 {signed(tot[3] - tot[4])}원")
    o.append("")

    # 통합 월별 현금흐름
    o.append("## 월별 현금흐름 (전체 회선 합산)")
    by = {}
    for r in results:
        for x in r["rows"]:
            b = by.setdefault(x["month"], {"fee": 0, "gift": 0, "who": []})
            b["fee"] += x["fee"]
            b["gift"] += x["gift"]
    acc = 0
    rows = []
    for m in sorted(by):
        b = by[m]
        acc += b["gift"] - b["fee"]
        rows.append([m.strftime("%Y-%m"), f"{b['fee']:,}", f"{b['gift']:,}", signed(b["gift"] - b["fee"]), signed(acc)])
    o.append(md_table(["월", "요금", "사은품", "월손익", "누적"], rows, ["---", num, num, num, num]))
    o.append("")

    # 회선별 상세
    for r in results:
        L, ev = r["line"], r["event"]
        o.append(f"## {L['name']} — {L['plan']}")
        o.append(f"- 이벤트: [{ev['title']}]({ev['url']}) (기간 {'~'.join(ev['period'] or [])}, 코드 {ev.get('promo_code') or '-'})")
        o.append(f"- 가입 {r['join']} → 해지 {r['cancel']} ({r['hold']}개월)")
        if L.get("memo"):
            o.append(f"- 메모: {L['memo']}")
        rows = []
        for x in r["rows"]:
            g = "<br>".join(f"{y['label']} {y['amount']:,} ({y['date'] or '날짜 공지 확인'}{', ' + y['kind'] if y['kind'] else ''})"
                            for y in x["gifts"]) or "-"
            rows.append([f"M+{x['k']}" if x["k"] else "M", x["month"].strftime("%Y-%m"), f"{x['fee']:,}", f"{x['gift']:,}", signed(x["profit"]), g])
        o.append(md_table(["회차", "월", "요금", "사은품", "순이익", "지급 내역"], rows, ["---", "---", num, num, num, "---"]))
        o.append(f"\n합계: 요금 {r['fee_total']:,} / 사은품 {r['gift_total']:,} / 순이익 **{signed(r['profit'])}** (월평균 {signed(r['monthly_avg'])})\n")
    o.append("## 주의")
    o.append("- 계산은 ssalmuk.life 스냅샷 기준. 실제 지급 조건(개통 기간, Hmall 가입, 결합신청 등)은 각 이벤트 공식 페이지로 확인할 것.")
    o.append("- 사이트는 'Hmall 적립금(라이프케어)'을 현금성으로 합산함. 현금만 보려면 `--cash-only`.")
    o.append("- 일부 사은품은 개통 기간이 이벤트 기간보다 짧음(유의사항 참고). 해당 안 되면 lines.json의 `exclude_gifts`에 이름 일부를 넣을 것.")
    return "\n".join(o)


def upcoming(results, today, days):
    until = today + dt.timedelta(days=days)
    ev = []
    for r in results:
        for x in r["rows"]:
            for g in x["gifts"]:
                if g["date"] and today <= g["date"] <= until:
                    ev.append((g["date"], r["line"]["name"], f"지급 {g['label']} {g['amount']:,}원"))
        if today <= r["cancel"] <= until:
            ev.append((r["cancel"], r["line"]["name"], "⚠ 해지 예정일 (다른 이벤트로 번호이동 준비)"))
    ev.sort()
    return md_table(["날짜", "회선", "내용"], ev) if ev else f"{days}일 내 일정 없음"


# ---------------------------------------------------------------- CLI
def main(argv=None):
    ap = argparse.ArgumentParser(description="쌀먹 회선 관리기")
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch"); f.add_argument("url"); f.add_argument("--force", action="store_true")
    a = sub.add_parser("add")
    a.add_argument("--name", required=True); a.add_argument("--url", required=True); a.add_argument("--plan", required=True)
    a.add_argument("--join", required=True); a.add_argument("--hold", type=int); a.add_argument("--cancel")
    a.add_argument("--memo", default="")
    sub.add_parser("list")
    r = sub.add_parser("report"); r.add_argument("--out"); r.add_argument("--cash-only", action="store_true"); r.add_argument("--today")
    u = sub.add_parser("upcoming"); u.add_argument("--days", type=int, default=45); u.add_argument("--today")
    args = ap.parse_args(argv)

    if args.cmd == "fetch":
        ev = fetch(args.url, force=args.force)
        print(f"{ev['title']} ({ev['carrier']}, {'~'.join(ev['period'] or [])}) → data/events/{ev['key']}.json")
        for p, d in ev["plans"].items():
            print(f"  - {p}: 월 {d['months'][1]['fee']:,}원")
        return
    if args.cmd == "add":
        if not (args.hold or args.cancel):
            raise SystemExit("--hold 또는 --cancel 중 하나 필요")
        ev = load_event(args.url)
        if args.plan not in ev["plans"]:
            raise SystemExit(f"요금제 없음. 가능: {', '.join(ev['plans'])}")
        lines = [l for l in load_lines() if l["name"] != args.name]
        line = {"name": args.name, "event_url": args.url, "plan": args.plan, "join_date": args.join}
        if args.cancel:
            line["cancel_date"] = args.cancel
        else:
            line["hold_months"] = args.hold
        if args.memo:
            line["memo"] = args.memo
        lines.append(line)
        save_lines(lines)
        print(f"저장: {args.name}")
        return

    lines = load_lines()
    if args.cmd == "list":
        for l in lines:
            print(f"- {l['name']}: {l['plan']} / 가입 {l['join_date']} / {l.get('cancel_date') or str(l.get('hold_months')) + '개월'}")
        return
    today = parse_date(args.today) if getattr(args, "today", None) else dt.date.today()
    results = [compute(l, line_event(l), cash_only=getattr(args, "cash_only", False), today=today) for l in lines]
    if args.cmd == "report":
        text = report(results, today)
        if args.out:
            Path(args.out).parent.mkdir(parents=True, exist_ok=True)
            Path(args.out).write_text(text + "\n", encoding="utf-8")
            print(f"저장: {args.out}")
        else:
            print(text)
    elif args.cmd == "upcoming":
        print(upcoming(results, today, args.days))


if __name__ == "__main__":
    main()
