import { useMemo } from "react";
import { useDispatch, useSelector } from "react-redux";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ChevronRightIcon } from "@heroicons/react/24/outline";
import { XMarkIcon } from "@heroicons/react/24/solid";

import { closeMenu, selectMenu } from "../../redux/MenuSlice";
import { useAuthContext } from "../../store/AuthContext";
import { getCategories, getAllSubcategories } from "util/api";
import { displayName } from "util/userDisplay";
import Avatar from "components/common/Avatar";
import { useProfile } from "hook/useProfile";
import { useTranslation } from "i18n/I18nProvider";

/**
 * 좌측 메뉴(햄버거).
 *
 * <p><b>예전에는 101줄 전체에 데이터를 읽는 코드가 한 줄도 없었다.</b> 목록이 JSX 에
 * 그대로 박혀 있었고 — "Amazon Music", "Amazon Appstore", "Electronics", "Smart Home",
 * "Gift Cards", "Amazon Live" — 이 사이트의 실제 카테고리(books, fashion 등)와는 아무
 * 관계가 없었다. `<li>` 에 `onClick` 도 `<Link>` 도 없어서 <b>눌러도 아무 일이
 * 일어나지 않았다.</b>
 *
 * <p>인사말도 정적 문자열이었다 — 로그인해도 계속 "Hello, sign in". 같은 화면의 헤더
 * (`Account.jsx`)는 처음부터 인증 상태를 보고 있었는데 여기만 빠져 있었다.
 *
 * <p>실제 카테고리를 읽어 그린다. 각 항목은 `/browse?category=<id>` 로 간다 —
 * browse 의 categoryHandler 가 그 파라미터를 이미 처리한다.
 */
const MenuSideBar = () => {
    const { t } = useTranslation();
    const dispatch = useDispatch();
    const menu = useSelector(selectMenu);
    const { user, isAuthenticated } = useAuthContext();

    // 로그인했을 때만 받는다 — 비로그인 상태에서 부르면 401 이다.
    // Layout(프로필 탭들)과 같은 쿼리 키라, 이미 받아 뒀으면 요청이 나가지 않는다.
    const { profile } = useProfile({ enabled: Boolean(isAuthenticated) });
    const me = { ...(user ?? {}), ...(profile ?? {}) };

    /**
     * 카테고리는 거의 바뀌지 않는다. 그리고 <b>메뉴를 처음 열 때</b> 받는다 —
     * 첫 화면 로딩에 요청을 하나 더 얹을 이유가 없다(`enabled: menu`).
     *
     * <p>`throwOnError` 를 끈다. 전역 설정은 true 라(util/api.js) 카테고리를 못 받으면
     * 예외가 ErrorBoundary 까지 올라가 <b>보고 있던 화면 전체가 사라진다.</b> 메뉴가
     * 비는 것과 사이트가 죽는 것은 다르다.
     */
    const { data, isPending, isError } = useQuery({
        queryKey: ["menu-categories"],
        queryFn: async () => {
            const [categories, subcategories] = await Promise.all([
                getCategories(),
                getAllSubcategories(),
            ]);
            return { categories: categories ?? [], subcategories: subcategories ?? [] };
        },
        enabled: Boolean(menu),
        staleTime: 10 * 60 * 1000,
        throwOnError: false,
    });

    /**
     * 카테고리별로 하위 카테고리를 묶는다.
     *
     * <p>`/allsubcategories` 는 하위 카테고리마다 부모를 <b>이름</b>으로 준다
     * (`SubCategoryResponse(id, name, categoryName)`). id 가 아니라 이름이므로 이름으로
     * 묶는다 — 여기서 id 로 묶으려 하면 조용히 전부 빈 목록이 된다.
     */
    const grouped = useMemo(() => {
        const categories = data?.categories ?? [];
        const subcategories = data?.subcategories ?? [];

        return categories.map((category) => ({
            ...category,
            subs: subcategories.filter((sub) => sub.category === category.name),
        }));
    }, [data]);

    const close = () => dispatch(closeMenu());

    return (
        <>
            <div
                className={`flex flex-col fixed bg-white shadow-xl w-72 md:w-96 h-screen top-0 left-0 z-50
               ${menu ? "block" : "hidden"}
            `}
            >
                <div className="relative flex flex-col h-full">
                    <div
                        className="absolute top-3 cursor-pointer -right-12 hover:scale-110 transition"
                        onClick={close}
                    >
                        <XMarkIcon className="h-9 text-white drop-shadow-md" />
                    </div>

                    {/* 인사말. 예전에는 "Hello, sign in" 이 박혀 있어 로그인해도 그대로였다. */}
                    <div className="flex items-center bg-amazon-blue_light text-white px-6 py-4 shrink-0">
                        {isAuthenticated ? (
                            <>
                                <Avatar user={me} size={36} />
                                <Link
                                    to="/profile"
                                    onClick={close}
                                    className="ml-3 text-lg font-bold hover:underline"
                                >
                                    {t("menu.user_name", { name: displayName(me) })}
                                </Link>
                            </>
                        ) : (
                            <Link
                                to="/signin"
                                onClick={close}
                                className="flex items-center hover:underline"
                            >
                                <Avatar user={null} size={36} />
                                <b className="text-lg font-bold ml-3">{t("menu.signin_prompt")}</b>
                            </Link>
                        )}
                    </div>

                    <div className="menu-sidebar flex flex-col py-2 overflow-y-auto flex-1">
                        <h3>{t("menu.categories")}</h3>

                        {isPending && (
                            <p className="px-5 py-3 text-sm text-slate-500">
                                {t("menu.categories_loading")}
                            </p>
                        )}

                        {isError && (
                            <p className="px-5 py-3 text-sm text-red-500">
                                {t("menu.categories_error")}
                            </p>
                        )}

                        {!isPending && !isError && grouped.length === 0 && (
                            <p className="px-5 py-3 text-sm text-slate-500">
                                {t("menu.categories_empty")}
                            </p>
                        )}

                        {grouped.map((category) => (
                            <ul className="border-b pb-2" key={category.id}>
                                <li className="group">
                                    <Link
                                        to={`/browse?category=${category.id}`}
                                        onClick={close}
                                        className="flex items-center justify-between w-full"
                                    >
                                        <span className="font-semibold">{category.name}</span>
                                        <ChevronRightIcon className="w-4 h-4 group-hover:text-gray-800" />
                                    </Link>
                                </li>

                                {category.subs.map((sub) => (
                                    <li className="group" key={sub.id}>
                                        <Link
                                            to={`/browse?category=${category.id}`}
                                            onClick={close}
                                            className="flex items-center justify-between w-full pl-3 text-sm text-slate-600"
                                        >
                                            <span>{sub.name}</span>
                                            <ChevronRightIcon className="w-3 h-3 group-hover:text-gray-800" />
                                        </Link>
                                    </li>
                                ))}
                            </ul>
                        ))}

                        <h3>{t("menu.shortcuts")}</h3>
                        <ul className="border-b pb-2">
                            <li className="group">
                                <Link to="/browse" onClick={close} className="flex items-center justify-between w-full">
                                    <span>{t("footer.link.all_products")}</span>
                                    <ChevronRightIcon className="w-4 h-4 group-hover:text-gray-800" />
                                </Link>
                            </li>
                            <li className="group">
                                <Link to="/event" onClick={close} className="flex items-center justify-between w-full">
                                    <span>{t("footer.link.events")}</span>
                                    <ChevronRightIcon className="w-4 h-4 group-hover:text-gray-800" />
                                </Link>
                            </li>
                            <li className="group">
                                <Link to="/notice" onClick={close} className="flex items-center justify-between w-full">
                                    <span>{t("footer.link.notices")}</span>
                                    <ChevronRightIcon className="w-4 h-4 group-hover:text-gray-800" />
                                </Link>
                            </li>
                            {isAuthenticated && (
                                <li className="group">
                                    <Link to="/profile/orders" onClick={close} className="flex items-center justify-between w-full">
                                        <span>{t("footer.link.orders")}</span>
                                        <ChevronRightIcon className="w-4 h-4 group-hover:text-gray-800" />
                                    </Link>
                                </li>
                            )}
                        </ul>
                    </div>
                </div>
            </div>

            {menu && (
                <div
                    onClick={close}
                    className="fixed bg-zinc-900/[0.85] w-full  h-screen z-40 top-0 right-0"
                ></div>
            )}
        </>
    );
};

export default MenuSideBar;
