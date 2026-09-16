import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { Check, Copy } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

const PERMISSIONS = [
  { name: "Sites.Read.All", kind: "Application", why: "Read the sites, libraries and pages in scope" },
  { name: "Files.Read.All", kind: "Application", why: "Download the files of those libraries" },
] as const;

/** What an administrator has to do in Entra before a credential can be verified here. */
export function SharePointEntraGuide() {
  const ui = useAppTranslation();

  return (
    <Collapsible className="font-secondary-body text-content-muted">
      <CollapsibleTrigger className="w-fit cursor-pointer underline underline-offset-4">
        {ui("Setup instructions")}
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="mt-3 flex flex-col gap-3">
          <ol className="list-decimal space-y-2 pl-5">
            <li>
              {ui(
                "In the Microsoft Entra admin center, register an application for MemoryOS. No redirect URI is needed: MemoryOS signs in as the application, not as a person.",
              )}
            </li>
            <li>
              {ui(
                "Copy the Directory (tenant) ID and the Application (client) ID from the app's Overview page. Both are GUIDs.",
              )}
            </li>
            <li>
              {ui(
                "Add the application permissions below under API permissions → Microsoft Graph → Application permissions, then use Grant admin consent.",
              )}
            </li>
            <li>
              {ui(
                "Create a client secret and copy its Value, or upload a certificate to the app and keep the matching PKCS#12 keystore for the next step.",
              )}
            </li>
          </ol>
          <Table className="w-full table-fixed text-sm">
            <TableHeader>
              <TableRow>
                <TableHead scope="col" className="py-2 text-left font-medium">
                  {ui("Permission")}
                </TableHead>
                <TableHead scope="col" className="w-28 py-2 text-left font-medium">
                  {ui("Type")}
                </TableHead>
                <TableHead scope="col" className="py-2 text-left font-medium">
                  {ui("Why")}
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {PERMISSIONS.map((permission) => (
                <TableRow key={permission.name}>
                  <TableCell className="py-2 align-middle">
                    <CopyableValue value={permission.name} />
                  </TableCell>
                  <TableCell className="py-2 align-middle">{ui(permission.kind)}</TableCell>
                  <TableCell className="py-2 align-middle wrap-anywhere">
                    {ui(permission.why)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <p>
            {ui(
              "Write access is never requested. A credential limited to selected sites still works; the catalog of all sites is then unavailable and the scope must name each site.",
            )}
          </p>
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

function CopyableValue({ value }: { value: string }) {
  const ui = useAppTranslation();

  const [copied, setCopied] = useState(false);
  return (
    <span className="flex items-center gap-1">
      <span className="min-w-0 truncate font-mono text-xs">{value}</span>
      <Button
        size="sm"
        prominence="tertiary"
        aria-label={ui("Copy {{v1}}", { v1: value })}
        onClick={() => {
          navigator.clipboard
            .writeText(value)
            .then(() => setCopied(true))
            .catch(() => setCopied(false));
        }}
      >
        {copied ? <Check /> : <Copy />}
      </Button>
    </span>
  );
}
