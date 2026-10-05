package com.smwu.backend.auth;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.support.TestUserMockMvcCustomizer;
import com.smwu.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Test
    void 가입하고_로그인한_userId로_내_정보를_본다() throws Exception {
        mockMvc.perform(get("/api/auth/check-login-id").param("loginId", "kimteacher").header(TestUserMockMvcCustomizer.ANONYMOUS, "1"))
                .andExpect(jsonPath("$.available").value(true));

        String signup = mockMvc.perform(post("/api/auth/signup").header(TestUserMockMvcCustomizer.ANONYMOUS, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\": \"kimteacher\", \"password\": \"1234\", \"name\": \"김강사\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.academyId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        // 비밀번호는 BCrypt 해시로만 저장
        assertThat(userRepository.findByLoginId("kimteacher").orElseThrow().getPasswordHash()).startsWith("$2").doesNotContain("1234");

        mockMvc.perform(get("/api/auth/check-login-id").param("loginId", "kimteacher").header(TestUserMockMvcCustomizer.ANONYMOUS, "1"))
                .andExpect(jsonPath("$.available").value(false));
        mockMvc.perform(post("/api/auth/signup").header(TestUserMockMvcCustomizer.ANONYMOUS, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\": \"kimteacher\", \"password\": \"5678\", \"name\": \"다른사람\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOGIN_ID_DUPLICATED"));

        String login = mockMvc.perform(post("/api/auth/login").header(TestUserMockMvcCustomizer.ANONYMOUS, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\": \"kimteacher\", \"password\": \"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("김강사"))
                .andReturn().getResponse().getContentAsString();
        long userId = ((Number) JsonPath.read(login, "$.userId")).longValue();
        assertThat(userId).isEqualTo(((Number) JsonPath.read(signup, "$.userId")).longValue());

        mockMvc.perform(get("/api/me").header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("kimteacher"));
        // 학원 소속 전에는 학원 데이터 API를 쓸 수 없다
        mockMvc.perform(get("/api/workspaces").header("X-User-Id", userId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_ACADEMY"));
    }

    @Test
    void 잘못된_가입_로그인_헤더() throws Exception {
        mockMvc.perform(post("/api/auth/signup").header(TestUserMockMvcCustomizer.ANONYMOUS, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\": \"Kim!\", \"password\": \"12\", \"name\": \"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/login").header(TestUserMockMvcCustomizer.ANONYMOUS, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\": \"nobody\", \"password\": \"1234\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));

        mockMvc.perform(get("/api/me").header(TestUserMockMvcCustomizer.ANONYMOUS, "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/workspaces").header("X-User-Id", "99999999"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/workspaces").header("X-User-Id", "abc"))
                .andExpect(status().isUnauthorized());
    }
}
