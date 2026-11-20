package com.project.chat_service.constants;

import java.util.Locale;

/**
 * 상담 데스크를 쓸 수 있는 역할 (docs/58).
 *
 * <p>고객지원팀 상담원({@code ROLE_SUPPORT})과 관리자({@code ROLE_ADMIN}). 표기가 배포마다 다를 수 있어 접두사
 * 유무를 둘 다 받는다(common {@code InternalCallGuard.isAdmin} 과 같은 이유).
 *
 * <p>[JA] 相談デスクを使えるロール(docs/58)。
 * <p>カスタマーサポートチームの相談員({@code ROLE_SUPPORT})と管理者({@code ROLE_ADMIN})。表記がデプロイごとに異なり得るので接頭辞の有無を両方受け付ける(common {@code InternalCallGuard.isAdmin} と同じ理由)。
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
    // [JA] 管理者 — 他人が担当した相談も返却させられる(席を外した相談員のものを解放する)。
    public static boolean isAdmin(String role) {
        if (role == null) {
            return false;
        }
        String r = role.trim().toUpperCase(Locale.ROOT);
        return r.equals("ROLE_ADMIN") || r.equals("ADMIN");
    }
}
