import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import useDocumentTitle from "hook/useDocumentTitle";
import { getEvents } from "util/api";
import { formatDate } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

const formatRange = (startsAt, endsAt) => {
    const fmt = (v) => formatDate(v, null);
    const from = fmt(startsAt);
    const to = fmt(endsAt);
    if (!from && !to) return "";
    if (!to) return `${from} ~`;
    return `${from ?? ""} ~ ${to}`;
};

/**
 * 진행 중인 기획전 목록.
 *
 * <p>초안과 예약분은 서버가 내려주지 않는다 — 준비 중인 세일의 이름과 시작 시점이 미리
 * 새면 안 된다.
 *
 * <p>대상 상품은 여기서 받지 않는다. 목록 화면은 배너와 제목만 그리는데 기획전마다 상품
 * 수백 개를 실어 보내면 응답만 커지고 쓰이지 않는다(서버의 EventView).
 */
const EventList = () => {
    const { t } = useTranslation();
    const [events, setEvents] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(false);

    useDocumentTitle(t("event.title"));

    useEffect(() => {
        getEvents()
            .then(setEvents)
            .catch(() => setError(true))
            .finally(() => setLoading(false));
    }, []);

    return (
        <div className="mx-auto w-full max-w-5xl p-4">
            <h1 className="mb-6 text-3xl font-bold">{t("event.title")}</h1>

            {loading && <p className="text-slate-500">{t("event.loading")}</p>}
            {/* 오류 문구는 그릴 때 번역한다 — 언어를 바꿔도 따라간다. */}
            {error && <p className="text-red-600">{t("event.load_failed")}</p>}

            {!loading && !error && events.length === 0 && (
                <p className="text-slate-500">{t("event.list.empty")}</p>
            )}

            <ul className="grid gap-6 sm:grid-cols-2">
                {events.map((event) => (
                    <li key={event.id} className="overflow-hidden rounded-lg border border-slate-200">
                        <Link to={`/event/${event.slug}`}>
                            {event.bannerImageUrl && (
                                <img
                                    src={event.bannerImageUrl}
                                    alt=""
                                    /* 배너는 장식이다 — 제목이 바로 아래 글자로 있으므로
                                       같은 내용을 스크린리더에 두 번 읽히지 않는다. */
                                    className="aspect-[5/2] w-full object-cover"
                                    loading="lazy"
                                />
                            )}
                            <div className="p-4">
                                <h2 className="text-lg font-semibold">{event.title}</h2>
                                {event.description && (
                                    <p className="mt-1 line-clamp-2 text-sm text-slate-600">
                                        {event.description}
                                    </p>
                                )}
                                <p className="mt-2 text-xs text-slate-500">
                                    {formatRange(event.startsAt, event.endsAt)}
                                </p>
                            </div>
                        </Link>
                    </li>
                ))}
            </ul>
        </div>
    );
};

export default EventList;
