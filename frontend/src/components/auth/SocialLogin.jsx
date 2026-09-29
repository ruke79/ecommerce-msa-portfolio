import { useEffect, useState } from "react";
import { getRequest } from "../../util/api";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 소셜 로그인 버튼 — **서버가 아는 프로바이더만** 그린다.
 *
 * 버튼을 하드코딩하면 client-id 가 설정되지 않은 프로바이더의 버튼이 그려지고, 누르고
 * 나서야 에러가 난다. 서버의 /api/auth/public/oauth2/providers 가 Secret 에 자격증명이
 * 있는 프로바이더 목록을 주므로 그걸 따른다 — 하나도 없으면 이 컴포넌트는 아무것도
 * 그리지 않는다(구분선 포함).
 *
 * 흐름: 버튼 → 전체 페이지 이동(window.location) → 게이트웨이 → user-service 의
 * /oauth2/authorization/{provider} → 프로바이더 동의 화면 → 콜백에서 REFRESH 쿠키를
 * 심고 홈으로 리다이렉트 → 앱이 새로 뜨며 TokenRefresher 가 세션을 복구한다.
 * XHR 로 하면 안 된다 — 프로바이더 동의 화면은 리다이렉트로만 갈 수 있다.
 */
//
// 카카오·LINE 은 각 사의 버튼 가이드가 색과 문구를 정해 둔다(카카오: #FEE500 바탕·검정 85% 글자·"카카오 로그인",
// LINE: #06C755 바탕·흰 글자·"LINE으로 로그인"). 그래서 공통 문구("{provider} 계정으로 로그인") 대신 자기 문구 키를 쓴다.
const PROVIDER_LABELS = {
    google: { name: "Google", bg: "bg-white", textClass: "text-gray-700 border" },
    github: { name: "GitHub", bg: "bg-gray-900", textClass: "text-white" },
    facebook: { name: "Facebook", bg: "bg-[#1877F2]", textClass: "text-white" },
    kakao: { name: "Kakao", bg: "bg-[#FEE500]", textClass: "text-black/85", labelKey: "social.kakao" },
    line: { name: "LINE", bg: "bg-[#06C755]", textClass: "text-white", labelKey: "social.line" },
};

const SocialLogin = () => {
    const { t } = useTranslation();
    const [providers, setProviders] = useState([]);

    useEffect(() => {
        let cancelled = false;
        getRequest("/user-service/api/auth/public/oauth2/providers")
            .then(({ data }) => {
                if (!cancelled && Array.isArray(data)) setProviders(data);
            })
            .catch(() => {
                // 목록을 못 받으면 버튼을 그리지 않는다. 로그인 화면의 본질(이메일 로그인)을
                // 막을 이유가 없으므로 에러도 띄우지 않는다.
            });
        return () => { cancelled = true; };
    }, []);

    if (providers.length === 0) return null;

    return (
        <div className="flex flex-col space-y-2 mt-3">
            <span className="text-center text-xs text-slate-400">{t("social.or")}</span>
            {providers.map((p) => {
                const meta = PROVIDER_LABELS[p];
                if (!meta) return null;
                return (
                    <button
                        key={p}
                        type="button"
                        data-testid={`social-${p}`}
                        className={`w-full py-2 rounded text-sm font-medium ${meta.bg} ${meta.textClass}`}
                        onClick={() => {
                            window.location.href = `/user-service/oauth2/authorization/${p}`;
                        }}
                    >
                        {meta.labelKey ? t(meta.labelKey) : t("social.signin_with", { provider: meta.name })}
                    </button>
                );
            })}
        </div>
    );
};

export default SocialLogin;
