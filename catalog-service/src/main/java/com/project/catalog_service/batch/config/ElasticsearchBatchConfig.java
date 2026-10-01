package com.project.catalog_service.batch.config;

import com.project.catalog_service.document.*;
import com.project.catalog_service.model.Category;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.ProductColor;
import com.project.catalog_service.model.ProductDetails;
import com.project.catalog_service.model.ProductQA;
import com.project.catalog_service.model.ProductSku;
import com.project.catalog_service.model.ProductSize;
import com.project.catalog_service.model.Subcategory;
import com.project.catalog_service.repository.elasticsearch.CategoryDocumentRepository;
import com.project.catalog_service.repository.elasticsearch.ProductColorDocumentRepository;
import com.project.catalog_service.repository.elasticsearch.ProductDetailsDocumentRepository;
import com.project.catalog_service.repository.elasticsearch.ProductDocumentRepository;
import com.project.catalog_service.repository.elasticsearch.ProductQADocumentRepository;
import com.project.catalog_service.repository.elasticsearch.ProductSizeDocumentRepository;
import com.project.catalog_service.repository.elasticsearch.ProductSkuDocumentRepository;
import com.project.catalog_service.repository.elasticsearch.SubcategoryDocumentRepository;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JpaPagingItemReader;

import com.project.catalog_service.batch.reader.KeysetItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.StepExecution;

import org.springframework.batch.core.listener.StepExecutionListenerSupport;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.elasticsearch.core.index.AliasAction;
import org.springframework.data.elasticsearch.core.index.AliasActions;
import org.springframework.data.elasticsearch.core.index.AliasActionParameters;

@Configuration
public class ElasticsearchBatchConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager batchTransactionManager;
    private final EntityManagerFactory entityManagerFactory;
    private final ElasticsearchOperations elasticsearchOperations;
    private final ProductDocumentRepository productDocumentRepository;
    private final CategoryDocumentRepository categoryDocumentRepository;
    private final ProductColorDocumentRepository productColorDocumentRepository;
    private final ProductDetailsDocumentRepository productDetailsDocumentRepository;
    private final ProductQADocumentRepository productQADocumentRepository;
    private final ProductSkuDocumentRepository productSkuDocumentRepository;
    private final ProductSizeDocumentRepository productSizeDocumentRepository;
    private final SubcategoryDocumentRepository subcategoryDocumentRepository;

    @Value("${chunk.size:1000}")
    private int CHUNK_SIZE;

    @Value("${elasticsearch.alias.name.product:product-alias}")
    private String productAliasName;
    @Value("${elasticsearch.alias.name.category:category-alias}")
    private String categoryAliasName;
    @Value("${elasticsearch.alias.name.product-color:product-color-alias}")
    private String productColorAliasName;

    public ElasticsearchBatchConfig(JobRepository jobRepository,
                                    @Qualifier("batchTransactionManager") PlatformTransactionManager batchTransactionManager,
                                    @Qualifier("metaTransactionManager") PlatformTransactionManager metaTransactionManager,
                                    @Qualifier("entityManagerFactory") EntityManagerFactory entityManagerFactory,
                                    ElasticsearchOperations elasticsearchOperations,
                                    ProductDocumentRepository productDocumentRepository,
                                    CategoryDocumentRepository categoryDocumentRepository,
                                    ProductColorDocumentRepository productColorDocumentRepository,
                                    ProductDetailsDocumentRepository productDetailsDocumentRepository,
                                    ProductQADocumentRepository productQADocumentRepository,
                                    ProductSkuDocumentRepository productSkuDocumentRepository,
                                    ProductSizeDocumentRepository productSizeDocumentRepository,
                                    SubcategoryDocumentRepository subcategoryDocumentRepository) {
        this.jobRepository = jobRepository;
        this.batchTransactionManager = batchTransactionManager;
        this.entityManagerFactory = entityManagerFactory;
        this.elasticsearchOperations = elasticsearchOperations;
        this.productDocumentRepository = productDocumentRepository;
        this.categoryDocumentRepository = categoryDocumentRepository;
        this.productColorDocumentRepository = productColorDocumentRepository;
        this.productDetailsDocumentRepository = productDetailsDocumentRepository;
        this.productQADocumentRepository = productQADocumentRepository;
        this.productSkuDocumentRepository = productSkuDocumentRepository;
        this.productSizeDocumentRepository = productSizeDocumentRepository;
        this.subcategoryDocumentRepository = subcategoryDocumentRepository;
    }

    // --- TaskExecutor 빈 추가: 병렬 처리를 위한 스레드 풀 정의 ---
    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5); // 기본 스레드 수
        executor.setMaxPoolSize(10); // 최대 스레드 수
        executor.setQueueCapacity(25); // 큐 사이즈
        executor.setThreadNamePrefix("batch-thread-");
        // 재색인을 시작한 요청의 traceId 를 배치 스레드로 넘긴다(docs/51 §3).
        executor.setTaskDecorator(new com.project.common.logging.MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    // --- Readers (수정된 부분) ---
    /**
     * 상품 — id 키셋 페이징(KeysetItemReader). 예전에는 컬렉션 넷을 JOIN FETCH 한 쿼리에 OFFSET 페이징을 걸어 Hibernate 가
     * <b>매 페이지마다 결과 전체를 메모리에 올렸다</b>(HHH90003004) — 200만 개면 OOM 이다(docs/19 §5.2).
     *
     * <p>매퍼(ProductDocumentMapper)가 읽는 것만 채운다: 카테고리 · SKU(색상) 는 본 쿼리에서, 소분류 · 사이즈는 컬렉션마다 쿼리 하나.
     * details · questions 는 매퍼가 쓰지 않아 읽지 않는다(예전에는 읽고 버렸다).
     */
    // [JA] 商品 — id キーセットページング。旧実装はコレクション 4 つを JOIN FETCH したクエリに OFFSET ページングをかけ、Hibernate が
    //      ページごとに結果全体をメモリに載せていた(HHH90003004)。マッパーが読むものだけを満たす。
    @Bean
    public KeysetItemReader<Product> productReader() {
        return new KeysetItemReader<>(entityManagerFactory, "Product", "productId",
                "SELECT DISTINCT p FROM Product p "
                        + "LEFT JOIN FETCH p.category "
                        + "LEFT JOIN FETCH p.skus s "
                        + "LEFT JOIN FETCH s.color "
                        + "WHERE p.productId IN :ids",
                Product::getProductId,
                (em, ids) -> {
                    // 같은 영속성 컨텍스트라 이미 읽은 상품 · SKU 의 컬렉션에 붙는다(List 두 개를 한 쿼리에 FETCH 하면
                    // MultipleBagFetchException — docs/16 B-26).
                    em.createQuery("SELECT DISTINCT p FROM Product p "
                            + "LEFT JOIN FETCH p.subcategories ps LEFT JOIN FETCH ps.subcategory "
                            + "WHERE p.productId IN :ids").setParameter("ids", ids).getResultList();
                    em.createQuery("SELECT DISTINCT s FROM ProductSku s LEFT JOIN FETCH s.sizes "
                            + "WHERE s.product.productId IN :ids").setParameter("ids", ids).getResultList();
                },
                CHUNK_SIZE);
    }

    @Bean
    public JpaPagingItemReader<Category> categoryReader() {
        return new JpaPagingItemReaderBuilder<Category>()
                .name("categoryReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT c FROM Category c")
                .pageSize(CHUNK_SIZE)
                .saveState(false)
                .build();
    }
    
    @Bean
    public JpaPagingItemReader<ProductColor> productColorReader() {
        return new JpaPagingItemReaderBuilder<ProductColor>()
                .name("productColorReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT pc FROM ProductColor pc")
                .pageSize(CHUNK_SIZE)
                .saveState(false)
                .build();
    }

    @Bean
    public JpaPagingItemReader<ProductDetails> productDetailsReader() {
        return new JpaPagingItemReaderBuilder<ProductDetails>()
                .name("productDetailsReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT pd FROM ProductDetails pd")
                .pageSize(CHUNK_SIZE)
                .saveState(false)
                .build();
    }

    @Bean
    public JpaPagingItemReader<ProductQA> productQAReader() {
        return new JpaPagingItemReaderBuilder<ProductQA>()
                .name("productQAReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT qa FROM ProductQA qa")
                .pageSize(CHUNK_SIZE)
                .saveState(false)
                .build();
    }

    /** 수백만 행 — OFFSET 페이징이면 앞 페이지를 매번 다시 건너뛴다. id 키셋으로 읽는다(docs/19 §5.2). */
    // [JA] 数百万行 — OFFSET ページングだと前のページを毎回読み飛ばす。id キーセットで読む。
    @Bean
    public KeysetItemReader<ProductSku> productSkuReader() {
        return new KeysetItemReader<>(entityManagerFactory, "ProductSku", "skuproductId",
                "SELECT e FROM ProductSku e WHERE e.skuproductId IN :ids", ProductSku::getSkuproductId, null, CHUNK_SIZE);
    }

    /** 수백만 행 — OFFSET 페이징이면 앞 페이지를 매번 다시 건너뛴다. id 키셋으로 읽는다(docs/19 §5.2). */
    // [JA] 数百万行 — OFFSET ページングだと前のページを毎回読み飛ばす。id キーセットで読む。
    @Bean
    public KeysetItemReader<ProductSize> productSizeReader() {
        return new KeysetItemReader<>(entityManagerFactory, "ProductSize", "sizeId",
                "SELECT e FROM ProductSize e WHERE e.sizeId IN :ids", ProductSize::getSizeId, null, CHUNK_SIZE);
    }

    @Bean
    public JpaPagingItemReader<Subcategory> subcategoryReader() {
        return new JpaPagingItemReaderBuilder<Subcategory>()
                .name("subcategoryReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT s FROM Subcategory s")
                .pageSize(CHUNK_SIZE)
                .saveState(false)
                .build();
    }

    // --- Processors ---
    // 매핑은 ProductDocumentMapper 가 소유한다 — 실시간 단건 색인(ProductIndexService)과
    // 같은 코드를 쓴다. 여기 람다에 별도 매핑이 있으면 두 경로가 반드시 어긋나고, 그
    // 차이는 검색 결과가 이상해질 때까지 아무도 모른다. (덤으로 예전 람다는 shipping·
    // category 무방비 역참조라 그 값이 빈 상품 하나로 배치 전체가 죽는 형태였다.)
    @Bean
    public ItemProcessor<Product, ProductDocument> productProcessor() {
        return com.project.catalog_service.mapper.ProductDocumentMapper::toDocument;
    }

    @Bean
    public ItemProcessor<Category, CategoryDocument> categoryProcessor() {
        return category -> CategoryDocument.builder()
                .categoryId(category.getCategoryId().toString())
                .categoryName(category.getCategoryName())
                .slug(category.getSlug())
                .createdAt(category.getCreatedAt().toString())
                .build();
    }

    @Bean
    public ItemProcessor<ProductColor, ProductColorDocument> productColorProcessor() {
        return color -> ProductColorDocument.builder()
                .colorId(color.getColorId().toString())
                .color(color.getColor())
                .build();
    }

    @Bean
    public ItemProcessor<ProductDetails, ProductDetailsDocument> productDetailsProcessor() {
        return details -> ProductDetailsDocument.builder()
                .pdetailId(details.getPdetailId().toString())
                .productId(details.getProduct().getProductId().toString())
                .name(details.getName())
                .value(details.getValue())
                .build();
    }

    @Bean
    public ItemProcessor<ProductQA, ProductQADocument> productQAProcessor() {
        return qa -> ProductQADocument.builder()
                .qaId(qa.getQaId().toString())
                .productId(qa.getProduct().getProductId().toString())
                .question(qa.getQuestion())
                .answer(qa.getAnswer())
                .createdAt(qa.getCreatedAt().toString())
                .build();
    }

    @Bean
    public ItemProcessor<ProductSku, ProductSkuDocument> productSkuProcessor() {
        return sku -> ProductSkuDocument.builder()
                .skuproductId(sku.getSkuproductId().toString())
                .productId(sku.getProduct().getProductId().toString())
                .sku(sku.getSku())
                .discount(sku.getDiscount())
                .sold(sku.getSold())
                .build();
    };

    @Bean
    public ItemProcessor<ProductSize, ProductSizeDocument> productSizeProcessor() {
        return size -> ProductSizeDocument.builder()
                .sizeId(size.getSizeId().toString())
                .skuproductId(size.getSku().getSkuproductId().toString())
                .size(size.getSize())
                .quantity(size.getQuantity())
                .price(size.getPrice().floatValue())
                .build();
    };

    @Bean
    public ItemProcessor<Subcategory, SubcategoryDocument> subcategoryProcessor() {
        return subcategory -> SubcategoryDocument.builder()
                .subcategoryId(subcategory.getSubcategoryId().toString())
                .categoryId(subcategory.getCategory().getCategoryId().toString())
                .subcategoryName(subcategory.getSubcategoryName())
                .slug(subcategory.getSlug())
                .build();
    }

    // --- Writers (StepExecutionListener를 사용하여 JobParameters를 주입받도록 수정) ---
    // ItemWriter는 StepExecutionListener를 구현해야 StepExecution에 접근할 수 있습니다.
    public class ProductWriter implements ItemWriter<ProductDocument>, StepExecutionListener {
        private String newIndexName;

        @Override
        public void beforeStep(StepExecution stepExecution) {
            this.newIndexName = stepExecution.getJobParameters().getString("newIndexName", "default");

            // (버그 수정, docs/06 CAT-9) 기존에는 save() 가 인덱스를 동적 매핑으로 자동 생성해
            // ProductDocument 의 @Setting(nori/kuromoji 애널라이저)과 @Field(Nested) 선언이
            // 전부 무시됐다(기본 standard 애널라이저 + object 강등). 색인 전에 명시적
            // settings/mappings 로 인덱스를 먼저 생성해 선언을 실제로 적용한다.
            String indexName = this.newIndexName + "-products";
            var targetOps = elasticsearchOperations.indexOps(IndexCoordinates.of(indexName));
            if (!targetOps.exists()) {
                var typedOps = elasticsearchOperations.indexOps(ProductDocument.class);
                targetOps.create(typedOps.createSettings(), typedOps.createMapping());
            }
        }

        @Override
        public void write(Chunk<? extends ProductDocument> chunk) {
            String indexName = this.newIndexName + "-products";
            elasticsearchOperations.save(chunk.getItems(), IndexCoordinates.of(indexName));
        }
    }

    public class CategoryWriter implements ItemWriter<CategoryDocument>, StepExecutionListener {
        private String newIndexName;

        @Override
        public void beforeStep(StepExecution stepExecution) {
            this.newIndexName = stepExecution.getJobParameters().getString("newIndexName", "default");
        }
        
        @Override
        public void write(Chunk<? extends CategoryDocument> chunk) {
            String indexName = this.newIndexName + "-categories";
            elasticsearchOperations.save(chunk.getItems(), IndexCoordinates.of(indexName));
        }
    }

    public class ProductColorWriter implements ItemWriter<ProductColorDocument>, StepExecutionListener {
        private String newIndexName;

        @Override
        public void beforeStep(StepExecution stepExecution) {
            this.newIndexName = stepExecution.getJobParameters().getString("newIndexName", "default");
        }
        
        @Override
        public void write(Chunk<? extends ProductColorDocument> chunk) {
            String indexName = this.newIndexName + "-product-colors";
            elasticsearchOperations.save(chunk.getItems(), IndexCoordinates.of(indexName));
        }
    }

    public class ProductDetailsWriter implements ItemWriter<ProductDetailsDocument>, StepExecutionListener {
        private String newIndexName;

        @Override
        public void beforeStep(StepExecution stepExecution) {
            this.newIndexName = stepExecution.getJobParameters().getString("newIndexName", "default");
        }
        
        @Override
        public void write(Chunk<? extends ProductDetailsDocument> chunk) {
            String indexName = this.newIndexName + "-product-details";
            elasticsearchOperations.save(chunk.getItems(), IndexCoordinates.of(indexName));
        }
    }

    public class ProductQAWriter implements ItemWriter<ProductQADocument>, StepExecutionListener {
        private String newIndexName;

        @Override
        public void beforeStep(StepExecution stepExecution) {
            this.newIndexName = stepExecution.getJobParameters().getString("newIndexName", "default");
        }
        
        @Override
        public void write(Chunk<? extends ProductQADocument> chunk) {
            String indexName = this.newIndexName + "-product-qa";
            elasticsearchOperations.save(chunk.getItems(), IndexCoordinates.of(indexName));
        }
    }

    public class ProductSkuWriter implements ItemWriter<ProductSkuDocument>, StepExecutionListener {
        private String newIndexName;

        @Override
        public void beforeStep(StepExecution stepExecution) {
            this.newIndexName = stepExecution.getJobParameters().getString("newIndexName", "default");
        }
        
        @Override
        public void write(Chunk<? extends ProductSkuDocument> chunk) {
            String indexName = this.newIndexName + "-product-skus";
            elasticsearchOperations.save(chunk.getItems(), IndexCoordinates.of(indexName));
        }
    }

    public class ProductSizeWriter implements ItemWriter<ProductSizeDocument>, StepExecutionListener {
        private String newIndexName;

        @Override
        public void beforeStep(StepExecution stepExecution) {
            this.newIndexName = stepExecution.getJobParameters().getString("newIndexName", "default");
        }
        
        @Override
        public void write(Chunk<? extends ProductSizeDocument> chunk) {
            String indexName = this.newIndexName + "-product-sizes";
            elasticsearchOperations.save(chunk.getItems(), IndexCoordinates.of(indexName));
        }
    }

    public class SubcategoryWriter implements ItemWriter<SubcategoryDocument>, StepExecutionListener {
        private String newIndexName;

        @Override
        public void beforeStep(StepExecution stepExecution) {
            this.newIndexName = stepExecution.getJobParameters().getString("newIndexName", "default");
        }
        
        @Override
        public void write(Chunk<? extends SubcategoryDocument> chunk) {
            String indexName = this.newIndexName + "-subcategories";
            elasticsearchOperations.save(chunk.getItems(), IndexCoordinates.of(indexName));
        }
    }

    // --- ItemWriter 빈을 생성하여 반환하도록 수정
    @Bean
    public ItemWriter<ProductDocument> productDocWriter() {
        return new ProductWriter();
    }
    
    @Bean
    public ItemWriter<CategoryDocument> categoryDocWriter() {
        return new CategoryWriter();
    }
    
    @Bean
    public ItemWriter<ProductColorDocument> productColorDocWriter() {
        return new ProductColorWriter();
    }
    
    @Bean
    public ItemWriter<ProductDetailsDocument> productDetailsDocWriter() {
        return new ProductDetailsWriter();
    }
    
    @Bean
    public ItemWriter<ProductQADocument> productQADocWriter() {
        return new ProductQAWriter();
    }
    
    @Bean
    public ItemWriter<ProductSkuDocument> productSkuDocWriter() {
        return new ProductSkuWriter();
    }
    
    @Bean
    public ItemWriter<ProductSizeDocument> productSizeDocWriter() {
        return new ProductSizeWriter();
    }
    
    @Bean
    public ItemWriter<SubcategoryDocument> subcategoryDocWriter() {
        return new SubcategoryWriter();
    }
    
    // --- Roll-over Tasklet ---
    @Bean
    public Tasklet aliasRollOverTasklet() {
        return (contribution, chunkContext) -> {

            Map<String, Object> jobParameters = chunkContext.getStepContext().getJobParameters();
            Object newIndexObj = jobParameters.get("newIndexName");
            // (버그 수정, docs/06 CAT-10) Writer 는 "<newIndexName>-products" 에 색인하는데
            // 여기서는 접미사 없는 이름을 확인해 "인덱스가 없다"며 항상 실패했고,
            // 그 결과 재색인이 성공해도 alias 가 영원히 새 인덱스를 가리키지 못했다.
            final String newIndex = ((newIndexObj != null) ? newIndexObj.toString() : "default-new-index") + "-products";

               // --- 앨리어스 롤오버 전에 인덱스 존재 여부 확인 ---
            if (!elasticsearchOperations.indexOps(IndexCoordinates.of(newIndex)).exists()) {
                throw new RuntimeException("New index '" + newIndex + "' does not exist. Cannot perform alias rollover.");
            }

            // (버그 수정) 최초 롤오버 시 alias 자체가 아직 없다 — 그대로 조회하면 예외가 나므로 가드.
            List<String> existingIndices = elasticsearchOperations.indexOps(IndexCoordinates.of(productAliasName)).exists()
                ? elasticsearchOperations.indexOps(IndexCoordinates.of(productAliasName)).getAliases().keySet().stream()
                    .filter(index -> !index.equals(newIndex))
                    .collect(Collectors.toList())
                : new java.util.ArrayList<>();

            AliasAction addAction = new AliasAction.Add(
                AliasActionParameters.builder()
                    .withIndices(newIndex)
                    .withAliases(productAliasName)
                    .build()
            );

            if (!existingIndices.isEmpty()) {
                AliasAction removeAction = new AliasAction.Remove(
                    AliasActionParameters.builder()
                        .withIndices(existingIndices.get(0))
                        .withAliases(productAliasName)
                        .build()
                );
                
                AliasActions aliasActions = new AliasActions(addAction, removeAction);
                elasticsearchOperations.indexOps(IndexCoordinates.of(productAliasName)).alias(aliasActions);
                
                elasticsearchOperations.indexOps(IndexCoordinates.of(existingIndices.get(0))).delete();
            } else {
                AliasActions aliasActions = new AliasActions(addAction);
                elasticsearchOperations.indexOps(IndexCoordinates.of(productAliasName)).alias(aliasActions);
            }

            return RepeatStatus.FINISHED;
        };
    }
    
    // --- Steps ---
    @Bean
    public Step aliasRollOverStep() {
        return new StepBuilder("aliasRollOverStep", jobRepository)
                .tasklet(aliasRollOverTasklet(), batchTransactionManager)
                .build();
    }

    @Bean
    public Step categoryIndexingStep(TaskExecutor taskExecutor) {
        return new StepBuilder("categoryIndexingStep", jobRepository)
                .<Category, CategoryDocument>chunk(CHUNK_SIZE, batchTransactionManager)
                .reader(categoryReader())
                .processor(categoryProcessor())
                .writer(categoryDocWriter())
                .taskExecutor(taskExecutor) // taskExecutor 추가
                .build();
    }

    @Bean
    public Step productColorIndexingStep(TaskExecutor taskExecutor) {
        return new StepBuilder("productColorIndexingStep", jobRepository)
                .<ProductColor, ProductColorDocument>chunk(CHUNK_SIZE, batchTransactionManager)
                .reader(productColorReader())
                .processor(productColorProcessor())
                .writer(productColorDocWriter())
                .taskExecutor(taskExecutor) // taskExecutor 추가
                .build();
    }

    @Bean
    public Step productDetailsIndexingStep(TaskExecutor taskExecutor) {
        return new StepBuilder("productDetailsIndexingStep", jobRepository)
                .<ProductDetails, ProductDetailsDocument>chunk(CHUNK_SIZE, batchTransactionManager)
                .reader(productDetailsReader())
                .processor(productDetailsProcessor())
                .writer(productDetailsDocWriter())
                .taskExecutor(taskExecutor) // taskExecutor 추가
                .build();
    }

    @Bean
    public Step productQAIndexingStep(TaskExecutor taskExecutor) {
        return new StepBuilder("productQAIndexingStep", jobRepository)
                .<ProductQA, ProductQADocument>chunk(CHUNK_SIZE, batchTransactionManager)
                .reader(productQAReader())
                .processor(productQAProcessor())
                .writer(productQADocWriter())
                .taskExecutor(taskExecutor) // taskExecutor 추가
                .build();
    }

    @Bean
    public Step productIndexingStep(TaskExecutor taskExecutor) {
        return new StepBuilder("productIndexingStep", jobRepository)
                .<Product, ProductDocument>chunk(CHUNK_SIZE, batchTransactionManager)
                .reader(productReader())
                .processor(productProcessor())
                .writer(productDocWriter())
                .taskExecutor(taskExecutor) // taskExecutor 추가
                .build();
    }

    @Bean
    public Step productSkuIndexingStep(TaskExecutor taskExecutor) {
        return new StepBuilder("productSkuIndexingStep", jobRepository)
                .<ProductSku, ProductSkuDocument>chunk(CHUNK_SIZE, batchTransactionManager)
                .reader(productSkuReader())
                .processor(productSkuProcessor())
                .writer(productSkuDocWriter())
                .taskExecutor(taskExecutor) // taskExecutor 추가
                .build();
    }

    @Bean
    public Step productSizeIndexingStep(TaskExecutor taskExecutor) {
        return new StepBuilder("productSizeIndexingStep", jobRepository)
                .<ProductSize, ProductSizeDocument>chunk(CHUNK_SIZE, batchTransactionManager)
                .reader(productSizeReader())
                .processor(productSizeProcessor())
                .writer(productSizeDocWriter())
                .taskExecutor(taskExecutor) // taskExecutor 추가
                .build();
    }

    @Bean
    public Step subcategoryIndexingStep(TaskExecutor taskExecutor) {
        return new StepBuilder("subcategoryIndexingStep", jobRepository)
                .<Subcategory, SubcategoryDocument>chunk(CHUNK_SIZE, batchTransactionManager)
                .reader(subcategoryReader())
                .processor(subcategoryProcessor())
                .writer(subcategoryDocWriter())
                .taskExecutor(taskExecutor) // taskExecutor 추가
                .build();
    }

    // --- Job ---
    @Bean
    public Job indexingJob() {
        return new JobBuilder("indexingJob", jobRepository)
                .start(categoryIndexingStep(taskExecutor()))
                .next(productColorIndexingStep(taskExecutor()))
                .next(productDetailsIndexingStep(taskExecutor()))
                .next(productQAIndexingStep(taskExecutor()))
                .next(productIndexingStep(taskExecutor()))
                .next(productSkuIndexingStep(taskExecutor()))
                .next(productSizeIndexingStep(taskExecutor()))
                .next(subcategoryIndexingStep(taskExecutor()))
                .next(aliasRollOverStep())
                .build();
    }
}