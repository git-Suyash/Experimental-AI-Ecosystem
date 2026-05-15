import asyncio
import time
from enum import Enum
from dataclasses import dataclass, field
from typing import Any


class StorageStatus(Enum):
    PENDING           = "pending"
    COMMITTED         = "committed"
    EVICTED           = "evicted"
    STREAMING_TO_DISK = "streaming_to_disk"


@dataclass
class RegistryEntry:
    agent_id: str
    status: StorageStatus
    in_memory: bool
    write_promise: asyncio.Task | None = None
    size_bytes: int = 0
    registered_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)

    def touch(self) -> None:
        self.updated_at = time.time()


class LookasideRegistry:
    def __init__(self):
        self._entries: dict[str, RegistryEntry] = {}

    def register(
        self,
        agent_id: str,
        status: StorageStatus,
        in_memory: bool,
        write_promise: asyncio.Task | None = None,
        size_bytes: int = 0,
    ) -> RegistryEntry:
        entry = RegistryEntry(
            agent_id=agent_id,
            status=status,
            in_memory=in_memory,
            write_promise=write_promise,
            size_bytes=size_bytes,
        )
        self._entries[agent_id] = entry
        return entry

    def update_status(self, agent_id: str, status: StorageStatus) -> None:
        self._entries[agent_id].status = status
        self._entries[agent_id].touch()

    def mark_evicted(self, agent_id: str) -> None:
        entry = self._entries.get(agent_id)
        if entry:
            entry.in_memory = False
            if entry.status == StorageStatus.COMMITTED:
                entry.status = StorageStatus.EVICTED
            entry.touch()

    def get(self, agent_id: str) -> RegistryEntry | None:
        return self._entries.get(agent_id)

    def exists(self, agent_id: str) -> bool:
        return agent_id in self._entries

    def snapshot(self) -> dict[str, dict[str, Any]]:
        return {
            agent_id: {
                "status": entry.status.value,
                "in_memory": entry.in_memory,
                "size_bytes": entry.size_bytes,
                "registered_at": entry.registered_at,
                "updated_at": entry.updated_at,
            }
            for agent_id, entry in self._entries.items()
        }
