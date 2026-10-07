# 프론트엔드 연동 가이드

백엔드를 띄우고 화면 흐름대로 API를 붙이는 방법입니다. 각 API의 요청·응답 필드는 Swagger를 기준으로 합니다.

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs (타입 생성기에 넣을 수 있습니다)
- 설계 전체: [BACKEND_DESIGN.md](BACKEND_DESIGN.md)

---

## 1. 백엔드 실행 (LLM 비용 없이)

`LLM_PROVIDER`의 기본값이 `mock`이라, 아무 설정 없이 띄우면 AI 호출 대신 고정 응답(`src/main/resources/mock-llm/`)을 돌려줍니다. 기출 추출, 프로필, 문제 생성이 1초 안에 끝나므로 화면 개발에 적합합니다.

```bash
# PostgreSQL 없이 (가장 간단): 파일 H2 DB + Mock LLM
SPRING_PROFILES_ACTIVE=h2 ./gradlew bootRun
```

```bash
# PostgreSQL(swmvp DB)이 있을 때
./gradlew bootRun
```

- IntelliJ: `BackendApplication` 실행 설정 → Active profiles에 `h2`
- H2 데이터는 `storage/h2/`에 남습니다. 처음부터 다시 하려면 서버를 끄고 폴더를 지웁니다.
- 실제 AI 결과를 보려면 `.env`에 `LLM_PROVIDER=openai`, `LLM_MODEL`, `LLM_API_KEY`를 넣습니다 (비용 발생).
- CORS 허용 주소 기본값은 `http://localhost:3000`, `http://localhost:5173`입니다. 다른 포트면 `CORS_ALLOWED_ORIGINS`로 바꿉니다.

### Mock 모드에서 알아둘 것

Mock 응답은 특정 지문에 맞춰져 있어서, 시험범위 텍스트에 아래 두 지문을 넣으면 **첫 지문의 요약문 빈칸**과 **둘째 지문의 어구 배열**이 `PASSED`로 나옵니다. 나머지 조합은 규칙 검증에 걸려 `FAILED` 문항이 되므로, 화면의 실패 표시를 확인할 때 쓰면 됩니다.

```
# Bringing New Life to Old Cities
As cities age, neighborhoods can become old and lifeless, which may cause citizens to move away. When this happens, a collaboration between the local government and citizens is an effective way to revitalize the area.
---
# Crime and Budget
For years, the neighborhood was known for its high crime rates. However, having a limited budget, the government was unable to do so and had to come up with a new plan.
```

---

## 2. 인증

세션·쿠키·JWT 없이, 로그인 응답의 `userId`를 모든 요청 헤더에 넣습니다.

```
POST /api/auth/login  { "loginId": "kim", "password": "1234" }
→ { "userId": 3, "loginId": "kim", "name": "김원장", "role": "OWNER", "academyId": 1 }

이후 모든 요청: X-User-Id: 3   (/api/auth/** 제외)
```

| 상태 | 화면 |
|---|---|
| `role`·`academyId`가 null | 학원 소속 전 → "학원 만들기" 또는 "초대 코드 입력" 화면 |
| `OWNER` | 원장: 강사 초대·내보내기, 로고, 워크스페이스 삭제 가능 |
| `TEACHER` | 강사: 🔒 표시 API를 부르면 403 `OWNER_ONLY` |

Swagger에서는 오른쪽 위 **Authorize**에 userId를 넣으면 모든 요청에 헤더가 붙습니다.

---

## 3. 화면별 API 순서

### 가입·학원

1. `GET /api/auth/check-login-id?loginId=kim` → `{ available }`
2. `POST /api/auth/signup` `{ loginId, password, name }`
3. `POST /api/auth/login`
4. 소속 전이면
   - 원장: `POST /api/academies` `{ name }` → 응답이 갱신된 내 정보 (`role: OWNER`)
   - 강사: `POST /api/academies/join` `{ code }` (원장이 `POST /api/academy/invites`로 발급한 6자리)

### 대시보드 (워크스페이스 카드)

- `GET /api/workspaces` → 카드 목록. 카드 요약은 `summary` (`pastExamCount`, `profileStatus`, `confirmedProfileVersion`, `latestWorksheet`)
- 학교 추가: `GET /api/schools?query=건대` → 없으면 `POST /api/schools` → `POST /api/workspaces` `{ schoolId, grade }`

### 기출 → 출제 프로필

1. 업로드: `POST /api/workspaces/{id}/past-exams` (multipart: `file`, `examYear`, `semester`, `examType`=`MIDTERM`|`FINAL`)
2. 분석 시작: `POST /api/past-exams/{id}/analyze` → 202
3. 폴링: `GET /api/past-exams/{id}` 2초 간격, `status`가 `EXTRACTED` 또는 `FAILED`(→ `failureReason`)
4. 추출 결과 확인·수정: `GET /api/past-exams/{id}/questions`, `PATCH /api/past-questions/{id}`, `PATCH /api/past-passages/{id}` (점검 이슈는 `issues`로 표시)
5. 프로필 생성: `POST /api/workspaces/{id}/profiles` (LLM 호출로 수 초~수십 초, 로딩 표시)
   - 우리 학원 기출이 없어도 학교 DB에 다른 학원 기출이 있으면 만들어집니다 (`changeSummary` 첫 줄에 안내)
6. 검토: `GET /api/profiles/{id}` → 통계(`stats`), 규칙(`rules[].source`: `PAST_EXAM` [기출] / `SCHOOL_DB` [학교 DB] / `TEACHER` [강사])
   - AI 재검토 `POST /api/profiles/{id}/recheck`, 강사 의견 `POST /api/profiles/{id}/feedback` `{ text, persistent }`, 직접 수정 `PATCH /api/profiles/{id}`
   - 모두 **새 DRAFT 버전**을 돌려줍니다. 바뀐 점은 `changeSummary`
7. 확정: `POST /api/profiles/{id}/confirm` (문제 생성은 확정 프로필로만 가능)

학교 경향 카드: `GET /api/workspaces/{id}/school-trends` (통계·변화), `GET /api/workspaces/{id}/school-trends/summary` (요약 문장, 처음엔 수 초)

### 시험범위 → 문제 생성

1. 자료 등록
   - PDF: `POST /api/workspaces/{id}/materials` (multipart: `file`, `title`) → `GET /api/materials/{id}` 폴링, `status`가 `SPLIT` 또는 `FAILED`
   - 텍스트: `POST /api/workspaces/{id}/materials/text` `{ title, text }` (지문은 `---` 줄로 구분, `# 제목` 줄은 지문 제목) → 바로 `SPLIT`
2. 지문 확인·수정: `GET /api/materials/{id}/passages`, `PATCH /api/passages/{id}`
3. 생성 시작: `POST /api/workspaces/{id}/generation-jobs`

   ```json
   { "profileId": 8, "passageIds": [11, 12],
     "perPassage": [{ "type": "SUMMARY_BLANK", "count": 1, "options": { "blankCount": 2, "firstLetterHint": true } },
                    { "type": "SENTENCE_ORDER", "count": 1 }] }
   ```
   `perPassage`를 비우면 확정 프로필의 지문당 유형 구성을 씁니다. 유형: `SUMMARY_BLANK`, `SENTENCE_ORDER`, `GRAMMAR_FIX`, `GUIDED_WRITING`
4. 폴링: `GET /api/generation-jobs/{id}` → `progress`(0~100), `passed`/`needsReview`/`failed`, `status`가 `COMPLETED`/`FAILED`
5. 결과: `GET /api/generation-jobs/{id}/problems` (지문 순서 → 유형 순서)

### 검수 → 시험지 → PDF

1. 문항 카드: `validationStatus` (`PASSED` / `NEEDS_REVIEW` 강조 표시 / `FAILED`), 실패·확인 이유는 `validationIssues`
2. 수정·채택·폐기: `PATCH /api/problems/{id}` `{ stem?, conditions?, body?, choices?, answerText?, explanation?, reviewStatus?, rejectReason? }` (보낸 항목만 바뀜)
   - 폐기할 때 사유(`rejectReason`)를 받아 두면 다음 생성에서 같은 문제를 피합니다. 수정한 내용도 다음 생성에 반영됩니다 ("쓸수록 맞춤")
   - 채택률: `GET /api/workspaces/{id}/review-stats` (유형별·생성 작업별 채택·폐기·수정 수와 `acceptanceRate`)
3. 다시 생성: `POST /api/problems/{id}/regenerate` (수 초~수십 초, 검수 상태 초기화)
4. 시험지 구성: `GET /api/workspaces/{id}/problems?reviewStatus=ACCEPTED` → `POST /api/workspaces/{id}/worksheets` `{ title, headerText, showLogo, problemIds }`
   - 채택한 문항만 넣을 수 있고, 같은 지문 문항은 자동으로 모여 지문마다 번호가 1부터 매겨집니다 (`sections[].items[].no`)
5. 미리보기: `GET /api/worksheets/{id}`
6. 다운로드: `GET /api/worksheets/{id}/pdf`, `GET /api/worksheets/{id}/answer-pdf` (파일명은 `Content-Disposition`의 `filename*`)

### 학원 관리 (원장)

- 강사: `GET /api/academy/members`, `DELETE /api/academy/members/{userId}`
- 초대 코드: `POST/GET /api/academy/invites`, `DELETE /api/academy/invites/{id}` (강사 수가 요금제 한도면 409 `PLAN_LIMIT_EXCEEDED`)
- 로고: `POST /api/academy/logo` (multipart `file`, PNG/JPG 2MB), 미리보기 `GET /api/academy/logo` (이미지, 없으면 404)

---

## 4. 오류 처리

모든 오류는 같은 형식입니다. `message`는 사용자에게 그대로 보여줘도 되는 한국어입니다.

```json
{ "code": "PROFILE_NOT_CONFIRMED", "message": "확정된 출제 프로필로만 문제를 생성할 수 있습니다." }
```

| HTTP | 처리 |
|---|---|
| 400 | 입력값 오류. `message`를 폼 아래에 표시 |
| 401 | 로그인 화면으로 (저장한 userId 삭제) |
| 403 `NO_ACADEMY` | 학원 만들기 / 초대 코드 화면으로 |
| 403 `OWNER_ONLY` | 원장 전용 안내 |
| 404 | 없는 리소스 (다른 학원 데이터도 404) |
| 409 | 상태 충돌. 예: `EXTRACTION_IN_PROGRESS`(추출 중), `PROFILE_NOT_CONFIRMED`, `WORKSPACE_DUPLICATED` |
| 502 `LLM_ERROR` | AI 응답 실패. 잠시 후 다시 시도 |

---

## 5. 시간이 걸리는 API

| API | 실제 LLM 기준 | 처리 |
|---|---|---|
| 기출 분석 | 1~2분 | 202 + 폴링 |
| 시험범위 PDF 지문 분리 | 30초~1분 | 202 + 폴링 |
| 문제 생성 작업 (48문항) | 약 2분 | 202 + 폴링 (`progress`) |
| 프로필 생성·재검토·의견 반영 | 수 초~수십 초 | 동기 응답, 로딩 표시 |
| 문항 다시 생성 | 수 초~수십 초 | 동기 응답, 로딩 표시 |
| 학교 경향 요약 | 처음 수 초, 이후 캐시 | 동기 응답 |
