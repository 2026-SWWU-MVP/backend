package com.smwu.backend.support;

import com.smwu.backend.academy.domain.Academy;
import com.smwu.backend.academy.domain.AcademyPlan;
import com.smwu.backend.academy.repository.AcademyRepository;
import com.smwu.backend.auth.web.CurrentUserInterceptor;
import com.smwu.backend.user.domain.User;
import com.smwu.backend.user.domain.UserRole;
import com.smwu.backend.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 테스트 MockMvc 요청에 X-User-Id가 없으면 기본 테스트 사용자(테스트 학원의 원장)를 넣는다.
 * 회원 기능(#6) 전에 쓰던 API 테스트가 헤더 없이도 그대로 돌게 하기 위해서다.
 * 헤더 없는 요청 자체를 시험하려면 {@link #ANONYMOUS} 헤더를 붙인다.
 * DB가 다시 만들어져도(컨텍스트가 여러 개일 때) 요청마다 찾고 없으면 만든다.
 */
@Component
public class TestUserMockMvcCustomizer implements MockMvcBuilderCustomizer {

    public static final String ANONYMOUS = "X-Test-Anonymous";
    public static final String LOGIN_ID = "testowner";

    private final UserRepository userRepository;
    private final AcademyRepository academyRepository;

    public TestUserMockMvcCustomizer(UserRepository userRepository, AcademyRepository academyRepository) {
        this.userRepository = userRepository;
        this.academyRepository = academyRepository;
    }

    @Override
    public void customize(ConfigurableMockMvcBuilder<?> builder) {
        builder.addFilters(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (request.getHeader(CurrentUserInterceptor.HEADER) != null || request.getHeader(ANONYMOUS) != null) {
                    chain.doFilter(request, response);
                    return;
                }
                String userId = String.valueOf(testUser().getId());
                chain.doFilter(new HttpServletRequestWrapper(request) {
                    @Override
                    public String getHeader(String name) {
                        return CurrentUserInterceptor.HEADER.equalsIgnoreCase(name) ? userId : super.getHeader(name);
                    }

                    @Override
                    public Enumeration<String> getHeaders(String name) {
                        return CurrentUserInterceptor.HEADER.equalsIgnoreCase(name)
                                ? Collections.enumeration(List.of(userId)) : super.getHeaders(name);
                    }
                }, response);
            }
        });
    }

    /** 기본 테스트 사용자 (없으면 학원과 함께 만든다) */
    public synchronized User testUser() {
        return userRepository.findByLoginId(LOGIN_ID).orElseGet(() -> {
            Academy academy = academyRepository.save(new Academy("테스트학원", AcademyPlan.BASIC));
            User user = new User(LOGIN_ID, "{test}", "테스트원장");
            user.joinAcademy(academy.getId(), UserRole.OWNER);
            return userRepository.save(user);
        });
    }
}
