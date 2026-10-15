The image of the MemoryOS Chat sandbox ([MEM-270](../../docs/increments/active/mem-270-chat-sandbox/design.md)): the
computer OpenSandbox starts for a conversation. It carries the interpreter executor's Python stack and helpers
(MEM-110) and the office tools listed in the design. Commands run as user 1000 in `/workspace`.

To build locally:

```
docker build -t memoryos-sandbox-computer -f Dockerfile .
```
