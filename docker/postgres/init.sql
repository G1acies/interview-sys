-- 启用 pgvector 向量搜索插件
-- 在容器首次启动时由 /docker-entrypoint-initdb.d/ 下的脚本自动执行
CREATE EXTENSION IF NOT EXISTS vector;
