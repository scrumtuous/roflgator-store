package com.mcnz.store.messaging;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.mcnz.store.Product;
import com.mcnz.store.Purchase;
import com.mcnz.store.PurchaseStatus;
import com.mcnz.store.service.StoreService;

@Service
public class StoreConsumers {

    @Autowired
    private KafkaTemplate<String, Long> kafka;

    @Autowired
    private StoreService storeService;

    @KafkaListener(topics = "roflgator-purchases.validate", groupId = "validators")
    public void validate(Long purchaseId) {
    	System.out.println("In the roflgator-validate listener");
        Purchase purchase = storeService.getPurchase(purchaseId);
        Product product = firstProduct(purchase);

        if (product.quantity <= 0 || product.price <= 0) {
            System.out.println("REJECTED: " + product.name);
            storeService.updatePurchaseStatus(purchaseId, PurchaseStatus.REJECTED);
            return;
        }

        System.out.println("VALIDATED: " + product.name);
        storeService.updatePurchaseStatus(purchaseId, PurchaseStatus.VALIDATED);
        kafka.send("roflgator-purchases.process", Objects.requireNonNull(purchaseId)).join();
    }

    @KafkaListener(topics = "roflgator-purchases.process", groupId = "processors")
    public void process(Long purchaseId) {
    	System.out.println("In the processing listener");
        try {
            TimeUnit.SECONDS.sleep(60);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to complete purchase", interruptedException);
        }

        Purchase purchase = storeService.getPurchase(purchaseId);
        Product product = firstProduct(purchase);
        storeService.updatePurchaseStatus(purchaseId, PurchaseStatus.COMPLETED);
        System.out.println("PROCESSED: " + product.name);
    }

    private Product firstProduct(Purchase purchase) {
        if (purchase == null || purchase.products == null || purchase.products.isEmpty()) {
            throw new IllegalArgumentException("Purchase must contain at least one product");
        }
        return purchase.products.get(0);
    }
}