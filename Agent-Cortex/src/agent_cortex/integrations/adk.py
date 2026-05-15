"""
Google ADK integration helpers for agent-cortex.

These helpers produce ADK-compatible callback functions that wire
AgentMemory into agent lifecycles transparently.

Usage:
    from agent_cortex.integrations.adk import cortex_after_callback, cortex_before_callback

    agent = Agent(
        name="researcher",
        after_agent_callback=cortex_after_callback(memory),
    )
"""

from __future__ import annotations
import logging
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from agent_cortex import AgentMemory

logger = logging.getLogger(__name__)


def cortex_after_callback(memory: "AgentMemory", agent_id: str | None = None):
    """
    Returns an ADK after_agent_callback that stores the agent's final
    text response in AgentMemory.

    Args:
        memory:   The shared AgentMemory instance.
        agent_id: Key to store the output under. Defaults to the agent's name
                  from callback_context if not provided.
    """
    async def _after_callback(callback_context, llm_response):
        _agent_id = agent_id or callback_context.agent_name

        try:
            text = _extract_text(llm_response)
            if text:
                await memory.put(_agent_id, text)
            else:
                logger.warning(
                    "cortex_after_callback: no text content found in response "
                    "for agent '%s'. Nothing stored.", _agent_id
                )
        except Exception as exc:
            logger.error(
                "cortex_after_callback: failed to store output for agent '%s': %s",
                _agent_id, exc
            )

        return llm_response

    return _after_callback


def cortex_before_callback(
    memory: "AgentMemory",
    depends_on: list[str],
    inject_as: str = "upstream_context",
):
    """
    Returns an ADK before_agent_callback that fetches upstream agent
    outputs and injects them into the callback context state.

    Args:
        memory:     The shared AgentMemory instance.
        depends_on: List of agent_ids whose outputs this agent needs.
        inject_as:  The key under which the fetched context is stored
                    in callback_context.state. Defaults to "upstream_context".
    """
    async def _before_callback(callback_context):
        try:
            results = await memory.get_many(depends_on)
            callback_context.state[inject_as] = results
        except Exception as exc:
            logger.error(
                "cortex_before_callback: failed to fetch upstream context: %s", exc
            )

        return None  # None means proceed normally

    return _before_callback


def _extract_text(llm_response) -> str | None:
    """
    Extract text content from an ADK LlmResponse.
    Handles both single-part and multi-part responses.
    Returns None if no text is found.
    """
    try:
        if llm_response.content and llm_response.content.parts:
            return "\n".join(
                part.text
                for part in llm_response.content.parts
                if hasattr(part, "text") and part.text
            )
    except (AttributeError, TypeError):
        pass
    return None
