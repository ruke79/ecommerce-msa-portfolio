import { useNavigate } from "react-router-dom";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 서버에 닿지 못했을 때 보여 주는 화면.
 *
 * <p>`ErrorPage` 가 네트워크 오류(`ERR_NETWORK`)를 만났을 때 여기로 온다. 즉 이 화면이
 * 뜨는 순간은 <b>네트워크가 이미 나쁠 때</b>다 — 그 사실이 아래 두 고침의 이유다.
 *
 * <h3>🐛 고친 것</h3>
 *
 * <ul>
 *   <li><b>외부 이미지를 런타임에 불러왔다.</b> svgrepo.com 의 SVG 를 `<img src>` 로
 *       가져왔는데, 네트워크가 끊겨서 뜬 화면이 <b>또 네트워크를 필요로 했다.</b>
 *       그래서 정작 필요한 순간에는 깨진 이미지가 떴다. 게다가 외부 도메인에 우리
 *       사용자의 방문이 그대로 기록된다. 인라인 SVG 로 바꿨다 — 요청이 0 이다.</li>
 *   <li><b>`&lt;a href="#"&gt;&lt;button&gt;` 중첩.</b> 앵커 안에 버튼을 넣는 것은 유효하지
 *       않은 HTML 이고, 스크린리더는 무엇을 읽어야 할지 모른다. 클릭은 앵커와 버튼
 *       양쪽에서 일어나 `#` 로 이동하면서 동시에 navigate 가 돈다.</li>
 *   <li><b>"Contact Us" 가 `#` 였다.</b> 이 앱에는 문의 페이지가 없다. 갈 곳이 없는 버튼을
 *       두는 것보다, 지금 실제로 도움이 되는 <b>다시 시도</b>를 두는 편이 낫다.</li>
 * </ul>
 */
const Maintenance = () => {
    const { t } = useTranslation();
    const navigate = useNavigate();

    return (
        <div className="bg-gray-100 dark:bg-gray-800">
            <div className="min-h-screen flex flex-col justify-center items-center px-4">
                {/* 인라인 SVG — 이 화면은 네트워크가 나쁠 때 뜬다. 추가 요청이 없어야 한다. */}
                <svg
                    className="mb-8 h-32 w-32 text-gray-400 dark:text-gray-500"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    aria-hidden="true"
                >
                    <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="M12 15.75a3.75 3.75 0 1 0 0-7.5 3.75 3.75 0 0 0 0 7.5Z"
                    />
                    <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        d="M19.5 12c0-.53-.06-1.05-.17-1.55l2.02-1.57-2-3.46-2.4.96a7.5 7.5 0 0 0-2.68-1.55L13.9 2.4h-4l-.37 2.43a7.5 7.5 0 0 0-2.69 1.55l-2.4-.96-2 3.46 2.03 1.57a7.6 7.6 0 0 0 0 3.1L2.44 15.1l2 3.46 2.4-.96a7.5 7.5 0 0 0 2.69 1.55l.37 2.43h4l.37-2.43a7.5 7.5 0 0 0 2.69-1.55l2.4.96 2-3.46-2.03-1.57c.11-.5.17-1.02.17-1.55Z"
                    />
                </svg>

                <h1 className="text-3xl md:text-4xl font-bold text-center text-gray-700 dark:text-white mb-4">
                    {t("maintenance.title")}
                </h1>
                <p className="text-center text-gray-500 dark:text-gray-300 text-base md:text-lg mb-8">
                    {t("maintenance.detail")}
                </p>

                <div className="flex flex-wrap justify-center gap-3">
                    <button
                        type="button"
                        onClick={() => window.location.reload()}
                        className="bg-gray-800 hover:bg-gray-700 text-white font-bold py-3 px-6 rounded dark:bg-gray-700 dark:hover:bg-gray-600"
                    >
                        {t("error.retry")}
                    </button>
                    <button
                        type="button"
                        onClick={() => navigate("/")}
                        className="border-2 border-gray-800 text-black font-bold py-3 px-6 rounded dark:text-white dark:border-white"
                    >
                        {t("error.go_home")}
                    </button>
                </div>
            </div>
        </div>
    );
};

export default Maintenance;
