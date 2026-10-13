package com.mcnz.store.data;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mcnz.store.Purchase;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {
}