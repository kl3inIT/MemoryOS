import { useAppTranslation } from "@/i18n/use-app-translation";
import { useRef, useState, type ReactNode } from "react";
import { UserRound } from "lucide-react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { SettingsLayout, PageHeader } from "@/components/composites/settings-layout";
import { NativeSelect } from "@/components/ui/native-select";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import { useApplicationSession } from "./application-session-context";
import {
  getCurrentIdentityOptions,
  setCurrentIdentityLanguageMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { uiLanguage } from "@/i18n";
import { AppearanceSection } from "./appearance-section";
import { ProfileSection } from "./profile-section";

const identityKey = getCurrentIdentityOptions().queryKey;

/**
 * The member's General settings. The destructive section at the end belongs to another capability (deleting every
 * conversation is Chat's), so the route hands it in.
 */
export function GeneralSettingsPage({ dangerZone }: { dangerZone?: ReactNode }) {
  const ui = useAppTranslation();

  const { t } = useTranslation(["settings", "common", "errors"]);
  const session = useApplicationSession();
  const queryClient = useQueryClient();
  const [uncertain, setUncertain] = useState(false);
  const [reloading, setReloading] = useState(false);
  const saving = useRef(false);
  const mutation = useMutation({
    ...setCurrentIdentityLanguageMutation(),
    // Cancel an older identity response before saving; the save applies only to the actor that made it.
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: identityKey, exact: true });
      return { actorId: session.actorId };
    },
    onSuccess: async (data, _variables, saved) => {
      const current = queryClient.getQueryData(identityKey);
      if (current?.actorId !== saved.actorId) return;
      await queryClient.cancelQueries({ queryKey: identityKey, exact: true });
      queryClient.setQueryData(identityKey, (value) =>
        value?.actorId === saved.actorId ? { ...value, uiLanguage: data.uiLanguage } : value,
      );
    },
    onError: async (_error, _variables, saved) => {
      if (queryClient.getQueryData(identityKey)?.actorId !== saved?.actorId) return;
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
        icon={<UserRound />}
        title={t("common:general")}
        description={t("settings:description")}
      />
      <ProfileSection />
      <AppearanceSection />
      <Field className="max-w-2xl">
        <FieldLabel htmlFor="ui-language">{t("settings:language")}</FieldLabel>
        <FieldDescription id="ui-language-description">
          {t("settings:languageDescription")}
        </FieldDescription>
        <NativeSelect
          id="ui-language"
          aria-describedby="ui-language-description"
          className="max-w-xs"
          value={uiLanguage(session.uiLanguage)}
          disabled={mutation.isPending || uncertain || reloading}
          onChange={(event) => {
            if (saving.current) return;
            saving.current = true;
            mutation.mutate({ body: { uiLanguage: uiLanguage(event.target.value) } });
          }}
        >
          <option value="vi" lang="vi">
            {ui("Tiếng Việt")}
          </option>
          <option value="en" lang="en">
            {ui("English")}
          </option>
        </NativeSelect>
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
      </Field>
      {dangerZone}
    </SettingsLayout>
  );
}
