package com.mcnz.store.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/** Classroom infrastructure: starts Kafka before Spring creates the listeners. */
public class FakeKafka {

    private static final Logger log = LoggerFactory.getLogger(FakeKafka.class);

    private static EmbeddedKafkaKraftBroker broker;
    private static boolean started;

    public static synchronized void start() {
        if (started) {
            log.info("Embedded Kafka startup skipped because broker is already running.");
            return;
        }
        broker = new EmbeddedKafkaKraftBroker(1, 3,
                StoreKafkaTopics.VALIDATE, StoreKafkaTopics.PROCESS);
        broker.brokerListProperty("spring.kafka.bootstrap-servers");
        broker.afterPropertiesSet();
        log.info("Embedded Kafka started: bootstrapServers='{}', topics='{}'.",
                broker.getBrokersAsString(),
                String.join(", ", StoreKafkaTopics.all()));
        Runtime.getRuntime().addShutdownHook(new Thread(new StopKafka()));
        started = true;
    }

    private static class StopKafka implements Runnable {
        @Override
        public void run() {
            if (broker != null) {
                log.info("Shutting down embedded Kafka broker.");
                broker.destroy();
            }
        }
    }
}