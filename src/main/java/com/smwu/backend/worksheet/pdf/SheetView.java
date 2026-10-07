package com.smwu.backend.worksheet.pdf;

import java.util.List;

/**
 * 시험지 템플릿(templates/worksheet.html)에 넘기는 화면 모델. 문제지와 정답지가 같은 모델을 쓰고 answerSheet만 다르다.
 * 문장은 원문자를 그리기 위해 {@link RichText}로 넘긴다.
 *
 * @param headerTitle  머리글 문구 (머리글이 없으면 시험지 제목)
 * @param logo         로고 (없거나 showLogo=false면 null)
 * @param academyName  로고가 없을 때 머리글 오른쪽에 쓰는 학원명 (showLogo=false면 null)
 */
public record SheetView(RichText headerTitle, LogoView logo, String academyName, boolean answerSheet,
                        List<SectionView> sections) {

    /** 머리글 로고: 높이 12mm 고정, 가로는 비율대로 최대 45mm (설계서 8). 크기를 직접 정해야 비율이 유지된다 */
    public record LogoView(String dataUri, double widthMm, double heightMm) {

        static final double MAX_HEIGHT_MM = 12;
        static final double MAX_WIDTH_MM = 45;

        public static LogoView of(String dataUri, int widthPx, int heightPx) {
            double ratio = widthPx / (double) heightPx;
            double height = MAX_HEIGHT_MM;
            double width = height * ratio;
            if (width > MAX_WIDTH_MM) {
                width = MAX_WIDTH_MM;
                height = width / ratio;
            }
            return new LogoView(dataUri, Math.round(width * 10) / 10.0, Math.round(height * 10) / 10.0);
        }

        public String style() {
            return "width: " + widthMm + "mm; height: " + heightMm + "mm;";
        }
    }

    /** @param text 지문 본문 (줄바꿈 유지, 대괄호 표기 그대로) */
    public record SectionView(int order, RichText title, RichText text, List<ItemView> items) {
    }

    /**
     * @param answer      문제지의 답안 칸 모양
     * @param answerSlots 빈칸 수(요약문 빈칸) / 고칠 곳 수(어법) / 줄 수
     * @param answerText  정답지에 찍는 정답
     */
    public record ItemView(int no, RichText stem, List<RichText> conditions, RichText body, RichText choices, AnswerKind answer,
                           int answerSlots, RichText answerText, RichText explanation) {
    }

    public enum AnswerKind {
        /** (1) ________ (2) ________ */
        BLANKS,
        /** → ______________ */
        ARROW_LINES,
        /** (  ) ________ → ________ */
        CORRECTIONS
    }
}
