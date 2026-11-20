package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
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
 * B-118: 운영 Secret 의 암호문(k8s/20-secret.enc.yaml, SOPS + age — docs/57 §24)은 값이 모두 암호문이고, 평문은 여전히 커밋되지 않는다.
 *
 * <ul>
 *   <li>{@code .sops.yaml} — 그 파일만 · 값(data · stringData)만 암호화, 받는 사람은 age 공개 키(또는 아직 자리표시).</li>
 *   <li>암호문이 있으면 sops 메타데이터가 있고, 비지 않은 값은 모두 {@code ENC[AES256_GCM,…]} — 평문을 암호문 이름으로 커밋하는 실수를 막는다.</li>
 *   <li>.gitignore 는 평문({@code 20-secret.yaml*})을 막고 암호문은 막지 않는다 — 막으면 암호문이 커밋되지 않는다.</li>
 *   <li>포트폴리오(공개)로 옮기지 않는다 — 운영 값의 암호문 · 받는 사람.</li>
 * </ul>
 *
 * <p>[JA] B-118: 本番 Secret の暗号文(k8s/20-secret.enc.yaml、SOPS + age — docs/57 §24)は値がすべて暗号文で、平文は今もコミットされない。
 * <ul>
 *   <li>{@code .sops.yaml} — そのファイルだけ · 値(data · stringData)だけを暗号化、受信者は age 公開鍵(またはまだプレースホルダ)。</li>
 *   <li>暗号文があれば sops メタデータがあり、空でない値はすべて {@code ENC[AES256_GCM,…]} — 平文を暗号文の名前でコミットするミスを防ぐ。</li>
 *   <li>.gitignore は平文({@code 20-secret.yaml*})を防ぎ、暗号文は防がない — 防ぐと暗号文がコミットされない。</li>
 *   <li>ポートフォリオ(公開)には移さない — 本番値の暗号文 · 受信者。</li>
 * </ul>
 */
@DisplayName("B-118: SOPS 암호문 · 규칙 · gitignore · 포트폴리오 제외")
class SopsSecretGuardTest {

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static String read(String rel) throws IOException {
        return Files.readString(repoRoot().resolve(rel), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("🔴 .sops.yaml — 그 파일만, 값만 암호화, 받는 사람은 age 공개 키(또는 자리표시)")
    void sopsRule() throws IOException {
        if (!Files.exists(repoRoot().resolve(".sops.yaml"))) {
            // 규칙이 없는 저장소(포트폴리오 — 운영 전용이라 옮기지 않는다)에는 암호문도 없어야 한다
            // [JA] ルールのないリポジトリ(ポートフォリオ — 運用専用なので移さない)には暗号文もあってはならない
            assertThat(repoRoot().resolve("k8s/20-secret.enc.yaml")).as("규칙 없이 암호문만").doesNotExist();
            return;
        }
        String cfg = read(".sops.yaml");
        assertThat(cfg).contains("path_regex: k8s/20-secret\\.enc\\.yaml$").contains("encrypted_regex: ^(data|stringData)$");
        Matcher age = Pattern.compile("(?m)^\\s+age:\\s*(\\S+)\\s*$").matcher(cfg);
        assertThat(age.find()).as("age 받는 사람").isTrue();
        for (String r : age.group(1).split(",")) {
            assertThat(r.trim()).matches("age1REPLACE_ME|age1[0-9a-z]{58}");
        }
    }

    /** 커밋된 암호문의 비지 않은 값은 모두 ENC[…] 다 — 평문 한 줄이라도 섞이면 실패. 시험용으로 경로를 받는다. */
    // [JA] コミットされた暗号文の空でない値はすべて ENC[…] だ — 平文が 1 行でも混ざれば失敗。試験用にパスを受け取る。
    static List<String> plaintextValues(String enc) {
        List<String> leaked = new ArrayList<>();
        boolean inside = false;
        for (String line : enc.split("\n")) {
            if (line.matches("^(stringData|data):.*")) {
                inside = true;
                continue;
            }
            if (line.matches("^[^ #].*")) {
                inside = false;
            }
            Matcher kv = Pattern.compile("^\\s+([A-Za-z0-9_.-]+):\\s*(.*)$").matcher(line);
            if (inside && kv.matches()) {
                String v = kv.group(2).trim();
                if (!v.isEmpty() && !v.equals("\"\"") && !v.equals("''") && !v.startsWith("ENC[AES256_GCM,")) {
                    leaked.add(kv.group(1));
                }
            }
        }
        return leaked;
    }

    @Test
    @DisplayName("🔴 커밋된 암호문이 있으면 sops 메타데이터 · 값은 모두 암호문(평문을 암호문 이름으로 커밋하지 않는다)")
    void committedCiphertextIsEncrypted() throws IOException {
        Path enc = repoRoot().resolve("k8s/20-secret.enc.yaml");
        if (!Files.exists(enc)) {
            return;   // 아직 만들지 않았다(ops/62 encrypt) — 규칙 · gitignore 만 본다 ／ [JA] まだ作っていない(ops/62 encrypt) — ルール · gitignore だけを見る
        }
        String text = Files.readString(enc, StandardCharsets.UTF_8);
        assertThat(text).contains("\nsops:").contains("age");
        assertThat(plaintextValues(text)).as("암호화되지 않은 값 — ops/62-secrets-sops.sh encrypt 로 다시").isEmpty();
    }

    @Test
    @DisplayName("검사기 자체 — 평문 값 · 들여쓰기 넷(sops 의 모양)을 잡고, 빈 값은 넘긴다")
    void detectorSelfCheck() {
        String sample = "kind: Secret\nstringData:\n    A: ENC[AES256_GCM,data:x,iv:y,tag:z,type:str]\n    B: \"\"\n"
                + "    C: plain-oops\n    #ENC[AES256_GCM,data:comment]\nsops:\n    age: []\n";
        assertThat(plaintextValues(sample)).containsExactly("C");
    }

    @Test
    @DisplayName("🔴 .gitignore 는 평문을 막고 암호문은 막지 않는다 · 포트폴리오로 옮기지 않는다")
    void gitignoreAndPortfolio() throws IOException {
        List<String> patterns = read(".gitignore").lines().map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith("!")).toList();
        assertThat(patterns).contains("k8s/20-secret.yaml*");
        for (String p : patterns) {
            if (p.contains("/") && !p.contains("**")) {
                boolean hitsEnc = FileSystems.getDefault().getPathMatcher("glob:" + p.replaceFirst("^/", ""))
                        .matches(Path.of("k8s/20-secret.enc.yaml"));
                assertThat(hitsEnc).as(".gitignore 의 '" + p + "' 가 암호문을 막는다 — 암호문은 커밋해야 한다").isFalse();
            }
        }
        if (Files.exists(repoRoot().resolve("ops/tools/portfolio-sync.py"))) {   // 원본에만 있는 도구 ／ [JA] 原本にしかないツール
            String sync = read("ops/tools/portfolio-sync.py");
            assertThat(sync).contains("'k8s/20-secret.enc.yaml'").contains("'.sops.yaml'");
        }
    }
}
