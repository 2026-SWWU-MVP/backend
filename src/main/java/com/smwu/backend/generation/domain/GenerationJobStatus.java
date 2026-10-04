package com.smwu.backend.generation.domain;

public enum GenerationJobStatus {
    /** 문항 생성 중 */
    RUNNING,
    /** 모든 문항이 끝남 (일부 문항이 실패했을 수 있음) */
    COMPLETED,
    /** 모든 문항이 실패했거나 서버 재시작으로 중단됨 */
    FAILED
}
