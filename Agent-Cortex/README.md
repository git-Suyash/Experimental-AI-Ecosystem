# agent-cortex

> A high-performance checkpoint and memory-bridge engine for multi-agent workflows.

`agent-cortex` solves the memory management trilemma in production multi-agent systems by combining a bounded, compressed in-memory cache with an asynchronous SQLite persistence layer — all orchestrated through a lookaside state registry that eliminates I/O penalties on cache misses.

Built specifically for complex, high-load agent pipelines using **Google ADK**, but designed to work with any Python-based agent framework.

---

## The Problem

As multi-agent systems scale beyond simple sequential pipelines into parallel graph topologies, ADK's native primitives (`output_key`, `InMemoryMemoryService`) begin to show structural cracks:

| Problem | Symptom at Scale |
|---|---|
| **OOM Crashes** | Large LLM outputs accumulate across 10+ agents, exhausting heap memory |
| **Data Loss** | No durability guarantee — agent failure mid-pipeline loses all intermediate state |
| **Race Conditions** | Parallel graph agents simultaneously request the same PENDING checkpoint, causing stampedes |
| **Zero Observability** | No way to inspect what state each agent handed off at any given point |

`agent-cortex` addresses all four through a single, consistent interface: `AgentMemory`.

---

## Architecture

```
                    ┌─────────────────────────────────────┐
                    │       Lookaside State Registry       │
                    │  agent_id → { status, in_memory,    │
                    │               write_promise, meta }  │
                    └──────────────────┬──────────────────┘
                                       │
                                       ▼
[Agent N] ──► AgentMemory.put() ───────┬────► [In-Memory Cache (FIFO/LRU)]──► [Agent N+1]
                    │                  │              │
                    │    (Async BG)    │         (Cache Miss)
                    ▼                  │              ▼
            [SQLite WAL DB] ◄──────────┘    [Await Write Promise / Disk Read]
```

### Data Lifecycle

1. **Ingress** — Agent N calls `put(agent_id, output)`.
2. **Guard** — Payload size is checked against `max_payload_mb`. Oversized payloads bypass memory entirely and stream directly to disk (`STREAMING_TO_DISK`).
3. **Compress** — Payload is `zlib`-compressed into bytes, shrinking object footprint significantly.
4. **Register** — Lookaside registry logs the entry as `PENDING` and spawns a background `aiosqlite` write task.
5. **Cache** — Compressed entry is inserted. If at capacity, eviction runs (`FIFO` or `LRU`).
6. **Egress** — Agent N+1 calls `get(agent_id)`:
   - **Cache Hit** → decompress and return immediately.
   - **Cache Miss + PENDING** → await the existing write promise, then read from disk. Zero duplicate I/O.
   - **Cache Miss + COMMITTED** → read directly from disk.
   - **PENDING + Evicted** → per-key mutex ensures only one agent fetches from disk; the rest receive a cache-warmed hit.

---

## Core Concepts

### Eviction Policies

| Policy | When to Use |
|---|---|
| `fifo` | Linear, sequential agent pipelines. Oldest checkpoint always evicted first. |
| `lru` | Graph-based or parallel topologies. Least recently accessed checkpoint evicted first. Prevents starvation of hot keys. |

Policy is set once at initialization. It applies globally to the cache layer.

### Lookaside Registry States

```
INGRESS
  └─► PENDING        (in memory + async write in progress)
        ├─► COMMITTED       (write succeeded, may or may not be in memory)
        ├─► EVICTED         (write succeeded, removed from memory cache)
        └─► STREAMING_TO_DISK  (payload too large, bypassed memory entirely)
```

The registry is the source of truth for every agent output in the pipeline. It is queryable at any point for debugging and observability.

### Stampede Protection

When multiple parallel agents simultaneously request a PENDING or evicted key, a per-key `asyncio.Lock` ensures:
- Only **one** agent triggers the disk read or awaits the write promise.
- All remaining agents receive the result from the repopulated cache slot.
- No duplicate I/O. No race conditions.

---

## Installation

This project uses [`uv`](https://github.com/astral-sh/uv) for package management.

```bash
# Clone the repository
git clone https://github.com/your-org/agent-cortex.git
cd agent-cortex

# Create virtual environment and install dependencies
uv sync

# Install in development mode
uv pip install -e .
```

### Dependencies

```toml
# pyproject.toml — runtime
[project.dependencies]
aiosqlite = ">=0.20.0"

# dev
[project.optional-dependencies.dev]
pytest = ">=8.0.0"
pytest-asyncio = ">=0.23.0"
ruff = ">=0.4.0"
```

Only one external runtime dependency: `aiosqlite`. Everything else (`zlib`, `asyncio`, `collections`) is Python stdlib.

### Public Exports

```python
from agent_cortex import (
    AgentMemory,        # Primary interface
    EvictionPolicy,     # FIFO | LRU
    StorageStatus,      # PENDING | COMMITTED | EVICTED | STREAMING_TO_DISK
    EventBus,           # Async pub/sub engine
    MemoryEvent,        # Lifecycle event dataclass
    AgentCortexError,   # Base exception
    PayloadTooLargeError,
    AgentNotFoundError,
    StorageError,
)
from agent_cortex.integrations.adk import cortex_after_callback, cortex_before_callback
```

---

## Quick Start

```python
import asyncio
from agent_cortex import AgentMemory, EvictionPolicy

async def main():
    # Initialize for a sequential pipeline
    memory = AgentMemory(
        max_slots=10,
        policy=EvictionPolicy.FIFO,
        max_payload_mb=50,
        db_path="./cortex_store.db"
    )

    await memory.initialize()  # Sets up SQLite schema

    # Agent 1 stores its output
    await memory.put("agent_researcher", "Here is the market analysis: ...")

    # Agent 2 retrieves it
    output = await memory.get("agent_researcher")
    print(output)

    await memory.close()

asyncio.run(main())
```

---

## Google ADK Integration

`agent-cortex` is designed to slot into ADK pipelines without restructuring your agents. The two primary integration patterns are:

### Pattern 1: Callback-Based (Recommended)

Use ADK's `after_agent_callback` and `before_agent_callback` hooks to wire `agent-cortex` around your agents transparently.

```python
from google.adk.agents import Agent
from google.adk.agents.callback_context import CallbackContext
from google.adk.models import LlmResponse
from agent_cortex import AgentMemory, EvictionPolicy
from agent_cortex.integrations.adk import cortex_after_callback, cortex_before_callback

# Shared memory instance across your pipeline
memory = AgentMemory(
    max_slots=15,
    policy=EvictionPolicy.LRU,   # LRU for graph topologies
    max_payload_mb=50,
    db_path="./pipeline_store.db"
)

researcher = Agent(
    name="researcher",
    model="gemini-2.0-flash",
    instruction="You are a research agent...",
    after_agent_callback=cortex_after_callback(memory)
)

analyst = Agent(
    name="analyst",
    model="gemini-2.0-flash",
    instruction="You are an analysis agent...",
    before_agent_callback=cortex_before_callback(memory, depends_on=["researcher"])
)
```

The `cortex_after_callback` automatically captures the agent's final response text and calls `memory.put()`. The `cortex_before_callback` fetches the required upstream outputs and injects them into the callback context before the agent runs.

### Pattern 2: Explicit In-Agent (Full Control)

For agents where you need fine-grained control over what gets stored:

```python
from google.adk.agents import Agent
from google.adk.agents.callback_context import CallbackContext
from agent_cortex import AgentMemory

memory = AgentMemory(max_slots=15, policy=EvictionPolicy.LRU, db_path="./store.db")

async def researcher_after_callback(
    callback_context: CallbackContext,
    llm_response: LlmResponse
) -> LlmResponse:
    # Store only the structured part of the response
    if llm_response.content:
        await memory.put(
            agent_id="researcher",
            output_data=llm_response.content.parts[0].text
        )
    return llm_response

researcher = Agent(
    name="researcher",
    model="gemini-2.0-flash",
    instruction="...",
    after_agent_callback=researcher_after_callback
)
```

### Pattern 3: Graph Topology (Parallel Agents)

For parallel sub-agents converging at a synchronization step, `agent-cortex` handles the stampede automatically:

```python
# All three run in parallel — they each store independently
await asyncio.gather(
    memory.put("sub_agent_finance", finance_output),
    memory.put("sub_agent_legal", legal_output),
    memory.put("sub_agent_ops", ops_output),
)

# Aggregator agent collects all three — safe even if some are still PENDING
results = await memory.get_many(["sub_agent_finance", "sub_agent_legal", "sub_agent_ops"])
```

---

## Configuration Reference

```python
AgentMemory(
    max_slots: int = 10,
    # Maximum number of agent outputs held in the fast in-memory cache.
    # When exceeded, eviction runs based on the selected policy.
    # Tune based on average payload size and available RAM.

    policy: EvictionPolicy = EvictionPolicy.FIFO,
    # EvictionPolicy.FIFO  — for sequential linear pipelines
    # EvictionPolicy.LRU   — for graph-based or parallel topologies

    max_payload_mb: int = 50,
    # Hard ceiling on in-memory payload size.
    # Payloads exceeding this bypass the cache entirely and stream
    # directly to disk (STREAMING_TO_DISK state).

    db_path: str = "./agent_cortex.db",
    # Path to the SQLite database file.
    # Uses WAL (Write-Ahead Logging) mode for concurrent read safety.

    compression_level: int = 6,
    # zlib compression level: 1 (fastest) to 9 (best compression).
    # Level 6 is the standard tradeoff. Tune based on CPU budget.
)
```

---

## API Reference

### `AgentMemory`

#### `await memory.initialize()`
Creates the SQLite schema and enables WAL mode. Must be called before any `put` or `get` operations.

#### `await memory.put(agent_id: str, output_data: str) -> None`
Stores an agent's output. The payload is compressed once with zlib, then registered, cached, and persisted via a background async write task. If `output_data` exceeds `max_payload_mb`, it bypasses the in-memory cache entirely and streams directly to disk (`STREAMING_TO_DISK` state) without raising an exception.

#### `await memory.get(agent_id: str) -> Optional[str]`
Retrieves an agent's stored output.
- Returns `None` if the `agent_id` was never registered.
- Handles cache hit, cache miss + PENDING, and cache miss + COMMITTED transparently.

#### `await memory.get_many(agent_ids: List[str]) -> Dict[str, Optional[str]]`
Concurrent bulk retrieval. Fires all `get()` calls in parallel via `asyncio.gather`. Essential for synchronization steps in graph topologies.

#### `await memory.status(agent_id: str) -> Optional[StorageStatus]`
Returns the current registry state for an agent ID. Useful for debugging and pipeline orchestration logic.

#### `memory.subscribe() -> asyncio.Queue`
Subscribe to memory lifecycle events. Returns an `asyncio.Queue` that receives `MemoryEvent` objects as they occur. Events are emitted non-blocking (`put_nowait`) so they never stall `put` or `get` operations.

```python
queue = memory.subscribe()
await memory.put("agent_1", "output")
event = await queue.get()
# event.event_type  → "registered" | "committed" | "evicted" | "resolved"
# event.agent_id    → "agent_1"
# event.status      → StorageStatus.PENDING
# event.in_memory   → True
# event.size_bytes  → int
# event.timestamp   → float (Unix time)
```

#### `await memory.registry_snapshot() -> Dict`
Returns a snapshot of the lookaside registry in two sections:

```python
{
    "summary": {
        "total_agents": int,
        "in_memory": int,
        "on_disk_only": int,
        "pending": int,
        "committed": int,
        "streaming_to_disk": int,
    },
    "agents": {
        "agent_id": {
            "status": str,        # "pending" | "committed" | "evicted" | "streaming_to_disk"
            "in_memory": bool,
            "size_bytes": int,
            "registered_at": float,
            "updated_at": float,
        },
        ...
    }
}
```

#### `await memory.close()`
Flushes pending writes and closes the SQLite connection cleanly.

---

## Observability

### Event Streaming

Subscribe to real-time memory lifecycle events to monitor your pipeline as it runs:

```python
import asyncio
from agent_cortex import AgentMemory, EvictionPolicy

async def monitor(memory):
    queue = memory.subscribe()
    while True:
        event = await queue.get()
        print(f"[{event.event_type:10}] {event.agent_id} → {event.status.value} | in_memory={event.in_memory}")

async def main():
    memory = AgentMemory(max_slots=5, policy=EvictionPolicy.LRU, db_path="./store.db")
    await memory.initialize()

    asyncio.create_task(monitor(memory))

    await memory.put("researcher", "market analysis output...")
    await memory.put("analyst", "trends: ...")
    await memory.close()
```

Example output:
```
[registered ] researcher → pending    | in_memory=True
[committed  ] researcher → committed  | in_memory=True
[registered ] analyst    → pending    | in_memory=True
[committed  ] analyst    → committed  | in_memory=True
```

Event types: `registered`, `committed`, `evicted`, `resolved`.

### Registry Snapshot

For a point-in-time overview of the entire pipeline state:

```python
snapshot = await memory.registry_snapshot()

print(snapshot["summary"])
# {'total_agents': 5, 'in_memory': 3, 'on_disk_only': 2, 'pending': 0, 'committed': 5, 'streaming_to_disk': 0}

for agent_id, meta in snapshot["agents"].items():
    print(f"{agent_id}: {meta['status']} | in_memory={meta['in_memory']}")
```

Example output:
```
agent_researcher:   committed  | in_memory=True
agent_planner:      committed  | in_memory=True
agent_sub_finance:  committed  | in_memory=True
agent_sub_legal:    committed  | in_memory=False   ← evicted to disk
agent_sub_ops:      committed  | in_memory=False   ← evicted to disk
```

This gives you a live map of exactly what state your pipeline is in at any moment — invaluable for debugging stalls, failures, and race conditions in complex graph workflows.

---

## Project Structure

```
agent-cortex/
├── pyproject.toml
├── README.md
├── simulate_pipeline.py              # Standalone 10-agent pipeline demo
├── src/
│   └── agent_cortex/
│       ├── __init__.py               # Public API surface
│       ├── memory.py                 # AgentMemory — primary interface
│       ├── cache.py                  # Bounded cache + eviction logic
│       ├── registry.py               # Lookaside state registry
│       ├── storage.py                # SQLite WAL async backend
│       ├── compression.py            # zlib compression utilities
│       ├── locks.py                  # Per-key mutex management
│       ├── events.py                 # EventBus + MemoryEvent pub/sub
│       ├── exceptions.py             # PayloadTooLargeError, etc.
│       └── integrations/
│           ├── __init__.py
│           └── adk.py                # Google ADK callback helpers
└── tests/
    ├── conftest.py
    ├── test_cache.py
    ├── test_storage.py
    ├── test_memory.py                # Core integration tests
    ├── test_concurrency.py           # Stampede, race condition tests
    ├── test_observability.py         # EventBus and registry snapshot tests
    └── test_adk_integration.py       # ADK callback end-to-end tests
```

---

## Development Setup

```bash
# Install with dev dependencies
uv sync --extra dev

# Run tests
uv run pytest

# Run tests with coverage
uv run pytest --cov=agent_cortex

# Lint
uv run ruff check src/

# Format
uv run ruff format src/
```

---

## Roadmap

- [x] **Phase 1** — Core storage and durability (SQLite WAL, zlib compression, payload guardrails)
- [x] **Phase 2** — Concurrency safety (per-key mutex, thundering herd prevention, race condition hardening)
- [x] **Phase 3** — Observability (EventBus pub/sub, structured `MemoryEvent`, `registry_snapshot()` with summary)
- [x] **Phase 4** — Google ADK integration layer (`cortex_after_callback`, `cortex_before_callback`, `get_many`)
- [ ] **Phase 5** — Redis-backed distributed registry for multi-node deployments

---

## License

MIT