import pytest
from unittest.mock import MagicMock, AsyncMock
from agent_cortex.integrations.adk import cortex_after_callback, cortex_before_callback


# ── Mock ADK objects ──────────────────────────────────────────────────────────

class MockPart:
    def __init__(self, text):
        self.text = text


class MockContent:
    def __init__(self, texts):
        self.parts = [MockPart(t) for t in texts]


class MockLlmResponse:
    def __init__(self, texts):
        self.content = MockContent(texts)


class MockCallbackContext:
    def __init__(self, name):
        self.agent_name = name
        self.state = {}


# ── After-callback tests ──────────────────────────────────────────────────────

async def test_after_callback_stores_text_under_explicit_agent_id(tmp_db):
    from agent_cortex import AgentMemory, EvictionPolicy
    mem = AgentMemory(max_slots=5, db_path=tmp_db)
    await mem.initialize()

    callback = cortex_after_callback(mem, agent_id="my_agent")
    ctx = MockCallbackContext("ignored_name")
    response = MockLlmResponse(["Hello, world!"])

    returned = await callback(ctx, response)

    assert returned is response
    result = await mem.get("my_agent")
    assert result == "Hello, world!"

    await mem.close()


async def test_after_callback_uses_agent_name_when_id_is_none(tmp_db):
    from agent_cortex import AgentMemory
    mem = AgentMemory(max_slots=5, db_path=tmp_db)
    await mem.initialize()

    callback = cortex_after_callback(mem)  # no explicit agent_id
    ctx = MockCallbackContext("researcher")
    response = MockLlmResponse(["Research findings here."])

    await callback(ctx, response)

    result = await mem.get("researcher")
    assert result == "Research findings here."

    await mem.close()


async def test_after_callback_handles_none_content_gracefully(tmp_db):
    from agent_cortex import AgentMemory
    mem = AgentMemory(max_slots=5, db_path=tmp_db)
    await mem.initialize()

    callback = cortex_after_callback(mem, agent_id="empty_agent")
    ctx = MockCallbackContext("empty_agent")

    # Response with no content parts
    class EmptyResponse:
        content = None

    returned = await callback(ctx, EmptyResponse())
    assert returned is not None  # returns the response object

    # Nothing stored for empty_agent
    result = await mem.get("empty_agent")
    assert result is None

    await mem.close()


async def test_after_callback_joins_multipart_response(tmp_db):
    from agent_cortex import AgentMemory
    mem = AgentMemory(max_slots=5, db_path=tmp_db)
    await mem.initialize()

    callback = cortex_after_callback(mem, agent_id="multipart")
    ctx = MockCallbackContext("multipart")
    response = MockLlmResponse(["Part one.", "Part two.", "Part three."])

    await callback(ctx, response)

    result = await mem.get("multipart")
    assert result == "Part one.\nPart two.\nPart three."

    await mem.close()


# ── Before-callback tests ─────────────────────────────────────────────────────

async def test_before_callback_fetches_and_injects_upstream(tmp_db):
    from agent_cortex import AgentMemory
    mem = AgentMemory(max_slots=5, db_path=tmp_db)
    await mem.initialize()

    await mem.put("upstream_1", "output from upstream 1")
    await mem.put("upstream_2", "output from upstream 2")

    callback = cortex_before_callback(mem, depends_on=["upstream_1", "upstream_2"])
    ctx = MockCallbackContext("consumer")

    result = await callback(ctx)
    assert result is None  # None = proceed normally

    injected = ctx.state["upstream_context"]
    assert injected["upstream_1"] == "output from upstream 1"
    assert injected["upstream_2"] == "output from upstream 2"

    await mem.close()


async def test_before_callback_custom_inject_as_key(tmp_db):
    from agent_cortex import AgentMemory
    mem = AgentMemory(max_slots=5, db_path=tmp_db)
    await mem.initialize()

    await mem.put("agent_a", "result of a")

    callback = cortex_before_callback(mem, depends_on=["agent_a"], inject_as="prior_results")
    ctx = MockCallbackContext("next_agent")
    await callback(ctx)

    assert "prior_results" in ctx.state
    assert ctx.state["prior_results"]["agent_a"] == "result of a"

    await mem.close()


async def test_before_callback_handles_partial_misses(tmp_db):
    """
    Some agents in depends_on list may not have run yet — their values
    should be None in the injected dict without crashing.
    """
    from agent_cortex import AgentMemory
    mem = AgentMemory(max_slots=5, db_path=tmp_db)
    await mem.initialize()

    await mem.put("exists", "I exist")
    # "missing" was never put

    callback = cortex_before_callback(mem, depends_on=["exists", "missing"])
    ctx = MockCallbackContext("consumer")
    await callback(ctx)

    injected = ctx.state["upstream_context"]
    assert injected["exists"] == "I exist"
    assert injected["missing"] is None

    await mem.close()


async def test_end_to_end_three_agent_pipeline(tmp_db):
    """
    Manual end-to-end: 3 sequential mock agents demonstrate
    put → store → get → inject into next agent context.
    """
    from agent_cortex import AgentMemory
    mem = AgentMemory(max_slots=5, db_path=tmp_db)
    await mem.initialize()

    # Agent 1: researcher produces output
    after_1 = cortex_after_callback(mem, agent_id="researcher")
    ctx_1 = MockCallbackContext("researcher")
    resp_1 = MockLlmResponse(["Research: climate data collected."])
    await after_1(ctx_1, resp_1)

    # Agent 2: analyst reads researcher output, produces its own
    before_2 = cortex_before_callback(mem, depends_on=["researcher"])
    after_2 = cortex_after_callback(mem, agent_id="analyst")
    ctx_2 = MockCallbackContext("analyst")
    await before_2(ctx_2)
    assert ctx_2.state["upstream_context"]["researcher"] == "Research: climate data collected."

    resp_2 = MockLlmResponse(["Analysis: temperatures rising 0.5°C/decade."])
    await after_2(ctx_2, resp_2)

    # Agent 3: writer reads both upstream outputs
    before_3 = cortex_before_callback(mem, depends_on=["researcher", "analyst"])
    ctx_3 = MockCallbackContext("writer")
    await before_3(ctx_3)
    ctx = ctx_3.state["upstream_context"]
    assert ctx["researcher"] == "Research: climate data collected."
    assert ctx["analyst"] == "Analysis: temperatures rising 0.5°C/decade."

    await mem.close()
