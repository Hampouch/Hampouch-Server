# 미니 챌린지 API 명세

> 📌 **서버 파트 작업 문서(백업) — 리뷰 대상 아님. 팀이 보는 명세(진실)는 노션 "API 설계"뿐.** 잠정 항목은 본문 ⚠️ 표시.
> **유저 소유 · 본 챌린지 독립**(0707 서버회의 — 정의+참여 합침, `challenge_id` 없음). 경로는 유저 스코프 `/mini-challenges`.
> 홈 "오늘 나의 챌린지" 섹션도 §1 조회를 재사용(0630 확정). 식비 판정과 무관 — 본 챌린지의 기간·상태에 아무 제약을 받지 않는다.
> 0630 회의 확정: **내용 수정 불가 — 삭제 후 새로 생성**(수정 API 없음) · **난이도·이모지 삭제**. 미니 메인의 "새 챌린지 시작하기" 버튼 = 미니 추가 진입(✅0711 PM 확정).
> 상태: **§2 = ✅구현 완료·머지**(이슈 #10 · PR #18) · **§1·3·4·5 = 구현 완료**(이슈 #9 · PR #21 리뷰 중) · 공통 규약은 `API명세_본챌린지.md` §0.
> ✳️ 본챌 독립화(0707)로 소멸한 제약: "본챌 기간 초과 금지" · "종료된 챌린지 미니 체크 불가" · 구 `participationId`(→ `miniChallengeId`로 통일).

## 1. `GET /api/mini-challenges?date=` — 그날 나의 미니 챌린지 🔒

유저 소유 조회. 날짜 스트립(전날/오늘/다음날 표시 — 미래 이동은 불가, 0711 PM) 대응 — `date` 생략 시 오늘.

**응답 `200 OK`**

```json
{ "date": "2026-07-06",
  "summary": { "checkedCount": 3, "totalCount": 4, "streakDays": 3 },
  "items": [
    { "miniChallengeId": 5, "title": "오늘 커피 사먹지 않기",
      "durationDays": 7, "progressDays": 4, "itemStreak": 3, "checked": true }
] }
```

> `totalCount` = 그날 활성(기간에 걸친) 미니 수, `checkedCount` = 그중 체크된 수. 집계는 조회 `date` 기준(as-of), `mini_challenge_day`에서 계산(저장 안 함).
> `progressDays` = 그 미니의 시작일~조회 `date` 경과 일수(as-of, 1부터) · `itemStreak` = 그 항목을 연속 체크한 일수(항상 `≤ progressDays`).
> `streakDays` 산식 — ✅확정(2026-07-07 일혁): **그날 활성 미니를 전부 체크(checkedCount = totalCount > 0)한 날**의 연속 일수(유저 단위).
> `streakDays`의 **과거 조회일** 처리 — ⚠️잠정(0717 보류 확정): 조회일이 과거여도 오늘과 동일하게, 미완료인 조회일은 건너뛰고 전날까지의 연속을 반환(a안 유지). 과거 화면이 이 값을 쓰는지 [안드 확인]이 선행 — 질문목록 10번·배경 10번. b안(지나간 날은 판정해 0)으로 확정되면 서비스가 실제 오늘 여부를 계산기에 넘기는 소규모 수정.

**에러**: 400(date 형식 · **미래 date** `MINI_FUTURE_DATE` — 날짜 스트립 미래 이동 불가(0711 PM)와 §5 미래 체크 400의 준용, 0716 자체 확정) / 401.

## 2. `GET /api/mini-challenges/recommended?durationDays=` — 추천 목록 (공용) 🔒

추천 프리셋 카탈로그(`recommended_mini_challenge`, 읽기 전용)에서 조회. 기간 탭 필터(**오늘만/3/7/14/31일 — ✅확정**, 스크린디자인 최신 기준 · 2026-07-07 일혁). 목록 = **기획 리스트업 확정**(0630, 시드 데이터로 반영) · **노출은 랜덤**(0630 — 서버가 무작위 순서/추출로 응답).

**응답 `200 OK`**: `{ "items": [ { "recommendedId": 7, "title": "편의점 디저트 안 먹기", "durationDays": 7 } ] }` · **에러**: 401.

> `durationDays`가 화이트리스트(1·3·7·14·31) 밖이면 **400이 아니라 빈 `items`의 200**이다(✅구현 확정 — #10). 필터 결과 0건은 요청 실패가 아니라 완료이고, 명세에 400이 없으며, 화이트리스트를 조회에 하드코딩하지 않아야 기획이 시드 기간을 바꿔도 서버 수정이 없기 때문. (생성 §3은 반대로 400 — 잘못된 값이 DB에 남아서.)
> `durationDays`에 숫자 아닌 값이 오면 **400**(`VALIDATION_ERROR` + fieldErrors) — 0716 나연이 공통 핸들러에 타입 바인딩 실패 매핑을 추가해 구 500이 해소됨(#10 컨트롤러 테스트도 develop에서 함께 갱신됨).

> `recommendedId`는 카탈로그(`recommended_mini_challenge`)의 id이고 유저 미니의 id가 아니다. 추가(§3)에 이 값을 보내면 서버가 카탈로그에서 그 행의 `title`·`durationDays`를 **복사**해 유저 소유의 `mini_challenge` 행을 새로 만들고 **새 `miniChallengeId`를 발급**한다. 이후 체크(§5)·삭제(§4)는 그 `miniChallengeId`로 한다.
> **참조가 아니라 값 복사인 이유**: 카탈로그는 기획이 관리하는 공용 목록이고 유저 미니는 유저 데이터라 수명이 다르다. FK로 엮으면 기획이 문구를 고치거나 항목을 빼는 순간 이미 시작한 유저 미니까지 흔들린다. 값을 복사해 두면 카탈로그가 바뀌어도 유저 미니는 불변 — ERD에 두 테이블 간 관계선이 없는 이유다(0707 미니 독립화).

## 3. `POST /api/mini-challenges` — 미니 추가 🔒

**요청 바디 — 둘 중 한 형태만** (둘 다/둘 다 아님 → 400 `MINI_INVALID_BODY`):

| 형태 | 바디 | 대응 화면 |
|---|---|---|
| 추천에서 추가 | `{ "recommendedId": 7 }` | "해당 미니 챌린지를 추가할까요?" (§2 카탈로그에서 값 복사) |
| 커스텀 생성 | `{ "custom": { "title": "오늘 간식 2개 이하", "durationDays": 7 } }` | 나만의 챌린지 만들기 |

**서버**: 유저 소유 `mini_challenge` 행 1개 생성 · `start_date = 오늘`(자체 결정) · `end_date = start + durationDays − 1` 스냅샷. 추천은 카탈로그에서 `title`/`duration_days` 복사(추천/커스텀 통일 — origin 없음).

**응답 `201 Created`** (헤더 `Location: /api/mini-challenges/{id}` — 본챌 §1과 동일 생성 패턴): `{ "miniChallengeId": 5, "title": "...", "durationDays": 7, "startDate": "2026-07-06", "endDate": "2026-07-12" }`

**에러**: 400(`MINI_INVALID_BODY` 형태 위반 · `MINI_INVALID_DURATION` = durationDays가 **{1, 3, 7, 14, 31} 밖**(✅화이트리스트 확정)) / 401 / 404(`recommendedId` 카탈로그에 없음 — `MINI_RECOMMENDED_NOT_FOUND`, ✅0716 일혁) / 409(같은 추천을 이미 진행 중 — ⚠️잠정)

## 4. `DELETE /api/mini-challenges/{miniChallengeId}` — 미니 삭제 🔒

0630 확정: "내용 수정 X, **삭제 후 새로 생성**" → 수정 API 대신 삭제 제공.

**서버**: 미니 행(`mini_challenge`) + 그 일별 체크(`mini_challenge_day`) 삭제(정의·참여 합쳐져 단순 삭제).

**응답 `204 No Content`** · **에러**: 401 / 403(남의 미니) / 404(`MINI_NOT_FOUND`).

## 5. `PUT /api/mini-challenges/{miniChallengeId}/check` — 일별 체크/해제 🔒

체크박스 = 그날 하나의 기록. **PUT + 목표 상태 전달 = 멱등**(같은 요청을 반복해도 결과 동일 — RFC 9110 §9.2.2).

**요청 바디**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| date | date | ❌ | 생략 시 오늘 · 과거 허용(✅0711 PM — 기간 내 자유 수정) · **미래는 400 차단**(`MINI_FUTURE_CHECK`, ✅확정 — 선체크 상황 없음, 2026-07-07 일혁) |
| checked | boolean | ✅ | `true`=체크(그날 행 upsert, 재요청 시 checked_at 보존) / `false`=해제(행 삭제) |

**서버**: `mini_challenge_day` — unique(mini_challenge_id, check_date) 기준 upsert/삭제.

**응답 `200 OK`**: `{ "miniChallengeId": 5, "date": "2026-07-06", "checked": true }`

**에러**: 400(date 미니 기간 밖 `MINI_DATE_OUT_OF_RANGE` · 미래 `MINI_FUTURE_CHECK` — 미래이면서 기간 밖이기도 하면 `MINI_FUTURE_CHECK` 우선, ✅0716 일혁) / 401 / 403(남의 미니) / 404(`MINI_NOT_FOUND`)

## 잠정 / 확정 요약

| 항목 | 현재 상태 |
|---|---|
| 추천 미니 목록 | ✅ 기획 리스트업·랜덤 노출 확정(0630) — 시드 반영 |
| 커스텀 수정·재사용 | ✅ 수정 불가·삭제 후 재생성 확정(0630) |
| "새 챌린지 시작하기" 버튼 | ✅ 미니 추가 진입 — 0711 PM 확정 |
| 과거 날짜 늦은 체크 | ✅ 허용 — 0711 PM(기간 내 자유 수정) |
| 미래 날짜 선체크 | ✅ 차단(400) — 선체크 상황 없음 |
| 미래 날짜 조회 | ✅ 차단(400 `MINI_FUTURE_DATE`) — 0711 미래 이동 불가·§5 준용(0716 자체 확정) |
| 연속 달성(streak) 기준 | ✅ 그날 활성 미니 전부 체크한 날의 연속 |
| durationDays 검증 | ✅ 화이트리스트 {1,3,7,14,31} 확정(스크린디자인 최신 기준) |
| 참여 시작일 | 추가한 날 (자체 결정) |
| 같은 추천 중복 추가 | ⚠️ 잠정: 이미 진행 중이면 409 |
| 과거 조회일의 streakDays | ⚠️ 잠정: 오늘과 동일하게 미완료 조회일 스킵(a안) — 안드/PM 확인 대기(질문 10, 0717 보류 결정) |
