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
 * 任务通知拓扑（DESIGN 11.1 / harden-reliable-jobs-and-fault-injection）：
 * 按任务类型拆分队列（content.resolve / content.process / content.index），
 * 全部绑定死信交换机 mangashelf.jobs.dlx 与死信队列 content.dlq 以隔离毒消息。
 */
@Configuration
public class RabbitTopologyConfig {

    public static final String JOBS_EXCHANGE = "mangashelf.jobs";
    public static final String JOBS_DLX = "mangashelf.jobs.dlx";
    public static final String QUEUE_CONTENT_PROCESS = "content.process";
    public static final String QUEUE_CONTENT_RESOLVE = "content.resolve";
    public static final String QUEUE_CONTENT_INDEX = "content.index";
    public static final String QUEUE_CONTENT_DLQ = "content.dlq";

    @Bean
    public DirectExchange jobsExchange() {
        return new DirectExchange(JOBS_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange jobsDeadLetterExchange() {
        return new DirectExchange(JOBS_DLX, true, false);
    }

    @Bean
    public Queue contentDlq() {
        return QueueBuilder.durable(QUEUE_CONTENT_DLQ).build();
    }

    @Bean
    public Binding contentDlqBinding(DirectExchange jobsDeadLetterExchange, Queue contentDlq) {
        return BindingBuilder.bind(contentDlq).to(jobsDeadLetterExchange).with(QUEUE_CONTENT_DLQ);
    }

    @Bean
    public Queue contentProcessQueue() {
        return QueueBuilder.durable(QUEUE_CONTENT_PROCESS)
                .deadLetterExchange(JOBS_DLX)
                .deadLetterRoutingKey(QUEUE_CONTENT_DLQ)
                .build();
    }

    @Bean
    public Binding contentProcessBinding(DirectExchange jobsExchange, Queue contentProcessQueue) {
        return BindingBuilder.bind(contentProcessQueue).to(jobsExchange).with(QUEUE_CONTENT_PROCESS);
    }

    @Bean
    public Queue contentResolveQueue() {
        return QueueBuilder.durable(QUEUE_CONTENT_RESOLVE)
                .deadLetterExchange(JOBS_DLX)
                .deadLetterRoutingKey(QUEUE_CONTENT_DLQ)
                .build();
    }

    @Bean
    public Binding contentResolveBinding(DirectExchange jobsExchange, Queue contentResolveQueue) {
        return BindingBuilder.bind(contentResolveQueue).to(jobsExchange).with(QUEUE_CONTENT_RESOLVE);
    }

    @Bean
    public Queue contentIndexQueue() {
        return QueueBuilder.durable(QUEUE_CONTENT_INDEX)
                .deadLetterExchange(JOBS_DLX)
                .deadLetterRoutingKey(QUEUE_CONTENT_DLQ)
                .build();
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
