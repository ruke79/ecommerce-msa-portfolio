package com.project.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KafkaTransactionalIds")
class KafkaTransactionalIdsTest {

    @Test
    @DisplayName("같은 프로세스 안에서는 같은 접두사 → 같은 id (재시작 사이에도 호스트명이 같으면 같다)")
    void stableWithinProcess() {
        assertThat(KafkaTransactionalIds.stable("order-saga-cart")).isEqualTo(KafkaTransactionalIds.stable("order-saga-cart"));
        assertThat(KafkaTransactionalIds.stable("order-saga-cart")).startsWith("order-saga-cart-");
    }

    @Test
    @DisplayName("접두사가 다르면 id 가 다르다 — 같은 JVM 의 두 프로듀서가 서로를 펜싱하지 않도록")
    void differentPrefixesDiffer() {
        assertThat(KafkaTransactionalIds.stable("a")).isNotEqualTo(KafkaTransactionalIds.stable("b"));
    }

    @Test
    @DisplayName("호스트명은 비어 있지 않다")
    void instanceNameIsNotBlank() {
        assertThat(KafkaTransactionalIds.instanceName()).isNotBlank();
    }
}
