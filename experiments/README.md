# 실험

## 기출 PDF 추출 실험 (이슈 #5)

멀티모달 LLM이 실제 학교 기출 PDF에서 지문과 문항을 얼마나 정확히 뽑는지 확인합니다.

1. `.env`에 `LLM_API_KEY`, `LLM_MODEL`을 설정합니다.
2. `experiments/exams/`에 기출 PDF를 넣습니다. 이 폴더의 PDF는 **커밋되지 않습니다** (저작권).
3. 실행합니다 (실제 API 호출, 비용 발생).

   ```bash
   ./gradlew llmTest --tests '*ExtractionExperiment'
   ```

4. `build/experiments/extract/`에서 결과를 확인합니다.
   - `SUMMARY.md`: 파일별 문항 수, 점검 이슈 수, 토큰, 시간
   - `{파일명}.md`: 지문과 문항을 읽기 좋게 정리한 리포트 (원본 PDF와 나란히 비교)
   - `{파일명}.json`: 원본 추출 결과

   - 일부 파일만: `EXPERIMENT_FILTER=압구정,현대 ./gradlew llmTest --tests '*ExtractionExperiment'`
   - 점검 규칙(`ExtractionChecker`)만 바꿨을 때 API 호출 없이 다시 점검: `RECHECK=true ./gradlew llmTest --tests '*ExtractionExperiment'`

결과 정리: [RESULTS-extract.md](RESULTS-extract.md)

## 확인할 것

- 문항 수가 원본과 같은가 (누락, 중복)
- 밑줄이 대괄호 규칙(`①[표현]`, `[표현]`)으로 바뀌었는가
- `[조건]`, `[보기]`가 본문과 분리되었는가
- 2단 레이아웃에서 문장이 섞이지 않았는가
- 유형 분류가 맞는가
- 스캔본에서도 읽히는가

## 출제 규칙 요약 실험 (이슈 #11)

기출 추출 실험이 저장한 `build/experiments/extract/*.json`을 시험지별로 읽어, 출제 프로필의 통계(코드 집계)와 규칙(LLM 요약)을 만들어 봅니다. 추출을 다시 하지 않아서 비용이 적습니다 (시험지당 약 10~15초).

```bash
./gradlew llmTest --tests '*RuleSummaryExperiment'
```

결과: `build/experiments/profile/{시험지}.md` — 통계, 지문당 유형 구성, 규칙과 근거 문항, 대표 문항

확인할 것: 규칙이 근거 문항에서 실제로 확인되는지, 일반론이 섞이지 않았는지, 강사가 읽기 쉬운지

## 출제 프로필 강사 검토 실험 (이슈 #13)

저장된 기출 추출 결과 1개로 프로필(v1)을 만들고, 강사 의견 반영(v2) → AI 재검토(v3)를 차례로 실행해 변경점을 확인합니다.

```bash
EXPERIMENT_FILTER=압구정 ./gradlew llmTest --tests '*ProfileReviewExperiment'
```

결과: `build/experiments/profile/review-{시험지}.md`

## 문제 생성 실험 (이슈 #15)

저장된 기출 추출 결과로 학교 규칙을 만들고, 그 시험지의 긴 지문 4개(시험범위 지문 대신)로 요약문 빈칸·어구 배열을 하나씩 만듭니다.

```bash
EXPERIMENT_FILTER=현대 ./gradlew llmTest --tests '*ProblemGenerationExperiment'
# 어법 오류 수정·조건 영작
EXPERIMENT_FILTER=현대 EXPERIMENT_TYPES=GRAMMAR_FIX,GUIDED_WRITING ./gradlew llmTest --tests '*ProblemGenerationExperiment'
```

결과: `build/experiments/generate/{시험지}.md` — 첫 시도 통과 / 재생성 후 통과 / 실패 수, 문제별 발문·[조건]·본문·[보기]·정답·해설·재생성 이유

## 시험범위 PDF 지문 분리 실험 (이슈 #12)

PDF에서 영어 지문만 골라내는지 확인합니다 (문항 번호, 한국어 발문, 선택지, 어휘 주석 제외).

```bash
EXPERIMENT_FILTER=압구정 ./gradlew llmTest --tests '*PassageSplitExperiment'
```

결과: `build/experiments/split/{파일}.md`. 기출 PDF로 실험하면 어법 문제용으로 틀리게 바꾼 문장이 그대로 나오므로, 실제 서비스에서는 교과서·모의고사 원문 PDF를 시험범위 자료로 올려야 합니다.
