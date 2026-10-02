package com.smwu.backend.problem.domain;

import com.smwu.backend.problem.type.ValidationCheck;

import java.util.List;

/**
 * 자동 검증 기록 (발표용 수치 #23의 원천 데이터).
 *
 * @param checks          마지막 시도의 규칙 검증 항목별 결과
 * @param attempts        생성 시도 횟수 (1~3)
 * @param attemptFailures 실패한 시도별 이유
 */
public record ValidationReport(List<ValidationCheck> checks, int attempts, List<String> attemptFailures) {
}
