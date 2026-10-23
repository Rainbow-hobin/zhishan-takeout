"""配置入口：统一从 .env 读取所有配置。"""
import os
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / ".env")

# ---- LLM（大模型生成） ----
DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "").strip()
LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://api.deepseek.com").strip()
LLM_MODEL = os.getenv("LLM_MODEL", "deepseek-chat").strip()

# ---- 检索 ----
CHROMA_DIR = Path(os.getenv("CHROMA_DIR", str(BASE_DIR / ".chroma")).strip())
try:
    TOP_K = int(os.getenv("TOP_K", "3"))
except ValueError:
    TOP_K = 3

# ---- 知识库文件 ----
KB_FILE = BASE_DIR / "data" / "knowledge_base.md"

# ---- 检索质量 ----
# 距离阈值：检索结果 distance 超过此值视为不相关，不送入 LLM
DISTANCE_THRESHOLD = float(os.getenv("DISTANCE_THRESHOLD", "1.5"))


def validate_config() -> list[str]:
    """启动校验，返回告警信息列表（空列表表示一切正常）。"""
    warnings: list[str] = []
    if not DEEPSEEK_API_KEY:
        warnings.append("DEEPSEEK_API_KEY 未配置，LLM 问答不可用（检索仍可用）")
    if TOP_K <= 0:
        warnings.append(f"TOP_K={TOP_K} 不合法，应为正整数")
    if not KB_FILE.exists():
        warnings.append(f"知识库文件不存在：{KB_FILE}")
    return warnings
