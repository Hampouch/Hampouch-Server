# 지난 챌린지 리스트 — REST API 명세 (초안)

> ⚠️ **초안(미확정)** — 이슈 #4. 구현하면서 확정한 뒤 `docs/` 루트로 옮겨 그 구현 PR에 포함한다.
> 2026-07-15 `API명세_본챌린지.md` §5-1에서 분리(확정본 문서에서 미구현 내용 제거).
> 공통 규약(§0)은 `API명세_본챌린지.md`를 따른다.

## `GET /api/challenges/history` — 지난 챌린지 리스트 🔒 (마이페이지)

대응 화면: 마이페이지 "지난 챌린지". **종료된(SUCCESS/FAIL) 챌린지 목록**만 반환(IN_PROGRESS 제외 — 진행 중은 `current`). 로그인 유저 소유분, `endDate` 내림차순(최근 먼저).

**응답 `200 OK`** (기록 없으면 `items: []`)

```json
{
  "items": [
    { "challengeId": 12, "status": "SUCCESS",
      "startDate": "2026-05-01", "endDate": "2026-05-14", "durationDays": 14,
      "budgetTotal": 280000, "actualSpent": 211800, "savedAmount": 68200 },
    { "challengeId": 8, "status": "FAIL",
      "startDate": "2026-04-01", "endDate": "2026-04-14", "durationDays": 14,
      "budgetTotal": 210000, "actualSpent": 224000, "savedAmount": 41000 }
  ]
}
```
> 요약 필드는 `ChallengeDay` 집계(조회 시 계산) — `actualSpent`/`savedAmount`는 `EXPENSE`(외부) 의존, 연동 계약 전엔 시드. 각 챌린지 상세는 `GET /{id}/result` 재사용.
> `status`는 종료 시점에 확정·저장된 값(본챌린지 §4 status 규칙). 아직 한 번도 종료된 챌린지가 없으면 빈 배열.

**에러**

| 코드 | 상황 |
|---|---|
| 401 | 인증 실패 |
