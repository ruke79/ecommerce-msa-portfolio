package com.project.catalog_service.repository.jpa.data;

import org.springframework.data.jpa.repository.JpaRepository;

import com.project.catalog_service.model.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
