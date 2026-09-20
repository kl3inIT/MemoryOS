import { useState } from "react";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Input } from "@/components/ui/input";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useChatPreferences, useSaveChatPreferences } from "./chat-preferences";

/** Onyx Profile: the name and email come from sign-in; the work role is the member's and reaches the prompt. */
export function ProfileSection() {
  const ui = useAppTranslation();
  const preferences = useChatPreferences();
  const save = useSaveChatPreferences();
  // A draft only while typing; otherwise the saved value shows.
  const [draft, setDraft] = useState<string>();
  const data = preferences.data;
  const role = draft ?? data?.workRole ?? "";
  return (
    <section aria-labelledby="profile-heading" className="flex max-w-2xl flex-col gap-3">
      <div>
        <h2 id="profile-heading" className="font-heading-h3 text-content-primary">
          {ui("Profile")}
        </h2>
        <p className="text-content-muted">
          {ui("Name and email come from your organization sign-in.")}
        </p>
      </div>
      <SettingRows>
        <SettingRow
          title={ui("Full Name")}
          control={
            <span className="block max-w-64 truncate text-content-secondary">
              {data?.displayName || "—"}
            </span>
          }
        />
        <SettingRow
          title={ui("Email")}
          control={
            <span className="block max-w-64 truncate text-content-secondary">
              {data?.email || "—"}
            </span>
          }
        />
        <div className="flex flex-col gap-2 px-4 py-3">
          <label htmlFor="work-role" className="font-main-ui-action text-content-primary">
            {ui("Work Role")}
          </label>
          <p id="work-role-description" className="font-secondary-body text-content-muted">
            {ui("Share your role to better tailor responses.")}
          </p>
          <Input
            id="work-role"
            aria-describedby="work-role-description"
            maxLength={200}
            placeholder={ui("Your role")}
            value={role}
            disabled={!preferences.isSuccess || save.isPending}
            onChange={(event) => setDraft(event.target.value)}
            onBlur={() => {
              if (draft === undefined || !preferences.isSuccess) return;
              if (draft.trim() === data?.workRole) setDraft(undefined);
              else
                save.mutate({ workRole: draft.trim() }, { onSettled: () => setDraft(undefined) });
            }}
          />
        </div>
      </SettingRows>
      <p aria-live="polite" className="font-secondary-body text-content-muted">
        {save.isPending ? ui("Saving…") : save.isSuccess ? ui("Saved") : ""}
      </p>
    </section>
  );
}
