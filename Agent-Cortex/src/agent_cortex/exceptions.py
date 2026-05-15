class AgentCortexError(Exception):
    """Base exception for all agent-cortex errors."""

class PayloadTooLargeError(AgentCortexError):
    """Raised when payload exceeds max_payload_mb and is routed to disk stream."""
    def __init__(self, agent_id: str, size_bytes: int, limit_bytes: int):
        self.agent_id = agent_id
        self.size_bytes = size_bytes
        self.limit_bytes = limit_bytes
        super().__init__(
            f"Payload for '{agent_id}' is {size_bytes / 1024 / 1024:.2f}MB, "
            f"exceeds limit of {limit_bytes / 1024 / 1024:.2f}MB. Routed to disk stream."
        )

class AgentNotFoundError(AgentCortexError):
    """Raised when get() is called for an agent_id that was never registered."""
    def __init__(self, agent_id: str):
        self.agent_id = agent_id
        super().__init__(f"No memory registered for agent '{agent_id}'.")

class StorageError(AgentCortexError):
    """Raised when SQLite read/write operations fail."""
