import { Link } from "react-router-dom";
import { ChatBubbleLeftRightIcon, BellIcon, MegaphoneIcon } from "@heroicons/react/24/outline";
import { useAuthContext } from "store/AuthContext";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 홈 상단의 서비스 바로가기.
 *
 * <p>예전에는 MUI `Grid` 안에 `<Item href='chat'>Chat</Item>` 버튼 하나였다. 세 가지가
 * 문제였다:
 *
 * <ol>
 *   <li><b>`href` 라서 전체 새로고침이 일어난다.</b> SPA 인데 링크 하나가 앱을 통째로
 *       다시 받아 온다. 게다가 `href='chat'` 은 <b>상대 경로</b>라 `/` 에서는 `/chat` 이
 *       되지만 `/browse` 에서 같은 컴포넌트를 쓰면 `/browse/chat` 이 된다.</li>
 *   <li><b>`Grid size={8}`</b> — 12칸 중 8칸을 버튼 하나가 차지해, 회색 버튼이 화면
 *       가로의 2/3 를 덮은 채 캐러셀 위에 떠 있었다.</li>
 *   <li>로그인 상태를 보지 않는다. 채팅은 로그인이 필요한데 눌러서 들어가면 튕긴다.</li>
 * </ol>
 *
 * <p>MUI 를 걷어내고 나머지 화면과 같은 Tailwind 로 맞췄다. 홈 최상단이라 눈에 띄되
 * 캐러셀을 가리지 않아야 해서, 버튼이 아니라 얇은 바로가기 줄로 둔다.
 */
const SERVICES = [
    {
        to: "/support",
        // 이름표는 사전 키 — 그릴 때 지금 언어로 번역한다.
        labelKey: "footer.link.support",
        descriptionKey: "home.services.chat_desc",
        Icon: ChatBubbleLeftRightIcon,
        requiresAuth: true,
    },
    {
        to: "/notice",
        labelKey: "footer.link.notices",
        descriptionKey: "home.services.notice_desc",
        Icon: MegaphoneIcon,
        requiresAuth: false,
    },
];

const MicroServices = () => {
    const { t } = useTranslation();
    const { isAuthenticated } = useAuthContext();

    return (
        <nav
            aria-label={t("home.services.label")}
            className="flex flex-wrap gap-2 px-4 py-3"
        >
            {SERVICES.map(({ to, labelKey, descriptionKey, Icon, requiresAuth }) => (
                <Link
                    key={to}
                    // 로그인이 필요한 곳은 로그인 화면으로 보내되, 어디로 가려 했는지
                    // 넘긴다 — 로그인하면 여기로 돌아온다(SignInPage 의 redirectTo).
                    to={requiresAuth && !isAuthenticated ? "/signin" : to}
                    state={
                        requiresAuth && !isAuthenticated
                            ? { from: { pathname: to } }
                            : undefined
                    }
                    className="group flex items-center gap-3 flex-1 min-w-[220px] max-w-sm
                               bg-white border border-slate-200 rounded-xl px-4 py-3
                               hover:border-amazon-blue_light hover:shadow-sm
                               transition-all duration-200"
                >
                    <span className="flex items-center justify-center w-10 h-10 rounded-full
                                     bg-amazon-blue_light/10 text-amazon-blue_light
                                     group-hover:bg-amazon-blue_light group-hover:text-white
                                     transition-colors duration-200">
                        <Icon className="w-5 h-5" />
                    </span>
                    <span className="flex flex-col text-left">
                        <span className="font-semibold text-sm text-amazon-blue_dark">
                            {t(labelKey)}
                        </span>
                        <span className="text-xs text-slate-500">{t(descriptionKey)}</span>
                    </span>
                </Link>
            ))}
        </nav>
    );
};

export default MicroServices;
