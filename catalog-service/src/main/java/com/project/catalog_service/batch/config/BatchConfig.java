package com.project.catalog_service.batch.config;

import com.project.catalog_service.dto.data.*;
import com.project.catalog_service.model.*;
import com.project.catalog_service.batch.processor.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.database.JpaItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.LineMapper;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

// when using ElasticsearchBatchConfig.java , commetted out the BatchConfig.java file
// to avoid conflicts with the existing batch configuration.

@Configuration
public class BatchConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager batchTransactionManager;
    private final PlatformTransactionManager metaTransactionManager;
    private final EntityManagerFactory entityManagerFactory;

    // ItemProcessor 클래스들을 주입
    private final CategoryItemProcessor categoryItemProcessor;
    private final SubcategoryItemProcessor subcategoryItemProcessor;
    private final ProductItemProcessor productItemProcessor;
    private final ProductColorItemProcessor productColorItemProcessor;
    private final ProductSkuItemProcessor productSkuItemProcessor;
    private final ProductDetailsItemProcessor productDetailsItemProcessor;
    private final ProductQAItemProcessor productQAItemProcessor;
    private final ProductSubcategoryItemProcessor productSubcategoryItemProcessor;
    private final ProductSizeItemProcessor productSizeItemProcessor;

    // 생성자 주입
    public BatchConfig(JobRepository jobRepository,
            @Qualifier("batchTransactionManager") PlatformTransactionManager batchTransactionManager,
            @Qualifier("metaTransactionManager") PlatformTransactionManager metaTransactionManager,
            @Qualifier("entityManagerFactory") EntityManagerFactory entityManagerFactory,
            CategoryItemProcessor categoryItemProcessor,
            SubcategoryItemProcessor subcategoryItemProcessor,
            ProductItemProcessor productItemProcessor,
            ProductColorItemProcessor productColorItemProcessor,
            ProductSkuItemProcessor productSkuItemProcessor,
            ProductDetailsItemProcessor productDetailsItemProcessor,
            ProductQAItemProcessor productQAItemProcessor,
            ProductSubcategoryItemProcessor productSubcategoryItemProcessor,
            ProductSizeItemProcessor productSizeItemProcessor) {
        this.jobRepository = jobRepository;
        this.batchTransactionManager = batchTransactionManager;
        this.metaTransactionManager = metaTransactionManager;
        this.entityManagerFactory = entityManagerFactory;
        this.categoryItemProcessor = categoryItemProcessor;
        this.subcategoryItemProcessor = subcategoryItemProcessor;
        this.productItemProcessor = productItemProcessor;
        this.productColorItemProcessor = productColorItemProcessor;
        this.productSkuItemProcessor = productSkuItemProcessor;
        this.productDetailsItemProcessor = productDetailsItemProcessor;
        this.productQAItemProcessor = productQAItemProcessor;
        this.productSubcategoryItemProcessor = productSubcategoryItemProcessor;
        this.productSizeItemProcessor = productSizeItemProcessor;
    }

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // --- ItemReader Beans ---
    @Bean
    public FlatFileItemReader<CategoryCsvDto> categoryFileReader() {
        return createCsvReader("categories.csv", CategoryCsvDto.class, "category_id", "category_name", "slug",
                "created_at");
    }

    @Bean
    public FlatFileItemReader<SubcategoryCsvDto> subcategoryFileReader() {
        return createCsvReader("subcategories.csv", SubcategoryCsvDto.class, "subcategory_id", "subcategory_name",
                "slug", "category_id", "created_at");
    }

    @Bean
    public FlatFileItemReader<ProductCsvDto> productFileReader() {
        return createCsvReader("products.csv", ProductCsvDto.class, "product_id", "name", "description", "brand",
                "slug", "category_id", "refund_policy", "rating", "shipping", "created_at");
    }

    @Bean
    public FlatFileItemReader<ProductColorCsvDto> productColorFileReader() {
        return createCsvReader("product_colors.csv", ProductColorCsvDto.class, "color_id", "color", "color_image");
    }

    @Bean
    public FlatFileItemReader<ProductSkuCsvDto> productSkuFileReader() {
        return createCsvReader("product_skus.csv", ProductSkuCsvDto.class, "skuproduct_id", "sku", "images", "discount",
                "sold", "color_id", "product_id", "created_at");
    }

    @Bean
    public FlatFileItemReader<ProductDetailsCsvDto> productDetailsFileReader() {
        return createCsvReader("product_details.csv", ProductDetailsCsvDto.class, "pdetail_id", "name", "value",
                "product_id", "created_at");
    }

    @Bean
    public ItemReader<ProductQACsvDto> productQAFileReader() {
        return createCsvReader("product_qas.csv", ProductQACsvDto.class, "qa_id", "question", "answer", "product_id",
                "created_at");
    }

    @Bean
    public ItemReader<ProductSubcategoryCsvDto> productSubcategoryFileReader() {
        return createCsvReader("product_subcategories.csv", ProductSubcategoryCsvDto.class, "id", "product_id",
                "subcategory_id");
    }

    @Bean
    public ItemReader<ProductSizeCsvDto> productSizeFileReader() {
        return createCsvReader("product_sizes.csv", ProductSizeCsvDto.class, "size_id", "size", "quantity", "price",
                "skuproduct_id");
    }

    // --- ItemProcessor Beans ---
    @Bean
    public ItemProcessor<CategoryCsvDto, Category> categoryCsvItemProcessor() {
        return categoryItemProcessor;
    }

    @Bean
    public ItemProcessor<SubcategoryCsvDto, Subcategory> subcategoryCsvItemProcessor() {
        return subcategoryItemProcessor;
    }

    @Bean
    public ItemProcessor<ProductCsvDto, Product> productCsvItemProcessor() {
        return productItemProcessor;
    }

    @Bean
    public ItemProcessor<ProductColorCsvDto, ProductColor> productColorCsvItemProcessor() {
        return productColorItemProcessor;
    }

    @Bean
    public ItemProcessor<ProductSkuCsvDto, ProductSku> productSkuCsvItemProcessor() {
        return productSkuItemProcessor;
    }

    @Bean
    public ItemProcessor<ProductDetailsCsvDto, ProductDetails> productDetailsCsvItemProcessor() {
        return productDetailsItemProcessor;
    }

    @Bean
    public ItemProcessor<ProductQACsvDto, ProductQA> productQACsvItemProcessor() {
        return productQAItemProcessor;
    }

    @Bean
    public ItemProcessor<ProductSubcategoryCsvDto, ProductSubcategory> productSubcategoryCsvItemProcessor() {
        return productSubcategoryItemProcessor;
    }

    @Bean
    public ItemProcessor<ProductSizeCsvDto, ProductSize> productSizeCsvItemProcessor() {
        return productSizeItemProcessor;
    }

    // --- ItemWriter Beans ---
    @Bean
    public JpaItemWriter<Category> categoryWriter() {
        return createJpaWriter();
    }

    @Bean
    public JpaItemWriter<Subcategory> subcategoryWriter() {
        return createJpaWriter();
    }

    @Bean
    public JpaItemWriter<Product> productWriter() {
        return createJpaWriter();
    }

    @Bean
    public JpaItemWriter<ProductColor> productColorWriter() {
        return createJpaWriter();
    }

    @Bean
    public JpaItemWriter<ProductSku> productSkuWriter() {
        return createJpaWriter();
    }

    @Bean
    public JpaItemWriter<ProductDetails> productDetailsWriter() {
        return createJpaWriter();
    }

    @Bean
    public JpaItemWriter<ProductQA> productQAWriter() {
        return createJpaWriter();
    }

    @Bean
    public JpaItemWriter<ProductSubcategory> productSubcategoryWriter() {
        return createJpaWriter();
    }

    @Bean
    public JpaItemWriter<ProductSize> productSizeWriter() {
        return createJpaWriter();
    }

    // --- Step Beans ---
    @Bean
    public Step categoryStep() {
        return new StepBuilder("categoryStep", jobRepository)
                .<CategoryCsvDto, Category>chunk(10, batchTransactionManager)
                .reader(categoryFileReader())
                .processor(categoryItemProcessor)
                .writer(categoryWriter())
                .build();
    }

    @Bean
    public Step subcategoryStep() {
        return new StepBuilder("subcategoryStep", jobRepository)
                .<SubcategoryCsvDto, Subcategory>chunk(10, batchTransactionManager)
                .reader(subcategoryFileReader())
                .processor(subcategoryItemProcessor)
                .writer(subcategoryWriter())
                .build();
    }

    @Bean
    public Step productStep() {
        return new StepBuilder("productStep", jobRepository)
                .<ProductCsvDto, Product>chunk(10, batchTransactionManager)
                .reader(productFileReader())
                .processor(productItemProcessor)
                .writer(productWriter())
                .build();
    }

    @Bean
    public Step productColorStep() {
        return new StepBuilder("productColorStep", jobRepository)
                .<ProductColorCsvDto, ProductColor>chunk(10, batchTransactionManager)
                .reader(productColorFileReader())
                .processor(productColorItemProcessor)
                .writer(productColorWriter())
                .build();
    }

    @Bean
    public Step productSkuStep() {
        return new StepBuilder("productSkuStep", jobRepository)
                .<ProductSkuCsvDto, ProductSku>chunk(10, batchTransactionManager)
                .reader(productSkuFileReader())
                .processor(productSkuCsvItemProcessor())
                .writer(productSkuWriter())
                .build();
    }

    @Bean
    public Step productDetailsStep() {
        return new StepBuilder("productDetailsStep", jobRepository)
                .<ProductDetailsCsvDto, ProductDetails>chunk(10, batchTransactionManager)
                .reader(productDetailsFileReader())
                .processor(productDetailsCsvItemProcessor())
                .writer(productDetailsWriter())
                .build();
    }

    @Bean
    public Step productQAStep() {
        return new StepBuilder("productQAStep", jobRepository)
                .<ProductQACsvDto, ProductQA>chunk(10, batchTransactionManager)
                .reader(productQAFileReader())
                .processor(productQACsvItemProcessor())
                .writer(productQAWriter())
                .build();
    }

    @Bean
    public Step productSubcategoryStep() {
        return new StepBuilder("productSubcategoryStep", jobRepository)
                .<ProductSubcategoryCsvDto, ProductSubcategory>chunk(10, batchTransactionManager)
                .reader(productSubcategoryFileReader())
                .processor(productSubcategoryCsvItemProcessor())
                .writer(productSubcategoryWriter())
                .build();
    }

    @Bean
    public Step productSizeStep() {
        return new StepBuilder("productSizeStep", jobRepository)
                .<ProductSizeCsvDto, ProductSize>chunk(10, batchTransactionManager)
                .reader(productSizeFileReader())
                .processor(productSizeCsvItemProcessor())
                .writer(productSizeWriter())
                .build();
    }

    /**
     * 사이즈마다 SKU 코드를 채운다(catalog V17). CSV 에는 사이즈 SKU 가 없다 — 규칙은 DB 함수 한 곳에만 두고
     * 사이즈가 다 들어온 뒤 한 번 부른다. 행마다 계산하지 않는 이유: 같은 스타일에 같은 사이즈 이름이 두 줄인
     * 경우(시드에 실제로 있다) 어느 쪽이 원래 코드를 갖는지 정하려면 전체를 봐야 한다.
     */
    @Bean
    public Step sizeSkuCodeStep() {
        return new StepBuilder("sizeSkuCodeStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    Object assigned = org.springframework.orm.jpa.EntityManagerFactoryUtils
                            .getTransactionalEntityManager(entityManagerFactory)
                            .createNativeQuery("select product.assign_size_sku_codes()")
                            .getSingleResult();
                    contribution.incrementWriteCount(assigned instanceof Number n ? n.longValue() : 0L);
                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, batchTransactionManager)
                .build();
    }

    // --- Job Bean ---
    @Bean
    public Job productImportJob() {
        return new JobBuilder("productImportJob", jobRepository)
                .start(categoryStep())
                .next(subcategoryStep())
                .next(productStep())
                .next(productColorStep())
                .next(productSkuStep())
                .next(productDetailsStep())
                .next(productQAStep())
                .next(productSubcategoryStep())
                .next(productSizeStep())
                .next(sizeSkuCodeStep())
                .build();
    }

    // --- Helper Methods ---
    // Helper method to create a CSV ItemReader for a given DTO type
    private <T> FlatFileItemReader<T> createCsvReader(String resource, Class<T> targetType, String... names) {
        FlatFileItemReader<T> reader = new FlatFileItemReader<>();
        // resources/csv 폴더 내의 파일을 참조하도록 경로 수정
        reader.setResource(new ClassPathResource("csv/" + resource));
        reader.setLineMapper(createLineMapper(targetType, names));
        reader.setLinesToSkip(1); // Skip the header row
        return reader;
    }

    private <T> DefaultLineMapper<T> createLineMapper(Class<T> targetType, String... names) {
        DefaultLineMapper<T> lineMapper = new DefaultLineMapper<>();
        DelimitedLineTokenizer lineTokenizer = new DelimitedLineTokenizer();
        lineTokenizer.setNames(names);

        BeanWrapperFieldSetMapper<T> fieldSetMapper = new BeanWrapperFieldSetMapper<>();
        fieldSetMapper.setTargetType(targetType);

        lineMapper.setLineTokenizer(lineTokenizer);
        lineMapper.setFieldSetMapper(fieldSetMapper);
        return lineMapper;
    }

    // JpaItemWriter는 기본적으로 merge() 연산을 사용하여 엔티티를 저장합니다. merge()는 ID가 존재하면 update를,
    // 없으면 insert를 시도합니다.
    // category.csv 파일의 데이터는 처음 실행 시에는 DB에 존재하지 않아야 합니다. 하지만 categoryStep에서
    // JpaItemWriter가 Category 엔티티를 merge()할 때, Category 엔티티에 id(Primary Key)가 이미
    // 할당되어 있어 Hibernate가 update 연산을 시도합니다.
    // update 연산은 엔티티의 버전 필드를 확인하는데, CSV 파일에서 읽어온 엔티티는 버전 정보가 없거나 0이므로, 데이터베이스에 이미
    // 존재하는 행의 버전과 충돌하여 OptimisticLockException이 발생하게 됩니다.
    // 이는 데이터가 실제로 중복되어 발생한 문제가 아니라, 엔티티 ID가 이미 할당된 상태에서 JpaItemWriter가 update를 시도하기
    // 때문에 발생하는 문제입니다.

    // BatchConfig.java
    private <T> JpaItemWriter<T> createJpaWriter() {
        JpaItemWriter<T> writer = new JpaItemWriter<T>() {
            @Override
            protected void doWrite(jakarta.persistence.EntityManager entityManager, Chunk<? extends T> items) {
                for (T item : items) {
                    entityManager.persist(item); // merge() 대신 persist() 사용
                }
            }
        };
        //JpaItemWriter<T> writer = new JpaItemWriter<>();
        writer.setEntityManagerFactory(entityManagerFactory);        
        return writer;
    }
}
