# 내신 영어 AI 시험 제작 서비스 - Backend

2026 SW MVP 경진대회 백엔드 레포지토리입니다. 설계는 [docs/BACKEND_DESIGN.md](docs/BACKEND_DESIGN.md)를 참고하세요.

## 기술 스택

Java 17 · Spring Boot 4.1 · Spring Data JPA · PostgreSQL · Thymeleaf · OpenHTMLtoPDF · PDFBox · springdoc-openapi

## 로컬 실행

1. PostgreSQL에 `swmvp` 데이터베이스를 만듭니다.

   ```sql
   CREATE DATABASE swmvp;
   ```

2. 서버를 실행합니다.

   ```bash
   ./gradlew bootRun
   ```

3. Swagger UI: http://localhost:8080/swagger-ui/index.html

### 환경변수

기본값은 `src/main/resources/application.properties`에 있고, 필요한 것만 환경변수로 덮어씁니다.

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/swmvp` | DB 주소 |
| `DB_USERNAME` | `postgres` | DB 사용자 |
| `DB_PASSWORD` | `postgres` | DB 비밀번호 |
| `STORAGE_ROOT` | `./storage` | 업로드 파일 저장 경로 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | 프론트 주소 |
| `LLM_API_KEY` | 없음 | LLM API 키 (**절대 커밋하지 않기**) |
| `LLM_MODEL` | 없음 | 사용할 LLM 모델 |

## 테스트

```bash
./gradlew test
```

테스트는 H2 인메모리 DB로 실행되므로 PostgreSQL이 없어도 됩니다.

## 패키지 구조

```
com.smwu.backend
├─ common        공통 오류 응답, 예외 처리, 설정(CORS, Async, app.* 프로퍼티)
├─ auth          로그인·회원가입, X-User-Id 인터셉터
├─ user          계정
├─ academy       학원, 초대 코드, 강사 관리, 로고
├─ workspace     학교, 워크스페이스(학교 + 학년), 데이터 격리
├─ pastexam      기출 업로드·문항 추출
├─ profile       출제 프로필, 강사 검토 루프
├─ material      시험범위 자료, 지문
├─ generation    문제 생성 작업(비동기)
├─ problem       생성 문항, 검수
│  └─ type       유형별 ProblemTypeHandler
├─ worksheet     시험지 구성
├─ ai            LlmClient
└─ document      PDF 입출력
```

## API 규칙

- 성공 응답은 DTO를 그대로 반환합니다.
- 오류 응답은 `{ "code": "...", "message": "..." }` 형식이며, 코드는 `common.exception.ErrorCode`에 정의합니다.
- `/api/auth/**`를 제외한 모든 요청은 `X-User-Id` 헤더가 필요합니다.

## 브랜치 규칙

- `feature/#이슈번호-설명` (예: `feature/#6-auth`), 설정·정리 작업은 `chore/#이슈번호-설명`
- main에서 브랜치를 따고 PR로 머지합니다. PR 본문에 `Closes #이슈번호`를 적으면 머지 시 이슈가 닫힙니다.
