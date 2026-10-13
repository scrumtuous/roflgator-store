package com.mcnz.store.data;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mcnz.store.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByNameContainingIgnoreCase(String name);
}