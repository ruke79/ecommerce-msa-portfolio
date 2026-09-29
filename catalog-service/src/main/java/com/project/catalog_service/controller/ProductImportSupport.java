package com.project.catalog_service.controller;

import com.project.common.exception.ClientMessage;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.project.catalog_service.service.ProductImportService;
import com.project.catalog_service.service.ProductImportService.AlreadyRunningException;
import com.project.catalog_service.service.ProductImportService.CatalogNotEmptyException;
import com.project.catalog_service.service.ProductImportService.StorageNotWritableException;

/**
 * CSV 임포트 기동의 HTTP 매핑.
 *
 * <p>두 곳에서 같은 동작이 필요하다 — 새 관리자 경로({@code /api/content/admin/product-import})
 * 와 예전 경로({@code /api/products/load}). 상태 코드가 갈리는 지점이 인가만큼이나 중요해서
 * (409 를 200 으로 돌려주면 화면이 "두 번째 실행도 받아들여졌다"고 믿는다) 한 곳에 모은다.
 */
final class ProductImportSupport {

    private ProductImportSupport() {
    }

    static ResponseEntity<?> launch(ProductImportService service, boolean reset) {
        try {
            // 202: 받아서 시작했다는 뜻이다. 200 이면 "끝났다"로 읽히고, 화면이 곧바로
            // 결과를 보여 주려 한다.
            return ResponseEntity.accepted().body(service.start(reset));
        } catch (AlreadyRunningException e) {
            // 409: 이 잡은 멱등하지 않다. 두 번 돌면 상품이 두 벌 생긴다.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(message(e));
        } catch (CatalogNotEmptyException e) {
            // 409: 요청은 정상이지만 **지금 상태에서는** 할 수 없다. 400 이 아니다 —
            // 고칠 것은 요청이 아니라 카탈로그 상태다. 메시지에 reset=true 안내가 있다.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(message(e));
        } catch (StorageNotWritableException e) {
            // 503: 우리 잘못이 아니라 의존 저장소가 안 된다는 뜻. 재시도가 의미 있는 상황이다.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(message(e));
        } catch (IllegalStateException e) {
            return ResponseEntity.internalServerError().body(message(e));
        }
    }

    private static Map<String, String> message(RuntimeException e) {
        return Map.of("message", ClientMessage.of(e));
    }
}
