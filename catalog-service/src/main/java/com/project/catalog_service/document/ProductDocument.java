package com.project.catalog_service.document;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

import org.springframework.boot.context.properties.bind.Nested;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import com.project.catalog_service.document.ProductDocument.ProductSubcategoryDocument;

@Getter
@Builder
@Document(indexName = "products", createIndex = false) // createindex set to false to avoid automatic index creation  
@Setting(settingPath = "/es/es-settings.json")
public class ProductDocument {

	@Id
	private String productId;

	@MultiField(mainField = @Field(type = FieldType.Text, analyzer = "korean_analyzer"), otherFields = {
			@InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard"),
			@InnerField(suffix = "japanese", type = FieldType.Text, analyzer = "japanese_analyzer")
	})
	private String name;

	@MultiField(mainField = @Field(type = FieldType.Text, analyzer = "korean_analyzer"), otherFields = {
			@InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard"),
			@InnerField(suffix = "japanese", type = FieldType.Text, analyzer = "japanese_analyzer")
	})
	private String description;

	@MultiField(mainField = @Field(type = FieldType.Text, analyzer = "korean_analyzer"), otherFields = {
			@InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard"),
			@InnerField(suffix = "japanese", type = FieldType.Text, analyzer = "japanese_analyzer")
	})
	private String brand;

	@Field(type = FieldType.Keyword)
	private String category;

	@Field(type = FieldType.Nested, name = "subcategories")
	private List<ProductSubcategoryDocument> subcategories;
	
	@Field(type = FieldType.Nested, name = "skus")
	private List<ProductSkuDocument> skus;

	@Field(type = FieldType.Float)
	private float rating;

	@Field(type = FieldType.Float)

	private float shipping;

	@Field(type = FieldType.Date)
	private LocalDateTime  createdAt;

	@Getter
	@Builder
	public static class ProductSubcategoryDocument {
		@Field(type = FieldType.Keyword)
		private String id;
		@MultiField(mainField = @Field(type = FieldType.Text, analyzer = "korean_analyzer"), otherFields = {
				@InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard"),
				@InnerField(suffix = "japanese", type = FieldType.Text, analyzer = "japanese_analyzer")
		})
		private String name;
	}

	@Getter
	@Builder
	public static class ProductSkuDocument {
		@Field(type = FieldType.Keyword)
		private String sku;
		@MultiField(mainField = @Field(type = FieldType.Text, analyzer = "korean_analyzer"), otherFields = {
				@InnerField(suffix = "english", type = FieldType.Text, analyzer = "standard"),
				@InnerField(suffix = "japanese", type = FieldType.Text, analyzer = "japanese_analyzer")
		})
		private String color;
		@Field(type = FieldType.Float)
		private float price;
		@Field(type = FieldType.Integer)
		private int discount;
	}
}