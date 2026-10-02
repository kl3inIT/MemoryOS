import { useQuery } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { CircleAlert } from "lucide-react";
import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from "recharts";
import { EmptyState } from "@/components/composites/empty-state";
import { SectionHeader } from "@/components/composites/section-header";
import { StatStrip, StatTile } from "@/components/composites/stat-strip";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatUiDate, uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { getMcpEndpointInsightsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { McpEndpointCountResponse, McpEndpointDayResponse } from "@/lib/hey-api/types.gen";

const endpointRoute = getRouteApi("/_authenticated/admin/mcp-endpoint");

function count(value: number) {
  return new Intl.NumberFormat(uiLocale()).format(value);
}

/** Every UTC day of the period, ending today, with the days nobody called counted as zero. */
function everyDay(days: number, daily: McpEndpointDayResponse[], today = new Date()) {
  const calls = new Map(daily.map((day) => [day.day, day.calls]));
  const end = Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate());
  return Array.from({ length: days }, (_, index) => {
    const day = new Date(end - (days - 1 - index) * 86_400_000).toISOString().slice(0, 10);
    return { day, calls: calls.get(day) ?? 0 };
  });
}

/**
 * MEM-209: how much the endpoint is used over the last 7 or 30 UTC days, as Glean's MCP insights show it: calls,
 * people, failures and refusals for rate, then the calls of each day, app and tool.
 */
export function McpEndpointInsights() {
  const ui = useAppTranslation();
  const { days } = endpointRoute.useSearch();
  const navigate = endpointRoute.useNavigate();
  const insights = useQuery(getMcpEndpointInsightsOptions({ query: { days } }));

  return (
    <>
      <Select
        value={String(days)}
        onValueChange={(value) =>
          void navigate({
            search: (current) => ({ ...current, days: value === "30" ? 30 : 7 }),
            replace: true,
            resetScroll: false,
          })
        }
      >
        <SelectTrigger aria-label={ui("Period")} className="w-40">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="7">{ui("Last 7 days")}</SelectItem>
          <SelectItem value="30">{ui("Last 30 days")}</SelectItem>
        </SelectContent>
      </Select>
      {insights.isError ? (
        <EmptyState
          role="alert"
          icon={<CircleAlert />}
          title={ui("Không tải được thống kê.")}
          action={
            <Button prominence="secondary" onClick={() => void insights.refetch()}>
              {ui("Thử lại")}
            </Button>
          }
        />
      ) : (
        <>
          <StatStrip columns={4}>
            <StatTile
              label={ui("Lượt gọi")}
              value={count(insights.data?.calls ?? 0)}
              loading={insights.isPending}
            />
            <StatTile
              label={ui("Người dùng")}
              value={count(insights.data?.people ?? 0)}
              loading={insights.isPending}
            />
            <StatTile
              label={ui("Thất bại")}
              value={count(insights.data?.failed ?? 0)}
              tone={insights.data?.failed ? "danger" : undefined}
              loading={insights.isPending}
            />
            <StatTile
              label={ui("Vượt giới hạn")}
              value={count(insights.data?.rateLimited ?? 0)}
              tone={insights.data?.rateLimited ? "warning" : undefined}
              loading={insights.isPending}
            />
          </StatStrip>
          {insights.data ? (
            <>
              <Card className="min-w-0">
                <CardContent className="min-w-0">
                  <div className="flex min-w-0 flex-col gap-4">
                    <SectionHeader title={ui("Lượt gọi theo ngày")} />
                    <DailyCalls days={everyDay(insights.data.days, insights.data.daily)} />
                  </div>
                </CardContent>
              </Card>
              <div className="grid min-w-0 gap-6 lg:grid-cols-2">
                <Breakdown
                  title={ui("Theo ứng dụng")}
                  column={ui("Ứng dụng")}
                  rows={insights.data.apps}
                />
                <Breakdown
                  title={ui("Theo công cụ")}
                  column={ui("Công cụ")}
                  rows={insights.data.tools}
                  code
                />
              </div>
            </>
          ) : null}
        </>
      )}
    </>
  );
}

function DailyCalls({ days }: { days: Array<{ day: string; calls: number }> }) {
  const ui = useAppTranslation();
  const config: ChartConfig = { calls: { label: ui("Lượt gọi"), color: "var(--chart-1)" } };
  const data = days.map((day) => ({
    label: formatUiDate(`${day.day}T00:00:00Z`, {
      day: "2-digit",
      month: "2-digit",
      timeZone: "UTC",
    }),
    calls: day.calls,
  }));
  return (
    <ChartContainer config={config} className="aspect-auto h-56 w-full">
      <BarChart accessibilityLayer data={data}>
        <CartesianGrid vertical={false} />
        <XAxis dataKey="label" tickLine={false} axisLine={false} tickMargin={8} minTickGap={16} />
        <YAxis
          tickLine={false}
          axisLine={false}
          width={40}
          allowDecimals={false}
          tickFormatter={(value: number) => count(value)}
        />
        <ChartTooltip content={<ChartTooltipContent />} />
        <Bar dataKey="calls" fill="var(--color-calls)" radius={4} />
      </BarChart>
    </ChartContainer>
  );
}

function Breakdown({
  title,
  column,
  rows,
  code = false,
}: {
  title: string;
  column: string;
  rows: McpEndpointCountResponse[];
  code?: boolean;
}) {
  const ui = useAppTranslation();
  return (
    <Card className="min-w-0">
      <CardContent className="min-w-0">
        <div className="flex min-w-0 flex-col gap-3">
          <SectionHeader title={title} />
          {rows.length === 0 ? (
            <p className="font-secondary-body text-content-muted">{ui("Chưa có lượt gọi nào.")}</p>
          ) : (
            <Table className="text-left">
              <TableHeader>
                <TableRow>
                  <TableHead className="pr-3 pl-0">{column}</TableHead>
                  <TableHead className="px-3 text-right whitespace-nowrap">
                    {ui("Lượt gọi")}
                  </TableHead>
                  <TableHead className="pr-0 pl-3 text-right whitespace-nowrap">
                    {ui("Người dùng")}
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row) => (
                  <TableRow key={row.key}>
                    <TableCell className="w-full max-w-0 truncate pr-3 pl-0" title={row.key}>
                      <span className={code ? "font-mono text-xs" : undefined}>{row.name}</span>
                    </TableCell>
                    <TableCell className="px-3 text-right">
                      <span className="tabular-nums">{count(row.calls)}</span>
                    </TableCell>
                    <TableCell className="pr-0 pl-3 text-right">
                      <span className="tabular-nums">{count(row.people)}</span>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
