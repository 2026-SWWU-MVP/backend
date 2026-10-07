package com.smwu.backend.worksheet;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.support.TestProblems;
import com.smwu.backend.support.TestProblems.Generated;
import com.smwu.backend.support.TestWorkspaces;
import com.smwu.backend.worksheet.pdf.PdfRenderer;
import com.smwu.backend.worksheet.pdf.RichText;
import com.smwu.backend.worksheet.pdf.SheetView;
import com.smwu.backend.worksheet.pdf.SheetView.AnswerKind;
import com.smwu.backend.worksheet.pdf.SheetView.ItemView;
import com.smwu.backend.worksheet.pdf.SheetView.SectionView;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 시험지 PDF (#3 한글·기호·머리글 확인, #18 문제지·정답지) */
@SpringBootTest
@AutoConfigureMockMvc
class WorksheetPdfApiTest {

    /** 샘플 PDF를 눈으로 확인할 때: build/pdf-samples/ */
    private static final Path SAMPLE_DIR = Path.of("build", "pdf-samples");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestWorkspaces workspaces;

    @Autowired
    private TemplateEngine templateEngine;

    @Autowired
    private PdfRenderer pdfRenderer;

    @Test
    void 한글_기호_대괄호_표기가_깨지지_않고_머리글이_매_페이지_반복된다() throws Exception {
        List<ItemView> items = new ArrayList<>();
        items.add(new ItemView(1, rich("윗글의 밑줄 친 ①~⑤ 중 어법상 틀린 것을 골라 바르게 고쳐 쓰시오."),
                RichText.all(List.of("틀린 곳의 번호와 고친 표현을 함께 쓸 것.", "ⓐ, (A)처럼 표시된 부분은 그대로 둘 것.")),
                rich("Many apartments ①[were] abandoned and then ②[taking] over by people. ⓐ[It] was (A)[were / was] hard."),
                RichText.EMPTY, AnswerKind.CORRECTIONS, 2, rich("② taking → taken"), rich("수동태이므로 taken이 맞다.")));
        items.add(new ItemView(2, rich("윗글에 나온 문장이 되도록 [보기]의 어구를 배열하시오."), RichText.all(List.of("[보기]의 모든 어구를 한 번씩 사용할 것.")),
                RichText.EMPTY, rich("However / having a limited budget / the government"), AnswerKind.ARROW_LINES, 1,
                rich("However, having a limited budget, the government ..."), RichText.EMPTY));
        items.add(new ItemView(3, rich("윗글의 내용을 요약할 때, 빈칸 (1), (2)에 들어갈 말을 쓰시오."), RichText.all(List.of("각 빈칸에 한 단어씩 쓸 것.")),
                rich("As a neighborhood ages, it may become (1) l__________, and citizens (2) __________ it."), RichText.EMPTY,
                AnswerKind.BLANKS, 2, rich("(1) lifeless   (2) revitalize"), RichText.EMPTY));
        // 문항이 많아 여러 페이지가 되게 한다
        List<SectionView> sections = IntStream.rangeClosed(1, 6)
                .mapToObj(i -> new SectionView(i, rich("Bringing New Life to Old Cities " + i),
                        rich("As cities age, neighborhoods can become old and lifeless.\nWhen this happens, citizens move away."), items))
                .toList();

        for (boolean answerSheet : List.of(false, true)) {
            SheetView view = new SheetView(rich("건대부고1 2학기 중간고사 서답형 대비 - 교과서 2과"), null, "파인로드영어", answerSheet, sections);
            Context context = new Context();
            context.setVariable("sheet", view);
            byte[] pdf = pdfRenderer.render(templateEngine.process("worksheet", context));
            save(answerSheet ? "sample-answer.pdf" : "sample.pdf", pdf);

            try (PDDocument document = Loader.loadPDF(pdf)) {
                assertThat(document.getNumberOfPages()).isGreaterThan(1);
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    String text = text(document, page);
                    assertThat(text).as("머리글 page " + page).contains("건대부고1 2학기 중간고사 서답형 대비", "파인로드영어");
                }
                String all = new PDFTextStripper().getText(document);
                // 원문자는 동그라미 안 글자로 그려져 '#'(글리프 없음)이 나오지 않는다
                assertThat(all).contains("[서답형 1]", "[조건]", "1. 틀린 곳의 번호", "[보기]", "(A)[were / was]", "→", "However / having")
                        .containsPattern("Many apartments 1\\s*\\[were\\]")
                        .doesNotContain("#");
                if (answerSheet) {
                    assertThat(all).contains("정답", "해설").containsPattern("2\\s*taking → taken");
                } else {
                    assertThat(all).doesNotContain("정답", "taking → taken");
                }
                assertThat(fontNames(document)).anyMatch(n -> n.contains("NanumMyeongjo")).anyMatch(n -> n.contains("NanumGothic"));
            }
        }
    }

    @Test
    void 채택한_문항으로_만든_시험지를_문제지와_정답지_PDF로_내려받는다() throws Exception {
        Generated g = TestProblems.generate(mockMvc, workspaces);
        for (long id : g.problemIds()) {
            TestProblems.accept(mockMvc, id);
        }
        long worksheetId = TestProblems.id(mockMvc.perform(post("/api/workspaces/{id}/worksheets", g.workspaceId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(Map.of(
                                "title", "2과 서술형 대비", "headerText", "건대부고1 2학기 중간고사 서답형 대비 - 교과서 2과",
                                "problemIds", g.problemIds()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        // 로고 등록 → 머리글에 이미지
        mockMvc.perform(multipart("/api/academy/logo").file(new MockMultipartFile("file", "logo.png", "image/png", png(300, 80))))
                .andExpect(status().isOk());
        MockHttpServletResponse sheet = mockMvc.perform(get("/api/worksheets/{id}/pdf", worksheetId))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(sheet.getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(sheet.getHeader("Content-Disposition")).startsWith("attachment").contains("filename*=UTF-8''");
        save("worksheet.pdf", sheet.getContentAsByteArray());
        try (PDDocument document = Loader.loadPDF(sheet.getContentAsByteArray())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("건대부고1 2학기 중간고사 서답형 대비", "Bringing New Life to Old Cities", "Crime and Budget",
                    "[서답형 1]", "[서답형 2]", "[조건]", "[보기]").doesNotContain("정답");
            assertThat(hasImage(document.getPage(0))).isTrue();
        }

        MockHttpServletResponse answer = mockMvc.perform(get("/api/worksheets/{id}/answer-pdf", worksheetId))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(answer.getHeader("Content-Disposition")).contains(java.net.URLEncoder.encode("_정답", "UTF-8").replace("+", "%20"));
        save("worksheet-answer.pdf", answer.getContentAsByteArray());
        try (PDDocument document = Loader.loadPDF(answer.getContentAsByteArray())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("(정답)", "정답", "lifeless", "revitalize");
        }

        // 로고 끄기 → 이미지도 학원명도 없음
        mockMvc.perform(patch("/api/worksheets/{id}", worksheetId).contentType(MediaType.APPLICATION_JSON).content("{\"showLogo\": false}"));
        try (PDDocument document = Loader.loadPDF(mockMvc.perform(get("/api/worksheets/{id}/pdf", worksheetId))
                .andReturn().getResponse().getContentAsByteArray())) {
            assertThat(hasImage(document.getPage(0))).isFalse();
            assertThat(new PDFTextStripper().getText(document)).doesNotContain("테스트학원");
        }

        // 로고가 없으면 학원명
        mockMvc.perform(patch("/api/worksheets/{id}", worksheetId).contentType(MediaType.APPLICATION_JSON).content("{\"showLogo\": true}"));
        mockMvc.perform(delete("/api/academy/logo")).andExpect(status().isNoContent());
        try (PDDocument document = Loader.loadPDF(mockMvc.perform(get("/api/worksheets/{id}/pdf", worksheetId))
                .andReturn().getResponse().getContentAsByteArray())) {
            assertThat(hasImage(document.getPage(0))).isFalse();
            assertThat(new PDFTextStripper().getText(document)).contains("테스트학원");
        }

        // 다른 학원은 내려받을 수 없다
        TestWorkspaces.Member other = workspaces.newAcademy();
        mockMvc.perform(get("/api/worksheets/{id}/pdf", worksheetId).header("X-User-Id", other.userId())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/worksheets/{id}/answer-pdf", worksheetId).header("X-User-Id", other.userId()))
                .andExpect(status().isNotFound());
        assertThat(JsonPath.<Integer>read(mockMvc.perform(get("/api/worksheets/{id}", worksheetId)).andReturn().getResponse()
                .getContentAsString(), "$.problemCount")).isEqualTo(4);
    }

    private static RichText rich(String text) {
        return RichText.of(text);
    }

    private static String text(PDDocument document, int page) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document);
    }

    private static Set<String> fontNames(PDDocument document) throws Exception {
        Set<String> names = new HashSet<>();
        for (PDPage page : document.getPages()) {
            for (var name : page.getResources().getFontNames()) {
                PDFont font = page.getResources().getFont(name);
                names.add(font.getName());
            }
        }
        return names;
    }

    private static boolean hasImage(PDPage page) throws Exception {
        for (var name : page.getResources().getXObjectNames()) {
            if (page.getResources().isImageXObject(name)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setColor(java.awt.Color.BLUE);
        g.fillRect(0, 0, width / 2, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void save(String name, byte[] pdf) throws Exception {
        Files.createDirectories(SAMPLE_DIR);
        Files.write(SAMPLE_DIR.resolve(name), pdf);
    }
}
