package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.userservice.model.KnownDevice;
import com.project.userservice.repository.jpa.KnownDeviceRepository;
import com.project.userservice.security.DeviceIdentity;
import com.project.userservice.service.DeviceRecognitionService.Recognition;

/**
 * "이 로그인이 처음 보는 기기인가" — {@code SECURITY_ALERT} 를 만들어 내는 유일한 판단.
 *
 * <p><b>이 판단은 양쪽으로 조용히 틀린다.</b> 알림이 안 나가면 계정이 털려도 사용자는
 * 모르고, 너무 자주 나가면 무시하게 되어 진짜 경고까지 묻힌다. 둘 다 예외도 로그도 없이
 * 일어나므로 규칙을 표로 고정한다.
 */
@DisplayName("새 기기 인식")
class DeviceRecognitionServiceTest {

    private static final Long USER = 7L;
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    private KnownDeviceRepository repository;
    private DeviceRecognitionService service;

    @BeforeEach
    void setUp() {
        repository = mock(KnownDeviceRepository.class);
        when(repository.save(any(KnownDevice.class))).thenAnswer(i -> {
            KnownDevice device = i.getArgument(0);
            device.setDeviceId(100L);
            return device;
        });
        when(repository.findByUserIdOrderByLastSeenAtDesc(anyLong())).thenReturn(List.of());
        service = new DeviceRecognitionService(repository);
        ReflectionTestUtils.setField(service, "maxPerUser", 20);
    }

    /** 이 사용자에게 이미 기기 기록이 있다고 해 둔다. */
    private void hasDevices(boolean any) {
        when(repository.existsByUserId(USER)).thenReturn(any);
    }

    private Recognition login(String token) {
        return service.recognize(USER, token, UA, "203.0.113.42");
    }

    @Nested
    @DisplayName("알릴 것인가")
    class Alerting {

        /**
         * 🔴 가입하고 처음 로그인한 사람에게 "새로운 기기에서 로그인되었습니다" 는 말이
         * 안 된다 — 비교할 기기가 없으니 새롭다고 할 것도 없다. 게다가 첫인상이 쓸데없는
         * 경고면 나중의 진짜 경고도 무시한다.
         */
        @Test
        @DisplayName("첫 기기에는 알리지 않는다 — 비교할 기기가 없다")
        void firstDeviceIsSilent() {
            hasDevices(false);

            Recognition result = login(null);

            assertThat(result.shouldAlert()).isFalse();
            // 그래도 기록은 한다 — 다음 기기가 새 기기인지 알려면 이게 있어야 한다.
            verify(repository).save(any(KnownDevice.class));
            assertThat(result.cookieToken()).isNotNull();
        }

        @Test
        @DisplayName("두 번째 기기부터 알린다")
        void secondDeviceAlerts() {
            hasDevices(true);

            assertThat(login(null).shouldAlert()).isTrue();
        }

        @Test
        @DisplayName("아는 기기면 알리지 않는다 — 매 로그인마다 경고가 오면 무시하게 된다")
        void knownDeviceIsSilent() {
            String token = "known-token";
            when(repository.findByUserIdAndTokenHash(USER, DeviceIdentity.hash(token)))
                    .thenReturn(Optional.of(device(token, LocalDateTime.now().minusDays(3))));

            Recognition result = login(token);

            assertThat(result.shouldAlert()).isFalse();
            verify(repository, never()).save(any(KnownDevice.class));
        }

        /**
         * 한 브라우저를 두 사람이 쓰면 두 번째 사람에게는 새 기기다. 쿠키 값이 같아도
         * <b>사용자별로</b> 판정해야 한다.
         */
        @Test
        @DisplayName("같은 쿠키라도 다른 사용자에게는 새 기기다")
        void sameCookieDifferentUserIsNew() {
            hasDevices(true);
            // 이 사용자에게는 그 해시가 없다.
            when(repository.findByUserIdAndTokenHash(anyLong(), anyString()))
                    .thenReturn(Optional.empty());

            assertThat(login("someone-elses-cookie").shouldAlert()).isTrue();
        }

        /**
         * 쿠키를 지운 브라우저는 서버 입장에서 처음 보는 브라우저와 구별되지 않는다.
         * 알리는 쪽이 맞다 — 오탐이 아니다.
         */
        @Test
        @DisplayName("쿠키를 지우면 새 기기로 본다")
        void clearedCookieIsANewDevice() {
            hasDevices(true);

            assertThat(login(null).shouldAlert()).isTrue();
        }
    }

    @Nested
    @DisplayName("기록")
    class Recording {

        @Test
        @DisplayName("토큰 원문을 저장하지 않는다 — 해시만 남긴다")
        void storesOnlyTheHash() {
            hasDevices(true);
            String token = "raw-token-value";
            when(repository.findByUserIdAndTokenHash(anyLong(), anyString()))
                    .thenReturn(Optional.empty());

            login(token);

            ArgumentCaptor<KnownDevice> saved = ArgumentCaptor.forClass(KnownDevice.class);
            verify(repository).save(saved.capture());
            assertThat(saved.getValue().getTokenHash()).isNotEqualTo(token);
            assertThat(saved.getValue().getTokenHash()).isEqualTo(DeviceIdentity.hash(token));
        }

        @Test
        @DisplayName("IP 는 마스킹해서 저장한다 — 원문은 어디에도 남지 않는다")
        void storesMaskedIp() {
            hasDevices(true);

            login(null);

            ArgumentCaptor<KnownDevice> saved = ArgumentCaptor.forClass(KnownDevice.class);
            verify(repository).save(saved.capture());
            assertThat(saved.getValue().getLastIp()).isEqualTo("203.0.113.xxx");
            assertThat(saved.getValue().getLastIp()).doesNotContain("42");
        }

        @Test
        @DisplayName("User-Agent 원문이 아니라 라벨을 저장한다")
        void storesALabelNotTheRawUserAgent() {
            hasDevices(true);

            login(null);

            ArgumentCaptor<KnownDevice> saved = ArgumentCaptor.forClass(KnownDevice.class);
            verify(repository).save(saved.capture());
            assertThat(saved.getValue().getLabel()).isEqualTo("Chrome · Windows");
            assertThat(saved.getValue().getLabel()).doesNotContain("AppleWebKit");
        }

        @Test
        @DisplayName("아는 기기는 마지막 사용 시각을 갱신한다")
        void knownDeviceIsTouched() {
            String token = "known";
            LocalDateTime before = LocalDateTime.now().minusDays(3);
            KnownDevice existing = device(token, before);
            when(repository.findByUserIdAndTokenHash(USER, DeviceIdentity.hash(token)))
                    .thenReturn(Optional.of(existing));

            login(token);

            assertThat(existing.getLastSeenAt()).isAfter(before);
        }

        /**
         * 값을 못 읽은 로그인 한 번이 예전 기록을 지우면, 사용자는 목록에서 "알 수 없음"
         * 만 보게 된다.
         */
        @Test
        @DisplayName("라벨을 못 읽어도 예전 라벨을 지우지 않는다")
        void missingLabelDoesNotWipeTheOldOne() {
            String token = "known";
            KnownDevice existing = device(token, LocalDateTime.now().minusDays(3));
            existing.setLabel("Chrome · Windows");
            when(repository.findByUserIdAndTokenHash(USER, DeviceIdentity.hash(token)))
                    .thenReturn(Optional.of(existing));

            service.recognize(USER, token, null, null);

            assertThat(existing.getLabel()).isEqualTo("Chrome · Windows");
        }

        @Test
        @DisplayName("아는 기기면 쿠키를 다시 심지 않는다 — 값이 그대로다")
        void knownDeviceDoesNotResetTheCookie() {
            String token = "known";
            when(repository.findByUserIdAndTokenHash(USER, DeviceIdentity.hash(token)))
                    .thenReturn(Optional.of(device(token, LocalDateTime.now())));

            assertThat(login(token).cookieToken()).isNull();
        }

        /**
         * 쿠키는 있는데 이 사용자에게는 처음인 경우다. 새 토큰을 만들면 브라우저의 다른
         * 계정 기록이 끊긴다 — 가져온 값을 그대로 쓴다.
         */
        @Test
        @DisplayName("가져온 쿠키 값이 있으면 그 값으로 기록한다")
        void reusesThePresentedToken() {
            hasDevices(true);
            when(repository.findByUserIdAndTokenHash(anyLong(), anyString()))
                    .thenReturn(Optional.empty());

            Recognition result = login("existing-browser-token");

            assertThat(result.cookieToken()).isEqualTo("existing-browser-token");
        }
    }

    @Nested
    @DisplayName("동시 로그인")
    class Concurrency {

        /**
         * 🔴 같은 사용자가 동시에 두 번 로그인하면 둘 다 "새 기기" 로 보고 각자 저장을
         * 시도한다. UNIQUE 가 한쪽을 막는데, 그때 알림까지 보내면 사용자는 같은 알림을
         * 두 개 받는다.
         */
        @Test
        @DisplayName("UNIQUE 에 걸린 쪽은 알리지 않는다 — 알림이 두 개가 되면 안 된다")
        void losingTheRaceDoesNotAlert() {
            hasDevices(true);
            when(repository.save(any(KnownDevice.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_known_device"));

            Recognition result = login(null);

            assertThat(result.shouldAlert()).isFalse();
        }

        @Test
        @DisplayName("UNIQUE 에 걸려도 로그인은 계속된다 — 예외를 밖으로 던지지 않는다")
        void losingTheRaceDoesNotThrow() {
            hasDevices(true);
            when(repository.save(any(KnownDevice.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_known_device"));

            assertThat(login(null)).isNotNull();
        }
    }

    @Nested
    @DisplayName("기기 수 상한")
    class Limit {

        private List<KnownDevice> devices(int count) {
            List<KnownDevice> list = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                list.add(device("t" + i, LocalDateTime.now().minusDays(i)));
            }
            return list;
        }

        /** 상한이 없으면 쿠키를 지우는 브라우저 하나가 이 사용자의 행을 무한히 늘린다. */
        @Test
        @DisplayName("상한을 넘으면 오래 안 쓴 것부터 지운다")
        void trimsTheOldest() {
            ReflectionTestUtils.setField(service, "maxPerUser", 3);
            hasDevices(true);
            when(repository.findByUserIdOrderByLastSeenAtDesc(USER)).thenReturn(devices(5));

            login(null);

            ArgumentCaptor<List<KnownDevice>> removed = ArgumentCaptor.forClass(List.class);
            verify(repository).deleteAll(removed.capture());
            assertThat(removed.getValue()).hasSize(2);
        }

        @Test
        @DisplayName("상한 안이면 아무것도 지우지 않는다")
        void keepsEverythingUnderTheLimit() {
            hasDevices(true);
            when(repository.findByUserIdOrderByLastSeenAtDesc(USER)).thenReturn(devices(3));

            login(null);

            verify(repository, never()).deleteAll(any());
        }

        @Test
        @DisplayName("아는 기기로 로그인할 때는 정리하지 않는다 — 매 로그인마다 전체 조회를 만들지 않는다")
        void knownDeviceSkipsTheTrim() {
            String token = "known";
            when(repository.findByUserIdAndTokenHash(USER, DeviceIdentity.hash(token)))
                    .thenReturn(Optional.of(device(token, LocalDateTime.now())));

            login(token);

            verify(repository, never()).findByUserIdOrderByLastSeenAtDesc(anyLong());
        }
    }

    @Nested
    @DisplayName("기기 잊기")
    class Forgetting {

        /** 🔒 id 만으로 지우면 번호를 훑어 남의 기기 기록을 지울 수 있다. */
        @Test
        @DisplayName("남의 기기는 지울 수 없다 — 소유자까지 함께 조회한다")
        void cannotForgetSomeoneElsesDevice() {
            when(repository.findByDeviceIdAndUserId(9L, USER)).thenReturn(Optional.empty());

            assertThat(service.forget(USER, 9L)).isFalse();
            verify(repository, never()).delete(any());
        }

        @Test
        @DisplayName("내 기기는 지운다")
        void forgetsMyDevice() {
            KnownDevice mine = device("t", LocalDateTime.now());
            when(repository.findByDeviceIdAndUserId(100L, USER)).thenReturn(Optional.of(mine));

            assertThat(service.forget(USER, 100L)).isTrue();
            verify(repository).delete(mine);
        }
    }

    private static KnownDevice device(String token, LocalDateTime lastSeen) {
        return KnownDevice.builder()
                .deviceId(100L)
                .userId(USER)
                .tokenHash(DeviceIdentity.hash(token))
                .firstSeenAt(lastSeen)
                .lastSeenAt(lastSeen)
                .build();
    }
}
