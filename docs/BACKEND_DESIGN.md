# 백엔드 기술 설계서 v2 (2주 MVP)

> 내신 영어 AI 시험 제작 서비스 · 2026 SW MVP 경진대회
> 팀 구성: 프론트엔드 1명, 백엔드 2명

## 0. v1 대비 변경 요약

| 항목 | v1 | v2 (확정) |
|---|---|---|
| 시험지 레이아웃 | 1단/2단 | **1단만** 지원 |
| 밑줄 표현 | 원문 밑줄 유지 시도 | **대괄호 `[ ]` 표기로 통일** (입력·저장·출력 모두) |
| 기출 텍스트 추출 | PDFBox 텍스트 + 정규식 분리 | **멀티모달 LLM에 페이지 이미지(+텍스트 PDF면 원본)를 입력**해서 문항 단위 JSON으로 받음 ([실험 결과](../experiments/RESULTS-extract.md)) |
| 출제 패턴 수치 | LLM이 추정 | **코드가 집계** (LLM은 문항 단위 분류만) |
| 출제 프로필 확정 | 분석 결과를 바로 사용 | **강사가 확인 후 OK**. 마음에 안 들면 AI 재검토 또는 강사 의견 반영 |
| `[조건]` 문구 | LLM이 생성 | **유형별 템플릿을 코드로 생성** |
| 자동 검증 | LLM 자기 검수 | **규칙 기반 검증 + 정답 없이 푸는 LLM 검증** |
| 문제 단위 | 시험범위 전체에서 생성 | **지문(Passage) 단위로 생성·배치** (출력예시 구조와 동일) |
| 계정 | 로그인 MUST (방식 미정) | **아이디/비밀번호 로그인만**. Spring Security 없음 |
| 조직 구조 | 없음 | **학원 = 조직**. 원장이 학원을 만들고 초대 코드로 강사를 추가 |
| 작업 단위 | 학교 | **워크스페이스 = 학교 + 학년**. 학원 안의 강사끼리 공유 |
| 학원 로고 | 선택 옵션 | **학원 로고 등록 후 문제지·정답지 머리글에 자동 삽입** |
| 학생 / S3 / 웹 퀴즈 | 포함 / 포함 / SHOULD | 기획만(추후 구현) / 로컬 디스크 / 시간 남으면 |
| 한글 폰트 | 언급 없음 | **TTF 폰트 임베딩 필수** |

---

## 1. 목표 산출물 (출력예시 기준)

알바 때 사용하던 시험지(`출력예시.pdf`)를 1단으로 만든 것이 목표 결과물입니다.

```
┌───────────────────────────────────────────────┐
│ 건대부고1 2학기 중간고사 서답형 대비 - 교과서 2과   [학원 로고] │  ← 매 페이지 머리글
├───────────────────────────────────────────────┤
│ Bringing New Life to Old Cities                 │  ← 지문 제목
│ As cities age, neighborhoods can become ...     │  ← 지문 본문 (영문)
│                                                 │
│ [서답형 1] 윗글의 내용을 요약할 때, 빈칸 (1), (2)에   │  ← 발문 (한국어)
│           들어갈 말을 쓰시오.                        │
│ ┌[조건]─────────────────────────┐               │
│ │1. 각 빈칸에 한 단어씩 쓸 것.        │               │  ← 조건 박스 (한국어)
│ │2. 윗글에 나온 단어를 형태 변경 없이 쓸 것.│           │
│ └──────────────────────────────┘               │
│ As a neighborhood ages, it may become (1) ____   │  ← 문제 본문
│ (1) ____________   (2) ____________             │  ← 답안 칸
│                                                 │
│ [서답형 2] 윗글에 나온 문장이 되도록 [보기]의 어구를 배열하시오. │
│ ┌[조건]…  [보기] where families / it could / …┐    │
│ → ___________________________________________   │
└───────────────────────────────────────────────┘
정답지는 별도 PDF로 출력
```

출력예시에서 확인한 구조적 특징은 다음과 같습니다.

- **지문 하나 + 그 지문에 딸린 서답형 2~3문항**이 한 묶음이고, 문항 번호는 지문마다 1부터 다시 시작합니다.
- 발문과 `[조건]`, `[보기]`는 **한국어**, 지문과 문제 본문은 **영어**입니다.
- `[조건]` 문구는 유형마다 거의 고정되어 있습니다. 그래서 LLM이 아니라 템플릿으로 생성합니다.
- 머리글 오른쪽에 **학원 로고**가 들어갑니다. 학원들은 보통 자기 로고가 들어간 시험지를 원합니다.
- 원본 PDF는 정답을 흰색 글씨로 넣어 두고, 인쇄할 때 형광펜 표시 옵션을 켜면 답지, 끄면 문제지로 쓰는 방식이었습니다. 이 서비스는 문제지와 정답지를 처음부터 별도 PDF로 출력하므로 숨김 텍스트를 쓰지 않습니다.

---

## 2. 기술 스택

| 영역 | 선택 | 비고 |
|---|---|---|
| Language | Java 17 | 현재 프로젝트 설정 그대로 |
| Framework | Spring Boot (webmvc, validation) | 현재 초기화된 버전과 로컬 기본 설정 그대로 사용 |
| ORM | Spring Data JPA | JSON 컬럼은 `@JdbcTypeCode(SqlTypes.JSON)` |
| DB | PostgreSQL | 패턴·검증 결과 등 가변 데이터는 JSONB |
| 비밀번호 | `spring-security-crypto` (BCrypt만) | Spring Security 아님. 암호화 유틸만 있는 작은 모듈 |
| LLM 연동 | OpenAI Responses API + `RestClient` + 직접 만든 `LlmClient` 인터페이스 | PDF 입력(`input_file`)과 JSON 스키마 출력(strict)을 사용. `LLM_PROVIDER=mock`이면 고정 응답 |
| 시험범위 텍스트 | Apache PDFBox 3 | 지문처럼 단순한 텍스트 PDF 전용. 기출 분석에는 쓰지 않음 |
| 템플릿 | Thymeleaf | 시험지 HTML 생성 |
| PDF 출력 | OpenHTMLtoPDF | 1단 레이아웃, 한글 TTF 임베딩, 로고 이미지 삽입 |
| 이미지 처리 | `javax.imageio` (JDK 기본) | 로고 검증·리사이즈. 별도 라이브러리 없음 |
| 파일 저장 | 로컬 디스크 (`./storage`) | S3 제외 |
| API 문서 | springdoc-openapi | 프론트 협업용 Swagger UI |
| 테스트 | JUnit 5 + Mockito | LLM은 `MockLlmClient`로 대체 |

**의도적으로 제외:** Spring Security, 세션/JWT, 이메일 발송, Docker, Redis/Kafka, 벡터 DB, OCR 라이브러리, 2단 레이아웃, 결제.

---

## 3. 계정과 학원 (B2B 구조)

### 3.1 기본 개념

학원이 서비스를 계약하는 **조직**이고, 원장과 강사는 학원에 소속된 **계정**입니다.

```
Academy (파인로드영어, 요금제: BASIC)
 ├─ User 김원장  (OWNER)
 ├─ User 이강사  (TEACHER)
 ├─ User 박강사  (TEACHER)
 │
 ├─ Workspace 건대부고 1학년   ← 이강사, 박강사가 함께 사용
 ├─ Workspace 건대부고 2학년
 └─ Workspace 광남고 1학년
```

- **한 계정은 한 학원에만 소속됩니다.** 여러 학원 출강은 지원하지 않습니다.
- 모든 데이터(기출, 프로필, 시험범위, 문제, 시험지)는 **학원 소유**입니다. 학원 안에서는 모든 강사가 공유하고, 다른 학원에서는 절대 보이지 않습니다.
- 같은 학교·같은 학년을 강사 2명이 나눠 맡는 경우가 많으므로, 기출 분석과 확정 프로필을 한 번 만들면 학원 안의 강사가 모두 재사용합니다.

### 3.2 가입과 역할 결정

가입할 때 역할을 고르지 않습니다. 가입 후 **어떤 행동을 하느냐**로 역할이 정해집니다. 가입 화면에서 원장을 직접 고르게 하면 아무나 원장이 될 수 있기 때문입니다.

```
회원가입 (아이디, 비밀번호, 이름)
   │   아이디 중복 확인
   ▼
로그인 → 소속 학원이 없으면 선택 화면
   ├─ "학원 만들기"    → 학원명 입력 (로고는 나중에) → OWNER(원장)
   └─ "초대 코드 입력"  → 원장에게 받은 코드 입력      → TEACHER(강사)
```

### 3.3 강사 초대와 내보내기

**초대**

1. 원장이 마이페이지에서 "강사 초대"를 누르면 6자리 초대 코드가 발급됩니다 (예: `FR7K2Q`).
2. 원장이 카톡 등으로 강사에게 코드를 직접 전달합니다. **이메일 발송은 하지 않습니다.**
3. 강사가 가입 후 코드를 입력하면 바로 그 학원의 강사가 됩니다.

| 규칙 | 내용 |
|---|---|
| 코드 형식 | 영문 대문자 + 숫자 6자리. 헷갈리는 문자(0, O, 1, I)는 제외 |
| 사용 횟수 | 1회용. 사용하면 `usedBy`, `usedAt` 기록 |
| 유효 기간 | 발급 후 7일 |
| 취소 | 원장이 사용 전 코드를 취소할 수 있음 |
| 강사 수 제한 | 요금제의 최대 강사 수를 넘으면 코드 발급과 사용 모두 거부 |

**내보내기**

- 원장이 강사 목록에서 강사를 내보내면 그 계정의 `academyId`와 `role`이 비워집니다. 계정 자체는 남고, 다시 초대 코드로 다른 학원에 들어갈 수 있습니다.
- 내보낸 강사가 만든 기출, 프로필, 시험지는 **학원에 그대로 남습니다.** `createdBy`에는 이름이 계속 표시됩니다.
- 원장은 자기 자신을 내보낼 수 없습니다. 원장 위임과 학원 삭제는 MVP에서 지원하지 않습니다.

### 3.4 권한

| 기능 | 원장 | 강사 |
|---|---|---|
| 학원 정보, 로고 수정 | O | X |
| 강사 초대, 코드 취소, 내보내기 | O | X |
| 워크스페이스(학교+학년) 추가 | O | O |
| 워크스페이스 삭제 | O | X |
| 기출 업로드, 프로필 확정, 강사 의견 입력 | O | O |
| 문제 생성, 검수, 시험지 PDF 출력 | O | O |
| 학원 내 다른 강사의 기출·프로필·시험지 보기 | O | O |

프로필 확정, 강사 의견, 시험지 생성에는 **누가 했는지**(`createdBy`, `confirmedBy`)를 기록해서 화면에 표시합니다.

### 3.5 요금제 (B2B 모델)

- 학원 단위 월 구독입니다. 요금제에 따라 **강사 계정 수**와 **월 생성 문항 수**가 달라집니다.

| 요금제 | 강사 수 (원장 제외) | 월 생성 문항 |
|---|---|---|
| `BASIC` | 3명 | 500문항 |
| `PRO` | 10명 | 3,000문항 |

- MVP에서는 결제를 구현하지 않습니다. `Academy.plan` 필드만 두고 **강사 수 제한만 실제로 막습니다.** 제한을 넘으면 "요금제 한도 초과" 오류를 반환합니다.
- 월 생성 문항 수는 기획서에만 적고 MVP에서는 막지 않습니다.

### 3.6 로그인 방식 (MVP 단순화)

프론트 연동을 쉽게 하기 위해 **세션, 쿠키, JWT를 쓰지 않습니다.**

```
POST /api/auth/login  { "loginId": "kim", "password": "1234" }
→ 200 { "userId": 3, "name": "김원장", "role": "OWNER", "academyId": 1 }

이후 모든 요청 헤더에 포함
X-User-Id: 3
```

- 프론트는 로그인 응답의 `userId`를 저장해 두고 모든 API 요청 헤더에 `X-User-Id`로 넣습니다.
- 백엔드는 `HandlerInterceptor`에서 헤더의 사용자를 조회해 요청에 담고, 컨트롤러에서는 `@CurrentUser User user`로 받습니다. `/api/auth/**`는 헤더 없이 호출할 수 있습니다.
- 아이디는 **중복 불가**(DB unique 제약 + 가입 전 중복 확인 API), 영문 소문자와 숫자 4~20자입니다.
- 비밀번호는 BCrypt로 해시해서 저장합니다. 백엔드 안에서만 처리되므로 프론트는 평문을 그대로 보내면 됩니다. 비밀번호 규칙은 4자 이상 하나만 둡니다.
- **한계:** 헤더 값은 누구나 바꿀 수 있어서 실제 서비스에는 쓸 수 없습니다. MVP 시연용이며, 발표에서는 "추후 JWT 기반 인증으로 전환"이라고 명시합니다.

### 3.7 학원 간 데이터 격리

로그인은 단순화하더라도 **다른 학원 데이터가 보이지 않는 것**은 B2B의 기본이므로 반드시 지킵니다.

- `academyId`는 요청 본문이나 URL에서 받지 않고 **항상 현재 사용자(`X-User-Id`)에서 꺼냅니다.**
- 워크스페이스 하위 데이터(기출, 프로필, 지문, 문제, 시험지)를 조회할 때 `workspace.academyId == user.academyId`를 확인하고, 다르면 **404**를 반환합니다. (403이 아니라 404인 이유: 다른 학원 데이터가 존재한다는 사실 자체를 알려주지 않기 위해)
- 이 확인은 `WorkspaceAccessChecker` 한 곳에서 처리하고, 서비스 계층에서 호출합니다.
- "A학원 강사가 B학원 워크스페이스 ID로 요청하면 404"를 **통합 테스트로 작성**합니다. 발표에서 멀티테넌시 데이터 격리를 보여주는 근거가 됩니다.

### 3.8 워크스페이스 (학교 + 학년)

로그인하면 학원의 워크스페이스 카드 목록이 먼저 나옵니다.

```
[파인로드영어] 대시보드
┌─────────────┐ ┌─────────────┐ ┌─────────────┐
│ 건대부고 1학년 │ │ 광남고 2학년  │ │ + 학교 추가   │
│ 기출 3회분     │ │ 기출 2회분     │ │              │
│ 프로필 확정 ✓  │ │ 프로필 검토중  │ │              │
└─────────────┘ └─────────────┘ └─────────────┘
          │
          ▼ 클릭
건대부고 1학년
 ├─ 기출        (업로드, 추출 결과)
 ├─ 출제 프로필  (확인, AI 재검토, 강사 의견, OK)
 ├─ 시험범위    (지문)
 ├─ 문제 생성   (생성, 검수)
 └─ 시험지      (PDF 출력 이력)
```

- 단위는 **학교 + 학년**입니다. 같은 학교라도 학년마다 교과서, 담당 선생님, 출제 스타일이 다릅니다.
- 학교 이름은 **공용 목록**(`School`)을 씁니다. 학교 추가 시 이름으로 검색하고, 없으면 새로 등록합니다. 같은 학교가 "건대부고", "건국대부고"로 중복 등록되는 것을 줄이기 위해서입니다.
- 워크스페이스와 그 안의 데이터는 학원별로 따로 있습니다. 두 학원이 모두 건대부고 1학년을 맡아도 기출과 프로필은 공유되지 않습니다.
- 한 학원 안에서 같은 학교 + 학년 워크스페이스는 하나만 만들 수 있습니다 (unique 제약).
- 강사별 담당 학교 지정은 제외합니다. 학원 안의 모든 강사가 모든 워크스페이스를 볼 수 있습니다.

카드에 표시하는 요약(기출 회차 수, 프로필 상태, 최근 시험지)은 워크스페이스 목록 API가 함께 반환합니다.

### 3.9 학생 (기획만, 추후 구현)

MVP에서는 구현하지 않습니다. 백엔드 2명 중 한 명의 일정이 여유 있으면 D11 이후에 시도합니다.

| 항목 | 기획 |
|---|---|
| 가입 | 같은 회원가입 후 "학생으로 합류" 선택 → 학원이 발급한 **반 코드** 입력 → `STUDENT` |
| 소속 | 학원의 특정 워크스페이스(학교+학년)에 소속 |
| 기능 | 강사가 배포한 시험지를 웹에서 풀기, 객관식·단답형 자동 채점, 결과 확인 |
| 확장 | 오답 기반 재시험 생성, 서술형 AI 보조 채점 |
| 데이터 준비 | `User.role`에 `STUDENT` 값만 미리 정의해 둠. 나머지 엔티티(`ClassCode`, `Attempt`)는 구현할 때 추가 |

---

## 4. 대괄호 표기 규칙

밑줄은 PDF 텍스트 추출 과정에서 사라지므로 **밑줄이 필요한 모든 곳을 대괄호로 표현**합니다.
입력(기출 분석), 저장(DB), 출력(PDF) 모두 같은 규칙을 씁니다.

| 용도 | 표기 | 예시 |
|---|---|---|
| 어법 선택지 | `①[표현]` ~ `⑤[표현]` | `the building ①[was] abandoned and ②[taking] over` |
| 기호 밑줄 | `ⓐ[표현]`, `(A)[표현]` | 문장 단위 밑줄도 같은 방식. 네모 선택형은 `(A)[were / was]` |
| 단일 밑줄 부분 | `[표현]` | `밑줄 친 [it]이 가리키는 것을 쓰시오` |
| 빈칸 | `(1) __________` | 대괄호 아님. 빈칸은 번호 + 밑줄 문자 |
| 첫 철자 힌트 | `(1) c________` | 출력예시와 동일 |

- `[조건]`, `[보기]` 라벨은 본문 안에 쓰지 않고 **별도 필드**(`conditions[]`, `choices[]`)에 저장합니다. 그래서 본문의 대괄호와 섞이지 않습니다.
- 기출 분석 프롬프트에 "인쇄된 밑줄만 대괄호로 표기하라(손글씨 밑줄 제외)"는 규칙을 넣습니다. 모델이 **페이지 이미지**를 보고 밑줄을 인식해 대괄호로 바꿉니다.
- 텍스트가 있는 PDF만 보내면 OpenAI가 텍스트만 전달해 밑줄이 사라지므로, **모든 페이지를 이미지로 렌더링해 함께 보냅니다** (#5 실험 결과).
- 추출 후 `ExtractionChecker`가 어법 문항의 밑줄 대괄호 개수 등을 확인하고, 이상한 문항을 검수 대상으로 표시합니다.

---

## 5. MVP 문항 유형

출력예시에 있는 두 유형을 먼저 구현하고, 나머지 두 유형은 시간이 남으면 추가합니다.

| 코드 | 유형 | 예시 발문 | `[조건]` 템플릿 (코드로 생성) | 우선순위 |
|---|---|---|---|---|
| `SUMMARY_BLANK` | 요약문 빈칸 | 윗글의 내용을 요약할 때, 빈칸 (1), (2)에 들어갈 말을 쓰시오. | 각 빈칸에 한 단어씩 쓸 것 / 윗글에 나온 단어를 형태 변경 없이 쓸 것 **또는** 제시된 첫 철자로 시작할 것 + 문맥에 맞는 형태로 쓸 것 | MUST |
| `SENTENCE_ORDER` | 어구 배열 | 윗글에 나온 문장이 되도록 [보기]의 어구를 배열하시오. | [보기]의 모든 어구를 한 번씩 사용할 것 / 단어를 추가하거나 형태를 바꾸지 말 것 / 쉼표와 마침표를 알맞게 쓸 것 | MUST |
| `GRAMMAR_FIX` | 어법 오류 수정 | 윗글의 ①~⑤ 중 어법상 틀린 것을 찾아 바르게 고쳐 쓰시오. | 틀린 것의 번호와 고친 표현을 모두 쓸 것 | 시간 남으면 |
| `GUIDED_WRITING` | 제시어 영작 | 우리말과 같은 뜻이 되도록 주어진 단어를 활용하여 영작하시오. | 제시어를 모두 사용할 것 / 필요시 어형을 바꿀 것 / N~M 단어로 쓸 것 | 시간 남으면 |

유형별 옵션(첫 철자 힌트 여부, 형태 변경 허용 여부, 단어 수)은 `ProblemOptions`로 받고, **옵션 조합에 따라 `[조건]` 문구가 결정적으로 만들어집니다.**
LLM은 발문과 조건을 쓰지 않고 **문제 내용(대상 문장, 빈칸 단어, 어구 분할, 오답 등)만** 생성합니다.

---

## 6. 도메인 모델

```
Academy ─< User (OWNER / TEACHER)
   ├─< InviteCode
   └─< Workspace >─ School (공용 학교 목록)
          ├─< PastExam ─< PastQuestion
          ├─< SchoolProfile (버전별, DRAFT → CONFIRMED)
          ├─< Material ─< Passage ─< Problem
          ├─< GenerationJob ─< Problem
          └─< Worksheet ─< WorksheetItem >─ Problem
```

| 엔티티 | 핵심 필드 | 설명 |
|---|---|---|
| `Academy` | id, name, plan, logoPath, logoWidth, logoHeight, createdAt | 학원 (계약 단위) |
| `User` | id, loginId(unique), passwordHash, name, role, academyId, createdAt | 계정. role = `OWNER` / `TEACHER` / `STUDENT`(추후). 소속 전에는 role, academyId가 null |
| `InviteCode` | id, academyId, code(unique), createdBy, expiresAt, usedBy, usedAt, canceled | 강사 초대 코드 |
| `School` | id, name, region | 공용 학교 목록 |
| `Workspace` | id, academyId, schoolId, grade, createdBy, createdAt | 학교 + 학년. `(academyId, schoolId, grade)` unique |
| `PastExam` | id, workspaceId, examYear, semester, examType, filePath, pageCount, textLayer, status(UPLOADED/EXTRACTING/EXTRACTED/FAILED), failureReason, warnings(JSON), llmModel, 토큰 수, rawResponse, createdBy | 업로드한 기출 PDF와 추출 상태 |
| `PastPassage` | id, pastExamId, code(P1...), orderNo, title, text, edited | 기출에서 추출한 지문 |
| `PastQuestion` | id, pastExamId, orderNo, section, no, type, passageCodes(JSON), stem, body, conditions(JSON), choices(JSON), answer, points, edited | 멀티모달 LLM이 추출한 기출 문항. `(section, no)`로 식별 |
| `SchoolProfile` | 아래 참고 | 출제 프로필. 강사가 확정해야 문제 생성에 사용 가능 |
| `Material` | id, workspaceId, title, filePath, createdBy | 교과서 2과, 모의고사 등 시험범위 자료 |
| `Passage` | id, materialId, orderNo, title, content | 지문 단위. 문제는 지문에 딸림 |
| `GenerationJob` | id, workspaceId, profileId, request(JSONB), status, total, done, failed, createdBy | 생성 작업 |
| `Problem` | 아래 참고 | 생성된 문항 |
| `Worksheet` | id, workspaceId, title, headerText, showLogo, createdBy, createdAt | 시험지 설정 (1단 고정) |
| `WorksheetItem` | worksheetId, problemId, orderNo | 시험지 내 문항 순서 |

`createdBy`는 모두 `User.id`이고, 화면에는 이름으로 표시합니다.

### SchoolProfile

| 필드 | 설명 |
|---|---|
| `workspaceId` | 어느 학교 + 학년의 프로필인지 |
| `version`, `parentId` | 수정할 때마다 새 버전을 만들고 이전 버전을 가리킴 |
| `status` | `DRAFT`(검토 중) / `CONFIRMED`(강사 OK) / `SUPERSEDED`(새 버전으로 대체됨) |
| `origin` | 이 버전이 만들어진 이유: `INITIAL_ANALYSIS` / `AI_RECHECK` / `TEACHER_FEEDBACK` / `MANUAL_EDIT` |
| `stats` (JSONB) | 기출에서 코드가 집계한 사실. **강사 의견으로 바뀌지 않음** |
| `rules` (JSONB) | 출제 규칙 목록. 규칙마다 출처(`PAST_EXAM` / `TEACHER`)와 근거 문항을 가짐 |
| `typeMixPerPassage` (JSONB) | 지문 1개당 기본 유형 구성. 문제 생성 화면의 기본값 |
| `teacherNotes` (JSONB) | 강사가 입력한 의견 원문과 작성자 |
| `changeSummary` | 이전 버전 대비 바뀐 점 (화면에 표시) |
| `createdBy`, `confirmedBy`, `confirmedAt` | 누가 만들고 누가 확정했는지 |

워크스페이스당 `CONFIRMED` 프로필은 하나만 유지합니다. 새 버전을 확정하면 이전 확정 버전은 `SUPERSEDED`가 됩니다.
같은 학교·학년을 맡은 강사들은 이 확정 프로필을 함께 씁니다.

### Problem

| 필드 | 예시 |
|---|---|
| `type` | `SUMMARY_BLANK` |
| `passageId` | 원문 지문 |
| `stem` | 윗글의 내용을 요약할 때, 빈칸 (1), (2)에 들어갈 말을 쓰시오. |
| `conditions` | `["각 빈칸에 한 단어씩 쓸 것.", "윗글에 나온 단어를 형태 변경 없이 쓸 것."]` |
| `body` | As a neighborhood ages, it may become (1) __________ ... |
| `choices` | 어구 배열의 `[보기]` 조각, 없으면 `[]` |
| `answer` | `{"blanks": ["lifeless", "revitalize"]}` (유형별 구조) |
| `explanation` | 해설 (한국어) |
| `evidence` | 근거가 되는 원문 문장 |
| `validationStatus` | `PASSED` / `FAILED` / `NEEDS_REVIEW` |
| `validationReport` | 검증 항목별 결과 (JSONB) |
| `reviewStatus` | `DRAFT` / `ACCEPTED` / `REJECTED` |
| `edited`, `reviewedBy` | 사용자가 수정했는지, 누가 검수했는지 |
| `llmModel`, `profileId` | 재현성 기록 (어떤 프로필 버전으로 만들었는지) |

v1에서는 검증 상태와 검수 상태가 한 필드에 섞여 있었는데, **두 필드로 분리**했습니다.

---

## 7. 파이프라인

### A. 기출 분석

```
기출 PDF 업로드 (워크스페이스 안에서)
  → [코드] PDFBox로 모든 페이지를 150dpi 이미지로 렌더링, 텍스트 레이어 유무 확인
  → [LLM, 멀티모달] 페이지 이미지(+텍스트 PDF면 원본) 입력 → 지문·문항 단위 JSON 추출 (비동기, 약 70~95초)
      (번호, 유형, 발문, 본문(대괄호 규칙), 조건, [보기]/선택지, 인쇄된 정답, 배점, 참조 지문 목록)
      학생 필기는 무시, 인쇄된 정답표가 있으면 정답 연결
  → [코드] ExtractionChecker로 구조 점검 (번호 중복, 밑줄 대괄호 개수, 선택지 수 등) + 모델 warnings
  → PastQuestion 저장 → 화면에서 점검 이슈가 있는 문항을 강조해 확인·수정
  → [코드] 유형별 개수·배점 비중·조건 빈도 집계 → stats
  → [LLM] 서술형 조건의 공통 규칙 요약 → rules (규칙마다 근거 문항 ID 포함)
  → [코드] 근거 문항 ID가 실제 이 워크스페이스의 기출 문항인지 확인, 근거 없는 규칙은 제거
  → 대표 서술형 문항 2~3개를 few-shot 예시로 선정
  → SchoolProfile v1 저장 (status = DRAFT)
  → 강사 검토 단계로 이동 (B)
```

### B. 출제 프로필 강사 검토 (학교별 맞춤의 핵심)

기출만으로는 **이번 시험**을 완벽히 예측할 수 없습니다. 예를 들어 학생들이 "학교 선생님이 이번 서술형은 어구 배열을 낸다고 하셨다"는 정보를 가져오면, 이것은 기출 분석보다 더 정확한 정보입니다.
그래서 AI가 만든 프로필을 **강사가 확인하고, 현장 정보를 더해서 확정**하는 단계를 둡니다.

```
프로필 화면 (DRAFT)
 ├─ 기출 통계 (stats)          ← 사실, 수정 불가
 ├─ 출제 규칙 (rules)          ← 출처 표시: [기출] / [강사]
 └─ 지문당 유형 구성 (typeMixPerPassage)

강사 선택
 ├─ ① OK          → CONFIRMED → 문제 생성 화면으로 이동
 ├─ ② AI 재검토    → [LLM] 기출 문항을 다시 보고 각 규칙의 근거를 재확인
 │                  → 근거 부족한 규칙 제거, 놓친 패턴 추가 → 새 DRAFT 버전
 ├─ ③ 의견 입력    → "이번엔 어구 배열 서술형 꼭 나온다고 함" 같은 자유 입력
 │                  → [LLM] 의견을 규칙·유형 구성 변경안으로 구조화 → 새 DRAFT 버전
 └─ ④ 직접 수정    → 규칙 추가/삭제, 유형 구성 숫자 수정 → 새 DRAFT 버전

새 DRAFT 버전은 이전 버전과의 변경점(changeSummary)을 보여주고, 다시 ①~④를 선택
```

**의견 반영 규칙**

- 강사 의견은 원문 그대로 `teacherNotes`에 저장하고, LLM이 구조화한 변경안은 `source: TEACHER` 규칙으로 추가합니다.
- **강사 의견이 기출 패턴과 충돌하면 강사 의견을 우선**합니다. 충돌한 기출 규칙은 삭제하지 않고 `overridden: true`로 표시해서, 강사가 무엇이 바뀌었는지 볼 수 있게 합니다.
- `stats`는 기출에서 나온 사실이므로 강사 의견으로 바꾸지 않습니다. 강사 의견은 `rules`와 `typeMixPerPassage`에만 반영됩니다.
- 강사 의견은 다음 시험에도 계속 적용할지 선택할 수 있습니다(`persistent`). 기본값은 이번 시험만 적용입니다.
- 같은 워크스페이스를 쓰는 다른 강사도 의견과 작성자를 볼 수 있습니다.
- 재검토와 의견 반영은 LLM 1회 호출이라 동기 API로 처리합니다. 프론트는 로딩을 표시합니다.

강사 의견 반영 후 프로필 예시:

```json
{
  "stats": {
    "totalQuestions": 28,
    "subjectiveRatio": 0.32,
    "subjectivePointsRatio": 0.45,
    "typeCounts": { "SUMMARY_BLANK": 4, "SENTENCE_ORDER": 1, "GRAMMAR_FIX": 3 }
  },
  "rules": [
    { "id": "r1", "text": "요약문 빈칸은 첫 철자 제시형이 많음",
      "source": "PAST_EXAM", "evidenceQuestionIds": [102, 115] },
    { "id": "r2", "text": "서술형 어법 수정 문항이 2문항 이상 출제됨",
      "source": "PAST_EXAM", "evidenceQuestionIds": [108, 119, 121], "overridden": true },
    { "id": "r3", "text": "이번 시험 서술형은 어구 배열 위주로 출제됨",
      "source": "TEACHER", "noteIndex": 0 }
  ],
  "typeMixPerPassage": { "SUMMARY_BLANK": 1, "SENTENCE_ORDER": 2 },
  "teacherNotes": [
    { "text": "학생들 말로는 이번 서술형은 어구 배열 위주로 낸다고 하심",
      "persistent": false, "createdBy": 5 }
  ],
  "changeSummary": [
    "강사 의견에 따라 어구 배열 비중을 지문당 1 → 2문항으로 변경",
    "기출 규칙 r2(어법 수정 위주)를 이번 시험에서는 비활성화"
  ],
  "exampleQuestionIds": [102, 115, 121]
}
```

**발표 포인트:** "기출 데이터 분석(과거)과 강사의 현장 정보(현재)를 결합해 **이번 시험**에 맞춘 출제 프로필을 만든다" → 일반 LLM이나 기존 문제은행과의 차별점입니다.

### C. 문제 생성

```
요청: CONFIRMED 프로필 + 지문 N개 × 유형별 개수 (+ 옵션)
      (유형별 개수는 typeMixPerPassage가 기본값, 화면에서 조정 가능)
  → GenerationJob 생성, 202 응답
  → 문항 1개 = 작업 1개로 쪼개서 병렬 실행 (동시 4개)
      1. 프롬프트 = 시스템 규칙 + 프로필(활성 rules, 강사 의견 원문, 예시 문항) + 지문 + 유형 스키마
         강사 규칙은 "우선 적용" 섹션으로 따로 넣음
      2. [LLM] 문제 내용 JSON 생성
      3. [코드] 조건 템플릿, 발문, 빈칸 표기 조립 (어구 배열은 셔플도 코드)
      4. [코드] 규칙 검증 → 실패 시 최대 2회 재생성
      5. [LLM] 정답 없이 문제를 풀게 해서 정답과 비교
      6. Problem 저장, job 진행률 갱신
```

- `DRAFT` 프로필로는 생성할 수 없습니다. API에서 409를 반환합니다.
- 문항 하나가 실패해도 작업 전체가 실패하지 않습니다. `failed` 카운트만 증가합니다.
- LLM 호출 중에는 DB 트랜잭션을 열어두지 않습니다. 결과를 받은 뒤 짧은 트랜잭션으로 저장합니다.
- `@Async` + `ThreadPoolTaskExecutor`로 처리합니다. 서버가 재시작되면 진행 중인 작업은 `FAILED`로 표시합니다.

### D. 자동 검증 (핵심 기술 포인트)

| 유형 | 규칙 검증 (코드) | LLM 검증 |
|---|---|---|
| 공통 | 필수 필드 존재, `evidence`가 지문에 실제로 있는지 확인(공백·따옴표 정규화 후 부분 일치) | 정답 없이 풀기 → 정답과 비교 |
| `SUMMARY_BLANK` | 빈칸 수 = 정답 수, 정답이 한 단어인지, "형태 변경 없이" 조건이면 정답 단어가 지문에 존재하는지, 첫 철자 힌트와 정답 첫 글자 일치 | 다른 단어도 정답이 될 수 있는지 |
| `SENTENCE_ORDER` | 정답 순서로 이은 문장 = 지문 원문 문장(정규화 후), 셔플 결과 ≠ 정답 순서, 모든 조각을 한 번씩 사용 | 불필요 (결정적으로 검증 가능) |
| `GRAMMAR_FIX` | `①[ ]~⑤[ ]` 정확히 5개, 틀린 번호 1개, 고친 표현이 원문 표현과 일치 | 다른 번호도 틀렸다고 볼 수 있는지 |
| `GUIDED_WRITING` | 모범답안에 제시어가 모두 포함(어형 변화 허용 시 원형 비교), 단어 수 범위 | 모범답안이 문법적으로 맞는지 |

- 규칙 검증 실패 → 재생성, 재생성해도 실패 → `FAILED`
- 규칙 통과 + LLM 풀이 불일치 → `NEEDS_REVIEW` (검수 화면에서 강조 표시)
- 둘 다 통과 → `PASSED`

**발표용 수치:** 생성 50문항 기준으로 ① 규칙 검증 통과율, ② 사람이 확인한 정답 오류율(검증 적용 전과 후), ③ 생성 문항과 확정 프로필의 유형 구성 일치도를 측정해 "구현 결과"에 넣습니다.

---

## 8. 학원 로고

학원들은 보통 자기 학원 로고가 들어간 시험지를 원합니다. 원장이 로고를 한 번 등록하면 그 학원 강사가 만드는 모든 문제지와 정답지 머리글에 자동으로 들어갑니다.

**업로드 처리 (원장만)**

```
로고 업로드 (multipart)
  → [코드] 형식 확인: PNG / JPG만 허용, 최대 2MB
      (확장자가 아니라 ImageIO로 실제로 읽히는지 확인)
  → [코드] 가로 600px 초과 시 비율 유지하며 축소 (PDF 용량 관리)
  → ./storage/logos/{academyId}/{uuid}.png 저장, Academy.logoPath 갱신
  → 가로·세로 픽셀 저장 (머리글 배치 계산용)
```

**PDF 배치**

- 머리글 오른쪽에 배치합니다 (출력예시와 동일).
- 높이를 **최대 12mm로 고정**하고 가로는 비율에 맞춰 자동으로 정합니다. 가로로 긴 로고는 최대 45mm에서 제한합니다.
- 이미지는 **Base64 data URI로 HTML에 직접 넣습니다.** 파일 경로 문제를 피할 수 있어 OpenHTMLtoPDF에서 가장 안정적입니다.
- 로고가 없으면 학원명을 텍스트로 대신 출력합니다.
- `Worksheet.showLogo = false`로 시험지별로 로고를 끌 수 있습니다.
- 투명 배경 PNG를 권장합니다. JPG는 흰 배경이 그대로 보입니다.

```html
<div class="header">
  <span class="title" th:text="${worksheet.headerText}">건대부고1 2학기 중간고사 서답형 대비 - 교과서 2과</span>
  <img th:if="${logoDataUri}" class="logo" th:src="${logoDataUri}"/>
  <span th:unless="${logoDataUri}" class="academy" th:text="${academy.name}"></span>
</div>
```

```css
.header { position: running(header); display: table; width: 100%; }
.header .title { display: table-cell; vertical-align: bottom; font-family: 'NanumGothic'; font-weight: 700; }
.header .logo  { max-height: 12mm; max-width: 45mm; float: right; }
```

---

## 9. PDF 출력

- **1단 고정**, A4, 여백 20mm (머리글 공간 확보를 위해 위쪽 여백 28mm)
- Thymeleaf로 HTML을 만들고 OpenHTMLtoPDF로 변환
- 머리글(학교·학기·시험·범위 + 학원 로고)은 `position: running(header)` + `@page { @top-center { content: element(header) } }`로 매 페이지 반복
- 문항 블록에 `page-break-inside: avoid`를 걸어 문항이 페이지 사이에서 잘리지 않게 함
- 문제지와 정답지를 **별도 PDF**로 출력 (같은 템플릿에서 정답 표시 여부만 바꿔 렌더링, 둘 다 로고 포함)

### 한글 폰트 (필수)

OpenHTMLtoPDF는 폰트를 직접 등록하지 않으면 한글이 빈칸이나 `#`으로 깨집니다.

```java
new PdfRendererBuilder()
    .useFont(() -> getClass().getResourceAsStream("/fonts/NanumMyeongjo.ttf"), "NanumMyeongjo")
    .useFont(() -> getClass().getResourceAsStream("/fonts/NanumGothicBold.ttf"), "NanumGothic", 700, BaseRendererBuilder.FontStyle.NORMAL, true)
    .withHtmlContent(html, baseUri)
    .toStream(out)
    .run();
```

```css
body   { font-family: 'NanumMyeongjo', serif; font-size: 10pt; line-height: 1.7; }
.stem  { font-family: 'NanumGothic'; font-weight: 700; }   /* [서답형 1] 발문 */
.cond  { border: 0.7pt solid #000; padding: 4pt 6pt; }     /* [조건] 박스 */
```

- **정적 TTF**를 사용합니다. 가변 폰트(Variable)나 일부 OTF는 임베딩이 안 될 수 있습니다.
- 나눔 폰트는 OFL 라이선스라 `src/main/resources/fonts/`에 포함해도 됩니다.
- 영문 지문도 나눔명조로 출력해도 무방합니다. 출력예시도 한 가지 명조 계열 폰트를 사용합니다.
- `①~⑤`, `→` 같은 기호가 폰트에 있는지 **D1에 샘플 PDF로 확인**합니다.

---

## 10. API (MVP)

`/api/auth/**`를 제외한 모든 요청은 `X-User-Id` 헤더가 필요합니다. `🔒`는 원장 전용입니다.

### 계정

| Method | Endpoint | 설명 |
|---|---|---|
| GET | `/api/auth/check-login-id?loginId=kim` | 아이디 중복 확인 → `{ "available": true }` |
| POST | `/api/auth/signup` | 회원가입 `{ loginId, password, name }` |
| POST | `/api/auth/login` | 로그인 → `{ userId, name, role, academyId }` |
| GET | `/api/me` | 내 정보 (소속 학원, 역할) |

### 학원과 강사

| Method | Endpoint | 설명 |
|---|---|---|
| POST | `/api/academies` | 학원 만들기 → 요청자가 OWNER가 됨 (소속 없는 사용자만) |
| POST | `/api/academies/join` | 초대 코드로 합류 `{ code }` → TEACHER (소속 없는 사용자만) |
| GET | `/api/academy` | 내 학원 정보 (요금제, 강사 수 / 최대 강사 수 포함) |
| PATCH | `/api/academy` 🔒 | 학원명 수정 |
| POST | `/api/academy/logo` 🔒 | 로고 업로드 (multipart, PNG/JPG, 2MB 이하) |
| GET | `/api/academy/logo` | 로고 이미지 조회 (미리보기용) |
| DELETE | `/api/academy/logo` 🔒 | 로고 삭제 |
| GET | `/api/academy/members` | 소속 원장·강사 목록 |
| DELETE | `/api/academy/members/{userId}` 🔒 | 강사 내보내기 |
| POST | `/api/academy/invites` 🔒 | 초대 코드 발급 → `{ code, expiresAt }` |
| GET | `/api/academy/invites` 🔒 | 발급한 코드 목록 (사용 여부 포함) |
| DELETE | `/api/academy/invites/{id}` 🔒 | 코드 취소 |

### 워크스페이스

| Method | Endpoint | 설명 |
|---|---|---|
| GET | `/api/schools?query=건대` | 공용 학교 검색 |
| POST | `/api/schools` | 학교 등록 (검색 결과에 없을 때) |
| GET | `/api/workspaces` | 내 학원의 워크스페이스 카드 목록 (기출 수, 프로필 상태, 최근 시험지 포함) |
| POST | `/api/workspaces` | 워크스페이스 추가 `{ schoolId, grade }` |
| GET | `/api/workspaces/{id}` | 워크스페이스 상세 |
| DELETE | `/api/workspaces/{id}` 🔒 | 워크스페이스 삭제 |

### 기출과 출제 프로필

| Method | Endpoint | 설명 |
|---|---|---|
| POST | `/api/workspaces/{id}/past-exams` | 기출 PDF 업로드 (multipart: file, examYear, semester, examType). PDF만, 30페이지 이하 |
| GET | `/api/workspaces/{id}/past-exams` | 기출 목록 (상태, 객관식·서답형 문항 수) |
| GET | `/api/past-exams/{id}` | 기출 상세. 추출 중 폴링용 (status) |
| DELETE | `/api/past-exams/{id}` | 기출 삭제 (추출 중이면 409) |
| POST | `/api/past-exams/{id}/analyze` | 문항 추출 시작 → 202, 비동기 (1~2분). 다시 추출하면 기존 결과 교체 |
| GET | `/api/past-exams/{id}/questions` | 추출 결과: 지문, 문항, 문항·지문별 점검 이슈, 모델 경고 |
| PATCH | `/api/past-questions/{id}` | 문항 수정 → 이슈를 다시 계산한 전체 결과 반환 |
| PATCH | `/api/past-passages/{id}` | 지문 수정 (밑줄 대괄호 보정 등) → 전체 결과 반환 |
| POST | `/api/workspaces/{id}/profiles` | 기출로 프로필 생성 → DRAFT v1 |
| GET | `/api/workspaces/{id}/profiles` | 프로필 버전 목록 |
| GET | `/api/workspaces/{id}/profiles/confirmed` | 현재 확정 프로필 |
| GET | `/api/profiles/{id}` | 프로필 상세 (변경점 포함) |
| POST | `/api/profiles/{id}/recheck` | AI 재검토 → 새 DRAFT 버전 |
| POST | `/api/profiles/{id}/feedback` | 강사 의견 반영 → 새 DRAFT 버전 |
| PATCH | `/api/profiles/{id}` | 직접 수정 (규칙, 유형 구성) → 새 DRAFT 버전 |
| POST | `/api/profiles/{id}/confirm` | 확정 (OK) |

### 시험범위, 문제 생성, 시험지

| Method | Endpoint | 설명 |
|---|---|---|
| POST | `/api/workspaces/{id}/materials` | 시험범위 자료 업로드 → 지문 분리 |
| GET | `/api/workspaces/{id}/materials` | 자료 목록 |
| GET | `/api/materials/{id}/passages` | 지문 목록 |
| PATCH | `/api/passages/{id}` | 지문 수정 |
| POST | `/api/workspaces/{id}/generation-jobs` | 생성 작업 시작 → 202 (확정 프로필만 허용) |
| GET | `/api/generation-jobs/{id}` | 진행률 조회 (프론트에서 2초 간격 폴링) |
| GET | `/api/generation-jobs/{id}/problems` | 생성 문항 목록 |
| PATCH | `/api/problems/{id}` | 수정 / 채택 / 폐기 |
| POST | `/api/problems/{id}/regenerate` | 개별 재생성 |
| POST | `/api/workspaces/{id}/worksheets` | 시험지 생성 (문항 순서, 머리글, 로고 표시 여부) |
| GET | `/api/workspaces/{id}/worksheets` | 시험지 목록 (작성자 포함, 학원 내 공유) |
| GET | `/api/worksheets/{id}/pdf` | 문제지 PDF |
| GET | `/api/worksheets/{id}/answer-pdf` | 정답지 PDF |

`{id}`로 접근하는 모든 워크스페이스 하위 리소스는 **3.7 데이터 격리** 규칙에 따라 다른 학원 것이면 404를 반환합니다.

### 요청 예시

강사 의견 반영:

```json
POST /api/profiles/7/feedback
X-User-Id: 5
{
  "text": "학생들 말로는 이번 서술형은 어구 배열 위주로 낸다고 하심",
  "persistent": false
}
```

응답은 새로 만든 DRAFT 프로필과 `changeSummary`입니다. 강사가 확인 후 `/confirm`을 호출합니다.

문제 생성:

```json
POST /api/workspaces/2/generation-jobs
X-User-Id: 5
{
  "profileId": 8,
  "passageIds": [11, 12, 13],
  "perPassage": [
    { "type": "SUMMARY_BLANK", "count": 1, "options": { "firstLetterHint": true, "blankCount": 2 } },
    { "type": "SENTENCE_ORDER", "count": 2 }
  ]
}
```

### 공통 오류 응답

```json
{ "code": "PLAN_LIMIT_EXCEEDED", "message": "요금제의 최대 강사 수(3명)를 초과했습니다." }
```

| HTTP | code 예시 | 상황 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 입력값 검증 실패 |
| 401 | `UNAUTHENTICATED` | `X-User-Id` 없음 또는 존재하지 않는 사용자 |
| 401 | `LOGIN_FAILED` | 아이디 또는 비밀번호 불일치 |
| 403 | `OWNER_ONLY` | 강사가 원장 전용 기능 호출 |
| 403 | `NO_ACADEMY` | 학원 소속 전에 학원 기능 호출 |
| 404 | `NOT_FOUND` | 없는 리소스 또는 다른 학원 리소스 |
| 409 | `LOGIN_ID_DUPLICATED` | 아이디 중복 |
| 409 | `INVITE_CODE_INVALID` | 만료·사용됨·취소된 코드 |
| 409 | `PLAN_LIMIT_EXCEEDED` | 요금제 강사 수 초과 |
| 409 | `WORKSPACE_DUPLICATED` | 같은 학교 + 학년 워크스페이스가 이미 있음 |
| 409 | `PROFILE_NOT_CONFIRMED` | 확정되지 않은 프로필로 생성 요청 |

---

## 11. 패키지 구조

```
com.smwu.backend
├─ common        예외, 공통 응답, 설정(Async, CORS, 파일 저장 경로)
├─ auth          AuthController, CurrentUserInterceptor, @CurrentUser 리졸버
├─ user          User, UserService(가입·중복 확인)
├─ academy       Academy, InviteCode, AcademyService(생성·합류·내보내기·요금제 제한), LogoService
├─ workspace     School, Workspace, WorkspaceService, WorkspaceAccessChecker(데이터 격리)
├─ pastexam      PastExam, PastQuestion, ExtractionService
├─ profile       SchoolProfile, ProfileAnalysisService(집계·규칙 요약),
│                ProfileRevisionService(재검토·강사 의견·직접 수정·확정)
├─ material      Material, Passage
├─ generation    GenerationJob, GenerationService, JobRunner
├─ problem       Problem, ProblemService(검수)
│  └─ type       유형별 ProblemTypeHandler (프롬프트 스키마, 조건 템플릿, 조립, 검증)
├─ worksheet     Worksheet, WorksheetService
├─ ai            LlmClient, OpenAiLlmClient, MockLlmClient, PromptLoader
└─ document      PdfTextReader(PDFBox), PdfRenderer(OpenHTMLtoPDF)
```

**유형별 로직은 `ProblemTypeHandler` 하나에 모읍니다.** 새 유형을 추가할 때 클래스 하나만 만들면 됩니다.

```java
public interface ProblemTypeHandler {
    ProblemType type();
    JsonSchema outputSchema();                               // LLM에 요구할 JSON 형식
    List<String> conditions(ProblemOptions options);         // [조건] 템플릿
    Problem assemble(LlmDraft draft, Passage passage, ProblemOptions options); // 발문·빈칸·셔플 조립
    List<RuleViolation> validate(Problem problem, Passage passage);            // 규칙 검증
}
```

**구현 원칙**
- Controller에서 LLM을 호출하지 않습니다.
- `academyId`는 요청에서 받지 않고 항상 `@CurrentUser`에서 꺼냅니다.
- 워크스페이스 하위 리소스는 서비스 계층에서 `WorkspaceAccessChecker`를 거친 뒤 조회합니다.
- 원장 전용 기능은 서비스 계층에서 `user.requireOwner()`로 확인합니다.
- 프롬프트는 `resources/prompts/*.txt`로 분리합니다. (`extract-questions`, `summarize-rules`, `recheck-profile`, `apply-feedback`, `generate-{type}`, `blind-solve`)
- LLM 원본 응답은 디버깅용으로 따로 저장하고 화면 데이터와 섞지 않습니다.
- `LlmClient`는 JSON 파싱 실패 시 1회 재요청합니다.
- 프로필은 수정하지 않고 항상 새 버전을 만듭니다. 이미 생성된 문제가 어떤 프로필로 만들어졌는지 추적할 수 있습니다.

---

## 12. 2주 일정 (백엔드 2명)

두 트랙으로 나눠 병렬로 진행합니다. **트랙 A는 AI 파이프라인, 트랙 B는 계정·학원·워크스페이스·PDF**입니다.
패키지가 겹치지 않도록 나눴기 때문에 충돌 없이 작업할 수 있습니다.

| 날짜 | 트랙 A (AI 파이프라인) | 트랙 B (플랫폼·출력) |
|---|---|---|
| D1 (10/1) | `LlmClient` 인터페이스 + Mock, 실제 API 호출 확인 | PostgreSQL 연결, 공통 응답/예외, **한글 폰트 + 로고 1단 PDF 샘플** |
| D2 | **기출 PDF 멀티모달 추출 실험** (실제 기출 2~3개) | 회원가입·아이디 중복 확인·로그인, `X-User-Id` 인터셉터 |
| D3 | 기출 업로드·추출 저장 | 학원 만들기, 초대 코드 발급·합류·취소, 강사 내보내기, 강사 수 제한 |
| D4 | 추출 결과 조회·수정 API | 학교 검색·등록, 워크스페이스 CRUD, **데이터 격리 + 통합 테스트** |
| D5 | 프로필 집계 + 규칙 요약 (근거 문항 포함) | 시험범위 업로드 → 지문 분리 |
| D6 | **프로필 검토 루프** (확정 / AI 재검토 / 강사 의견 / 직접 수정) | 로고 업로드·리사이즈, 워크스페이스 카드 요약 |
| D7–D8 | `SUMMARY_BLANK`, `SENTENCE_ORDER` 생성 + 규칙 검증 | 검수 API (수정·채택·폐기), 시험지 구성 API |
| D9 | GenerationJob 비동기, 재시도, 진행률 | 시험지 PDF + 정답지 PDF (로고 포함) |
| D10 | 블라인드 풀이 검증, 개별 재생성 | 프론트 연동 지원, Swagger 정리, 버그 수정 |
| D11–D12 | `GRAMMAR_FIX`, `GUIDED_WRITING` (시간 허용 시) | 학생 기능 (시간 허용 시, 3.9 참고) |
| D13 | 실제 기출·범위로 end-to-end, **평가 수치 측정** | end-to-end, 시연용 데모 데이터(학원·강사 2명·워크스페이스 2개) 준비 |
| D14 (10/14) | 버그 수정, 시연 녹화 지원 | 버그 수정, 시연 녹화 지원 → 제출 |

**D1과 D2가 가장 중요합니다.** 한글 PDF 출력과 기출 추출이 되는지를 초반에 확인해야 나머지 일정이 안전합니다.

**트랙 간 약속:** 트랙 A는 D4까지 `workspaceId`를 파라미터로만 받고, D4에 트랙 B의 `WorkspaceAccessChecker`가 나오면 연결합니다.

**시연 시나리오**

1. 원장이 학원을 만들고 로고를 등록한 뒤 초대 코드를 발급한다.
2. 강사가 가입해서 초대 코드를 입력하고 학원에 합류한다.
3. 강사가 "건대부고 1학년" 워크스페이스에 기출을 업로드하고 AI가 분석한 프로필을 확인한다.
4. "이번엔 어구 배열 위주로 낸다고 함" 의견을 입력하고, 변경점을 확인한 뒤 OK를 누른다.
5. 문제를 생성한 결과에서 어구 배열 비중이 늘어난 것을 확인하고 검수한다.
6. 학원 로고가 들어간 문제지와 정답지 PDF를 출력한다.
7. 다른 강사 계정으로 로그인해 같은 워크스페이스의 프로필과 시험지가 공유되는 것을 보여준다.

---

## 13. 리스크

| 리스크 | 대응 |
|---|---|
| 멀티모달 추출이 부정확함 | 추출 결과 수정 API 제공. 시연용 기출은 미리 검수해 둠 |
| 강사 의견을 LLM이 잘못 해석함 | 변경안을 바로 적용하지 않고 새 DRAFT로 만들어 `changeSummary`를 보여준 뒤 강사가 확정 |
| AI 재검토가 매번 다른 결과를 냄 | `stats`는 코드 집계라 고정. 규칙은 근거 문항이 있는 것만 남김 |
| `X-User-Id` 헤더 위조 가능 | MVP 시연 전용으로 명시. 추후 JWT 인증으로 전환 (발표 자료에 한계로 기재) |
| 다른 학원 데이터 노출 | `academyId`는 항상 현재 사용자에서 꺼냄, `WorkspaceAccessChecker` 일원화, 통합 테스트 |
| 같은 워크스페이스를 두 강사가 동시에 수정 | 프로필은 항상 새 버전 생성이라 덮어쓰기 없음. 확정은 마지막 확정이 우선 |
| 한글/기호 폰트 깨짐 | D1에 샘플로 확인. 기호가 없으면 기호용 폰트를 추가로 등록 |
| 로고 이미지 문제 (너무 크거나 깨진 파일) | 업로드 시 ImageIO로 검증, 600px로 축소, 머리글 크기 제한 |
| LLM 응답 지연 | 문항 단위 병렬 처리, 시연 영상은 사전 녹화 |
| 생성 문항 정답 오류 | 규칙 검증 + 블라인드 풀이 + 강사 검수 |
| 저작권 | 서비스가 콘텐츠를 제공하지 않고, 사용자가 권한 있는 자료를 업로드하는 구조 |
