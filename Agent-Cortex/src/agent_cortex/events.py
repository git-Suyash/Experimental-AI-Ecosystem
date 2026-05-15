import asyncio
import time
from dataclasses import dataclass
from agent_cortex.registry import StorageStatus


@dataclass
class MemoryEvent:
    agent_id: str
    event_type: str        # "registered", "committed", "evicted", "resolved"
    status: StorageStatus
    in_memory: bool
    size_bytes: int
    timestamp: float


class EventBus:
    """Simple async pub/sub for memory lifecycle events."""

    def __init__(self):
        self._subscribers: list[asyncio.Queue] = []

    def subscribe(self) -> asyncio.Queue:
        """Return a new Queue that receives all future MemoryEvents."""
        q: asyncio.Queue = asyncio.Queue()
        self._subscribers.append(q)
        return q

    def unsubscribe(self, queue: asyncio.Queue) -> None:
        if queue in self._subscribers:
            self._subscribers.remove(queue)

    async def emit(self, event: MemoryEvent) -> None:
        """Put event into every subscriber queue. Non-blocking."""
        for q in self._subscribers:
            q.put_nowait(event)
