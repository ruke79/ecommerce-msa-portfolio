import { useTranslation } from "i18n/I18nProvider";
import { useAuthContext } from "store/AuthContext";

/**
 * 데모 계정으로 로그인한 동안 맨 위에 띄우는 안내 띠(docs/100). 막힌 기능을 미리 알려 403 에 놀라지 않게 한다.
 * [JA] デモアカウントでログイン中に最上部へ出す案内バー。止められた機能を先に知らせ、403 で驚かせない。
 */
export default function DemoBanner() {
    const { t } = useTranslation();
    const { user } = useAuthContext();
    if (!user?.demo) {
        return null;
    }
    return (
        <div role="status" className="w-full bg-amber-100 text-amber-900 text-xs sm:text-sm text-center px-3 py-1.5">
            {t("user.demo.banner")}
        </div>
    );
}
