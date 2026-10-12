import { useAppTranslation } from "@/i18n/use-app-translation";
import { TriangleAlert } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
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
import { CopyableValue } from "@/features/sources/shared/copyable-value";

/** The read scopes a Lark Source uses, each enabled for the app identity. */
const SCOPES = [
  { name: "wiki:wiki:readonly", why: "List Wiki spaces and their pages" },
  { name: "drive:drive:readonly", why: "List Drive folders and download files" },
  { name: "docx:document:readonly", why: "Read documents" },
  { name: "sheets:spreadsheet:readonly", why: "Read spreadsheets" },
  { name: "docs:permission.member:retrieve", why: "Read who a document is shared with" },
  { name: "im:chat:readonly", why: "Read the members of a group a document is shared with" },
  {
    name: "contact:user.base:readonly",
    why: "Read the members of a department a document is shared with",
  },
  { name: "contact:user.email:readonly", why: "Match Lark people to MemoryOS accounts by email" },
] as const;

/** What an administrator does in the Lark Developer Console before a credential can be verified. */
export function LarkAppGuide() {
  const ui = useAppTranslation();

  return (
    <Collapsible>
      <CollapsibleTrigger asChild>
        <Button size="sm" prominence="tertiary" className="w-fit px-0">
          {ui("Setup instructions")}
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="mt-3 flex flex-col gap-3 font-secondary-body text-content-muted">
          <ol className="flex list-decimal flex-col gap-2 pl-5">
            <li>
              {ui(
                "In the Lark Developer Console, create a custom app for your organization and enable its bot.",
              )}
            </li>
            <li>
              {ui(
                "Under Permissions & Scopes, add the scopes below as app identity scopes, then publish a version of the app.",
              )}
            </li>
            <li>
              {ui(
                "When publishing, set the app's availability to all members, so it can read every department.",
              )}
            </li>
            <li>{ui("Copy the App ID and App Secret from Credentials & Basic Info.")}</li>
            <li>
              {ui(
                "Add the app's bot as a member of each Wiki space, and share each Drive folder with it.",
              )}
            </li>
          </ol>
          <Table className="table-fixed">
            <colgroup>
              <col />
              <col />
            </colgroup>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">{ui("Scope")}</TableHead>
                <TableHead scope="col">{ui("Why")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {SCOPES.map((scope) => (
                <TableRow key={scope.name}>
                  <TableCell className="py-2 align-middle">
                    <CopyableValue value={scope.name} />
                  </TableCell>
                  <TableCell className="py-2 align-middle wrap-anywhere">{ui(scope.why)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

/** The scopes Lark refused the app at its last verification, ready to paste into the console. */
export function LarkMissingScopes({ scopes }: { scopes: readonly string[] }) {
  const ui = useAppTranslation();
  return (
    <Alert variant="warning" role="note">
      <TriangleAlert aria-hidden="true" />
      <AlertDescription>
        <p>
          {ui(
            "Lark refused these scopes to the app identity. Add them as app identity scopes and publish the app, then test again.",
          )}
        </p>
        <ul className="mt-2 flex flex-col gap-1">
          {scopes.map((scope) => (
            <li key={scope}>
              <CopyableValue value={scope} />
            </li>
          ))}
        </ul>
      </AlertDescription>
    </Alert>
  );
}
