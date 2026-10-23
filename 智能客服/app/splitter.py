"""知识库切分器：把 Markdown 语料切成检索单元（chunk）。

约定语料格式（见 data/knowledge_base.md）：
    ## 板块名
    Q: 问题
    A: 答案

切分规则：
  1. 按 "## 板块名" 分成板块
  2. 板块内按 "Q:" 切成一条条，"一条 Q + 对应 A" 就是一个检索单元
  3. Q: 后为空时直接丢弃该问题
  4. 空行一律跳过
  5. 答案续行保留原始缩进（仅去除行尾换行）
"""


def split_knowledge_base(text: str) -> list[dict]:
    """返回 [{"section": 板块名, "q": 问题, "content": "问题：...\\n回答：..."}]"""
    chunks: list[dict] = []
    section = "通用"
    cur_question: str | None = None
    cur_answer: list[str] = []

    def flush() -> None:
        nonlocal cur_question, cur_answer
        if cur_question and cur_answer:
            content = f"问题：{cur_question}\n回答：{'\n'.join(cur_answer).strip()}"
            chunks.append({"section": section, "q": cur_question, "content": content})
        cur_question = None
        cur_answer = []

    for line in text.splitlines():
        s = line.strip()
        if not s:
            # 空行一律跳过
            continue
        if s.startswith("## "):
            flush()
            section = s[3:].strip()
        elif s.startswith("Q:"):
            flush()
            q_text = s[2:].strip()
            # Q: 后为空时丢弃，不创建新条目
            if q_text:
                cur_question = q_text
        elif s.startswith("A:") and cur_question:
            cur_answer.append(s[2:].strip())
        elif cur_question:
            # 答案续行：保留原始缩进（仅去行尾换行，不去前导空格）
            cur_answer.append(line.rstrip())
    flush()
    return chunks
