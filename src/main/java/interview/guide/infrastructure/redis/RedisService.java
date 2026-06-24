package interview.guide.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis 操作服务。
 * <p>
 * 对 Redisson 的简单封装，统一项目中的 Redis 访问入口。
 * 主要服务于面试模块的会话缓存和异步评估消息队列。
 *
 * <h3>Redisson vs Jedis/Lettuce</h3>
 * Redisson 是一个更高层的 Redis 客户端，提供了开箱即用的：
 * <ul>
 *   <li>{@link RBucket} — 键值操作（最常用，存会话对象）</li>
 *   <li>{@link RMap} — Hash 结构，适合存多字段数据</li>
 *   <li>{@link RLock} — 分布式锁，面试中用于防重复提交</li>
 *   <li>{@link RStream} — 消息队列，用于异步评估</li>
 *   <li>{@link RAtomicLong} — 原子计数器</li>
 * </ul>
 * Jedis/Lettuce 只提供底层命令，Redisson 帮你封装好了对象映射和连接池。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedissonClient redissonClient;

    // ==================== 基础键值操作 ====================
    // RBucket 是最常用的数据结构，相当于 Redis 的 STRING 类型，
    // 但能直接存 Java 对象（Redisson 自动序列化/反序列化）。

    /** 设置值（永不过期） */
    public <T> void set(String key, T value) {
        redissonClient.<T>getBucket(key).set(value);
    }

    /** 设置值（带 TTL 过期时间，过期后自动删除） */
    public <T> void set(String key, T value, Duration ttl) {
        redissonClient.<T>getBucket(key).set(value, ttl);
    }

    /** 取值。如果 key 不存在返回 null */
    public <T> T get(String key) {
        return redissonClient.<T>getBucket(key).get();
    }

    /** 删除 key。返回 true 表示确实删除了某个键 */
    public boolean delete(String key) {
        return redissonClient.getBucket(key).delete();
    }

    /** 判断 key 是否存在 */
    public boolean exists(String key) {
        return redissonClient.getBucket(key).isExists();
    }

    /** 设置过期时间，通常用于刷新 TTL */
    public boolean expire(String key, Duration ttl) {
        return redissonClient.getBucket(key).expire(ttl);
    }

    // ==================== Hash 操作 ====================
    // Hash 适合存储"一个大 key 下有多个字段"的场景，
    // 比如一个配置对象的不同字段。面试模块暂未使用，供后续扩展。

    /** 设置 Hash 中的某个字段 */
    public <K, V> void hSet(String key, K field, V value) {
        redissonClient.<K, V>getMap(key).put(field, value);
    }

    /** 获取 Hash 中的某个字段 */
    public <K, V> V hGet(String key, K field) {
        return redissonClient.<K, V>getMap(key).get(field);
    }

    /** 获取整个 Hash 的所有字段 */
    public <K, V> Map<K, V> hGetAll(String key) {
        return redissonClient.<K, V>getMap(key).readAllMap();
    }

    // ==================== 分布式锁 ====================
    // 面试答提交时防止并发重复提交，用锁保证同一会话的答案有序写入。
    // RLock 底层用 Redlock 算法，跨实例也有效。

    /** 获取锁（不阻塞，需手动调 lock/unlock） */
    public RLock getLock(String lockKey) {
        return redissonClient.getLock(lockKey);
    }

    /**
     * 尝试获取锁（非阻塞）。
     *
     * @param waitTime  等待锁的最长时间，单位由 unit 指定
     * @param leaseTime 锁持有时间，超时自动释放，防止死锁
     */
    public boolean tryLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            return lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 释放锁。只有持有锁的线程才能释放 */
    public void unlock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    // ==================== Stream 消息队列 ====================
    // Redis Stream 是 Redis 5.0+ 提供的有序消息队列，类似 Kafka 的 topic。
    // 面试模块用 Stream 做"异步评估"：用户答完最后一题后，
    // 把评估任务发到 Stream，消费者后台处理，不阻塞前端。
    //
    // 相比 RabbitMQ/Kafka，Redis Stream 不需要额外运维，适合小型项目。

    /**
     * 创建消费者组（如果不存在）。
     * 消费者组实现了"一条消息只被一个消费者消费一次"的语义，
     * 同一个组内的消费者分摊消息负载。
     */
    public void createStreamGroup(String streamKey, String groupName) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        try {
            stream.createGroup(
                    org.redisson.api.stream.StreamCreateGroupArgs.name(groupName).makeStream());
            log.info("Stream 消费者组已创建: stream={}, group={}", streamKey, groupName);
        } catch (Exception e) {
            // BUSYGROUP = 组已存在（重复调用不报错）
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                return;
            }
            log.warn("创建消费者组失败: stream={}, group={}, error={}",
                    streamKey, groupName, e.getMessage());
        }
    }

    /**
     * 发送消息到 Stream（无长度限制），返回消息 ID。
     *
     * @param streamKey Stream 的 Key
     * @param message   消息内容（Key-Value Map）
     * @return 消息 ID（可用于后续 ACK 或查询）
     */
    public String streamAdd(String streamKey, Map<String, String> message) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        var args = org.redisson.api.stream.StreamAddArgs.entries(message);
        return stream.add(args).toString();
    }

    /**
     * 发送消息到 Stream（带最大长度限制），返回消息 ID。
     * <p>
     * 当 Stream 长度超过 {@code maxLen} 时，Redis 会自动裁剪最旧的消息。
     * 防止 Stream 无限增长占用过多内存。
     *
     * @param streamKey Stream 的 Key
     * @param message   消息内容（Key-Value Map）
     * @param maxLen    最大消息条数，超出时自动裁剪旧消息
     * @return 消息 ID
     */
    public String streamAdd(String streamKey, Map<String, String> message, int maxLen) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        var args = org.redisson.api.stream.StreamAddArgs.entries(message);
        if (maxLen > 0) {
            args.trimNonStrict().maxLen(maxLen);
        }
        return stream.add(args).toString();
    }

    /**
     * 消费 Stream 消息（阻塞模式）。
     * <p>
     * 使用 BLOCK 参数让 Redis 服务端等待新消息，比客户端轮询高效。
     * blockTimeoutMs = 0 表示无限等待。
     *
     * @return true 如果处理了消息，false 如果超时无消息
     */
    public boolean streamConsumeMessages(
            String streamKey, String groupName, String consumerName, int count,
            long blockTimeoutMs,
            java.util.function.BiConsumer<
                    org.redisson.api.stream.StreamMessageId, Map<String, String>> processor) {

        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        Map<org.redisson.api.stream.StreamMessageId, Map<String, String>> messages;
        try {
            messages = stream.readGroup(groupName, consumerName,
                    org.redisson.api.stream.StreamReadGroupArgs.neverDelivered()
                            .count(count)
                            .timeout(Duration.ofMillis(blockTimeoutMs)));
        } catch (ClassCastException e) {
            // Redisson 4.0.0 bug: 无消息时空 List 强转 Map 失败，等价于无消息
            return false;
        }

        if (messages == null || messages.isEmpty()) return false;

        messages.forEach(processor);
        return true;
    }

    /** 确认消息已处理完成，从 pending 列表移除 */
    public void streamAck(String streamKey, String groupName,
                          org.redisson.api.stream.StreamMessageId... ids) {
        RStream<String, String> stream = redissonClient.getStream(streamKey, StringCodec.INSTANCE);
        stream.ack(groupName, ids);
    }

    // ==================== 计数器 & 批量删除 ====================

    /** 原子自增（用于生成序号） */
    public long increment(String key) {
        return redissonClient.getAtomicLong(key).incrementAndGet();
    }

    /** 按模式批量删除 key（如在清理测试数据时用） */
    public long deleteByPattern(String pattern) {
        return redissonClient.getKeys().deleteByPattern(pattern);
    }
}
