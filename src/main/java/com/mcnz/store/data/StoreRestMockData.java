package com.mcnz.store.data;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class StoreRestMockData {

    public List<Map<String, Object>> getCustomers(String city) {
        return List.of();
    }

    public Optional<Map<String, Object>> getCustomer(long customerId) {
        return Optional.empty();
    }

    public List<Map<String, Object>> getCities() {
        return List.of();
    }

    public List<Map<String, Object>> getProducts(String name) {
        return List.of();
    }

    public Optional<Map<String, Object>> getProduct(long productId) {
        return Optional.empty();
    }

    public List<Map<String, Object>> getPurchases(Long customerId, String city) {
        return List.of();
    }

    public Optional<Map<String, Object>> getPurchase(long purchaseId) {
        return Optional.empty();
    }

    public List<Map<String, Object>> getRefunds(Long customerId, String city) {
        return List.of();
    }

    public Optional<Map<String, Object>> getRefund(long refundId) {
        return Optional.empty();
    }

    public Map<String, Object> getSummary() {
        return Map.of();
    }
}
