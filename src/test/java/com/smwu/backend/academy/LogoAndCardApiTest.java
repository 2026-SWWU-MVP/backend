package com.smwu.backend.academy;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.document.TestPdfs;
import com.smwu.backend.support.TestWorkspaces;
import com.smwu.backend.worksheet.domain.Worksheet;
import com.smwu.backend.worksheet.repository.WorksheetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 학원 로고 업로드(#14)와 워크스페이스 카드 요약 */
@SpringBootTest
@AutoConfigureMockMvc
class LogoAndCardApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestWorkspaces workspaces;

    @Autowired
    private WorksheetRepository worksheetRepository;

    @Test
    void 원장이_로고를_올리면_가로_600px로_줄여_PNG로_저장하고_지울_수_있다() throws Exception {
        upload(image(1200, 300, "png"), "logo.png", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasLogo").value(true))
                .andExpect(jsonPath("$.logoWidth").value(600))
                .andExpect(jsonPath("$.logoHeight").value(150));
        byte[] png = mockMvc.perform(get("/api/academy/logo"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage saved = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(saved.getWidth()).isEqualTo(600);
        assertThat(saved.getColorModel().hasAlpha()).isTrue();

        // 작은 JPG는 그대로 크기 유지
        upload(image(200, 80, "jpg"), "logo.jpg", null)
                .andExpect(jsonPath("$.logoWidth").value(200))
                .andExpect(jsonPath("$.logoHeight").value(80));
        mockMvc.perform(get("/api/academy")).andExpect(jsonPath("$.hasLogo").value(true));

        // 같은 학원 강사는 미리보기만, 업로드·삭제는 원장만
        long teacher = workspaces.newTeacher();
        mockMvc.perform(get("/api/academy/logo").header("X-User-Id", teacher)).andExpect(status().isOk());
        upload(image(100, 100, "png"), "logo.png", teacher)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_ONLY"));
        mockMvc.perform(delete("/api/academy/logo").header("X-User-Id", teacher)).andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/academy/logo")).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/academy/logo")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/academy"))
                .andExpect(jsonPath("$.hasLogo").value(false))
                .andExpect(jsonPath("$.logoWidth").doesNotExist());
    }

    @Test
    void 이미지가_아니거나_형식이_다르거나_크면_400() throws Exception {
        upload("not an image".getBytes(), "logo.png", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));
        upload(image(50, 50, "gif"), "logo.gif", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("로고는 PNG 또는 JPG 이미지만 올릴 수 있습니다."));
        byte[] png = image(10, 10, "png");
        byte[] broken = java.util.Arrays.copyOf(png, png.length / 2);
        upload(broken, "broken.png", null).andExpect(status().isBadRequest());
        upload(new byte[2 * 1024 * 1024 + 1], "big.png", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void 워크스페이스_카드에_기출_수_프로필_상태_최근_시험지가_나온다() throws Exception {
        long workspaceId = workspaces.create();
        mockMvc.perform(get("/api/workspaces/{id}", workspaceId))
                .andExpect(jsonPath("$.summary.pastExamCount").value(0))
                .andExpect(jsonPath("$.summary.profileStatus").doesNotExist())
                .andExpect(jsonPath("$.summary.latestWorksheet").doesNotExist());

        long examId = id(mockMvc.perform(multipart("/api/workspaces/{id}/past-exams", workspaceId)
                        .file(new MockMultipartFile("file", "mid.pdf", "application/pdf", TestPdfs.textPdf(1)))
                        .param("examYear", "2025").param("semester", "1").param("examType", "MIDTERM"))
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(post("/api/past-exams/{id}/analyze", examId));
        String state = null;
        for (int i = 0; i < 100 && !"EXTRACTED".equals(state); i++) {
            Thread.sleep(50);
            state = JsonPath.read(mockMvc.perform(get("/api/past-exams/{id}", examId)).andReturn().getResponse().getContentAsString(),
                    "$.status");
        }
        long profileId = id(mockMvc.perform(post("/api/workspaces/{id}/profiles", workspaceId)).andReturn().getResponse().getContentAsString());
        mockMvc.perform(get("/api/workspaces/{id}", workspaceId))
                .andExpect(jsonPath("$.summary.pastExamCount").value(1))
                .andExpect(jsonPath("$.summary.profileStatus").value("DRAFT"));

        mockMvc.perform(post("/api/profiles/{id}/confirm", profileId));
        Worksheet worksheet = worksheetRepository.save(new Worksheet(workspaceId, "2과 대비", null, true, null));
        mockMvc.perform(get("/api/workspaces"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces/{id}", workspaceId))
                .andExpect(jsonPath("$.summary.profileStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.summary.confirmedProfileId").value(profileId))
                .andExpect(jsonPath("$.summary.confirmedProfileVersion").value(1))
                .andExpect(jsonPath("$.summary.latestWorksheet.id").value(worksheet.getId()))
                .andExpect(jsonPath("$.summary.latestWorksheet.title").value("2과 대비"));
    }

    private ResultActions upload(byte[] bytes, String filename, Long userId) throws Exception {
        var request = multipart(HttpMethod.POST, "/api/academy/logo").file(new MockMultipartFile("file", filename, null, bytes));
        if (userId != null) {
            request.header("X-User-Id", userId);
        }
        return mockMvc.perform(request);
    }

    private static byte[] image(int width, int height, String format) throws Exception {
        boolean alpha = format.equals("png");
        BufferedImage image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(20, 60, 160, alpha ? 200 : 255));
        g.fillRect(0, 0, width / 2, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
