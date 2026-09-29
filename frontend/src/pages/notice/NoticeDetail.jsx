import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";

import { getNotice } from "util/api";
import { formatDateTime } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

const formatDate = (value) => formatDateTime(value, "");

/**
 * 공지 상세.
 *
 * <p>본문은 <b>일반 텍스트로</b> 그린다. {@code dangerouslySetInnerHTML} 을 쓰면 관리자
 * 계정 하나만 뚫려도 모든 방문자에게 스크립트가 실행되는 저장형 XSS 가 된다. 줄바꿈은
 * {@code whitespace-pre-wrap} 이 살려 주므로 대부분의 공지에는 이것으로 충분하다.
 * 서식이 꼭 필요해지면 마크다운 + sanitize 를 거쳐야 한다.
 */
const NoticeDetail = () => {
    const { t } = useTranslation();
    const { id } = useParams();
    const [notice, setNotice] = useState(null);
    // 문구가 아니라 사전 키를 담는다 — 언어를 바꾸면 오류 문구도 따라 바뀐다.
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let cancelled = false;

        (async () => {
            setLoading(true);
            setError("");
            try {
                const loaded = await getNotice(id);
                if (!cancelled) setNotice(loaded);
            } catch (e) {
                if (!cancelled) {
                    setError(
                        e?.response?.status === 404
                            ? "notice.detail.not_found"
                            : "notice.detail.error"
                    );
                }
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [id]);

    return (
        <div className="mx-auto w-full max-w-3xl p-4">
            <Link to="/notice" className="text-sm text-slate-500 hover:underline">
                {t("notice.detail.back")}
            </Link>

            {loading && <p className="mt-6 text-slate-500">{t("app.loading")}</p>}
            {error && <p className="mt-6 text-red-600">{t(error)}</p>}

            {notice && (
                <article className="mt-4">
                    <header className="border-b border-slate-200 pb-4">
                        <h1 className="text-2xl font-bold">
                            {notice.pinned && (
                                <span className="mr-2 rounded bg-amazon-orange px-2 py-0.5 align-middle text-xs font-semibold text-amazon-blue_dark">
                                    {t("notice.pinned")}
                                </span>
                            )}
                            {notice.title}
                        </h1>
                        <p className="mt-2 text-sm text-slate-500">
                            {formatDate(notice.publishedAt)}
                        </p>
                    </header>
                    <div className="mt-6 whitespace-pre-wrap leading-relaxed text-slate-800">
                        {notice.body}
                    </div>
                </article>
            )}
        </div>
    );
};

export default NoticeDetail;
