package com.project.catalog_service.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.project.catalog_service.dto.ContentDtos.BannerAdminView;
import com.project.catalog_service.dto.ContentDtos.BannerRequest;
import com.project.catalog_service.dto.ContentDtos.NoticeAdminView;
import com.project.catalog_service.dto.ContentDtos.NoticeRequest;
import com.project.catalog_service.dto.ContentDtos.PagedAdminNotices;
import com.project.catalog_service.dto.EventDtos.EventAdminView;
import com.project.catalog_service.dto.EventDtos.EventRequest;
import com.project.catalog_service.dto.HomeDtos.HomeSectionAdminView;
import com.project.catalog_service.dto.response.ProductResponse;
import com.project.catalog_service.dto.HomeDtos.HomeSectionRequest;
import com.project.catalog_service.service.BannerService;
import com.project.catalog_service.service.EventScheduler;
import com.project.catalog_service.service.EventService;
import com.project.catalog_service.service.HomeSectionService;
import com.project.catalog_service.service.HomeViewCache;
import com.project.catalog_service.service.ImageService.StorageStatus;
import com.project.catalog_service.service.NoticeService;
import com.project.catalog_service.service.ProductImportService;
import com.project.catalog_service.service.ProductImportService.ImportStatus;
import com.project.catalog_service.service.ProductService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 콘텐츠 관리 — 관리자만.
 *
 * <p>인가는 {@code AdminWriteInterceptor} 가 <b>경로 접두사</b>로 건다(ADMIN_CONTENT_PREFIX). 그래서 새 관리자
 * 엔드포인트는 반드시 이 컨트롤러(= {@code /api/content/admin}) 아래에 둬야 한다. 다른
 * 자리에 만들면 아무도 막지 않는다 — 메서드마다 애노테이션을 붙이는 방식은 하나 빠뜨려도
 * 티가 안 나지만, 경로 규칙은 빠뜨리면 URL 이 달라져서 눈에 띈다.
 *
 * <p>[JA] コンテンツ管理 — 管理者のみ。
 *      認可は {@code AdminWriteInterceptor} が <b>パスの接頭辞</b> でかける(ADMIN_CONTENT_PREFIX)。そのため新しい管理者
 *      エンドポイントは必ずこのコントローラ(= {@code /api/content/admin})の下に置くこと。別の
 *      場所に作ると誰も塞がない — メソッドごとにアノテーションを付ける方式は 1 つ漏らしても
 *      目立たないが、パス規則は漏らすと URL が変わるので目に付く。
 */
@RestController
@RequestMapping("/api/content/admin")
@RequiredArgsConstructor
public class ContentAdminController {

    private final BannerService bannerService;
    private final NoticeService noticeService;
    private final HomeSectionService homeSectionService;
    /** 섹션을 바꾸면 이 파드의 첫 화면 캐시를 비운다(커밋 뒤 — 서비스 메서드가 돌아온 뒤). 다른 파드는 TTL(30초)로 맞춰진다. */
    // [JA] セクションを変えるとこのポッドの最初の画面のキャッシュを消す(コミット後 — サービスメソッドが戻った後)。他のポッドは TTL(30 秒)で揃う。
    private final HomeViewCache homeView;
    private final ProductImportService productImportService;
    private final ProductService productService;
    private final EventService eventService;
    private final EventScheduler eventScheduler;

    // ── 상품 등록 (일괄) ─────────────────────────────────────────────────
    // [JA] ── 商品登録(一括) ──

    /**
     * 이미지 저장소가 쓰기 가능한지.
     *
     * <p>여기 있는 이유는 <b>인가</b> 때문이다. 이 접두사 아래여야 GET 도 관리자 전용이 된다
     * (클래스 주석 참조). 버킷 이름과 SDK 오류 메시지가 실려 나가므로 공개될 수 없다.
     *
     * <p>등록 화면이 폼을 그리기 전에 부른다. 그러지 않으면 관리자는 긴 폼을 다 채우고
     * 이미지를 올린 뒤에야 500 을 보고, 그게 자격 증명 문제인지 알 방법이 없다.
     *
     * <p>[JA] 画像ストレージが書き込み可能か。
     *      ここにある理由は <b>認可</b> のためである。この接頭辞の下でないと GET も管理者専用にならない
     *      (クラスのコメント参照)。バケット名と SDK のエラーメッセージが載って出るので公開できない。
     *      登録画面がフォームを描く前に呼ぶ。そうしないと管理者は長いフォームを全部埋めて
     *      画像を上げた後でやっと 500 を見ることになり、それが資格情報の問題かを知る方法がない。
     */
    @GetMapping("/storage-health")
    public StorageStatus storageHealth() {
        return productImportService.storage();
    }

    /**
     * 관리자 상품 목록 — 커서 페이징.
     *
     * <p>🐛 예전에 관리자 화면이 쓰던 {@code GET /api/product/products} 는 이름과 달리
     * {@code warmUpProductCaches} 를 거쳐 <b>카탈로그 전체를 한 배열로</b> 돌려주고,
     * 그 결과를 Redis 키 하나에 통째로 담았다(ProductService 주석).
     *
     * <p>여기(= {@code /api/content/admin}) 아래에 두는 이유는 <b>인가</b> 때문이다.
     * {@code AdminWriteInterceptor} 가 경로 접두사로 관리자 여부를 판정하므로, 이 접두사
     * 아래여야 GET 도 관리자 전용이 된다. 예전 엔드포인트는 {@code /api/product/**} 라
     * 누구나 전 카탈로그를 받아 갈 수 있었다.
     *
     * @param cursor   마지막으로 받은 상품 id. 첫 페이지는 {@code -1}(기본값).
     * @param pageSize 한 번에 받을 개수. 서버가 {@code MAX_ADMIN_PAGE_SIZE} 로 자른다.
     *
     * <p>[JA] 管理者の商品一覧 — カーソルページング。
     *      🐛 以前管理者画面が使っていた {@code GET /api/product/products} は名前と違い
     *      {@code warmUpProductCaches} を経て <b>カタログ全体を 1 つの配列で</b> 返し、
     *      その結果を Redis のキー 1 つに丸ごと入れていた(ProductService のコメント)。
     *      ここ(= {@code /api/content/admin})の下に置く理由は <b>認可</b> のためである。
     *      {@code AdminWriteInterceptor} がパスの接頭辞で管理者かどうかを判定するので、この接頭辞の
     *      下でないと GET も管理者専用にならない。以前のエンドポイントは {@code /api/product/**} だったので
     *      誰でも全カタログを持って行けた。
     *      cursor: 最後に受け取った商品 id。最初のページは {@code -1}(既定値)。
     *      pageSize: 一度に受け取る件数。サーバーが {@code MAX_ADMIN_PAGE_SIZE} で切る。
     */
    @GetMapping("/products")
    public ProductResponse products(
            @RequestParam(name = "cursor", required = false, defaultValue = "-1") Long cursor,
            @RequestParam(name = "pageSize", required = false, defaultValue = "20") int pageSize) {
        return productService.getProductsPage(cursor, pageSize);
    }

    // ── 재고 (docs/53) ───────────────────────────────────────────────────
    // [JA] ── 在庫(docs/53) ──

    /**
     * 재고 목록 — 사이즈 id 커서. {@code soldOutOnly=true}(기본)면 품절만 — 품절 경보를 받은 운영자가 "채울 것"
     * 을 보는 화면이다. 이 접두사 아래라 GET 도 관리자 전용이다(AdminWriteInterceptor).
     *
     * <p>[JA] 在庫一覧 — サイズ id のカーソル。{@code soldOutOnly=true}(既定)なら品切れのみ — 品切れ警報を受けた運用者が「補充するもの」
     *      を見る画面である。この接頭辞の下なので GET も管理者専用である(AdminWriteInterceptor)。
     */
    @GetMapping("/stock")
    public java.util.List<ProductService.StockRow> stock(
            @RequestParam(name = "soldOutOnly", defaultValue = "true") boolean soldOutOnly,
            @RequestParam(name = "after", required = false) Long afterSizeId) {
        return productService.stockRows(soldOutOnly, afterSizeId);
    }

    /**
     * 재고를 그 값으로 정한다. 0 에서 채우면 재입고 알림을 신청한 손님에게 알림이 간다.
     * 본문: {@code {"quantity": 10}}.
     *
     * <p>[JA] 在庫をその値に決める。0 から補充すると再入荷通知を申し込んだ客に通知が行く。
     *      本文: {@code {"quantity": 10}}。
     */
    @PutMapping("/stock/{sizeId}")
    public ResponseEntity<?> setStock(@PathVariable("sizeId") Long sizeId,
            @RequestBody java.util.Map<String, Object> body) {
        Object raw = body == null ? null : body.get("quantity");
        int quantity;
        try {
            quantity = raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "quantity 는 숫자여야 합니다."));
        }
        if (quantity < 0 || quantity > 1_000_000) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "quantity 는 0 이상 1,000,000 이하여야 합니다."));
        }
        ProductService.StockRow row = productService.setStock(sizeId, quantity);
        return row == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(row);
    }

    @GetMapping("/product-import")
    public ImportStatus productImportStatus() {
        return productImportService.status();
    }

    /**
     * 임포트 시작.
     *
     * <p>{@code reset=true} 는 <b>카탈로그를 비우고</b> 시작한다 — 되돌릴 수 없다. 기본값은
     * false 이고, 그때 카탈로그가 비어 있지 않으면 409 로 거절한다(이 잡은 멱등하지 않다).
     *
     * <p>[JA] インポート開始。
     *      {@code reset=true} は <b>カタログを空にして</b> 始める — 戻せない。既定値は
     *      false で、そのときカタログが空でなければ 409 で拒否する(このジョブは冪等ではない)。
     */
    @PostMapping("/product-import")
    public ResponseEntity<?> startProductImport(
            @RequestParam(name = "reset", defaultValue = "false") boolean reset) {
        return ProductImportSupport.launch(productImportService, reset);
    }

    // ── 홈 섹션 ──────────────────────────────────────────────────────────
    // [JA] ── ホームセクション ──

    @GetMapping("/home-sections")
    public List<HomeSectionAdminView> homeSections() {
        return homeSectionService.all();
    }

    @PostMapping("/home-sections")
    public ResponseEntity<?> createHomeSection(@Valid @RequestBody HomeSectionRequest request) {
        try {
            HomeSectionAdminView created = homeSectionService.create(request);
            homeView.invalidate();
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            // 없는 카테고리를 가리키면 400 이다. 저장을 허용하면 증상이 "홈에 그 섹션만
            // 안 나온다"로 나타나고, 어디가 틀렸는지 화면에서는 알 수 없다.
            // [JA] 存在しないカテゴリを指せば 400 である。保存を許すと症状は「ホームでそのセクションだけ
            //      出ない」として現れ、どこが間違っているか画面ではわからない。
            return ResponseEntity.badRequest().body(ContentController.message(e));
        }
    }

    @PutMapping("/home-sections/{id}")
    public ResponseEntity<?> updateHomeSection(@PathVariable("id") Long id,
            @Valid @RequestBody HomeSectionRequest request) {
        try {
            HomeSectionAdminView updated = homeSectionService.update(id, request);
            homeView.invalidate();
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ContentController.message(e));
        }
    }

    @DeleteMapping("/home-sections/{id}")
    public ResponseEntity<?> deleteHomeSection(@PathVariable("id") Long id) {
        try {
            homeSectionService.delete(id);
            homeView.invalidate();
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return notFound(e);
        }
    }

    // ── 배너 ────────────────────────────────────────────────────────────
    // [JA] ── バナー ──

    @GetMapping("/banners")
    public List<BannerAdminView> banners() {
        return bannerService.all();
    }

    @PostMapping("/banners")
    public ResponseEntity<?> createBanner(@Valid @RequestBody BannerRequest request) {
        try {
            return ResponseEntity.ok(bannerService.create(request));
        } catch (IllegalArgumentException e) {
            // 링크 모양이 틀렸다(SafeLinks) — 관리자가 고칠 수 있는 실패다.
            // [JA] リンクの形が間違っている(SafeLinks) — 管理者が直せる失敗である。
            return ResponseEntity.badRequest().body(ContentController.message(e));
        }
    }

    @PutMapping("/banners/{id}")
    public ResponseEntity<?> updateBanner(@PathVariable("id") Long id,
            @Valid @RequestBody BannerRequest request) {
        try {
            return ResponseEntity.ok(bannerService.update(id, request));
        } catch (IllegalArgumentException e) {
            return notFound(e);
        }
    }

    @DeleteMapping("/banners/{id}")
    public ResponseEntity<?> deleteBanner(@PathVariable("id") Long id) {
        try {
            bannerService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return notFound(e);
        }
    }

    // ── 공지 ────────────────────────────────────────────────────────────
    // [JA] ── 告知 ──

    @GetMapping("/notices")
    public PagedAdminNotices notices(@RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return noticeService.all(page, size);
    }

    @GetMapping("/notices/{id}")
    public ResponseEntity<?> notice(@PathVariable("id") Long id) {
        try {
            return ResponseEntity.ok(noticeService.one(id));
        } catch (IllegalArgumentException e) {
            return notFound(e);
        }
    }

    @PostMapping("/notices")
    public ResponseEntity<NoticeAdminView> createNotice(@Valid @RequestBody NoticeRequest request) {
        return ResponseEntity.ok(noticeService.create(request));
    }

    @PutMapping("/notices/{id}")
    public ResponseEntity<?> updateNotice(@PathVariable("id") Long id,
            @Valid @RequestBody NoticeRequest request) {
        try {
            return ResponseEntity.ok(noticeService.update(id, request));
        } catch (IllegalArgumentException e) {
            return notFound(e);
        }
    }

    @DeleteMapping("/notices/{id}")
    public ResponseEntity<?> deleteNotice(@PathVariable("id") Long id) {
        try {
            noticeService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return notFound(e);
        }
    }

    // ── 이벤트 / 기획전 ──────────────────────────────────────────────────
    //
    // 할인율은 여기(event_product)에만 있고 상품 조회 시 합성된다 — ProductSku.discount 를
    // 덮어쓰지 않는다(EventPricing 주석). 그래서 이 화면에서 무엇을 바꾸든 상품 데이터는
    // 그대로이고, 기획전을 지우면 가격이 원래대로 돌아온다.
    //
    // 저장·삭제 뒤에 eventScheduler.refreshNow() 를 부르는 이유: 할인 맵은 1분마다
    // 갱신된다. 그게 없으면 관리자는 저장한 뒤 최대 1분 동안 자기 변경이 반영되지 않는
    // 것을 보고 저장이 실패했다고 생각한다.
    // [JA] ── イベント / 企画展 ──
    //      割引率はここ(event_product)にだけあり、商品照会時に合成される — ProductSku.discount を
    //      上書きしない(EventPricing のコメント)。だからこの画面で何を変えても商品データは
    //      そのままで、企画展を消せば価格は元に戻る。
    //      保存・削除の後に eventScheduler.refreshNow() を呼ぶ理由: 割引マップは 1 分ごとに
    //      更新される。それがないと管理者は保存後最大 1 分間自分の変更が反映されない
    //      のを見て、保存が失敗したと思う。

    @GetMapping("/events")
    public List<EventAdminView> events() {
        return eventService.all();
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<?> event(@PathVariable("id") Long id) {
        try {
            return ResponseEntity.ok(eventService.one(id));
        } catch (IllegalArgumentException e) {
            return notFound(e);
        }
    }

    @PostMapping("/events")
    public ResponseEntity<?> createEvent(@Valid @RequestBody EventRequest request) {
        try {
            EventAdminView created = eventService.create(request);
            eventScheduler.refreshNow();
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException e) {
            // 없는 상품을 대상에 넣었다. 저장을 허용하면 증상이 "그 상품만 할인이 안
            // 걸린다"로 나타나고, 화면에서는 무엇이 틀렸는지 알 수 없다.
            // [JA] 存在しない商品を対象に入れた。保存を許すと症状は「その商品だけ割引が
            //      かからない」として現れ、画面では何が間違っているかわからない。
            return ResponseEntity.badRequest().body(ContentController.message(e));
        }
    }

    @PutMapping("/events/{id}")
    public ResponseEntity<?> updateEvent(@PathVariable("id") Long id,
            @Valid @RequestBody EventRequest request) {
        try {
            EventAdminView updated = eventService.update(id, request);
            eventScheduler.refreshNow();
            return ResponseEntity.ok(updated);
        } catch (com.project.common.domain.exception.StateConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ContentController.message(e));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ContentController.message(e));
        }
    }

    /** 지금 끝낸다. 삭제와 다르다 — 진행 기록은 남는다. */
    // [JA] 今すぐ終わらせる。削除とは違う — 実施記録は残る。
    @PostMapping("/events/{id}/end")
    public ResponseEntity<?> endEvent(@PathVariable("id") Long id) {
        try {
            EventAdminView ended = eventService.endNow(id);
            eventScheduler.refreshNow();
            return ResponseEntity.ok(ended);
        } catch (com.project.common.domain.exception.StateConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ContentController.message(e));
        } catch (IllegalArgumentException e) {
            return notFound(e);
        }
    }

    @DeleteMapping("/events/{id}")
    public ResponseEntity<?> deleteEvent(@PathVariable("id") Long id) {
        try {
            eventService.delete(id);
            eventScheduler.refreshNow();
            return ResponseEntity.noContent().build();
        } catch (com.project.common.domain.exception.StateConflictException e) {
            // 진행 중인 기획전은 지울 수 없다 — 할인이 조용히 사라지고 기록도 안 남는다.
            // [JA] 進行中の企画展は消せない — 割引が黙って消え、記録も残らない。
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ContentController.message(e));
        } catch (IllegalArgumentException e) {
            return notFound(e);
        }
    }

    private ResponseEntity<?> notFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ContentController.message(e));
    }
}
