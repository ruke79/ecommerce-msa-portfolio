/**
 * 프로필 사이드바 메뉴.
 *
 * <p>🔴 <b>{@code name} 을 번역하지 않는다.</b> {@code Item.jsx} 가 그 값을
 * {@code slugify(link.name)} 해서 URL 의 {@code ?q=} 를 만든다 — 번역하면 <b>언어에 따라
 * 주소가 달라져</b> 공유한 링크가 다른 언어 사용자에게서 열리지 않고, 뒤로 가기도 어긋난다.
 *
 * <p>그래서 표시용 문구는 {@code key} 로 따로 둔다. {@code name} 은 영어 그대로 남아
 * 화면에 보이지 않고 주소에만 쓰인다.
 *
 * <p>[JA] プロフィールサイドバーメニュー。
 * 🔴 <b>{@code name} を翻訳しない。</b> {@code Item.jsx} がその値を
 * {@code slugify(link.name)} して URL の {@code ?q=} を作る — 翻訳すると <b>言語によって
 * アドレスが変わり</b> 共有したリンクが別言語のユーザーで開けず、戻るもずれる。
 * そのため表示用の文言は {@code key} で別に置く。{@code name} は英語のまま残り
 * 画面には見えずアドレスにだけ使われる。
 */
export const profile =[
    {
        heading: "My Account",
        headingKey: "profile.menu.account",
        links: [
            {
                name: "My Profile",
                key: "profile.menu.my_profile",
                link: "/profile",
            },
            {
                name: "Addresses",
                key: "profile.menu.addresses",
                link: "/profile/address",
            },
            {
                name: "My Payment Options",
                key: "profile.menu.payment",
                link: "/profile/payment",
            },
            {
                name: "Account Security",
                key: "profile.menu.security",
                link: "/profile/security",
            },
        ],
    },
    {
        heading: "My Orders",
        headingKey: "profile.menu.orders",
        links: [
            {
                name: "All Orders",
                key: "profile.menu.orders.all",
                link: "/profile/orders",
                filter: "",
            },
            {
                name: "Paid Orders",
                key: "profile.menu.orders.paid",
                link: "/profile/orders",
                filter: "PAID",
            },
            {
                name: "Unpaid Orders",
                key: "profile.menu.orders.unpaid",
                link: "/profile/orders",
                filter: "UNPAID",
            },
            {
                name: "Processing Orders",
                key: "profile.menu.orders.processing",
                link: "/profile/orders",
                filter: "PROCESSING",
            },
            {
                name: "Unprocessed Orders",
                key: "profile.menu.orders.unprocessed",
                link: "/profile/orders",
                filter: "NOT_PROCESSED",
            },
            {
                name: "Dispatched Orders",
                key: "profile.menu.orders.dispatched",
                link: "/profile/orders",
                filter: "DISPATCHED",
            },
            // 🐛 "배송 완료" 가 COMPLETED 를 걸렀다 — 도착한 주문은 DELIVERED 라 언제나 비었다.
            //    COMPLETED 는 구매 확정(docs/25 §12)이고 그 항목은 따로 있다.
            // [JA] 🐛「配送完了」が COMPLETED で絞り込んでいた — 届いた注文は DELIVERED なので常に空だった。
            //      COMPLETED は購入確定(docs/25 §12)でその項目は別にある。
            {
                name: "Delivered Orders",
                key: "profile.menu.orders.delivered",
                link: "/profile/orders",
                filter: "DELIVERED",
            },
            {
                name: "Completed Orders",
                key: "profile.menu.orders.completed",
                link: "/profile/orders",
                filter: "COMPLETED",
            },
            {
                name: "Cancelled Orders",
                key: "profile.menu.orders.cancelled",
                link: "/profile/orders",
                filter: "CANCELED",
            },
        ],
    },
    // {
    //     heading: "My Lists",
    //     links: [
    //         {
    //             name: "Whishlist",
    //             link: "/profile/whishlist",
    //         },
    //         {
    //             name: "Recently Viewed",
    //             link: "/profile/recent",
    //         },
    //     ],
    // },
    // {
    //     heading: "Customer Service",
    //     links: [
    //         {
    //             name: "My Message",
    //             link: "/profile/messages",
    //         },
    //         {
    //             name: "Service Records",
    //             link: "/profile/services",
    //         },
    //     ],
    // },
    // {
    //     heading: "Policy",
    //     links: [
    //         {
    //             name: "My Message",
    //             link: "/profile/messages",
    //         },
    //         {
    //             name: "Service Records",
    //             link: "/profile/services",
    //         },
    //     ],
    // },
    {
        heading: "Sign Out",
        link: []
    },
];

