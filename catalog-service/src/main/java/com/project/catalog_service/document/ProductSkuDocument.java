package com.project.catalog_service.document;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

@Getter
@Builder
@Document(indexName = "product_skus", createIndex = false) // createindex set to false to avoid automatic index creation  
@Setting(settingPath = "/es/es-settings.json")
public class ProductSkuDocument {

    @Id
    private String skuproductId;

    @Field(type = FieldType.Keyword)
    private String productId;

    @Field(type = FieldType.Keyword)
    private String sku;

    @Field(type = FieldType.Integer)
    private int discount;

    @Field(type = FieldType.Integer)
    private int sold;
}