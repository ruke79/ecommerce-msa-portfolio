package com.project.userservice.constants;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AppRole {
    ROLE_USER("ROLE_USER"),
    ROLE_ADMIN("ROLE_ADMIN"),
    /**
     * 고객지원팀 상담원(docs/58). 고객센터 채팅의 상담 데스크를 쓴다 — 관리자 화면(/admin)은 못 쓴다.
     * 관리자가 {@code PUT /api/admin/update-role?roleName=ROLE_SUPPORT} 로 부여한다. 상담원 전용 업무 계정을 쓴다.
     */
    ROLE_SUPPORT("ROLE_SUPPORT");

    private String role;

    AppRole(String role) {
        this.role = role;
    }
}




