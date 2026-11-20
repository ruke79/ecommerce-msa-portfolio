import { ChevronDownIcon } from "@heroicons/react/24/outline";

import { SUPPORTED, useTranslation } from "i18n/I18nProvider";

/**
 * 언어 전환.
 *
 * <p>🐛 <b>예전 이 컴포넌트는 동작하지 않는 껍데기였다.</b> Amazon 클론에서 딸려 온
 * 목업으로, 라디오 버튼에 {@code name} 도 {@code onChange} 도 상태도 없었고
 * ({@code defaultChecked} 만 박혀 있었다), 목록에 있는 언어(español·العربية·Deutsch)는
 * 이 사이트에 존재한 적이 없다. "Learn more" 는 {@code href=""} 라 <b>현재 페이지를 다시
 * 불러오는</b> 링크였다.
 *
 * <p>즉 사용자에게는 "언어를 바꿀 수 있다"고 말해 놓고 누르면 아무 일도 일어나지 않았다.
 * 없는 기능보다 나쁘다 — 없으면 찾지라도 않는다.
 *
 * <p>이제 실제로 바꾼다. 지원하는 언어만 보여 주고, 지금 언어를 표시하고, 고르면 즉시
 * 반영되며 선택은 기억된다.
 *
 * <p>[JA] 言語切り替え。🐛 <b>以前このコンポーネントは動かない抜け殻だった。</b>Amazon クローンから付いてきたモックアップで、ラジオボタンに {@code name} も
 * {@code onChange} も状態もなく({@code defaultChecked} だけが埋め込まれていた)、一覧にある言語(español・العربية・Deutsch)はこのサイトに存在したことがない。
 * "Learn more" は {@code href=""} なので<b>現在のページを再読み込みする</b>リンクだった。つまりユーザーには「言語を変えられる」と言っておきながら、
 * 押しても何も起きなかった。ない機能より悪い — なければ探しもしない。今は実際に切り替える。サポートする言語だけを見せ、今の言語を表示し、
 * 選ぶとすぐ反映され選択は記憶される。
 */

/** 화면에 보일 이름과 짧은 코드. 각 언어는 <b>그 언어로</b> 적는다(endonym). */
// [JA] 画面に見せる名前と短いコード。各言語は<b>その言語で</b>書く(endonym)。
const LABELS = {
    ko: { name: "한국어", code: "KO" },
    ja: { name: "日本語", code: "JA" },
};

const Language = () => {
    const { lang, setLang, t } = useTranslation();

    const current = LABELS[lang] ?? LABELS.ko;

    return (
        <div className="show-account relative hidden md:flex items-center link self-end mb-1.5">
            {/* 국기를 쓰지 않는다 — 언어와 국가는 다르고, 국기로 언어를 나타내면
                같은 말을 쓰는 다른 나라 사용자를 배제한다. 코드가 더 정확하고 가볍다. */}
            {/* [JA] 国旗は使わない — 言語と国は違い、国旗で言語を表すと同じ言葉を使う他の国のユーザーを排除する。コードのほうが正確で軽い。 */}
            <p className="flex ml-2 font-bold text-sm">
                {current.code}
                <ChevronDownIcon className="h-4 self-end ml-1" />
            </p>

            <div className="z-20 show-account-popup absolute w-48 top-5 -right-[3.8rem] h-auto bg-white rounded-sm border shadow-md mt-1">
                <div className="absolute h-3 w-3 bg-white rotate-45 -mt-1 right-[3.85rem]" />

                {/* 라디오 그룹으로 둔다 — 하나만 고를 수 있다는 것이 마크업으로 드러나고,
                    키보드 화살표로 이동할 수 있다. fieldset/legend 가 그 묶음의 이름이다. */}
                {/* [JA] ラジオグループにする — 一つだけ選べることがマークアップで表れ、キーボードの矢印で移動できる。fieldset/legend がそのまとまりの名前である。 */}
                <fieldset className="flex flex-col p-3">
                    <legend className="text-xs text-gray-900 mb-1">{t("lang.label")}</legend>

                    {SUPPORTED.map((code) => (
                        <label
                            key={code}
                            className="flex items-center text-xs text-gray-900 mt-2 cursor-pointer"
                        >
                            <input
                                className="mr-2 text-amazon-orange"
                                type="radio"
                                name="app-language"
                                value={code}
                                checked={lang === code}
                                onChange={() => setLang(code)}
                            />
                            {LABELS[code].name} - {LABELS[code].code}
                        </label>
                    ))}
                </fieldset>
            </div>
        </div>
    );
};

export default Language;
