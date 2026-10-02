package com.smwu.backend.profile.domain;

public enum ProfileStatus {
    /** 강사 검토 중. 문제 생성에 쓸 수 없음 */
    DRAFT,
    /** 강사가 확정. 워크스페이스당 1개 */
    CONFIRMED,
    /** 새 버전이 확정되어 대체됨 */
    SUPERSEDED
}
