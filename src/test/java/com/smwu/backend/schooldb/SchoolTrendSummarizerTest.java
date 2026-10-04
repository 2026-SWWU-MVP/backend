package com.smwu.backend.schooldb;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.LlmRequest;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.schooldb.domain.ExamRoundStats;
import com.smwu.backend.schooldb.domain.SchoolExam;
import com.smwu.backend.schooldb.service.SchoolTrendCalculator;
import com.smwu.backend.schooldb.service.SchoolTrendSummarizer;
import com.smwu.backend.schooldb.service.SchoolTrendSummarizer.Summary;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchoolTrendSummarizerTest {

    private static final String QUOTE = "As cities age, neighborhoods can become old and lifeless.";

    private final List<LlmRequest> requests = new ArrayList<>();
    private final Deque<Object> responses = new ArrayDeque<>();
    private final LlmClient fakeLlm = new LlmClient() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> LlmResult<T> generate(LlmRequest request, Class<T> responseType) {
            requests.add(request);
            return new LlmResult<>((T) responses.poll(), "{}", "fake-model", 0, 0);
        }
    };
    private final SchoolTrendSummarizer summarizer = new SchoolTrendSummarizer(fakeLlm, new PromptLoader(JsonMapper.builder().build()));

    private final SchoolExam round = round();
    private final SchoolTrendCalculator.Trend trend = SchoolTrendCalculator.calculate(List.of(round));

    @Test
    void 통계만_넣고_영어_문장이_있으면_이유를_알려주고_다시_만든다() throws Exception {
        responses.add(draft("요약문 빈칸 중심 학교", List.of("본문 문장 " + QUOTE + " 이 출제되었다."), List.of()));
        responses.add(draft("요약문 빈칸 중심 학교 (아직 기출 1회분 기준)", List.of("서술형 비중은 30%다."), List.of("요약문 빈칸을 연습한다.")));

        Summary summary = summarizer.summarize("테스트고등학교 1학년", "학교 DB 기출 1회분 · 학원 1곳 기준", trend, List.of(round),
                Map.of());

        assertThat(summary.attempts()).isEqualTo(2);
        assertThat(summary.points()).containsExactly("서술형 비중은 30%다.");
        assertThat(summary.prepTips()).containsExactly("요약문 빈칸을 연습한다.");
        assertThat(requests.get(0).userPrompt())
                .contains("학교 DB 기출 1회분", "2025년 1학기 중간 (학원 1곳): 30문항, 객관식 21 / 서술형 9 (30%)", "요약문 빈칸 6")
                .doesNotContainPattern("[A-Za-z]{4,}\\s+[A-Za-z]{4,}");
        assertThat(requests.get(1).userPrompt()).contains("이전 응답에서 지켜지지 않은 점", "영어 문장이 들어 있다");
    }

    @Test
    void 다시_만들어도_어기면_그_문장은_버리고_제목은_코드로_만든다() throws Exception {
        responses.add(draft(QUOTE, List.of(QUOTE, "어구 배열은 없었다."), List.of()));
        responses.add(draft(QUOTE, List.of(QUOTE, "어구 배열은 없었다."), List.of()));

        Summary summary = summarizer.summarize("테스트고등학교 1학년", "학교 DB 기출 1회분 · 학원 1곳 기준", trend, List.of(round),
                Map.of());

        assertThat(summary.attempts()).isEqualTo(2);
        assertThat(summary.headline()).isEqualTo("테스트고등학교 1학년 · 학교 DB 기출 1회분 · 학원 1곳 기준");
        assertThat(summary.points()).containsExactly("어구 배열은 없었다.");
    }

    private static Object draft(String headline, List<String> points, List<String> tips) throws Exception {
        Class<?> type = Class.forName("com.smwu.backend.schooldb.service.SchoolTrendSummarizer$Draft");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, List.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(headline, points, tips);
    }

    private static SchoolExam round() {
        SchoolExam round = new SchoolExam(1L, 1, 2025, 1, ExamType.MIDTERM);
        ReflectionTestUtils.setField(round, "id", 7L);
        ReflectionTestUtils.setField(round, "stats", new ExamRoundStats(30, 21, 9, 0.3, null,
                Map.of(QuestionType.OBJ_BLANK, 21, QuestionType.SUMMARY_BLANK, 6, QuestionType.GRAMMAR_FIX, 3), Map.of(), 8));
        return round;
    }
}
