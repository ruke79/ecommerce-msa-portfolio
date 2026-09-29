import Avatar from "components/common/Avatar";
import { displayName } from "util/userDisplay";
import { useTranslation } from "i18n/I18nProvider";
import { countryName } from "util/country";

/**
 * 🐛 `user.image` / `user.name` 은 JWT 에서 만든 사용자 객체에 없는 필드다
 * (util/jwtUser.js). 주문 상세에서 깨진 이미지와 빈 이름으로 보이던 자리다.
 */
const UserInfo = ({ order, user }) => {
    const { t, lang } = useTranslation();
    // 나라는 코드(또는 옛 주소의 영어 이름)로 저장돼 있다 — 지금 언어의 이름으로 그린다(docs/60 §11).
    const country = countryName(order.shippingAddress.country, lang);
    return (
        <div className=" mb-2">
            <h3 className="text-2xl font-bold border-b pb-3 mb-3">
                {t("order.customer.title")}
            </h3>

            <div className="flex items-center space-x-3">
                <Avatar user={user} size={65} />
                <div className="flex flex-col text-slate-800">
                    <span className="font-semibold">{displayName(user)}</span>
                    <span>{user?.email}</span>
                </div>
            </div>
            <div className="mt-2 flex flex-col text-slate-800">
                <h4 className="text-xl font-bold border-b pb-2 mb-2">
                    {t("order.customer.shipping_address")}
                </h4>
                <span>
                    {order.shippingAddress.firstname}
                    {order.shippingAddress.lastname}
                </span>
                <span>
                    {order.shippingAddress.city}/{order.shippingAddress.state}/
                    {country}
                </span>
                <span>{order.shippingAddress.address1}</span>
                <span>{order.shippingAddress.address2}</span>
                <span>{order.shippingAddress.zipCode}</span>
                <span>{order.shippingAddress.phoneNumber}</span>
            </div>
            <div className="mt-2 flex flex-col text-slate-800">
                <h4 className="text-xl font-bold border-b pb-2 mb-2">
                    {t("order.customer.billing_address")}
                </h4>
                <span>
                    {order.shippingAddress.firstname}{" "}
                    {order.shippingAddress.lastname}
                </span>
                <span>
                    {order.shippingAddress.city}/{order.shippingAddress.state}/
                    {country}
                </span>
                <span>{order.shippingAddress.address1}</span>
                <span>{order.shippingAddress.address2}</span>
                <span>{order.shippingAddress.zipCode}</span>
                <span>{order.shippingAddress.phoneNumber}</span>
            </div>
        </div>
    );
};

export default UserInfo;
