import { Link, useSearchParams } from "react-router-dom";

import BrandMark from "components/common/BrandMark";
import ResendVerification from "components/auth/ResendVerification";
import { useDocumentTitle } from "hook/useDocumentTitle";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 가입 확인 링크가 통하지 않았을 때.
 *
 * <p>🐛 <b>이 화면이 없었다.</b> {@code RegistrationController} 는 실패 시
 * {@code {frontendUrl}/verification-failed?reason=…} 으로 리다이렉트하는데 프론트에 그
 * 라우트가 없어서, 링크를 늦게 누른 사용자는 <b>라우터 오류 화면</b>을 봤다. 무엇이
 * 잘못됐는지도, 무엇을 하면 되는지도 알 수 없었다.
 *
 * <p>여기서 가장 중요한 것은 사과 문구가 아니라 <b>재발송 폼</b>이다. 만료는 정상적으로
 * 일어나는 일이고(링크는 24시간), 그때 사용자가 스스로 빠져나올 수 있어야 한다.
 */

/**
 * 서버가 붙여 보내는 이유 코드 → 문구 키.
 *
 * <p>문구가 아니라 <b>키</b>를 담는다. 문구를 직접 담으면 이 상수가 모듈 로드 시점에
 * 한 번만 평가돼 <b>언어를 바꿔도 바뀌지 않는다.</b>
 */
const REASON_KEYS = {
    expired: "auth.verify_failed.expired",
    invalid: "auth.verify_failed.invalid",
};

const FALLBACK_KEY = "auth.verify_failed.fallback";

const VerificationFailedPage = () => {
    const [searchParams] = useSearchParams();
    const { t } = useTranslation();

    // 모르는 값이 와도 화면이 비지 않게 한다 — 서버가 코드를 늘릴 수 있다.
    const prefix = REASON_KEYS[searchParams.get("reason")] ?? FALLBACK_KEY;

    useDocumentTitle(t("auth.verify_failed.doc_title"));

    return (
        <div className="mx-auto flex w-full flex-col px-4 pb-16 pt-8 sm:w-3/5 md:w-3/5 lg:w-2/5">
            <div className="mx-auto my-2">
                <Link to="/">
                    <BrandMark variant="dark" size="lg" className="pt-2" />
                </Link>
            </div>

            <div className="my-4 flex flex-col space-y-4 rounded border bg-white p-4">
                <h3 className="text-xl font-bold">{t(`${prefix}.title`)}</h3>
                <p className="text-sm text-slate-600">{t(`${prefix}.detail`)}</p>

                <hr />

                <ResendVerification />

                <p className="text-sm">
                    <Link to="/signin" className="text-blue-600 hover:underline">
                        {t("common.back_to_signin")}
                    </Link>
                </p>
            </div>
        </div>
    );
};

export default VerificationFailedPage;
