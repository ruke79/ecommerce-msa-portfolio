import Header from "pages/header/Header";
import Sidebar from "./sidebar/Sidebar";
import { useProfile } from "hook/useProfile";

/**
 * 프로필 탭들의 공통 뼈대.
 *
 * <p>🐛 예전에는 호출부가 넘긴 `user` 를 그대로 사이드바에 꽂았다. 그 값은 JWT 에서 만든
 * `{ email, userId, roles }` 뿐이라 사이드바가 읽는 `image` · `username` 은 <b>존재하지
 * 않는 필드</b>였다 — 아바타는 깨진 이미지, 이름 자리는 빈 칸.
 *
 * <p>프로필을 <b>여기서</b> 한 번 받는다. 다섯 탭(프로필·주소·주문·결제수단·보안)이 모두
 * 이 Layout 을 거치므로, 각 페이지를 고치지 않아도 전부 실제 이름과 사진을 갖는다.
 * React Query 가 같은 키를 공유하니 탭을 옮겨 다녀도 요청은 한 번이다.
 *
 * <p>합칠 때 서버 값을 <b>나중에</b> 펼친다. 아직 못 받았으면 JWT 의 이메일이라도 보이고,
 * 받고 나면 이름·사진이 채워진다 — 화면이 비어 있는 순간이 없다.
 *
 * <p>[JA] プロフィールタブ共通の骨組み。
 * 🐛 以前は呼び出し側が渡した `user` をそのままサイドバーに差していた。その値は JWT から作った
 * `{ email, userId, roles }` だけで、サイドバーが読む `image` · `username` は <b>存在しない
 * フィールド</b>だった — アバターは壊れた画像、名前の場所は空欄。
 * プロフィールを <b>ここで</b> 一度受け取る。五つのタブ(プロフィール・住所・注文・決済手段・セキュリティ)が全て
 * この Layout を通るので、各ページを直さなくても全て実際の名前と写真を持つ。
 * React Query が同じキーを共有するのでタブを移動してもリクエストは一度だ。
 * マージするときサーバーの値を <b>後に</b> 展開する。まだ受け取っていなければ JWT のメールだけでも見え、
 * 受け取れば名前・写真が埋まる — 画面が空になる瞬間がない。
 */
const Layout = ({ user, tab, title, children }) => {
    const { profile } = useProfile();

    const merged = { ...(user ?? {}), ...(profile ?? {}) };

    return (
        <>
            <Header title={title} />
            <main className="max-w-screen-2xl mx-auto bg-gray-100 grid grid-cols-4 md:px-14 pt-5 pb-8 gap-8">
                <section className="col-span-1 bg-white p-2 md:p-5 rounded-xl border">
                    <Sidebar data={{ ...merged, tab }} />
                </section>
                <section className="col-span-3 bg-white p-2 md:p-5 rounded-xl border">
                    {children}
                </section>
            </main>
        </>
    );
};

export default Layout;
