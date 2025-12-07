# Kafka 로컬 설치 및 기본 기능 테스트 가이드

> 이 문서는 로컬 환경에서 Kafka 클러스터를 실행하고 기본 기능을 테스트하는 실습 가이드입니다.

## 📚 목차
1. [Kafka 클러스터 실행](#1-kafka-클러스터-실행)
2. [클러스터 상태 확인](#2-클러스터-상태-확인)
3. [Topic 생성](#3-topic-생성)
4. [콘솔 Producer 테스트](#4-콘솔-producer-테스트)
5. [콘솔 Consumer 테스트](#5-콘솔-consumer-테스트)
6. [Consumer Group 테스트](#6-consumer-group-테스트)
7. [파티션 및 복제 확인](#7-파티션-및-복제-확인)
8. [Offset 관리 테스트](#8-offset-관리-테스트)
9. [클러스터 정리](#9-클러스터-정리)

---

## 1. Kafka 클러스터 실행

### 1.1 Docker Compose로 클러스터 시작

```bash
# Kafka 클러스터 실행 (백그라운드)
docker compose -f docker-compose.kafka.yaml up -d

# 또는 (구버전 docker-compose)
docker-compose -f docker-compose.kafka.yaml up -d
```

**예상 출력:**
```
[+] Running 4/4
 ✔ Container zookeeper  Started
 ✔ Container broker1    Started
 ✔ Container broker2    Started
 ✔ Container broker3    Started
```

### 1.2 컨테이너 상태 확인

```bash
# 실행 중인 컨테이너 확인
docker ps

# 예상 출력:
# CONTAINER ID   IMAGE                            STATUS         PORTS
# abc123...      confluentinc/cp-kafka:7.6.0      Up 30 seconds  0.0.0.0:9092->9092/tcp
# def456...      confluentinc/cp-kafka:7.6.0      Up 30 seconds  0.0.0.0:9093->9093/tcp
# ghi789...      confluentinc/cp-kafka:7.6.0      Up 30 seconds  0.0.0.0:9094->9094/tcp
# jkl012...      confluentinc/cp-zookeeper:7.6.0  Up 30 seconds  0.0.0.0:2181->2181/tcp
```

### 1.3 로그 확인 (정상 실행 확인)

```bash
# 모든 컨테이너 로그 확인
docker compose -f docker-compose.kafka.yaml logs -f

# 특정 브로커 로그만 확인
docker logs -f broker1

# Kafka 준비 완료 확인 (다음 메시지가 나오면 성공)
# [KafkaServer id=1] started (kafka.server.KafkaServer)
```

---

## 2. 클러스터 상태 확인

### 2.1 Zookeeper 연결 확인

```bash
# Zookeeper 상태 확인
echo "stat" | nc localhost 2181

# 예상 출력:
# Zookeeper version: 3.8.0
# Clients:
#  /172.18.0.2:xxxxx[0](queued=0,recved=1,sent=0)
```

### 2.2 Broker 목록 확인

```bash
# Broker 목록 조회
docker exec -it broker1 kafka-broker-api-versions \
  --bootstrap-server localhost:9092

# 예상 출력:
# broker1:29092 (id: 1 rack: null) -> (
#         Produce(0): 0 to 9 [usable: 9],
#         Fetch(1): 0 to 13 [usable: 13],
#         ...
# )
# broker2:29093 (id: 2 rack: null) -> ...
# broker3:29094 (id: 3 rack: null) -> ...
```

### 2.3 Cluster ID 확인

```bash
# Cluster 정보 확인
docker exec -it broker1 kafka-cluster \
  cluster-id --bootstrap-server localhost:9092

# 예상 출력:
# Cluster ID: xtzWWN4bTjitpL3kfd9s5g
```

---

## 3. Topic 생성

### 3.1 주문 이벤트 Topic 생성

```bash
# Topic 생성: ecommerce.order.events
docker exec -it broker1 kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --partitions 3 \
  --replication-factor 2 \
  --config retention.ms=604800000 \
  --config min.insync.replicas=2

# 예상 출력:
# Created topic ecommerce.order.events.
```

**설정 설명:**
- `--partitions 3`: 파티션 3개 (병렬 처리)
- `--replication-factor 2`: 복제본 2개 (고가용성)
- `--config retention.ms=604800000`: 보관 기간 7일
- `--config min.insync.replicas=2`: 최소 복제본 수 2개

### 3.2 Topic 목록 확인

```bash
# 모든 Topic 목록 조회
docker exec -it broker1 kafka-topics --list \
  --bootstrap-server localhost:9092

# 예상 출력:
# __consumer_offsets
# ecommerce.order.events
```

### 3.3 Topic 상세 정보 확인

```bash
# Topic 상세 정보 조회
docker exec -it broker1 kafka-topics --describe \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events

# 예상 출력:
# Topic: ecommerce.order.events	TopicId: abc123...	PartitionCount: 3	ReplicationFactor: 2	Configs: min.insync.replicas=2,retention.ms=604800000
# 	Topic: ecommerce.order.events	Partition: 0	Leader: 1	Replicas: 1,2	Isr: 1,2
# 	Topic: ecommerce.order.events	Partition: 1	Leader: 2	Replicas: 2,3	Isr: 2,3
# 	Topic: ecommerce.order.events	Partition: 2	Leader: 3	Replicas: 3,1	Isr: 3,1
```

**설명:**
- **Leader**: 해당 파티션의 리더 브로커
- **Replicas**: 복제본이 있는 브로커 목록
- **Isr (In-Sync Replicas)**: 동기화된 복제본 목록

---

## 4. 콘솔 Producer 테스트

### 4.1 간단한 메시지 발행 (Key 없이)

```bash
# Producer 실행
docker exec -it broker1 kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events

# 프롬프트가 나타나면 메시지 입력:
> Hello Kafka!
> This is a test message.
> Order completed: 12345
```

**종료:** `Ctrl + C`

### 4.2 Key-Value 메시지 발행

```bash
# Key-Value Producer 실행
docker exec -it broker1 kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --property "parse.key=true" \
  --property "key.separator=:"

# 메시지 입력 형식: key:value
> 12345:{"orderId":12345,"userId":67890,"total":45000}
> 12346:{"orderId":12346,"userId":67891,"total":50000}
> 12347:{"orderId":12347,"userId":67892,"total":35000}
```

**설명:**
- Key: `orderId` (같은 Key는 같은 파티션으로)
- Value: JSON 형태의 주문 데이터

### 4.3 JSON 파일로 대량 메시지 발행

```bash
# 테스트 메시지 파일 생성
cat > /tmp/orders.txt <<EOF
12345:{"orderId":12345,"userId":1,"total":45000,"items":[{"productId":101,"name":"텀블러","price":25000,"qty":2}]}
12346:{"orderId":12346,"userId":2,"total":50000,"items":[{"productId":102,"name":"노트북","price":50000,"qty":1}]}
12347:{"orderId":12347,"userId":3,"total":35000,"items":[{"productId":103,"name":"키보드","price":35000,"qty":1}]}
12348:{"orderId":12348,"userId":1,"total":60000,"items":[{"productId":104,"name":"모니터","price":60000,"qty":1}]}
12349:{"orderId":12349,"userId":2,"total":15000,"items":[{"productId":105,"name":"마우스","price":15000,"qty":1}]}
EOF

# 파일에서 메시지 발행
docker exec -i broker1 kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --property "parse.key=true" \
  --property "key.separator=:" \
  < /tmp/orders.txt

# 예상 출력: (에러 없으면 성공)
```

---

## 5. 콘솔 Consumer 테스트

### 5.1 최신 메시지부터 읽기

```bash
# Consumer 실행 (최신 메시지부터)
docker exec -it broker1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events

# 새로운 메시지만 출력됨
# (다른 터미널에서 Producer로 메시지 발행하면 실시간으로 출력)
```

### 5.2 처음부터 모든 메시지 읽기

```bash
# Consumer 실행 (처음부터)
docker exec -it broker1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --from-beginning

# 예상 출력:
# {"orderId":12345,"userId":1,"total":45000,...}
# {"orderId":12346,"userId":2,"total":50000,...}
# {"orderId":12347,"userId":3,"total":35000,...}
# ...
```

### 5.3 Key와 함께 메시지 읽기

```bash
# Key-Value Consumer 실행
docker exec -it broker1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --from-beginning \
  --property print.key=true \
  --property key.separator=":"

# 예상 출력:
# 12345:{"orderId":12345,"userId":1,"total":45000,...}
# 12346:{"orderId":12346,"userId":2,"total":50000,...}
# 12347:{"orderId":12347,"userId":3,"total":35000,...}
```

### 5.4 파티션 정보와 함께 읽기

```bash
# 파티션, Offset 정보 포함
docker exec -it broker1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --from-beginning \
  --property print.key=true \
  --property print.partition=true \
  --property print.offset=true

# 예상 출력:
# Partition:0	Offset:0	Key:12345	Value:{"orderId":12345,...}
# Partition:1	Offset:0	Key:12346	Value:{"orderId":12346,...}
# Partition:2	Offset:0	Key:12347	Value:{"orderId":12347,...}
```

**분석:**
- 같은 Key는 항상 같은 파티션으로 전송됨
- Offset은 파티션 내에서 순차적으로 증가

---

## 6. Consumer Group 테스트

### 6.1 Consumer Group 생성 및 테스트

**터미널 1 - Consumer Group "ranking-updater" 실행:**
```bash
docker exec -it broker1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --group ranking-updater \
  --from-beginning

# Partition 0, 1, 2 모두 처리
```

**터미널 2 - 같은 Consumer Group에 Consumer 추가:**
```bash
docker exec -it broker2 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --group ranking-updater

# Rebalancing 발생 후 파티션 분산
# Consumer 1: Partition 0, 1
# Consumer 2: Partition 2
```

**터미널 3 - 다른 Consumer Group "analytics":**
```bash
docker exec -it broker3 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --group analytics \
  --from-beginning

# 독립적으로 처음부터 읽기 (ranking-updater와 별개)
```

### 6.2 Consumer Group 목록 조회

```bash
# Consumer Group 목록
docker exec -it broker1 kafka-consumer-groups --list \
  --bootstrap-server localhost:9092

# 예상 출력:
# ranking-updater
# analytics
```

### 6.3 Consumer Group 상세 정보

```bash
# Consumer Group 상세 정보 (Offset, Lag 확인)
docker exec -it broker1 kafka-consumer-groups --describe \
  --bootstrap-server localhost:9092 \
  --group ranking-updater

# 예상 출력:
# GROUP           TOPIC                    PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# ranking-updater ecommerce.order.events   0          5               5               0
# ranking-updater ecommerce.order.events   1          5               5               0
# ranking-updater ecommerce.order.events   2          5               5               0
```

**설명:**
- **CURRENT-OFFSET**: Consumer가 현재 읽은 위치
- **LOG-END-OFFSET**: 파티션의 최신 Offset
- **LAG**: 지연된 메시지 수 (LOG-END-OFFSET - CURRENT-OFFSET)
  - LAG = 0 → 실시간 처리 중
  - LAG > 0 → 지연 발생

---

## 7. 파티션 및 복제 확인

### 7.1 메시지가 어느 파티션으로 갔는지 확인

```bash
# Partition별 메시지 확인
for i in 0 1 2; do
  echo "=== Partition $i ==="
  docker exec -it broker1 kafka-console-consumer \
    --bootstrap-server localhost:9092 \
    --topic ecommerce.order.events \
    --partition $i \
    --from-beginning \
    --max-messages 3 \
    --property print.key=true \
    --property print.offset=true
done

# 예상 출력:
# === Partition 0 ===
# Offset:0	Key:12345	Value:{"orderId":12345,...}
# Offset:1	Key:12348	Value:{"orderId":12348,...}
#
# === Partition 1 ===
# Offset:0	Key:12346	Value:{"orderId":12346,...}
#
# === Partition 2 ===
# Offset:0	Key:12347	Value:{"orderId":12347,...}
# Offset:1	Key:12349	Value:{"orderId":12349,...}
```

### 7.2 Broker 장애 시뮬레이션

```bash
# Broker 1 중지
docker stop broker1

# Topic 상태 확인 (Leader 변경 확인)
docker exec -it broker2 kafka-topics --describe \
  --bootstrap-server localhost:9093 \
  --topic ecommerce.order.events

# 예상 출력:
# Partition: 0	Leader: 2	Replicas: 1,2	Isr: 2
# (Leader가 1 → 2로 변경됨)

# 메시지 발행 테스트 (정상 동작 확인)
docker exec -it broker2 kafka-console-producer \
  --bootstrap-server localhost:9093 \
  --topic ecommerce.order.events

> Test message after broker1 down
# → 정상 발행됨 (고가용성 확인)

# Broker 1 재시작
docker start broker1

# ISR 복구 확인
docker exec -it broker1 kafka-topics --describe \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events

# Isr: 1,2 (다시 동기화됨)
```

---

## 8. Offset 관리 테스트

### 8.1 Offset 리셋 (재처리)

```bash
# Consumer Group 중지 필요 (모든 Consumer 종료)

# Offset을 처음으로 리셋
docker exec -it broker1 kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group ranking-updater \
  --reset-offsets \
  --to-earliest \
  --topic ecommerce.order.events \
  --execute

# 예상 출력:
# GROUP           TOPIC                    PARTITION  NEW-OFFSET
# ranking-updater ecommerce.order.events   0          0
# ranking-updater ecommerce.order.events   1          0
# ranking-updater ecommerce.order.events   2          0

# Consumer 재실행 시 처음부터 다시 읽음
```

### 8.2 특정 Offset으로 이동

```bash
# Offset 10으로 이동
docker exec -it broker1 kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group ranking-updater \
  --reset-offsets \
  --to-offset 10 \
  --topic ecommerce.order.events \
  --execute
```

### 8.3 특정 시간으로 이동

```bash
# 2025-12-01 00:00:00 시점으로 이동
docker exec -it broker1 kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group ranking-updater \
  --reset-offsets \
  --to-datetime 2025-12-01T00:00:00.000 \
  --topic ecommerce.order.events \
  --execute
```

### 8.4 최신으로 이동 (메시지 스킵)

```bash
# 최신 Offset으로 이동 (지연 메시지 스킵)
docker exec -it broker1 kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group ranking-updater \
  --reset-offsets \
  --to-latest \
  --topic ecommerce.order.events \
  --execute
```

---

## 9. 클러스터 정리

### 9.1 Topic 삭제 (선택사항)

```bash
# Topic 삭제
docker exec -it broker1 kafka-topics --delete \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events

# 확인
docker exec -it broker1 kafka-topics --list \
  --bootstrap-server localhost:9092
```

### 9.2 클러스터 중지

```bash
# 모든 컨테이너 중지 및 삭제
docker compose -f docker-compose.kafka.yaml down

# 볼륨까지 삭제 (데이터 완전 삭제)
docker compose -f docker-compose.kafka.yaml down -v
```

### 9.3 로그 및 데이터 정리

```bash
# 중지된 컨테이너 정리
docker container prune

# 사용하지 않는 볼륨 정리
docker volume prune

# 사용하지 않는 네트워크 정리
docker network prune
```

---

## 10. 실습 체크리스트

### 10.1 기본 기능

- [ ] Kafka 클러스터 실행 (Zookeeper + Broker 3대)
- [ ] Broker 목록 확인
- [ ] Topic 생성 (파티션 3, 복제 2)
- [ ] Topic 상세 정보 확인 (Leader, Replicas, ISR)
- [ ] 콘솔 Producer로 메시지 발행
- [ ] 콘솔 Consumer로 메시지 수신
- [ ] Key-Value 메시지 발행 및 수신

### 10.2 고급 기능

- [ ] Consumer Group 생성 및 테스트
- [ ] 여러 Consumer 추가 시 Rebalancing 확인
- [ ] Consumer Group Offset 확인 (Lag 모니터링)
- [ ] 파티션별 메시지 분산 확인
- [ ] Broker 장애 시뮬레이션 (고가용성 확인)
- [ ] Leader 변경 확인
- [ ] Offset 리셋 (재처리 테스트)
- [ ] 특정 Offset/시간으로 이동

### 10.3 운영 및 모니터링

- [ ] 로그 확인 및 분석
- [ ] 클러스터 상태 모니터링
- [ ] Consumer Lag 모니터링
- [ ] 성능 테스트 (처리량 측정)
- [ ] 정상 종료 및 정리

---

## 11. 다음 단계

### 11.1 Spring Kafka 통합

이제 콘솔로 Kafka의 기본 기능을 확인했으니, Spring Boot 애플리케이션에서 Kafka를 사용해봅시다:

1. **의존성 추가**: `spring-kafka`
2. **Producer 구현**: `KafkaTemplate`
3. **Consumer 구현**: `@KafkaListener`
4. **Outbox Pattern 구현**: 트랜잭션 안정성 보장

### 11.2 실전 적용

- 주문 완료 이벤트를 Kafka로 발행
- Ranking, DataPlatform, Analytics Consumer 구현
- 기존 Spring Application Event를 Kafka로 전환
- 통합 테스트 및 검증

---

## 12. 트러블슈팅

### 문제 1: Broker가 시작되지 않음

**증상:**
```
docker logs broker1
[error] ... connection refused
```

**원인:** Zookeeper가 준비되지 않음

**해결:**
```bash
# Zookeeper 로그 확인
docker logs zookeeper

# Zookeeper 재시작
docker restart zookeeper

# 30초 대기 후 Broker 재시작
sleep 30
docker restart broker1 broker2 broker3
```

### 문제 2: Topic 생성 실패

**증상:**
```
Error: Topic 'xxx' already exists
```

**해결:**
```bash
# Topic 삭제 후 재생성
docker exec -it broker1 kafka-topics --delete \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events
```

### 문제 3: Consumer가 메시지를 받지 못함

**증상:** Consumer 실행했지만 아무것도 출력 안 됨

**원인:** `--from-beginning` 없이 실행 (최신 메시지부터 읽음)

**해결:**
```bash
# --from-beginning 옵션 추가
docker exec -it broker1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic ecommerce.order.events \
  --from-beginning
```

### 문제 4: Consumer Lag 발생

**증상:** LAG > 1000

**원인:** Consumer 처리 속도 < Producer 발행 속도

**해결:**
1. Consumer 수 증가 (Concurrency ↑)
2. 파티션 수 증가
3. Consumer 로직 최적화

---

## 결론

이 가이드를 통해 다음을 배웠습니다:

✅ **Kafka 클러스터 실행 및 관리**
- Docker Compose로 간편한 클러스터 구성
- Zookeeper + Broker 3대 고가용성 구조

✅ **Topic 관리**
- 파티션과 복제를 통한 확장성 및 안정성
- Retention 정책 설정

✅ **Producer/Consumer 기본 동작**
- 콘솔로 메시지 발행 및 수신
- Key-Value 메시지와 파티셔닝

✅ **Consumer Group**
- 독립적인 Offset 관리
- 병렬 처리 및 Rebalancing

✅ **고가용성 및 장애 복구**
- Broker 장애 시 자동 Leader 변경
- 데이터 손실 없는 복구

✅ **Offset 관리**
- 재처리를 위한 Offset 리셋
- 특정 시점으로 이동

**다음 단계는 Spring Kafka로 실제 애플리케이션 구현입니다!** 🚀
