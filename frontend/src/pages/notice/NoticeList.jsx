import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";

import { getNotices } from "util/api";
import { formatDate as serverDate } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

const PAGE_SIZE = 10;

const formatDate = (value) => serverDate(value, "");

/**
 * 공지사항 목록.
 *
 * <p>지금까지 서비스 점검이나 정책 변경을 알릴 방법이 없었다. 목록은 로그인 없이 열린다.
 *
 * <p>본문은 목록에 싣지 않는다 — 10건짜리 목록이 본문 10개를 끌고 오면 안 된다(서버의
 * NoticeSummary 가 제목·고정·공개시각만 준다).
 */
const NoticeList = () => {
    const { t } = useTranslation();
    const [data, setData] = useState(null);
    const [page, setPage] = useState(0);
    // 문구가 아니라 실패 여부만 담는다 — 문구는 그릴 때 지금 언어로 만든다.
    const [error, setError] = useState(false);
    const [loading, setLoading] = useState(true);

    const load = useCallback(async (next) => {
        setLoading(true);
        setError(false);
        try {
            setData(await getNotices({ page: next, size: PAGE_SIZE }));
            setPage(next);
        } catch (e) {
            setError(true);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        load(0);
    }, [load]);

    const items = data?.items ?? [];
    const totalPages = data?.totalPages ?? 0;

    return (
        <div className="mx-auto w-full max-w-3xl p-4">
            <h1 className="mb-6 text-3xl font-bold">{t("footer.link.notices")}</h1>

            {loading && <p className="text-slate-500">{t("app.loading")}</p>}
            {error && <p className="text-red-600">{t("notice.list.error")}</p>}

            {!loading && !error && items.length === 0 && (
                <p className="text-slate-500">{t("notice.list.empty")}</p>
            )}

            <ul className="divide-y divide-slate-200 border-y border-slate-200">
                {items.map((notice) => (
                    <li key={notice.id}>
                        <Link
                            to={`/notice/${notice.id}`}
                            className="flex items-center justify-between gap-4 py-4 hover:bg-slate-50"
                        >
                            <span className="flex min-w-0 items-center gap-2">
                                {notice.pinned && (
                                    <span className="shrink-0 rounded bg-amazon-orange px-2 py-0.5 text-xs font-semibold text-amazon-blue_dark">
                                        {t("notice.pinned")}
                                    </span>
                                )}
                                <span className="truncate font-medium">{notice.title}</span>
                            </span>
                            <span className="shrink-0 text-sm text-slate-500">
                                {formatDate(notice.publishedAt)}
                            </span>
                        </Link>
                    </li>
                ))}
            </ul>

            {totalPages > 1 && (
                <div className="mt-6 flex items-center justify-center gap-4">
                    <button
                        type="button"
                        disabled={page <= 0 || loading}
                        onClick={() => load(page - 1)}
                        className="rounded border border-slate-300 px-3 py-1 disabled:opacity-40"
                    >
                        {t("notice.list.prev")}
                    </button>
                    <span className="text-sm text-slate-600">
                        {page + 1} / {totalPages}
                    </span>
                    <button
                        type="button"
                        disabled={page + 1 >= totalPages || loading}
                        onClick={() => load(page + 1)}
                        className="rounded border border-slate-300 px-3 py-1 disabled:opacity-40"
                    >
                        {t("notice.list.next")}
                    </button>
                </div>
            )}
        </div>
    );
};

export default NoticeList;
