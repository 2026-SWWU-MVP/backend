package com.smwu.backend.worksheet.pdf;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF용 문장 조각. 나눔 폰트에 원문자(①~⑳, ⓐ~ⓩ, Ⓐ~Ⓩ) 글리프가 없어서(#3에서 확인) 그대로 쓰면 '#'으로 찍힌다.
 * 원문자는 숫자·문자만 남기고 circled=true로 표시해 템플릿이 CSS 동그라미로 그리게 한다.
 * 대괄호 표기(①[표현])는 설계서 4에 따라 그대로 둔다.
 */
public record RichText(List<Segment> segments) {

    /** @param circled true면 text를 동그라미 안에 그린다 */
    public record Segment(String text, boolean circled) {
    }

    public static final RichText EMPTY = new RichText(List.of());

    public static RichText of(String text) {
        if (text == null || text.isEmpty()) {
            return EMPTY;
        }
        List<Segment> segments = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String circled = circled(text.charAt(i));
            if (circled == null) {
                plain.append(text.charAt(i));
                continue;
            }
            if (!plain.isEmpty()) {
                segments.add(new Segment(plain.toString(), false));
                plain.setLength(0);
            }
            segments.add(new Segment(circled, true));
        }
        if (!plain.isEmpty()) {
            segments.add(new Segment(plain.toString(), false));
        }
        return new RichText(List.copyOf(segments));
    }

    public static List<RichText> all(List<String> texts) {
        return texts == null ? List.of() : texts.stream().map(RichText::of).toList();
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    /** 원문자면 안에 들어갈 글자, 아니면 null */
    static String circled(char c) {
        if (c >= '①' && c <= '⑳') {
            return String.valueOf(c - '①' + 1);
        }
        if (c >= 'Ⓐ' && c <= 'Ⓩ') {
            return String.valueOf((char) ('A' + (c - 'Ⓐ')));
        }
        if (c >= 'ⓐ' && c <= 'ⓩ') {
            return String.valueOf((char) ('a' + (c - 'ⓐ')));
        }
        return null;
    }
}
