import { useEffect, useRef, useState } from "react";
import { useNavigation } from "react-router-dom";

import { useTranslation } from "i18n/I18nProvider";

/**
 * 라우터가 다음 화면을 준비하는 동안 위쪽에 얇은 막대를 그린다.
 *
 * <h3>🔴 왜 필요한가</h3>
 *
 * <p>이 앱의 화면 전환은 <b>loader 가 끝나야 일어난다.</b> react-router 의 데이터 라우터는
 * 로더가 응답할 때까지 <b>URL 도 바꾸지 않고</b> 이전 화면을 그대로 둔다. 그래서
 * `/browse` 에서 필터를 누르면 응답이 올 때까지 <b>아무 반응이 없다</b> — 사용자는
 * 클릭이 먹었는지 알 수 없어 한 번 더 누른다.
 *
 * <h3>왜 전체화면 스피너가 아닌가</h3>
 *
 * <p>이 앱에는 이미 전체화면 오버레이(`DotLoaderSpinner`)가 있는데, 호출부 대부분에서
 * <b>주석 처리돼 있다.</b> 이유가 있다 — 화면을 덮으면 이전 내용이 사라져서, 잠깐 걸리는
 * 전환에도 화면이 깜빡이고 읽던 것을 잃는다. 그래서 다들 꺼 버렸고 결과적으로
 * <b>아무 표시도 없는 상태</b>가 남았다.
 *
 * <p>막대는 덮지 않는다. 이전 화면은 그대로 읽을 수 있고, 무언가 진행 중이라는 사실만
 * 전한다. 유튜브·깃허브가 같은 이유로 같은 것을 쓴다.
 *
 * <h3>⏱️ 200ms 를 기다렸다 보여 준다</h3>
 *
 * <p>캐시된 전환은 수십 ms 에 끝난다. 그때 막대를 띄우면 <b>깜빡임</b>만 남아 오히려
 * 화면이 불안해 보인다. 사람이 "느리다" 고 느끼기 시작하는 지점 언저리에서만 나타난다.
 *
 * <p>{@code submitting} 도 함께 본다 — form 제출(useFetcher/Form)도 같은 기다림이다.
 *
 * <p>[JA] ルーターが次の画面を準備する間、上部に細いバーを描く。
 * 🔴 なぜ必要か: このアプリの画面遷移は loader が終わってから起きる。react-router のデータルーターは
 * ローダーが応答するまで URL も変えず前の画面をそのまま置く。そのため `/browse` でフィルターを押すと応答が来るまで
 * 何の反応もない — ユーザーはクリックが効いたのか分からずもう一度押す。
 * なぜ全画面スピナーではないか: このアプリにはすでに全画面オーバーレイ(`DotLoaderSpinner`)があるが、呼び出し側のほとんどで
 * コメントアウトされている。理由がある — 画面を覆うと前の内容が消え、少し掛かる遷移でも画面がちらつき読んでいたものを失う。
 * そのため皆オフにし、結果的に何の表示もない状態が残った。
 * バーは覆わない。前の画面はそのまま読め、何かが進行中だという事実だけを伝える。YouTube · GitHub が同じ理由で同じものを使う。
 * ⏱️ 200ms 待ってから見せる: キャッシュされた遷移は数十 ms で終わる。そのときバーを出すとちらつきだけが残り、かえって
 * 画面が不安定に見える。人が「遅い」と感じ始めるあたりでだけ現れる。
 * submitting も一緒に見る — form の送信(useFetcher/Form)も同じ待ちである。
 */
const NavigationProgress = () => {
    const { t } = useTranslation();
    const navigation = useNavigation();
    const busy = navigation.state !== "idle";

    const [visible, setVisible] = useState(false);
    const timer = useRef(null);

    useEffect(() => {
        if (!busy) {
            clearTimeout(timer.current);
            setVisible(false);
            return undefined;
        }

        timer.current = setTimeout(() => setVisible(true), 200);
        return () => clearTimeout(timer.current);
    }, [busy]);

    if (!visible) return null;

    return (
        <div
            // role="status" + aria-live 로 스크린리더에도 알린다. 막대만 그리면
            // 눈으로 보는 사람에게만 전달된다.
            // [JA] role="status" + aria-live でスクリーンリーダーにも知らせる。バーだけ描くと
            //      目で見る人にしか伝わらない。
            role="status"
            aria-live="polite"
            className="fixed top-0 left-0 right-0 z-50 h-1 overflow-hidden bg-transparent"
        >
            <div className="h-full w-1/3 animate-[nav-progress_1.1s_ease-in-out_infinite] bg-amazon-orange" />
            <span className="sr-only">{t("app.loading")}</span>
        </div>
    );
};

export default NavigationProgress;
