import { useState, useEffect } from "react";
import { useDispatch, useSelector } from "react-redux";
import AddShipping from "./AddShipping";
import ListShipping from "./ListShipping";

import { useFetchAddresses } from "hook/hooks";
import { useTranslation } from "i18n/I18nProvider";
import { DEFAULT_COUNTRY } from "util/country";

const initialValue = {
    firstname: "",
    lastname: "",
    phoneNumber: "",
    state: "",
    city: "",
    zipCode: "",
    address1: "",
    address2: "",
    // 발송국을 기본값으로 둔다. 목록에서 두 한국이 나란히 있어("Korea, Democratic People'S
    // Republic of" 바로 아래가 "Korea, Republic of") 빈 채로 두면 잘못 고르기 쉽고, 그러면
    // 목포 주소가 KP 로 저장돼 국제 배송(통관 단계)으로 만들어진다 — 실제로 그랬다.
    // 값은 나라 코드다 — 이름은 화면 언어로 그린다(docs/60 §11).
    country: DEFAULT_COUNTRY,
};

const ShippingPage = ({    
    setSelectedAddress,
    user,    
    setLoading,
    profile
}) => {
    const { t } = useTranslation();
    

    const [shipping, setShipping] = useState(initialValue);
    // 고치는 중인 배송지. 지금까지는 등록·삭제만 됐다 — 나라 하나를 잘못 골랐으면 지우고
    // 다시 넣어야 했고, 그러면 기본 배송지 표시도 함께 사라졌다.
    const [editing, setEditing] = useState(null);
    const { addressesData, isPendingAddress, invalidate, isSuccessAddress } = useFetchAddresses(user.userId);
         
        
     useEffect(() => {
    
            
        if (isSuccessAddress && addressesData ) {  // empty null value
           
            const check = addressesData.find((address) => address.active === true);
            if (check) {
                setSelectedAddress(check);

            } else {
                setSelectedAddress("");
            }
            
        }
    

    }, [addressesData]);


if (isPendingAddress) {
    // 🐛 `return;` 이었다 — undefined 를 그리면 React 는 **아무것도 렌더하지 않는다.**
    //    배송지를 불러오는 동안 결제 화면 위쪽이 통째로 빈 공간이 됐고, 사용자는
    //    로딩 중인지 주소가 없는 건지 알 수 없었다.
    return (
        <div className="py-6 text-sm text-slate-500" role="status">
            {t("checkout.address.loading")}
        </div>
    );
}
    

            
    return (
        <>
            {(!profile &&
                <div className="text-xl font-semibold pb-2 mb-4 border-b border-b-2 ">
                    {t("checkout.address.section_title")}
                </div>
            )}
            <ListShipping
                setLoading={setLoading}      
                addresses = {addressesData}                          
                invalidate= {invalidate}    
                user={user}
                profile={profile}
                onEdit={setEditing}
            />
            {(
                <AddShipping                    
                    setLoading={setLoading}                       
                    invalidate= {invalidate}    
                    shipping={shipping}
                    setShipping={setShipping}                    
                    initialValue={initialValue}                    
                    editing={editing}
                    onCancelEdit={() => setEditing(null)}
                />
            )}
        </>
    );
};

export default ShippingPage;
