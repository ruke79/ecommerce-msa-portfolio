package com.project.delivery_service.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.project.delivery_service.domain.Carrier;

public interface CarrierRepository extends JpaRepository<Carrier, String> {

    List<Carrier> findByActiveTrue();
}
