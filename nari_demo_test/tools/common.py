import json
from pathlib import Path
from typing import Any


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def keyword_score(text: str, keywords: list[str]) -> int:
    return sum(1 for kw in keywords if kw and kw in text)


def unique_keep_order(items: list[str]) -> list[str]:
    seen = set()
    result = []
    for item in items:
        if item not in seen:
            result.append(item)
            seen.add(item)
    return result


def safe_read_text(path: Path, max_chars: int = 2000) -> str:
    suffix = path.suffix.lower()
    if suffix in {".txt", ".csv", ".json", ".md"}:
        for encoding in ("utf-8", "gbk", "gb18030"):
            try:
                return path.read_text(encoding=encoding, errors="ignore")[:max_chars]
            except OSError:
                return ""
            except UnicodeError:
                continue
    if suffix == ".docx":
        return read_docx_text(path, max_chars=max_chars)
    return ""


def read_docx_text(path: Path, max_chars: int = 2000) -> str:
    import zipfile
    from xml.etree import ElementTree

    ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
    try:
        with zipfile.ZipFile(path) as zf:
            xml = zf.read("word/document.xml")
        root = ElementTree.fromstring(xml)
    except (OSError, KeyError, zipfile.BadZipFile, ElementTree.ParseError):
        return ""
    paragraphs = []
    for para in root.findall(".//w:p", ns):
        texts = [node.text or "" for node in para.findall(".//w:t", ns)]
        text = "".join(texts).strip()
        if text:
            paragraphs.append(text)
        joined = "\n".join(paragraphs)
        if len(joined) >= max_chars:
            return joined[:max_chars]
    return "\n".join(paragraphs)[:max_chars]
