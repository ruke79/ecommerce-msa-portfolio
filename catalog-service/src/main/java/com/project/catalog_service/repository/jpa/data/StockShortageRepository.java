package com.project.catalog_service.repository.jpa.data;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.project.catalog_service.model.StockShortage;

public interface StockShortageRepository extends JpaRepository<StockShortage, String> {

    /** 아직 보내지 못한 것 — 오래된 것부터. */
    @Query("select s from StockShortage s where s.publishedAt is null order by s.createdAt")
    List<StockShortage> findUnpublished(Pageable page);
}
