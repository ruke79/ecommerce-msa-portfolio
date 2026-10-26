import {
    ChevronRightIcon,
    EllipsisHorizontalIcon,
    MinusIcon,
    PlusIcon,
} from "@heroicons/react/24/solid";
import { useState } from "react";

/**
 * 상위 카테고리 한 줄과 그 아래 하위 카테고리 목록.
 *
 * <h3>🐛 하위 카테고리는 <b>눌러도 아무 일도 일어나지 않았다</b></h3>
 *
 * <p>목록은 그려지고 있었다 — 서버가 {@code subCategories} 를 함께 내려 주고 여기서
 * 이름을 찍는다. 그런데 그 {@code <h5>} 에는 <b>{@code onClick} 이 없었다.</b> 유일한
 * 핸들러가 주석 처리된 채였다:
 *
 * <pre>{@code
 * <h5 key={i}
 *     // onClick={() => setShow((prev) => !prev)}
 *     className="cursor-pointer ...">
 * }</pre>
 *
 * <p>게다가 그 주석의 내용마저 <b>필터가 아니라 접기/펴기</b>다. 즉 하위 카테고리로 거르는
 * 기능은 "동작하지 않았다"가 아니라 <b>프론트·URL·서버 어디에도 존재한 적이 없다</b> —
 * {@code SearchParamsRequest} 에 필드도, 검색 조건도 없었다.
 *
 * <p>{@code cursor-pointer} 와 hover 스타일만 남아 있어서 <b>눌러도 되는 것처럼 보였다.</b>
 * 그게 "반응이 없다"는 인상을 만든 부분이다.
 */
const ParentCategory = ({ category, subCategories, categoryHandler, subCategoryHandler, replaceQuery }) => {
    const [show, setShow] = useState(false);
    // const [activeCat, setActiveCat] = useState(false);
    const check = replaceQuery("category",category.id);

    const selectSubCategory = subCategories.filter(
        (c) => c.parent?.id === category.id
    );

    const showSub = (e) => {
        e.stopPropagation();
        setShow((prev) => !prev);
    };

    return (
        <div>
            <h4
                onClick={() => {
                    categoryHandler(category.id);
                    setShow((prev) => !prev)
                }}
                className={`${
                    check.active && "text-red-500"
                } cursor-pointer my-2 flex items-center justify-between font-semibold`}
            >
                <span className="flex items-center">
                    {check.active ? (
                        <ChevronRightIcon className="w-4 h-4 mr-2" />
                    ) : (
                        <EllipsisHorizontalIcon className="w-4 h-4 mr-2" />
                    )}
                    {category.name}
                </span>
                {selectSubCategory.length > 0 && (
                    <span>
                        {show ? (
                            <MinusIcon
                                className="w-4 h-4"
                                onClick={(e) => showSub(e)}
                            />
                        ) : (
                            <PlusIcon
                                className="w-4 h-4"
                                onClick={(e) => showSub(e)}
                            />
                        )}
                    </span>
                )}
            </h4>
            {show && subCategories.length > 0 && (
                <div className="my-1 ml-5">
                    {selectSubCategory.map((sc, i) => {
                        const subCheck = replaceQuery("subCategory", sc.id);
                        return (
                            <h5
                                key={sc.id ?? i}
                                onClick={() => subCategoryHandler(sc.id, category.id)}
                                className={`${
                                    subCheck.active ? "font-semibold text-red-500" : ""
                                } cursor-pointer flex items-center hover:font-semibold hover:text-yellow-500`}
                            >
                                <EllipsisHorizontalIcon className="w-4 h-4 mr-2" />
                                <span>{sc.name}</span>
                            </h5>
                        );
                    })}
                </div>
            )}
        </div>
    );
};

export default ParentCategory;
