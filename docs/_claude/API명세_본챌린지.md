# 본 챌린지 — REST API 명세 (정일혁 파트)

> 📌 **이 문서는 PR 리뷰 대상이 아닙니다** — 팀이 보는 명세는 노션 "API 설계"입니다. 이 폴더의 성격은 `docs/README.md` 참고.
>
> 상태: 구현 완료(§1~§5 — 이 문서의 엔드포인트 전부) · 스택: Spring Boot 4.1 / Java 21 / REST(RFC 9110)
> 데이터 모델: 팀 ERDCloud 참조
> 범위: 본 챌린지의 **핵심 루프**(생성 → 기록 → 현황 → 캘린더 → 결과)만.
> 다른 기능(휴식·중도포기·한도조정·미니챌린지·추천·집중카테고리·지난 챌린지 리스트)의 명세는 구현하면서 확정하고,
> **그 기능의 구현 PR에서 확정본으로 추가**된다. 공통 규약(§0)은 모든 명세 파일이 이 문서 것을 따른다.
> (2026-07-06: 휴식 재해석으로 PAUSED 상태·이 문서의 409 조건 변경은 **철회** — 아래 조건들이 원래대로 유효)
> ⚠️ **문서 수준 잠정 3곳**(팀 확정 전 가정 — 조항별 잠정은 해당 본문의 ⚠️ 표시): (1) 유저 식별(로그인=나연)
> (2) `EXPENSE` 연동 = 령준 조회 메서드 호출로 결정(0707 서버팀) (3) DB 공유 여부·네이밍/PK 컨벤션 미정(팀 개발 회의 — 마이그레이션·FK의 전제)

## 🚦 구현 현황 (안드 인계용 — 2026-07-12)

> ✅ 서버 구현 완료 = 지금 붙일 수 있음(유저 식별은 X-User-Id 헤더 스텁, 로그인 연동 전). ⏳ 미구현 = 명세만 있고 코드 없음(붙이면 404).

- **✅ 구현 완료 (6)**: §1 `POST /challenges` · §2 `GET /current` · §3 `GET /{id}/calendar` · §4 `GET /{id}/result` · §5 `POST /{id}/days`(시드 전용) · 지난 챌린지 리스트 `GET /challenges/history`(#4 — 상세 `API명세_히스토리.md`)
- **⏳ 미구현**: 휴식·중도포기·미니(4개)·한도조정·추천·집중카테고리 — 명세는 각 기능 구현 PR에서 추가(붙이면 404)
- ⚠️ §4 `result`의 categoryBreakdown/emotionBreakdown은 령준 연동 전 빈 배열(구현됐으나 값 `[]` 고정)

---

## 0. 공통 규약

- **기본 경로**: `/api` — 버전 접두어(v1) 제거(0714 BE 확정)
- **인증** 🔒: `Authorization: Bearer <JWT>` 필수. **userId = JWT `sub` 클레임.**
  ⚠️ 잠정 — 나연 로그인 연동 전엔 임시값(예: 헤더 `X-User-Id` 또는 하드코딩). 스택: Spring Security OAuth2 Resource Server(Nimbus).
- **형식**: 요청/응답 모두 `application/json; charset=UTF-8`
- **금액**: 정수(원). **날짜**: `yyyy-MM-dd`(ISO-8601). **일시**: ISO-8601.
- **표기**: 🔒 인증 필요 · ⚠️ 잠정(팀 확정 전)

### 공통 상태코드 (RFC 9110)

| 코드 | 의미 | 이 API에서 |
|---|---|---|
| 200 OK | 성공(조회/갱신) | GET 전부, `POST .../days`(upsert) |
| 201 Created | 생성됨 | `POST /challenges` |
| 400 Bad Request | 요청 형식/검증 오류 | 필드 누락·형식·범위 위반 |
| 401 Unauthorized | 인증 없음/실패 | 토큰 없음·만료·위조 |
| 403 Forbidden | 인증됐으나 권한 없음 | 남의 챌린지 접근 |
| 404 Not Found | 리소스 없음 | 챌린지 id 없음, 진행 중 없음 |
| 409 Conflict | 현재 상태와 충돌 | 진행 중 챌린지 중복 생성, 진행 중인데 결과 요청 |

### 공통 정상 응답 — 팀 공통 포맷 `{ code, message, data }` (2026-07-10 나연 common, PR #15)

> 정상(2xx) 응답은 전부 `ApiResponse<T>`(나연 common)로 감쌈. `code`는 `"SUCCESS"` 고정, 실제 페이로드는 `data`에.
> `data`가 null이면 필드 자체가 생략됨(`@JsonInclude(NON_NULL)`). `message`는 기본 "요청이 성공했습니다."(변경 가능).

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": { }
}
```

> ★ 아래 각 API의 **응답 예시는 `data` 내부만** 표기(래퍼 생략). 에러 응답은 래핑하지 않고 아래 에러 포맷 그대로.

### 공통 에러 응답 — 팀 공통 포맷 `{ code, message, status, fieldErrors }` (2026-07-07 서버팀 확정)

> `ProblemDetail`/`application/problem+json`은 **철회**. 팀 공통 에러 포맷으로 통일 — Content-Type `application/json`.
> 공통 `ErrorCode` enum·`GlobalExceptionHandler`는 **나연이 common 모듈에 구현**. 챌린지 도메인은 자기 예외를 이 enum에 등록만 함(아래 코드표).

```json
{
  "code": "CHALLENGE_ALREADY_IN_PROGRESS",
  "message": "이미 진행 중인 챌린지가 있습니다.",
  "status": 409
}
```

검증 실패(`@Valid`, 400)일 때만 `fieldErrors`(필드명 → 메시지 **Map**)가 붙고, 그 외엔 **필드 자체가 생략**됨(`@JsonInclude(NON_NULL)`):

```json
{
  "code": "VALIDATION_ERROR",
  "message": "입력값이 올바르지 않습니다.",
  "status": 400,
  "fieldErrors": { "durationDays": "1 이상이어야 합니다." }
}
```

> `code` = `ErrorCode` enum 이름 · `status` = HTTP 상태값(정수) · `fieldErrors` = 필드명→메시지 Map. ✅ **나연 common 실물과 대조 확인(0714)** — `@Valid` 실패=`VALIDATION_ERROR` · 파라미터 범위 등 서비스 검증=`BAD_REQUEST`(둘 다 CommonErrorCode 실존).

**챌린지 도메인이 공통 `ErrorCode`에 등록할 코드 (제안 — 나연 확정)**

| code | status | 발생 지점 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | `@Valid` 검증 실패(공통 — 나연 common 실물 확인 0714) |
| `CHALLENGE_NOT_FOUND` | 404 | 챌린지 id 없음 · 진행 중 챌린지 없음 |
| `CHALLENGE_FORBIDDEN` | 403 | 남의 챌린지 접근 |
| `CHALLENGE_ALREADY_IN_PROGRESS` | 409 | 진행 중인데 새 챌린지 생성 |
| `CHALLENGE_NOT_ENDED` | 409 | 진행 중인데 `result` 요청 |
| `CHALLENGE_NOT_IN_PROGRESS` | 409 | 종료됐는데 `give-up`·`adjust` 요청 |
| `DAY_OUT_OF_RANGE` | 400 | `days`·미니 체크 date가 기간 밖 |
| `REST_ALREADY_ACTIVE` | 409 | 이미 휴식 중(종료 안 된 `user_rest`) |
| `REST_NOT_ACTIVE` | 404 | 휴식 중 아닌데 `resume` |
| `ADJUSTMENT_LIMIT_EXCEEDED` | 409 | 한도 조정 2회 소진 |
| `MINI_INVALID_BODY` | 400 | 미니 추가 바디 형태 위반(추천/커스텀 동시·부재) |
| `MINI_INVALID_DURATION` | 400 | 미니 durationDays 화이트리스트 {1,3,7,14,31} 밖 |
| `MINI_FUTURE_CHECK` | 400 | 미니 미래 날짜 체크 |
| `MINI_NOT_FOUND` | 404 | 미니 챌린지 없음 |

> 이 표는 **일혁 도메인 예외 ↔ 공통 코드 매핑 제안**이다. 최종 enum 상수명·메시지 문구는 나연 common에서 확정.

---

## 1. `POST /api/challenges` — 챌린지 생성 🔒

대응 화면: 온보딩 STEP 2(목표 설정) 완료 시.

**요청 바디**

| 필드 | 타입 | 필수 | 제약 / 설명 |
|---|---|---|---|
| durationDays | int | ✅ | 1~100 (프리셋 3/7/14/31 + 직접입력 N — 스크린디자인 최신 기준, 2026-07-07). 상한 100일 = 0714 전체회의 확정("기간 입력의 한도 = 100일" — 0711 '상한 없음' 대체) |
| budgetTotal | int(원) | ✅ | ≥ 1 (기간 내 식비 목표) |
| startDate | date | ✅ | `yyyy-MM-dd`, 오늘 이상 |
| resetByPayday | boolean | ❌ | 기본 `false` |
| paydayDay | int | ⚠️조건부 | 1~31, `resetByPayday=true`면 필수 |
| weakCategories | string[] | ❌ | 집중(약한) 카테고리, 다중 선택 — ✅확정 7종(지출과 동일: 배달/외식/편의점/카페/간식/장보기/술자리)+직접추가 · 진행 중 수정은 집중 카테고리 수정 기능(구현 PR에서 명세 추가)으로 제공 예정 |

```json
{ "durationDays": 30, "budgetTotal": 100000, "startDate": "2026-06-23",
  "resetByPayday": true, "paydayDay": 25,
  "weakCategories": ["카페", "배달", "편의점"] }
```

**서버 계산**: `endDate = startDate + durationDays − 1` · `dailyLimit = floor(budgetTotal / durationDays)` · `status = IN_PROGRESS`

**응답 `201 Created`** (헤더 `Location: /api/challenges/{id}`)

```json
{ "challengeId": 1, "dailyLimit": 3333,
  "startDate": "2026-06-23", "endDate": "2026-07-22", "status": "IN_PROGRESS" }
```

**에러**

| 코드 | 상황 |
|---|---|
| 400 | 필드 누락·형식·범위 위반, `resetByPayday=true`인데 `paydayDay` 없음 |
| 401 | 토큰 없음/유효하지 않음 |
| 409 | 이미 진행 중(IN_PROGRESS) 챌린지 존재 — ⚠️가정: **동시 1개** |

---

## 2. `GET /api/challenges/current` — 진행 중 챌린지 + 현황 🔒

대응 화면: 홈(챌린지 현황). PDF p15 기준 홈이 챌린지 현황을 표시 → **이 API 필요 확정**(챌린지 현황 부분은 일혁 제공). 로그인 유저의 IN_PROGRESS 챌린지 1건.

**응답 `200 OK`**

```json
{
  "challenge": {
    "id": 1, "durationDays": 30, "startDate": "2026-06-23", "endDate": "2026-07-22",
    "budgetTotal": 100000, "dailyLimit": 3333, "status": "IN_PROGRESS"
  },
  "progress": {
    "elapsedDays": 5, "remainingDays": 25,
    "successDays": 4, "overDays": 1, "currentStreak": 2, "savedAmountSoFar": 4200
  },
  "consumption": {
    "todaySpent": 2000, "todayRemaining": 1333, "dailyLimit": 3333,
    "usageRate": 0.6, "character": "NORMAL", "alertLevel": "CAUTION"
  },
  "warningCards": [],
  "adjustment": { "usedCount": 0, "maxCount": 2 }
}
```
> ★ 이 4개 블록(`challenge`·`progress`·`consumption`·`warningCards`·`adjustment`)은 진행 중이면 **항상 함께** 내려감(구현 `CurrentChallengeResponse` 기준).
> `progress`는 저장값이 아니라 `ChallengeDay` 집계(조회 시 계산). **미입력일 = 0원 = 성공으로 채워 집계**(0714 확정 — 결과 화면 §4와 동일 규칙). 단 **오늘은 기록이 있을 때만 포함**(판정 완료 구간 = 시작일~어제, 오늘 기록 시 오늘까지).
> 🔺 **0715 개정 예고** — 배틀 무효 규칙 이식 확정(연속 3일 미기록 = 챌린지 무효)으로 이 규칙의 **생존 범위가 좁아짐**. 1~2일 미기록도 계속 성공으로 채우는지는 `PM_질문목록.md` 11번(미해소) — 그때까지 현행 유지.
> `consumption` = 홈 소비상태(하루 사용률 2축) — `character`(FULL 볼빵빵 / NORMAL 보통 / SKINNY 홀쭉), `alertLevel`(NONE / CAUTION 주의 / DANGER 위험). **경계 둘 다 `<30% / <70% / ≥70%` — 0714 통일**(알림 구 40/70 · 캐릭터 0–30/30–70/70–100은 0711 PM 서면 재확정). 숫자는 같아졌지만 역할(표정 vs 경고)이 달라 필드는 분리 유지. `todayRemaining = dailyLimit − todaySpent`(초과 시 음수 그대로). 한도 초과(사용률 >100%)면 SKINNY·DANGER. enum 상수명은 자체 결정 — 안드 확인 시 맞춤.
> `warningCards` = 홈 경고 카드 코드 목록 — **카드별 자기 트리거로 발동**(오늘 사용률 게이트 없음, 0713 제거). 현재 `"GOAL_TOO_TIGHT"`(판정 완료 구간 3일 연속 초과 — 조정 화면 진입점)만 구현 · 원소는 문자열. `WEAK_CATEGORY_ALERT`는 기준 확정(0714: 전체 예산의 70%↑·'주의' 일괄) — **령준 지출 연동 후 노출**(⚠️ 그때 원소 형태와 `overStreakDays`(카드 문구용 연속 초과 일수) 동반 여부 확정).
> 위젯도 이 API 재사용 예정(팀 협의) · 홈 날짜 스트립의 과거(as-of) 조회는 `date` 파라미터 확장 예정(0711 PM — 설계 초안, 구현 이슈로) · 휴식 중 응답은 404가 아니라 200 + 휴식 데이터(휴식 기능 구현 PR에서 명세 추가).
> `adjustment` = 한도 조정 사용/최대 횟수. `usedCount`는 한도 조정 기능 구현 전까지 `0` 고정.

**에러**

| 코드 | code 상수 | 상황 |
|---|---|---|
| 401 | (나연 common) | 인증 실패 |
| 404 | `NO_ACTIVE_CHALLENGE` | 진행 중 챌린지 없음 — ⚠️가정(대안: `200` + `null`) |

---

## 3. `GET /api/challenges/{id}/calendar?year=&month=` — 일별 기록 🔒

대응 화면: 하루하루 기록(캘린더).

**경로/쿼리 파라미터**

| 이름 | 위치 | 타입 | 필수 | 제약 |
|---|---|---|---|---|
| id | path | long | ✅ | 챌린지 id |
| year | query | int | ✅ | 1~9999 (범위 밖 400 — 상한은 MySQL DATE 지원 한계, 하한은 0·음수 컷) · 예: 2026 |
| month | query | int | ✅ | 1~12 |

**응답 `200 OK`** (기록 있는 날만, 챌린지 기간 내)

```json
{ "challengeId": 1, "year": 2026, "month": 6,
  "days": [
    { "date": "2026-06-23", "status": "SUCCESS", "spentAmount": 2000 },
    { "date": "2026-06-24", "status": "OVER",    "spentAmount": 5000 }
  ] }
```

**에러**

| 코드 | 상황 |
|---|---|
| 400 | year/month 형식·범위 오류 |
| 401 | 인증 실패 |
| 403 | 남의 챌린지 |
| 404 | 챌린지 id 없음 |

---

## 4. `GET /api/challenges/{id}/result` — 종료 결과 🔒

대응 화면: 성공/실패(각 2장). **종료된(SUCCESS/FAIL) 챌린지** 대상.

**응답 `200 OK`**

```json
{
  "challengeId": 1, "status": "SUCCESS",
  "period": { "startDate": "2026-05-01", "endDate": "2026-05-14", "durationDays": 14 },
  "summary": {
    "successDays": 14, "overDays": 0, "savedAmount": 68200, "overAmount": 0,
    "maxStreak": 14, "budgetTotal": 280000, "actualSpent": 211800
  },
  "categoryBreakdown": [ { "category": "배달", "amount": 38400 }, { "category": "카페", "amount": 23500 } ],
  "emotionBreakdown":  [ { "emotion": "먹고싶어서", "ratio": 0.42 }, { "emotion": "스트레스", "ratio": 0.31 } ]
}
```
> ⚠️ `categoryBreakdown`(지출 상위 카테고리, 전체 합 아님) / `emotionBreakdown`(비율 합 = 1.0)은 **`EXPENSE`(외부) 의존** — 출처 확정 전엔 시드로 채움.
> `savedAmount = Σ max(0, dailyLimit − spent)`, `overAmount = Σ max(0, spent − dailyLimit)`.
> ➕ **한도 조정 도입 후**: 여기서의 dailyLimit은 챌린지 단일 값이 아니라 **그날의 `challenge_day.daily_limit` 스냅샷** 기준 (V1처럼 조정이 없으면 둘이 동일).
> ➕ **미입력일(행 없는 날) = 0원 지출로 가정** (0630 회의 확정) → SUCCESS·successDays 포함, 절약액엔 그날 한도 전액 가산. **기록 0건이어도 동일 — 전일 미입력 = 전원 성공 = SUCCESS 확정(0714 PM)**. 매일 저녁 리마인더로 입력 유도.
> 🔺 **0715 개정 예고 — "전일 미입력 = SUCCESS"는 곧 죽는다.** 배틀 무효 규칙 이식 확정(연속 3일 미기록 = 챌린지 무효, `ChallengeStatus`에 VOID 신설 + 무효 화면)으로 전일 미입력 챌린지는 3일차에 무효로 끝나 결과 판정에 도달하지 못함. 1~2일 미기록의 처리·7일 이하 챌린지 예외는 `PM_질문목록.md` 11번(미해소) — 그때까지 현행 유지. 상세는 `질문배경.md` 11번.
> **status 확정**: `end_date` 경과 시 계산(전부 SUCCESS → `SUCCESS`, OVER 1+ → `FAIL`), 최초 계산 시 저장, **별도 배치 없음**. 진행 중이면 409.
> ✅ **종료 후 수정 시 재계산(0714 PM 확정)**: 기간 내 지출은 종료 후에도 수정 가능(0711)하고, 수정하면 **확정 status·집계 모두 다시 계산됨** — 서버는 §5 upsert 시 status 재갱신, 집계는 조회 시 계산이라 자동 최신. (⚠️ 최종 종료 잠금 도입 시 경계는 PM_질문목록 2번)
> ➕ **중도 포기(`give-up`) 시**: `end_date` 경과 전이라도 즉시 `FAIL` 확정 → 이 `result` 조회 가능(0707 전체회의: 도중 중단 없음, 그만두면 실패).

**에러**

| 코드 | code 상수 | 상황 |
|---|---|---|
| 401 | (나연 common) | 인증 실패 |
| 403 | (나연 common) | 남의 챌린지 |
| 404 | `CHALLENGE_NOT_FOUND` | 챌린지 id 없음 |
| 409 | `CHALLENGE_NOT_ENDED` | 아직 IN_PROGRESS(결과 미확정) → `current` 사용 안내 |

> ~~`RESULT_NO_RECORD`(기록 0건 409)~~ — **제거(0714 PM: 기록 0건도 성공 처리)**. 안드가 이미 이 코드를 참조했다면 삭제 반영 필요.

---

## 5. `POST /api/challenges/{id}/days` — 일별 지출 수신·판정 🔒 ⚠️

대응: 일별 지출 유입 → 그날 판정(upsert).
> ⚠️ `EXPENSE` 출처는 **령준 확정**(2026-07-05 분담표). 연동 방식은 **령준이 지출 합계 조회 메서드 제공, 일혁이 호출**로 결정(0707 서버팀) → 이 `POST /days`는 **테스트/시드 전용**으로 강등(운영에선 서버가 메서드로 조회).

**요청 바디(잠정)**

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| date | date | ✅ | 챌린지 기간 내 — **종료된 챌린지여도 기간 내면 수정 가능(0711 PM 확정, 현 구현 일치)** |
| spentAmount | int(원) | ✅ | ≥ 0 |
| category | string | ❌ | ⚠️ `EXPENSE` 형태로 확정되면 포함 |
| emotion | string | ❌ | ⚠️ 동상 |

```json
{ "date": "2026-06-24", "spentAmount": 5000 }
```

**서버**: `ChallengeDay(challengeId, date)` upsert → `status = spentAmount ≤ dailyLimit ? SUCCESS : OVER`
> ➕ 한도 조정 도입 후: 판정 한도 = **그날 스냅샷** — 신규 행은 현재 유효 한도를 스냅샷하고, **기존 행 재전송(upsert) 시 그 행의 스냅샷 유지**(✅화면 확정 — 조정 화면 문구 "이미 지난 N일 기록은 그대로 유지되고, 앞으로의 한도만 바뀌어요").

**응답 `200 OK`**

```json
{ "date": "2026-06-24", "spentAmount": 5000, "dailyLimit": 3333, "status": "OVER" }
```

**에러**

| 코드 | 상황 |
|---|---|
| 400 | date 기간 밖, spentAmount < 0 |
| 401 | 인증 실패 |
| 403 | 남의 챌린지 |
| 404 | 챌린지 id 없음 |

---

## 출처

- HTTP 상태코드·시맨틱: [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html)
- 인증(JWT / OAuth2 Resource Server): [Spring Security Reference](https://docs.spring.io/spring-security/reference/)
- 검증·에러(팀 공통 `{code, message, status, fieldErrors}` — 나연 common), 설정: [Spring Boot 4.1](https://docs.spring.io/spring-boot/4.1/) · [Spring Framework 7.0](https://docs.spring.io/spring-framework/reference/7.0/)
