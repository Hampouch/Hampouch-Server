# 팀 노션 "API 설계" 이관용 표 (정일혁 챌린지 파트)

> 목적: 팀 워크스페이스 "API 설계" 페이지 양식에 맞춰 우리 명세를 옮기기 위한 준비본.
> 팀 페이지 양식 = 도메인별 표(Notion DB), 컬럼: 도메인 | 기능 | HTTP 메서드 | API Path | 사람 | 상태 | 특이사항.
> 상세(Request/Response/Error)는 각 행 하위 페이지에 — 원본은 `docs/API명세_본챌린지.md` · `docs/API명세_역할확장.md` · 노션 "햄포치 명세서".
> 상태값: 구현완료 / 미구현(명세만) — 2026-07-12 기준.

## challenge 도메인

| 도메인 | 기능 | HTTP 메서드 | API Path | 사람 | 상태 | 특이사항 |
|---|---|---|---|---|---|---|
| challenge | 챌린지 생성 | POST | /api/challenges | 정일혁 | 구현완료 | 온보딩 STEP2 · 201 Created + Location 헤더 · 동시 진행 1개(중복 시 409) |
| challenge | 진행 중 챌린지 + 현황 | GET | /api/challenges/current | 정일혁 | 구현완료 | 홈 화면 · 응답 5블록(challenge/progress/consumption/warningCards/adjustment) |
| challenge | 캘린더(일별 기록) | GET | /api/challenges/{id}/calendar | 정일혁 | 구현완료 | year·month 쿼리 필수 · 기록 있는 날만 |
| challenge | 종료 결과 | GET | /api/challenges/{id}/result | 정일혁 | 구현완료 | 종료(SUCCESS/FAIL) 챌린지만 · 진행 중이면 409 · categoryBreakdown/emotionBreakdown은 령준 연동 전 빈 배열 |
| challenge | 일별 지출 수신·판정 | POST | /api/challenges/{id}/days | 정일혁 | 구현완료(시드 전용) | 운영은 령준 지출 조회 메서드로 대체 예정 · upsert |
| challenge | 지난 챌린지 리스트 | GET | /api/challenges/history | 정일혁 | 미구현 | 마이페이지 · 종료 챌린지 목록(endDate 내림차순) |
| challenge | 중도 포기 | POST | /api/challenges/{id}/give-up | 정일혁 | 미구현 | IN_PROGRESS → FAIL 즉시 확정(0707: 중단=실패) |
| challenge | 목표(하루 한도) 조정 | POST | /api/challenges/{id}/adjust | 정일혁 | 미구현 | +10%/+20% · 챌린지당 최대 2회 |
| challenge | 다음 챌린지 추천 | GET | /api/challenges/recommendation | 정일혁 | 미구현 | 성공/실패 후 프리필 · 룰 기반(0711: AI 안 씀) · 수치 공식 PM 확인 중 |
| challenge | 집중 카테고리 수정 | PUT | /api/challenges/{id}/focus-categories | 정일혁 | 미구현 | 전체 교체(멱등) |

## rest(휴식) 도메인

| 도메인 | 기능 | HTTP 메서드 | API Path | 사람 | 상태 | 특이사항 |
|---|---|---|---|---|---|---|
| rest | 휴식 시작 | POST | /api/rests | 정일혁 | 미구현 | 휴식 = 챌린지 사이 공백(유저 단위) · 진입점 = 결과 화면 |
| rest | 복귀 / 더 쉬기 | POST | /api/rests/resume | 정일혁 | 미구현 | when=NOW/TOMORROW/EXTEND |

## mini-challenge(미니 챌린지) 도메인

| 도메인 | 기능 | HTTP 메서드 | API Path | 사람 | 상태 | 특이사항 |
|---|---|---|---|---|---|---|
| mini-challenge | 그날 나의 미니 조회 | GET | /api/mini-challenges | 정일혁 | 미구현 | date 스트립 · 유저 소유(본챌 독립, 0707) |
| mini-challenge | 추천 목록 | GET | /api/mini-challenges/recommended | 정일혁 | 미구현 | 추천 카탈로그에서 조회 · 노출 랜덤 |
| mini-challenge | 미니 추가 | POST | /api/mini-challenges | 정일혁 | 미구현 | 추천(recommendedId) 또는 커스텀 · 둘 중 하나만 |
| mini-challenge | 미니 삭제 | DELETE | /api/mini-challenges/{miniChallengeId} | 정일혁 | 미구현 | 204 · 수정 불가라 삭제 후 재생성 |
| mini-challenge | 일별 체크/해제 | PUT | /api/mini-challenges/{miniChallengeId}/check | 정일혁 | 미구현 | 멱등 · 미래 날짜 400 |

## 이관 시 메모

- 팀 페이지 상단 콜아웃(`배포 서버 url` · `배포 서버 스웨거`)은 배포 후 채움 — 현재 미배포.
- `사람` 컬럼은 전부 정일혁(챌린지·휴식·미니 모두 일혁 담당). users/auth는 나연, companies류는 다른 프로젝트 잔재로 보임(팀 확인 필요).
- 각 행 하위 페이지의 상세 양식(Request/Response/Error 배치)은 팀 페이지에 아직 예시가 없음 — 회의에서 팀 공통 상세 템플릿 정하면 그 틀로 옮김. 우리 원본 명세가 이미 그 내용을 담고 있어 매핑만 하면 됨.
