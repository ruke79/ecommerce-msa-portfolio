/**
 * 가격 계산.
 *
 * <p>페이지 컴포넌트(pages/Product.jsx) 안에 있던 것을 여기로 옮겼다. 순수 함수가 화면
 * 모듈에 묶여 있으면 그걸 검증하려고 Header → AuthContext → axios → ProductPage → Swiper
 * 까지 전부 끌고 와야 한다. 계산 규칙은 화면과 독립적이므로 독립적으로 두는 것이 맞다.
 *
 * <p>[JA] 価格計算。ページコンポーネント(pages/Product.jsx)の中にあったものをここへ移した。純粋関数が画面モジュールに縛られていると、
 * それを検証するために Header → AuthContext → axios → ProductPage → Swiper まですべて引き込む必要がある。計算規則は画面と独立なので独立させるのが正しい。
 */

/**
 * 할인가를 계산한다. {@code discountPercent} 는 <b>퍼센트</b>다(시드 데이터가 5~70 을 넣는다).
 *
 * <p>🐛 예전 식은 {@code price - price / discount} 였다. "정가에서 정가를 할인율로 나눈 값을
 * 뺀다"는 뜻이라 아무 의미가 없고, 결과적으로 <b>할인율이 클수록 할인 폭이 작아졌다</b>:
 * 10,000원에 50% 할인이면 9,800원(=2% 할인), 70% 할인이면 9,857원(=1.4% 할인).
 *
 * <p>[JA] 割引価格を計算する。{@code discountPercent} は<b>パーセント</b>である(シードデータが 5〜70 を入れる)。
 * 🐛 以前の式は {@code price - price / discount} だった。「定価から定価を割引率で割った値を引く」という意味で何の意味もなく、
 * 結果として<b>割引率が大きいほど割引幅が小さくなった</b>: 10,000ウォンの 50% 割引で 9,800ウォン(=2% 割引)、70% 割引で 9,857ウォン(=1.4% 割引)。
 */
export function discounted(price, discountPercent) {
    const p = Number(price) || 0;
    const d = Number(discountPercent) || 0;
    if (d <= 0) return p;
    // 데이터 오류로 100 을 넘는 값이 들어와도 가격이 음수가 되지 않게 한다.
    // [JA] データ誤りで 100 を超える値が入っても価格が負にならないようにする。
    const capped = Math.min(d, 100);
    // 🔴 서버와 **같은 방법으로** 센다 — catalog 의 ProductService.discounted 는
    //    price × (100 − d) / 100 을 소수 둘째 자리에서 HALF_UP 으로 자른다. 그 값이 장바구니에 담기고 청구된다.
    //    예전 식(p × (1 − d/100) 의 부동소수)은 드물게 1센트가 어긋났다 — 1 − 0.07 = 0.9299999999999999.
    //    센트 정수로 바꿔 계산하면 두 쪽이 늘 같다.
    // [JA] 🔴 サーバーと**同じ方法で**数える — catalog の ProductService.discounted は price × (100 − d) / 100 を小数第二位で HALF_UP で切る。その値がカートに入り請求される。
    //      以前の式(p × (1 − d/100) の浮動小数)はまれに 1セントずれた — 1 − 0.07 = 0.9299999999999999。セントの整数に直して計算すれば両側が常に一致する。
    const cents = Math.round(p * 100);
    return Math.floor((cents * (100 - capped) + 50) / 100) / 100;
}

/**
 * 한 색상(sku)의 <b>할인이 반영된</b> 가격 범위 — 목록 카드·상품 화면·가격 정렬이 모두 이것을 본다.
 *
 * <p>🐛 예전에는 목록 카드(검색·홈·기획전)가 사이즈의 <b>정가</b>를, 상품 화면이 <b>할인가</b>를 보여 줬다. 같은
 * 상품이 검색에서는 $100, 들어가면 $70 이었다 — 카드에는 "-30%" 배지까지 붙은 채로. 서버는 두 화면에 같은 값
 * (정가 + 기획전까지 합친 할인율)을 보낸다. 할인을 어디서 적용하느냐가 화면마다 달랐다.
 *
 * @return {{ min: number, max: number, minBefore: number, maxBefore: number, discount: number } | null}
 *
 * <p>[JA] ある色(sku)の<b>割引を反映した</b>価格範囲 — 一覧カード · 商品画面 · 価格ソートがすべてこれを見る。
 * 🐛 以前は一覧カード(検索 · ホーム · 企画展)がサイズの<b>定価</b>を、商品画面が<b>割引価格</b>を見せていた。同じ商品が検索では $100、入ると $70 だった —
 * カードに "-30%" バッジまで付いたまま。サーバーは両画面に同じ値(定価 + 企画展まで合わせた割引率)を送る。割引をどこで適用するかが画面ごとに違っていた。
 */
export function skuPriceRange(sku) {
    const sizes = Array.isArray(sku?.sizes) ? sku.sizes : [];
    const before = sizes.map((s) => Number(s?.price)).filter((n) => Number.isFinite(n));
    if (before.length === 0) return null;
    const discount = Number(sku?.discount) || 0;
    const minBefore = Math.min(...before);
    const maxBefore = Math.max(...before);
    return {
        min: discounted(minBefore, discount),
        max: discounted(maxBefore, discount),
        minBefore,
        maxBefore,
        discount,
    };
}

/**
 * 가격대(하한·상한, 비면 그쪽은 열림)에 <b>화면 가격</b>이 드는 사이즈가 있는 첫 스타일(SKU)의 위치. 없으면 -1.
 *
 * <p>🐛 검색의 가격 필터는 상품의 <b>어느 스타일·사이즈든</b> 범위에 들면 그 상품을 준다(catalog SearchService.skuFilterIds). 그런데
 * 목록 카드는 늘 첫 스타일의 가격을 보여 줘서, "$50~$100" 을 골랐는데 "$15~$29" 카드가 줄지어 나왔다 — 둘째·셋째 스타일이 맞은
 * 것이다. 카드가 이것으로 맞은 스타일부터 보여 준다. 화면 가격은 서버와 같은 식이다({@link discounted}).
 *
 * <p>[JA] 価格帯(下限 · 上限、空ならその側は開いている)に<b>画面価格</b>が入るサイズがある最初のスタイル(SKU)の位置。なければ -1。
 * 🐛 検索の価格フィルターは商品の<b>どのスタイル · サイズでも</b>範囲に入ればその商品を返す(catalog SearchService.skuFilterIds)。ところが
 * 一覧カードは常に最初のスタイルの価格を見せるので、"$50~$100" を選んだのに "$15~$29" のカードが並んだ — 2 番目 · 3 番目のスタイルが一致したのだ。
 * カードはこれで一致したスタイルから見せる。画面価格はサーバーと同じ式である({@link discounted})。
 */
export function skuIndexInPriceRange(skus, low, high) {
    const lo = low === "" || low == null ? null : Number(low);
    const hi = high === "" || high == null ? null : Number(high);
    if ((lo === null || !Number.isFinite(lo)) && (hi === null || !Number.isFinite(hi))) return -1;
    const list = Array.isArray(skus) ? skus : [];
    return list.findIndex((sku) => (Array.isArray(sku?.sizes) ? sku.sizes : []).some((size) => {
        const before = Number(size?.price);
        if (!Number.isFinite(before)) return false;
        const shown = discounted(before, sku?.discount);
        return (lo === null || !Number.isFinite(lo) || shown >= lo) && (hi === null || !Number.isFinite(hi) || shown <= hi);
    }));
}

/**
 * 장바구니 총액을 <b>상품 금액과 배송비로 나눈다.</b>
 *
 * <h3>🔴 왜 이게 필요했나</h3>
 *
 * <p>cart-service 는 `price×qty + shipping` 으로 <b>합산한 총액 하나</b>만 보여 줬다.
 * 그래서 결제 화면은 "Total: 13000$" 한 줄이 전부였고, 사용자는 <b>자기가 배송비를
 * 얼마 내는지 주문하는 순간까지 알 수 없었다.</b>
 *
 * <p>서버가 항목마다 `shipping` 을 실어 보내게 바꿨고(CartProductDto), 여기서 다시
 * 나눈다. 계산은 서버의 `computeCartTotal` 과 같은 방식이어야 한다 —
 * <b>배송비는 수량과 무관한 줄 단위 금액</b>이다. 다르게 더하면 상품 금액 + 배송비 ≠
 * 총액이 되어 화면의 세 줄이 서로 맞지 않는다.
 *
 * <p>옛 장바구니 캐시에는 `shipping` 이 없어 `undefined` 로 온다. 그때는 0 으로 본다 —
 * 배송비를 <b>없는 것으로</b> 보는 쪽이 안전하다. 반대로 추측하면 상품 금액이 실제보다
 * 작게 표시된다.
 *
 * <p>[JA] カート合計を<b>商品金額と送料に分ける。</b>
 * 🔴 なぜ必要だったか: cart-service は `price×qty + shipping` で<b>合算した合計一つ</b>だけを見せていた。そのため決済画面は "Total: 13000$" の一行だけで、
 * ユーザーは<b>自分が送料をいくら払うのか注文する瞬間まで分からなかった。</b>
 * サーバーが項目ごとに `shipping` を載せるように変え(CartProductDto)、ここで再び分ける。計算はサーバーの `computeCartTotal` と同じ方式でなければならない —
 * <b>送料は数量と無関係な行単位の金額</b>である。違う足し方をすると商品金額 + 送料 ≠ 合計となり、画面の三行が互いに合わない。
 * 古いカートキャッシュには `shipping` がなく `undefined` で来る。そのときは 0 と見なす — 送料を<b>ないものと</b>見る方が安全だ。逆に推測すると商品金額が実際より小さく表示される。
 */
export function splitCartTotal(cart) {
    const products = Array.isArray(cart?.products) ? cart.products : [];

    const shipping = roundMoney(
        products.reduce((sum, p) => sum + (Number(p?.shipping) || 0), 0));

    // 총액은 서버가 계산한 값을 그대로 쓴다. 여기서 price×qty 를 다시 더하면 서버와
    // 어긋날 수 있고(반올림·할인 합성), 사용자가 결제하는 금액은 서버 값이다.
    // [JA] 合計はサーバーが計算した値をそのまま使う。ここで price×qty を足し直すとサーバーとずれることがあり(丸め · 割引の合成)、ユーザーが払う金額はサーバーの値である。
    const total = roundMoney(Number(cart?.cartTotal) || 0);

    return {
        // 🐛 예전에는 `total - shipping` 을 그대로 내보냈다. 둘 다 부동소수이므로
        //    13.8 - 4.9 가 8.900000000000002 가 된다 — 결제 화면에 그 숫자가 통째로
        //    찍혔다. **돈을 다루는 값은 계산 직후에 센트 단위로 고정한다.**
        //
        //    표시할 때만 반올림하지 않는 이유: 세 줄을 각각 반올림하면
        //    상품 금액 + 배송비 ≠ 총액 이 되어 1센트가 어긋나 보일 수 있다.
        // [JA] 🐛 以前は `total - shipping` をそのまま出していた。どちらも浮動小数なので 13.8 - 4.9 が 8.900000000000002 になる — 決済画面にその数字がまるごと表示された。
        //      **お金を扱う値は計算直後にセント単位で固定する。**
        //      表示時だけ丸めない理由: 三行をそれぞれ丸めると商品金額 + 送料 ≠ 合計となり 1セントずれて見えることがある。
        merchandise: roundMoney(Math.max(0, total - shipping)),
        shipping,
        total,
    };
}

/**
 * 돈을 센트 단위로 고정한다.
 *
 * <p>{@code 0.1 + 0.2 === 0.30000000000000004} 인 세계에서, 금액을 더하거나 뺀 결과를
 * 그대로 두면 <b>화면에 그 꼬리가 그대로 나온다.</b> 실제로 결제 화면의 배송비가 그랬다.
 *
 * <p>{@code Number.EPSILON} 을 더하는 이유: {@code Math.round(1.005 * 100)} 은 100 이다
 * (1.005 가 실제로는 1.00499999… 로 저장된다). 아주 작은 값을 더해 그 경계를 넘긴다.
 *
 * <p>[JA] お金をセント単位で固定する。{@code 0.1 + 0.2 === 0.30000000000000004} の世界で、金額を足し引きした結果をそのままにすると<b>画面にその端数がそのまま出る。</b>
 * 実際に決済画面の送料がそうだった。{@code Number.EPSILON} を足す理由: {@code Math.round(1.005 * 100)} は 100 である
 * (1.005 は実際には 1.00499999… として保存される)。ごく小さい値を足してその境界を越えさせる。
 */
export function roundMoney(value) {
    const n = Number(value);
    if (!Number.isFinite(n)) return 0;
    return Math.round((n + Number.EPSILON) * 100) / 100;
}

/**
 * 화면에 찍을 금액 문자열.
 *
 * <p>소수점 <b>둘째 자리까지</b>, 그리고 <b>불필요한 0 은 붙이지 않는다</b> — 4 를
 * "4.00" 으로 보여 주면 정수 가격이 대부분인 이 카탈로그에서 오히려 시끄럽다.
 * 4.5 는 "4.5", 8.900000000000002 는 "8.9", 12.345 는 "12.35" 가 된다.
 *
 * <p>통화 기호는 붙이지 않는다. 지금 화면들이 {@code {값}$} 로 뒤에 붙이고 있고,
 * 통화 표시 규칙은 i18n 과 함께 정리할 일이다(docs/45 §5).
 *
 * <p>[JA] 画面に表示する金額文字列。小数点<b>第二位まで</b>、そして<b>不要な 0 は付けない</b> — 4 を "4.00" と見せると整数価格がほとんどのこのカタログではかえってうるさい。
 * 4.5 は "4.5"、8.900000000000002 は "8.9"、12.345 は "12.35" になる。
 * 通貨記号は付けない。今の画面は {@code {値}$} と後ろに付けており、通貨表示の規則は i18n とともに整理すること(docs/45 §5)。
 */
export function formatMoney(value) {
    return String(roundMoney(value));
}
