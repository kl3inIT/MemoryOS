import type { ReactNode } from "react";
import { Card, CardContent } from "@/components/ui/card";

/** One bordered block per group of settings in a Source setup, as in Vanta's integration setup. */
export function SetupSection({
  labelledBy,
  children,
}: {
  /** The id of the heading that names the section. */
  labelledBy: string;
  children: ReactNode;
}) {
  return (
    <section aria-labelledby={labelledBy}>
      <Card>
        <CardContent>
          <div className="flex flex-col gap-5">{children}</div>
        </CardContent>
      </Card>
    </section>
  );
}
