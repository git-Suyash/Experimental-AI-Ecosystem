import pytest
from agent_cortex.storage import StorageBackend
from agent_cortex.exceptions import StorageError


async def test_initialize_creates_table_and_wal(storage):
    async with storage._conn.execute("PRAGMA journal_mode") as cursor:
        row = await cursor.fetchone()
    assert row[0] == "wal"


async def test_write_then_read_returns_same_bytes(storage):
    data = b"compressed_payload_bytes"
    await storage.write("agent_1", data)
    result = await storage.read("agent_1")
    assert result == data


async def test_write_upsert_overwrites(storage):
    await storage.write("agent_1", b"first")
    await storage.write("agent_1", b"second")
    result = await storage.read("agent_1")
    assert result == b"second"


async def test_read_unknown_returns_none(storage):
    result = await storage.read("nonexistent_agent")
    assert result is None


async def test_delete_removes_entry(storage):
    await storage.write("agent_del", b"some data")
    await storage.delete("agent_del")
    result = await storage.read("agent_del")
    assert result is None
