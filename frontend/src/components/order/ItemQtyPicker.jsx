import { translate, useCurrentLang } from "i18n/I18nProvider";

/**
 * 품목별 수량 선택 — 부분 환불·부분 반품의 공통 조각.
 *
 * <p>줄마다 {@code 0 … max} 의 select 하나. 기본값은 {@code max}(남은 전부)라 아무것도 건드리지
 * 않으면 전액·전량이다. 금액은 그리지 않는다 — 부분 환불액은 할인·배송비를 반영해 서버가
 * 정하고, 여기서 단가 × 수량을 보여 주면 그 값과 어긋나 "왜 적게 돌아왔냐" 가 된다.
 *
 * @param lines   [{ id, name, size, max }]
 * @param value   { [id]: qty }
 * @param onChange (next) => void
 * @param label   접근성 라벨 접두어 — "환불 수량" · "입고 수량"
 *
 * <p>[JA] 品目ごとの数量選択 — 部分返金 · 部分返品の共通部品。
 * 行ごとに 0 … max の select 一つ。既定値は max(残り全部)なので何も触らなければ全額 · 全量である。
 * 金額は描かない — 部分返金額は割引 · 送料を反映してサーバーが決め、ここで単価 × 数量を見せるとその値と食い違い
 * 「なぜ少なく戻ってきたの」になる。
 * label: アクセシビリティラベルの接頭語 — 「返金数量」 · 「入庫数量」
 */
export default function ItemQtyPicker({ lines, value, onChange, label, disabled }) {
    // 관리자 화면(pages/admin/Refunds)도 쓰는 공통 조각이라 Provider 가 없어도 그려져야 한다 —
    // useTranslation 은 Provider 밖에서 던진다. 언어는 useCurrentLang 으로 구독해(바뀌면 다시 그려진다)
    // 그리는 순간 translate 로 꺼낸다.
    // [JA] 管理者画面(pages/admin/Refunds)も使う共通部品なので Provider がなくても描けなければならない —
    //      useTranslation は Provider の外で例外を投げる。言語は useCurrentLang で購読し(変われば描き直される)
    //      描く瞬間に translate で取り出す。
    useCurrentLang();
    if (!lines || lines.length === 0) return null;
    return (
        <ul className="mt-2 space-y-1 text-xs text-slate-800">
            {lines.map((line) => (
                <li key={line.id} className="flex items-center justify-between gap-2">
                    <span className="truncate">
                        {line.name}{line.size ? ` · ${line.size}` : ""}
                        <span className="ml-1 text-slate-500">{translate("refund.qty_remaining", { count: line.max })}</span>
                    </span>
                    <select
                        aria-label={`${label} ${line.name}`}
                        disabled={disabled || line.max === 0}
                        value={value?.[line.id] ?? line.max}
                        onChange={(e) => onChange({ ...value, [line.id]: Number(e.target.value) })}
                        className="rounded border border-slate-300 p-1"
                    >
                        {Array.from({ length: line.max + 1 }, (_, n) => (
                            <option key={n} value={n}>{n}</option>
                        ))}
                    </select>
                </li>
            ))}
        </ul>
    );
}

/** 선택을 서버 본문의 items 로 — 0 은 뺀다. 전부가 max 면 null(= 남은 전부, 서버 기본). */
// [JA] 選択をサーバー本文の items へ — 0 は除く。すべてが max なら null(= 残り全部、サーバー既定)。
export function toItems(lines, value) {
    const items = lines
        .map((line) => ({ orderProductId: line.id, qty: value?.[line.id] ?? line.max }))
        .filter((i) => i.qty > 0);
    const all = lines.every((line) => (value?.[line.id] ?? line.max) === line.max);
    return all ? null : items;
}

/** 선택된 수량이 하나라도 있는가. 품목 정보가 없는 옛 주문은 고를 것이 없으니 참이다. */
// [JA] 選んだ数量が一つでもあるか。品目情報のない古い注文は選ぶものがないので真である。
export function anySelected(lines, value) {
    if (!lines || lines.length === 0) return true;
    return lines.some((line) => (value?.[line.id] ?? line.max) > 0);
}
