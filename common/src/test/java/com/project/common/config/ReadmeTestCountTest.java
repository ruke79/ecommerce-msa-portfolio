package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * README 의 백엔드 테스트 수는 두 곳(맨 위 표 · "테스트" 절 본문)에 적혀 있다 — 둘이 같은가.
 *
 * <p>테스트를 더할 때마다 표의 수만 고치고 본문의 수를 빠뜨려, 2026-10-13 에 표는 1,993 · 본문은 1,886 으로 100 넘게
 * 어긋나 있었다(브랜치마다 다른 값으로). 사람이 두 곳을 기억하는 대신 여기서 대조한다.
 *
 * <p>[JA] README のバックエンドのテスト数は二か所(冒頭の表 · 「テスト」節の本文)にある — 両者が一致するか。表だけ直して
 * 本文を直し忘れ、100 件以上ずれていた。
 */
@DisplayName("README — 백엔드 테스트 수의 두 표기가 같다")
class ReadmeTestCountTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static List<String> counts(String file, String... patterns) throws IOException {
        String text = Files.readString(repoRoot().resolve(file));
        List<String> found = new ArrayList<>();
        for (String p : patterns) {
            Matcher m = Pattern.compile(p).matcher(text);
            while (m.find()) {
                found.add(m.group(1));
            }
        }
        return found;
    }

    @Test
    @DisplayName("한국어 README — 표의 '백엔드 **N개**' 와 본문의 '**백엔드 N개**'")
    void korean() throws IOException {
        List<String> c = counts("README.ko.md", "백엔드 \\*\\*([0-9,]+)개\\*\\*", "\\*\\*백엔드 ([0-9,]+)개\\*\\*");
        assertThat(c).as("두 표기를 모두 찾지 못했다 — 형식이 바뀌었으면 이 테스트를 고친다").hasSize(2);
        assertThat(c.get(1)).as("본문의 수가 표와 다르다").isEqualTo(c.get(0));
    }

    @Test
    @DisplayName("일본어 README — 표의 'バックエンド **N 件**' 와 본문의 '**バックエンド N 件**'")
    void japanese() throws IOException {
        List<String> c = counts("README.md", "バックエンド \\*\\*([0-9,]+) 件\\*\\*", "\\*\\*バックエンド ([0-9,]+) 件\\*\\*");
        assertThat(c).as("두 표기를 모두 찾지 못했다 — 형식이 바뀌었으면 이 테스트를 고친다").hasSize(2);
        assertThat(c.get(1)).as("본문의 수가 표와 다르다").isEqualTo(c.get(0));
    }
}
