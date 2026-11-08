package com.mangashelf.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

/**
 * 任务通知拓扑（DESIGN 11.1）：初期仅 content.process 队列，媒体处理与模型／索引
 * 并发分开在 M4 增加 content.index。拓扑声明幂等，API 与 Worker 均可执行。
 */
@Configuration
public class RabbitTopologyConfig {

    public static final String JOBS_EXCHANGE = "mangashelf.jobs";
    public static final String QUEUE_CONTENT_PROCESS = "content.process";
    public static final String QUEUE_CONTENT_INDEX = "content.index";

    @Bean
    public DirectExchange jobsExchange() {
        return new DirectExchange(JOBS_EXCHANGE, true, false);
    }

    @Bean
    public Queue contentProcessQueue() {
        return QueueBuilder.durable(QUEUE_CONTENT_PROCESS).build();
    }

    @Bean
    public Binding contentProcessBinding(DirectExchange jobsExchange, Queue contentProcessQueue) {
        return BindingBuilder.bind(contentProcessQueue).to(jobsExchange).with(QUEUE_CONTENT_PROCESS);
    }

    @Bean
    public Queue contentIndexQueue() {
        return QueueBuilder.durable(QUEUE_CONTENT_INDEX).build();
    }

    @Bean
    public Binding contentIndexBinding(DirectExchange jobsExchange, Queue contentIndexQueue) {
        return BindingBuilder.bind(contentIndexQueue).to(jobsExchange).with(QUEUE_CONTENT_INDEX);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, JsonMapper jsonMapper) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new JacksonJsonMessageConverter(jsonMapper));
        return template;
    }
}
