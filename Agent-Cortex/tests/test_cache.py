import pytest
from agent_cortex.cache import BoundedCache, EvictionPolicy


def make_cache(max_slots=3, policy=EvictionPolicy.FIFO):
    return BoundedCache(max_slots=max_slots, policy=policy)


def test_put_and_get_roundtrip():
    cache = make_cache()
    cache.put("a1", "hello world")
    assert cache.get("a1") == "hello world"


def test_get_unknown_returns_none():
    cache = make_cache()
    assert cache.get("missing") is None


def test_fifo_eviction():
    cache = make_cache(max_slots=3, policy=EvictionPolicy.FIFO)
    cache.put("a", "alpha")
    cache.put("b", "beta")
    cache.put("c", "gamma")
    evicted = cache.put("d", "delta")
    assert evicted == "a"
    assert not cache.contains("a")
    assert cache.contains("d")


def test_lru_eviction_respects_recency():
    cache = make_cache(max_slots=3, policy=EvictionPolicy.LRU)
    cache.put("a", "alpha")
    cache.put("b", "beta")
    cache.put("c", "gamma")
    # Access "a" to make it recently used
    cache.get("a")
    # "b" is now least recently used
    evicted = cache.put("d", "delta")
    assert evicted == "b"
    assert cache.contains("a")
    assert not cache.contains("b")
    assert cache.contains("d")


def test_contains_true_for_present():
    cache = make_cache()
    cache.put("x", "data")
    assert cache.contains("x") is True


def test_contains_false_for_absent():
    cache = make_cache()
    assert cache.contains("missing") is False


def test_size_increments_and_decrements():
    cache = make_cache(max_slots=3, policy=EvictionPolicy.FIFO)
    assert cache.size() == 0
    cache.put("a", "one")
    assert cache.size() == 1
    cache.put("b", "two")
    assert cache.size() == 2
    cache.put("c", "three")
    assert cache.size() == 3
    # Insert beyond capacity triggers eviction — size stays at max
    cache.put("d", "four")
    assert cache.size() == 3
