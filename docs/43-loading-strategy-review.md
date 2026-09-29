# 43. データローディング戦略の点検 — プロフィール画面 · ホーム画面 · 全体

**日本語** | [한국어](43-loading-strategy-review.ko.md)

> 🕰️ **状態（2026-09-27）— 過去の記録。** 17 件すべて直しました（一番上の進捗表）。下の「要約」表の「未着手」は作成当時のものです。

調査範囲: フロントエンド全体のデータローディング経路（ルーター loader、React Query、useEffect fetch）と
それに対応するバックエンドのエンドポイント。2026-09 時点。

この文書は**確認したことだけ**を書く。確認できなかったことは「未確認」と明記する。

---

## 進捗状況（2026-09-15）

| 項目 | 状態 | コミット |
|---|---|---|
| A1 `/profile` 本文なし | ✅ 完了 | `a5ad644` |
| A2 プロフィール画像の変更不可 | ✅ 完了 — GET/PUT/POST/DELETE を追加 | `a5ad644` |
| A3 サイドバーの `user.image`/`username` | ✅ 完了 — Layout がプロフィールを受け取り 5 つのタブに渡す | `f653e80`, `a5ad644` |
| B1 `findByActive` の全体照会 | ✅ 完了 — 安全でないメソッドを除去したので、戻すとコンパイルが通らない | `0ed948e` |
| B2 住所削除の IDOR | ✅ 完了 | `0ed948e` |
| B3 個人情報の複合インデックス | ✅ 完了 — V7 で DROP | `0ed948e` |
| B4 `@Valid` なし | ✅ 完了 | `0ed948e` |
| B5 builder+deepCopy の重複など | ✅ 完了 — マッピングを `ShippingAddress.from` 1 つに集約し、ガードテスト | |
| C1 グリッドがカルーセルを覆う | ✅ 完了 — 重なりをバナーの高さに比例させた | `8d8e469` |
| C2 バナーの読み込み順 | ✅ 完了 — loader へ移動 + 高さを予約 | `8d8e469` |
| D1 カーソルページングをフロントが呼ばない | ✅ 完了（商品・注文の 2 画面） | `2956937` |
| D2 管理者の商品一覧の全件ロード | ✅ 完了 — 管理者専用経路への移動まで | `2956937` |
| D3 注文一覧が無制限 | ✅ 完了 — N+1 と null の混入も一緒に | `2956937` |
| D4 検索レスポンスごとにカテゴリ全体 | ✅ 完了 — 最初のページでだけ受け取る | このコミット |
| D5 ローディング状態の表示 | ✅ 完了 — グローバル進捗バー + フィルタ中のぼかし + スケルトン | |
| E1 ハンバーガーメニューのハードコーディング | ✅ 完了 | `7ed471e` |
| E2 メニューの静的な "Hello, sign in" | ✅ 完了 | `7ed471e` |
| E3 Amazon の商標 | ✅ 完了 — ガードテストを含む | `7ed471e` |

---

## 要約

| # | 症状 / リスク | 深刻度 | 状態 |
|---|---|---|---|
| A1 | `/profile` の本文に何もない — 作られたことがない | 高（機能なし） | 未着手 |
| A2 | プロフィール画像の変更機能がフロント・バックの両方にない | 中 | 未着手 |
| A3 | サイドバーが `user.image` / `user.username` を読むが、どちらも存在しないフィールド | 高 | 未着手 |
| B1 | **`findByActive(true)` が全テーブル照会だ — 他人のデフォルト配送先をオフにする** | **致命的** | 未着手 |
| B2 | **住所削除に所有権チェックがない（IDOR）** | **致命的** | 未着手 |
| B3 | 名前+住所全体に張られた、誰も使わない複合インデックス | 中（個人情報） | 未着手 |
| B4 | `@Valid` なし → 検証が Hibernate の flush 時点まで遅れる | 中 | 未着手 |
| C1 | ホーム: 商品グリッドがカルーセルを覆う（固定の負のマージン + 可変の高さ） | 中（UI） | 未着手 |
| C2 | ホーム: バナーをマウント後に取得するので画像が 2 回変わる → レイアウトジャンプ | 中（UI） | 未着手 |
| D1 | **バックエンドにカーソルページングがすでにあるのに、フロントが 1 か所も呼んでいない** | 高 | 未着手 |
| D2 | 管理者の商品一覧がカタログ全体を一度に受け取る | 高 | 未着手 |
| D3 | `GET /order/orders` が注文全体を無制限に返す | 高 | 未着手 |
| D4 | `/api/search` のレスポンスごとにカテゴリ・サブカテゴリ全体が載ってくる | 中 | 未着手 |
| D5 | ローディング状態の表示がほとんどない — ルーター loader が画面遷移を丸ごとブロックする | 中 | 未着手 |
| E1 | ハンバーガーメニューが 100% ハードコーディング — 実際のカテゴリを 1 件も読んでいない | 高 | 未着手 |
| E2 | メニューに `Hello, sign in` が静的な文字列として埋め込まれている（ログインしてもそのまま） | 中 | 未着手 |
| E3 | **公開デプロイされたサイトに Amazon のロゴ・商標・規約の文言がそのまま残っている** | **法的リスク** | 未着手 |

`/browse` の無限スクロールは、この調査の途中で別途対応した（コミット `0c30f23`）。

---

## A. プロフィール画面

### A1. `/profile` の本文はタイトルだけだ

`frontend/src/pages/profile/profile.jsx`:

```jsx
<Layout user={user} tab={0} title={`${user.email}'s Profile`}>
    <div className="text-center">
        <h2 className="text-4xl font-bold mb-6">My Profile</h2>
    </div>
</Layout>
```

本文に `<h2>` が 1 つあるだけだ。**バグではなく、作られたことがない。** loader は `tab` の値を
計算して返すが、コンポーネントでそれを使う行がコメントアウトされている。

同じ `Layout` を使う他のタブ（`address` · `orders` · `payment` · `security`）には内容がある。
`/profile` だけが殻だ。

### A2 / A3. プロフィール画像

`components/profile/sidebar/Sidebar.jsx:9`:

```jsx
<img src={data.image} alt={data.username} ... />
```

`data` は `useAuthContext().user` で、その値は `util/jwtUser.js` の `toUser()` が作る:

```js
export function toUser(decodedToken) {
  return {
    email: decodedToken?.sub,
    userId: decodedToken?.uid ?? null,
    roles: normalizeRoles(decodedToken),
  };
}
```

**`image` も `username` もない。** そのため

- `<img src={undefined}>` → React が `src` 属性をまるごと外してレンダリング → 壊れた画像の枠
- `<span>{data.username}</span>` → 空欄

ユーザー名が消え、アバターが壊れて見える原因はこれだ。アイコンの 404 とは無関係だ。

バックエンド側:

- `User.image` カラムと `UserProfileDto.image` は**ある**
- ところが `UserController` のエンドポイントは `/address` · `/save_address` · `/select/{id}` ·
  `/delete/{id}` · `/payment` · `/changepm` · `/update-password` がすべてだ
- **プロフィールを丸ごと返す GET がなく、画像を変えるエンドポイントもない**
- `user-service` に `ImageService` はあるが、レビュー画像用だ

つまり「プロフィール画像を変更できない」のは UI が抜けているのではなく、**経路そのものがない**。

### 直すには

1. `GET /api/user/profile` を追加 → `UserProfileDto.toDto(user, roles)`（すでにある）
2. フロントに `useFetchProfile()` フックを追加し、`Layout` に JWT ではなくこのレスポンスを渡す
   — JWT は身元の証明であってプロフィールの保存場所ではない。名前を変えてもトークンはそのままだ
3. `Sidebar` に画像がないときのフォールバック（イニシャルの円など）を追加 — 値がない状態は正常だ
4. 画像の変更は `POST /api/user/profile/image`（multipart）→ `ImageService` を再利用

---

## B. 住所の保存 — 実務の観点

`user-service/.../service/AddressService.java` を読んだ結果。2 つは**今すぐ直さなければならない。**

### B1. 🔴 `findByActive(true)` にはユーザーの範囲がない

`ShippingAddressRepository`:

```java
Optional<ShippingAddress> findByActive(boolean active);
```

`selectShipAddress()` の中で:

```java
ShippingAddress currActiveAddress = shippingAddressRepository.findByActive(true).orElse(null);
if (currActiveAddress != null && !Objects.equals(currActiveAddress.getShippingAddressId(), ...)) {
    currActiveAddress.setActive(false);
    shippingAddressRepository.save(currActiveAddress);
}
```

このクエリは `where active = true` だ。**`user_id` の条件がない。** 結果:

1. ユーザーが 2 人以上デフォルト配送先を持った瞬間、`Optional` で宣言されたクエリが 2 行に出会い、
   Spring Data が `IncorrectResultSizeDataAccessException` を投げる →
   コントローラの `catch (RuntimeException)` に引っかかって **400 "배송지를 선택할 수 없습니다"**。
   今はユーザーが事実上 1 人なので表に出なかっただけで、2 人目のユーザーが配送先を選んだ
   瞬間に再現する。
2. 例外が出る前でも — つまり全体で active がちょうど 1 つのとき — その 1 つが**他人のもの**なら、
   他人のデフォルト配送先をオフにしてしまう。

直し方: `Optional<ShippingAddress> findByUser_UserIdAndActiveTrue(Long userId)`。
DB 側にも部分ユニークインデックスを張るのが正しい:

```sql
CREATE UNIQUE INDEX uq_shipping_address_active_per_user
    ON users.shipping_address (user_id) WHERE active;
```

（新しい Flyway バージョンファイルで。すでに適用されたマイグレーションは絶対に修正しない — チェックサムが壊れる。）

### B2. 🔴 住所削除に所有権チェックがない

```java
public void deleteShippingAddress(String username, String addressId) {
    Optional<User> user = userRepository.findByUsername(username);
    if (!user.isPresent()) return;

    Optional<ShippingAddress> deleteAddress = shippingAddressRepository.findById(Long.parseLong(addressId));
    if (deleteAddress.isPresent()) {
        user.get().getShippingAddresses().remove(deleteAddress.get());
        shippingAddressRepository.delete(deleteAddress.get());   // ← 他人のものでも消す
    }
}
```

`user` を照会はするが、**その住所がこのユーザーのものかを確認しない。**
`remove(...)` は他人のものなら何もせず静かに通り過ぎ、その次の行の `delete()` が
そのまま実行される。ログインした誰でも、住所の id を 1 つ知っていれば他人の配送先を消せる（IDOR）。

TSID なので id の推測が難しいというのは緩和要因であって対策ではない。`selectShipAddress` は（方式は
非効率だが）ユーザーの住所一覧を巡回して確認している — 削除だけが抜けていた。

直し方:

```java
ShippingAddress target = shippingAddressRepository.findById(Long.parseLong(addressId))
        .filter(a -> Objects.equals(a.getUser().getUserId(), user.get().getUserId()))
        .orElseThrow(() -> new AccessDeniedException("본인의 배송지가 아닙니다."));
```

`selectShipAddress` も `findByUser_UserIdAndShippingAddressId(...)` 1 回に減らすのが正しい。

### B3. 名前 + 住所全体に張られた複合インデックス

`ShippingAddress`:

```java
indexes = { @Index(columnList = "firstname, lastname, address1, city, state, zipCode",
                   name = "idx_shipping_address") }
```

このテーブルに実際に発行されるクエリは `findByUser_UserId`、`findById`、`findByActive` の 3 つだけだ。
**このインデックスを使うクエリが 1 つもない。** 代わりに:

- 書き込みのたびに 6 カラムのインデックスを更新する
- 個人情報（実名 + 住所全体）を並べ替えた形でもう一度複製しておく。DB ダンプが流出すると
  「この人の住所を探す」がインデックススキャン 1 回で済んでしまう

実務では**消すのが正しい。** 本当に必要なインデックスは `user_id` だ（`@ManyToOne` の FK に
自動では作られない — PostgreSQL は FK にインデックスを作らない）。

### B4. `@Valid` がない

```java
ResponseEntity<?> saveShippingAddress(@RequestBody AddressRequest request, ...)
```

`AddressRequest` · `AddressDto` には制約が 1 つもなく、`@NotBlank` / `@Size` は**エンティティ**に
付いている。そのため検証がコントローラへの進入時点ではなく **Hibernate の flush 時点**まで遅れる。
結果として 400 は返るが、メッセージが Hibernate の `ConstraintViolationException` のダンプだ。
DTO に制約を移して `@Valid` を付けるのが正しい。

### B5. その他 ✅

**① builder + deepCopy の重複** — `ShippingAddress.builder()...build()` で全フィールドを埋めた
**直後に** `deepCopyShippingAddress()` が同じフィールドを再び上書きしていた。単なる重複に見えるが
罠がある: 後で**ビルダーにだけ**フィールドを追加すると、deepCopy がそれを `null` で上書きする。
コンパイルも通り、そのフィールドを見ないテストはすべて通る — 新しい住所項目が 1 つ静かに
消える形だ。

どちらか一方を消すだけでは足りなかった。消す側を誤って選ぶと、次の人がまた対を
合わせてしまう。マッピングを **`ShippingAddress.from(AddressDto)` 1 つ**に集め、リフレクションで
「エンティティのフィールドのうち from が移さないもの」を探すテストを付けた。フィールドが増えたのにマッピングを
直さなければ、そこで先に止まる。（実際に `zipCode` のマッピングを消してみて、テストが捕まえることを
確認した。）

**② 不要な `save`** — `userRepository.save(user); // prevent java.stackoverflow`。
コメントの説明は**事実ではない。** `user` はすぐ上で `findByUsername` で読んだ永続
状態のエンティティで、このクラスは `@Transactional` なので、変更はコミット時点でダーティチェックにより
反映される。save は何もしないのにクエリだけをもう 1 回発生させる。スタックオーバーフローとは
関係ない — それは双方向の関連をシリアライズするときに出る症状で、`@JsonBackReference` がすでに
防いでいる。

**③ ログの個人情報** — ⚠️ **この文書の元の指摘は不正確だった。** 「username がメールアドレスだ」
と書いてあったが、確認してみるとそうではない: フォームでの登録はユーザーが選んだ名前
（`UserServiceImpl:411`）、ソーシャル登録は `provider_providerId`
（`CustomOAuth2UserService:120`）だ。メールアドレスは別のカラムだ。

それでも直した。メールアドレスではないだけで**その人を特定する値**であり、配送先の保存・照会は頻繁に
起きるので INFO の 1 行がそのまま溜まる。セキュリティ通知・デバイス認識の経路が `userId` だけを残すのと
基準を揃えた。ユーザーが見つからなかったときに username を残していた `log.warn` も消した — そうしないと
「そのアカウントが存在するか」がログから漏れる。

### B6. 問題の**ない**もの

注文は配送先を**スナップショットとしてコピー**する（`OrderDataService:198-211` → `OrderAddress`）。
そのため住所を後で修正したり削除したりしても、過去の注文の配送先は変わらない。
これは実務的に正確な設計だ。（逆に FK で参照していたら、住所の削除が注文履歴を壊す。）

---

## C. ホーム画面

### C1. 商品グリッドがカルーセルを覆う

`pages/home/HomeSections.jsx:38`:

```jsx
<div className="relative grid md:grid-cols-4 ... -mt-16 sm:-mt-32 md:-mt-48 lg:-mt-80 z-10 ...">
```

上部の GRID セクションを**固定ピクセルの負のマージン**で引き上げ、カルーセルの上に重ねている（`lg:-mt-80` = -20rem
= -320px）。意図は Amazon 風の「バナーの下部に商品グリッドがかかる」レイアウトだ。

問題は**カルーセルの高さが可変**である点だ。バナーは `<img>` を幅 100% で描画するので、高さは
画像のアスペクト比 × ビューポート幅で決まる。負のマージンは固定、カルーセルの高さは可変 →
ブレークポイントと実際の画像比率がずれる区間で、グリッドが**バナーの真ん中まで**上がってくる。

直し方（3 つのうち 1 つ）:

- カルーセルのコンテナに `aspect-ratio` を固定し、負のマージンをその比率に合わせて計算
- 負のマージンの代わりにカルーセルを `position: relative`、グリッドを `position: absolute; bottom: 0` で
  バナーの**内側**に配置（重なり幅がバナーの高さに自動で追従する）
- 重ねるのを諦めて `mt-0` — 最も単純で壊れる余地がない

### C2. バナーがマウント後に変わり、レイアウトがジャンプする

`components/home/CarouselContainer.jsx`:

```js
const [banners, setBanners] = useState(FALLBACK);   // バンドルに埋め込まれた画像5枚

useEffect(() => {
    const loaded = await getBanners();              // マウント**後に**リモートのバナーを取得する
    if (...) setBanners(loaded);
}, []);
```

一方、商品セクションは**ルーター loader** が取得する。ルーターは loader が終わるまで画面遷移を
ブロックするので、最初のペイント時点で `home.sections` は**すでにある。**

そのため順序がこうなる:

1. loader 完了 → ページをレンダリング。商品画像が即座にリクエストされる
2. カルーセルはバンドルのフォールバック画像で描かれる（バンドルなので速い）
3. `getBanners()` のレスポンス到着 → **画像がリモート URL に丸ごと置き換わる** → まだ読み込まれていない画像なので
   高さが 0 に潰れてから再び大きくなる → 下全体が上下に跳ねる

「商品画像が動く画像より先に読み込まれて不自然だ」の正確なメカニズムだ。
フォールバックを置く判断自体は正しい（最初の画面が空だとサイトが壊れているように見える）。問題は
**置き換えがレイアウトの高さを変える**ことだ。

直し方:

1. **バナーをホームの loader に移す** — `Promise.allSettled([getHome(), getBanners()])`。
   そうすれば最初のペイントに最終的な画像が入り、置き換え自体がなくなる。失敗したらフォールバックを使う
2. バナーの `<img>` に `width`/`height`、またはコンテナに `aspect-ratio` を与えて**高さを予約**する。
   これだけでも置き換え時のジャンプは消える
3. 1 枚目に `fetchpriority="high"` + `loading="eager"`、残りに `loading="lazy"`。
   今は商品画像とバナーが同じ優先度で競合している

---

## D. 全体のローディング戦略 —「一度に全部受け取る」

ユーザーの指摘は正確だ。**段階的ローディングが適用された画面は（今直した `/browse` を除けば）ない。**

### D1. 🔴 バックエンドにカーソルページングがすでにあるのに、フロントが呼ばない

バックエンドにあるもの:

| メソッド / エンドポイント | 位置 |
|---|---|
| `getOrdersByCustomerIdWithCursor(customerId, cursorId, size)` | `OrderService:116`, `OrderDataService:138`, `OrderCacheService:108` |
| `getOrdersByCustomerIdWithPagination(customerId, page, size)` | `OrderService:102` ほか 2 か所 |
| `GET /api/product/products/page?category=&cursorId=&pageSize=` | `ProductController:101` |
| `CursorPagenation<T>` ユーティリティ | `catalog-service/util/CursorPagenation.java` |

フロントでこれらを呼ぶコード: **0 件**（`grep -rn "cursorId\|products/page" frontend/src` → なし）。

`useInfiniteScroll` が定義だけあって誰も使っていなかったのと**まったく同じパターン**だ。段階的ローディングの
基盤は 3 層（Service / DataService / CacheService）にわたって全部作っておきながら、画面に接続して
いなかった。今は死んだコードで、いつでも「あるのだから動くだろう」と誤解されかねない。

### D2. 🔴 管理者の商品一覧がカタログ全体を一度に受け取る

`frontend/src/pages/admin/ProductList.jsx:19`:

```js
getRequest("/catalog-service/api/product/products")
```

→ `ProductController:84` → `productService.warmUpProductCaches(20)` →

```java
@Cacheable(value = "products", key = "'warmup'")
public List<ProductDto> warmUpProductCaches(int pageSize) {
    List<Category> allCategories = categoryRepository.findAll();
    for (Category category : allCategories) {
        for (Subcategory subcategory : subCategoryRepository.findByCategory(category)) { ... }
    }
}
```

名前は「キャッシュウォーミング」なのに画面の一覧照会に使われ、**全カテゴリ × 全サブカテゴリを回って
すべての商品を 1 つの配列で**返す。しかも `@Cacheable(key = "'all'")` / `"'warmup'"` なので、
**カタログ全体が Redis のキー 1 つ**に入る。

今は商品が 250 個なので耐えている。規模が大きくなると:

- レスポンス JSON が数 MB → ゲートウェイのタイムアウト、ブラウザのメモリ
- Redis のキー 1 つが数十 MB → `maxmemory` ポリシーに応じて他のキーを押し出すか、このキーが evict されて
  毎回カタログ全体を照会し直す
- `ProductService:153` の `getProducts()` も同じ `findAll()` だ

`/product/products/page` がすでにあるので、**画面をそちらに移せばよい。**

### D3. 🔴 注文一覧が無制限だ

`GET /order-service/api/order/orders?filter=` → `getOrders(email, filter)` →
ページングの引数がない。注文が溜まるほどレスポンスが大きくなる。`/profile/orders` 画面はそれを
そのまま全部描画する。

すぐ隣に `getOrdersByCustomerIdWithCursor` があるのに使われていない（D1）。

### D4. 検索レスポンスごとにカテゴリ全体が載ってくる

`SearchService:149-150`:

```java
List<CategoryDto> categoryDtos = categoryRepository.findAll()...
List<SubCategoryDto> subCategoryDtos = subCategoryRepository.findAll()...
```

`/api/search` は**ページリクエストのたびに**カテゴリ・サブカテゴリ全体を一緒に返す。
無限スクロールを付けた今はこれがより目立つ — 2 ページ目、3 ページ目を受け取るときもまったく同じ
フィルタメニューのデータが繰り返し来る（フロントは 1 ページ目のものだけを使い、残りは捨てる）。

フィルタメニューは `GET /api/search/filters` のように分離し、別途キャッシュするのが正しい。

### D5. ローディング状態がほとんどない ✅

**🔴 核心は「覆わない」ことだった。** このアプリにはすでに全画面オーバーレイ（`DotLoaderSpinner`）が
あるのに、呼び出し箇所の**大半でコメントアウトされていた。** それなりの理由がある — 画面を覆うと
読んでいた内容が消え、短い遷移でもちらつく。だから皆オフにしてしまい、結果として**何の
表示もない状態**が残った。同じオーバーレイを再び有効にするのは答えではない。

覆わない 3 つを入れた。

**① グローバル進捗バー（`NavigationProgress`）** — `Root.jsx` にあった
`{/* {navigation.state === 'loading' && <p>Loading...</p>} */}` の場所だ。ルーターが loader を
待つ間、上部に細いバーを描く。データルーターはローダーが終わるまで **URL も変え
ないので**、これがないと `/browse` でフィルタを押してもクリックが効いたのかすら分からない。

⏱️ **200ms 待ってから表示する。** キャッシュされた遷移は数十 ms で終わり、そのときバーを出すと
ちらつきだけが残ってかえって画面が不安定に見える — ないよりも悪い。`submitting` も一緒に
見る（form の送信もユーザーにとっては同じ待ち時間だ）。`role="status"` + `aria-live` で
スクリーンリーダーにも知らせる。

**② フィルタ変更中は一覧をぼかす** — `/browse` でフィルタを変える間、画面に残っている
一覧は**変わる前の結果**だ。消したりスケルトンに変えたりはしない（読んでいたものが消え、
レイアウトが跳ねる）。ぼかしてクリックだけを防げば「もうすぐ変わる値」という意味が伝わりつつ、内容は
残る。`aria-busy` も一緒に立てる。

**③ スケルトン（`Skeleton` · `ProductGridSkeleton`）** — 1 か所もなかったものを入れた。
スケルトンの要点は待ち時間を隠すことではなく、**場所を確保しておく**ことだ。実際の内容と同じ
大きさを占めるので、データが届いても何も動かない。見せるべき以前の結果がない
**最初の進入時にだけ**使う。

**アクセシビリティ** — 2 つのアニメーションとも `prefers-reduced-motion: reduce` で止まる。動きに
敏感なユーザーにとって無限に繰り返すアニメーションは不快を超えて症状を誘発し得るし、静止した灰色の
ブロックでも意味は十分に伝わる。

### D6. 問題の**ない**もの

- 管理者画面は `React.lazy` でコード分割されている（`App.jsx:38-46`）。一般ユーザーは
  管理者のバンドルを受け取らない — これはきちんとできている
- `GET /api/products/info?ids=...` は `MAX_BATCH_IDS = 100` で上限がある

---

## E. ハンバーガーメニュー（`pages/header/MenuSidebar.jsx`）

### E1. カテゴリ一覧がすべてハードコーディングだ

ファイル 101 行全体に**データを読むコードが 1 行もない。** `useSelector(selectMenu)` で
開閉状態だけを読み、一覧は JSX に埋め込まれている:

```jsx
<h3>Shop By Department</h3>
<ul className="border-b pb-2">
    <li className="group">Electronics <ChevronRightIcon /></li>
    <li className="group flex">Computers <ChevronRightIcon /></li>
    <li className="group">Smart Home <ChevronRightIcon /></li>
    <li className="group">Arts & Crafts <ChevronRightIcon /></li>
</ul>
```

`<li>` に `onClick` も `<Link>` もない — **クリックしても何も起きない。**
実際のカテゴリ（`books`、`fashion` など、`product.category` 10 件）とは何の関係もない。

使えるエンドポイントはすでにある:

- `GET /catalog-service/api/category/categories`（`CategoryController:116`）
- `GET /catalog-service/api/category/product/allsubcategories`（`CategoryController:151`）
- `GET /catalog-service/api/category/product/subcategories?category=`（`CategoryController:177`）

そして各項目は `/browse?category=<id>` にリンクすればよい — `/browse` の `categoryHandler`
がすでにそのパラメータを処理している。

メニューはホームからすぐ開くので、**ルーター loader ではなく React Query で**取得するのが正しい
（`staleTime` を長く — カテゴリはほとんど変わらない）。メニューを最初に開いたときに取得すれば
（`enabled: menuOpen`）、最初の画面のローディングに負担をかけない。

### E2. `Hello, sign in` が静的な文字列だ

`MenuSidebar.jsx:27`:

```jsx
<b className="text-xl font-bold ml-3">Hello, sign in</b>
```

ログインしているかどうかを見ない。ログインしても "sign in" と表示され続け、リンクもない。

ヘッダの `Account.jsx:47` は**きちんとできている**:

```jsx
<p className="text-xs text-slate-300">Hello, {user ? user?.email : "sign in"}</p>
```

つまり同じ画面の中で、ヘッダはログイン状態を反映し、メニューは反映していない。
`useAuthContext()` を同じように使えばよい。

### E3. ⚠️ Amazon の商標・ロゴ・規約の文言が公開サイトにそのまま残っている

このプロジェクトは Amazon クローンのテンプレートから出発しており、**ブランドの文字列が置き換えられないまま
`www.ysyproject.com` にデプロイされている。** 確認したもの:

| 位置 | 内容 |
|---|---|
| `pages/user/SignInPage.jsx:6,196` | `assets/images/amazon-dark.png` のロゴをログイン画面に表示 |
| `pages/user/RegisterPage.jsx:5,131` | 同じロゴ |
| `pages/user/ForgotPage.jsx:4,75` | 同じロゴ |
| `SignInPage.jsx:289`, `RegisterPage.jsx:211` | "By continuing, you agree to **Amazon's Conditions of Use and Privacy Notice**." |
| `SignInPage.jsx:341,347` | "New to Amazon?" / "Create your Amazon account" |
| `pages/Footer.jsx:4` | `amazon-logo.png` |
| `pages/Footer.jsx:24-46` | "About Amazon", "Amazon Devices", "Amazon Science", "Sell products on Amazon", "Amazon Payment Products" … |
| `MenuSidebar.jsx:37,41,81` | "Amazon Music", "Amazon Appstore", "Amazon Live" |
| `components/product/ShippingInfo.jsx:59` | 販売者/配送業者の名前が "Amazon" |

ポートフォリオ用のローカルデモならよくあることだが、今は**公開ドメインで稼働しており、PayPal の実決済が
接続されている。** 特に問題になる組み合わせは:

- 他社の**ロゴをアカウント作成・ログイン画面で**使っていること（出所を誤認させる）
- **「Amazon の利用規約に同意する」**と書いてあること — 事実ではない規約の告知だ。
  実際に適用される規約が何なのか、ユーザーには分からない
- 決済画面まで続くフローの中にあること

技術的な欠陥ではないが、**デプロイ状態では上の一覧の中で最初に対処すべき項目**だ。
ロゴ 3 枚の差し替え + 文字列の置換で済む作業で、コード構造を変える必要はない。

---

## 推奨順序

1. **E3** — 公開デプロイ中だ。コード作業ではなくロゴ・文字列の差し替えで、最も早く終わる
2. **B1, B2** — データ整合性と認可の問題だ。ユーザーが 2 人以上になった途端に破綻する
3. **C2**（バナーを loader へ + 高さの予約）— 小さく、第一印象に直接影響
4. **E1, E2**（メニューを実際のカテゴリに + ログイン状態の反映）— エンドポイントがすでにある
5. **D2**（管理者の商品一覧を `/products/page` へ）— エンドポイントがすでにある
6. **D3 + D1**（注文一覧のカーソルページング）— サービス層がすでにある。コントローラと画面だけ
7. **A1〜A3**（プロフィール画面）— 新しいエンドポイントが必要だ。上より規模が大きい
8. **C1**（重なりレイアウト）、**D4**、**D5**、**B3〜B5**
