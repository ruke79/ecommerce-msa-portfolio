package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 관리자가 올린 상품 이미지는 공개 읽기 접두사({@code images/}) 아래에 저장된다.
 *
 * <p>🐛 예전: 키가 무작위 이름 하나뿐이라({@code images/} · 확장자 없음) 버킷 정책({@code images/*} 공개 읽기) 밖이었다 — 업로드는
 * 성공하는데 화면에서는 403, 관리자가 등록한 상품 이미지가 모두 깨졌다.
 *
 * <p>[JA] 管理者がアップロードした商品画像は公開読み取りの接頭辞 {@code images/} の下に保存される。
 */
@DisplayName("상품 이미지 키 — images/ 아래")
class ProductImageKeyTest {

    @Test
    @DisplayName("🔴 키는 images/product/<이름>.<확장자> — 공개 정책 images/* 안")
    void keyIsUnderImages() {
        String key = ProductService.productImageKey(new MockMultipartFile("f", "Photo.JPG", "image/jpeg", new byte[] {1}), "abc");
        assertThat(key).isEqualTo("images/product/abc.jpg");
    }

    @Test
    @DisplayName("🔴 ProductService 의 업로드 키는 모두 productImageKey 를 거친다 — 무작위 이름을 그대로 키로 쓰지 않는다")
    void everyUploadUsesTheKey() throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        Path root = cwd.getFileName().toString().equals("catalog-service") ? cwd : cwd.resolve("catalog-service");
        String src = Files.readString(root.resolve("src/main/java/com/project/catalog_service/service/ProductService.java"));
        Matcher m = Pattern.compile("FileUtil\\.getRandomFilename\\(\\)").matcher(src);
        int uses = 0;
        while (m.find()) {
            uses++;
            String line = src.substring(src.lastIndexOf('\n', m.start()) + 1, src.indexOf('\n', m.start()));
            assertThat(line).as("무작위 이름은 productImageKey(...) 안에서만").contains("productImageKey(");
        }
        assertThat(uses).as("업로드 지점을 찾았다").isGreaterThanOrEqualTo(4);
    }
}
