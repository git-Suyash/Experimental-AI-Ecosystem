# How to Implement agent-cortex

A practical guide for wiring `agent-cortex` into your own multi-agent project — from installation to production patterns.

---

## 1. Install

Add `agent-cortex` as a dependency. Since it is a local package (not yet on PyPI), install it directly from the source directory:

```bash
# With uv (recommended)
uv add path/to/agent-cortex

# With pip
pip install path/to/agent-cortex
```

Or, if you copy the `src/agent_cortex/` directory into your project tree, install it in editable mode:

```bash
pip install -e path/to/agent-cortex
```

**Runtime requirements:** Python ≥ 3.11, `aiosqlite` (installed automatically).

---

## 2. The One Object You Need

Everything goes through `AgentMemory`. Create a **single shared instance** and pass it to all agents that need to share state.

```python
from agent_cortex import AgentMemory, EvictionPolicy

memory = AgentMemory(
    max_slots=10,           # How many agent outputs to keep in RAM at once
    policy=EvictionPolicy.LRU,  # or FIFO — see Section 4
    max_payload_mb=50,      # Outputs larger than this go straight to disk
    db_path="./my_pipeline.db",  # SQLite file — created automatically
    compression_level=6,    # zlib 1–9; 6 is the default sweet spot
)
```

Initialize it before your pipeline starts, close it when done:

```python
async def main():
    await memory.initialize()   # creates DB schema, enables WAL mode
    # ... run your pipeline ...
    await memory.close()        # flushes all pending writes, closes DB
```

---

## 3. Core Operations

### Store an agent's output

```python
await memory.put("researcher", "Here are my findings: ...")
```

- Payload is zlib-compressed once, registered in the lookaside registry as `PENDING`, inserted into the in-memory cache, and a background task writes it to SQLite.
- If the cache is full, the oldest (FIFO) or least-recently-used (LRU) entry is evicted to disk first.
- Payloads over `max_payload_mb` skip the cache entirely and go straight to disk (`STREAMING_TO_DISK`).

### Retrieve an agent's output

```python
result = await memory.get("researcher")   # str | None
```

- **Cache hit** → decompresses and returns immediately.
- **Cache miss (evicted)** → reads from disk, optionally re-warms the cache slot.
- **Cache miss (still writing)** → awaits the background write task, then reads disk. No data loss.
- Returns `None` if the `agent_id` was never registered.

### Retrieve multiple outputs in parallel

```python
results = await memory.get_many(["researcher", "analyst", "planner"])
# {"researcher": "...", "analyst": "...", "planner": None}
```

All `get()` calls fire concurrently via `asyncio.gather`. Use this at synchronization points where one agent needs output from several upstream agents.

---

## 4. Choosing an Eviction Policy

| Policy | Use when... |
|---|---|
| `EvictionPolicy.FIFO` | Agents run sequentially and each output is read exactly once downstream. Oldest checkpoint evicted first. Simple and predictable. |
| `EvictionPolicy.LRU` | Agents run in parallel or outputs are re-read multiple times (graph topologies). Least-recently-accessed output evicted first. Prevents hot keys from being flushed. |

Set `max_slots` to roughly the number of agents whose outputs you expect to be "alive" at the same time. When in doubt, start at `10` and lower it if you're memory-constrained.

---

## 5. Google ADK Integration

`agent-cortex` ships two ADK-ready callback factories that wire memory in without changing your agent code.

### `cortex_after_callback` — capture agent output

Wraps `after_agent_callback`. Extracts the final text from the LLM response and calls `memory.put()` automatically.

```python
from agent_cortex.integrations.adk import cortex_after_callback, cortex_before_callback
from agent_cortex import AgentMemory, EvictionPolicy
from google.adk.agents import Agent

memory = AgentMemory(max_slots=15, policy=EvictionPolicy.LRU, db_path="./pipeline.db")

researcher = Agent(
    name="researcher",
    model="gemini-2.0-flash",
    instruction="You are a research agent...",
    after_agent_callback=cortex_after_callback(memory),
    # agent_id defaults to agent.name ("researcher")
)
```

To store under a different key:

```python
after_agent_callback=cortex_after_callback(memory, agent_id="my_custom_key")
```

### `cortex_before_callback` — inject upstream context

Wraps `before_agent_callback`. Fetches the listed upstream outputs and injects them into `callback_context.state` before the agent runs.

```python
analyst = Agent(
    name="analyst",
    model="gemini-2.0-flash",
    instruction="You are an analysis agent. Use the upstream context in your state.",
    before_agent_callback=cortex_before_callback(
        memory,
        depends_on=["researcher"],   # list of agent_ids to fetch
        inject_as="upstream_context" # key in callback_context.state (default)
    ),
    after_agent_callback=cortex_after_callback(memory),
)
```

Inside the analyst's tool or instruction template, `callback_context.state["upstream_context"]` will be:

```python
{
    "researcher": "Here are my findings: ..."
}
```

### Full 3-agent pipeline example

```python
import asyncio
from agent_cortex import AgentMemory, EvictionPolicy
from agent_cortex.integrations.adk import cortex_after_callback, cortex_before_callback
from google.adk.agents import Agent, SequentialAgent

async def build_pipeline():
    memory = AgentMemory(max_slots=10, policy=EvictionPolicy.LRU, db_path="./pipeline.db")
    await memory.initialize()

    researcher = Agent(
        name="researcher",
        model="gemini-2.0-flash",
        instruction="Research the topic and summarize your findings.",
        after_agent_callback=cortex_after_callback(memory),
    )

    analyst = Agent(
        name="analyst",
        model="gemini-2.0-flash",
        instruction="Analyse the research. Context is in state['upstream_context']['researcher'].",
        before_agent_callback=cortex_before_callback(memory, depends_on=["researcher"]),
        after_agent_callback=cortex_after_callback(memory),
    )

    writer = Agent(
        name="writer",
        model="gemini-2.0-flash",
        instruction="Write a report using state['upstream_context']['researcher'] and ['analyst'].",
        before_agent_callback=cortex_before_callback(
            memory, depends_on=["researcher", "analyst"]
        ),
        after_agent_callback=cortex_after_callback(memory),
    )

    pipeline = SequentialAgent(
        name="pipeline",
        sub_agents=[researcher, analyst, writer],
    )

    return pipeline, memory
```

---

## 6. Without ADK (Framework-Agnostic)

If you are not using Google ADK, call `put` and `get` directly at the boundaries of each agent's execution:

```python
import asyncio
from agent_cortex import AgentMemory, EvictionPolicy

memory = AgentMemory(max_slots=5, policy=EvictionPolicy.FIFO, db_path="./store.db")

async def run_agent_1(memory):
    # ... your agent logic here ...
    output = "Agent 1 result"
    await memory.put("agent_1", output)

async def run_agent_2(memory):
    context = await memory.get("agent_1")
    # use context in your prompt or logic
    output = f"Agent 2 processed: {context}"
    await memory.put("agent_2", output)

async def main():
    await memory.initialize()
    await run_agent_1(memory)
    await run_agent_2(memory)
    await memory.close()

asyncio.run(main())
```

For parallel agents:

```python
async def main():
    await memory.initialize()

    # Run three agents in parallel
    await asyncio.gather(
        run_agent_finance(memory),
        run_agent_legal(memory),
        run_agent_ops(memory),
    )

    # Aggregator collects all three safely — handles PENDING state automatically
    results = await memory.get_many(["finance", "legal", "ops"])

    await memory.close()
```

---

## 7. Observability

### Live event stream

Subscribe before your pipeline starts to receive lifecycle events as they happen:

```python
import asyncio
from agent_cortex import AgentMemory

memory = AgentMemory(max_slots=5, db_path="./store.db")

async def log_events(queue):
    while True:
        event = await queue.get()
        print(
            f"[{event.event_type:10}] {event.agent_id:20} "
            f"status={event.status.value:16} in_memory={event.in_memory}"
        )

async def main():
    await memory.initialize()

    queue = memory.subscribe()
    asyncio.create_task(log_events(queue))

    # ... run pipeline ...

    await memory.close()
```

Event types and when they fire:

| `event_type` | When |
|---|---|
| `registered` | Immediately after `put()` — entry is live in cache or queued to disk |
| `committed` | Background write to SQLite completed successfully |
| `evicted` | Entry removed from in-memory cache (still safe on disk) |
| `resolved` | Cache miss resolved — data read from disk and optionally re-warmed |

### Point-in-time snapshot

```python
snapshot = await memory.registry_snapshot()

# High-level counts
print(snapshot["summary"])
# {'total_agents': 8, 'in_memory': 3, 'on_disk_only': 5, 'pending': 1, 'committed': 7, ...}

# Per-agent detail
for agent_id, meta in snapshot["agents"].items():
    print(f"{agent_id:20} {meta['status']:16} in_memory={meta['in_memory']}")
```

### Per-agent status check

```python
from agent_cortex import StorageStatus

s = await memory.status("researcher")
# StorageStatus.PENDING | COMMITTED | EVICTED | STREAMING_TO_DISK | None
```

---

## 8. Writing Tests

Use `tmp_path` (pytest built-in) for isolated per-test databases. Never share a database file across tests.

```python
import pytest
from agent_cortex import AgentMemory, EvictionPolicy

@pytest.fixture
def tmp_db(tmp_path):
    return str(tmp_path / "test.db")

# pytest-asyncio with asyncio_mode = "auto" in pyproject.toml handles async automatically

async def test_put_and_get(tmp_db):
    mem = AgentMemory(max_slots=5, policy=EvictionPolicy.FIFO, db_path=tmp_db)
    await mem.initialize()

    await mem.put("agent_a", "some output")
    result = await mem.get("agent_a")
    assert result == "some output"

    await mem.close()
```

Add to your `pyproject.toml`:

```toml
[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]
```

---

## 9. Tuning for Production

| Concern | Recommendation |
|---|---|
| **Memory pressure** | Lower `max_slots`. Evicted data is safe on disk; reads are slightly slower. |
| **Write throughput** | Increase `compression_level` to 1 for faster puts at the cost of larger DB size. |
| **Large LLM outputs** | Set `max_payload_mb` to your P99 payload size. Oversized payloads route to disk automatically. |
| **Parallel graph pipelines** | Use `EvictionPolicy.LRU` and set `max_slots` ≥ the width of your widest parallel fan-out. |
| **Long-running pipelines** | Call `await memory.registry_snapshot()` periodically and alert if `pending` count is nonzero after expected flush time. |
| **Multiple pipeline runs** | Use a unique `db_path` per run (e.g. `f"./runs/{run_id}.db"`) to keep state isolated. |

---

## 10. What Not To Do

```python
# DON'T — create a new AgentMemory per agent
researcher_mem = AgentMemory(...)
analyst_mem = AgentMemory(...)   # these can't see each other's data

# DO — one shared instance across all agents
memory = AgentMemory(...)
```

```python
# DON'T — forget to initialize before use
memory = AgentMemory(...)
await memory.put("a", "b")   # will fail — DB schema doesn't exist yet

# DO
await memory.initialize()
await memory.put("a", "b")
```

```python
# DON'T — forget to close (pending writes may be lost)
await memory.put("a", "b")
# process exits here — background write may not have completed

# DO
await memory.put("a", "b")
await memory.close()   # blocks until all writes flush
```

```python
# DON'T — commit .db files to git
# Add *.db to .gitignore — these are runtime artifacts, not source

# DON'T — share a db_path across parallel test cases
# Each test must get its own isolated path (use tmp_path fixture)
```
