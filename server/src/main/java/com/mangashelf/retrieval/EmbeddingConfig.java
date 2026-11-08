package com.mangashelf.retrieval;

import com.mangashelf.config.MangaShelfProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地 ONNX Embedding（implement-intelligent-retrieval D7）：
 * e5-small-v2（384 维），模型文件不入库；未启用或文件缺失时语义入口明确 503。
 * 排除官方自动配置，改由本配置按需装配（模型缺失不阻塞应用启动）。
 */
@Configuration
@ConditionalOnProperty(prefix = "mangashelf.embedding", name = "enabled", havingValue = "true")
public class EmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel(MangaShelfProperties props) throws Exception {
        var embedding = props.embedding();
        Path modelFile = Path.of(embedding.modelPath()).toAbsolutePath().normalize();
        Path tokenizerFile = Path.of(embedding.tokenizerPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(modelFile) || !Files.isRegularFile(tokenizerFile)) {
            throw new IllegalStateException(
                    "Embedding 模型文件缺失：" + modelFile + " / " + tokenizerFile
                            + "（可设 mangashelf.embedding.enabled=false）");
        }
        TransformersEmbeddingModel embeddingModel = new TransformersEmbeddingModel();
        embeddingModel.setModelResource(new FileSystemResource(modelFile));
        embeddingModel.setTokenizerResource(new FileSystemResource(tokenizerFile));
        embeddingModel.afterPropertiesSet();
        return embeddingModel;
    }
}
