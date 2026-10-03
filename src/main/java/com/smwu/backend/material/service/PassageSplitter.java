package com.smwu.backend.material.service;

import com.smwu.backend.ai.LlmClient;
import com.smwu.backend.ai.LlmFile;
import com.smwu.backend.ai.LlmRequest;
import com.smwu.backend.ai.LlmResult;
import com.smwu.backend.ai.prompt.PromptLoader;
import com.smwu.backend.document.PdfPageImages;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 시험범위 자료를 지문 단위로 나눈다.
 * <ul>
 *   <li>PDF: 기출 추출(#5)과 같은 방식으로 페이지 이미지(+텍스트 PDF면 원본)를 멀티모달 LLM에 보내 영어 지문만 받는다</li>
 *   <li>붙여넣은 텍스트: "---" 줄로 구분된 블록을 코드가 나눈다 (LLM 없음)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PassageSplitter {

    public static final String TASK = "split-passages";
    static final int MAX_PASSAGE_CHARS = 10_000;
    private static final Pattern SEPARATOR = Pattern.compile("(?m)^\\s*-{3,}\\s*$");
    private static final Pattern TITLE_LINE = Pattern.compile("^#\\s*(.+)$");

    private final LlmClient llmClient;
    private final PromptLoader promptLoader;

    /** LLM 응답 형식 (prompts/split-passages.schema.json) */
    public record SplitResult(List<SplitPassage> passages, List<String> warnings) {
    }

    public record SplitPassage(String title, String sourceLabel, String text) {
    }

    public LlmResult<SplitResult> splitPdf(String filename, byte[] pdf) {
        boolean hasText = PdfPageImages.hasTextLayer(pdf);
        List<byte[]> pages = PdfPageImages.renderJpeg(pdf, PdfPageImages.DEFAULT_DPI);
        List<LlmFile> files = new ArrayList<>();
        if (hasText) {
            files.add(LlmFile.pdf(filename, pdf));
        }
        for (int i = 0; i < pages.size(); i++) {
            files.add(LlmFile.jpeg("page-" + (i + 1) + ".jpg", pages.get(i)));
        }
        String attachments = (hasText ? "PDF 원본(텍스트 포함) 1개 + " : "스캔본이라 텍스트 없음, ")
                + "페이지 이미지 " + pages.size() + "장 (1페이지부터 순서대로)";

        LlmRequest request = LlmRequest.of(
                        TASK,
                        promptLoader.text(TASK + "-system"),
                        promptLoader.render(TASK + "-user", Map.of(
                                "filename", filename, "pageCount", pages.size(), "attachments", attachments)),
                        promptLoader.schema(TASK))
                .withFiles(files);
        LlmResult<SplitResult> result = llmClient.generate(request, SplitResult.class);
        return new LlmResult<>(clean(result.value()), result.rawText(), result.model(), result.inputTokens(), result.outputTokens());
    }

    /**
     * 붙여넣은 텍스트를 "---" 줄로 나눈다. 블록 첫 줄이 "# 제목"이면 제목으로 쓴다. 구분선이 없으면 지문 1개.
     */
    public static List<SplitPassage> splitText(String text) {
        List<SplitPassage> passages = new ArrayList<>();
        for (String block : SEPARATOR.split(text.replace("\r\n", "\n"))) {
            String body = block.strip();
            String title = null;
            int newline = body.indexOf('\n');
            String firstLine = newline < 0 ? body : body.substring(0, newline);
            var titleMatch = TITLE_LINE.matcher(firstLine.strip());
            if (titleMatch.matches()) {
                title = titleMatch.group(1).strip();
                body = newline < 0 ? "" : body.substring(newline + 1).strip();
            }
            if (!body.isEmpty()) {
                passages.add(new SplitPassage(title, null, body));
            }
        }
        return passages;
    }

    /** 빈 지문 제거, 공백 정리, 너무 긴 지문은 경고 후 자르지 않고 그대로 둔다 */
    static SplitResult clean(SplitResult result) {
        List<String> warnings = new ArrayList<>(result.warnings() == null ? List.of() : result.warnings());
        List<SplitPassage> passages = new ArrayList<>();
        for (SplitPassage p : result.passages() == null ? List.<SplitPassage>of() : result.passages()) {
            String text = p.text() == null ? "" : p.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > MAX_PASSAGE_CHARS) {
                warnings.add((passages.size() + 1) + "번째 지문이 " + MAX_PASSAGE_CHARS + "자를 넘습니다. 여러 지문이 합쳐졌는지 확인해 주세요.");
            }
            passages.add(new SplitPassage(blankToNull(p.title()), blankToNull(p.sourceLabel()), text));
        }
        if (passages.isEmpty()) {
            warnings.add("영어 지문을 찾지 못했습니다.");
        }
        return new SplitResult(passages, warnings);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
