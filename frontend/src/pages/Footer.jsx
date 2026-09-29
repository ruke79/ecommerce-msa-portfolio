import { GlobeAltIcon } from "@heroicons/react/24/outline";
import { Link } from "react-router-dom";

import BrandMark from "components/common/BrandMark";
import { BRAND_NAME, BRAND_SINCE } from "constants/brand";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 푸터.
 *
 * <p>예전 푸터는 아마존 클론 템플릿 그대로였다:
 *
 * <ul>
 *   <li>"About Amazon" · "Amazon Devices" · "Amazon Science" · "Sell products on Amazon" ·
 *       "Amazon Payment Products" · "Amazon Currency Converter" 등 <b>남의 상호</b>로 된
 *       링크 30여 개</li>
 *   <li>그 아래 "Mazon Music / Amazon Global / Home Services / PillPack" 네 개를
 *       <b>다섯 번 복사해 붙인</b> 20개짜리 목록 (오타 "Mazon" 까지 그대로 반복된다)</li>
 *   <li>© 1996-2023, Amazon.com, Inc. or its affiliates</li>
 * </ul>
 *
 * <p>그리고 이 링크들은 <b>전부 어디로도 가지 않는다</b> — `to="/"` 또는 `to=""`. 누르면
 * 홈으로 가거나 아무 일도 없다. 즉 이 영역은 정보가 아니라 화면을 채우는 장식이었고,
 * 그 장식이 하필 남의 브랜드였다.
 *
 * <p>실제로 있는 것만 남긴다. 링크가 생기면 그때 추가하면 된다 — 없는 페이지로 가는
 * 링크를 미리 깔아 두는 것보다 낫다.
 */

// 실제로 동작하는 링크만 넣는다. `to` 가 없는 항목은 아직 페이지가 없다는 뜻이고,
// 링크가 아니라 회색 글자로 그린다 — 눌러도 아무 일이 없는 링크는 고장으로 보인다.
// 이름표는 사전 키로 둔다 — 모듈을 읽는 순간의 언어로 굳지 않게 그릴 때 번역한다.
const SECTIONS = [
    {
        titleKey: "footer.section.shopping",
        links: [
            { labelKey: "footer.link.all_products", to: "/browse" },
            { labelKey: "footer.link.cart", to: "/cart" },
            { labelKey: "footer.link.orders", to: "/profile/orders" },
        ],
    },
    {
        titleKey: "footer.section.account",
        links: [
            { labelKey: "footer.link.profile", to: "/profile" },
            { labelKey: "footer.link.addresses", to: "/profile/address" },
            { labelKey: "footer.link.payment", to: "/profile/payment" },
            { labelKey: "footer.link.security", to: "/profile/security" },
        ],
    },
    {
        titleKey: "footer.section.support",
        links: [
            { labelKey: "footer.link.notices", to: "/notice" },
            { labelKey: "footer.link.events", to: "/event" },
            { labelKey: "footer.link.support", to: "/support" },
            { labelKey: "footer.link.chat", to: "/chat" },
            { labelKey: "footer.link.shipping_policy" },
            { labelKey: "footer.link.returns" },
        ],
    },
];

const Footer = () => {
    const { t, lang } = useTranslation();
    const scrollToTop = () => window.scrollTo({ top: 0, behavior: "smooth" });

    return (
        <footer className="flex flex-col w-full mx-auto">
            {/*
              * 예전에는 `<Link to="#">` 이었다. react-router 에서 "#" 은 경로로 해석돼
              * 이상한 곳으로 이동한다 — 맨 위로 올라가지 않는다. 버튼으로 바꾸고
              * 실제로 스크롤한다.
              */}
            <button
                type="button"
                onClick={scrollToTop}
                className="flex bg-[#37475a] hover:bg-[#485769] justify-center w-full"
            >
                <span className="text-white text-xs py-4">{t("footer.back_to_top")}</span>
            </button>

            <div className="grid grid-cols-2 lg:grid-cols-3 md:px-32 gap-4 px-5 bg-amazon-blue_light justify-center py-10 md:space-x-16 border-b border-slate-600">
                {SECTIONS.map((section) => (
                    <div className="link-footer" key={section.titleKey}>
                        <h5>{t(section.titleKey)}</h5>
                        <ul>
                            {section.links.map((link) => (
                                <li key={link.labelKey}>
                                    {link.to ? (
                                        <Link to={link.to}>{t(link.labelKey)}</Link>
                                    ) : (
                                        <span className="text-slate-400 cursor-default">
                                            {t(link.labelKey)}
                                        </span>
                                    )}
                                </li>
                            ))}
                        </ul>
                    </div>
                ))}
            </div>

            <div className="flex max-md:flex-col items-center bg-amazon-blue_light justify-center py-4 gap-4 md:gap-16">
                <BrandMark variant="light" size="md" />

                <div className="flex items-center space-x-2 max-md:mb-2">
                    {/*
                      * 예전에는 English / USD / United States(성조기) 세 칸이었다. 이
                      * 사이트는 한국어이고 notification-service 는 KR·JP 템플릿을 갖고
                      * 있다 — 미국 기본값은 어디서 온 것도 아니다.
                      *
                      * 아직 언어·통화를 실제로 바꿀 수 있는 기능이 없으므로 <b>표시만</b>
                      * 한다. cursor-pointer 를 뺀 이유: 눌러도 아무 일이 없는 것을
                      * 누를 수 있는 것처럼 보이면 안 된다.
                      */}
                    <div className="flex items-center border rounded border-slate-400 px-2 py-2 text-xs text-slate-100">
                        <GlobeAltIcon className="h-4 mr-2" />
                        <span>{t(`lang.${lang}`)}</span>
                    </div>
                    <div className="flex border rounded border-slate-400 px-2 py-2 text-xs text-slate-100">
                        <span className="text-slate-100 mr-2">$</span>
                        <span>USD</span>
                    </div>
                </div>
            </div>

            <div className="flex flex-col py-8 bg-[#131A22] items-center">
                <div className="flex flex-col items-center">
                    <ul className="flex whitespace-nowrap max-md:flex-col items-center text-xs text-slate-300 space-x-4 max-md:space-y-2">
                        {/* 갈 곳이 있는 것만 링크다. 나머지는 페이지가 생기면 추가한다. */}
                        <li className="hover:underline">
                            <Link to="/notice">{t("footer.link.notices")}</Link>
                        </li>
                        <li className="text-slate-400">{t("footer.terms")}</li>
                        <li className="text-slate-400">{t("footer.privacy")}</li>
                    </ul>
                    <h6 className="text-xs text-slate-400 mt-3">
                        {`© ${BRAND_SINCE}-${new Date().getFullYear()} ${BRAND_NAME}`}
                    </h6>
                    <p className="text-[11px] text-slate-500 mt-1">
                        {t("footer.demo_notice")}
                    </p>
                </div>
            </div>
        </footer>
    );
};

export default Footer;
