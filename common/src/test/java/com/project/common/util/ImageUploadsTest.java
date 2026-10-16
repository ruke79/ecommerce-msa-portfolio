package com.project.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 업로드 이미지의 형식은 파일 내용으로 — 이름 · 클라이언트의 Content-Type 을 믿지 않는다.
 *
 * <p>[JA] アップロード画像の形式はファイルの内容で — 名前・クライアントの Content-Type を信じない。
 */
@DisplayName("ImageUploads — 이미지 형식은 내용으로")
class ImageUploadsTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};

    @Test
    @DisplayName("🔴 x.png 이름 · text/html 로 올린 HTML 은 거절 — 공개 경로에 HTML 로 제공되는 객체가 생기지 않는다")
    void htmlDisguisedAsPngIsRejected() {
        var html = new MockMultipartFile("image", "x.png", "text/html",
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ImageUploads.contentTypeOf(html)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미지 파일");
    }

    @Test
    @DisplayName("🔴 Content-Type 은 내용의 형식 — 클라이언트가 text/html 이라고 해도 PNG 면 image/png")
    void contentTypeComesFromTheBytes() throws Exception {
        assertThat(ImageUploads.contentTypeOf(new MockMultipartFile("image", "x.png", "text/html", PNG))).isEqualTo("image/png");
    }

    @Test
    @DisplayName("jpeg · gif · webp 도 알아본다")
    void knownFormats() {
        assertThat(ImageUploads.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}, 4)).isEqualTo("image/jpeg");
        assertThat(ImageUploads.sniff("GIF89a".getBytes(StandardCharsets.US_ASCII), 6)).isEqualTo("image/gif");
        assertThat(ImageUploads.sniff("RIFF\0\0\0\0WEBP".getBytes(StandardCharsets.US_ASCII), 12)).isEqualTo("image/webp");
        assertThat(ImageUploads.sniff(new byte[] {1, 2}, 2)).isNull();
    }
}
