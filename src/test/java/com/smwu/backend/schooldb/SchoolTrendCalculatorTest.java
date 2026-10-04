package com.smwu.backend.schooldb;

import com.smwu.backend.pastexam.domain.ExamType;
import com.smwu.backend.pastexam.extraction.QuestionType;
import com.smwu.backend.schooldb.domain.ExamRoundStats;
import com.smwu.backend.schooldb.domain.SchoolExam;
import com.smwu.backend.schooldb.service.SchoolTrendCalculator;
import com.smwu.backend.schooldb.service.SchoolTrendCalculator.Trend;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchoolTrendCalculatorTest {

    @Test
    void 최근_회차_가중으로_합치고_변화를_찾는다() {
        SchoolExam r2024 = round(2024, 1, ExamType.MIDTERM, 21, 9, Map.of(
                QuestionType.OBJ_BLANK, 21, QuestionType.SUMMARY_BLANK, 6, QuestionType.GRAMMAR_FIX, 3));
        SchoolExam r2025a = round(2025, 1, ExamType.MIDTERM, 17, 13, Map.of(
                QuestionType.OBJ_BLANK, 17, QuestionType.SUMMARY_BLANK, 6, QuestionType.SENTENCE_ORDER, 4, QuestionType.GRAMMAR_FIX, 3));
        SchoolExam r2025b = round(2025, 2, ExamType.FINAL, 16, 14, Map.of(
                QuestionType.OBJ_BLANK, 16, QuestionType.SUMMARY_BLANK, 6, QuestionType.SENTENCE_ORDER, 6, QuestionType.GUIDED_WRITING, 2));

        // 입력 순서와 무관
        Trend trend = SchoolTrendCalculator.calculate(List.of(r2025b, r2024, r2025a));

        assertThat(trend.examCount()).isEqualTo(3);
        // 가중치 2024 ×0.5, 2025 ×1.0 → 서술형 (4.5 + 13 + 14) / (15 + 30 + 30)
        assertThat(trend.weightedSubjectiveRatio()).isEqualTo(0.42);
        assertThat(trend.averageQuestions()).isEqualTo(30.0);
        assertThat(trend.typeRatios().keySet().iterator().next()).isEqualTo(QuestionType.OBJ_BLANK);
        assertThat(trend.typeMixPerPassage().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);
        assertThat(trend.typeMixPerPassage()).containsKeys(QuestionType.SUMMARY_BLANK, QuestionType.SENTENCE_ORDER);

        assertThat(trend.byYear()).extracting(SchoolTrendCalculator.YearTrend::year).containsExactly(2024, 2025);
        assertThat(trend.byYear().get(1).subjectiveRatio()).isEqualTo(0.45);
        assertThat(trend.highlights()).containsExactly(
                "서술형 비중이 2024년 30%에서 2025년 45%로 늘었습니다.",
                "요약문 빈칸: 기출 3회 모두 출제",
                "어구 배열: 최근 2회 연속 출제",
                "어법 오류 수정: 직전 회차(2025년 1학기 중간)에는 있었지만 최근 회차(2025년 2학기 기말)에는 없음",
                "조건 영작: 최근 회차(2025년 2학기 기말)에 처음 출제");
    }

    @Test
    void 회차가_하나면_변화는_없고_없으면_기본_유형_구성() {
        Trend one = SchoolTrendCalculator.calculate(List.of(round(2025, 1, ExamType.MIDTERM, 20, 10,
                Map.of(QuestionType.OBJ_DETAIL, 20, QuestionType.GUIDED_WRITING, 10))));
        assertThat(one.highlights()).isEmpty();
        assertThat(one.typeMixPerPassage()).containsExactly(Map.entry(QuestionType.GUIDED_WRITING, 3));

        Trend none = SchoolTrendCalculator.calculate(List.of());
        assertThat(none.examCount()).isZero();
        assertThat(none.typeMixPerPassage()).containsEntry(QuestionType.SUMMARY_BLANK, 2).containsEntry(QuestionType.SENTENCE_ORDER, 1);
    }

    private static SchoolExam round(int year, int semester, ExamType type, int objective, int subjective,
                                    Map<QuestionType, Integer> typeCounts) {
        SchoolExam round = new SchoolExam(1L, 1, year, semester, type);
        int total = objective + subjective;
        ReflectionTestUtils.setField(round, "stats", new ExamRoundStats(total, objective, subjective,
                Math.round(subjective * 1000.0 / total) / 1000.0, null, typeCounts, Map.of(), 5));
        return round;
    }
}
