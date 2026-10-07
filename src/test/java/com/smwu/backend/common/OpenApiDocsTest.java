package com.smwu.backend.common;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Swagger 문서 (#20): 프론트가 Swagger만 보고 연동할 수 있는지 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 모든_API에_요약과_태그와_공통_오류가_있고_X_User_Id를_전역으로_넣는다() throws Exception {
        String docs = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        Map<String, Object> scheme = JsonPath.read(docs, "$.components.securitySchemes['X-User-Id']");
        assertThat(scheme).containsEntry("in", "header").containsEntry("name", "X-User-Id");
        assertThat(JsonPath.<List<Object>>read(docs, "$.security[*]['X-User-Id']")).isNotEmpty();
        assertThat(JsonPath.<List<String>>read(docs, "$.tags[*].name")).contains("01. 계정", "04. 기출", "10. 시험지");

        Map<String, Map<String, Map<String, Object>>> paths = JsonPath.read(docs, "$.paths");
        assertThat(paths).containsKeys("/api/worksheets/{worksheetId}/pdf", "/api/academy/logo", "/api/workspaces/{workspaceId}/past-exams");
        paths.forEach((path, operations) -> operations.forEach((method, operation) -> {
            assertThat(operation.get("summary")).as(method + " " + path).isNotNull();
            assertThat(operation.get("tags")).as(method + " " + path).isNotNull();
            assertThat((Map<String, Object>) operation.get("responses")).as(method + " " + path).containsKeys("400", "404");
        }));
        // 로그인 API는 헤더가 필요 없다
        assertThat(JsonPath.<List<Object>>read(docs, "$.paths['/api/auth/login'].post.security")).isEmpty();
        assertThat(JsonPath.<String>read(docs, "$.paths['/api/past-exams/{examId}/analyze'].post.description")).contains("추출");
    }
}
