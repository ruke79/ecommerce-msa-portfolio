import PropTypes from "prop-types";

import { useTranslation } from "i18n/I18nProvider";

/**
 * 고객센터 채팅의 작은 조각들 — 고객 화면과 상담 데스크가 같이 쓴다 (docs/58 §11).
 *
 * <p>⚠️ 겹침: 상담 화면의 떠 있는 요소(새 메시지 알림 · 빠른 답변 목록)는 카드 안에서만 위로 뜬다. 카드에 {@code isolate}
 * 를 주어 z-index 가 카드 밖으로 새지 않게 한다 — 옛 단체방 화면은 z-index 200 짜리 버튼이 헤더 메뉴를 덮었다.
 */

/** 문의 유형 — 서버 SupportTopic 과 같은 순서 · 값. */
export const SUPPORT_TOPICS = ["ORDER", "DELIVERY", "PAYMENT", "PRODUCT", "ACCOUNT", "OTHER"];

const TOPIC_TONE = {
    ORDER: "bg-indigo-50 text-indigo-700 ring-indigo-200",
    DELIVERY: "bg-sky-50 text-sky-700 ring-sky-200",
    PAYMENT: "bg-amber-50 text-amber-800 ring-amber-200",
    PRODUCT: "bg-emerald-50 text-emerald-700 ring-emerald-200",
    ACCOUNT: "bg-violet-50 text-violet-700 ring-violet-200",
    OTHER: "bg-gray-50 text-gray-600 ring-gray-200",
};

export function TopicChip({ topic, className = "" }) {
    const { t } = useTranslation();
    const key = SUPPORT_TOPICS.includes(topic) ? topic : "OTHER";
    return (
        <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ring-inset ${TOPIC_TONE[key]} ${className}`}
            data-testid="topic-chip">
            {t(`support.topic.${key}`)}
        </span>
    );
}
TopicChip.propTypes = { topic: PropTypes.string, className: PropTypes.string };

const STATUS_TONE = {
    WAITING: "bg-amber-100 text-amber-800",
    ACTIVE: "bg-emerald-100 text-emerald-800",
    CLOSED: "bg-gray-100 text-gray-600",
};

export function StatusBadge({ status }) {
    const { t } = useTranslation();
    if (!STATUS_TONE[status]) return null;
    return (
        <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold ${STATUS_TONE[status]}`}>
            <span aria-hidden="true" className={`h-1.5 w-1.5 rounded-full ${
                status === "ACTIVE" ? "bg-emerald-500" : status === "WAITING" ? "bg-amber-500" : "bg-gray-400"}`} />
            {t(`support.status.${status}`)}
        </span>
    );
}
StatusBadge.propTypes = { status: PropTypes.string };

/** 이름의 첫 글자로 만든 동그라미. 사진은 쓰지 않는다 — 상담에는 연락처 · 얼굴이 필요 없다. */
export function Avatar({ name, tone = "blue", size = "md" }) {
    const letter = String(name ?? "").trim().charAt(0) || "·";
    const tones = {
        blue: "bg-blue-600 text-white",
        gray: "bg-gray-200 text-gray-700",
        green: "bg-emerald-600 text-white",
    };
    const sizes = { sm: "h-7 w-7 text-xs", md: "h-9 w-9 text-sm", lg: "h-11 w-11 text-base" };
    return (
        <span aria-hidden="true"
            className={`inline-flex shrink-0 items-center justify-center rounded-full font-semibold ${tones[tone]} ${sizes[size]}`}>
            {letter}
        </span>
    );
}
Avatar.propTypes = { name: PropTypes.string, tone: PropTypes.oneOf(["blue", "gray", "green"]), size: PropTypes.oneOf(["sm", "md", "lg"]) };

/** 별점 표시(읽기 전용). */
export function Stars({ value, className = "" }) {
    const { t } = useTranslation();
    if (!value) return null;
    return (
        <span className={`inline-flex items-center text-amber-500 ${className}`} role="img"
            aria-label={t("support.rating.stars", { n: value })}>
            {[1, 2, 3, 4, 5].map((n) => (
                <svg key={n} viewBox="0 0 20 20" className={`h-3.5 w-3.5 ${n <= value ? "fill-current" : "fill-gray-200"}`} aria-hidden="true">
                    <path d="M10 1.5l2.6 5.3 5.9.9-4.25 4.1 1 5.8L10 14.9l-5.25 2.7 1-5.8L1.5 7.7l5.9-.9z" />
                </svg>
            ))}
        </span>
    );
}
Stars.propTypes = { value: PropTypes.number, className: PropTypes.string };
