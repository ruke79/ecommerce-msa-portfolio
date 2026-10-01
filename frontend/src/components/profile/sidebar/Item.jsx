import { MinusIcon, PlusIcon } from "@heroicons/react/24/solid";
import { useTranslation } from "i18n/I18nProvider";
import React, { useState } from "react";
import { Link  } from "react-router-dom";
import { ArrowLongRightIcon } from "@heroicons/react/24/solid";
import slugify from "slugify";
import { useAuthContext } from "store/AuthContext";
import { useLogout } from "hook/hooks";
import { useSearchParams } from "react-router-dom";

const Item = ({ item, visible, index }) => {
    const { t } = useTranslation();
    const { token } = useAuthContext();

    const [show, setShow] = useState(visible);


    const [ searchParams, setSearchParams] = useSearchParams();
    const qParam = searchParams.get('q');

    // 🐛 예전에는 화면의 로그인 표시만 지웠다(logout()) — 서버의 리프레시 토큰과 쿠키가 남아, 새로고침하면 다시 로그인돼 있었고
    //    다른 탭은 계속 로그인 상태였다. 헤더의 로그아웃과 같은 경로(useLogout — 서버 로그아웃 → 화면 · 다른 탭 정리)를 쓴다.
    // [JA] 以前は画面のログイン表示だけを消していた — サーバーのリフレッシュトークンと Cookie が残り、再読み込みで再ログイン状態になった。
    const { logout: signOut } = useLogout();
    const handleLogout = () => {
        signOut();
      };
      
    
    return (
        <li className="py-2 cursor-pointer transition-all duration-300 text-amazon-blue_light">
            {item.heading === "Sign Out" ? (
                <b className="hover:text-[#fda22c]" onClick={() => handleLogout()}>
                    {t("account.signout")}
                </b>
            ) : (
                <b
                    className=" hover:text-[#fda22c] flex items-center justify-between transition-all"
                    onClick={() => setShow((prev) => !prev)}
                >
                    <span className={`${show && "text-[#fda22c]"}`}>
                        {item.headingKey ? t(item.headingKey) : item.heading}
                    </span>
                    {show ? (
                        <MinusIcon className="w-5 h-5 fill-[#fda22c]" />
                    ) : (
                        <PlusIcon className="w-5 h-5 mr-2" />
                    )}
                </b>
            )}
            {show && (
                <ul className="ml-2">
                    {/*
                      * 🐛 여기에는 문제가 둘 있었다.
                      *   ① JSX 안에서 {console.log(link)} 를 호출했다 — 렌더마다 사이드바
                      *      링크가 전부 콘솔에 찍혔다
                      *   ② 감싸는 프래그먼트 <> 에 key 가 없었다. key 는 map 이 만드는
                      *      **가장 바깥 요소**에 있어야 하는데 안쪽 <li> 에 붙어 있었다.
                      *      React 는 "Each child in a list should have a unique key" 경고를
                      *      냈고, 목록이 바뀔 때 엉뚱한 항목을 재사용할 수 있었다.
                      * 프래그먼트는 <li> 하나만 감싸고 있어 존재할 이유가 없다 — 없앤다.
                      */}
                    {item.links.map((link, i) => (
                        <React.Fragment key={link.link ?? i}>
                            {link.link.startsWith("/profile/orders") ? (
                                <li
                                    className={`${
                                        (qParam?.split("__")[0] || "") === slugify(link.name, {lower: true}) ? "text-black font-semibold" : "text-slate-600"
                                    } flex items-center  py-1 hover:text-[#fda22c] hover:font-semibold transition-all`}
                                >
                                    <ArrowLongRightIcon className="w-5 h-5 mr-2" />
                                    <Link
                                        to={`${process.env.PUBLIC_URL}${
                                            link.link
                                        }?tab=${index}&q=${slugify(link.name, {
                                            lower: true,
                                        })}__${link.filter}`}
                                    >
                                        {link.key ? t(link.key) : link.name}
                                    </Link>
                                </li>
                            ) :
                             (
                                <li
                                    className={`${
                                        (qParam || "") ===
                                        slugify(link.name, { lower: true })
                                            ? "text-black font-semibold"
                                            : "text-slate-600"
                                    } flex items-center  py-1 hover:text-[#fda22c] hover:font-semibold transition-all`}
                                >
                                    <ArrowLongRightIcon className="w-5 h-5 mr-2" />
                                    <Link 
                                        to={`${
                                            link.link
                                        }?tab=${index}&q=${slugify(link.name, {
                                            lower: true,
                                        })}`}
                                    >
                                        {link.key ? t(link.key) : link.name}
                                    </Link>
                                </li>
                            )}
                        </React.Fragment>
                    ))}
                </ul>
            )}
        </li>
    );
};

export default Item;
