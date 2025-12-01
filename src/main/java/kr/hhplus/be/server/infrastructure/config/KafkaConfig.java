package kr.hhplus.be.server.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Configuration
 *
 * Kafka Topic 자동 생성 및 기본 설정
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    /**
     * 주문 이벤트 Topic 자동 생성
     *
     * Topic: ecommerce.order.events
     * Partitions: 3
     * Replication Factor: 2 (운영 환경)
     * Retention: 7일
     */
    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name("ecommerce.order.events")
                .partitions(3)
                .replicas(2)
                .config("retention.ms", "604800000")  // 7일
                .config("min.insync.replicas", "2")   // 최소 복제본 수
                .build();
    }

    /**
     * 쿠폰 발급 요청 Topic 자동 생성
     *
     * Topic: coupon.issue.requests
     * Partitions: 10 (높은 처리량)
     * Replication Factor: 2
     * Retention: 1일
     */
    @Bean
    public NewTopic couponIssueRequestsTopic() {
        return TopicBuilder.name("coupon.issue.requests")
                .partitions(10)
                .replicas(2)
                .config("retention.ms", "86400000")   // 1일
                .config("min.insync.replicas", "2")
                .build();
    }

    /**
     * 쿠폰 발급 결과 Topic 자동 생성
     *
     * Topic: coupon.issue.results
     * Partitions: 5
     * Replication Factor: 2
     * Retention: 3일
     */
    @Bean
    public NewTopic couponIssueResultsTopic() {
        return TopicBuilder.name("coupon.issue.results")
                .partitions(5)
                .replicas(2)
                .config("retention.ms", "259200000")  // 3일
                .config("min.insync.replicas", "2")
                .build();
    }
}
