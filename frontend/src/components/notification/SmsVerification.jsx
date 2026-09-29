import { useEffect, useRef, useState } from "react";
import { IoPhonePortraitOutline, IoCheckmarkCircle } from "react-icons/io5";

import { requestPhoneCode, confirmPhoneCode } from "api/notificationApi";
import { useTranslation } from "i18n/I18nProvider";
import "./notification.css";

/**
 * 휴대폰 번호 인증 — 번호 등록과 로그인 2단계 인증이 같은 화면을 쓴다.
 *
 * <p>한국·일본 번호를 모두 받는다. 국가 선택은 <b>국가코드가 없는 입력을 어떻게 해석할지</b>를
 * 정할 뿐이다 — 사용자가 +81 로 시작하는 번호를 넣으면 선택과 무관하게 일본 번호로 처리된다.
 *
 * <p>형식 검증은 <b>서버에 맡긴다.</b> 프론트에서 정규식으로 미리 거르면 유효한 번호까지
 * 막힌다 — 한국의 10자리 010 번호나 019 대역이 대표적이다. 서버는 libphonenumber 로 판정한다.
 */
export default function SmsVerification({ onVerified, initialRegion = "KR", title }) {
  const { t } = useTranslation();
  const [region, setRegion] = useState(initialRegion);
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [step, setStep] = useState("phone");   // phone → code → done
  const [message, setMessage] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const codeInputRef = useRef(null);

  // 재전송까지 남은 시간. 서버가 Retry-After 로 알려준 값을 그대로 센다 —
  // 프론트에서 임의로 정하면 서버 제한과 어긋나 "눌러도 계속 실패"가 된다.
  useEffect(() => {
    if (cooldown <= 0) return undefined;
    const timer = setInterval(() => setCooldown((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(timer);
  }, [cooldown]);

  // 코드 단계로 넘어가면 바로 입력할 수 있게 초점을 옮긴다.
  useEffect(() => {
    if (step === "code") codeInputRef.current?.focus();
  }, [step]);

  const onRequest = async () => {
    setBusy(true);
    setError(null);
    setMessage(null);

    const result = await requestPhoneCode({ phone, region });

    if (result.ok) {
      setStep("code");
      setMessage(t("notification.sms.sent", { phone: result.phone, minutes: result.expiresInMinutes }));
      setCooldown(60);
    } else if (result.tooMany) {
      // 남용 방지 제한이다. 오류가 아니라 정상 응답이므로 그대로 알려 준다.
      setError(result.message);
      setCooldown(Math.min(result.retryAfterSeconds ?? 60, 3600));
    } else {
      setError(result.message);
    }
    setBusy(false);
  };

  const onConfirm = async () => {
    setBusy(true);
    setError(null);

    const result = await confirmPhoneCode({ phone, region, code });

    if (result.ok && result.verified) {
      setStep("done");
      setMessage(t("notification.sms.done"));
      onVerified?.(result.preference);
    } else {
      // 남은 시도 횟수를 보여 준다. 알려주지 않으면 사용자는 왜 갑자기 막혔는지 모른다.
      setError(
        typeof result.attemptsLeft === "number" && result.attemptsLeft > 0
          ? t("notification.sms.attempts_left", { message: result.message, count: result.attemptsLeft })
          : result.message
      );
      setCode("");
    }
    setBusy(false);
  };

  if (step === "done") {
    return (
      <div className="sms-verify">
        <div className="sms-verify__ok">
          <IoCheckmarkCircle size={18} style={{ verticalAlign: "-3px", marginRight: 6 }} />
          {message}
        </div>
      </div>
    );
  }

  return (
    <div className="sms-verify">
      <div style={{ fontWeight: 600, display: "flex", alignItems: "center", gap: 6 }}>
        <IoPhonePortraitOutline size={18} />
        {title ?? t("notification.sms.title")}
      </div>

      {step === "phone" && (
        <>
          <div className="sms-verify__row">
            <select
              value={region}
              onChange={(e) => setRegion(e.target.value)}
              aria-label={t("notification.sms.country")}
            >
              <option value="KR">{t("notification.sms.country.kr")}</option>
              <option value="JP">{t("notification.sms.country.jp")}</option>
            </select>
            <input
              type="tel"
              inputMode="tel"
              autoComplete="tel"
              placeholder={region === "JP" ? "090-1234-5678" : "010-1234-5678"}
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && phone && !busy) onRequest();
              }}
              aria-label={t("notification.sms.phone")}
            />
          </div>
          <button
            type="button"
            className="sms-verify__button"
            onClick={onRequest}
            disabled={busy || !phone || cooldown > 0}
          >
            {cooldown > 0 ? t("notification.sms.retry_in", { seconds: cooldown }) : t("notification.sms.request")}
          </button>
          <div className="sms-verify__hint">
            {t("notification.sms.hint")}
          </div>
        </>
      )}

      {step === "code" && (
        <>
          <input
            ref={codeInputRef}
            className="sms-verify__code"
            type="text"
            inputMode="numeric"
            // 브라우저·OS 가 문자에서 코드를 자동으로 채워 준다. 사용자가 앱을 오갈 필요가 없다.
            autoComplete="one-time-code"
            maxLength={6}
            placeholder="000000"
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
            onKeyDown={(e) => {
              if (e.key === "Enter" && code.length === 6 && !busy) onConfirm();
            }}
            aria-label={t("notification.sms.code")}
          />
          <button
            type="button"
            className="sms-verify__button"
            onClick={onConfirm}
            disabled={busy || code.length !== 6}
          >
            {t("notification.sms.confirm")}
          </button>
          <button
            type="button"
            className="sms-verify__button"
            style={{ background: "transparent", color: "var(--notif-brand)" }}
            onClick={onRequest}
            disabled={busy || cooldown > 0}
          >
            {cooldown > 0 ? t("notification.sms.resend_in", { seconds: cooldown }) : t("notification.sms.resend")}
          </button>
        </>
      )}

      {message && !error && <div className="sms-verify__hint">{message}</div>}
      {error && <div className="sms-verify__error" role="alert">{error}</div>}
    </div>
  );
}
