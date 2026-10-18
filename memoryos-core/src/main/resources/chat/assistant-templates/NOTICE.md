# Notice

The assistant templates in this directory are MemoryOS's own text. How the jobs are split and the shape of several
prompts (role, steps, output format) follow agents of [lobehub/lobe-chat-agents](https://github.com/lobehub/lobe-chat-agents)
(MEM-281, 2026-10-18):

| Template | lobe-chat-agents agent |
| --- | --- |
| `email.json` | `business-email` |
| `translate-vi-en.json` | `translation-specialist` |
| `meeting-minutes.json` | `meeting` |
| `weekly-report.json` | `write-report-assistant-development` |
| `document-summary.json` | `summary-assistant` |
| `contract-review.json` | `business-contract`, `tech-lawyer` |
| `okr-kpi.json` | `kpi-hero` |

`policy-qa.json` has no counterpart.

lobe-chat-agents is distributed under the MIT License:

```
MIT License

Copyright (c) 2023 LobeHub

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
