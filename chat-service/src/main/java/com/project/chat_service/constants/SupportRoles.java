package com.project.chat_service.constants;

import java.util.Locale;

/**
 * 상담 데스크를 쓸 수 있는 역할 (docs/58).
 *
 * <p>고객지원팀 상담원({@code ROLE_SUPPORT})과 관리자({@code ROLE_ADMIN}). 표기가 배포마다 다를 수 있어 접두사
 * 유무를 둘 다 받는다(common {@code InternalCallGuard.isAdmin} 과 같은 이유).
 */
public final class SupportRoles {

    private SupportRoles() {
    }

    public static boolean isAgent(String role) {
        if (role == null) {
            return false;
        }
        String r = role.trim().toUpperCase(Locale.ROOT);
        return r.equals("ROLE_SUPPORT") || r.equals("SUPPORT") || r.equals("ROLE_ADMIN") || r.equals("ADMIN");
    }

    /** 관리자 — 남이 맡은 상담도 반납시킬 수 있다(자리를 비운 상담원의 것을 풀어 준다). */
    public static boolean isAdmin(String role) {
        if (role == null) {
            return false;
        }
        String r = role.trim().toUpperCase(Locale.ROOT);
        return r.equals("ROLE_ADMIN") || r.equals("ADMIN");
    }
}
