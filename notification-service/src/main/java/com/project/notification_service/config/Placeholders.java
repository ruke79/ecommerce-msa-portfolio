package com.project.notification_service.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 채우지 않은 설정값을 <b>기동 시점에</b> 잡는다.
 *
 * <p>이것이 없어서 실제로 이런 일이 있었다. {@code k8s/20-secret.example.yaml} 을 복사해 쓰면서
 * {@code MAIL_HOST} 를 안 채웠는데, 그 값 {@code "REPLACE_ME_smtp_host"} 는 <b>비어 있지 않으므로</b>
 * 기존 검사({@code isBlank()})를 통과했다. 그래서 서비스는 이렇게 찍고 정상 기동했다:
 *
 * <pre>메일 발송기: smtp (실제 발송) from=…</pre>
 *
 * <p>실패는 25초 뒤 {@code UnknownHostException: REPLACE_ME_smtp_host} WARN 한 줄로만 나타났고,
 * 그 사이 가입 확인 메일은 재시도 상한을 전부 태우고 FAILED 가 됐다. 사용자에게는 "가입은 됐는데
 * 메일이 안 온다" 로만 보였다.
 *
 * <p><b>비어 있는 설정은 막으면서 안 채운 설정은 통과시키는 것이 문제였다.</b> 후자가 훨씬 흔한
 * 실수인데도 그랬다 — 비우려면 지워야 하지만, 안 채우는 것은 아무것도 안 하면 된다.
 *
 * <p>여기서 막는 편이 나은 이유는 <b>실패 시점이 원인에 가깝기 때문</b>이다. 기동이 실패하면
 * 파드가 CrashLoopBackOff 로 눈에 띄고 메시지가 어느 키인지 바로 말해 준다. 반대로 통과시키면
 * 원인과 증상이 다른 서비스, 다른 시각, 다른 로그 레벨로 흩어진다.
 */
public final class Placeholders {

    /** {@code 20-secret.example.yaml} 의 모든 자리 표시자가 이 문자열로 시작한다. */
    private static final String MARKER = "REPLACE_ME";

    private Placeholders() {
    }

    /**
     * 자리 표시자가 남아 있으면 기동을 멈춘다.
     *
     * <p>🔒 <b>값은 메시지에 넣지 않는다 — 키 이름만 넣는다.</b> 이 검사가 보는 값 중에는
     * {@code MAIL_PASSWORD} 처럼 진짜 비밀이 섞일 수 있고, 예외 메시지는 로그로 나간다.
     * 지금은 값이 자리 표시자라 찍어도 무해하지만, 그것은 이 검사가 <b>걸렸을 때</b> 뿐이다.
     * "걸린 경우에만 안전한 규칙"은 다음 사람이 검사를 넓히는 순간 깨진다.
     *
     * @param what   무엇을 켜려다 막혔는지 (예: {@code "notification.mail.provider=smtp"})
     * @param values 설정 키 → 값. 키는 사람이 고칠 자리를 가리켜야 한다.
     */
    public static void rejectUnfilled(String what, Map<String, String> values) {
        List<String> unfilled = values.entrySet().stream()
                .filter(e -> isPlaceholder(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();

        if (!unfilled.isEmpty()) {
            throw new IllegalStateException(
                    what + " 인데 다음 설정이 아직 자리 표시자입니다: " + String.join(", ", unfilled)
                    + " — k8s/20-secret.yaml 의 " + MARKER + "… 값을 실제 값으로 채우세요.");
        }
    }

    /**
     * 값이 자리 표시자인가.
     *
     * <p>{@code contains} 로 보는 이유: {@code SPRING_DATA_MONGODB_CLIENT} 처럼 자리 표시자가
     * 연결 문자열 <b>가운데</b> 박혀 있는 경우가 있다
     * ({@code mongodb://admin:REPLACE_ME_mongo_password@…}). {@code startsWith} 로 보면
     * 그런 값을 통째로 놓친다.
     */
    public static boolean isPlaceholder(String value) {
        return value != null && value.contains(MARKER);
    }

    /**
     * {@code 키, 값, 키, 값, …} 을 맵으로 묶는다.
     *
     * <p>{@link Map#of} 를 쓰지 않는 이유가 둘 있다. 순서를 잃어 메시지에 나열되는 키 순서가
     * 매번 달라지고, <b>값에 null 을 넣으면 터진다</b> — 설정이 없는 경우가 정확히 이 검사가
     * 다뤄야 할 경우인데, 거기서 예외가 나면 진짜 원인이 가려진다.
     */
    public static Map<String, String> pairs(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("키와 값이 짝을 이루지 않습니다");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
