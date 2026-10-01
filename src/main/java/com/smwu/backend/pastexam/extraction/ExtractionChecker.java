package com.smwu.backend.pastexam.extraction;

import com.smwu.backend.pastexam.extraction.ExtractedExam.Passage;
import com.smwu.backend.pastexam.extraction.ExtractedExam.Question;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 추출 결과를 코드로 점검한다. LLM이 놓치기 쉬운 구조 오류(번호 중복, 없는 지문 참조, 대괄호 규칙 위반 등)를 찾아
 * 사람이 검수할 위치를 알려준다. 결과가 비어 있으면 구조상 문제는 없다는 뜻이다 (내용 정확도는 사람이 확인).
 * 이슈마다 어느 문항·지문의 문제인지 담겨 있어 검수 화면에서 해당 위치 옆에 표시할 수 있다.
 */
public final class ExtractionChecker {

    /** 밑줄 대괄호 표기: ①[표현], ⓐ[표현], (A)[표현] */
    private static final Pattern MARKED_BRACKET = Pattern.compile("(?:[①-⑩ⓐ-ⓩ]|\\([A-E]\\))\\[[^\\[\\]]+]");
    /** 선택지 번호가 지문 안에 있는 문항인지 판단 (무관한 문장, 밑줄 어휘, 문장 삽입 등) */
    private static final Pattern CIRCLED_NUMBER = Pattern.compile("[①-⑤]");
    private static final String UNREADABLE = "[판독불가]";

    /**
     * @param section   문항 이슈면 문항 구분, 아니면 null
     * @param no        문항 이슈면 문항 번호, 아니면 null
     * @param passageId 지문 이슈면 지문 id(P1 ...), 아니면 null
     * @param message   이슈 내용
     */
    public record Issue(QuestionSection section, Integer no, String passageId, String message) {

        public boolean isFor(QuestionSection section, int no) {
            return this.section == section && this.no != null && this.no == no;
        }

        /** 사람이 읽는 형식. 예) "서술형 2번: 어구 배열인데 [보기] 어구가 0개" */
        public String text() {
            if (section != null) {
                return label(section, no) + ": " + message;
            }
            if (passageId != null) {
                return "지문 " + passageId + ": " + message;
            }
            return message;
        }
    }

    private ExtractionChecker() {
    }

    public static List<Issue> check(ExtractedExam exam) {
        List<Issue> issues = new ArrayList<>();
        Map<String, Passage> passages = exam.passages().stream()
                .collect(Collectors.toMap(Passage::id, Function.identity(), (a, b) -> a));

        if (passages.size() != exam.passages().size()) {
            issues.add(new Issue(null, null, null, "지문 id가 중복됨"));
        }
        if (exam.questions().isEmpty()) {
            issues.add(new Issue(null, null, null, "추출된 문항이 없음"));
        }

        Set<String> seenNumbers = new HashSet<>();
        Set<String> usedPassages = new HashSet<>();
        for (Question q : exam.questions()) {
            Consumer<String> report = message -> issues.add(new Issue(q.section(), q.no(), null, message));
            if (!seenNumbers.add(q.section() + "-" + q.no())) {
                report.accept("문항 번호 중복");
            }

            StringBuilder textBuilder = new StringBuilder();
            for (String passageId : q.passageIds()) {
                Passage passage = passages.get(passageId);
                if (passage == null) {
                    report.accept("없는 지문 " + passageId + " 참조");
                } else {
                    usedPassages.add(passageId);
                    textBuilder.append(passage.text()).append('\n');
                }
            }
            if (q.body() != null) {
                textBuilder.append(q.body());
            }
            String text = textBuilder.toString();

            if (q.body() != null && !bracketsBalanced(q.body())) {
                report.accept("대괄호 짝이 맞지 않음");
            }
            if (text.contains(UNREADABLE) || q.stem().contains(UNREADABLE)) {
                report.accept("판독불가 부분 있음");
            }

            int marks = countMarkedBrackets(text);
            // 선택지가 지문·본문 안에 있는 문항: 무관한 문장(①문장), 밑줄형(①[ ], (A)[ ]), 문장 고르기형(본문에 ①~⑤ 문장)
            boolean choicesInText = countCircledNumbers(text) >= 5 || marks >= 5;

            if (q.type() == QuestionType.OBJ_GRAMMAR) {
                if (marks == 0 && q.choices().size() != 5 && !choicesInText) {
                    report.accept("어법 객관식인데 밑줄 대괄호도, 선택지 5개도 없음 (밑줄 → 대괄호 변환 확인 필요)");
                } else if (marks > 0 && marks < 5 && !text.contains("(A)[")) {
                    report.accept("어법 객관식 밑줄 대괄호가 " + marks + "개 (5개 예상, 원본 확인 필요)");
                }
            }
            if (q.type() == QuestionType.SENTENCE_ORDER && q.choices().size() < 2 && !hasInlineWordList(q)) {
                report.accept("어구 배열인데 [보기] 어구가 " + q.choices().size() + "개");
            }

            if (q.section() == QuestionSection.OBJECTIVE && q.type() != QuestionType.OBJ_LISTENING) {
                if (q.choices().size() != 5 && !(q.choices().isEmpty() && choicesInText)) {
                    report.accept("객관식 선택지가 " + q.choices().size() + "개");
                }
                if (q.passageIds().isEmpty() && q.body() == null && q.choices().isEmpty()) {
                    report.accept("지문, 본문, 선택지가 모두 없음");
                }
            }
            if (q.section() == QuestionSection.SUBJECTIVE && q.type().name().startsWith("OBJ_")) {
                report.accept("서술형인데 객관식 유형 " + q.type());
            }
            if (q.section() == QuestionSection.OBJECTIVE && !q.type().name().startsWith("OBJ_")) {
                report.accept("객관식인데 서술형 유형 " + q.type());
            }
        }

        for (Passage p : exam.passages()) {
            if (!usedPassages.contains(p.id())) {
                issues.add(new Issue(null, null, p.id(), "참조하는 문항이 없음"));
            }
            if (!bracketsBalanced(p.text())) {
                issues.add(new Issue(null, null, p.id(), "대괄호 짝이 맞지 않음"));
            }
        }
        return issues.stream().distinct().toList();
    }

    /** 배열할 어구가 [보기] 대신 본문 "( men / seem )"이나 [조건] "lack, evolve, describe ...을 사용할 것"에 있는 경우 */
    private static boolean hasInlineWordList(Question q) {
        if (q.body() != null && q.body().contains("/")) {
            return true;
        }
        return q.conditions().stream().anyMatch(c -> c.split(",").length >= 4);
    }

    static int countMarkedBrackets(String text) {
        return count(MARKED_BRACKET.matcher(text));
    }

    private static int countCircledNumbers(String text) {
        return count(CIRCLED_NUMBER.matcher(text));
    }

    private static int count(Matcher matcher) {
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** 대괄호가 중첩 없이 짝이 맞는지 */
    private static boolean bracketsBalanced(String text) {
        int depth = 0;
        for (char c : text.toCharArray()) {
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
            if (depth < 0 || depth > 1) {
                return false;
            }
        }
        return depth == 0;
    }

    private static String label(QuestionSection section, Integer no) {
        return (section == QuestionSection.SUBJECTIVE ? "서술형 " : "") + no + "번";
    }
}
