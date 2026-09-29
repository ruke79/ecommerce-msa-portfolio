import { useEffect, useRef, useState } from "react";
import { useTranslation } from "i18n/I18nProvider";
import Layout from "components/profile/Layout";
import TierStatus from "components/profile/TierStatus";
import { useAuthContext } from "store/AuthContext";
import Avatar from "components/common/Avatar";
import { displayName } from "util/userDisplay";
import { useProfile, useUpdateProfile, profileErrorText } from "hook/useProfile";

/**
 * 내 프로필.
 *
 * <p>🐛 <b>이 화면은 본문이 없었다.</b> 전체가 이랬다:
 *
 * <pre>
 *   &lt;Layout user={user} tab={0} title={`${user.email}'s Profile`}&gt;
 *       &lt;h2&gt;My Profile&lt;/h2&gt;
 *   &lt;/Layout&gt;
 * </pre>
 *
 * 제목 한 줄뿐이다 — 버그가 아니라 만들어진 적이 없다. 같은 Layout 을 쓰는 다른 탭
 * (주소·주문·결제수단·보안)은 전부 내용이 있고 `/profile` 만 껍데기였다.
 *
 * <p>그리고 프로필 사진을 바꿀 방법도 없었다. UI 가 빠진 것이 아니라 서버에 <b>경로 자체가
 * 없었다</b> — 프로필을 돌려주는 GET 도, 이미지를 바꾸는 엔드포인트도.
 */
const Profile = () => {
    const { t } = useTranslation();
    const { user } = useAuthContext();
    const { profile, isPending, isError, error, refetch } = useProfile();
    const { save, uploadImage, removeImage } = useUpdateProfile();

    // 프로필을 아직 못 받았어도 화면은 그려야 한다. JWT 의 이메일이 그때의 근거다.
    const shown = profile ?? user;

    const [name, setName] = useState("");
    const [saved, setSaved] = useState(false);
    const fileRef = useRef(null);

    // 서버 값이 도착하면 입력란을 채운다. 이미 타이핑 중이면 덮지 않는다 —
    // 응답이 늦게 오는 사이 입력한 글자가 사라지면 안 된다.
    useEffect(() => {
        if (profile && name === "") {
            setName(profile.name ?? profile.username ?? "");
        }
        // name 을 의존성에 넣으면 한 글자 칠 때마다 다시 돈다.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [profile]);

    const onSave = (event) => {
        event.preventDefault();
        setSaved(false);
        save.mutate({ name }, { onSuccess: () => setSaved(true) });
    };

    const onPickImage = (event) => {
        const file = event.target.files?.[0];
        // 같은 파일을 다시 고를 수 있어야 한다. value 를 비우지 않으면 change 가 안 뜬다.
        event.target.value = "";
        if (file) {
            uploadImage.mutate(file);
        }
    };

    const busy = save.isPending || uploadImage.isPending || removeImage.isPending;

    return (
        <Layout user={shown} tab={0} title={t("profile.doc_title", { name: displayName(shown) })}>
            <div className="max-w-xl mx-auto">
                <h2 className="text-3xl font-bold mb-6 text-center">{t("profile.title")}</h2>

                {isError && (
                    <div className="mb-4 flex items-center justify-between gap-3 rounded border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-800">
                        <span>
                            {profileErrorText(error, t("profile.error.load"))}
                        </span>
                        <button type="button" className="shrink-0 underline" onClick={() => refetch()}>
                            {t("profile.retry")}
                        </button>
                    </div>
                )}

                {/* ── 프로필 사진 ─────────────────────────────────────────── */}
                <section className="flex flex-col items-center gap-3 pb-6 border-b">
                    <Avatar user={shown} size={112} className="outline outline-2 outline-offset-[3px] outline-slate-200" />

                    <div className="flex items-center gap-2">
                        <button
                            type="button"
                            disabled={busy}
                            onClick={() => fileRef.current?.click()}
                            className="px-4 py-2 text-sm rounded-full border border-slate-300 hover:bg-slate-50 disabled:opacity-50"
                        >
                            {uploadImage.isPending ? t("profile.uploading") : t("profile.change_photo")}
                        </button>

                        {shown?.image && (
                            <button
                                type="button"
                                disabled={busy}
                                onClick={() => removeImage.mutate()}
                                className="px-4 py-2 text-sm rounded-full border border-slate-300 text-slate-600 hover:bg-slate-50 disabled:opacity-50"
                            >
                                {t("profile.remove_photo")}
                            </button>
                        )}
                    </div>

                    {/*
                      * accept 는 편의일 뿐 검증이 아니다 — 파일 선택창의 기본 필터를 정할
                      * 뿐이고 사용자가 "모든 파일"로 바꿀 수 있다. 실제 검사는 서버가 한다
                      * (FileUtil 의 확장자 허용 목록).
                      */}
                    <input
                        ref={fileRef}
                        type="file"
                        accept="image/png,image/jpeg,image/gif,image/webp"
                        className="hidden"
                        onChange={onPickImage}
                    />

                    <p className="text-xs text-slate-500">
                        {t("profile.photo_hint")}
                    </p>

                    {uploadImage.isError && (
                        <p className="text-sm text-red-500">
                            {profileErrorText(uploadImage.error, t("profile.error.upload"))}
                        </p>
                    )}
                    {removeImage.isError && (
                        <p className="text-sm text-red-500">
                            {profileErrorText(removeImage.error, t("profile.error.remove"))}
                        </p>
                    )}
                </section>

                {/* ── 기본 정보 ───────────────────────────────────────────── */}
                <form onSubmit={onSave} className="py-6 flex flex-col gap-4">
                    <label className="flex flex-col gap-1">
                        <span className="text-sm font-semibold">{t("profile.name")}</span>
                        <input
                            type="text"
                            value={name}
                            maxLength={50}
                            disabled={isPending || busy}
                            onChange={(e) => { setName(e.target.value); setSaved(false); }}
                            placeholder={isPending ? t("profile.loading") : t("profile.name_placeholder")}
                            className="border border-slate-300 rounded px-3 py-2 outline-none focus:border-amazon-blue_light disabled:bg-slate-50"
                        />
                    </label>

                    <label className="flex flex-col gap-1">
                        <span className="text-sm font-semibold">{t("common.email")}</span>
                        {/*
                          * 이메일은 바꿀 수 없다 — 신원 그 자체이고 JWT 의 subject 이며
                          * 다른 서비스(order·cart·coupon)가 사용자를 찾는 키다. 바꿀 수
                          * 없는 것을 입력란처럼 보여 주면 눌러 보고 나서야 알게 된다.
                          */}
                        <input
                            type="email"
                            value={shown?.email ?? ""}
                            readOnly
                            disabled
                            className="border border-slate-200 rounded px-3 py-2 bg-slate-50 text-slate-500"
                        />
                        <span className="text-xs text-slate-500">
                            {t("profile.email_fixed")}
                        </span>
                    </label>

                    <div className="flex items-center gap-3">
                        <button
                            type="submit"
                            disabled={busy || isPending || name.trim() === ""}
                            className="px-5 py-2 rounded-full bg-amazon-orange font-semibold text-amazon-blue_dark hover:brightness-95 disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {save.isPending ? t("profile.saving") : t("common.save")}
                        </button>

                        {saved && !save.isError && (
                            <span className="text-sm text-green-600">{t("profile.saved")}</span>
                        )}
                        {save.isError && (
                            <span className="text-sm text-red-500">
                                {profileErrorText(save.error, t("profile.error.save"))}
                            </span>
                        )}
                    </div>
                </form>

                {/* ── 계정 ────────────────────────────────────────────────── */}
                <section className="py-6 border-t text-sm text-slate-600 flex flex-col gap-2">
                    <div className="flex justify-between">
                        <span>{t("profile.signup_method")}</span>
                        <span>{shown?.signUpMethod ?? t("profile.signup_method.email")}</span>
                    </div>
                    <div className="flex justify-between">
                        <span>{t("profile.two_factor")}</span>
                        <span>{shown?.twoFactorEnabled ? t("profile.two_factor.on") : t("profile.two_factor.off")}</span>
                    </div>
                    {shown?.createdDate && (
                        <div className="flex justify-between">
                            <span>{t("profile.joined_at")}</span>
                            <span>{String(shown.createdDate).slice(0, 10)}</span>
                        </div>
                    )}
                </section>

                {/*
                  * 등급은 프로필에 둔다. 별도 탭을 만들면 "등급 화면" 이 되고, 그러면
                  * 등급 자체가 목적처럼 보인다 — 계정 정보 옆에 있는 편이 톤에 맞는다.
                  */}
                <TierStatus />
            </div>
        </Layout>
    );
};

export default Profile;

/**
 * 라우트 loader.
 *
 * <p>예전에는 `tab` 을 계산해 돌려줬는데 컴포넌트에서 쓰는 줄이 주석 처리돼 있었다.
 * 프로필은 언제나 첫 번째 탭이므로 계산할 것이 없다. 데이터는 useProfile 이 가져온다 —
 * loader 로 가져오면 프로필 조회가 느릴 때 화면 전환 자체가 막힌다.
 */
export const loader = async () => null;
