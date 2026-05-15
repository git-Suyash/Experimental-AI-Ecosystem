import time
import aiosqlite
from pathlib import Path
from agent_cortex.exceptions import StorageError


class StorageBackend:
    def __init__(self, db_path: str = "./agent_cortex.db"):
        self.db_path = Path(db_path)
        self._conn: aiosqlite.Connection | None = None

    async def initialize(self) -> None:
        try:
            self._conn = await aiosqlite.connect(self.db_path)
            await self._conn.execute("PRAGMA journal_mode=WAL")
            await self._conn.execute("PRAGMA foreign_keys=ON")
            await self._conn.execute("""
                CREATE TABLE IF NOT EXISTS checkpoints (
                    agent_id    TEXT PRIMARY KEY,
                    data        BLOB NOT NULL,
                    size_bytes  INTEGER NOT NULL,
                    created_at  REAL NOT NULL,
                    updated_at  REAL NOT NULL
                )
            """)
            await self._conn.commit()
        except Exception as exc:
            raise StorageError(f"Failed to initialize storage: {exc}") from exc

    async def write(self, agent_id: str, compressed_data: bytes) -> None:
        try:
            now = time.time()
            await self._conn.execute(
                """
                INSERT INTO checkpoints (agent_id, data, size_bytes, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(agent_id) DO UPDATE SET
                    data       = excluded.data,
                    size_bytes = excluded.size_bytes,
                    updated_at = excluded.updated_at
                """,
                (agent_id, compressed_data, len(compressed_data), now, now),
            )
            await self._conn.commit()
        except Exception as exc:
            raise StorageError(f"Failed to write agent '{agent_id}': {exc}") from exc

    async def read(self, agent_id: str) -> bytes | None:
        try:
            async with self._conn.execute(
                "SELECT data FROM checkpoints WHERE agent_id = ?", (agent_id,)
            ) as cursor:
                row = await cursor.fetchone()
                return row[0] if row else None
        except Exception as exc:
            raise StorageError(f"Failed to read agent '{agent_id}': {exc}") from exc

    async def delete(self, agent_id: str) -> None:
        try:
            await self._conn.execute(
                "DELETE FROM checkpoints WHERE agent_id = ?", (agent_id,)
            )
            await self._conn.commit()
        except Exception as exc:
            raise StorageError(f"Failed to delete agent '{agent_id}': {exc}") from exc

    async def close(self) -> None:
        if self._conn:
            await self._conn.close()
            self._conn = None
