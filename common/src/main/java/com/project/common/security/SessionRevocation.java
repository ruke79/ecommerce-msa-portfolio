package com.project.common.security;

/**
 * 긴 연결(채팅 WebSocket · 알림 SSE)을 로그인 해제에 묶는 약속 — user-service 가 쓰고 chat-service · user-service SSE 가 읽는다
 * (user-service docs/68 §9).
 *
 * <h3>왜 필요한가</h3>
 *
 * <p>HTTP 요청은 매번 액세스 토큰을 보여 주므로 토큰이 만료되면(최대 15분) 끝난다. 긴 연결은 <b>연결할 때 한 번만</b> 토큰을
 * 본다. 예전 chat-service 의 WebSocket 은 CONNECT 뒤 끊길 때까지 — 토큰이 만료돼도, 로그아웃 · "모든 기기에서 로그아웃" ·
 * 재사용 감지로 로그인이 끊겨도 — 계속 메시지를 받았다. 상한이 없었다.
 *
 * <h3>두 겹</h3>
 *
 * <ol>
 *   <li><b>만료에 묶는다</b> — 연결은 그 토큰의 {@code exp} 에 닫힌다(close code {@link #CLOSE_CODE}). 프론트는 갱신한 토큰으로 다시 붙는다.
 *   <li><b>로그인 해제는 즉시</b> — 사용자의 로그인을 끊을 때 {@link #cutoffKey} 에 "이 시각 이전에 발급된 액세스 토큰은 긴 연결에 못 쓴다" 를
 *       적고 {@link #CHANNEL} 로 알린다. 받은 쪽은 그 사용자의 연결을 {@link #CLOSE_CODE} 로 닫고, 이후 CONNECT · 구독에서 그 시각 이전
 *       토큰을 거절한다 — 닫아도 옛 토큰으로 곧장 다시 붙으면 소용이 없기 때문이다.
 * </ol>
 *
 * <p>4401 은 "토큰을 갱신하고 다시 붙어라" 다. 로그인이 살아 있는 기기는 갱신에 성공해 조용히 다시 붙고, 끊긴 기기는 갱신이 실패해
 * 로그인 화면으로 간다. 그래서 사용자 단위로 넓게 닫아도 해가 없다.
 */
// [JA] 長い接続(チャット WebSocket・通知 SSE)をログイン解除に結びつける約束。① トークンの exp で閉じる(4401)、
//      ② ログイン解除時は cutoff を書いて CHANNEL で知らせ、受け手は即座に閉じ、それ以前に発行されたトークンを拒否する。
public final class SessionRevocation {

    /** 이 사용자의 액세스 토큰 중 {@code iat} 가 이 값(epoch 초)보다 이른 것은 긴 연결에 쓸 수 없다. TTL = 액세스 토큰 수명. */
    public static final String CUTOFF_KEY_PREFIX = "auth:cutoff:";

    /** 로그인 해제 알림 채널(Redis pub/sub). 본문은 사용자 id 숫자 하나 — 토큰 · 이메일은 싣지 않는다. */
    public static final String CHANNEL = "auth:session-revoked";

    /** "토큰이 만료됐거나 로그인이 해제됐다 — 갱신하고 다시 붙어라". WebSocket 앱 전용 범위(4000–4999). */
    public static final int CLOSE_CODE = 4401;

    /** SSE 에서 같은 뜻 — 이 이벤트를 받으면 프론트는 토큰을 갱신한다. */
    public static final String SSE_REAUTH = "Reauth";

    private SessionRevocation() {
    }

    public static String cutoffKey(Long userId) {
        return CUTOFF_KEY_PREFIX + userId;
    }

    /**
     * 이 토큰을 긴 연결에 써도 되는가.
     *
     * @param cutoffEpochSec {@link #cutoffKey} 의 값(없으면 null)
     * @param issuedAtEpochSec 토큰의 {@code iat}(초)
     */
    public static boolean allowed(String cutoffEpochSec, long issuedAtEpochSec) {
        if (cutoffEpochSec == null || cutoffEpochSec.isBlank()) {
            return true;
        }
        try {
            return issuedAtEpochSec >= Long.parseLong(cutoffEpochSec.trim());
        } catch (NumberFormatException e) {
            return true;   // 모르는 값 때문에 모든 연결을 막지 않는다 — 만료 묶기가 여전히 상한을 건다
        }
    }
}
