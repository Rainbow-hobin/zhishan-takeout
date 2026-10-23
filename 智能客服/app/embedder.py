"""Embedding 封装：把文本转成向量（本地模型，无需 API Key）。

使用 fastembed + BAAI/bge-small-zh-v1.5（中文效果好、体积小的轻量模型）。
首次运行会自动下载模型（约 100MB）到本地缓存；
下载慢或失败时，把环境变量 HF_ENDPOINT 设为 https://hf-mirror.com 走国内镜像。
"""
from __future__ import annotations

import os
import threading

# 必须在导入 fastembed 之前设置，让它下载模型时走国内镜像
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

from fastembed import TextEmbedding  # noqa: E402

_embedder: TextEmbedding | None = None
_lock = threading.Lock()


def get_embedder() -> TextEmbedding:
    """获取全局唯一的 TextEmbedding 实例（双重检查锁，线程安全）。"""
    global _embedder
    if _embedder is None:
        with _lock:
            if _embedder is None:
                _embedder = TextEmbedding("BAAI/bge-small-zh-v1.5")
    return _embedder


def embed_texts(texts: list[str]) -> list[list[float]]:
    """把一批文本转成向量，返回 list[list[float]]。

    自动过滤空字符串和纯空白输入，避免模型报错。
    """
    filtered = [t for t in texts if t and t.strip()]
    if not filtered:
        return []
    return [vec.tolist() for vec in get_embedder().embed(filtered)]
