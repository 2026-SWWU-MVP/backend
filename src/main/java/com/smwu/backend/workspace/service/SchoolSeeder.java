package com.smwu.backend.workspace.service;

import com.smwu.backend.workspace.domain.School;
import com.smwu.backend.workspace.dto.SchoolRequest;
import com.smwu.backend.workspace.repository.SchoolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 학교 목록이 비어 있으면 seed/schools.json(시연용 학교와 별칭)을 넣는다.
 * 전체 목록은 추후 나이스(NEIS) 학교 기본정보로 채울 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchoolSeeder {

    public static final int ORDER = 0;

    private final SchoolRepository schoolRepository;
    private final ObjectMapper objectMapper;

    /** 데모 데이터(DemoDataSeeder)보다 먼저: 학교가 하나라도 있으면 건너뛰므로 순서가 중요하다 */
    @EventListener(ApplicationReadyEvent.class)
    @Order(ORDER)
    @Transactional
    public void seed() throws IOException {
        if (schoolRepository.count() > 0) {
            return;
        }
        try (InputStream in = new ClassPathResource("seed/schools.json").getInputStream()) {
            List<SchoolRequest> schools = objectMapper.readValue(in, new TypeReference<>() {
            });
            schools.forEach(s -> schoolRepository.save(new School(s.name(), s.region(), s.aliases())));
            log.info("학교 목록 {}개 등록 (seed/schools.json)", schools.size());
        }
    }
}
