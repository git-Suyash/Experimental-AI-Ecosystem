import asyncio
import pytest
from agent_cortex import AgentMemory, EvictionPolicy
from agent_cortex.registry import StorageStatus


async def _drain_events(queue: asyncio.Queue, event_type: str, timeout: float = 1.0) -> list:
    """Collect all events of a given type from the queue within `timeout` seconds."""
    collected = []
    deadline = asyncio.get_event_loop().time() + timeout
    while True:
        remaining = deadline - asyncio.get_event_loop().time()
        if remaining <= 0:
            break
        try:
            event = await asyncio.wait_for(queue.get(), timeout=remaining)
            if event.event_type == event_type:
                collected.append(event)
        except asyncio.TimeoutError:
            break
    return collected


async def _next_event(queue: asyncio.Queue, event_type: str, timeout: float = 2.0):
    """Wait for the next event of a specific type."""
    deadline = asyncio.get_event_loop().time() + timeout
    while True:
        remaining = deadline - asyncio.get_event_loop().time()
        if remaining <= 0:
            raise AssertionError(f"Timed out waiting for '{event_type}' event")
        try:
            event = await asyncio.wait_for(queue.get(), timeout=remaining)
            if event.event_type == event_type:
                return event
        except asyncio.TimeoutError:
            raise AssertionError(f"Timed out waiting for '{event_type}' event")


async def test_subscribe_receives_registered_event(tmp_db):
    mem = AgentMemory(max_slots=5, db_path=tmp_db)
    await mem.initialize()

    queue = mem.subscribe()
    await mem.put("agent_obs", "hello observability")

    event = await _next_event(queue, "registered")
    assert event.agent_id == "agent_obs"
    assert event.status == StorageStatus.PENDING
    assert event.in_memory is True

    await mem.close()


async def test_subscribe_receives_committed_event(tmp_db):
    mem = AgentMemory(max_slots=5, db_path=tmp_db)
    await mem.initialize()

    queue = mem.subscribe()
    await mem.put("agent_commit", "commit test")

    event = await _next_event(queue, "committed")
    assert event.agent_id == "agent_commit"
    assert event.status == StorageStatus.COMMITTED

    await mem.close()


async def test_subscribe_receives_evicted_event(tmp_db):
    mem = AgentMemory(max_slots=2, policy=EvictionPolicy.FIFO, db_path=tmp_db)
    await mem.initialize()

    queue = mem.subscribe()

    await mem.put("first", "data 1")
    await mem.put("second", "data 2")
    # Third put overflows cache — "first" should be evicted (FIFO)
    await mem.put("third", "data 3")

    event = await _next_event(queue, "evicted")
    assert event.agent_id == "first"
    assert event.in_memory is False

    await mem.close()


async def test_registry_snapshot_summary_counts_10_agent_pipeline(tmp_db):
    mem = AgentMemory(max_slots=5, policy=EvictionPolicy.FIFO, db_path=tmp_db)
    await mem.initialize()

    agents = [f"pipe_{i:02d}" for i in range(10)]
    for aid in agents:
        await mem.put(aid, f"output of {aid}")

    # Flush all writes
    await mem.close()

    # Reopen and verify snapshot summary is accurate
    mem2 = AgentMemory(max_slots=5, policy=EvictionPolicy.FIFO, db_path=tmp_db)
    await mem2.initialize()

    # Re-register all agents so registry is populated (simulates a real run)
    for aid in agents:
        await mem2.put(aid, f"output of {aid}")

    # Flush and check summary
    await mem2.close()

    mem3 = AgentMemory(max_slots=5, policy=EvictionPolicy.FIFO, db_path=tmp_db)
    await mem3.initialize()
    for aid in agents:
        await mem3.put(aid, f"output of {aid}")

    snapshot = await mem3.registry_snapshot()
    summary = snapshot["summary"]

    assert summary["total_agents"] == 10
    assert summary["in_memory"] + summary["on_disk_only"] == 10
    assert "pending" in summary
    assert "committed" in summary

    await mem3.close()


async def test_snapshot_summary_counts_are_accurate(tmp_db):
    """Snapshot summary totals must equal total_agents at all times."""
    mem = AgentMemory(max_slots=3, policy=EvictionPolicy.FIFO, db_path=tmp_db)
    await mem.initialize()

    for i in range(6):
        await mem.put(f"a{i}", f"data {i}")
        snap = await mem.registry_snapshot()
        s = snap["summary"]
        assert s["in_memory"] + s["on_disk_only"] == s["total_agents"], (
            f"Count mismatch after putting a{i}: {s}"
        )

    await mem.close()


async def test_status_returns_correct_value_at_each_stage(tmp_db):
    mem = AgentMemory(max_slots=2, policy=EvictionPolicy.FIFO, db_path=tmp_db)
    await mem.initialize()

    # Immediately after put — PENDING
    await mem.put("target", "some data")
    s = await mem.status("target")
    assert s == StorageStatus.PENDING

    # After write flush — COMMITTED
    entry = mem._registry.get("target")
    if entry.write_promise:
        await entry.write_promise
    s = await mem.status("target")
    assert s == StorageStatus.COMMITTED

    # After eviction (add 2 more to overflow a 2-slot cache)
    await mem.put("fill1", "x")
    await mem.put("fill2", "y")
    s = await mem.status("target")
    assert s == StorageStatus.EVICTED

    # Unknown agent
    s = await mem.status("ghost")
    assert s is None

    await mem.close()
