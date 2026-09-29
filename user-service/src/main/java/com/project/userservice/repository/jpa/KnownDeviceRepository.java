package com.project.userservice.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.project.userservice.model.KnownDevice;

@Repository
public interface KnownDeviceRepository extends JpaRepository<KnownDevice, Long> {

    Optional<KnownDevice> findByUserIdAndTokenHash(Long userId, String tokenHash);

    /** 최근에 쓴 순서. 화면 목록과 상한 정리가 모두 이 순서를 쓴다. */
    List<KnownDevice> findByUserIdOrderByLastSeenAtDesc(Long userId);

    /**
     * 이 사용자에게 기기 기록이 하나라도 있는가.
     *
     * <p>🔴 <b>첫 기기에는 알리지 않기 위해</b> 필요하다. 가입하고 처음 로그인한 사람에게
     * "새로운 기기에서 로그인되었습니다" 를 보내면 그건 소음이다 — 비교할 기기가 없으니
     * 새롭다고 말할 것도 없다. 게다가 첫인상이 "쓸데없는 경고" 가 되면 나중의 진짜 경고도
     * 무시한다.
     */
    boolean existsByUserId(Long userId);

    Optional<KnownDevice> findByDeviceIdAndUserId(Long deviceId, Long userId);
}
