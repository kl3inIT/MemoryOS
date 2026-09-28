import { useAppTranslation } from "@/i18n/use-app-translation";
import { CirclePlus, Search } from "lucide-react";
import { useRef, useState } from "react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import type { GroupMember, GroupSummary } from "@/lib/hey-api/types.gen";
import { GroupMemberCandidates } from "./group-member-candidates";
import { GroupMemberTable } from "./group-member-table";
import type { GroupMembersDraft } from "./group-members-draft";

const searchDelayMs = 250;

/**
 * The Group's members; edits stay in the page's draft until Save Changes. Adding replaces member
 * browsing with a search over eligible users, so one search field and one pager show at a time.
 */
export function GroupMembersSection({
  group,
  draft,
}: {
  group: GroupSummary;
  draft: GroupMembersDraft;
}) {
  const ui = useAppTranslation();
  const { canManageMembers } = draft;
  const [adding, setAdding] = useState(false);
  const [memberSearch, setMemberSearch] = useState("");
  const [candidateSearch, setCandidateSearch] = useState("");
  const [selected, setSelected] = useState<ReadonlyMap<string, GroupMember>>(() => new Map());
  const [generation, setGeneration] = useState(draft.generation);
  const addButtonRef = useRef<HTMLButtonElement>(null);
  const picking = adding && canManageMembers;
  const settledMemberSearch = useDebouncedValue(memberSearch.trim(), searchDelayMs);
  const settledCandidateSearch = useDebouncedValue(candidateSearch.trim(), searchDelayMs);

  // A discarded draft also closes the picker and forgets what it had selected.
  if (generation !== draft.generation) {
    setGeneration(draft.generation);
    setSelected(new Map());
    setAdding(false);
  }

  function toggleCandidate(candidate: GroupMember) {
    setSelected((current) => {
      const next = new Map(current);
      if (next.has(candidate.actorId)) next.delete(candidate.actorId);
      else next.set(candidate.actorId, candidate);
      return next;
    });
  }

  function addSelected() {
    if (!canManageMembers || selected.size === 0 || draft.pending) return;
    draft.add([...selected.values()]);
    setSelected(new Map());
    setAdding(false);
    addButtonRef.current?.focus();
  }

  return (
    <section
      aria-labelledby="group-members-heading"
      className="flex min-w-0 flex-col gap-3 border-t border-border-subtle pt-5"
    >
      <h2 id="group-members-heading" className="mb-1 font-heading-h3 text-content-primary">
        {ui("Group Members")}
      </h2>
      <div className="flex min-w-0 items-center gap-2">
        <InputGroup className="min-w-0 flex-1">
          <InputGroupAddon>
            <Search aria-hidden="true" />
          </InputGroupAddon>
          <InputGroupInput
            type="search"
            value={picking ? candidateSearch : memberSearch}
            aria-label={picking ? ui("Search member candidates") : ui("Search group members")}
            maxLength={200}
            placeholder={picking ? ui("Search users…") : ui("Search members…")}
            onChange={(event) =>
              picking ? setCandidateSearch(event.target.value) : setMemberSearch(event.target.value)
            }
          />
        </InputGroup>
        {picking ? (
          <Button size="sm" disabled={selected.size === 0 || draft.pending} onClick={addSelected}>
            {ui("Add")} {selected.size > 0 ? selected.size : ui("selected")}
          </Button>
        ) : null}
        {canManageMembers ? (
          <Button
            ref={addButtonRef}
            size="sm"
            prominence={picking ? "secondary" : "tertiary"}
            className="shrink-0"
            disabled={draft.pending}
            onClick={() => {
              setAdding((current) => !current);
              setSelected(new Map());
              draft.clearError();
            }}
          >
            <CirclePlus data-icon="inline-start" aria-hidden="true" />
            {picking ? ui("Done") : ui("Add")}
          </Button>
        ) : null}
      </div>

      {draft.error ? (
        <Alert variant="destructive">
          <AlertDescription>{ui(draft.error)}</AlertDescription>
        </Alert>
      ) : null}

      {picking ? (
        <GroupMemberCandidates
          group={group}
          search={settledCandidateSearch}
          excluded={draft.added}
          selected={selected}
          onToggle={toggleCandidate}
        />
      ) : (
        <GroupMemberTable
          group={group}
          draft={draft}
          search={settledMemberSearch}
          addButtonRef={addButtonRef}
        />
      )}
    </section>
  );
}
