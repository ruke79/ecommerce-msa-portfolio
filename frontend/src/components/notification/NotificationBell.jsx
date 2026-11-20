import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { IoNotificationsOutline, IoNotifications } from "react-icons/io5";

import {
  fetchNotifications,
  fetchUnreadCount,
  isBroadcast,
  markAllNotificationsRead,
  markNotificationRead,
  deleteNotification,
} from "api/notificationApi";
import NotificationIcon from "./NotificationIcon";
import "./notification.css";
import { parseServerDate } from "util/datetime";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 헤더의 알림 종.
 *
 * 배지는 안 읽은 개수만 따로 가볍게 가져온다 — 목록 전체를 주기적으로 받아오면 알림이 쌓일수록
 * 헤더 하나 때문에 트래픽이 늘어난다. 목록은 드롭다운을 열 때만 가져온다.
 *
 * 목록에는 개인 알림과 **전체 알림(공지·관리자 안내)** 이 섞여 있다. 사용자에게는 "알림"
 * 하나이고 어디에 저장됐는지는 관심 밖이지만, 읽음·삭제 경로가 달라서 각 항목의 scope 를
 * 그대로 들고 다녀야 한다(notificationApi 의 isBroadcast 주석).
 *
 * <p>[JA] ヘッダーの通知ベル。
 * バッジは未読数だけを別に軽く取得する — 一覧全体を周期的に取得すると通知が溜まるほど
 * ヘッダー一つのためにトラフィックが増える。一覧はドロップダウンを開いたときだけ取得する。
 * 一覧には個人通知と **全体通知(お知らせ · 管理者案内)** が混じっている。ユーザーには「通知」一つで
 * どこに保存されたかは関心の外だが、既読 · 削除の経路が違うので各項目の scope を
 * そのまま持ち回る必要がある(notificationApi の isBroadcast のコメント)。
 */
const POLL_INTERVAL_MS = 60_000;

/**
 * 목록 키.
 *
 * 개인 알림과 전체 알림은 **서로 다른 테이블**의 id 다. 둘 다 TSID 라 부딪힐 일은 거의
 * 없지만, 부딪히면 React 가 두 항목을 같은 것으로 보고 하나를 지운다. scope 를 섞어
 * 애초에 가능성을 없앤다.
 *
 * <p>[JA] 一覧のキー。
 * 個人通知と全体通知は **別々のテーブル** の id である。どちらも TSID なのでぶつかることはほぼ
 * ないが、ぶつかると React が二つの項目を同じものとみなし一つを消す。scope を混ぜて
 * 最初から可能性をなくす。
 */
const keyOf = (item) => `${item.scope ?? "PERSONAL"}:${item.id}`;

export default function NotificationBell({ isAuthenticated }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const containerRef = useRef(null);
  const navigate = useNavigate();

  const refreshCount = useCallback(async () => {
    if (!isAuthenticated) {
      setUnread(0);
      return;
    }
    try {
      setUnread(await fetchUnreadCount());
    } catch {
      // 배지 하나 때문에 화면에 오류를 띄우지 않는다. 다음 주기에 다시 시도한다.
      // [JA] バッジ一つのために画面にエラーを出さない。次の周期で再試行する。
    }
  }, [isAuthenticated]);

  useEffect(() => {
    refreshCount();
    if (!isAuthenticated) return undefined;
    const timer = setInterval(refreshCount, POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [isAuthenticated, refreshCount]);

  // 바깥을 누르면 닫는다. 이게 없으면 드롭다운이 화면을 가린 채 남는다.
  // [JA] 外側を押すと閉じる。これがないとドロップダウンが画面を隠したまま残る。
  useEffect(() => {
    if (!open) return undefined;
    const onClickOutside = (event) => {
      if (containerRef.current && !containerRef.current.contains(event.target)) {
        setOpen(false);
      }
    };
    const onEscape = (event) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onClickOutside);
    document.addEventListener("keydown", onEscape);
    return () => {
      document.removeEventListener("mousedown", onClickOutside);
      document.removeEventListener("keydown", onEscape);
    };
  }, [open]);

  const toggle = async () => {
    const next = !open;
    setOpen(next);
    if (next) {
      setLoading(true);
      try {
        setItems(await fetchNotifications(20));
      } catch {
        setItems([]);
      } finally {
        setLoading(false);
      }
    }
  };

  const onSelect = async (item) => {
    // 낙관적으로 먼저 반영한다 — 서버 응답을 기다리는 동안 눌린 항목이 그대로면 답답하다.
    // [JA] 楽観的に先に反映する — サーバーの応答を待つ間、押した項目がそのままだともどかしい。
    if (!item.read) {
      setItems((prev) => prev.map((n) => (keyOf(n) === keyOf(item) ? { ...n, read: true } : n)));
      setUnread((prev) => Math.max(0, prev - 1));
      try {
        // 항목을 통째로 넘긴다 — scope 에 따라 엔드포인트가 갈린다. id 만 넘기면
        // 전체 알림이 개인 알림 경로로 가서 404 가 되거나 남의 알림을 건드린다.
        // [JA] 項目を丸ごと渡す — scope によってエンドポイントが分かれる。id だけ渡すと
        //      全体通知が個人通知の経路へ行って 404 になるか、他人の通知に触れる。
        await markNotificationRead(item);
      } catch {
        refreshCount();
      }
    }
    if (item.url) {
      setOpen(false);
      navigate(`/${item.url}`);
    }
  };

  const onRemove = async (event, item) => {
    event.stopPropagation();   // 항목 클릭(이동)으로 번지지 않게 ／ [JA] 項目クリック(遷移)へ波及しないように
    setItems((prev) => prev.filter((n) => keyOf(n) !== keyOf(item)));
    if (!item.read) setUnread((prev) => Math.max(0, prev - 1));
    try {
      await deleteNotification(item);
    } catch {
      refreshCount();
    }
  };

  const onReadAll = async () => {
    setItems((prev) => prev.map((n) => ({ ...n, read: true })));
    setUnread(0);
    try {
      await markAllNotificationsRead();
    } catch {
      refreshCount();
    }
  };

  if (!isAuthenticated) return null;

  return (
    <div className="notif" ref={containerRef}>
      <button
        type="button"
        className="notif__button"
        onClick={toggle}
        aria-haspopup="true"
        aria-expanded={open}
        // 스크린리더에도 개수를 읽어 준다 — 배지는 시각적 표시일 뿐이다.
        // [JA] スクリーンリーダーにも件数を読ませる — バッジは視覚的な表示にすぎない。
        aria-label={unread > 0 ? t("notification.aria_unread", { count: unread }) : t("notification.title")}
      >
        {unread > 0 ? <IoNotifications size={22} /> : <IoNotificationsOutline size={22} />}
        {unread > 0 && (
          <span className="notif__badge">{unread > 99 ? "99+" : unread}</span>
        )}
      </button>

      {open && (
        <div className="notif__panel" role="menu">
          <div className="notif__header">
            <span>{t("notification.title")}</span>
            {unread > 0 && (
              <button type="button" className="notif__link" onClick={onReadAll}>
                {t("notification.read_all")}
              </button>
            )}
          </div>

          {loading && <div className="notif__empty">{t("notification.loading")}</div>}

          {!loading && items.length === 0 && (
            <div className="notif__empty">{t("notification.empty")}</div>
          )}

          <ul className="notif__list">
            {items.map((item) => (
              <li
                key={keyOf(item)}
                className={`notif__item ${item.read ? "" : "notif__item--unread"}`}
                role="menuitem"
                tabIndex={0}
                onClick={() => onSelect(item)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") onSelect(item);
                }}
              >
                <NotificationIcon type={item.type} />
                <div className="notif__body">
                  <div className="notif__title">{item.title}</div>
                  <div className="notif__content">{item.content}</div>
                  <div className="notif__meta">
                    <RelativeTime value={item.createdAt} />
                    {/* 공지는 나에게만 온 것이 아니다. 그 사실을 밝혀 두지 않으면 "왜 나한테
                        이게 오지?" 가 된다. 삭제 동작도 다르다(내 목록에서만 사라진다). */}
                    {/* [JA] お知らせは自分だけに来たものではない。その事実を明かしておかないと「なぜ自分に
                        これが来るの?」になる。削除の動作も違う(自分の一覧からだけ消える)。 */}
                    {isBroadcast(item) && <span className="notif__scope">{t("notification.broadcast")}</span>}
                    {/* SMS 로도 나갔는지 보여 준다 — 사용자가 "문자 못 받았는데?" 를
                        스스로 확인할 수 있다. */}
                    {/* [JA] SMS でも送られたかを見せる — ユーザーが「ショートメッセージ届いてないけど?」を
                        自分で確認できる。 */}
                    {item.smsStatus === "SENT" && <span className="notif__sms">{t("notification.sms_sent")}</span>}
                    {item.smsStatus === "FAILED" && (
                      <span className="notif__sms notif__sms--failed">{t("notification.sms_failed")}</span>
                    )}
                  </div>
                </div>
                <button
                  type="button"
                  className="notif__remove"
                  aria-label={t("notification.remove")}
                  onClick={(e) => onRemove(e, item)}
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

/** 서버는 UTC 로 내려준다(B-21 과 같은 이유). 표시 시점에만 현지 시각으로 바꾼다. */
// [JA] サーバーは UTC で返す(B-21 と同じ理由)。表示時点でだけ現地時刻に変える。
function RelativeTime({ value }) {
  const { t, lang } = useTranslation();
  if (!value) return null;

  const parsed = parseServerDate(value);
  if (!parsed) return null;

  const diffMinutes = Math.floor((Date.now() - parsed.getTime()) / 60000);
  let label;
  if (diffMinutes < 1) label = t("notification.just_now");
  else if (diffMinutes < 60) label = t("notification.minutes_ago", { count: diffMinutes });
  else if (diffMinutes < 60 * 24) label = t("notification.hours_ago", { count: Math.floor(diffMinutes / 60) });
  // 날짜 표기는 브라우저가 아니라 화면 언어를 따른다.
  // [JA] 日付表記はブラウザではなく画面の言語に従う。
  else label = parsed.toLocaleDateString(lang === "ja" ? "ja-JP" : "ko-KR");

  return <time dateTime={parsed.toISOString()}>{label}</time>;
}
