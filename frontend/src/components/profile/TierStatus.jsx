import { useEffect, useState } from "react";

import { getMyTier } from "util/api";
import { formatDate } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

/** 등급 기준 금액은 원화다. 숫자 자리 구분만 화면 언어를 따른다. */
const numberLocale = (lang) => (lang === "ja" ? "ja-JP" : "ko-KR");

/**
 * 내 등급.
 *
 * <h3>🔴 이 화면에 <b>없는 것</b>이 설계다</h3>
 *
 * <p>진행 게이지도, "골드까지 32만원 남았습니다" 도 없다. 계산해서 넣기는 쉽지만 그
 * 한 줄이 화면의 성격을 바꾼다 — <b>"당신의 혜택"이 "당신이 더 써야 할 금액"이 된다.</b>
 * 등급을 점수처럼 전시하지 않는 것이 이 서비스가 택한 톤이다(CustomerTier 주석).
 *
 * <p>대신 <b>근거는 전부 보여 준다</b>: 지금 등급, 그 판단에 쓰인 금액, 기준 금액,
 * 마지막 계산 시각. "왜 내가 실버인가"에 화면이 답하지 못하면 그 질문은 CS 로 간다.
 * 사용자가 그 숫자로 직접 뺄셈하는 것은 막지 않는다 — 알고 싶은 사람에게 감추는 것과
 * 우리가 먼저 들이미는 것은 다르다.
 *
 * <p>⏱️ 갱신 시점을 반드시 적는다. 등급은 하루 한 번 다시 매겨지므로 "오늘 큰 주문을
 * 했는데 왜 그대로냐"가 반드시 나온다. 화면이 미리 답해 둔다.
 */
const TierStatus = () => {
    const { t, lang } = useTranslation();
    const won = (amount) =>
        t("profile.tier.amount", { amount: Number(amount ?? 0).toLocaleString(numberLocale(lang)) });
    const [status, setStatus] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(false);

    useEffect(() => {
        let alive = true;
        (async () => {
            try {
                const data = await getMyTier();
                if (alive) setStatus(data);
            } catch (e) {
                // 문구가 아니라 "실패했다"는 사실만 담는다 — 문구는 그릴 때 지금 언어로 만든다.
                if (alive) setError(true);
            } finally {
                if (alive) setLoading(false);
            }
        })();
        // 언마운트 뒤 setState 를 막는다 — 프로필을 빠르게 벗어나면 경고가 뜬다.
        return () => { alive = false; };
    }, []);

    if (loading) {
        return (
            <section className="mt-10 border-t pt-6">
                <h3 className="font-semibold">{t("profile.tier.title")}</h3>
                <p className="mt-3 text-sm text-slate-500">{t("profile.loading")}</p>
            </section>
        );
    }

    // 등급 하나 때문에 프로필 화면 전체가 죽으면 안 된다.
    if (error) {
        return (
            <section className="mt-10 border-t pt-6">
                <h3 className="font-semibold">{t("profile.tier.title")}</h3>
                <p className="mt-3 text-sm text-red-600">{t("profile.tier.error")}</p>
            </section>
        );
    }

    if (!status) return null;

    // 서버는 UTC 로 준다(B-21). 표시 시점에만 현지 시각으로 바꾼다 — util/datetime.
    const computedAt = formatDate(status.computedAt, null);
    const isBasic = status.tier === "BASIC";

    return (
        <section className="mt-10 border-t pt-6">
            <h3 className="font-semibold">{t("profile.tier.title")}</h3>

            <div className="mt-3 flex items-baseline gap-2">
                <span className="text-2xl font-semibold text-amazon-blue_light">
                    {status.displayName}
                </span>
                <span className="text-xs text-slate-500">
                    {t("profile.tier.recent_paid", { months: status.months, amount: won(status.recentPaidAmount) })}
                </span>
            </div>

            {/*
              * 혜택을 먼저, 등급은 그 이유로만. 지금 등급으로 받을 수 있는 것이 무엇인지가
              * 사용자가 실제로 알고 싶은 것이다.
              */}
            <p className="mt-3 text-sm text-slate-600">
                {isBasic
                    ? t("profile.tier.benefit_basic")
                    : t("profile.tier.benefit", { tier: status.displayName })}
            </p>

            {/* 기준은 정적으로 공개한다. 클라이언트에 하드코딩하면 설정을 바꾼 날부터
                화면이 거짓말을 한다 — 서버가 내려준 값을 그대로 쓴다. */}
            <dl className="mt-4 rounded bg-slate-50 p-3 text-xs text-slate-600">
                <dt className="font-medium text-slate-700">{t("profile.tier.criteria", { months: status.months })}</dt>
                <dd className="mt-2 flex flex-col gap-1">
                    <span>{t("profile.tier.silver", { amount: won(status.silverAmount) })}</span>
                    <span>{t("profile.tier.gold", { amount: won(status.goldAmount) })}</span>
                    <span>{t("profile.tier.vip", { amount: won(status.vipAmount) })}</span>
                </dd>
            </dl>

            <p className="mt-2 text-xs text-slate-400">
                {t("profile.tier.refresh_before")}
                <b>{t("profile.tier.refresh_bold")}</b>
                {t("profile.tier.refresh_after")}
                {computedAt && t("profile.tier.last_updated", { at: computedAt })}
                {t("profile.tier.refresh_tail")}
            </p>
        </section>
    );
};

export default TierStatus;
