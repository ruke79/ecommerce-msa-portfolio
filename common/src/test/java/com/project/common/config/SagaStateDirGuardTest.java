package com.project.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * saga-projection 의 Kafka Streams 상태 디렉터리는 emptyDir 마운트의 <b>뿌리가 아니라 그 아래</b>여야 한다(docs/99 §4).
 *
 * <p>Kafka Streams 는 상태 디렉터리의 권한을 700 으로 바꾼다. emptyDir 뿌리는 root 소유라 앱(uid 1000)이 바꾸지 못해 기동마다 ERROR
 * 'Error changing permissions for the directory /app/state' 가 났다. 앱이 만든 하위 디렉터리는 앱 소유라 바꿀 수 있다.
 *
 * <p>[JA] saga-projection の Kafka Streams 状態ディレクトリは emptyDir マウントの根ではなくその下でなければならない。根は root 所有で
 *      アプリが権限を変えられず、起動ごとに ERROR が出た。
 */
@DisplayName("saga-projection — 상태 디렉터리는 마운트 뿌리 아래")
class SagaStateDirGuardTest {

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) {
            p = p.getParent();
        }
        return p;
    }

    @Test
    @DisplayName("🔴 SAGA_STATE_DIR 은 state 볼륨의 mountPath 아래 하위 디렉터리")
    void stateDirIsBelowMountRoot() throws Exception {
        String yaml = Files.readString(repoRoot().resolve("k8s/49-saga-projection.yaml"));
        Matcher dir = Pattern.compile("name: SAGA_STATE_DIR\\s+value: \"([^\"]+)\"").matcher(yaml);
        Matcher mount = Pattern.compile("- name: state\\s+mountPath: (\\S+)").matcher(yaml);
        assertThat(dir.find()).as("SAGA_STATE_DIR").isTrue();
        assertThat(mount.find()).as("state 볼륨 마운트").isTrue();
        String mountPath = mount.group(1).replaceAll("/+$", "");
        assertThat(dir.group(1)).startsWith(mountPath + "/").isNotEqualTo(mountPath + "/");
    }
}
