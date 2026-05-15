import pytest
from agent_cortex import AgentMemory, EvictionPolicy
from agent_cortex.registry import StorageStatus


async def test_put_then_get_cache_hit(memory_fifo):
    await memory_fifo.put("agent_1", "hello from agent 1")
    result = await memory_fifo.get("agent_1")
    assert result == "hello from agent 1"


async def test_get_unregistered_returns_none(memory_fifo):
    result = await memory_fifo.get("ghost_agent")
    assert result is None


async def test_large_payload_routes_to_streaming(tmp_db):
    # max_payload_mb=0 means even tiny payloads exceed the limit
    mem = AgentMemory(max_slots=5, policy=EvictionPolicy.FIFO, max_payload_mb=0, db_path=tmp_db)
    await mem.initialize()
    try:
        await mem.put("big_agent", "any text at all")
        entry = mem._registry.get("big_agent")
        assert entry is not None
        assert entry.status == StorageStatus.STREAMING_TO_DISK
    finally:
        await mem.close()


async def test_registry_snapshot_shows_statuses(memory_fifo):
    await memory_fifo.put("a1", "output one")
    await memory_fifo.put("a2", "output two")
    snapshot = await memory_fifo.registry_snapshot()
    agents = snapshot["agents"]
    assert "a1" in agents
    assert "a2" in agents
    assert agents["a1"]["status"] in ("pending", "committed")
    assert agents["a2"]["status"] in ("pending", "committed")


async def test_close_completes_with_pending_writes(tmp_db):
    mem = AgentMemory(max_slots=5, policy=EvictionPolicy.FIFO, db_path=tmp_db)
    await mem.initialize()
    for i in range(5):
        await mem.put(f"agent_{i}", f"output {i}")
    # Should complete without error
    await mem.close()


async def test_all_statuses_committed_after_close(tmp_db):
    mem = AgentMemory(max_slots=5, policy=EvictionPolicy.FIFO, db_path=tmp_db)
    await mem.initialize()
    agents = [f"agent_{i}" for i in range(5)]
    for aid in agents:
        await mem.put(aid, f"data for {aid}")
    await mem.close()

    # Reopen and verify all data is on disk
    mem2 = AgentMemory(max_slots=5, policy=EvictionPolicy.FIFO, db_path=tmp_db)
    await mem2.initialize()
    for aid in agents:
        result = await mem2._storage.read(aid)
        assert result is not None, f"{aid} not found on disk"
    await mem2.close()


async def test_sequential_10_agent_pipeline(tmp_db):
    mem = AgentMemory(max_slots=5, policy=EvictionPolicy.LRU, db_path=tmp_db)
    await mem.initialize()

    agents = [f"agent_{i:02d}" for i in range(10)]
    outputs = {a: f"Output from {a}: " + ("x" * 200) for a in agents}

    for aid in agents:
        await mem.put(aid, outputs[aid])

    await mem.close()

    # Reopen to verify disk persistence
    mem2 = AgentMemory(max_slots=5, policy=EvictionPolicy.LRU, db_path=tmp_db)
    await mem2.initialize()
    for aid in agents:
        result = await mem2._storage.read(aid)
        assert result is not None, f"{aid} missing from disk"
    await mem2.close()
