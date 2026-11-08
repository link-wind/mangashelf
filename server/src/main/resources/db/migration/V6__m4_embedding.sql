-- M6 迁移（M4 第二阶段）：向量列与语义检索。
-- 维度 384 由本机实测 e5-small-v2（Spring AI transformers，2026-11-08）产出向量后固定；
-- 换模型必须新迁移并全量重建批次（DESIGN 8.2 / implement-intelligent-retrieval D1）。

ALTER TABLE search_chunks ADD COLUMN embedding vector(384);

CREATE INDEX idx_chunks_embedding_hnsw ON search_chunks
    USING hnsw (embedding vector_cosine_ops);
