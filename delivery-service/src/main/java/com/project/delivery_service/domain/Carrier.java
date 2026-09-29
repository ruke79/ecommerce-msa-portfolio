package com.project.delivery_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 택배사 마스터.
 *
 * <p>이 테이블이 있는 이유는 <b>택배사를 코드 수정 없이 늘리기 위해서</b>다(docs/25 §4).
 * 새 택배사를 붙일 때 필요한 것은 어댑터 클래스 하나와 이 테이블의 행 하나뿐이다 —
 * 스케줄러·상태 판정·알림·화면은 손대지 않는다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "carrier", schema = "delivery")
public class Carrier implements Persistable<String> {

    /** 'KOREA_POST', 'CJ', 'SIMULATED' 등. 어댑터가 자기 코드로 자신을 등록한다. */
    @Id
    @Column(name = "carrier_code", length = 30)
    private String carrierCode;

    @Column(name = "name_ko", nullable = false, length = 60)
    private String nameKo;

    @Column(name = "name_en", length = 60)
    private String nameEn;

    /**
     * 고객이 직접 볼 수 있는 조회 페이지. {@code {trackingNumber}} 를 치환한다.
     *
     * <p>우리가 보여주지 못하는 정보(택배사 자체 화면의 지도·기사 연락처 등)를 사용자가
     * 직접 확인할 수 있게 한다. 추적 데이터가 늦거나 빠질 때의 안전판이기도 하다.
     */
    @Column(name = "tracking_url", length = 300)
    private String trackingUrl;

    @Column(name = "active", nullable = false)
    private boolean active;

    /** 추적 API 를 제공하지 않는 택배사도 있다. 그런 곳은 송장번호만 보관한다. */
    @Column(name = "supports_tracking", nullable = false)
    private boolean supportsTracking;

    public Carrier(String carrierCode, String nameKo, String nameEn,
                   String trackingUrl, boolean supportsTracking) {
        this.carrierCode = carrierCode;
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.trackingUrl = trackingUrl;
        this.active = true;
        this.supportsTracking = supportsTracking;
    }

    /** 송장번호를 끼운 조회 URL. 템플릿이 없으면 null. */
    public String trackingUrlFor(String trackingNumber) {
        if (trackingUrl == null || trackingNumber == null) {
            return null;
        }
        return trackingUrl.replace("{trackingNumber}", trackingNumber);
    }

    // ── 왜 Persistable 을 구현하는가 ────────────────────────────────────────
    //
    // carrier_code('CJ', 'KOREA_POST' 등)는 어댑터가 정하는 자연 키다 — 코드가 직접 배정한다.
    // SimpleJpaRepository 는 save() 때 "@Id 가 null 인가"만 보고 신규 여부를 판단하므로,
    // id 가 이미 차 있는 이 엔티티는 persist() 가 아니라 merge() 로 간다. merge 는 붙일
    // 대상을 찾으려 SELECT 를 먼저 던지고(= INSERT 마다 헛조회), 같은 id 가 이미 커밋돼
    // 있으면 PK 충돌 대신 조용히 UPDATE 를 낸다.
    //
    // 플래그가 "저장됐으면 true" 방향인 이유는 기본값 false 가 곧 "새 것"이라 Lombok 이
    // 필드 초기화를 어떻게 다루든 안전한 쪽으로 떨어지기 때문이다. 불러온 엔티티는
    // @PostLoad 가 true 로 되돌리므로 수정 후 save() 는 그대로 UPDATE 로 간다.
    @Transient
    private boolean persisted;

    @Override
    public String getId() {
        return carrierCode;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    private void markPersisted() {
        this.persisted = true;
    }
}
