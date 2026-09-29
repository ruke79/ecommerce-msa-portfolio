import { fireEvent, render, screen } from "@testing-library/react";

import HeadingFilter, {
    PRICE_RANGES,
    splitPriceParam,
} from "components/browse/headingFilter/HeadingFilter";
import { withI18n } from "i18n/testing";

const noop = () => {};

/**
 * ⚠️ {@code searchParams} 는 훅이 아니라 <b>prop</b> 이다 — browse.jsx 가 자기 것을
 * 내려보낸다. 라우터로 감싸도 이 컴포넌트에는 닿지 않으므로 직접 넘긴다.
 */
const renderFilter = (lang = "ko", search = "") =>
    render(
        withI18n(
            <HeadingFilter
                priceHandler={noop}
                multiPriceHandler={noop}
                shippingHandler={noop}
                sortHandler={noop}
                replaceQuery={() => ({ result: "" })}
                ratingHandler={noop}
                searchParams={new URLSearchParams(search)}
            />,
            lang
        )
    );

/**
 * 목록 화면의 필터·정렬 막대.
 *
 * <p>이 컴포넌트에는 렌더 테스트가 <b>하나도 없었다</b> — {@code browse.test.jsx} 는 순수
 * 함수({@code nextPageParam} 등)만 검증하고 화면은 그리지 않는다. 문구를 사전으로 옮기면서
 * 여기서 처음 렌더해 본다.
 */
describe("목록 필터 막대", () => {
    it("한국어로 렌더된다", () => {
        renderFilter("ko");

        expect(screen.getByText("가격")).toBeInTheDocument();
        expect(screen.getByText("정렬")).toBeInTheDocument();
        expect(screen.getByText("무료 배송")).toBeInTheDocument();
    });

    /** 🔴 사전만 채우고 컴포넌트가 영어를 그리고 있으면 여기서 드러난다. */
    it("🔴 일본어로 렌더된다", () => {
        renderFilter("ja");

        expect(screen.getByText("価格")).toBeInTheDocument();
        expect(screen.getByText("並び替え")).toBeInTheDocument();
        expect(screen.getByText("送料無料")).toBeInTheDocument();
    });

    it("영어 원문이 남아 있지 않다", () => {
        renderFilter("ja");

        expect(screen.queryByText("Price:")).not.toBeInTheDocument();
        expect(screen.queryByText("Sort by:")).not.toBeInTheDocument();
        expect(screen.queryByText("Free Shipping")).not.toBeInTheDocument();
    });

    /** 정렬 이름은 번역되지만 <b>질의 값은 그대로</b>여야 한다 — 화면 언어가 API 계약을 바꾸면 안 된다. */
    it("현재 정렬을 그 언어로 보여준다", () => {
        renderFilter("ja", "sort=newest");
        expect(screen.getByText("新着順")).toBeInTheDocument();

        renderFilter("ko", "sort=newest");
        expect(screen.getByText("신상품순")).toBeInTheDocument();
    });

    it("모르는 정렬값이면 기본값으로 떨어진다 — 빈 칸이 되지 않는다", () => {
        renderFilter("ko", "sort=nonsense");
        expect(screen.getByText("추천순")).toBeInTheDocument();
    });

    /** 입력칸의 안내 문구도 번역 대상이다 — 자주 빠뜨리는 자리다. */
    it("가격 입력칸의 안내 문구도 번역된다", () => {
        renderFilter("ja");

        expect(screen.getByPlaceholderText("下限")).toBeInTheDocument();
        expect(screen.getByPlaceholderText("上限")).toBeInTheDocument();
    });
});

/**
 * 가격 범위 설정 UI.
 *
 * <p>사용자 보고: "상품 가격 범위 설정 UI도 버그가 있네." 실제로 셋이 겹쳐 있었다 —
 * 지울 수 없는 입력칸, 양수를 거부하는 상한 칸, 그리고 눌러도 어디를 골랐는지 알 수 없는
 * 가격대 버튼.
 */
describe("가격 범위 UI", () => {
    const priceInputs = () => [
        screen.getByPlaceholderText("최소"),
        screen.getByPlaceholderText("최대"),
    ];

    it("한쪽만 있는 범위를 그대로 나눈다", () => {
        expect(splitPriceParam("10_50")).toEqual(["10", "50"]);
        expect(splitPriceParam("500_")).toEqual(["500", ""]);   // 500 이상
        expect(splitPriceParam("_50")).toEqual(["", "50"]);     // 50 이하
        expect(splitPriceParam(null)).toEqual(["", ""]);
    });

    it("URL 의 가격이 입력칸에 들어온다", () => {
        renderFilter("ko", "price=10_50");
        const [min, max] = priceInputs();

        expect(min).toHaveValue(10);
        expect(max).toHaveValue(50);
    });

    /**
     * 🐛 회귀 대상: <b>가격 칸을 비울 수 없었다.</b>
     *
     * <p>`value={minPrice || priceParam?.split("_")[0]}` — 지운 칸은 빈 문자열이고 빈
     * 문자열은 falsy 라 URL 의 옛 값이 곧바로 되살아났다. 숫자를 지우면 그 자리에 방금
     * 지운 값이 다시 나타난다.
     */
    it("🔴 입력한 가격을 지울 수 있다", () => {
        renderFilter("ko", "price=10_50");
        const [min] = priceInputs();

        fireEvent.change(min, { target: { value: "" } });

        expect(min).toHaveValue(null);   // 빈 number 입력칸
    });

    /**
     * 🐛 회귀 대상: 상한 칸에 `max="0"` 이 붙어 있었다 — 위 칸의 `min="0"` 을 복사하며
     * 이름만 바뀐 것이다. "최대 가격은 0 이하" 라는 제약이라 양수를 넣는 순간 입력칸이
     * 유효하지 않은 상태가 된다.
     */
    it("🔴 상한 칸이 양수를 거부하지 않는다", () => {
        renderFilter("ko");
        const [, max] = priceInputs();

        expect(max).not.toHaveAttribute("max");
        expect(max).toHaveAttribute("min", "0");
    });

    /** 상한이 없는 칸의 최대값은 빈 문자열이다 — 0 이면 `500 ~ 0` 이라는 불가능한 범위가 된다. */
    it("🔴 '이상' 칸은 상한을 0 으로 보내지 않는다", () => {
        const open = PRICE_RANGES[PRICE_RANGES.length - 1];

        expect(open.min).toBe(500);
        expect(open.max).toBe("");
    });

    it("고른 가격대가 눌린 상태로 보인다", () => {
        renderFilter("ko", "price=10_50");

        const pressed = screen.getAllByRole("button", { pressed: true });
        expect(pressed).toHaveLength(1);
        expect(pressed[0]).toHaveAttribute("aria-label", expect.stringContaining("10"));
    });

    it("가격대를 누르면 그 범위를 그대로 넘긴다", () => {
        const calls = [];
        render(
            withI18n(
                <HeadingFilter
                    priceHandler={noop}
                    multiPriceHandler={(min, max) => calls.push([min, max])}
                    shippingHandler={noop}
                    sortHandler={noop}
                    ratingHandler={noop}
                    searchParams={new URLSearchParams("")}
                />,
                "ko"
            )
        );

        fireEvent.click(screen.getAllByRole("button", { pressed: false }).at(-1));

        expect(calls).toEqual([[500, ""]]);
    });
});

/**
 * 무료배송·별점 체크박스.
 *
 * <p>🐛 `Number(searchParams.get('shipping')) === "0"` — 숫자와 문자열을 `===` 로 비교해
 * <b>언제나 거짓</b>이었다. URL 에 필터가 걸려 있어도 체크가 켜지지 않는다. 게다가
 * `defaultChecked` 라 한 번 켠 뒤에는 Clear All 을 눌러도 체크가 남았다.
 */
describe("체크박스 필터", () => {
    it("🔴 URL 에 걸린 필터가 체크되어 보인다", () => {
        renderFilter("ko", "shipping=0&rating=4");

        expect(screen.getByRole("checkbox", { name: /무료 배송/ })).toBeChecked();
        expect(screen.getByRole("checkbox", { name: /이상/ })).toBeChecked();
    });

    it("걸리지 않은 필터는 꺼져 있다", () => {
        renderFilter("ko");

        expect(screen.getByRole("checkbox", { name: /무료 배송/ })).not.toBeChecked();
    });

    /** 켜진 것을 다시 누르면 <b>지운다</b> — 빈 객체가 filter() 에서 "이 필터 삭제" 다. */
    it("켜진 필터를 끄면 빈 값을 넘긴다", () => {
        const calls = [];
        render(
            withI18n(
                <HeadingFilter
                    priceHandler={noop}
                    multiPriceHandler={noop}
                    shippingHandler={(v) => calls.push(v)}
                    sortHandler={noop}
                    ratingHandler={noop}
                    searchParams={new URLSearchParams("shipping=0")}
                />,
                "ko"
            )
        );

        fireEvent.click(screen.getByRole("checkbox", { name: /무료 배송/ }));

        expect(calls).toEqual([{}]);
    });
});
