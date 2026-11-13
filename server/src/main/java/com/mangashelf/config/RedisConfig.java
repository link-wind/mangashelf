package com.mangashelf.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Redis 接入（redis-shared-cache-and-storage 决策 1/5）：
 * enabled=false 时不创建连接；所有读写包异常降级——失败按"无缓存/放行"处理并计数告警，
 * 业务事实不进 Redis，因此降级不影响正确性。
 */
@Configuration
@ConditionalOnProperty(name = "mangashelf.redis.enabled", havingValue = "true")
public class RedisConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(MangaShelfProperties props) {
        MangaShelfProperties.Redis redis = props.redis();
        String[] hostPort = redis.address().split(":");
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(hostPort[0], Integer.parseInt(hostPort[1]));
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(redis.commandTimeoutMs()))
                .build();
        return new LettuceConnectionFactory(standalone, client);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public SharedCacheHelper sharedCacheHelper(StringRedisTemplate template,
                                               ObjectProvider<MeterRegistry> registry) {
        return new SharedCacheHelper(template, registry.getIfAvailable());
    }
}
