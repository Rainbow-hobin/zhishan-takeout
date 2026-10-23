"""第 1 步：构建知识库向量库。

流程：读 data/knowledge_base.md → 切分 → 向量化 → 存入本地向量库 .chroma/
用法：.venv\\Scripts\\python ingest.py
"""
import sys

sys.stdout.reconfigure(encoding="utf-8")

from app import config
from app.embedder import embed_texts
from app.splitter import split_knowledge_base
from app.vector_store import add_chunks_overwrite


def main():
    # 1. 知识库文件存在性校验
    if not config.KB_FILE.exists():
        print(f"❌ 知识库文件不存在：{config.KB_FILE}")
        print("   请先创建 data/knowledge_base.md（格式见 README）")
        return

    try:
        text = config.KB_FILE.read_text(encoding="utf-8")
        chunks = split_knowledge_base(text)
        if not chunks:
            print("⚠️  知识库切分结果为空，请检查文件格式是否正确。")
            print("   约定格式：## 板块名 → Q: 问题 → A: 答案")
            return
        print(f"[1/3] 读入 {config.KB_FILE.name}：共 {len(chunks)} 条问答")

        print("[2/3] 向量化（首次会下载 embedding 模型约 100MB，请耐心等待）...")
        contents = [c["content"] for c in chunks]
        vectors = embed_texts(contents)
        if not vectors:
            print("❌ 向量化失败：所有文本为空，请检查知识库内容。")
            return

        print("[3/3] 写入向量库（全量覆盖，幂等）...")
        n = add_chunks_overwrite(chunks, vectors)
        print(f"✅ 入库完成：{n} 条，向量库目录：{config.CHROMA_DIR}")
        print("下一步：运行 ask.py 提问（.venv\\Scripts\\python ask.py）")
    except Exception as e:
        print(f"❌ 入库失败：{type(e).__name__}: {e}")
        raise


if __name__ == "__main__":
    main()
