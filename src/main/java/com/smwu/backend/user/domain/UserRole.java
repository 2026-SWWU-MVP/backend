package com.smwu.backend.user.domain;

/** 가입할 때 고르지 않고, 학원을 만들면 OWNER, 초대 코드로 합류하면 TEACHER가 된다 (설계서 3.2) */
public enum UserRole {
    OWNER,
    TEACHER,
    /** 추후 구현 (#22) */
    STUDENT
}
