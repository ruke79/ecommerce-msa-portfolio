package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.project.userservice.constants.AwsS3Properties;

/**
 * 리뷰 · 프로필 이미지 업로드 — S3 의 Content-Type 은 파일 내용으로 서버가 정한다. HTML 은 올라가지 않는다.
 *
 * <p>[JA] レビュー・プロフィール画像のアップロード — S3 の Content-Type はファイルの内容でサーバーが決める。HTML は上がらない。
 */
@DisplayName("ImageService — 업로드 Content-Type")
class ImageServiceContentTypeTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};

    private final AmazonS3 s3 = mock(AmazonS3.class);
    private final ImageService service;

    ImageServiceContentTypeTest() throws Exception {
        AwsS3Properties props = new AwsS3Properties();
        props.setBucket("bucket");
        when(s3.getUrl(anyString(), anyString())).thenReturn(new URL("https://bucket.s3/x"));
        service = new ImageService(props, s3);
    }

    @Test
    @DisplayName("🔴 클라이언트가 text/html 이라고 보내도 PNG 면 image/png 로 저장한다")
    void storesServerDecidedType() throws Exception {
        service.upload(new MockMultipartFile("image", "x.png", "text/html", PNG), "reviews/abc");

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture());
        assertThat(put.getValue().getMetadata().getContentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("🔴 이미지가 아니면(HTML) 올리지 않는다")
    void rejectsNonImages() {
        var html = new MockMultipartFile("image", "x.png", "text/html", "<html></html>".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.upload(html, "reviews/abc")).isInstanceOf(IllegalArgumentException.class);
        verify(s3, never()).putObject(any(PutObjectRequest.class));
    }
}
