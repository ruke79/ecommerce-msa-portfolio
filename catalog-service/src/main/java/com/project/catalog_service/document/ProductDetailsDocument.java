package com.project.catalog_service.document;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

@Getter
@Builder
@Document(indexName = "product_details", createIndex = false) // createindex set to false to avoid automatic index creation  
@Setting(settingPath = "/es/es-settings.json")
public class ProductDetailsDocument {

    @Id
    private String pdetailId;

    @Field(type = FieldType.Keyword)
    private String productId;

    @MultiField(
        mainField = @Field(type = FieldType.Text, analyzer = "custon_korean_analyzer"),
        otherFields = {
            @InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard"),
            @InnerField(suffix = "japanese", type = FieldType.Text, analyzer = "custon_japanese_analyzer")
        }
    )
    private String name;

    @MultiField(
        mainField = @Field(type = FieldType.Text, analyzer = "custon_korean_analyzer"),
        otherFields = {
            @InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard"),
            @InnerField(suffix = "japanese", type = FieldType.Text, analyzer = "custon_japanese_analyzer")
        }
    )
    private String value;
}