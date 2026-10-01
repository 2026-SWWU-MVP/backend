package com.smwu.backend.pastexam.extraction;

import com.smwu.backend.pastexam.extraction.ExtractedExam.Passage;
import com.smwu.backend.pastexam.extraction.ExtractedExam.Question;
import com.smwu.backend.pastexam.extraction.ExtractedExam.QuestionSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 추출 결과를 코드로 점검한다. LLM이 놓치기 쉬운 구조 오류(번호 중복, 없는 지문 참조, 대괄호 규칙 위반 등)를 찾아
 * 사람이 검수할 위치를 알려준다. 결과가 비어 있으면 구조상 문제는 없다는 뜻이다 (내용 정확도는 사람이 확인).
 */
public final class ExtractionChecker {

    private static final Pattern NUMBERED_BRACKET = Pattern.compile("[①②③④⑤⑥⑦⑧⑨⑩]\\[[^\\[\\]]+]");
    private static final String UNREADABLE = "[판독불가]";

    private ExtractionChecker() {
    }

    public static List<String> check(ExtractedExam exam) {
        List<String> issues = new ArrayList<>();
        Map<String, Passage> passages = exam.passages().stream()
                .collect(Collectors.toMap(Passage::id, Function.identity(), (a, b) -> a));

        if (passages.size() != exam.passages().size()) {
            issues.add("지문 id가 중복됨");
        }
        if (exam.questions().isEmpty()) {
            issues.add("추출된 문항이 없음");
        }

        Set<String> seenNumbers = new HashSet<>();
        Set<String> usedPassages = new HashSet<>();
        for (Question q : exam.questions()) {
            String at = label(q);
            if (!seenNumbers.add(q.section() + "-" + q.no())) {
                issues.add(at + ": 문항 번호 중복");
            }

            Passage passage = null;
            if (q.passageId() != null) {
                passage = passages.get(q.passageId());
                if (passage == null) {
                    issues.add(at + ": 없는 지문 " + q.passageId() + " 참조");
                } else {
                    usedPassages.add(q.passageId());
                }
            }
            String text = (passage == null ? "" : passage.text()) + "\n" + (q.body() == null ? "" : q.body());

            checkBrackets(at, text, issues);
            if (text.contains(UNREADABLE) || q.stem().contains(UNREADABLE)) {
                issues.add(at + ": 판독불가 부분 있음");
            }

            switch (q.type()) {
                case OBJ_GRAMMAR -> {
                    // 밑줄 ①~⑤형이면 ①[ ]~⑤[ ]가 5개, 네모 (A)(B)(C)형이면 (A)[ ] 표기가 있어야 한다
                    int marks = countNumberedBrackets(text);
                    if (marks != 5 && !text.contains("(A)[")) {
                        issues.add(at + ": 어법 객관식인데 ①[ ]~⑤[ ] 표기가 " + marks + "개 (밑줄 → 대괄호 변환 확인 필요)");
                    }
                }
                case GRAMMAR_FIX -> {
                    if (countNumberedBrackets(text) == 0 && !text.contains("[")) {
                        issues.add(at + ": 어법 서술형인데 대괄호 표기가 없음 (밑줄이 사라졌을 수 있음)");
                    }
                }
                case SENTENCE_ORDER -> {
                    if (q.choices().size() < 2) {
                        issues.add(at + ": 어구 배열인데 [보기] 어구가 " + q.choices().size() + "개");
                    }
                }
                default -> {
                }
            }

            if (q.section() == QuestionSection.OBJECTIVE && q.choices().size() != 5) {
                issues.add(at + ": 객관식 선택지가 " + q.choices().size() + "개");
            }
            if (q.section() == QuestionSection.SUBJECTIVE && q.type().name().startsWith("OBJ_")) {
                issues.add(at + ": 서술형인데 객관식 유형 " + q.type());
            }
            if (q.section() == QuestionSection.OBJECTIVE && !q.type().name().startsWith("OBJ_")) {
                issues.add(at + ": 객관식인데 서술형 유형 " + q.type());
            }
        }

        for (Passage p : exam.passages()) {
            if (!usedPassages.contains(p.id())) {
                issues.add("지문 " + p.id() + ": 참조하는 문항이 없음");
            }
            checkBrackets("지문 " + p.id(), p.text(), issues);
        }
        return issues.stream().distinct().toList();
    }

    static int countNumberedBrackets(String text) {
        Matcher matcher = NUMBERED_BRACKET.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static void checkBrackets(String at, String text, List<String> issues) {
        int depth = 0;
        for (char c : text.toCharArray()) {
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
            if (depth < 0 || depth > 1) {
                issues.add(at + ": 대괄호 짝이 맞지 않음");
                return;
            }
        }
        if (depth != 0) {
            issues.add(at + ": 대괄호 짝이 맞지 않음");
        }
    }

    private static String label(Question q) {
        return (q.section() == QuestionSection.SUBJECTIVE ? "서술형 " : "") + q.no() + "번";
    }
}
