package com.project.common.util;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.web.multipart.MultipartFile;

/**
 * 업로드한 이미지의 형식을 <b>파일 내용</b>으로 정한다 — S3 에 저장할 Content-Type 은 이것이다.
 *
 * <p>🔒 예전에는 클라이언트가 보낸 {@code Content-Type} 을 그대로 S3 메타데이터에 넣었고, 형식 검사는 파일 이름의 확장자뿐이었다
 * (리뷰 이미지는 그것도 없었다). {@code x.png} 이름에 {@code Content-Type: text/html} 로 HTML 을 올리면 공개 경로에 HTML 로 제공되는
 * 객체가 생겼다 — 우리 도메인에서 도는 스크립트다. 이제 앞부분 바이트(시그니처)가 png · jpeg · gif · webp 중 하나여야 하고,
 * Content-Type 은 그 형식의 것으로 서버가 정한다.
 *
 * <p>[JA] アップロードした画像の形式を<b>ファイルの内容</b>で決める — S3 に保存する Content-Type はこれである。以前はクライアントが送った
 * {@code Content-Type} をそのまま入れ、形式の検査は拡張子だけだった(レビュー画像はそれもなかった)。今は先頭バイトで判定する。
 */
public final class ImageUploads {

    private ImageUploads() {
    }

    /** 이미지가 아니면 {@link IllegalArgumentException}(문구는 손님에게 보인다). ／ [JA] 画像でなければ例外。 */
    public static String contentTypeOf(MultipartFile file) throws IOException {
        byte[] head = new byte[12];
        int n;
        try (InputStream in = file.getInputStream()) {
            n = in.readNBytes(head, 0, head.length);
        }
        String type = sniff(head, n);
        if (type == null) {
            throw new IllegalArgumentException("이미지 파일(png · jpg · gif · webp)만 올릴 수 있습니다.");
        }
        return type;
    }

    /** 앞부분 바이트로 이미지 형식 — 모르면 null. ／ [JA] 先頭バイトで画像形式 — 不明なら null。 */
    static String sniff(byte[] b, int n) {
        if (n >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (n >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (n >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8' && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return "image/gif";
        }
        if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
