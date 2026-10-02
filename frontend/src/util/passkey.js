/**
 * 패스키(WebAuthn) 브라우저 쪽 — 서버가 준 옵션(바이트는 base64url 문자열)을 navigator.credentials 가 받는 모양으로 바꾸고,
 * 결과를 서버가 읽는 JSON 으로 되돌린다(user-service docs/68 §20).
 *
 * 비밀 키는 기기를 떠나지 않는다 — 이 파일이 다루는 것은 공개 키 · 서명 · challenge 뿐이다.
 * [JA] パスキーのブラウザ側 — サーバーのオプション(base64url)を navigator.credentials の形に変え、結果を JSON に戻す。秘密鍵は端末から出ない。
 */

export const bufferToBase64url = (buffer) => {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

export const base64urlToBuffer = (value) => {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
};

/** 이 브라우저가 패스키를 쓸 수 있는가 — 안 되면 버튼을 그리지 않는다. */
export const isPasskeySupported = () =>
  typeof window !== "undefined" &&
  typeof window.PublicKeyCredential === "function" &&
  typeof navigator !== "undefined" &&
  !!navigator.credentials;

export const toCreationOptions = (options) => ({
  ...options,
  challenge: base64urlToBuffer(options.challenge),
  user: { ...options.user, id: base64urlToBuffer(options.user.id) },
  excludeCredentials: (options.excludeCredentials ?? []).map((c) => ({ ...c, id: base64urlToBuffer(c.id) })),
});

export const toRequestOptions = (options) => ({
  ...options,
  challenge: base64urlToBuffer(options.challenge),
  allowCredentials: (options.allowCredentials ?? []).map((c) => ({ ...c, id: base64urlToBuffer(c.id) })),
});

const optional = (buffer) => (buffer ? bufferToBase64url(buffer) : undefined);

/** PublicKeyCredential → 서버가 읽는 JSON(WebAuthn Level 3 의 toJSON 과 같은 모양). */
export const credentialToJSON = (credential) => {
  const { response } = credential;
  const json = {
    id: credential.id,
    rawId: bufferToBase64url(credential.rawId),
    type: credential.type,
    clientExtensionResults: credential.getClientExtensionResults?.() ?? {},
    authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
    response: { clientDataJSON: bufferToBase64url(response.clientDataJSON) },
  };
  if (response.attestationObject) {
    json.response.attestationObject = bufferToBase64url(response.attestationObject);
    json.response.transports = response.getTransports?.() ?? [];
  } else {
    json.response.authenticatorData = bufferToBase64url(response.authenticatorData);
    json.response.signature = bufferToBase64url(response.signature);
    json.response.userHandle = optional(response.userHandle);
  }
  return json;
};

/** 사용자가 기기 창을 닫았거나 시간이 지났다 — 오류로 보여 주지 않는다. */
export const isUserCancel = (error) => error?.name === "NotAllowedError" || error?.name === "AbortError";
