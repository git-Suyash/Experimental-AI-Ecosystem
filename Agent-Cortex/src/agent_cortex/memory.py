import asyncio
import logging
import time
from agent_cortex.cache import BoundedCache, CacheEntry, EvictionPolicy
from agent_cortex.registry import LookasideRegistry, StorageStatus
from agent_cortex.storage import StorageBackend
from agent_cortex.compression import compress, decompress
from agent_cortex.exceptions import PayloadTooLargeError
from agent_cortex.locks import KeyedLockManager
from agent_cortex.events import EventBus, MemoryEvent

logger = logging.getLogger(__name__)


class AgentMemory:
    def __init__(
        self,
        max_slots: int = 10,
        policy: EvictionPolicy = EvictionPolicy.FIFO,
        max_payload_mb: int = 50,
        db_path: str = "./agent_cortex.db",
        compression_level: int = 6,
    ):
        self.max_payload_bytes = max_payload_mb * 1024 * 1024
        self._compression_level = compression_level
        self._cache = BoundedCache(max_slots, policy, compression_level)
        self._registry = LookasideRegistry()
        self._storage = StorageBackend(db_path)
        self._global_lock = asyncio.Lock()
        self._key_locks = KeyedLockManager()
        self._event_bus = EventBus()

    def subscribe(self) -> asyncio.Queue:
        """Subscribe to memory lifecycle events. Returns an asyncio.Queue."""
        return self._event_bus.subscribe()

    async def initialize(self) -> None:
        await self._storage.initialize()

    async def put(self, agent_id: str, output_data: str) -> None:
        size_bytes = len(output_data.encode("utf-8"))

        if size_bytes > self.max_payload_bytes:
            self._registry.register(
                agent_id,
                StorageStatus.STREAMING_TO_DISK,
                in_memory=False,
                size_bytes=size_bytes,
            )
            task = asyncio.create_task(self._direct_disk_write(agent_id, output_data))
            self._registry.get(agent_id).write_promise = task
            await self._event_bus.emit(MemoryEvent(
                agent_id=agent_id,
                event_type="registered",
                status=StorageStatus.STREAMING_TO_DISK,
                in_memory=False,
                size_bytes=size_bytes,
                timestamp=time.time(),
            ))
            return

        evicted_id = None
        compressed = compress(output_data, self._compression_level)
        async with self._global_lock:
            if self._cache.is_full():
                evicted_id = self._cache.evict()
                if evicted_id:
                    self._handle_eviction(evicted_id)

            task = asyncio.create_task(self._async_disk_write(agent_id, compressed))
            self._registry.register(
                agent_id,
                StorageStatus.PENDING,
                in_memory=True,
                write_promise=task,
                size_bytes=size_bytes,
            )
            self._cache._store[agent_id] = CacheEntry.from_compressed(compressed, size_bytes)

        await self._event_bus.emit(MemoryEvent(
            agent_id=agent_id,
            event_type="registered",
            status=StorageStatus.PENDING,
            in_memory=True,
            size_bytes=size_bytes,
            timestamp=time.time(),
        ))

        if evicted_id:
            evicted_entry = self._registry.get(evicted_id)
            await self._event_bus.emit(MemoryEvent(
                agent_id=evicted_id,
                event_type="evicted",
                status=evicted_entry.status if evicted_entry else StorageStatus.EVICTED,
                in_memory=False,
                size_bytes=evicted_entry.size_bytes if evicted_entry else 0,
                timestamp=time.time(),
            ))

    def _handle_eviction(self, evicted_id: str) -> None:
        """
        Mark evicted entry. Does NOT cancel write_promise — background task
        owns the PENDING → COMMITTED transition.
        """
        self._registry.mark_evicted(evicted_id)

    async def get(self, agent_id: str) -> str | None:
        async with self._global_lock:
            entry = self._registry.get(agent_id)
            if entry is None:
                return None
            if entry.in_memory:
                return self._cache.get(agent_id)

        # Cache miss — per-key lock prevents thundering herd on disk reads
        await self._key_locks.acquire(agent_id)
        try:
            # Re-check under global lock — another coroutine may have already resolved it
            async with self._global_lock:
                entry = self._registry.get(agent_id)
                if entry and entry.in_memory:
                    return self._cache.get(agent_id)

            # Still a miss — we are the designated resolver
            if entry.write_promise and not entry.write_promise.done():
                await entry.write_promise

            compressed = await self._storage.read(agent_id)
            if compressed is None:
                return None

            result = decompress(compressed)

            # Re-warm the cache slot if space allows
            async with self._global_lock:
                if not self._cache.is_full():
                    self._cache._store[agent_id] = CacheEntry(result, self._compression_level)
                    self._registry.update_status(agent_id, StorageStatus.COMMITTED)
                    self._registry.get(agent_id).in_memory = True

            entry = self._registry.get(agent_id)
            await self._event_bus.emit(MemoryEvent(
                agent_id=agent_id,
                event_type="resolved",
                status=entry.status if entry else StorageStatus.COMMITTED,
                in_memory=entry.in_memory if entry else False,
                size_bytes=entry.size_bytes if entry else 0,
                timestamp=time.time(),
            ))

            return result
        finally:
            await self._key_locks.release(agent_id)

    async def get_many(self, agent_ids: list[str]) -> dict[str, str | None]:
        results = await asyncio.gather(*[self.get(aid) for aid in agent_ids])
        return dict(zip(agent_ids, results))

    async def status(self, agent_id: str) -> StorageStatus | None:
        entry = self._registry.get(agent_id)
        return entry.status if entry else None

    async def registry_snapshot(self) -> dict:
        raw = self._registry.snapshot()
        in_memory = on_disk_only = pending = committed = streaming = 0
        for e in raw.values():
            if e["in_memory"]:
                in_memory += 1
            else:
                on_disk_only += 1
            s = e["status"]
            if s == "pending":
                pending += 1
            elif s == "committed":
                committed += 1
            elif s == "streaming_to_disk":
                streaming += 1
        summary = {
            "total_agents": len(raw),
            "in_memory": in_memory,
            "on_disk_only": on_disk_only,
            "pending": pending,
            "committed": committed,
            "streaming_to_disk": streaming,
        }
        return {"summary": summary, "agents": raw}

    async def close(self) -> None:
        pending = [
            entry.write_promise
            for entry in self._registry._entries.values()
            if entry.write_promise and not entry.write_promise.done()
        ]
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)
        await self._storage.close()

    async def _async_disk_write(self, agent_id: str, compressed_data: bytes) -> None:
        try:
            await self._storage.write(agent_id, compressed_data)
            self._registry.update_status(agent_id, StorageStatus.COMMITTED)
            entry = self._registry.get(agent_id)
            await self._event_bus.emit(MemoryEvent(
                agent_id=agent_id,
                event_type="committed",
                status=StorageStatus.COMMITTED,
                in_memory=entry.in_memory if entry else False,
                size_bytes=entry.size_bytes if entry else 0,
                timestamp=time.time(),
            ))
        except Exception as exc:
            logger.error("Failed to write agent '%s' to disk: %s", agent_id, exc)

    async def _direct_disk_write(self, agent_id: str, output_data: str) -> None:
        try:
            compressed = compress(output_data, self._compression_level)
            await self._storage.write(agent_id, compressed)
            self._registry.update_status(agent_id, StorageStatus.COMMITTED)
            entry = self._registry.get(agent_id)
            await self._event_bus.emit(MemoryEvent(
                agent_id=agent_id,
                event_type="committed",
                status=StorageStatus.COMMITTED,
                in_memory=False,
                size_bytes=entry.size_bytes if entry else 0,
                timestamp=time.time(),
            ))
        except Exception as exc:
            logger.error("Failed to stream agent '%s' to disk: %s", agent_id, exc)
