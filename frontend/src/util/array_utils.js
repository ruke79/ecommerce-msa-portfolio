export const compareArrays = (array1, array2) => {
    if (array1.length !== array2.length) return false;
    const newArr = (object) =>
        JSON.stringify(
            Object.keys(object)
                .sort()
                .map((key) => [key, object[key]])
        );
    // 파라미터를 재할당하지 않는다. 이름은 배열인데 값은 Set 이 되어 있으면 나중에 이
    // 함수를 고치는 사람이 반드시 헷갈린다.
    const seen = new Set(array1.map(newArr));

    return array2.every((object) => seen.has(newArr(object)));
};

export const filterArray = (array, property) => {
    return array.filter((item) => item.name === property).map((s) => s.value);
};

export const removeDublicates = (array) => {
    return [...new Set(array)];
}

/**
 * 배열을 무작위로 섞는다.
 *
 * <p>예전 구현은 `array.sort(() => 0.5 - Math.random())` 이었다. 두 가지가 문제다.
 *
 * <p><b>1. 호출자의 배열을 망가뜨린다.</b> `Array.prototype.sort` 는 제자리 정렬이라 인자로
 * 받은 배열 자체가 바뀐다. 화면에 뿌릴 목록을 섞으려고 불렀는데 원본(로더가 준 데이터,
 * redux 상태)까지 순서가 바뀐다.
 *
 * <p><b>2. 고르게 섞이지 않는다.</b> 비교 함수는 일관성이 있어야 한다 — 같은 두 원소를
 * 비교하면 항상 같은 답이 나와야 한다. 무작위 비교자는 그 규칙을 깨서, 엔진의 정렬
 * 알고리즘이 어떤 순서를 다른 순서보다 훨씬 자주 만들어 낸다. 편향 없는 셔플은
 * Fisher–Yates 다.
 */
export const randomize = (array) => {
    const copy = [...array];
    for (let i = copy.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [copy[i], copy[j]] = [copy[j], copy[i]];
    }
    return copy;
}