package com.project.notification_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이 서비스의 시각은 전부 UTC 여야 한다(B-21).
 *
 * <p><b>왜 알림 서비스에서 특히 문제가 되나.</b> 목록은 개인 알림과 전체 알림을 <b>합쳐서</b>
 * {@code createdAt} 으로 정렬한다. 한쪽만 시스템 시간대로 찍히면, 서버가 KST 일 때 그쪽이
 * 언제나 9시간 미래라 항상 맨 위에 붙는다. 게다가 화면은 서버 시각을 UTC 로 보고 현지
 * 시각으로 바꾸므로(NotificationBell 의 RelativeTime) 그 알림은 영영 "방금"으로 표시된다.
 *
 * <p>둘 다 예외가 나지 않는다 — 코드는 멀쩡히 돌고 순서만 틀린다. 그래서 여기서 못박는다.
 *
 * <p>실제로 이 규칙은 전체 알림을 붙이면서 한 번 깨졌다. 나머지 엔티티는 전부
 * {@code LocalDateTime.now(ZoneOffset.UTC)} 인데 새로 추가한 네 파일만 맨손
 * {@code now()} 였다.
 */
@DisplayName("시각은 UTC")
class UtcClockGuardrailTest {

    /** 인자 없는 {@code LocalDateTime.now()} — 시스템 시간대를 쓴다. */
    private static final Pattern BARE_NOW =
            Pattern.compile("\\bLocalDateTime\\s*\\.\\s*now\\s*\\(\\s*\\)");

    /** 블록/줄 주석 안의 예시 코드는 규칙 대상이 아니다. */
    private static final Pattern COMMENTS =
            Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    @Test
    @DisplayName("LocalDateTime.now() 를 맨손으로 쓰지 않는다 — 합쳐진 목록의 정렬이 틀어진다")
    void allClocksAreUtc() throws IOException {
        Path main = Path.of("src", "main", "java");
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(main)) {
            for (Path p : sources.filter(f -> f.toString().endsWith(".java")).toList()) {
                String body = COMMENTS.matcher(Files.readString(p, StandardCharsets.UTF_8))
                        .replaceAll("");
                Matcher m = BARE_NOW.matcher(body);
                if (m.find()) {
                    offenders.add(main.relativize(p).toString());
                }
            }
        }

        assertThat(offenders)
                .as("시스템 시간대로 찍힌 시각은 UTC 로 찍힌 것과 섞이는 순간 정렬이 틀어지고, "
                        + "화면은 그것을 UTC 로 해석해 미래 시각으로 보여 준다. "
                        + "LocalDateTime.now(ZoneOffset.UTC) 를 쓸 것")
                .isEmpty();
    }
}
