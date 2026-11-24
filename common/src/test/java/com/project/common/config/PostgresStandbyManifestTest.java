package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * PostgreSQL 스탠바이(k8s/91-postgres-standby.yaml — 절차는 그 파일 머리말) — 스트리밍 복제 · 수동 승격.
 *
 * <p>2026-11-23 운영(Ampere 한 대) 옆 데이터 노드에 물리 복제본을 둔다(RPO 24시간 → 수 초). 이 파일이 깨지는 방식은 조용하다:
 * 라벨이 {@code postgres} Service 와 겹치면 앱의 쓰기가 읽기 전용 스탠바이로 가 실패하고, 비밀번호가 PGDATA 에 남으면 백업 · 복사본마다
 * 따라다니며, 인증서 볼륨이 없으면 primary 에서 온 postgresql.conf 의 ssl 경로 때문에 기동하지 못한다.
 *
 * <p>[JA] PostgreSQL スタンバイ(ストリーミング複製 · 手動昇格)のマニフェストの約束 — ラベルが postgres Service と重ならない ·
 * データノードだけ · 証明書ボリューム · パスワードをディスクに書かない · スロット名 · primary と同じイメージ · kustomization の外。
 */
@DisplayName("PostgreSQL 스탠바이 — 앱과 섞이지 않고, 데이터 노드에만, 비밀번호는 디스크에 없다")
class PostgresStandbyManifestTest {

    private static final String STANDBY = "k8s/91-postgres-standby.yaml";
    private static final String INFRA = "k8s/90-dev-infra.yaml";

    private Map<String, Object> deployment;
    private Map<String, Object> podSpec;
    private String raw;

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return cwd.getFileName().toString().equals("common") ? cwd.getParent() : cwd;
    }

    private static String read(String path) throws IOException {
        return Files.readString(repoRoot().resolve(path), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> find(String yaml, String kind, String name) {
        for (Object doc : new Yaml().loadAll(yaml)) {
            if (doc instanceof Map<?, ?> m && kind.equals(m.get("kind"))
                    && name.equals(((Map<String, Object>) m.get("metadata")).get("name"))) {
                return (Map<String, Object>) m;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> podSpecOf(Map<String, Object> deploy) {
        return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) deploy.get("spec")).get("template")).get("spec");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? List.of() : (List<Map<String, Object>>) v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> container(Map<String, Object> spec, String key, String name) {
        return list(spec, key).stream().filter(c -> name.equals(c.get("name"))).findFirst().orElse(null);
    }

    @BeforeEach
    void load() throws IOException {
        raw = read(STANDBY);
        deployment = find(raw, "Deployment", "postgres-standby");
        assertThat(deployment).as("postgres-standby Deployment").isNotNull();
        podSpec = podSpecOf(deployment);
    }

    @Test
    @DisplayName("🔴 파드 라벨이 postgres Service 의 셀렉터와 겹치지 않는다 — 겹치면 앱의 쓰기가 읽기 전용 스탠바이로 간다")
    @SuppressWarnings("unchecked")
    void standbyIsNotBehindTheWriteService() throws IOException {
        Map<String, Object> svc = find(read(INFRA), "Service", "postgres");
        assertThat(svc).as("90-dev-infra 의 postgres Service").isNotNull();
        Map<String, Object> selector = (Map<String, Object>) ((Map<String, Object>) svc.get("spec")).get("selector");
        Map<String, Object> labels = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) deployment.get("spec"))
                .get("template")).get("metadata");
        labels = (Map<String, Object>) labels.get("labels");
        final Map<String, Object> podLabels = labels;
        boolean matches = selector.entrySet().stream().allMatch(e -> e.getValue().equals(podLabels.get(e.getKey())));
        assertThat(matches).as("스탠바이 라벨 %s 가 Service 셀렉터 %s 에 걸린다", podLabels, selector).isFalse();
        assertThat(raw).as("이 파일에 Service 를 두지 않는다 — 승격 뒤 앱을 돌리는 것은 postgres Service 셀렉터(매니페스트 머리말 ④)")
                .doesNotContain("kind: Service");
    }

    @Test
    @DisplayName("데이터 노드에만 · Recreate — 같은 PGDATA 를 두 postgres 가 열지 않는다")
    @SuppressWarnings("unchecked")
    void onlyOnTheDataNodeAndRecreate() {
        Map<String, Object> strategy = (Map<String, Object>) ((Map<String, Object>) deployment.get("spec")).get("strategy");
        assertThat(strategy).containsEntry("type", "Recreate");
        assertThat((Map<String, Object>) podSpec.get("nodeSelector")).containsEntry("ecommerce.io/data", "true");
        assertThat(list(podSpec, "tolerations")).anySatisfy(t -> {
            assertThat(t).containsEntry("key", "ecommerce.io/data").containsEntry("effect", "NoSchedule");
        });
    }

    @Test
    @DisplayName("🔴 primary 의 ssl 인증서 경로를 initContainer 가 파드마다 채운다 — basebackup 이 postgresql.conf 를 그대로 가져온다(B-93)")
    @SuppressWarnings("unchecked")
    void certificatePathIsFilledEveryPod() throws IOException {
        Map<String, Object> init = find(read(INFRA), "ConfigMap", "pg-init");
        String script = String.valueOf(((Map<String, Object>) init.get("data")).get("00-init.sh"));
        Matcher m = Pattern.compile("ssl_(?:cert|key)_file\\s*=\\s*'([^']+)/[^/']+'").matcher(script);
        List<String> dirs = new ArrayList<>();
        while (m.find()) {
            dirs.add(m.group(1));
        }
        assertThat(dirs).as("primary 의 인증서 디렉터리").isNotEmpty();

        Map<String, Object> pg = container(podSpec, "containers", "postgres");
        Map<String, Object> copy = container(podSpec, "initContainers", "copy-secrets");
        assertThat(copy).as("copy-secrets initContainer").isNotNull();
        for (String dir : dirs) {
            String vol = list(pg, "volumeMounts").stream().filter(v -> dir.equals(v.get("mountPath")))
                    .map(v -> String.valueOf(v.get("name"))).findFirst().orElse(null);
            assertThat(vol).as("본 컨테이너가 %s 를 마운트한다", dir).isNotNull();
            assertThat(list(copy, "volumeMounts")).as("copy-secrets 가 같은 볼륨(%s)에 쓴다", vol)
                    .anySatisfy(v -> assertThat(v).containsEntry("name", vol).containsEntry("mountPath", dir));
            assertThat(list(podSpec, "volumes")).as("%s 는 파드마다 새로 — emptyDir", vol)
                    .anySatisfy(v -> assertThat(v).containsEntry("name", vol).containsKey("emptyDir"));
        }
    }

    @Test
    @DisplayName("🔴 복제 비밀번호를 디스크에 쓰지 않는다 — pg_basebackup -R 금지 · 접속 문자열에 password 없음 · passfile 은 메모리 볼륨")
    void replicationPasswordNeverOnDisk() {
        Map<String, Object> base = container(podSpec, "initContainers", "basebackup");
        assertThat(base).as("basebackup initContainer").isNotNull();
        // 줄 이음(\ + 줄바꿈)을 합쳐 명령 하나로 본다 — 둘째 줄의 -R 도 잡는다
        // [JA] 行の継続(\ + 改行)をつないで一つのコマンドとして見る — 二行目の -R も捕まえる
        String script = String.valueOf(base.get("args")).replace("\\\n", " ");
        Matcher cmd = Pattern.compile("pg_basebackup[^\\n]*").matcher(script);
        assertThat(cmd.find()).as("pg_basebackup 명령").isTrue();
        assertThat(cmd.group()).as("-R(--write-recovery-conf)은 비밀번호째 postgresql.auto.conf 에 적는다")
                .doesNotContainPattern("\\s(-R|--write-recovery-conf)\\b")
                .doesNotContainPattern("\\s-[A-Za-z]*R[A-Za-z]*(\\s|$)");
        // 주석(머리말의 Secret 만드는 명령 `--from-file=password=…`)은 빼고 설정 값만 본다
        // [JA] コメント(冒頭の Secret を作るコマンド `--from-file=password=…`)は除き、設定値だけを見る
        String values = raw.lines().filter(l -> !l.stripLeading().startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(values).as("접속 문자열 · 설정에 비밀번호를 적지 않는다").doesNotContainPattern("password\\s*=");

        String args = String.valueOf(container(podSpec, "containers", "postgres").get("args"));
        Matcher passfile = Pattern.compile("passfile=(\\S+)").matcher(args);
        assertThat(passfile.find()).as("primary_conninfo 는 passfile 로").isTrue();
        String dir = passfile.group(1).substring(0, passfile.group(1).lastIndexOf('/'));
        String vol = list(container(podSpec, "containers", "postgres"), "volumeMounts").stream()
                .filter(v -> dir.equals(v.get("mountPath"))).map(v -> String.valueOf(v.get("name"))).findFirst().orElse(null);
        assertThat(vol).as("passfile 디렉터리 %s 의 볼륨", dir).isNotNull();
        assertThat(list(podSpec, "volumes")).as("passfile 은 메모리 볼륨에").anySatisfy(v -> {
            assertThat(v).containsEntry("name", vol);
            assertThat(String.valueOf(v.get("emptyDir"))).contains("Memory");
        });
    }

    @Test
    @DisplayName("슬롯 이름이 받기(init)와 스트리밍(본 컨테이너)에서 같다 — 다르면 primary 가 받지 않은 WAL 을 지운다")
    void slotNameIsTheSameForBackupAndStreaming() {
        Map<String, Object> base = container(podSpec, "initContainers", "basebackup");
        String slot = list(base, "env").stream().filter(e -> "SLOT".equals(e.get("name")))
                .map(e -> String.valueOf(e.get("value"))).findFirst().orElse(null);
        assertThat(slot).as("basebackup 의 SLOT").isNotBlank();
        assertThat(String.valueOf(container(podSpec, "containers", "postgres").get("args")))
                .contains("primary_slot_name=" + slot);
    }

    @Test
    @DisplayName("이미지가 primary 와 같다 — 물리 복제는 메이저 판이 같아야 한다")
    @SuppressWarnings("unchecked")
    void sameImageAsPrimary() throws IOException {
        Map<String, Object> primary = podSpecOf(find(read(INFRA), "Deployment", "postgres"));
        String image = String.valueOf(container(primary, "containers", "postgres").get("image"));
        for (String key : List.of("initContainers", "containers")) {
            assertThat(list(podSpec, key)).allSatisfy(c -> assertThat(c).containsEntry("image", image));
        }
    }

    @Test
    @DisplayName("kustomization 밖 — 앱 배포(ops/28)가 스탠바이를 만들거나 지우지 않는다")
    void notInKustomization() throws IOException {
        assertThat(read("k8s/kustomization.yaml")).doesNotContain("91-postgres-standby");
    }
}
