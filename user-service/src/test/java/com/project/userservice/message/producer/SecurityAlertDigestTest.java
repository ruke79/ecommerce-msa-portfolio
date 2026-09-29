package com.project.userservice.message.producer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 로그인 해제 알림의 eventId(docs/68) — 묶음마다 하나로 고정되고, 묶음 id 원문(토큰의 클레임)은 드러내지 않는다. */
@DisplayName("보안 알림 — 로그인 해제 eventId")
class SecurityAlertDigestTest {

    @Test
    @DisplayName("같은 묶음이면 같은 값(재전달 · 동시 감지에도 알림 하나), 다른 묶음이면 다른 값")
    void stablePerFamily() {
        String a = SecurityAlertProducer.familyDigest("7c9e6679-7425-40de-944b-e07fc1f90ae7");
        assertThat(a).isEqualTo(SecurityAlertProducer.familyDigest("7c9e6679-7425-40de-944b-e07fc1f90ae7"));
        assertThat(a).isNotEqualTo(SecurityAlertProducer.familyDigest("0f8fad5b-d9cb-469f-a165-70867728950e"));
        assertThat(a).hasSize(32).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("묶음 id 원문을 싣지 않는다")
    void doesNotLeakTheFamilyId() {
        String family = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
        assertThat(SecurityAlertProducer.familyDigest(family)).doesNotContain("7c9e6679");
    }
}
