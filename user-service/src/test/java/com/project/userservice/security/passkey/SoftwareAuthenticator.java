package com.project.userservice.security.passkey;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;

/**
 * 테스트용 소프트웨어 인증기 — 실제 기기처럼 P-256 키 쌍을 만들고 서명한다(docs/68 §20).
 *
 * <p>목(mock)이 아니라 진짜 서명이다. 그래서 출처(피싱) · RP ID · challenge · UV · 서명 횟수 검사를 라이브러리가 실제로 하는지 볼 수 있다.
 * 각 값은 일부러 틀리게 바꿀 수 있다.
 */
final class SoftwareAuthenticator {

    private static final Base64.Encoder B64U = Base64.getUrlEncoder().withoutPadding();

    final KeyPair keyPair;
    final byte[] credentialId = new byte[16];
    long signCount;

    // 바꿔 볼 수 있는 값들
    String origin;
    String rpId;
    boolean userVerified = true;
    boolean backupEligible = false;
    /** 동기화 패스키(iCloud · Google)처럼 서명 횟수를 늘 0 으로 — 복제 검사가 건너뛰어진다. */
    boolean counterless = false;

    SoftwareAuthenticator(String origin, String rpId) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        this.keyPair = g.generateKeyPair();
        new SecureRandom().nextBytes(credentialId);
        this.origin = origin;
        this.rpId = rpId;
    }

    String credentialIdB64() {
        return B64U.encodeToString(credentialId);
    }

    private byte flags(boolean attested) {
        byte f = AuthenticatorData.BIT_UP;
        if (userVerified) {
            f |= AuthenticatorData.BIT_UV;
        }
        if (backupEligible) {
            f |= AuthenticatorData.BIT_BE;
        }
        if (attested) {
            f |= AuthenticatorData.BIT_AT;
        }
        return f;
    }

    private String clientData(String type, String challenge) {
        return "{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin
                + "\",\"crossOrigin\":false}";
    }

    /** navigator.credentials.create() 결과의 JSON. */
    String register(String challenge) throws Exception {
        AttestedCredentialData acd = new AttestedCredentialData(AAGUID.ZERO, credentialId,
                EC2COSEKey.create(keyPair, COSEAlgorithmIdentifier.ES256));
        AuthenticatorData<com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput> authData =
                new AuthenticatorData<>(sha256(rpId), flags(true), signCount, acd);
        byte[] attestation = new AttestationObjectConverter(new ObjectConverter())
                .convertToBytes(new AttestationObject(authData, new NoneAttestationStatement()));
        String clientDataJson = clientData("webauthn.create", challenge);
        return "{\"id\":\"" + credentialIdB64() + "\",\"rawId\":\"" + credentialIdB64() + "\",\"type\":\"public-key\","
                + "\"response\":{\"clientDataJSON\":\"" + b64(clientDataJson.getBytes(StandardCharsets.UTF_8))
                + "\",\"attestationObject\":\"" + b64(attestation) + "\",\"transports\":[\"internal\",\"hybrid\"]},"
                + "\"clientExtensionResults\":{}}";
    }

    /** navigator.credentials.get() 결과의 JSON — 서명할 때마다 횟수를 올린다(실제 기기처럼). */
    String assertion(String challenge, String userHandleB64) throws Exception {
        if (counterless) {
            return assertionWithCount(challenge, userHandleB64, 0);
        }
        signCount++;
        return assertionWithCount(challenge, userHandleB64, signCount);
    }

    String assertionWithCount(String challenge, String userHandleB64, long count) throws Exception {
        byte[] authData = ByteBuffer.allocate(37).put(sha256(rpId)).put(flags(false)).putInt((int) count).array();
        byte[] clientDataJson = clientData("webauthn.get", challenge).getBytes(StandardCharsets.UTF_8);
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(authData);
        sig.update(MessageDigest.getInstance("SHA-256").digest(clientDataJson));
        return "{\"id\":\"" + credentialIdB64() + "\",\"rawId\":\"" + credentialIdB64() + "\",\"type\":\"public-key\","
                + "\"response\":{\"clientDataJSON\":\"" + b64(clientDataJson) + "\",\"authenticatorData\":\"" + b64(authData)
                + "\",\"signature\":\"" + b64(sig.sign()) + "\""
                + (userHandleB64 == null ? "" : ",\"userHandle\":\"" + userHandleB64 + "\"")
                + "},\"clientExtensionResults\":{}}";
    }

    private static byte[] sha256(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] b) {
        return B64U.encodeToString(b);
    }
}
