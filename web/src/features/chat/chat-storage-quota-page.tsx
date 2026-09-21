import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { HardDrive, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  getChatStorageQuota,
  setChatStoragePersonQuota,
  setChatStorageQuota,
} from "@/lib/hey-api/sdk.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";
import { fileSize } from "./chat-code";

const MIB = 1024 * 1024;

/** Whole MiB is the unit an administrator thinks in; the API takes bytes. */
function mib(bytes: number | null | undefined) {
  return bytes == null ? "" : String(Math.round(bytes / MIB));
}

/**
 * Storage limits for file libraries (MEM-152): one limit per person for the whole Tenant, raised or lowered for
 * individuals. Model managers administer it, as they do every other Tenant-wide Chat limit.
 */
export function ChatStorageQuotaPage() {
  const ui = useAppTranslation();
  const session = useApplicationSession();
  const manager = session.capabilities.includes("MODELS_MANAGE");
  const problemMessage = useProblemMessage();
  const [limit, setLimit] = useState<string>();
  const [person, setPerson] = useState("");
  const [personLimit, setPersonLimit] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ErrorMessage>();
  const quota = useQuery({
    queryKey: ["chat-storage-quota", session.actorId, session.authorizationVersion],
    enabled: manager,
    queryFn: async ({ signal }) => (await getChatStorageQuota({ signal, throwOnError: true })).data,
    retry: false,
  });

  const save = async (run: () => Promise<unknown>) => {
    setPending(true);
    setError(undefined);
    try {
      await run();
      await quota.refetch();
      setLimit(undefined);
    } catch (failed) {
      setError(presentProblem(failed, "mutation", {}).message);
    } finally {
      setPending(false);
    }
  };

  const bytes = (value: string) => {
    const megabytes = Number(value.trim());
    return value.trim() === "" || !Number.isFinite(megabytes) || megabytes <= 0
      ? null
      : Math.round(megabytes * MIB);
  };

  if (!manager)
    return (
      <p role="alert" className="p-6">
        {ui("Bạn không có quyền quản lý mô hình.")}
      </p>
    );
  const tenantLimit = limit ?? mib(quota.data?.tenantLimitBytes);
  return (
    <SettingsLayout>
      <PageHeader
        title={ui("Dung lượng thư viện tệp")}
        icon={<HardDrive />}
        description={ui(
          "Giới hạn dung lượng thư viện tệp của mỗi người. Để trống là không giới hạn, như trước đây.",
        )}
      />
      {quota.isError && (
        <p role="alert">
          {ui("Không tải được hạn mức dung lượng.")}{" "}
          <Button prominence="internal" size="sm" onClick={() => void quota.refetch()}>
            {ui("Tải lại")}
          </Button>
        </p>
      )}
      {quota.isPending && <p role="status">{ui("Đang tải…")}</p>}
      {error && (
        <p role="alert" className="text-sm text-content-danger">
          {problemMessage(error)}
        </p>
      )}
      {quota.data && (
        <>
          <section aria-label={ui("Hạn mức của tổ chức")} className="space-y-3">
            <label className="block space-y-1">
              <span>{ui("Hạn mức mỗi người (MiB)")}</span>
              <Input
                value={tenantLimit}
                inputMode="numeric"
                placeholder={ui("Không giới hạn")}
                onChange={(event) => setLimit(event.target.value)}
              />
            </label>
            <div className="flex items-center gap-2">
              <Button
                pending={pending}
                onClick={() =>
                  void save(() =>
                    setChatStorageQuota({
                      body: { maxBytes: bytes(tenantLimit) },
                      headers: sameOriginMutationHeaders,
                      throwOnError: true,
                    }),
                  )
                }
              >
                {ui("Lưu hạn mức")}
              </Button>
              <span className="text-sm text-content-muted">
                {quota.data.tenantLimitBytes
                  ? ui("Đang áp dụng {{size}} mỗi người", {
                      size: fileSize(quota.data.tenantLimitBytes, i18n.language),
                    })
                  : ui("Hiện không giới hạn")}
              </span>
            </div>
          </section>

          <section aria-label={ui("Hạn mức riêng")} className="space-y-3">
            <h2 className="font-medium">{ui("Hạn mức riêng theo người")}</h2>
            <div className="flex flex-wrap items-end gap-2">
              <label className="min-w-64 flex-1 space-y-1">
                <span>{ui("Actor ID")}</span>
                <Input value={person} onChange={(event) => setPerson(event.target.value)} />
              </label>
              <label className="w-40 space-y-1">
                <span>{ui("Hạn mức (MiB)")}</span>
                <Input
                  value={personLimit}
                  inputMode="numeric"
                  onChange={(event) => setPersonLimit(event.target.value)}
                />
              </label>
              <Button
                prominence="secondary"
                pending={pending}
                disabled={person.trim() === "" || bytes(personLimit) === null}
                onClick={() =>
                  void save(async () => {
                    await setChatStoragePersonQuota({
                      path: { actorId: person.trim() },
                      body: { maxBytes: bytes(personLimit) },
                      headers: sameOriginMutationHeaders,
                      throwOnError: true,
                    });
                    setPerson("");
                    setPersonLimit("");
                  })
                }
              >
                {ui("Đặt hạn mức riêng")}
              </Button>
            </div>
            {quota.data.people.length === 0 ? (
              <p className="text-sm text-content-muted">
                {ui("Chưa có ai được đặt hạn mức riêng.")}
              </p>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{ui("Người dùng")}</TableHead>
                    <TableHead>{ui("Hạn mức")}</TableHead>
                    <TableHead className="text-right">{ui("Thao tác")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {quota.data.people.map((row) => (
                    <TableRow key={row.actorId}>
                      <TableCell>{row.name ?? row.actorId}</TableCell>
                      <TableCell>{fileSize(row.maxBytes, i18n.language)}</TableCell>
                      <TableCell className="text-right">
                        <IconButton
                          size="sm"
                          prominence="internal"
                          aria-label={ui("Bỏ hạn mức riêng của {{name}}", {
                            name: row.name ?? row.actorId,
                          })}
                          onClick={() =>
                            void save(() =>
                              setChatStoragePersonQuota({
                                path: { actorId: row.actorId },
                                body: { maxBytes: null },
                                headers: sameOriginMutationHeaders,
                                throwOnError: true,
                              }),
                            )
                          }
                        >
                          <Trash2 />
                        </IconButton>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </section>
        </>
      )}
    </SettingsLayout>
  );
}
