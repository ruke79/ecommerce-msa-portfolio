package com.project.catalog_service.document;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

@Getter
@Builder
@Document(indexName = "product_colors", createIndex = false)  // createindex set to false to avoid automatic index creation  
@Setting(settingPath = "/es/es-settings.json")
public class ProductColorDocument {

    @Id
    private String colorId;

    @MultiField(
        mainField = @Field(type = FieldType.Text, analyzer = "custom_korean_analyzer"),
        otherFields = {
            @InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard"),
            @InnerField(suffix = "japanese", type = FieldType.Text, analyzer = "custom_japanese_analyzer")
        }
    )
    private String color;
}