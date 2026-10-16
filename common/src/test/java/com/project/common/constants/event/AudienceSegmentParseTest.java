package com.project.common.constants.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 고객군 읽기 — 저장할 때(parseKnown)는 모르는 값을 거절하고, 읽을 때(from)는 예전처럼 ALL 로 읽는다.
 *
 * <p>[JA] 顧客層の読み取り — 保存時は未知の値を拒否し、読み取り時は従来どおり ALL として読む。
 */
@DisplayName("AudienceSegment — 저장할 때와 읽을 때")
class AudienceSegmentParseTest {

    @Test
    @DisplayName("🔴 저장할 때 모르는 값은 거절 — VIP(오타)가 ALL 이 되지 않는다")
    void parseKnownRejectsUnknown() {
        assertThatThrownBy(() -> AudienceSegment.parseKnown("VIP")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIP");
        assertThat(AudienceSegment.parseKnown(" tier_vip ")).isEqualTo(AudienceSegment.TIER_VIP);
        assertThat(AudienceSegment.parseKnown(null)).isEqualTo(AudienceSegment.ALL);
        assertThat(AudienceSegment.parseKnown(" ")).isEqualTo(AudienceSegment.ALL);
    }

    @Test
    @DisplayName("읽을 때는 그대로 — 옛 서비스가 새 값을 만나도 ALL")
    void fromStillFallsBackToAll() {
        assertThat(AudienceSegment.from("SOMETHING_NEW")).isEqualTo(AudienceSegment.ALL);
    }
}
