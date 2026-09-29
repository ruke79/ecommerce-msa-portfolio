/**
 * 일본어 문구 — 영역 "shop" (docs/60 §2). 키 앞머리는 영역의 화면 이름으로 한다.
 *
 * <p>영역마다 파일을 나눈 이유: 한 파일(ko.js)에 모두 모으면 여러 사람이 동시에 고칠 때 늘 충돌한다. 합치는 곳은 ../ja.js 이고,
 * 같은 키가 두 파일에 있으면 i18n.test.jsx 가 실패한다.
 */
const shop = {
    // ── 상품 상세 — 비슷한 상품
    "product.similar.title": "似ている商品",

    // ── 상품 상세 — 리뷰 목록
    "product.review.title": "カスタマーレビュー（{{count}}）",
    "product.review.average": "平均評価",
    "product.review.none": "まだレビューはありません。",
    "product.review.login": "ログインしてレビューを書く",
    "product.review.card.size": "サイズ: {{size}}",
    "product.review.card.fit": "着用感: {{fit}}",
    "product.review.like": "いいね",
    "product.review.like.undo": "いいねを取り消す",
    "product.review.like.login": "ログインが必要です",
    "product.review.like.count": "いいね {{count}}",

    // ── 상품 상세 — 리뷰 걸러 보기·정렬(TableHeader). 값은 코드라 번역하지 않고 이름표만 번역한다.
    "product.review.filter.all": "すべて",
    "product.review.filter.stars": "星{{count}}つ",
    "product.review.filter.all_styles": "すべてのスタイル",
    "product.review.order.recommended": "おすすめ順",
    "product.review.order.newest": "新しい順",
    "product.review.order.oldest": "古い順",

    // ── 상품 상세 — 리뷰 쓰기. label·select 의 끝은 Select/TableSelect 의 text 값(size·style·fit·rating·order)이다.
    "product.review.label.size": "サイズ",
    "product.review.label.style": "スタイル",
    "product.review.label.fit": "着用感",
    "product.review.label.rating": "評価",
    "product.review.label.order": "並び順",
    "product.review.select.size": "サイズを選択",
    "product.review.select.style": "スタイルを選択",
    "product.review.select.fit": "着用感はいかがでしたか？",
    "product.review.select.rating": "評価を選択",
    "product.review.select.order": "並び順を選択",
    "product.review.fit.small": "小さめ",
    "product.review.fit.true_to_size": "ちょうどいい",
    "product.review.fit.large": "大きめ",
    "product.review.images.add": "画像を追加",
    "product.review.images.max": "画像は最大{{max}}枚までアップロードできます。",
    "product.review.images.unsupported": "{{name}} は対応していない形式です。JPEG、PNG、WEBP のみアップロードできます。",
    "product.review.images.too_large": "{{name}} のファイルサイズが大きすぎます。最大{{max}}までアップロードできます。",
    "product.review.placeholder": "レビューをご記入ください...",
    "product.review.submit": "レビューを投稿",
    "product.review.submitting": "投稿中…",
    "product.review.delete": "レビューを削除",
    "product.review.update": "レビューを更新",
    "product.review.delete_confirm": "レビューを削除しますか？削除したレビューは元に戻せません。",
    "product.review.error.submit_failed": "レビューを投稿できませんでした。しばらくしてから再度お試しください。",
    "product.review.error.delete_header": "レビューを削除できません",
    "product.review.error.delete_failed": "レビューを削除できませんでした。しばらくしてから再度お試しください。",
    "product.review.select.empty": "選択できる項目がありません。",
    "product.review.added.header": "レビューを投稿しました",
    "product.review.added.msg": "レビューをご投稿いただきありがとうございます。",
    "product.review.deleted.header": "レビューを削除しました",
    "product.review.deleted.msg": "レビューが削除されました。",
    "product.review.error.header": "レビューを投稿できません",
    "product.review.error.size": "サイズを選択してください。",
    "product.review.error.style": "スタイルを選択してください。",
    "product.review.error.fit": "着用感を選択してください。",
    "product.review.error.rating": "評価を選択してください。",
    "product.review.error.review": "レビュー内容を入力してください。",

    // ── 상품 목록 — 왼쪽 필터. 성별 값(Men 등)은 서버로 가는 질의 값이라 번역하지 않고 이름표만 번역한다.
    "browse.filter.clear_all": "すべてクリア（{{count}}）",
    "browse.filter.category": "カテゴリー",
    "browse.filter.sizes": "サイズ",
    "browse.filter.colors": "カラー",
    "browse.filter.brands": "ブランド",
    "browse.filter.styles": "スタイル",
    "browse.filter.materials": "素材",
    "browse.filter.gender": "性別",
    "browse.filter.gender.men": "メンズ",
    "browse.filter.gender.women": "レディース",
    "browse.filter.gender.unisex": "ユニセックス",

    // ── 상품 목록 — 결과
    "browse.list.empty": "条件に合う商品はありません。",
    "browse.list.load_more_failed": "商品を読み込めませんでした。",
    "browse.list.retry": "再試行",
    "browse.list.end": "全{{count}}件の商品をすべて表示しました。",

    // ── 장바구니 화면
    "cart.page.title": "ショッピングカート",
    "cart.page.save_failed": "カートを保存できませんでした。もう一度お試しください。",
    "cart.page.update_failed": "カートを更新できませんでした。もう一度お試しください。",
    "cart.page.remove": "カートから削除",

    // ── 기획전
    "event.title": "特集",
    "event.loading": "読み込み中…",
    "event.load_failed": "特集を読み込めませんでした。",
    "event.list.empty": "開催中の特集はありません。",
    "event.audience.new_user": "新規会員限定",
    "event.audience.existing_user": "既存会員限定",
    "event.detail.starts_at": "{{from}} 開始",
    "event.detail.not_found": "終了したか、見つからない特集です。",
    "event.detail.coupon": "{{discount}}%割引クーポン",
    "event.detail.claim": "クーポンを受け取る",
    "event.detail.claiming": "受け取り中…",
    "event.detail.claimed": "受け取り済み",
    "event.detail.claimed_toast": "クーポンを受け取りました。",
    "event.detail.login": "ログインするとクーポンを受け取れます。",
    "event.detail.empty": "この特集には商品がありません。",
};

export default shop;
