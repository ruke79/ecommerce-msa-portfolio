package com.project.catalog_service.document;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

@Getter
@Builder
@Document(indexName = "product_sizes", createIndex = false) // createindex set to false to avoid automatic index creation  
@Setting(settingPath = "/es/es-settings.json")
public class ProductSizeDocument {

    @Id
    private String sizeId;

    @Field(type = FieldType.Keyword)
    private String skuproductId;

    @MultiField(
        mainField = @Field(type = FieldType.Text, analyzer = "custom_korean_analyzer"),
        otherFields = {
            @InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard"),
            @InnerField(suffix = "japanese", type = FieldType.Text, analyzer = "custom_japanese_analyzer")
        }
    )
    private String size;

    @Field(type = FieldType.Integer)
    private int quantity;

    @Field(type = FieldType.Float)
    private float price;
}