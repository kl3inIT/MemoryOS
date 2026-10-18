# Sandbox verification

| Contract | Evidence |
| --- | --- |
| A conversation keeps one sandbox, renewed on every use | `PostgresSandboxServiceTest.aConversationKeepsOneSandboxRenewedOnEveryUse` |
| A sandbox the runtime no longer has is replaced | `PostgresSandboxServiceTest.aSandboxTheRuntimeNoLongerHasIsReplaced` |
| A sandbox past its total life is killed and replaced | `PostgresSandboxServiceTest.aSandboxPastItsLifetimeIsKilledAndReplaced` |
| Scratch work runs in a sandbox of no conversation that ends with it, even when the work fails; it takes no place in the bound, and a third at once is busy | `PostgresSandboxServiceTest.scratchWorkRunsInASandboxOfNoConversationThatEndsWithIt` |
| The running bound refuses another conversation with `SANDBOX_BUSY`; an idle sandbox stops counting | `PostgresSandboxServiceTest.pastTheBoundAnotherConversationIsToldTheSandboxesAreBusy` |
| Two conversations starting at once cannot both take the last place (fails without the advisory lock: 2 sandboxes under a bound of 1) | `PostgresSandboxServiceTest.twoConversationsStartingAtOnceCannotBothTakeTheLastPlace` |
| A slow start holds no lock other conversations wait for | `PostgresSandboxServiceTest.aSlowStartHoldsNoLockThatOtherConversationsWaitFor` |
| A failed start gives its place back | `PostgresSandboxServiceTest.aFailedStartGivesItsPlaceBack` |
| Two calls of one conversation share one start | `PostgresSandboxServiceTest.twoCallsOfOneConversationShareOneStart` |
| A call whose sandbox went away mid-call runs again in a fresh one | `PostgresSandboxServiceTest.aCommandRunsAgainInAFreshSandboxWhenItsOwnWentAwayMidCall` |
| No computer image or runtime: `SANDBOX_NOT_CONFIGURED` | `PostgresSandboxServiceTest.withoutAnImageOrARuntimeThereIsNoSandbox` |
| The Worker kills orphans older than the grace and clears rows whose sandbox is gone | `PostgresSandboxServiceTest.theReconciliationKillsOrphansAndClearsRowsWhoseSandboxIsGone` |
| A turn's workspace is archived under a new key and the next sandbox starts from it; without a sandbox nothing is archived | `PostgresSandboxServiceTest.aTurnsWorkspaceIsArchivedAndTheNextSandboxStartsFromIt` |
| A workspace past the bounds is not saved and the last archive stays | `PostgresSandboxServiceTest.aWorkspacePastTheBoundKeepsTheLastArchive` |
| The Worker deletes archives older than the grace that no row starts from, a deleted conversation's among them | `PostgresSandboxServiceTest.theReconciliationDeletesArchivesNoConversationStartsFrom` |
| Object Storage streams an object of declared size and SHA-256, refuses a body that does not match and refuses to overwrite it | `S3ObjectStorageIntegrationTest.streamsAnObjectOfTheDeclaredLengthOnceAndRefusesToOverwriteIt` |
| Deleting a conversation deletes its row | `PostgresSandboxServiceTest.deletingTheConversationDeletesItsRow` |
| The OpenSandbox runtime: user 1000 in `/workspace`, exit codes and stderr, a timed-out command reported and stopped, binary files exact and writable by the sandbox user, the read bound, listing, a planted link to a root file neither read nor written but refused as a file (`SandboxFileException`), an unlisted domain blocked, renewal, labels, and `SandboxGoneException` once killed | `OpenSandboxRuntimeContractTest`, run against a server with `MEMORYOS_SANDBOX_TEST_ENDPOINT`, `_API_KEY` and `_IMAGE` set; skipped elsewhere |
| The computer image has its tools, and user 1000 owns the venv so `uv pip install` can write to it | CI `sandbox` job, "Check the computer image's tools" |
| On staging, a command adds a package with `uv pip install` from PyPI and a domain outside the list stays blocked | Chat scenario `pip` (`run.py`), recorded in the MEM-270 plan |
| The integration uses only the sandbox contract | `OpenSandboxDependencyRulesTest` |
| `sandbox` is a closed module whose persistence only it reads | `ModulithArchitectureTest`, `CoreDependencyRulesTest` |
