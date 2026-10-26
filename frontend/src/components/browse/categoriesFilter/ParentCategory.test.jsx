import { fireEvent, render, screen } from "@testing-library/react";

import ParentCategory from "components/browse/categoriesFilter/ParentCategory";

/**
 * 🐛 <b>하위 카테고리를 눌러도 아무 일도 일어나지 않았다.</b>
 *
 * <p>목록은 그려지는데 그 {@code <h5>} 에 {@code onClick} 이 없었다 — 유일한 핸들러가
 * 주석 처리된 채였고, 그마저도 필터가 아니라 접기/펴기였다. URL 파라미터도, 서버의
 * 검색 조건도 없었다. 즉 <b>기능이 존재한 적이 없다.</b>
 *
 * <p>그런데 {@code cursor-pointer} 와 hover 스타일은 남아 있어서 <b>눌러도 되는 것처럼
 * 보였다.</b> 이런 종류는 테스트가 없으면 다시 조용히 사라진다 — 렌더는 멀쩡하고
 * 콘솔에도 아무것도 안 뜨기 때문이다.
 */
describe("카테고리 필터 — 하위 카테고리", () => {
  const category = { id: "1", name: "신발" };
  const subCategories = [
    { id: "10", name: "러닝", parent: { id: "1" } },
    { id: "11", name: "샌들", parent: { id: "1" } },
    // 다른 상위에 속한 것 — 여기 나오면 안 된다.
    { id: "20", name: "티셔츠", parent: { id: "2" } },
  ];

  // 실제 replaceQuery 의 계약만 흉내 낸다: { result, active }.
  const replaceQuery = (name, value) => ({ result: String(value), active: false });

  const draw = (props = {}) =>
    render(
      <ParentCategory
        category={category}
        subCategories={subCategories}
        categoryHandler={jest.fn()}
        subCategoryHandler={jest.fn()}
        replaceQuery={replaceQuery}
        {...props}
      />
    );

  /** 목록은 상위를 눌러야 펼쳐진다(show 초기값 false). */
  const openAndGet = (name, props) => {
    draw(props);
    fireEvent.click(screen.getByText(category.name));
    return screen.getByText(name);
  };

  /** 🔴 이 파일의 핵심 가드. */
  it("하위 카테고리를 누르면 그 id 로 필터를 건다", () => {
    const subCategoryHandler = jest.fn();

    fireEvent.click(openAndGet("러닝", { subCategoryHandler }));

    expect(subCategoryHandler).toHaveBeenCalledWith("10", "1");   // 하위 id 와 그 상위 id — 다른 상위가 골라져 있으면 상위도 바꾼다
  });

  it("상위 카테고리를 누르면 그 id 로 필터를 건다", () => {
    const categoryHandler = jest.fn();
    draw({ categoryHandler });

    fireEvent.click(screen.getByText(category.name));

    expect(categoryHandler).toHaveBeenCalledWith("1");
  });

  /** 부모가 다른 하위 카테고리가 섞이면 고를 수 없는 조합이 만들어진다. */
  it("자기 상위의 하위 카테고리만 보여 준다", () => {
    draw();
    fireEvent.click(screen.getByText(category.name));

    expect(screen.getByText("러닝")).toBeInTheDocument();
    expect(screen.getByText("샌들")).toBeInTheDocument();
    expect(screen.queryByText("티셔츠")).not.toBeInTheDocument();
  });

  /** 선택된 하위 카테고리는 눈에 띄어야 한다 — 안 그러면 무엇이 걸렸는지 알 수 없다. */
  it("선택된 하위 카테고리를 표시한다", () => {
    const activeQuery = (name, value) => ({
      result: String(value),
      active: name === "subCategory" && String(value) === "10",
    });

    draw({ replaceQuery: activeQuery });
    fireEvent.click(screen.getByText(category.name));

    expect(screen.getByText("러닝").closest("h5").className).toContain("text-red-500");
    expect(screen.getByText("샌들").closest("h5").className).not.toContain("text-red-500");
  });
});
