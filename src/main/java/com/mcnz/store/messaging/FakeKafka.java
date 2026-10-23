package com.mcnz.store.messaging;

import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import com.mcnz.store.config.StoreKafkaTopics;

/** Classroom infrastructure: starts Kafka before Spring creates the listeners. */
public class FakeKafka {
	

    private static EmbeddedKafkaKraftBroker broker;
    private static boolean started;

    public static synchronized void start() {
        if (started) {
            return;
        }
        broker = new EmbeddedKafkaKraftBroker(1, 3,
                StoreKafkaTopics.VALIDATE, StoreKafkaTopics.PROCESS);
        broker.brokerListProperty("spring.kafka.bootstrap-servers");
        broker.afterPropertiesSet();
        Runtime.getRuntime().addShutdownHook(new Thread(new StopKafka()));
        started = true;
    }

    private static class StopKafka implements Runnable {
        @Override
        public void run() {
            if (broker != null) {
                broker.destroy();
            }
        }
    }
}