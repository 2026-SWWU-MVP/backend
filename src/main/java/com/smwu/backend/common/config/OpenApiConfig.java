package com.smwu.backend.common.config;

import com.smwu.backend.auth.web.CurrentUserInterceptor;
import com.smwu.backend.common.response.ErrorResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Swagger UI (http://localhost:8080/swagger-ui/index.html) 설정 (#20).
 * 오른쪽 위 Authorize에 로그인 응답의 userId를 넣으면 모든 요청에 X-User-Id 헤더가 붙는다.
 */
@Configuration
public class OpenApiConfig {

    static final String USER_ID_SCHEME = "X-User-Id";

    private static final String DESCRIPTION = """
            내신 영어 맞춤형 AI 시험 제작 서비스 백엔드 API.

            **인증 (MVP)**
            1. `POST /api/auth/signup` → `POST /api/auth/login` → 응답의 `userId`
            2. 이후 모든 요청 헤더에 `X-User-Id: {userId}` (`/api/auth/**` 제외). Swagger에서는 Authorize에 넣는다
            3. 학원 소속 전이면 `POST /api/academies`(학원 만들기) 또는 `POST /api/academies/join`(초대 코드)

            **오류 응답** `{ "code": "PROFILE_NOT_CONFIRMED", "message": "확정된 출제 프로필로만 문제를 생성할 수 있습니다." }`
            - 400 입력값 오류, 401 로그인 필요, 403 원장 전용·학원 소속 전, 404 없음(다른 학원 데이터 포함), 409 상태 충돌

            **비동기 작업** (202 응답 후 2초 간격 폴링)
            - 기출 추출: `GET /api/past-exams/{id}`의 `status`가 `EXTRACTED` 또는 `FAILED`가 될 때까지
            - 지문 분리: `GET /api/materials/{id}`의 `status`가 `SPLIT` 또는 `FAILED`가 될 때까지
            - 문제 생성: `GET /api/generation-jobs/{id}`의 `status`가 `COMPLETED` 또는 `FAILED`가 될 때까지 (`progress` 0~100)

            LLM 비용 없이 프론트 개발: `LLM_PROVIDER=mock`(기본값)으로 실행하면 고정 응답을 돌려준다. 자세한 내용은 docs/FRONTEND_GUIDE.md
            """;

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("내신 영어 AI 시험 제작 API").version("MVP").description(DESCRIPTION))
                .components(new Components().addSecuritySchemes(USER_ID_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(CurrentUserInterceptor.HEADER)
                        .description("로그인 응답의 userId")))
                .addSecurityItem(new SecurityRequirement().addList(USER_ID_SCHEME));
    }

    /** 모든 API에 공통 오류 응답(ErrorResponse)을 문서화한다. /api/auth/** 는 X-User-Id가 필요 없다 */
    @Bean
    public OpenApiCustomizer errorResponses() {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put("400", "입력값 오류 (INVALID_REQUEST, INVALID_FILE, FILE_TOO_LARGE)");
        errors.put("401", "로그인 필요 (X-User-Id 없음·잘못됨)");
        errors.put("403", "원장 전용(OWNER_ONLY) 또는 학원 소속 전(NO_ACADEMY)");
        errors.put("404", "없음 (다른 학원의 데이터도 404)");
        errors.put("409", "상태 충돌 (예: PROFILE_NOT_CONFIRMED, EXTRACTION_IN_PROGRESS, WORKSPACE_DUPLICATED)");
        return openApi -> {
            Schema<?> schema = ModelConverters.getInstance().resolveAsResolvedSchema(
                    new AnnotatedType(ErrorResponse.class)).schema;
            openApi.getComponents().addSchemas("ErrorResponse", schema);
            Content content = new Content().addMediaType("application/json",
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/ErrorResponse")));
            openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                errors.forEach((status, description) -> {
                    if (!operation.getResponses().containsKey(status)) {
                        operation.getResponses().addApiResponse(status, new ApiResponse().description(description).content(content));
                    }
                });
                if (path.startsWith("/api/auth/")) {
                    operation.setSecurity(List.of());
                    operation.getResponses().remove("403");
                }
            }));
        };
    }
}
