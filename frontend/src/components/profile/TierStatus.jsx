import { useEffect, useState } from "react";

import { getMyTier } from "util/api";
import { formatDate } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

/** 등급 기준 금액은 원화다. 숫자 자리 구분만 화면 언어를 따른다. */
// [JA] ランク基準額はウォン建てだ。数字の桁区切りだけ画面の言語に従う。
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
 *
 * <p>[JA] 自分のランク。
 * 🔴 この画面に <b>無いもの</b>が設計だ: 進捗ゲージも「ゴールドまであと 32 万ウォン」も無い。計算して入れるのは簡単だが、その
 * 一行が画面の性格を変える — <b>「あなたの特典」が「あなたがもっと使うべき金額」になる。</b>
 * ランクを点数のように陳列しないのがこのサービスの選んだトーンだ(CustomerTier のコメント)。
 * 代わりに <b>根拠は全部見せる</b>: 今のランク、その判断に使った金額、基準額、
 * 最終計算時刻。「なぜ自分がシルバーなのか」に画面が答えられなければその質問は CS に行く。
 * ユーザーがその数字で自分で引き算するのは止めない — 知りたい人に隠すことと
 * こちらから先に突きつけることは違う。
 * ⏱️ 更新時点を必ず書く。ランクは一日一回付け直されるので「今日大きな注文を
 * したのになぜそのままなのか」が必ず出る。画面があらかじめ答えておく。
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
                // [JA] 文言ではなく「失敗した」という事実だけを入れる — 文言は描くときに今の言語で作る。
                if (alive) setError(true);
            } finally {
                if (alive) setLoading(false);
            }
        })();
        // 언마운트 뒤 setState 를 막는다 — 프로필을 빠르게 벗어나면 경고가 뜬다.
        // [JA] アンマウント後の setState を防ぐ — プロフィールを素早く離れると警告が出る。
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
    // [JA] ランクひとつのためにプロフィール画面全体が落ちてはならない。
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
    // [JA] サーバーは UTC で返す(B-21)。表示時点でだけ現地時刻に変える — util/datetime。
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
            {/* [JA] 特典を先に、ランクはその理由としてだけ。今のランクで受けられるものが何かが
                ユーザーが本当に知りたいことだ。 */}
            <p className="mt-3 text-sm text-slate-600">
                {isBasic
                    ? t("profile.tier.benefit_basic")
                    : t("profile.tier.benefit", { tier: status.displayName })}
            </p>

            {/* 기준은 정적으로 공개한다. 클라이언트에 하드코딩하면 설정을 바꾼 날부터
                화면이 거짓말을 한다 — 서버가 내려준 값을 그대로 쓴다. */}
            {/* [JA] 基準は静的に公開する。クライアントにハードコードすると設定を変えた日から
                画面が嘘をつく — サーバーが返した値をそのまま使う。 */}
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
