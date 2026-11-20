package com.project.notification_service.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.project.notification_service.sms.PhoneNumbers.Region;

/**
 * 한국·일본 번호 처리.
 *
 * <p>이 테스트가 지키는 것은 <b>정규식으로 짰다면 반드시 틀렸을 것들</b>이다. 특히 트렁크
 * 프리픽스 0 — 국내 표기의 맨 앞 0 은 국제 형식에서 사라진다. 이걸 놓치면 문자가 조용히
 * 엉뚱한 곳으로 가거나 게이트웨이가 거부한다.
 *
 * <p>[JA] 韓国・日本の番号処理。このテストが守るのは<b>正規表現で書いていたら必ず間違えていたもの</b>だ。
 * 特にトランクプレフィックス 0 — 国内表記の先頭の 0 は国際形式で消える。これを見落とすと SMS
 * が黙ってまったく別の所へ行くか、ゲートウェイが拒否する。
 */
@DisplayName("전화번호 정규화 (한국·일본)")
class PhoneNumbersTest {

    @Nested
    @DisplayName("한국")
    class Korea {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                // 어떤 표기로 넣어도 같은 E.164 로 모여야 한다. 그래야 중복 등록을 막을 수 있다.
                // [JA] どんな表記で入れても同じ E.164 にまとまるべきだ。そうしてこそ重複登録を防げる。
                "010-1234-5678,      +821012345678",
                "01012345678,        +821012345678",
                "010 1234 5678,      +821012345678",
                "+82 10 1234 5678,   +821012345678",
                "+821012345678,      +821012345678",
                // ⚠️ 핵심: 국가코드를 붙일 때 앞의 0 이 **빠진다**. 그대로 두면 +82010... 이 되어
                //    자릿수가 하나 늘고 발송이 실패한다.
                // [JA] ⚠️ 要: 国番号を付けるとき先頭の 0 が**抜ける**。そのままだと +82010... になり、
                //      桁が一つ増えて送信が失敗する。
                "82-10-1234-5678,    +821012345678",
        })
        void 표기가_달라도_같은_E164_로_모인다(String input, String expected) {
            assertThat(PhoneNumbers.toE164(input, Region.KR)).contains(expected);
        }

        @Test
        @DisplayName("유선번호는 거절한다 — SMS 가 가지 않는다")
        void 유선번호는_거절() {
            // 02-1234-5678 은 형식상 유효한 서울 유선번호다. 형식만 보면 통과하지만
            // SMS 는 도달하지 않으므로 등록 단계에서 걸러야 한다.
            // [JA] 02-1234-5678 は形式上有効なソウルの固定番号だ。形式だけ見れば通るが、SMS
            //      は届かないので登録段階で弾く必要がある。
            assertThat(PhoneNumbers.toE164("02-1234-5678", Region.KR)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "010-1234-56789",    // 자릿수 초과 ／ [JA] 桁数超過
                "abc",
                "",
        })
        void 잘못된_번호는_거절(String input) {
            assertThat(PhoneNumbers.toE164(input, Region.KR)).isEmpty();
        }

        @Test
        @DisplayName("10자리 010 과 구 통신사 대역(019 등)도 유효하다 — 직접 짰다면 막았을 번호들")
        void 흔한_오판_사례() {
            // 이 두 가지는 "010 으로 시작하는 11자리"라는 흔한 정규식이 **거절**하는 번호다.
            // 실제로는 둘 다 유효하고, 막으면 그 사용자는 가입 자체를 못 한다.
            //   · 010-XXX-XXXX (10자리) — 번호 확장 전에 배정된 번호가 아직 쓰인다
            //   · 019/016/017/018 — 구 통신사 식별번호. 번호이동으로 지금도 살아 있다
            // [JA] この二つは「010 で始まる 11 桁」というよくある正規表現が**拒否**する番号だ。実際にはどちらも有効で、
            //      止めるとそのユーザーは登録自体ができない。
            //        · 010-XXX-XXXX(10 桁) — 番号拡張前に割り当てられた番号がまだ使われている
            //        · 019/016/017/018 — 旧通信事業者の識別番号。番号ポータビリティで今も生きている
            assertThat(PhoneNumbers.toE164("010-123-4567", Region.KR)).contains("+82101234567");
            assertThat(PhoneNumbers.toE164("019-1234-5678", Region.KR)).contains("+821912345678");
        }
    }

    @Nested
    @DisplayName("일본")
    class Japan {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                // 일본 휴대폰은 070/080/090. 한국과 마찬가지로 국제 형식에서 앞 0 이 빠진다.
                // [JA] 日本の携帯は 070/080/090。韓国と同様に国際形式で先頭の 0 が抜ける。
                "090-1234-5678,      +819012345678",
                "09012345678,        +819012345678",
                "080-1234-5678,      +818012345678",
                "070-1234-5678,      +817012345678",
                "+81 90 1234 5678,   +819012345678",
        })
        void 휴대폰_대역이_정규화된다(String input, String expected) {
            assertThat(PhoneNumbers.toE164(input, Region.JP)).contains(expected);
        }

        @Test
        @DisplayName("도쿄 유선번호(03)는 거절한다")
        void 유선번호는_거절() {
            assertThat(PhoneNumbers.toE164("03-1234-5678", Region.JP)).isEmpty();
        }
    }

    @Nested
    @DisplayName("국가 판정")
    class RegionDetection {

        @Test
        @DisplayName("기본 국가가 입력의 국가코드를 이기지 못한다")
        void 국가코드가_우선한다() {
            // 사용자의 기본 설정이 한국이어도, +81 로 시작하면 일본 번호다.
            // [JA] ユーザーの既定設定が韓国でも、+81 で始まれば日本の番号だ。
            Optional<String> result = PhoneNumbers.toE164("+81 90 1234 5678", Region.KR);
            assertThat(result).contains("+819012345678");
            assertThat(PhoneNumbers.regionOfE164(result.orElseThrow())).contains(Region.JP);
        }

        @Test
        @DisplayName("지원하지 않는 국가는 거절한다 — 보낼 문구가 없다")
        void 미지원_국가는_거절() {
            // 미국 번호는 형식상 완전히 유효하지만 이 서비스는 한국어·일본어 문구만 갖고 있고
            // 요금 체계도 다르다. 통과시키면 뜻 모를 문자가 나간다.
            // [JA] 米国の番号は形式上完全に有効だが、このサービスは韓国語・日本語の文言しか持たず料金体系も違う。
            //      通すと意味不明な SMS が出る。
            assertThat(PhoneNumbers.toE164("+1 415 555 0132", Region.KR)).isEmpty();
        }

        @Test
        void E164_에서_국가를_되찾는다() {
            assertThat(PhoneNumbers.regionOfE164("+821012345678")).contains(Region.KR);
            assertThat(PhoneNumbers.regionOfE164("+819012345678")).contains(Region.JP);
        }
    }

    @Nested
    @DisplayName("마스킹")
    class Masking {

        @Test
        @DisplayName("가운데를 가리되 식별은 가능하게 남긴다")
        void 마스킹() {
            String masked = PhoneNumbers.mask("+821012345678");

            assertThat(masked)
                    .as("전체 번호가 로그에 남으면 로그 열람 권한이 곧 연락처 열람 권한이 된다")
                    .doesNotContain("1012345678")
                    .startsWith("+8210")
                    .endsWith("5678");
        }

        @Test
        @DisplayName("짧거나 없는 값에도 깨지지 않는다")
        void 마스킹_경계() {
            // 로깅 경로에서 불리므로 여기서 예외가 나면 진짜 오류가 가려진다.
            // [JA] ロギング経路から呼ばれるので、ここで例外が出ると本当のエラーが隠れる。
            assertThat(PhoneNumbers.mask(null)).isEqualTo("***");
            assertThat(PhoneNumbers.mask("+82")).isEqualTo("***");
        }
    }
}
