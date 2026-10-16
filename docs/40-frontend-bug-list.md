# 40. フロントエンドの欠陥リスト — 優先度順

**日本語** | [한국어](40-frontend-bug-list.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** P0〜P2 はすべて修正しました（進捗表）。残っているのは chat ルートのヘッダーの順序（§バックエンドに引き渡すもの）です。

2026-09-14、`frontend/src` 全体（約 18,000 行）を洗い出した結果である。デプロイされたサイトが初めて
端から端まで動くようになった直後なので、**インフラ・バックエンドに隠れて見えなかった画面の問題**が今そのまま
表に出てくる。上から順に直せばよい。

基準は一つである: **ユーザーが体験する結果**。コードの臭いではなく、「これを押すと何が起きるか」で
順位を付けた。各項目にファイルと行、そして直す方向を一行で書いた。

すでに直っているもの（SSE の再接続、PayPal の二重キャプチャ、注文の重複送信、ストアのバナー 3 状態など）は
除き、**今残っているもの**だけを書いた。

---

## 進捗状況（2026-09-15 更新）

| 項目 | 状態 | コミット |
|---|---|---|
| 0-a アイコンの 404 | ✅ 完了 | `1e1638c` |
| 0-b 消した商品が復活する | ✅ 完了（2 段階 — サーバー基準のカートを含む） | `1e1638c` |
| 1 `/forgot` の 404 | ✅ 完了 | `1e1638c` |
| 2 決済手段なしで注文 | ✅ 完了 | `1e1638c` |
| 3 `/profile/orders` のクラッシュ | ✅ 完了 | `1e1638c` |
| 4 401 一つでグローバルにログアウト | ✅ 完了 — ErrorFallback も併せて書き直した | `2d49a44` |
| 5 キー入力のたびに API | ✅ 完了 | `b919e30` |
| 6 Paid 表示が逆 | ✅ 完了 | `f653e80` |
| 7 画面の `undefined` | ✅ 完了 — `util/userDisplay` + `Avatar` で緩和した後、プロフィールの GET エンドポイントができて実際の名前・写真が埋まるようになった | `f653e80`, `a5ad644` |
| 8 カートの選択が隣に移ってしまう | ✅ 完了 | `f653e80` |
| 9 クーポン入力欄で文字が消える | ✅ 解消 — 入力欄を `CouponPicker` に置き換え、Formik/ShippingInput の組み合わせ自体がなくなった | `9dbf3c5` |
| 10 金額をクライアントが決める | ✅ 完了 — `OrderRequest` から金額フィールドを**削除**した。総額はカート、割引額は coupon-service の見積もりから来る | |
| 11 モバイルヘッダーの "Sign in" | ✅ 完了 | `f653e80` |
| 12 ページタイトル | ✅ 完了 | `f653e80` |
| 13 ログイン後は常にホーム | ✅ 完了 | `f653e80` |
| 14〜18（P2） | ✅ 完了 — ソースマップ・診断ログ・console.log・タイポ・デッドリンク・構造 | |
| 19 デプロイが反映されない（キャッシュ） | ✅ 完了 — バンドルにコンテンツハッシュ + `/static/` | 下記 §19 |
| 20 ホームの画像が画面の外へ | ✅ 完了 — 画像サイズの制約 + `slidesPerView="auto"` | 下記 §20 |
| 22 検索と商品画面で価格が違う・通貨表記がバラバラ | ✅ 完了 — 割引価格を一つの関数に、通貨は Intl で | 下記 §22 |

---

## 19. 🔴 フロントエンドのデプロイがユーザーに反映されない ✅

**「デプロイしたのに画面がそのまま」の原因。** この項目はホーム画面一つではなく、**すべてのフロントの
変更**に該当する。

- `webpack.config.base.js` は `filename: "[name].bundle.js"` — バンドル名が**常に**
  `main.bundle.js` である。内容が変わってもパスが同じ。
- `nginx.conf` のキャッシュルールは、CRA のように**ハッシュ付きのファイルが `/static/` にあることを前提**としている
  （そのコメントにそう書かれている）。実際の成果物はルートの `main.bundle.js` なので、そのルールに
  **一つも引っかからず**、`Cache-Control` ヘッダーがまったく付いていなかった。
- ヘッダーがないと、ブラウザは*ヒューリスティックキャッシュ*を行う — 通常 `Last-Modified` からの経過
  時間の 10% を勝手にキャッシュする。`index.html` は `no-cache` なので新しく取得するが、
  その中の `<script src="/main.bundle.js">` は**名前がそのまま**なので、ブラウザはキャッシュにある
  古いバンドルを使う。

症状が一つしかないのがこのバグの性質である。開発者ツールには `200 OK (from disk cache)` と
表示され、サーバーは新しいファイルをちゃんと配信しているので、どこが間違っているのかが表に出ない。

**修正** — `webpack.config.prod.js` の output にコンテンツハッシュを入れ、`/static/` の下に
出力する。名前にハッシュが入れば、内容が変わるたびにパスが変わるのでキャッシュが挟まる余地がなく、
nginx の既存の `immutable` ルールが**意図どおりに**適用される。`HtmlWebPackPlugin` が script タグを
新しい名前に書き換えるので、手作業はない。

本番ビルドで確認した: `dist/static/main.<해시>.bundle.js` が生成され、`index.html` がそのパスを
指す。`location ~ \.map$` は正規表現なので prefix より優先され、ソースマップの 404 遮断は維持される。
ファイル名をハードコードしている箇所はない（Dockerfile・ops・k8s で参照なし）。

⚠️ **今回のデプロイまでは**古い `main.bundle.js` がキャッシュに残っている可能性がある。確認するときは
ハードリフレッシュ（Ctrl/Cmd+Shift+R）を使うこと。次のデプロイからは構造的に解消される。

---

## 20. ホームの商品画像が画面の外にはみ出す ✅（ユーザー報告）

- `components/home/ProductSwiper.jsx` — `<img className="w-auto h-auto object-cover">`。
  `w-auto h-auto` は**「元のサイズで描け」**という意味である。1200×1600 の商品写真が 220px の
  ボックスの中で **1200px 幅で**レンダリングされる。ボックスに `overflow-hidden` もなく、四方に
  あふれ出していた。
- `object-cover` は**死んだ宣言**だった。`object-fit` は要素のボックスが元の比率と異なる
  ときにだけ意味があるが、`w-auto h-auto` ならボックス = 元画像なので合わせるものがない。設定があるので
  大丈夫そうに見えることが、このバグが長く残った理由である。
- **なぜ「一瞬」なのか** — ロード前はサイズが 0 に近くおとなしいが、ロードされた瞬間に元の
  サイズへ跳ね上がり、その後 Swiper がレイアウトを取り直して収まる。
- 幅が 3 か所で互いに違っていた: カード `w-[215px]`、内部ラッパー `w-52`（208px）、画像ボックス
  `w-[220px]` — **内側が外側より広かった。**
- `HomeProductSwiper` は `slidesPerView: 5` だった。Swiper はスライドの幅をコンテナの
  1/5 で計算するが、カードは固定 215px なので、1024px の画面ならスライド ≈190px vs カード
  215px → **カードが 25px ずつはみ出して重なった。**
- `pages/home/GridCategory.jsx` も同じ類である — `h-[420px] object-cover` で**幅が
  制限されていなかった。**

**修正** — ボックスがサイズを決め（`w-full h-[300px]` + `overflow-hidden`）、画像はその中に
収める（`max-w-full max-h-full object-contain`）。Swiper は `slidesPerView="auto"` に変えて
**内容が幅を決めるように**する — 固定幅のカードに合った定石であり、どの画面幅でもはみ出さ
ない。画面外の画像は `loading="lazy"` で遅延させる（ボックスの高さが固定なので、遅れて届いても
レイアウトがずれない）。

`object-contain` を選んだ理由は、商品写真を**切らないため**である。`cover` はボックスを
埋める代わりに端を切り落とすが、服・靴の写真では切られた部分こそが見るべき部分である。

> ⚠️ **ホームについては 21 番で覆された。** 切らなければ、代わりに**ボックスが空く。** ホームのグリッド・ホームの
> カードはサムネイルなので、埋まっているほうがよいという判断で `cover` に変え、詳細画面は `contain`
> のままである。下のガードの説明も 21 番を基準に更新された。

ガードは `ProductSwiper.test.jsx` — 画像がボックスを**はみ出さず空けもしない**ことと、
`w-auto`/`h-auto` の**不在**を併せて固定する。

---

## 21. ホーム中央のグリッドに大きな空白が残る ✅（ユーザー報告）

20 番を直したことで `GridCategory` は**はみ出さなく**はなったが、今度は逆に**空いた。**
原因は三つあり、三つとも「枠と内容の比率が合わないと、余った場所をそのまま空けておく」である。

- **① 1 列のグリッドに 420px のタイル。** `grid-cols-1` + `h-[420px]` なのに、サーバーが返す
  件数は `content.home_section.item_limit = 4` である。カード一つが縦に **1700px** 以上に
  伸びる。ところがカテゴリーごとに商品数が違う — 1 個しかないカテゴリーのカードは 420px なのに、
  同じ行の他のカードが 1700px なので、グリッドが行の高さを揃える際に**そのカードの下に
  1200px を超える白い空白**ができた。名前が「グリッド」なのに 1 列だったことが災いの元である。
  （元のコードは `slice(0, gridCols*gridCols)` で 2×2 を前提としていたが、サーバー側のグルーピングに
  移したときに列数だけが 1 のまま残った。）
- **② `object-contain` のレターボックス。** ボックスの比率と写真の比率が違う分だけボックスが空く。
  横長の写真を縦長の枠に入れると、半分が白い帯になる。
- **③ 最終行の空きセル。** `md:grid-cols-4` は**列の数を固定**する。セクションの数は
  データが決める（シードは GRID 5 個）ので、5 ÷ 4 = 最終行にカード一つ + **空きセル三つ**である。
  `grid-flow-row-dense` はあったが何もしていなかった — それは後ろに来る**小さな**
  項目を前の穴にはめ込むルールであり、すべてのカードが 1 セルなら埋める穴がない。

**修正**

| 場所 | Before | After |
|---|---|---|
| `GridCategory` のタイル | `h-[420px] w-full object-contain` | `aspect-square` のボックス + `absolute inset-0 h-full w-full object-cover` |
| `GridCategory` の内部の列 | `grid-cols-1` | `grid-cols-2`（商品が 1 個なら `grid-cols-1`） |
| `HomeSections` のグリッド | `grid md:grid-cols-4 grid-flow-row-dense` | `flex flex-wrap` + 項目に `flex-1 basis-[220px] min-w-0` |

正方形のタイル + `cover` は**ボックスが決して空かない。** flex の折り返しは**列の数を内容が
決めるように**するので、セクションがいくつあっても各行が余った幅を分け合う — 穴ができようがない。

### カードのスワイパーも同じ理由で空いていた

`ProductSwiper` は `w-full h-[300px]` のボックスに `max-w-full max-h-full object-contain`
だった。はみ出しはしないが同じ ② 番である — 215×300 のボックス（比率 0.72）に正方形の写真を入れると
**上下に 85px ずつ白い帯**ができ、カードが並んでいるのでその帯も並んで見える。

`aspect-[4/5]` のボックス + `absolute inset-0 h-full w-full object-cover` に変えた。
**高さをピクセルで固定しなくてもレイアウトはそのまま安定している** — 遅延ロードと対になる
条件は「高さがピクセルで固定されている」ことではなく「画像が届く前に高さが分かる」ことであり、
比率 + 幅があれば分かる。`ProductSwiper.test.jsx` の固定高さのガードも比率のガードに変えた。

> **なぜグリッドは正方形で、カードは 4:5 なのか。** 切り取られる量は、ボックスの比率が写真の比率から
> **どれだけ離れているか**で決まる。商品写真はたいてい縦長である（3:4 = 0.75、4:5 = 0.8）。
> 正方形（1.0）はその分より多く切り取る。グリッドのタイルは小さなサムネイルなので揃っていることのほうが価値があり、
> カードは商品を見る場所なので、あまり切らないほうを選んだ。

> 20 番で `contain` を選んだ理由（切らない）は、**詳細画面では依然として有効である。**
> ホームはサムネイルなので埋まっているほうがよいという判断で覆し、詳細画面はそのまま `contain` である。
> 同じルールを画面全体に一律適用したことが、20 番の修正の的外れだった。

ガードは `GridCategory.test.jsx`（8 件）・`HomeSections.test.jsx`（4 件）・`ProductSwiper.test.jsx`
（更新）。要点は `object-cover`・`aspect-*` の**存在**と、`object-contain`・`h-[…px]`・
`grid-cols-N` の**不在**を併せて固定すること。元に戻すと失敗することを確認した。

おまけに、`__mocks__/swiperMock.js` の prop 除去リストが漏れていて（`centeredSlides`、
`slidesPerGroup`、`navigation`、`breakpoints`、`speed`）、React の警告がテスト出力を埋め尽くして
いた。`forwardRef` でもなかったので ref の警告まで出ていた。両方とも整理した。

---

## 22. 🔴 検索画面と商品画面で価格が違う・通貨表記がバラバラである ✅（ユーザーからの確認依頼）

### 何が違っていたのか

サーバーは検索結果と商品詳細に**同じ値**を送っている — サイズの定価と、常時割引・キャンペーン割引のうち大きいほうの割引率
（`EventPricing.apply`）。食い違っていたのは、**割引をどこで適用するか**だった。

| 画面 | 以前 | 結果（定価 $100、30% 割引） |
|---|---|---|
| 検索・ホーム・キャンペーンの一覧カード（`ProductCard`） | サイズの**定価**を `USD100$` で | "$100" + "-30%" バッジ |
| 商品画面（`pages/Product.jsx`） | `discounted(정가, 할인율)` | "$70" |
| カート・決済（サーバーの `ProductService.discounted`） | 割引価格 | $70 |
| 価格の並べ替え（`browse.jsx`） | **定価** | $70 のものが $80 のものの後ろに |
| 価格フィルター（サーバーの `findProductIDBySizeAndPriceAndColor`） | **定価** | 「$80 以下」から漏れ、「$90 以上」に出てくる |

さらに 🟡 1 セント: 商品画面の `discounted` は浮動小数点の `p × (1 − d/100)` に `toFixed(2)` で、サーバーは整数の
パーセント・BigDecimal・HALF_UP である。$1.15 の 50% が、画面では **$0.57**、請求では **$0.58** だった。

### 直したこと

- **割引価格を一か所で** — `util/pricing` の `discounted` をセント単位の整数・HALF_UP に（サーバーと同じ値）、`skuPriceRange`
  を一覧カード・商品画面・価格の並べ替えが共通で使う。カードは割引価格（範囲）と取り消し線付きの定価を表示する。
- **価格フィルターも表示される価格で** — サーバーは定価で絞り込んでいた。キャンペーン割引はメモリ上のマップなので SQL は知らない。そこで SQL は
  常に真になる条件（割引価格 ≤ 定価なので「定価 ≥ 下限」）で候補を絞り、`SearchService.skuFilterIds` が候補ごとに
  `ProductService.discounted(정가, effectiveDiscount)` で正確に判定する。
- **通貨表記は Intl で** — 画面ごとに `59.98$` ・ `USD59.98 $` ・ `$59.98` と手で付けていたものを `useMoney()` 一つに。ストアの
  通貨（ビルド引数 `REACT_APP_STORE_CURRENCY`）をアプリの言語のロケールで描画する — 韓国語なら `US$59.98`、日本語なら `$59.98`。請求通貨
  （PortOne の KRW など）は第 2 引数で渡す。決済・返金のように i18n Provider の外でテストされる画面でも使えるよう、言語がなければ
  デフォルトのロケールで描画する（`useCurrentLang`）。配送料の文言（`shipping.fee`）から `$` を外し、金額を表記済みのまま入れる。
- おまけで見つけたもの:
  - 🐛 カートの要約の金額が `toFixed` の**文字列**なので `shippingFee === 0` が常に偽 — 送料無料が "배송비 0.00$" と表示されていた。
  - 🐛 商品画面の配送案内の在庫表示が `quantity > 1` なので、**最後の 1 個が「品切れ」**と表示されていた。
  - 🐛 一覧カードのリンクが、価格が複数あると `&size=<색상 번호>` を付けるため、詳細が見当違いのサイズを選んだ状態で開いていた。
  - 🌏 翻訳されていない "pieces Available" ・ "Select a Color:" ・ "Coupon applied" ・ "New Price"、決済画面の韓国語固定の
    クーポン文言を辞書へ。

### ガード

`pricing.test`（サーバーと同じ丸め — $1.15 の 50% = 0.58 など、割引価格の範囲）、`ProductCard.test`（割引価格・取り消し線付きの定価・範囲、
`USD…$` がない）、`browse.test`（表示される価格で並べ替え）、`SearchServiceUnitTest`（割引・キャンペーン割引を反映した価格フィルター、価格
のないフィルターは以前のクエリ）、`Summary`・`Checkout`・`ShippingInfo`・`Total` のテスト（Intl 表記、文字列 "0.00" も無料、最後の 1 個は
在庫あり）。新しい SQL は PG16 で確認した。

⚠️ 残っていること: 一覧カードは最初の色（sku）ではなく**マウスを乗せた色**の価格を表示し、価格の並べ替えは**最初の色**基準である
（以前と同じ）。色ごとに割引が違うと、並べ替えの順序とカードの価格が食い違うことがある。

---

## P0 — 機能が動かない

### 0-a. アイコンが壊れる — 本番ビルドが `public/assets` をコピーしない（ユーザー報告）

- `frontend/webpack.config.prod.js:30-40` — `CopyPlugin({ from: "public", globOptions:
  { ignore: ["**/assets/**"] }, to: "public" })`。**`assets/` は丸ごと除外し**、残りは
  `dist/public/` の下に入れる（ルートではなく）。
- なぜ除外したのか: `webpack.config.base.js:62` の asset generator が **import した**画像を
  `dist/assets/images/[name].[ext]` に出力するので、同じパスをコピーすると重複すると考えたのである。
- ところが**文字列のパスでだけ**使う画像は import がないのでバンドラーが生成せず、Copy も
  スキップする → 本番で 404:
  - `components/order/Payment.jsx:196`、`components/checkout/Payment.jsx:40` —
    `/assets/images/{paypal,credit_card,cash}.png` → **決済手段のアイコンすべて**
  - `components/browse/brandsFilter/BrandsFilter.jsx:34` — `/assets/images/<brand>.png` →
    **/browse のブランドロゴすべて**
- 開発で見えなかった理由: `webpack.config.dev.js` には CopyPlugin がなく、dev-server が
  `public/` を静的に配信している。「ローカルでは動くのにデプロイすると壊れる」の典型。
- 修正: `to: "."` でルートにコピーし、`assets/` を除外しない。バンドラーが出力する
  ファイルと名前が重なるものは**同じファイル**なので、上書きしても無害である — ただし確実にするなら、
  ランタイムで参照する画像を `public/assets/images/` ではなく `public/img/` のような**別のパス**に
  移し、参照も変える。`index.html` は除外（`ignore: ["**/index.html"]` — HtmlWebPackPlugin が
  作る）。
- 付随: `public/index.html` が font-awesome を**二つの CDN から二つのバージョン**（5.8.2 + 5.7.0）で
  読み込んでいる。チャット画面のアイコン 4 つ（`fas fa-*`）がこれに依存している。残りのアイコンはすべて
  heroicons/react-icons なので CDN とは無関係である。チャットの 4 つを react-icons に変えて CDN のリンク
  二つを消せば、外部依存がなくなる。

### 0-b. カートから消した商品が復活する（ユーザー報告）

- `components/cart/Product.jsx:56-73` — `removeItemCart` は **redux からだけ**消す。サーバーの
  呼び出しは `:59-68` でコメントアウトされている。`updateQty`（`:26-54`）も同様にローカルだけを変える。
- `pages/cart.jsx:27-35` — カート画面に入ったとき、**redux が空ならサーバーのコピーで
  埋める**（`cart.cartItems.length === 0 && cartData.length > 0 → updateCart(cartData)`）。
- そのため: 全部消して出ると redux は空になり → 再び入るとサーバーに残っている古いコピーがそのまま
  戻ってくる。サーバーのコピーは `savecart`（チェックアウトを押したときだけ）で書かれるので、**最後のチェックアウト時点の
  スナップショット**である。それをカートとして復活させるのがこのバグである。
- もう一つ: `redux/CartSlice.js:27` — `updateCart` の中の `state.status = UPDATED` がコメント
  アウトされている。生きていれば「ユーザーが手を加えたカート」を区別できた。
- cart-service には項目削除のエンドポイントが**ない**（`CartController.java:63` にコメントとしてあるだけ）。
- 修正 — 2 段階:
  1. **今すぐ**: `updateCart` が `status = UPDATED` を設定するようにし、`cart.jsx` は
     `status === EMPTY` のときだけサーバーから埋める。ユーザーが空にしたカート（UPDATED）は
     復活させない。redux は persist されるので、リロードしても維持される。
  2. **きちんと**: サーバーがカートの真実であるべきである。項目削除・数量変更のエンドポイントを
     cart-service に作り、`removeItemCart`/`updateQty` がそれを呼び出す。そうすれば他の端末でも
     同じカートが見える。これにはバックエンドの作業も併せて必要である。
- ⚠️ `useFetchCart`/`useFetchCheckOut` の queryKey に `cart.status` が含まれているため、1 番を
  行うと状態が変わるたびにサーバーのカートを読み直す。無害だが、知っておくこと。

#### 0-b 第 2 段階 — サーバーがカートの基準（完了）

| 何 | どこ |
|---|---|
| 追加・数量・削除のエンドポイント — `PUT/PATCH/DELETE /api/cart/items`（身元は `User-Id`、行は商品・色・サイズ）、応答は現在のカート全体 | `cart-service` `CartController`・`CartService.putItem/setItemQty/removeItem` |
| チェックアウト（`savecart`）はカートを**変えずに**、選んだ行に `selected` の印を付けるだけ — 総額・`/checkout`（order・coupon が読む）は選んだ行だけで | `V3__cart_product_selected.sql`（追加のみ、デフォルト値 true） |
| catalog が知らない行（削除・販売停止）は応答からだけ外して警告ログ — 以前はカート全体が例外になり、その行を外すリクエストまでロールバックされていた | `CartService.loadCart` |
| 色は SKU が決める（本文の色を信用しない） | `CartService.createCartProduct` |
| 画面: ログインしていれば、追加（`Infos`）・数量・削除（`cart/Product`）がサーバーを呼び、応答で置き換える。失敗したら画面はそのまま + 理由 | `redux/CartSlice.syncedFromServer` |
| カートに**所有者（ownerId）** — ログイン時に `CartSync` が所有者のない行（ログイン前に入れたもの・古い状態）をサーバーに上げ、サーバーのものを受け取る。他人のものは捨てる。ログアウトしたらコピーを消す | `components/cart/CartSync.jsx` |
| カート画面は、自分のカートなら入るたびにサーバーのもので — 他の端末での変更が見える | `pages/cart.jsx` |

- 🐛 併せて直したもの: 同じブラウザで別の人がログインすると前の人のカートが見え、チェックアウトするとその人のサーバーの
  カート・注文に入っていた（redux-persist が cart を所有者なしで保存）。
- デプロイ: cart-service（Flyway V3 — カラム追加のみ）+ frontend。順序は問わない — 古い画面は `savecart` だけを使い、新しいサーバーの `savecart` は
  選んだ行の印付けとして同じ動作をする（選ばなかった行が消えないことだけが違う）。


### 1. 「パスワードをお忘れですか？」がエラーページに飛ぶ

- `pages/user/SignInPage.jsx:314` — `<Link to="/forgot">`
- `App.jsx` — `/forgot` のルートが**ない**。`pages/user/ForgotPage.jsx`（134 行）は作られて
  いるのに、どこにもつながっていない。
- 結果: リンクを押すとルーターのエラー画面。パスワードを忘れたユーザーはアカウントを取り戻す手段がない。
- 修正: `App.jsx` の `RootPage` の子に `{ path: 'forgot', element: <ForgotPage /> }` を一行。
  （バックエンドの `/user-service/api/auth/public/forgot` が実際にあるかも併せて確認。）

### 2. 決済手段を選ばなくても注文が作成される

- `pages/checkout.jsx:28` — `useState(user.defaultPaymentMethod)`。JWT から作った `user` には
  そのフィールドがない（`util/jwtUser.js` — email/userId/roles だけ）。初期値が **`undefined`**。
- `components/checkout/Summary.jsx:92` — ガードが `paymentMethod === ""` である。`undefined` は
  通過する。
- 結果: 決済手段を選ばずに "Place Order" を押すと、`paymentMethod: undefined` で注文が
  作られる。注文画面（`order/Payment.jsx`）は `payment.id == order.paymentMethod` でアイコンを
  選ぶので何も描画されず、PayPal ボタンだけがぽつんと残る。
- 修正: 初期値を `""` にし、ガードを `!paymentMethod` に。`pages/profile/payment.jsx:15-16` も
  同じ初期値を使っている。

### 3. `/profile/orders` に直接入るとクラッシュ

- `pages/profile/orders.jsx:107` — `searchParams.get('q').split("__")[1]`
- クエリ `?q=` なしで入ると `get('q')` が `null` → `TypeError` → ルーターのエラー画面。
  サイドバーのリンクは q を付けるが、アドレスバーへの入力・ブックマーク・リロード（アドレスが `/profile/orders` に
  正規化された場合）では付かない。
- 修正: `searchParams.get('q')?.split("__")[1] ?? "all"` のようにデフォルトのフィルターを置く。

### 4. 付随的なリクエスト一つがアプリ全体をログアウトさせる

- `util/TokenRefresher.js:158` — 401 を受けたリクエストが、リフレッシュ後にリトライしても 401 なら
  `logout()`。**どんなリクエストでも**そうなる。
- 昨日 `/admin/product` がログイン画面に飛ばされたのがまさにこれだった（バナーの storage-health
  一つがセッションを吹き飛ばした）。原因のログ（`reportForcedLogout`）は付けたが、動作はそのままである。
- 同じファイルの `:137` `case 500: navRef.current("/")` — **どんな 500 でもホームへ**送る。ユーザーは自分が
  何をしていて飛ばされたのか分からない。`case 503` もログアウトまでしてしまう。
- 修正の方向: ログアウトは**トークンの更新そのものが失敗したときだけ**（リフレッシュのエンドポイントが 401/204）。
  個別のリクエストの 401 は、そのリクエストの失敗として返す（呼び出し側が処理する）。500 は遷移せずに
  呼び出し側へ投げる — ErrorBoundary が受け取る。
- ⚠️ これはインターセプター一つを直す作業ではなく、**認証失敗の意味を定め直す作業**である。
  直した後は、「権限のない API を呼んだ画面」がどう見えるかを画面ごとに確認する必要がある。

### 5. 商品登録画面がキーを押すたびに API を呼ぶ

- `pages/admin/Index.jsx:113-131` — `useEffect(..., [product.category, product.name])`。
  **商品名を一文字打つたびに** `loadCategories` と `getSubs` が再び飛ぶ。
  ゲートウェイのレートリミッターが burst 40・毎秒 20 なので、速く打つと 429 が出て、
  429 はどこでも処理されていない。
- 同じファイルの三つの `useEffect` の中の `async` 関数（`getProducts`、`getParentData`、`getSubs`）は
  すべて **catch がない。** 失敗はコンソールの「処理されない reject」として残るだけで、画面はそのままである。
- `getParentData` は `setProduct({ ...product, ... })` で**古いクロージャの product** を
  展開する — 応答が来るまでの間に入力した値が上書きされる。
- 修正: 依存配列から `product.name` を外す。`loadCategories` はマウント時に一回。`setProduct`
  は関数形式（`prev => ({...prev, ...})`）に。三つとも `try/catch` + 画面表示。

---

## P1 — 間違ったものを表示するか、黙って誤動作する

### 6. 注文一覧の "Paid" 表示が逆である

- `pages/profile/orders.jsx:80` — `orderStatus === "UNPAID" ? <초록 체크> : <빨간 X>`
- **未決済**が緑のチェック、決済完了が赤い X で表示される。
- 修正: 条件を反転するか、決済完了の状態の集合（`PAID`、`COMPLETED` …）で判定。

### 7. 画面のあちこちに `undefined` が表示される — JWT のユーザーオブジェクトにないフィールド

`util/jwtUser.js` の `toUser()` は `{ email, userId, roles }` しか作らない。ところが:

| ファイル | アクセス | 結果 |
|---|---|---|
| `pages/profile/orders.jsx:36` | `user.name` | タイトル "undefined's Orders" |
| `pages/profile/address.jsx:22` | `user.userName` | "undefined's Address" |
| `pages/profile/payment.jsx:38` | `user.username` | "undefined's Address"（コピペ — タイトルも間違い） |
| `components/order/UserInfo.jsx:10-17` | `user.image`, `user.name` | 壊れた画像 + 空の名前 |
| `pages/checkout.jsx:28` | `user.defaultPaymentMethod` | P0-2 |
| `pages/header/Account.jsx:45` | `user ? user.email : "sign in"` | `user` の初期値が `{}` なので truthy → "Hello, undefined" |

- 修正: 二つのうちどちらか。(a) ログイン直後に `/user-service/api/user/profile` でプロフィールを受け取り、
  `updateUser` で埋める（`AuthContext.js` にコメントアウトされた `fetchUser` がその場所である）。
  (b) 画面が `user.email` だけを使うように変える。(a) が正しい — プロフィール画像・名前は結局必要になる。

### 8. カートから項目を消すと、選択が隣の項目に移ってしまう

- `components/cart/CartPage.jsx:118` — `cart.cartItems.map((product, i) => <Product key={i} …>)`
- 子の `Product` が選択状態を持っているのにキーがインデックスなので、真ん中の項目を消すと React が
  後ろの項目に前の項目の状態を付けてしまう。「1 番を消したのに 2 番がチェックされている。」
- 修正: `key={product._uid}`（Infos.jsx がすでに `_uid` を作っている）。

### 9. クーポン入力欄で文字が消える

- `components/checkout/Summary.jsx:141` — `<Formik enableReinitialize initialValues={{ coupon }}>`
  + `<ShippingInput onChange={(e) => setCoupon(e.target.value)}>`
- 値の出どころが二つ（ページの state + Formik）。`enableReinitialize` がキー入力のたびにフォームを初期化する。
  `pages/profile/security.jsx` のコメントが**まさにこのバグ**を説明して直してあるのに、こちらは
  そのままである。
- 修正: security.jsx と同じ方式 — Formik が値を所有し、`onSubmit(values)` で読む。

### 10. 金額をクライアントが決める ✅

**確認結果: サーバーは再計算していなかった。** `OrderDataService.createOrder` が
`request.getTotal()` をそのまま `Order.total` に保存していた。

PayPal の検証があるから大丈夫とは言えなかった。その検証は**決済額と DB の注文総額が
同じかどうか**しか見ない（`persisitPaypalPayment`）。総額そのものがブラウザから来ているので、
`total: 1` を送って 1 ドルを決済すれば、**検証は正常に通過した。** 錠前は頑丈なのに、
鍵を客が削っていたのである。

同じ問題がクーポン側にも残っていた。制約の**ない**クーポンは `CouponApplyRequest.totalPrice`
をそのまま割引の基準に使っていた —「自分のカートは 100 万ウォンだ」と主張すれば 10% クーポンが
10 万ウォンを割り引き、その金額が再び注文総額に入っていた。

**直し方**

- `OrderRequest` から `total`・`totalBeforeDiscount` を**削除した。** 読まないとコメントだけ
  付けておくと、次の人が「ここに total があるな」と言ってまた使ってしまう。なくすことだけが唯一確実である
- 総額は **cart-service がカタログ価格で計算して保存した値**を使う
  （`CartDto.cartTotal`）。注文作成はすでにそのカートを受け取っていた
- 割引額は coupon-service に**改めて問い合わせる**（`POST /api/coupon/internal/quote`）。
  カートに保存された `totalAfterDiscount` を使わない理由は、それが Kafka で非同期に
  更新されるからである — クーポンを適用してすぐに注文すると、まだ届いていない可能性がある
- 🔴 **見積もりはクーポンを消費しない。** 注文作成がクーポンを「使用」扱いにすると、決済失敗や
  離脱のときに使ってもいないクーポンが消えてしまう。使用扱いは元どおり適用時点でだけ行われる
- クーポンを確認できなければ**注文を拒否する。** 割引なしで進めると、ユーザーは画面で見た
  金額と違う金額を決済することになる — 黙って多く取るより「今はできない」のほうがよい
- `Summary.jsx` も金額を送らない。サーバーは捨てるが、画面が送り続けていると、
  次の人がサーバーにフィールドを復活させてしまう
- `Payment.jsx` の `order.total` はそのままにする — その値は今や**サーバーが計算して返した**
  注文総額であり、決済後にサーバーが実際の決済額と再び突合する

**🔒 認可** — `/internal/quote` は `/api/auth/user/id` と同じルールで塞ぐ
（`User-Role` ヘッダーがあれば外部リクエスト → 管理者のみ）。そうしないと、ログインした誰もが**他人のメール**で
「その人がこのクーポンを持っているか、いくら割り引かれるか」を問い合わせられる — `/applycoupon` で
直した BOLA がそのまま復活する。

**⚠️ 残っていること — 金額が整数である。** `Order.total` が `int` なので小数点以下が切り捨てられる。$59.98 は 59 として
保存され、フロントが PayPal に送る金額も同じ 59 なので検証は通るが、実際には 98 セントを
少なく受け取る。ここで直さなかった理由は、`Order`・`OrderProduct`・`OrderDto`・決済の Outbox・
フロントまで一緒に `BigDecimal` に移す必要があるからである。今回の変更は**誰が金額を
決めるのか**だけを正す。切り捨てを使ったのもそのためである — 四捨五入すると、一部の注文はカートに
表示された金額より高くなってしまう。`OrderPricingTest` がこの動作を隠さずに固定している。

> ✅ **直した** — order-service の V7 が四つの金額カラムを `numeric(19,2)` に移し、`Order`・
> `OrderPricing.Amounts`・`OrderDto` が `BigDecimal` になった。上のテストは今では逆（「セントが
> 生き残る」）を固定している。フロントの `{total}$` も `formatMoney(total, STORE_CURRENCY)` に
> 変えた（`Total.jsx`・`orders.jsx`・`Refunds.jsx`）。詳しくは docs/49 §6。

### 11. モバイルのヘッダーはログインしても "Sign in"

- `pages/header/Account.jsx:35` — `md:hidden` のブロックが認証状態を見ていない。
- 同じファイルの `:143` — 未ログインの状態でカートアイコンを押しても**何も起きない**
  （`token ? navigate("/cart") : {}`）。ログイン画面に送るべきである。

### 12. ページタイトルが変わらない

- `pages/header/Header.jsx:33-37` — `<title>`・`<meta>` を `<header>` の中（body）に描画している。
  React 18 はこれを head に持ち上げない（19 から）。`public/index.html` には `<title>` 自体が
  ないので、タブのタイトルが空文字列/URL になる。
- 修正: `react-helmet-async` または `useEffect(() => { document.title = title }, [title])`。

### 13. ログイン後は常にホームに行く

- `pages/user/SignInPage.jsx:178` — `navigate('/')`。
- `/checkout` でセッションが切れてログインしに行って戻ると、また最初からである。`ProtectedRoute` が
  `state: { from }` を渡し、ログイン成功時にそこへ戻るべきである。

---

## P2 — 今は見えないが、すぐに問題になるもの

### 14. 本番ビルドがソースマップをそのまま出力する / 診断ログをすべて消す ✅

- `devtool: "source-map"` → **`hidden-source-map`**。マップは作るがバンドルに
  `//# sourceMappingURL=` のコメントを入れない。マップ自体をなくすと（`false`）、後で
  スタックトレースを解読する手段がなくなる。
- 🔴 **それだけでは不十分である。** ファイルは依然として `dist/` にあるので、`/main.bundle.js.map` を
  直接叩けばそのまま取得できる。nginx が `location ~ \.map$` を 404 で塞ぐ。二重である。
- `drop_console: true` → **削除。** それは `console.error`・`console.warn` まですべて消してしまう —
  本番でクライアントの診断が 0 になる。実際に消されていたもの: `[auth] 강제 로그아웃 —
  원인 요청: …`、ErrorBoundary のコンポーネントスタック、axios インターセプターの失敗理由。
  代わりに `pure_funcs: ["console.log", "console.debug", "console.trace"]` で**ノイズだけ**を消す。
- 付随して `output: { clean: true }` を有効にした。これがないと、以前のビルドのハッシュ付きチャンクが
  溜まり続けてイメージに入る — 実際に `dist/` に日付の違うチャンクが混ざっていた。
- ビルドして確認: バンドルに `console.log` は 0 個、`console.error` は生きている、
  `//# sourceMappingURL=` のトレーラーなし、チャンクのタイムスタンプはすべて同一。

### 15. 残っている `console.log` 22 か所 ✅

生きている呼び出しは **0 個**になった（残っているのはすべてコメント）。単純な削除ではなく三つに分けた:

- **消した** — レンダリングのたびに出力されるか、何の情報もなかったもの:
  `MultipleSelect`（field）、`ProductPage`（reviews[0]）、`Reviews`（レビュー本文全体 —
  ユーザーが書いた文章である）、`ProductImages`（"err"）
- **`console.error` に上げた** — 失敗なのに本番で消されていたもの:
  `CreateSubCategory`、`CreateCategory`、`util/api` の queryCache onError、
  `browse.jsx` の loader の catch（ここが `null` を返すと画面は「商品がありません」を
  描画する — なぜ空なのかがこの一行にしか残らない）
- **`console.debug` に下げた** — 開発用のシードスクリプト `loadFake.js`

`components/profile/sidebar/Item.jsx` は問題が二つあった。JSX の中で
`{console.log(link)}` を呼び出しており、`map` が作る**最も外側**の要素である `<>` に key が
なかった（key は内側の `<li>` に付いていた）。フラグメントが `<li>` 一つだけを囲んでいて存在
理由もなかったので、`React.Fragment key={...}` に変えて内側の key は外した。

### 16. ユーザーに見えるタイポ ✅

`charactes`・`atleast`・`Faild`・`successed`・`Confirm our New` — すべて**韓国語に置き換えた。**
綴りだけを直すと、韓国語の画面に英語の案内が混ざったまま残る。ログイン失敗の文言を
韓国語に変えたのと同じ方向である。

🐛 置き換えている途中で**別のバグを見つけた。** `AddShipping` の `address1` の案内がすべて "Address 2" だった —
基本住所を空にすると、詳細住所が間違っているというメッセージが出ていた。すぐ上の `phoneNumber` が "First Name
..." を使っていたのとまったく同じコピーミスである。

id の値 `passowrd` も直した。参照する `htmlFor` がないので無害だったが、付けようとした瞬間に間違える。

### 17. 死んでいるリンク・コンポーネント ✅

- **`BreadCrumb`** — `<Link href=…>` だった。react-router の Link は `to` を受け取るので、
  レンダリングはされるがクリックだけが効かなかった。`to` に直すだけなら今度は 404 に飛んでいただろう —
  指していた `/category/<slug>` のルートがこのアプリにはない（`/category` は管理者画面である）。
  実際のパスである `/browse?category=<id>` に変えた（ハンバーガーメニューと同じアドレス）。
  サブカテゴリーは**わざとリンクにしていない** — サブカテゴリーで絞り込む経路がまだない。
  `// have error … i will fix this` の箇所にその事情を書いた。
- **`Maintenance`** — この画面は**ネットワークが切れたときに**表示される。ところが svgrepo.com の SVG を
  ランタイムに `<img src>` で取得していた。肝心なときに壊れた画像が表示され、外部の
  ドメインにこちらのユーザーの訪問が記録される。インライン SVG に変えてリクエストを 0 にした。
  `<a href="#"><button>` の入れ子（無効な HTML）も解消し、行き先のない "Contact Us" の代わりに
  今実際に役に立つ**再試行**を置いた。文言も韓国語に。
- **font-awesome の二重読み込み** — 二つの CDN から二つのバージョンを取得していた。後から来た 5.7.0 が前の
  5.8.2 を上書きするので、**実際に使われているのは古いバージョン**であり、最新を取得したリクエストは丸ごと無駄だった。
  一つだけ残した（すでに使われていた 5.8.2 — アイコン名が変わる余地を作らないよう、上げなかった）。
  ⚠️ `integrity`（SRI）は付けていない。ハッシュは実際のファイルから計算する必要があるが、この作業
  環境からは CDN に出られなかった。**推測したハッシュを入れるとブラウザがスタイルシートを丸ごと
  拒否し、チャットのアイコンがすべて消える** — 間違った錠前は錠前がないより悪い。計算方法は
  `public/index.html` のコメントに書いておいた。

### 18. 構造 ✅

- **`createBrowserRouter` をモジュールのトップレベルへ。** この関数は、履歴の購読・ローダーの状態・スクロール
  復元位置を保持する生きたオブジェクトを作る。レンダリングのたびに新しく作ると、その状態が丸ごと
  捨てられる。`AppRouter` には prop も state もないので、実際には**症状なく潜伏していた** —
  上に Provider が一つ増えるだけで破綻する。
- **`pages/checkout.jsx` の見当違いな import。** `import { isPending } from "@reduxjs/toolkit"` —
  このファイルが使っているのは react-query の `isPendingCheckOut` であり、redux-toolkit の `isPending`
  は async thunk のアクションマッチャーである。誰かが単に `isPending` を使うと、**常に真になる関数オブジェクト**を
  条件として検査することになる。使っていない import 7 個と一緒に取り除いた。
- **`ShippingPage` の `return;`** — undefined を描画すると React は何もレンダリングしない。
  配送先を読み込んでいる間、決済画面の上部が空白になっており、ユーザーは読み込み中なのか住所が
  ないのか分からなかった。"배송지를 불러오는 중…" に変えた。
- **ESLint を CI で有効にした — ラチェット方式で。** 今 `no-unused-vars` を error に上げると警告が
  198 個あるので CI が初日から赤くなり、そうなると人はルールに従って直す代わりに**ルールを無効にする。**
  逆に警告を出すだけで通すなら、それは飾りである。`npm run lint` が `--max-warnings 198` で
  現在の件数を固定するので、**新しく生じた警告が一つでもあれば CI が赤くなる。** 既存の 198 個は直すたびに
  その数を下げてロックする — 上がることはない。（ラチェットが実際に機能するか、警告を一つ
  わざと追加して確認した: 199 > 198 で止まる。）
- **`nginx.conf` の死んだプロキシブロック** — 消さずに**そうであると書いた。** cloudflared が
  `/*-service/` をゲートウェイに直接送るのでこのブロックには届かないが、cloudflared なしで
  nginx 一つで立ち上げる構成ではそのまま使われる。書いておかないと、次の人が「SSE は
  nginx がプロキシしている」と信じて、SSE が切れたときにここから調べてしまう。


---

### 19. チャットルームを作って最初のメッセージを送ると画面が落ちる ✅

ユーザー報告: *「チャットルームを作ってチャットを入力したら `Cannot read properties of undefined
(reading 'trim')` … 화면을 그리는 중 오류가 발생했습니다 の画面にばかりつながる。」*

`ChatContent.jsx` が日付の区切り線をこう作っていた:

```js
const splitDate = createdAt.toLocaleDateString().split('.');
return `${splitDate[0].trim()}년 ${splitDate[1].trim()}월 ${splitDate[2].trim()}일 ${day}`;
```

`toLocaleDateString()` は**ブラウザの言語**どおりに出力する。ドットで区切られるのは `ko-KR`
（"2026. 9. 18."）だけである — `en-US` は "9/18/2026"、`ja-JP` は "2026/9/18"。ドットがないので
`split('.')` は要素一つの配列になり、`splitDate[1]` は undefined である。その後の `.trim()` が
まさにそのエラーである。

**なぜ「最初のメッセージ」なのか。** 区切り線は*前のメッセージと日付が違うとき*に作る。最初のメッセージには
前がないので必ず作る。だからルームを作って最初の一行を打った瞬間が正確な再現条件であり、
韓国語のブラウザでだけ見えなかったバグである。日本語の画面を付けた後（docs/45）に表に出た。

さらに二重の問題があった:

- **時刻も切り取って作っていた。** `toLocaleTimeString()` の末尾 3 文字を取って秒を消していたが
  （"오후 3:24:05" → "오후 3:24"）、英語（"3:24:05 PM"）ではその 3 文字が " PM" なので、
  秒は残って午前/午後が消える。
- **サーバーが時刻のないメッセージを送りうる状態だった。** `ChatService.sendMessage` が
  `if (member != null)` の中でだけ id・`createdAt` を付けていた。会員のレプリカ行がないアカウント
  （docs/48 — メール認証を無効にした登録）は、**id なし、時刻なしで**メッセージが送られていた。

文字列を分割せずに、`Intl` に形式を**要求**する — 韓国語 "2026년 9월 18일 금요일"、
日本語 "2026年9月18日金曜日"、英語 "Friday, September 18, 2026" が同じコードから出てくる。
同じ日かどうかの判定も、ロケールの文字列ではなく `getFullYear/getMonth/getDate` で行う。
`createdAt` がなければ、時刻・日付なしで吹き出しだけを描画する。サーバーは送信者が誰であっても id・時刻を
付け、ニックネームだけは会員行から取るが、なければメールのローカルパートで代用する。

ガード: `ChatContent.test.jsx`（10 — 古いコードに戻して 6 個が失敗することを確認）・
`ChatServiceSendMessageTest`（4）。

## バックエンドに引き渡すもの（このリストで見つかったが、フロントの問題ではない）

- **10 番** — 注文/決済の金額をサーバーが再計算しているか。
- `gateway-server/application-local.yml` `chat-service-api` — `RemoveRequestHeader=Cookie` が
  `JwtAuthorization` の**前**にある。同じファイルの他のルートのコメントが「必ず後ろ」と明記して
  いる順序と逆である。今はブラウザがヘッダーでトークンを送っているので無害だが、Cookie の経路を使った瞬間に
  401 になる。
- `chat-service-api` の `uri: lb:ws://` — REST のルートが ws スキームである。動いているなら偶然である。→ ✅ 2026-10-16 `lb://` に。動いていたのは偶然だった — Eureka のインスタンスが https を知らせ ws が無視されていた(知らせないディスカバリーでは GET 400 · POST 405、ChatRouteTlsTest · RouteMatchingTest)。

---

## 手を付けなくてよいもの（確認済み）

読んでいて「これはバグではないか」と思ったが、そうではなかったもの。二度と疑わないように書いておく。

- `ChatPage.jsx` が STOMP CONNECT に `Authorization: <raw token>`（Bearer なし）を送っている —
  chat-service の `StompHandler` が raw トークンをそのまま検証する。一貫性はないが動作する。
- `SocialLogin.jsx` の `window.location.href = "/user-service/oauth2/…"` — API_BASE なしの
  相対パスだが、same-origin なので cloudflared がゲートウェイに送る。
- `Payment.jsx`（order）の `useReducer` の初期値 `loading: true` — 読む箇所がないので無害。
- `AuthContext` がトークンをメモリにだけ置いていること — **意図された設計**である（XSS 対策）。localStorage
  に移そうとしないこと。

---

## 順序の提案

1. **0-a・0-b・1・2・3** — それぞれ数行。今日中に終わる。ユーザーがすぐにぶつかるものである。
   0-a は設定一行で、結果を `ls dist/assets/images` ですぐに確認できる。
2. **6・8・9・11** — 1 ファイルずつ。半日。
3. **7** — `fetchUser` を生かせば 6 か所が一度に解決する。ただし、先にプロフィール API の応答の形を見る必要がある。
4. **4** — 最も大きな変更。認証失敗の意味を決める作業なので、別に時間を取る。これを先に
   触ると、残りをテストするときにログアウトが飛び出して邪魔になる。
5. **5・13・14** — その次。
6. 残りは目についたときに。
