import asyncio


class KeyedLockManager:
    """
    Manages per-key asyncio Locks for fine-grained mutual exclusion
    on individual agent_ids without blocking the entire system.
    """
    def __init__(self):
        self._locks: dict[str, asyncio.Lock] = {}
        self._meta_lock = asyncio.Lock()

    async def acquire(self, key: str) -> asyncio.Lock:
        """
        Get or create the lock for `key` and acquire it.
        Uses _meta_lock only for creation to avoid deadlock.
        """
        async with self._meta_lock:
            if key not in self._locks:
                self._locks[key] = asyncio.Lock()
            lock = self._locks[key]
        await lock.acquire()
        return lock

    async def release(self, key: str) -> None:
        """Release the lock for `key`. No-op if key has no lock."""
        lock = self._locks.get(key)
        if lock and lock.locked():
            lock.release()

    def locked(self, key: str) -> bool:
        """Return True if the lock for `key` is currently held."""
        lock = self._locks.get(key)
        return lock.locked() if lock else False
