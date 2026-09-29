import { compareArrays, filterArray, removeDublicates, randomize } from "util/array_utils";

/**
 * 🐛 회귀 대상: `randomize` 가 `array.sort(() => 0.5 - Math.random())` 이었다.
 *
 * 두 가지가 틀렸다 — 원본 배열을 제자리에서 뒤섞었고(로더가 준 데이터나 redux 상태까지
 * 순서가 바뀐다), 무작위 비교자는 정렬 알고리즘의 일관성 전제를 깨서 특정 순열이 훨씬 자주
 * 나온다. 지금은 복사본에 Fisher–Yates 를 돌린다.
 */
describe("randomize", () => {
  it("does not mutate the caller's array", () => {
    const source = [1, 2, 3, 4, 5];
    const snapshot = [...source];

    randomize(source);

    // 이것이 핵심 회귀 검사다. sort 기반 구현에서는 실패한다.
    expect(source).toEqual(snapshot);
  });

  it("returns a new array containing exactly the same elements", () => {
    const source = ["a", "b", "c", "d"];
    const shuffled = randomize(source);

    expect(shuffled).not.toBe(source);
    expect([...shuffled].sort()).toEqual([...source].sort());
  });

  it("handles empty and single-element arrays", () => {
    expect(randomize([])).toEqual([]);
    expect(randomize([7])).toEqual([7]);
  });

  it("reaches more than one arrangement over many runs", () => {
    // 편향 자체를 통계적으로 증명하려는 게 아니다. "사실상 안 섞인다"는 명백한 고장만
    // 잡는다 — 4원소 배열을 200번 섞었는데 순열이 하나뿐이면 그건 셔플이 아니다.
    const seen = new Set();
    for (let i = 0; i < 200; i++) {
      seen.add(randomize([1, 2, 3, 4]).join(","));
    }
    expect(seen.size).toBeGreaterThan(1);
  });
});

describe("compareArrays", () => {
  it("is true for the same objects in a different order", () => {
    expect(compareArrays([{ a: 1 }, { b: 2 }], [{ b: 2 }, { a: 1 }])).toBe(true);
  });

  it("is false when lengths differ", () => {
    expect(compareArrays([{ a: 1 }], [{ a: 1 }, { b: 2 }])).toBe(false);
  });

  it("is false when contents differ", () => {
    expect(compareArrays([{ a: 1 }], [{ a: 2 }])).toBe(false);
  });

  it("ignores key ordering within an object", () => {
    expect(compareArrays([{ a: 1, b: 2 }], [{ b: 2, a: 1 }])).toBe(true);
  });
});

describe("filterArray", () => {
  it("collects values whose name matches", () => {
    const details = [
      { name: "Style", value: "Casual" },
      { name: "Material", value: "Cotton" },
      { name: "Style", value: "Formal" },
    ];
    expect(filterArray(details, "Style")).toEqual(["Casual", "Formal"]);
  });

  it("returns an empty array when nothing matches", () => {
    expect(filterArray([{ name: "Style", value: "Casual" }], "Color")).toEqual([]);
  });
});

describe("removeDublicates", () => {
  it("keeps only the first occurrence of each value", () => {
    expect(removeDublicates(["a", "b", "a", "c", "b"])).toEqual(["a", "b", "c"]);
  });
});
