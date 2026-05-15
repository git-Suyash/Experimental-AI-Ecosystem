import pytest
from agent_cortex import AgentMemory, EvictionPolicy
from agent_cortex.storage import StorageBackend


@pytest.fixture
def tmp_db(tmp_path):
    return str(tmp_path / "test_cortex.db")


@pytest.fixture
async def storage(tmp_db):
    backend = StorageBackend(tmp_db)
    await backend.initialize()
    yield backend
    await backend.close()


@pytest.fixture
async def memory_fifo(tmp_db):
    mem = AgentMemory(max_slots=5, policy=EvictionPolicy.FIFO, db_path=tmp_db)
    await mem.initialize()
    yield mem
    await mem.close()


@pytest.fixture
async def memory_lru(tmp_db):
    mem = AgentMemory(max_slots=5, policy=EvictionPolicy.LRU, db_path=tmp_db)
    await mem.initialize()
    yield mem
    await mem.close()
