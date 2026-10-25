/**
 * 화면 안에서 고르는 검색(채팅방 이름 등)의 비교 키 — NFKC + 공백 제거 + 소문자.
 *
 * 일본어 IME 는 전각 영숫자(`ＶＩＰ`) · 반각 카나(`ｼｬﾂ`)를 쉽게 낸다. NFKC 로 맞추면 `vip` · `シャツ` 로 만든 방을 찾는다.
 * 비교에만 쓴다 — 화면에는 원문을 보여 준다(서버의 `common.text.Nfkc` · catalog `product.search_key` 와 같은 규칙).
 * 공백을 지우는 것은 예전 규칙 그대로("신상 문의"를 "신상문의"로 찾는다).
 *
 * [JA] 画面内の検索(チャットルーム名など)の比較キー — NFKC + 空白除去 + 小文字。全角英数 · 半角カナでも同じ部屋を見つける。
 */
export const searchKey = (value) => String(value ?? '').normalize('NFKC').replace(/\s/g, '').toLowerCase();
