import { useEffect, useState } from "react";

import { useTranslation } from "i18n/I18nProvider";
import { adminStorageHealth } from "util/api";

/**
 * 이미지 저장소 상태 배너.
 *
 * <p>상품 등록은 이미지 업로드에 전적으로 기댄다. 자격 증명이 폐기되거나 버킷 정책이
 * 바뀌면 <b>등록 버튼을 누른 뒤에야</b> 500 이 나고, 로그를 볼 수 없는 관리자는 자기 입력이
 * 틀린 줄 안다. 긴 폼을 다 채운 뒤에 그걸 알게 되는 게 가장 나쁘다.
 *
 * <p>그래서 화면이 열릴 때 한 번 실제 쓰기로 확인하고, 안 되면 그 자리에서 말해 준다.
 * 정상일 때는 아무것도 그리지 않는다 — 늘 초록불이 떠 있으면 아무도 안 읽는다.
 */
const StorageHealthBanner = () => {
    // ⚠️ 세 가지 상태를 **구분해야 한다.** 예전에는 정상과 "점검을 못 했음" 을 똑같이
    //    아무것도 안 그렸다(둘 다 status 가 falsy 하거나 writable 이라 null 반환).
    //
    //    그래서 배너가 사라졌을 때 그게 "이제 괜찮다" 인지 "확인을 못 했다" 인지 알 수
    //    없었다. 이 배너의 존재 이유가 **제출 뒤에야 알게 되는 실패를 미리 막는 것**인데,
    //    침묵이 성공처럼 보이면 정확히 그 목적이 무너진다. 실제로 배너가 사라졌을 때
    //    둘 중 어느 쪽인지 되짚어야 했다.
    const [state, setState] = useState({ phase: "loading" });
    const { t } = useTranslation();

    useEffect(() => {
        let alive = true;
        adminStorageHealth()
            .then((data) => alive && setState({ phase: "loaded", status: data }))
            // 점검 자체가 실패해도 등록 화면을 막지는 않는다 — 다만 **조용히 넘어가지도
            // 않는다.** 권한이 없거나 게이트웨이가 흔들리는 경우인데, 어느 쪽이든
            // "저장소가 멀쩡하다" 는 뜻은 아니다.
            .catch(() => alive && setState({ phase: "unknown" }));
        return () => {
            alive = false;
        };
    }, []);

    if (state.phase === "loading") {
        return null;
    }

    if (state.phase === "unknown") {
        return (
            <div className="mb-4 rounded border border-amber-300 bg-amber-50 p-4 text-sm">
                <p className="font-semibold text-amber-800">
                    {t("admin.storage.unknown_title")}
                </p>
                <p className="mt-1 text-amber-700">
                    {t("admin.storage.unknown_detail")}
                </p>
            </div>
        );
    }

    const status = state.status;

    if (!status || status.writable) {
        return null;
    }

    return (
        <div className="mb-4 rounded border border-red-300 bg-red-50 p-4 text-sm">
            <p className="font-semibold text-red-700">
                {t("admin.storage.unwritable_title")}
            </p>
            <p className="mt-1 text-red-700">
                {t("admin.storage.bucket")}: <code>{status.bucket}</code>
            </p>
            {status.detail && (
                <p className="mt-1 break-all text-red-600">{status.detail}</p>
            )}
            <p className="mt-2 text-slate-600">{remedyFor(status, t)}</p>
        </div>
    );
};

/**
 * 무엇을 고쳐야 하는지 고른다.
 *
 * <p>예전에는 원인과 무관하게 한 문장만 띄웠다 — "AWS 자격 증명(AWS_ACCESS_KEY/
 * AWS_SECRET_KEY)을 교체하라". 두 군데가 틀렸다:
 *
 * <ul>
 *   <li><b>이름이 틀렸다.</b> 이 시스템이 읽는 키는 <code>AMAZON_*</code> 다
 *       (catalog-service 의 application-local.yml). <code>AWS_ACCESS_KEY</code> 는
 *       저장소 어디에도 없어서, 그대로 찾아 나서면 아무것도 못 찾는다.</li>
 *   <li><b>원인이 하나가 아니다.</b> 실제로 처음 마주한 실패는 자격 증명이 아니라
 *       인증서 신뢰 문제였는데(PKIX), 배너가 자격 증명을 가리키는 바람에 엉뚱한 곳을
 *       뒤졌다. 틀린 안내는 안내가 없는 것보다 나쁘다.</li>
 * </ul>
 *
 * <p>문구는 부르는 쪽의 {@code t} 로 꺼낸다 — 렌더 중에 불리므로 언어를 바꾸면 함께 바뀐다.
 */
function remedyFor(status, t) {
    const detail = String(status.detail ?? "");
    const bucket = String(status.bucket ?? "");

    // 아직 예시값 그대로다. 자격 증명을 아무리 고쳐도 이 버킷은 존재하지 않는다.
    if (bucket.startsWith("REPLACE_ME")) {
        return t("admin.storage.remedy.placeholder_bucket");
    }

    // TLS 신뢰 실패. 핸드셰이크에서 막히므로 자격 증명까지 가지도 못한다 — 키를 바꿔도
    // 그대로다. 트러스트스토어에 공인 CA 가 들어 있는지부터 봐야 한다(ops/07).
    if (/PKIX|certification path|certificate_unknown|SSLHandshake/i.test(detail)) {
        return t("admin.storage.remedy.tls");
    }

    if (/AccessDenied|InvalidAccessKeyId|SignatureDoesNotMatch|ExpiredToken|credential/i.test(detail)) {
        return t("admin.storage.remedy.credentials");
    }

    if (/NoSuchBucket|does not exist/i.test(detail)) {
        return t("admin.storage.remedy.no_bucket", { bucket });
    }

    return t("admin.storage.remedy.default");
}

export default StorageHealthBanner;
