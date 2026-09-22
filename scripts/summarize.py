#!/usr/bin/env python3
"""JUnit XML 을 읽어 챕터별 채점 결과로 요약한다.

- GitHub Actions 요약 화면($GITHUB_STEP_SUMMARY)에 표를 그린다
- grade.md 를 남긴다 (현황보기.py 가 읽는다)
- 전부 통과하면 exit 0, 하나라도 실패하면 exit 1
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

RESULT_DIR = "target/surefire-reports"
WEEK_RE = re.compile(r"(?:^|\.)week(\d+)(?:\.|$)", re.I)


def collect():
    """[(week, 표시이름, 통과여부, 메모)] 를 돌려준다."""
    rows = []
    for path in sorted(glob.glob(os.path.join(RESULT_DIR, "*.xml"))):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for tc in root.iter("testcase"):
            classname = tc.get("classname") or ""
            m = WEEK_RE.search(classname)
            week = f"{int(m.group(1)):02d}" if m else "??"
            bad = [c for c in tc if c.tag in ("failure", "error")]
            note = ""
            if bad:
                note = (bad[0].get("message") or bad[0].tag or "").strip()
                note = " ".join(note.split())[:160]
            rows.append((week, tc.get("name") or "(이름 없음)", not bad, note))
    return rows


def main():
    rows = collect()
    if not rows:
        msg = "테스트 결과가 없다. 컴파일에 실패했을 가능성이 높다."
        print(msg)
        write("# 채점 결과\n\n" + msg + "\n", "SCORE=0\nPASSED=0\nTOTAL=0\n")
        return 1

    rows.sort(key=lambda r: (r[0], r[1]))
    passed = sum(1 for r in rows if r[2])
    total = len(rows)
    score = round(passed * 100 / total)

    # 주차별 집계
    weeks = {}
    for week, _, ok, _ in rows:
        w = weeks.setdefault(week, [0, 0])
        w[1] += 1
        if ok:
            w[0] += 1

    md = ["# 채점 결과", "", f"**{passed} / {total} 통과 · {score}점**", "", "## 챕터별", "",
          "| 챕터 | 통과 | 상태 |", "|---|---|---|"]
    for week in sorted(weeks):
        p, t = weeks[week]
        mark = "✅" if p == t else ("⚠️" if p else "❌")
        label = f"CH{int(week):02d}" if week != "??" else "기타"
        md.append(f"| {label} | {p} / {t} | {mark} |")

    md += ["", "## 항목별", "", "| 챕터 | 테스트 | 결과 | 메모 |", "|---|---|---|---|"]
    for week, name, ok, note in rows:
        label = f"CH{int(week):02d}" if week != "??" else "기타"
        md.append(f"| {label} | {name} | {'✅ 통과' if ok else '❌ 실패'} | {note} |")
    md.append("")

    head = [f"WEEK=week{w} PASSED={weeks[w][0]} TOTAL={weeks[w][1]}" for w in sorted(weeks)]
    head += [f"SCORE={score}", f"PASSED={passed}", f"TOTAL={total}"]

    body = "\n".join(md)
    print(body)
    write(body, "\n".join(head) + "\n")
    return 0 if passed == total else 1


def write(body, head):
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(body)
    with open("grade.md", "w", encoding="utf-8") as f:
        f.write(head + "\n" + body)


if __name__ == "__main__":
    sys.exit(main())
