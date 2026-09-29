/**
 * 객체 배열을 여러 키로 정렬한다.
 *
 * <p>예전에는 이 파일이 `Array.prototype.keySort = function (...)` 로 <b>내장 프로토타입을
 * 직접 고쳤다.</b> 그러면:
 *
 * <ul>
 *   <li>이 모듈을 import 하는 것만으로 애플리케이션의 <b>모든 배열</b>이 바뀐다. 임포트의
 *       부작용이 전역이라, 어디서 왜 생긴 메서드인지 추적하기 어렵다.</li>
 *   <li>열거 가능한 프로퍼티로 추가되므로 `for (const k in someArray)` 가 인덱스와 함께
 *       "keySort" 까지 돌려준다. 이런 루프가 프로젝트 안에 하나라도 있으면 조용히 깨진다.</li>
 *   <li>같은 이름을 쓰는 다른 라이브러리나 미래의 표준 메서드와 충돌한다.</li>
 * </ul>
 *
 * <p>그리고 `export default Array.prototype.keySort` 라는 기본 내보내기는 <b>바인딩되지 않은
 * 함수</b>여서, import 해서 그냥 호출하면 `this` 가 undefined 라 즉시 터진다. 실제로 이
 * 모듈을 import 하는 곳은 한 군데도 없었다 — 아무도 쓸 수 없는 코드였다.
 *
 * <p>이제 배열을 인자로 받아 <b>새 배열</b>을 돌려주는 평범한 함수다.
 *
 * @param array 정렬할 배열(원본은 건드리지 않는다)
 * @param keys  {키: 'asc' | 'desc' | 'skip'} 형태의 정렬 기준. 비우면 값 자체로 정렬한다.
 */
export function keySort(array, keys) {
    const items = [...array];

    const direction = (value) => {
        if (value === 'desc' || value === -1) return -1;
        if (value === 'skip' || value === 0) return 0;
        return 1;
    };

    const compare = (a, b, d = 1) => {
        if (a === b) return 0;
        return a > b ? d : -d;
    };

    const entries = Object.entries(keys || {}).map(([key, value]) => [key, direction(value)]);

    if (entries.length === 0) {
        return items.sort((a, b) => compare(a, b));
    }

    return items.sort((a, b) => {
        for (const [key, dir] of entries) {
            const result = compare(a[key], b[key], dir);
            if (result !== 0) return result;
        }
        return 0;
    });
}

export default keySort;
