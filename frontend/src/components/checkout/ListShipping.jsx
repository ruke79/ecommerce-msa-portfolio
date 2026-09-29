import { useEffect, useState } from "react";
import {
    CheckIcon,
    ChevronUpIcon,
    MapPinIcon,
    MinusCircleIcon,
    PencilSquareIcon,
    PhoneIcon,
    PlusIcon,
    UserIcon,
} from "@heroicons/react/24/outline";

import DotLoaderSpinner from "components/loader/Loading";
import { useAuthContext } from "store/AuthContext";
import { selectShippingAddress, deleteAddress } from "util/api";
import { useDispatch } from "react-redux";
import { useTranslation } from "i18n/I18nProvider";
import { countryName } from "util/country";


const ListShipping = ({
    setLoading,
    selectedAddress,    
    addresses,
    invalidate,
    user,
    profile,
    onEdit,
}) => {
    const { t, lang } = useTranslation();

    const [visible, setVisible] = useState(addresses.length ? false : true);    
    const [prevAddressId, setPrevAddressId] = useState(null);


    const changeActiveHandler = async (id, e) => {

        e.stopPropagation();

        if (prevAddressId !== id) {
            setPrevAddressId(id);

            try {
                const res = await selectShippingAddress(id);


            } catch (err) {
                console.error(err.response?.data.message);
            }

            invalidate();
        }

    };
    const deleteHandler = async (id, e) => {
        e.stopPropagation();
        //setLoading(true);
        try {
            const res = await deleteAddress(id);



        } catch (err) {
            console.error(err.response?.data.message);
        }

        invalidate();


        //setLoading(false);

    };



    return (
        <div>
            {addresses && addresses.map((address) => (
                <div
                    className={`relative cursor-pointer p-4 mb-4 border border-slate-100 rounded-xl shadow-md hover:shadow-xl hover:border-white hover:scale-[101%] transition duration-300 ${address.active &&
                        "border-l-4 border-l-amazon-blue_light hover:border-l-amazon-blue_light"
                        } `}
                    key={address.id}
                    onClick={(e) => changeActiveHandler(address.id, e)}
                >
                    {addresses.length > 0 ? (
                        <div className="z-10 absolute top-2 right-2 flex items-center gap-2">
                            {/* 수정. 카드 클릭(기본 배송지 선택)과 겹치므로 전파를 막는다. */}
                            <button
                                type="button"
                                aria-label={t("checkout.address.edit_aria")}
                                className="text-slate-600 hover:text-amazon-blue_light hover:scale-110 transition"
                                onClick={(e) => { e.stopPropagation(); onEdit?.(address); }}
                            >
                                <PencilSquareIcon className="w-6 h-6" />
                            </button>
                            <button
                                type="button"
                                aria-label={t("checkout.address.delete_aria")}
                                className="text-slate-600 hover:text-red-500 hover:scale-110 transition"
                                onClick={(e) => deleteHandler(address.id, e)}
                            >
                                <MinusCircleIcon className="w-6 h-6" />
                            </button>
                        </div>
                    ) : (
                        ""
                    )}
                    {/*
                      * 🐛 여기 `<img src={""} alt={address.id} />` 가 있었다.
                      *
                      * 빈 `src` 는 "없음"이 아니다 — 브라우저는 **현재 문서 주소**로
                      * 해석해 페이지 HTML 을 받아오고, 그것을 이미지로 못 읽어 결국
                      * <b>alt 텍스트를 글자로 그린다.</b> 그 alt 가 `address.id`(TSID)라,
                      * 배송지 카드마다 긴 숫자가 60px 칸에 잘린 채 찍혔다.
                      *
                      * 배송지에는 원래 보여 줄 그림이 없다. 대신 그 자리에 **실제로
                      * 필요한 정보**(받는 사람·연락처)를 넣는다 — 아래 블록은 주석 처리된
                      * 채 남아 있었고, 그 안에 `address.phoneNumbe`(r 빠짐) 오타가 있었다.
                      * 되살리면서 함께 고쳤다.
                      */}
                    <div className="mb-3 flex flex-col gap-1">
                        <span className="flex items-center font-semibold">
                            <UserIcon className="w-4 h-4 mr-1" />
                            {address.firstname} {address.lastname}
                        </span>
                        <span className="flex items-center text-sm text-slate-600">
                            <PhoneIcon className="w-4 h-4 mr-1" />
                            {address.phoneNumber}
                        </span>
                    </div>
                    <div className="flex flex-col">
                        <div>
                            <span className="flex items-center">
                                <MapPinIcon className="w-5 h-5 mr-1" />
                                {address.address1}
                            </span>
                            {address.address2 && (
                                <span className="flex items-center">
                                    <MapPinIcon className="w-5 h-5 mr-1" />
                                    {address.address2}
                                </span>
                            )}
                        </div>
                        <span>
                            {address.city},{address.state},{countryName(address.country, lang)}
                        </span>
                        <div className="flex justify-between">
                            <span>{address.zipCode}</span>
                            <span
                                className={`flex items-center text-amazon-blue_light font-semibold ${!address.active && "hidden"
                                    }`}
                            >
                                <CheckIcon className="w-5 h-5 " /> {t("checkout.address.active")}
                            </span>
                        </div>
                    </div>
                </div>
            ))}

            <div className="flex justify-center">
                <button
                    onClick={() => setVisible((prev) => !prev)}
                    className={`flex justify-center w-52 my-4  py-4 rounded-xl hover:text-amazon-blue_dark font-bold bg-gradient-to-r hover:from-amazon-orange hover:to-yellow-300 hover:text-amazon-blue_dark  text-slate-100 from-amazon-blue_light to-slate-500 transition duration-300 hover:scale-95 `}
                >
                    {visible ? (
                        <span>
                            <ChevronUpIcon className="h-6 w-6" />
                        </span>
                    ) : (
                        <span className="flex items-center">
                            <PlusIcon className="h-6 w-6" /> {t("checkout.address.add_new")}
                        </span>
                    )}
                </button>
            </div>
        </div>
    );
};

export default ListShipping;
