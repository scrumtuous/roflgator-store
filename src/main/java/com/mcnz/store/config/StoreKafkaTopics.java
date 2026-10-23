package com.mcnz.store.config;

import java.util.List;

public final class StoreKafkaTopics {

    public static final String VALIDATE = "roflgator-purchases.validate";
    public static final String PROCESS = "roflgator-purchases.process";

    private StoreKafkaTopics() {
    }

    public static List<String> all() {
        return List.of(VALIDATE, PROCESS);
    }
}
