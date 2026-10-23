"""Chroma 向量库封装：入库、清空、检索。"""
from __future__ import annotations

import threading
import uuid

import chromadb

from . import config

_client: chromadb.Client | None = None
_lock = threading.Lock()

# 创建集合时指定内积空间，配合 BGE 归一化向量使用
_COLLECTION_META = {"hnsw:space": "ip"}
_COLLECTION_NAME = "knowledge_base"


def get_client() -> chromadb.Client:
    """获取全局唯一的 Chroma 客户端（双重检查锁，线程安全）。"""
    global _client
    if _client is None:
        with _lock:
            if _client is None:
                _client = chromadb.PersistentClient(path=config.CHROMA_DIR)
    return _client


def get_collection() -> chromadb.Collection:
    """取（或建）知识库集合。"""
    return get_client().get_or_create_collection(
        name=_COLLECTION_NAME, metadata=_COLLECTION_META
    )


def reset_collection() -> chromadb.Collection:
    """清空旧库并重建，保证 ingest 幂等（重复跑不会越积越多）。"""
    client = get_client()
    try:
        client.delete_collection(_COLLECTION_NAME)
    except Exception as e:
        # 仅忽略"集合不存在"的异常，其余重新抛出
        if "not found" not in str(e).lower() and "does not exist" not in str(e).lower():
            raise
    return client.get_or_create_collection(
        name=_COLLECTION_NAME, metadata=_COLLECTION_META
    )


def _validate_inputs(chunks: list[dict], vectors: list[list[float]]) -> None:
    """入参校验：长度一致、非空。"""
    if not chunks:
        raise ValueError("chunks 不能为空")
    if not vectors:
        raise ValueError("vectors 不能为空")
    if len(chunks) != len(vectors):
        raise ValueError(
            f"chunks({len(chunks)}) 与 vectors({len(vectors)}) 长度不一致"
        )


def add_chunks_overwrite(chunks: list[dict], vectors: list[list[float]]) -> int:
    """全量覆盖入库（幂等）：先清空再写入，适合 ingest 脚本使用。"""
    _validate_inputs(chunks, vectors)
    col = reset_collection()
    ids = [f"{c['section']}-{i}" for i, c in enumerate(chunks)]
    docs = [c["content"] for c in chunks]
    metas = [{"section": c["section"], "question": c["q"]} for c in chunks]
    col.add(ids=ids, documents=docs, embeddings=vectors, metadatas=metas)
    return len(ids)


def add_chunks_append(chunks: list[dict], vectors: list[list[float]]) -> int:
    """增量追加入库：使用 uuid 保证 ID 全局唯一，不清空已有数据。"""
    _validate_inputs(chunks, vectors)
    col = get_collection()
    ids = [str(uuid.uuid4()) for _ in chunks]
    docs = [c["content"] for c in chunks]
    metas = [{"section": c["section"], "question": c["q"]} for c in chunks]
    col.add(ids=ids, documents=docs, embeddings=vectors, metadatas=metas)
    return len(ids)


def search(query_vector: list[float], top_k: int = 3) -> dict:
    """按向量相似度检索，返回 Chroma 的原始查询结果。"""
    col = get_collection()
    return col.query(query_embeddings=[query_vector], n_results=top_k)
