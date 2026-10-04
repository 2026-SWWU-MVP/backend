package com.smwu.backend.workspace.controller;

import com.smwu.backend.workspace.dto.SchoolRequest;
import com.smwu.backend.workspace.dto.SchoolResponse;
import com.smwu.backend.workspace.service.SchoolService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 공용 학교 목록. 워크스페이스를 만들 때 검색하고, 없으면 등록한다 */
@RestController
@RequestMapping("/api/schools")
@RequiredArgsConstructor
public class SchoolController {

    private final SchoolService schoolService;

    /** 이름·별칭 부분 일치 (예: "건대부고", "압구정"), 최대 20개 */
    @GetMapping
    public List<SchoolResponse> search(@RequestParam("query") String query) {
        return schoolService.search(query);
    }

    /** 이름이나 별칭이 이미 있으면 409 SCHOOL_DUPLICATED */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SchoolResponse register(@Valid @RequestBody SchoolRequest request) {
        return schoolService.register(request);
    }
}
