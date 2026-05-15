import asyncio
import pytest
from agent_cortex import AgentMemory, EvictionPolicy
from agent_cortex.cache import CacheEntry
from agent_cortex.registry import StorageStatus


async def test_thundering_herd_single_disk_read(tmp_db):
    """
    50 concurrent get() calls for the same evicted key must result in
    exactly one disk read; all 50 must return the correct value.
    The per-key lock ensures the first resolver re-warms the cache so
    all subsequent waiters get a cache hit instead of a disk read.
    """
    mem = AgentMemory(max_slots=10, policy=EvictionPolicy.LRU, db_path=tmp_db)
    await mem.initialize()

    await mem.put("target", "precious data")
    # Flush write so data is on disk
    entry = mem._registry.get("target")
    if entry.write_promise:
        await entry.write_promise

    # Manually evict from cache while keeping registry entry (simulates natural eviction
    # after the cache fills and overflows)
    mem._cache.remove("target")
    mem._registry.mark_evicted("target")  # in_memory=False, status=EVICTED

    # Instrument storage.read to count disk hits
    read_count = 0
    _original_read = mem._storage.read

    async def counting_read(agent_id: str):
        nonlocal read_count
        read_count += 1
        return await _original_read(agent_id)

    mem._storage.read = counting_read

    results = await asyncio.gather(*[mem.get("target") for _ in range(50)])

    assert all(r == "precious data" for r in results), "Some results were wrong"
    assert read_count == 1, f"Expected 1 disk read, got {read_count}"

    await mem.close()


async def test_pending_eviction_no_data_loss(memory_fifo):
    """
    A key evicted while PENDING must still be retrievable after its
    write promise completes — no data loss during eviction under load.
    """
    # Put "victim" first — it will be evicted when the cache overflows
    await memory_fifo.put("victim", "victim data")
    victim_entry = memory_fifo._registry.get("victim")
    # Status may be PENDING at this point (background write in flight)

    # Fill cache to capacity (max_slots=5) + 1 to trigger eviction of victim (FIFO)
    for i in range(5):
        await memory_fifo.put(f"filler_{i}", f"filler data {i}")

    victim_entry = memory_fifo._registry.get("victim")
    assert victim_entry is not None
    assert not victim_entry.in_memory, "Victim should have been evicted from cache"

    # Retrieval must succeed regardless of PENDING vs COMMITTED status at eviction time
    result = await memory_fifo.get("victim")
    assert result == "victim data"


async def test_concurrent_puts_no_corruption(tmp_db):
    """
    20 agents writing simultaneously must all be retrievable with
    correct, non-corrupted values after all writes flush.
    """
    mem = AgentMemory(max_slots=5, policy=EvictionPolicy.LRU, db_path=tmp_db)
    await mem.initialize()

    agents = [f"concurrent_{i:02d}" for i in range(20)]
    outputs = {a: f"output_for_{a}_" + ("z" * 100) for a in agents}

    await asyncio.gather(*[mem.put(aid, outputs[aid]) for aid in agents])
    await mem.close()  # flush all pending writes

    # Reopen with cold cache and verify all data is on disk uncorrupted
    mem2 = AgentMemory(max_slots=5, policy=EvictionPolicy.LRU, db_path=tmp_db)
    await mem2.initialize()
    for aid in agents:
        raw = await mem2._storage.read(aid)
        assert raw is not None, f"{aid} missing from disk"
        from agent_cortex.compression import decompress
        assert decompress(raw) == outputs[aid], f"Data corruption for {aid}"
    await mem2.close()


async def test_get_many_parallel_with_mix_of_statuses(tmp_db):
    """
    get_many() with a mix of PENDING, COMMITTED, and EVICTED statuses
    must return all values correctly.
    """
    mem = AgentMemory(max_slots=3, policy=EvictionPolicy.LRU, db_path=tmp_db)
    await mem.initialize()

    # Put 5 agents into a 3-slot cache — agents 0 and 1 get evicted
    agents = [f"mixed_{i}" for i in range(5)]
    outputs = {a: f"data_for_{a}" for a in agents}

    for aid in agents:
        await mem.put(aid, outputs[aid])

    # Flush writes so evicted agents are COMMITTED/EVICTED on disk
    pending = [
        e.write_promise
        for e in mem._registry._entries.values()
        if e.write_promise and not e.write_promise.done()
    ]
    if pending:
        await asyncio.gather(*pending, return_exceptions=True)

    results = await mem.get_many(agents)

    for aid in agents:
        assert results[aid] == outputs[aid], f"Wrong value for {aid}: {results[aid]!r}"

    await mem.close()
