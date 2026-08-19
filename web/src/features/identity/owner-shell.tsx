import { LockKeyhole, ShieldCheck } from "lucide-react";
import { Brand } from "@/components/brand";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const dateFormatter = new Intl.DateTimeFormat(undefined, {
  weekday: "long",
  month: "long",
  day: "numeric",
});

function greetingFor(date: Date) {
  const hour = date.getHours();

  if (hour < 12) return "Good morning.";
  if (hour < 18) return "Good afternoon.";
  return "Good evening.";
}

export function OwnerShell({ actorId, now = new Date() }: { actorId: string; now?: Date }) {
  return (
    <main className="min-h-svh bg-muted/30 text-foreground">
      <header className="border-b border-border bg-background">
        <div className="mx-auto flex h-14 w-full max-w-7xl items-center justify-between px-5 sm:px-8">
          <Brand />
          <Badge
            variant="outline"
            className="h-7 gap-2 rounded-md bg-background px-2.5 shadow-none"
          >
            <span className="size-1.5 rounded-full bg-foreground" />
            Private session
          </Badge>
        </div>
      </header>

      <section className="mx-auto w-full max-w-7xl px-5 py-8 sm:px-8 sm:py-12">
        <header className="mb-8 flex flex-col gap-3 border-b border-border pb-6 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p className="mb-2 text-xs font-medium text-muted-foreground">Owner workspace</p>
            <h1 className="text-3xl font-semibold tracking-[-0.04em] sm:text-4xl">
              {greetingFor(now)}
            </h1>
          </div>
          <time className="text-sm text-muted-foreground" dateTime={now.toISOString()}>
            {dateFormatter.format(now)}
          </time>
        </header>

        <div className="grid gap-4 lg:grid-cols-[minmax(0,1.5fr)_minmax(18rem,0.7fr)]">
          <Card className="overflow-hidden border-neutral-800 bg-neutral-950 text-white shadow-none">
            <CardHeader className="flex-row items-start justify-between border-b border-white/10">
              <div className="space-y-1">
                <CardTitle className="text-sm font-medium text-white">Identity</CardTitle>
                <p className="text-sm text-white/55">Authenticated workspace owner</p>
              </div>
              <Badge
                variant="outline"
                className="gap-1.5 rounded-md border-white/15 bg-white/5 text-xs text-white shadow-none"
              >
                <ShieldCheck className="size-3.5" />
                Verified
              </Badge>
            </CardHeader>
            <CardContent className="space-y-8 pt-6">
              <div className="grid size-12 place-items-center rounded-lg border border-white/15 bg-white/5">
                <LockKeyhole className="size-5 text-white/70" />
              </div>
              <div>
                <p className="mb-2 text-xs font-medium text-white/45">Actor ID</p>
                <code
                  className="block break-all font-mono text-sm leading-6 text-white/80"
                  aria-label={`Actor ID ${actorId}`}
                >
                  {actorId}
                </code>
              </div>
            </CardContent>
          </Card>

          <Card className="border-border bg-background shadow-none">
            <CardHeader className="border-b border-border">
              <CardTitle className="text-sm font-medium">Session</CardTitle>
              <p className="text-sm text-muted-foreground">Current security posture</p>
            </CardHeader>
            <CardContent className="pt-2">
              <dl className="divide-y divide-border">
                <div className="flex items-center justify-between py-4">
                  <dt className="text-sm text-muted-foreground">Status</dt>
                  <dd className="flex items-center gap-2 text-sm font-medium">
                    <span className="size-1.5 rounded-full bg-foreground" />
                    Active
                  </dd>
                </div>
                <div className="flex items-center justify-between py-4">
                  <dt className="text-sm text-muted-foreground">Role</dt>
                  <dd className="text-sm font-medium">Owner</dd>
                </div>
                <div className="flex items-center justify-between py-4">
                  <dt className="text-sm text-muted-foreground">Transport</dt>
                  <dd className="text-sm font-medium">Same origin</dd>
                </div>
              </dl>
            </CardContent>
          </Card>
        </div>
      </section>
    </main>
  );
}
