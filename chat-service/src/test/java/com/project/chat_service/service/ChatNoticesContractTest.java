package com.project.chat_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 서버의 채팅 시스템 문구 모양 = 화면이 알아보는 모양 (docs/60 §8).
 *
 * <p>화면(frontend/src/util/chatNotice.js)이 서버가 저장한 문구를 알아보고 화면 언어로 다시 그린다. 여기 문구를 바꾸고 화면을
 * 빠뜨리면 일본어 화면에 한국어가 그대로 나온다 — 한국어 화면에서는 멀쩡해 보여서 아무도 모른다. 두 목록을 글자로 대조한다.
 */
@DisplayName("채팅 시스템 문구 — 서버·화면 대조")
class ChatNoticesContractTest {

    private static Path frontendFile() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = Files.exists(cwd.resolve("settings.gradle")) ? cwd : cwd.getParent();
        return root.resolve("frontend/src/util/chatNotice.js");
    }

    @Test
    @DisplayName("서버가 만드는 모양이 모두 화면의 SERVER_FORMATS 에 같은 글자로 있다")
    void everyServerFormatIsKnownToTheScreen() throws Exception {
        String js = Files.readString(frontendFile(), StandardCharsets.UTF_8);
        for (String format : ChatNotices.FORMATS) {
            assertThat(js).as("화면이 모르는 서버 문구: %s", format).contains("\"" + format + "\"");
        }
    }

    @Test
    @DisplayName("문구를 만드는 곳이 모양을 쓴다 — 이름이 들어갈 자리")
    void buildersMatchFormats() {
        assertThat(ChatNotices.entered("A")).isEqualTo(ChatNotices.FORMATS.get(0).replace("{name}", "A"));
        assertThat(ChatNotices.left("A")).isEqualTo(ChatNotices.FORMATS.get(1).replace("{name}", "A"));
        assertThat(ChatNotices.agentJoined("A")).isEqualTo(ChatNotices.FORMATS.get(2).replace("{name}", "A"));
        assertThat(ChatNotices.tempNickname(35L)).isEqualTo("회원 z");
    }
}
