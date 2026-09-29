package com.project.coupon_service.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.project.coupon_service.model.ProcessedEvent;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
