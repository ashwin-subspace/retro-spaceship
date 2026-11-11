"""Game entities.

Entities never import ``world``, ``fx`` or ``audio``. They receive a per-frame
context object (``ctx``) exposing ``clock``, ``fx``, ``audio``, ``world`` and
``player``, which keeps the dependency graph acyclic and makes every entity
testable in isolation.
"""
