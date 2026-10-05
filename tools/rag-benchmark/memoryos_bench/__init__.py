"""MemoryOS benchmark tooling that runs inside the promptfoo container: standard library only.

The container's root filesystem is read-only and nothing is installed into it, so every module here
imports only from the Python standard library.
"""
