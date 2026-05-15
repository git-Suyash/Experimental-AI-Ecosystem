import zlib
from agent_cortex.exceptions import StorageError


def compress(text: str, level: int = 6) -> bytes:
    """Encode text to UTF-8 and compress with zlib. level: 1 (fastest) to 9 (best)."""
    try:
        return zlib.compress(text.encode("utf-8"), level)
    except Exception as exc:
        raise StorageError(f"Compression failed: {exc}") from exc


def decompress(data: bytes) -> str:
    """Decompress zlib bytes and decode to UTF-8 string."""
    try:
        return zlib.decompress(data).decode("utf-8")
    except (zlib.error, UnicodeDecodeError) as exc:
        raise StorageError(f"Decompression failed: {exc}") from exc
