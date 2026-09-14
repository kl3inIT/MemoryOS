import { useAppTranslation } from "@/i18n/use-app-translation";
import { useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { SettingsLayout, PageHeader } from "@/components/ui/settings-layout";
import { Select } from "@/components/ui/select";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "./application-session-context";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { setCurrentIdentityLanguage } from "@/lib/hey-api/sdk.gen";
import type { CurrentIdentity } from "@/lib/hey-api/types.gen";
import { sameOriginMutationHeaders } from "@/lib/api";
import { presentProblem } from "@/lib/problem-presentation";
import { uiLanguage, type UiLanguage } from "@/i18n";

const identityKey = getCurrentIdentityQueryKey();

export function GeneralSettingsPage() {
  const ui = useAppTranslation();

  const { t } = useTranslation(["settings", "common", "errors"]);
  const session = useApplicationSession();
  const queryClient = useQueryClient();
  const [uncertain, setUncertain] = useState(false);
  const [reloading, setReloading] = useState(false);
  const saving = useRef(false);
  const mutation = useMutation({
    mutationFn: async ({ language }: { language: UiLanguage; actorId: string }) => {
      // Cancel an older identity response before saving; refetch the authoritative value afterward.
      await queryClient.cancelQueries({ queryKey: identityKey, exact: true });
      const { data } = await setCurrentIdentityLanguage({
        body: { uiLanguage: language },
        headers: sameOriginMutationHeaders,
        throwOnError: true,
      });
      return data;
    },
    onSuccess: async (data, variables) => {
      const current = queryClient.getQueryData<CurrentIdentity>(identityKey);
      if (current?.actorId !== variables.actorId) return;
      await queryClient.cancelQueries({ queryKey: identityKey, exact: true });
      queryClient.setQueryData<CurrentIdentity>(identityKey, (value) =>
        value?.actorId === variables.actorId ? { ...value, uiLanguage: data.uiLanguage } : value,
      );
    },
    onError: async (_, variables) => {
      if (queryClient.getQueryData<CurrentIdentity>(identityKey)?.actorId !== variables.actorId)
        return;
      setUncertain(true);
      try {
        await queryClient.refetchQueries(
          { queryKey: identityKey, exact: true },
          { throwOnError: true },
        );
        setUncertain(false);
      } catch {
        /* Keep the reload action; persistence is still unknown. */
      }
    },
    onSettled: () => {
      saving.current = false;
    },
  });
  async function reload() {
    setReloading(true);
    try {
      await queryClient.refetchQueries(
        { queryKey: identityKey, exact: true },
        { throwOnError: true },
      );
      setUncertain(false);
      mutation.reset();
    } catch {
      setUncertain(true);
    } finally {
      setReloading(false);
    }
  }
  return (
    <SettingsLayout>
      <PageHeader
        eyebrow={t("common:settings")}
        title={t("common:general")}
        description={t("settings:description")}
      />
      <div className="flex max-w-2xl flex-col gap-3">
        <label htmlFor="ui-language" className="font-main-ui-body text-content-primary">
          {t("settings:language")}
        </label>
        <p id="ui-language-description" className="text-content-muted">
          {t("settings:languageDescription")}
        </p>
        <Select
          id="ui-language"
          aria-describedby="ui-language-description"
          className="max-w-xs"
          value={uiLanguage(session.uiLanguage)}
          disabled={mutation.isPending || uncertain || reloading}
          onChange={(event) => {
            if (saving.current) return;
            saving.current = true;
            mutation.mutate({ language: uiLanguage(event.target.value), actorId: session.actorId });
          }}
        >
          <option value="vi" lang="vi">
            {ui("Tiếng Việt")}
          </option>
          <option value="en" lang="en">
            {ui("English")}
          </option>
        </Select>
        <p className="text-content-muted">{t("settings:replyHint")}</p>
        <div aria-live="polite">
          {mutation.isPending
            ? t("common:saving")
            : mutation.isSuccess
              ? t("settings:saved")
              : null}
        </div>
        {mutation.isError ? (
          <p role="alert">
            {uncertain
              ? t("settings:saveUncertain")
              : t(
                  `errors:${presentProblem(mutation.error, "mutation", { IAM_LANGUAGE_INVALID: { key: "languageInvalid" } }).message.key}`,
                )}
          </p>
        ) : null}
        {uncertain ? (
          <Button prominence="secondary" disabled={reloading} onClick={() => void reload()}>
            {t("settings:reload")}
          </Button>
        ) : null}
      </div>
    </SettingsLayout>
  );
}
