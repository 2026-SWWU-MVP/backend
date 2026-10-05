package com.smwu.backend.academy.domain;

/**
 * 요금제 (설계서 3.5). MVP에서는 결제 없이 강사 수 제한만 실제로 막는다. 월 생성 문항 수는 기획용.
 *
 * @param maxTeachers 원장을 제외한 최대 강사 수
 */
public enum AcademyPlan {
    BASIC(3, 500),
    PRO(10, 3000);

    private final int maxTeachers;
    private final int monthlyProblems;

    AcademyPlan(int maxTeachers, int monthlyProblems) {
        this.maxTeachers = maxTeachers;
        this.monthlyProblems = monthlyProblems;
    }

    public int maxTeachers() {
        return maxTeachers;
    }

    public int monthlyProblems() {
        return monthlyProblems;
    }
}
