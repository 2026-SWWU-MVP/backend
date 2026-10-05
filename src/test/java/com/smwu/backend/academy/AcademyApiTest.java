package com.smwu.backend.academy;

import com.jayway.jsonpath.JsonPath;
import com.smwu.backend.academy.domain.InviteCode;
import com.smwu.backend.academy.repository.InviteCodeRepository;
import com.smwu.backend.support.TestUserMockMvcCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AcademyApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InviteCodeRepository inviteCodeRepository;

    @Test
    void 학원을_만들고_초대_코드로_강사가_합류한다() throws Exception {
        long owner = signup("원장");
        as(owner, post("/api/academies").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"파인로드영어\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.academyId").isNumber());
        as(owner, post("/api/academies").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"두번째\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_IN_ACADEMY"));

        String code = issue(owner);
        assertThat(code).matches("[A-HJ-NP-Z2-9]{6}");

        long teacher = signup("강사");
        as(teacher, post("/api/academies/join").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \" %s \"}".formatted(code.toLowerCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TEACHER"));
        // 1회용
        long other = signup("다른사람");
        join(other, code).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVITE_CODE_INVALID"));

        as(teacher, get("/api/academy"))
                .andExpect(jsonPath("$.name").value("파인로드영어"))
                .andExpect(jsonPath("$.teacherCount").value(1))
                .andExpect(jsonPath("$.maxTeachers").value(3))
                .andExpect(jsonPath("$.plan").value("BASIC"));
        as(teacher, get("/api/academy/members"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[1].userId").value(teacher));
        as(owner, get("/api/academy/invites"))
                .andExpect(jsonPath("$[0].status").value("USED"))
                .andExpect(jsonPath("$[0].usedByName").value(nameOf(teacher)));

        // 강사는 원장 전용 기능 불가
        as(teacher, post("/api/academy/invites")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OWNER_ONLY"));
        as(teacher, patch("/api/academy").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"x\"}"))
                .andExpect(status().isForbidden());
        as(owner, patch("/api/academy").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"파인로드영어 본원\"}"))
                .andExpect(jsonPath("$.name").value("파인로드영어 본원"));
    }

    @Test
    void 취소되거나_만료된_코드는_쓸_수_없고_강사_수_제한을_지킨다() throws Exception {
        long owner = signup("원장");
        as(owner, post("/api/academies").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"제한학원\"}"));

        String canceled = issue(owner);
        long canceledId = inviteCodeRepository.findByCode(canceled).orElseThrow().getId();
        as(owner, delete("/api/academy/invites/{id}", canceledId)).andExpect(status().isNoContent());
        join(signup("t"), canceled).andExpect(status().isConflict());

        String expired = issue(owner);
        InviteCode invite = inviteCodeRepository.findByCode(expired).orElseThrow();
        ReflectionTestUtils.setField(invite, "expiresAt", LocalDateTime.now().minusMinutes(1));
        inviteCodeRepository.save(invite);
        join(signup("t"), expired).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVITE_CODE_INVALID"));

        // BASIC 강사 3명: 미리 발급한 코드도 자리가 차면 쓸 수 없다
        String spare = issue(owner);
        join(signup("t"), issue(owner)).andExpect(status().isOk());
        join(signup("t"), issue(owner)).andExpect(status().isOk());
        String used = issue(owner);
        join(signup("t"), used).andExpect(status().isOk());
        join(signup("t"), spare).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_LIMIT_EXCEEDED"));
        as(owner, post("/api/academy/invites")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_LIMIT_EXCEEDED"));

        long usedId = inviteCodeRepository.findByCode(used).orElseThrow().getId();
        as(owner, delete("/api/academy/invites/{id}", usedId)).andExpect(status().isConflict());
        as(owner, delete("/api/academy/invites/{id}", 99999999)).andExpect(status().isNotFound());
    }

    @Test
    void 강사를_내보내면_소속만_비워지고_다시_합류할_수_있다() throws Exception {
        long owner = signup("원장");
        as(owner, post("/api/academies").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"내보내기학원\"}"));
        long teacher = signup("강사");
        join(teacher, issue(owner)).andExpect(status().isOk());

        as(owner, delete("/api/academy/members/{id}", owner)).andExpect(status().isBadRequest());
        as(owner, delete("/api/academy/members/{id}", signup("남"))).andExpect(status().isNotFound());
        as(owner, delete("/api/academy/members/{id}", teacher)).andExpect(status().isNoContent());

        as(teacher, get("/api/me"))
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.academyId").doesNotExist());
        as(teacher, get("/api/academy")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_ACADEMY"));
        join(teacher, issue(owner)).andExpect(status().isOk());
    }

    private String issue(long owner) throws Exception {
        String json = as(owner, post("/api/academy/invites"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.code");
    }

    private ResultActions join(long user, String code) throws Exception {
        return as(user, post("/api/academies/join").contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"%s\"}".formatted(code)));
    }

    private ResultActions as(long userId, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("X-User-Id", userId));
    }

    private final java.util.Map<Long, String> names = new java.util.HashMap<>();

    private String nameOf(long userId) {
        return names.get(userId);
    }

    private long signup(String name) throws Exception {
        String loginId = "acad" + SEQ.incrementAndGet() + System.nanoTime() % 100000;
        String json = mockMvc.perform(post("/api/auth/signup").header(TestUserMockMvcCustomizer.ANONYMOUS, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\": \"%s\", \"password\": \"1234\", \"name\": \"%s\"}".formatted(loginId, name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(json, "$.userId")).longValue();
        names.put(id, name);
        return id;
    }
}
