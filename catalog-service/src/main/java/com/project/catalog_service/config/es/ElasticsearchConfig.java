package com.project.catalog_service.config.es;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;


@Configuration
@EnableElasticsearchRepositories(basePackages = "com.project.catalog_service.repository.elasticsearch")
public class ElasticsearchConfig {

}
