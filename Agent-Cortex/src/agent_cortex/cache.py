from collections import OrderedDict
from enum import Enum
from agent_cortex.compression import compress, decompress


class EvictionPolicy(Enum):
    FIFO = "fifo"
    LRU  = "lru"


class CacheEntry:
    """Holds compressed bytes for a single agent output."""
    def __init__(self, text: str, compression_level: int = 6):
        self.compressed_bytes: bytes = compress(text, level=compression_level)
        self.original_size: int = len(text.encode("utf-8"))
        self.compressed_size: int = len(self.compressed_bytes)

    @classmethod
    def from_compressed(cls, compressed_bytes: bytes, original_size: int) -> "CacheEntry":
        """Build a CacheEntry from already-compressed bytes, skipping re-compression."""
        obj = cls.__new__(cls)
        obj.compressed_bytes = compressed_bytes
        obj.original_size = original_size
        obj.compressed_size = len(compressed_bytes)
        return obj

    def decompress(self) -> str:
        return decompress(self.compressed_bytes)


class BoundedCache:
    def __init__(self, max_slots: int, policy: EvictionPolicy, compression_level: int = 6):
        self.max_slots = max_slots
        self.policy = policy
        self.compression_level = compression_level
        self._store: OrderedDict[str, CacheEntry] = OrderedDict()

    def put(self, agent_id: str, text: str) -> str | None:
        evicted_id = None
        if agent_id in self._store:
            del self._store[agent_id]
        elif self.is_full():
            evicted_id = self.evict()
        self._store[agent_id] = CacheEntry(text, self.compression_level)
        return evicted_id

    def get(self, agent_id: str) -> str | None:
        if agent_id not in self._store:
            return None
        if self.policy == EvictionPolicy.LRU:
            self._store.move_to_end(agent_id)
        return self._store[agent_id].decompress()

    def evict(self) -> str | None:
        if not self._store:
            return None
        evicted_id, _ = self._store.popitem(last=False)
        return evicted_id

    def remove(self, agent_id: str) -> None:
        self._store.pop(agent_id, None)

    def contains(self, agent_id: str) -> bool:
        return agent_id in self._store

    def size(self) -> int:
        return len(self._store)

    def is_full(self) -> bool:
        return len(self._store) >= self.max_slots
