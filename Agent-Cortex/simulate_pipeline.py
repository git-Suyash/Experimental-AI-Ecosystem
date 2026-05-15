import asyncio
from agent_cortex import AgentMemory, EvictionPolicy

async def simulate():
    memory = AgentMemory(
        max_slots=5,
        policy=EvictionPolicy.LRU,
        max_payload_mb=50,
        db_path="./sim_pipeline.db"
    )

    await memory.initialize()

    agents = [f"agent_{i:02d}" for i in range(10)]
    outputs = {a: f"Output from {a}: " + ("x" * 500) for a in agents}

    for i, agent_id in enumerate(agents):
        await memory.put(agent_id, outputs[agent_id])
        snapshot = await memory.registry_snapshot()
        print(f"After {agent_id}: {snapshot}")

    for agent_id in agents:
        result = await memory.get(agent_id)
        assert result == outputs[agent_id], f"Data mismatch for {agent_id}"
        print(f"OK {agent_id} verified")

    await memory.close()
    print("\nSimulation complete. All outputs verified.")

asyncio.run(simulate())
