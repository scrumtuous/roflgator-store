package com.mcnz.store;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.mcnz.store.data.StorePurchasesData;
import com.mcnz.store.messaging.FakeKafka;
import com.mcnz.store.messaging.StoreProducers;
import com.mcnz.store.service.StoreService;

@SpringBootApplication
public class App implements CommandLineRunner {

    @Autowired
    private StoreProducers producers;

    @Autowired
    private StoreService storeService;

    public static void main(String[] args) {
        FakeKafka.start();
        SpringApplication.run(App.class, args);
    }

    @Override
    public void run(String... args) {
        for (String[] row : StorePurchasesData.ROWS) {
            // Refunds are left for a separate lesson.
            if ("PURCHASE".equals(row[0])) {
                Purchase purchase = new Purchase(
                    LocalDate.parse(row[1]),
                    new Customer(row[2], new Address(row[3], row[4]))
                );
                purchase.addProduct(new Product(row[5], Integer.parseInt(row[6]), Double.parseDouble(row[7])));
                Purchase pendingPurchase = storeService.createPendingPurchase(purchase);
                producers.submit(pendingPurchase.id);
            }
        }
        System.out.println("Purchases submitted. Kafka listeners will process them.");
    }
}
