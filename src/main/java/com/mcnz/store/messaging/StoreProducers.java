package com.mcnz.store.messaging;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class StoreProducers {

    @Autowired
    private KafkaTemplate<String, Long> kafka;

    public void submit(Long purchaseId) {
    	System.out.println("In the producer submitting the purchase id.");
        Long id = Objects.requireNonNull(purchaseId);
        kafka.send(StoreKafkaTopics.VALIDATE, id).join();
    }
}