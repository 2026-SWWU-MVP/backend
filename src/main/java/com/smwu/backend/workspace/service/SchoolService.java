package com.smwu.backend.workspace.service;

import com.smwu.backend.common.exception.BusinessException;
import com.smwu.backend.common.exception.ErrorCode;
import com.smwu.backend.workspace.domain.School;
import com.smwu.backend.workspace.dto.SchoolRequest;
import com.smwu.backend.workspace.dto.SchoolResponse;
import com.smwu.backend.workspace.repository.SchoolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class SchoolService {

    static final int SEARCH_LIMIT = 20;

    private final SchoolRepository schoolRepository;

    /** 이름·별칭 부분 일치 (공백, 대소문자 무시). 짧은 이름부터 */
    @Transactional(readOnly = true)
    public List<SchoolResponse> search(String query) {
        String key = School.normalize(query);
        if (key.isEmpty()) {
            return List.of();
        }
        return schoolRepository.search(key, PageRequest.of(0, SEARCH_LIMIT)).stream().map(SchoolResponse::of).toList();
    }

    /** 이름이나 별칭이 이미 등록된 학교의 이름·별칭과 겹치면 409 */
    @Transactional
    public SchoolResponse register(SchoolRequest request) {
        List<String> names = Stream.concat(Stream.of(request.name()),
                request.aliases() == null ? Stream.<String>empty() : request.aliases().stream()).toList();
        for (String name : names) {
            schoolRepository.search(School.normalize(name), PageRequest.of(0, SEARCH_LIMIT)).stream()
                    .filter(s -> s.isCalled(name))
                    .findFirst()
                    .ifPresent(s -> {
                        throw new BusinessException(ErrorCode.SCHOOL_DUPLICATED,
                                "이미 등록된 학교입니다: " + s.getName() + (s.getRegion() == null ? "" : " (" + s.getRegion() + ")"));
                    });
        }
        return SchoolResponse.of(schoolRepository.save(new School(request.name(), request.region(), request.aliases())));
    }

    public School getSchool(Long schoolId) {
        return schoolRepository.findById(schoolId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학교를 찾을 수 없습니다."));
    }
}
