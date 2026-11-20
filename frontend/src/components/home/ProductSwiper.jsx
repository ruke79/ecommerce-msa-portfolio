import { useEffect, useRef } from "react";
import { Autoplay }  from "swiper/modules";
import { Swiper, SwiperSlide } from "swiper/react";

import "swiper/css";

import { useTranslation } from "i18n/I18nProvider";

/**
 * 상품 카드 안의 이미지 스와이퍼.
 *
 * <h3>🐛 이미지가 화면 밖으로 튀어나오고 있었다</h3>
 *
 * <p>{@code <img className="w-auto h-auto object-cover">} 였다. {@code w-auto h-auto} 는
 * "이미지의 <b>원본 크기</b>로 그려라" 라는 뜻이다 — 1200×1600 상품 사진이 220px 상자
 * 안에서 <b>1200px 폭으로</b> 렌더된다. 상자에 {@code overflow-hidden} 도 없어서 사방으로
 * 흘러넘쳤다.
 *
 * <p>{@code object-cover} 는 아무 일도 하지 않았다. {@code object-fit} 은 요소의 상자가
 * 원본 비율과 다를 때만 의미가 있는데, {@code w-auto h-auto} 면 상자 = 원본이라 맞출
 * 것이 없다. "설정해 뒀으니 괜찮겠지" 로 보이지만 <b>죽은 선언</b>이었다.
 *
 * <p>왜 <b>순간</b> 보였나: 이미지가 로드되기 전에는 크기가 0 에 가까워 얌전히 있다가,
 * 로드되는 순간 원본 크기로 <b>튀어오른다.</b> 그 다음 스와이퍼가 레이아웃을 다시 잡으며
 * 정리되므로 "깜빡했다 사라지는" 것처럼 보인다.
 *
 * <p>고친 방식: <b>상자가 크기를 정하고 이미지는 그것을 채운다.</b> 이제 이미지가 아무리
 * 커도 상자를 벗어날 수 없다.
 *
 * <h3>🐛 그다음엔 반대로 비었다</h3>
 *
 * <p>처음 고칠 때는 {@code w-full h-[300px]} 상자에 {@code max-w-full max-h-full
 * object-contain} 이었다. 넘치지는 않지만 <b>상자 비율과 사진 비율이 다른 만큼 상자가
 * 빈다</b> — 215×300 상자(비율 0.72)에 정사각 사진을 넣으면 위아래로 85px 씩 흰 띠가
 * 생긴다. 카드가 줄지어 있으니 그 띠도 줄지어 보인다.
 *
 * <p>{@code aspect-[4/5]} 상자를 {@code object-cover} 로 채운다. 상자가 <b>절대 비지
 * 않는다.</b> 높이를 픽셀로 박지 않아도 비율이 고정이라 레이아웃은 그대로 안정적이다 —
 * 지연 로딩과 짝을 이루는 조건은 "높이가 픽셀로 고정" 이 아니라 "이미지가 오기 전에
 * 높이를 알 수 있다" 이고, 비율 + 폭이면 알 수 있다.
 *
 * <p>비율을 정사각이 아니라 <b>4:5 로</b> 잡은 이유: 잘려 나가는 양은 상자 비율이 사진
 * 비율에서 <b>얼마나 먼가</b>로 정해진다. 상품 사진은 대개 세로가 길다(3:4 = 0.75,
 * 4:5 = 0.8). 정사각(1.0)은 그만큼 더 잘라낸다. 홈 격자 타일이 정사각인 것은 격자를
 * 가지런히 두기 위한 선택이고(작은 썸네일이라 손해가 적다), 카드는 상품을 보는 자리라
 * 덜 자르는 쪽을 골랐다.
 *
 * <p>⚠️ {@code cover} 는 <b>가장자리를 잘라낸다.</b> 상품을 제대로 봐야 하는 상세 화면은
 * 자르지 않는 {@code contain} 을 그대로 쓴다. 이 선택은 "썸네일이라 꽉 찬 쪽이 낫다" 는
 * 판단이지 화면 전체에 적용할 규칙이 아니다.
 *
 * <p>[JA] 商品カード内の画像スワイパー。
 * 🐛 画像が画面外へ飛び出していた。<img className="w-auto h-auto object-cover"> だった。w-auto h-auto は「画像の原寸で描け」という意味で、
 * 1200×1600 の商品写真が 220px の箱の中で 1200px 幅でレンダーされる。箱に overflow-hidden もなく四方へ溢れた。
 * object-cover は何もしていなかった。object-fit は要素の箱が原本の比率と違うときだけ意味があるが、w-auto h-auto なら箱 = 原本で
 * 合わせるものがない。「設定してあるから大丈夫だろう」と見えるが死んだ宣言だった。
 * なぜ一瞬見えたか: 画像が読み込まれる前は大きさが 0 に近くおとなしいが、読み込まれた瞬間に原寸へ跳ね上がる。
 * その後スワイパーがレイアウトを組み直して整うので「一瞬ちらついて消える」ように見える。
 * 直し方: 箱が大きさを決め、画像はそれを埋める。今は画像がどれだけ大きくても箱をはみ出せない。
 * 🐛 次は逆に空いた。最初の修正では w-full h-[300px] の箱に max-w-full max-h-full object-contain だった。はみ出しはしないが
 * 箱の比率と写真の比率が違う分だけ箱が空く — 215×300 の箱(比率 0.72)に正方形の写真を入れると上下に 85px ずつ白い帯ができる。
 * カードが並んでいるのでその帯も並んで見える。
 * aspect-[4/5] の箱を object-cover で埋める。箱が決して空かない。高さをピクセルで固定しなくても比率が固定なのでレイアウトは安定したまま —
 * 遅延読み込みと対になる条件は「高さがピクセルで固定」ではなく「画像が来る前に高さが分かる」で、比率 + 幅なら分かる。
 * 比率を正方形ではなく 4:5 にした理由: 切り取られる量は箱の比率が写真の比率からどれだけ遠いかで決まる。商品写真は大抵縦長である
 * (3:4 = 0.75, 4:5 = 0.8)。正方形(1.0)はその分多く切る。ホームのグリッドタイルが正方形なのはグリッドを揃えるための選択で
 * (小さいサムネイルなので損が少ない)、カードは商品を見る場所なので切る量が少ない方を選んだ。
 * ⚠️ cover は端を切り取る。商品をきちんと見るべき詳細画面は切らない contain をそのまま使う。この選択は
 * 「サムネイルなので詰まった方がよい」という判断であって、画面全体に適用する規則ではない。
 */
const ProductSwiper = ({ images }) => {
    const { t } = useTranslation();
    const swiperRef = useRef(null);

    useEffect(() => {
        swiperRef.current?.swiper?.autoplay.stop();
    }, [swiperRef]);

    return (
        <div
            // 🐛 w-52(208px) 였다. 부모 카드는 215px, 안쪽 이미지 상자는 220px —
            //    셋이 전부 다른 값이라 안쪽이 바깥을 넘쳤다. 상자 크기는 카드가 정한다.
            // [JA] 🐛 w-52(208px)だった。親カードは 215px、内側の画像の箱は 220px —
            //      三つがすべて違う値なので内側が外側をはみ出した。箱の大きさはカードが決める。
            className="relative w-full"
            onMouseEnter={() => {
                swiperRef.current?.swiper?.autoplay.start();
            }}
            onMouseLeave={() => {
                swiperRef.current?.swiper?.autoplay.stop();
                swiperRef.current?.swiper?.slideTo(0);
            }}
        >
            <Swiper
                ref={swiperRef}
                centeredSlides={true}
                autoplay={{ delay: 100, stopOnLastSlide: false }}
                speed={500}
                modules={[Autoplay]}
            >
                {images.map((img, i) => (
                    <SwiperSlide key={i}>
                        <div className="relative z-10 w-full aspect-[4/5] rounded bg-slate-100 overflow-hidden">
                            <img
                                src={img}
                                // inset-0 + h-full w-full 이 핵심이다. 이게 없으면
                                // 원본 크기로 그려지고, 상자는 채워지지 않는다.
                                // [JA] inset-0 + h-full w-full が核心である。これがないと
                                //      原寸で描かれ、箱は埋まらない。
                                className="absolute inset-0 h-full w-full rounded object-cover"
                                // 화면 밖 슬라이드의 이미지는 나중에 받는다. 상자 비율이
                                // 고정돼 있어 늦게 와도 레이아웃이 흔들리지 않는다.
                                // [JA] 画面外のスライドの画像は後で受け取る。箱の比率が
                                //      固定されているので遅れて来てもレイアウトが揺れない。
                                loading="lazy"
                                decoding="async"
                                alt={t("home.product_image_alt", { n: i + 1 })}
                            />
                        </div>
                    </SwiperSlide>
                ))}
            </Swiper>
        </div>
    );
};

export default ProductSwiper;
