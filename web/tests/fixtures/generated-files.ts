import { readFile } from "node:fs/promises";
import type { ServerResponse } from "node:http";
import type { ChatMessage } from "../../src/lib/hey-api/types.gen.ts";

const directory = new URL("./generated-files/", import.meta.url);
const xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

type Fixture = { id: string; filename: string; mediaType: string; chart?: unknown } & (
  | { text: string }
  | { file: string }
  | { sizeBytes: number }
);

const csv = [
  "Mã đơn,Khách hàng,Địa chỉ,Sản phẩm,Số lượng,Đơn giá (₫),Thành tiền (₫),Ngày",
  'DH-0001,Công ty TNHH Minh Phát,"12 Lê Lợi, Quận 1, TP.HCM",Máy lọc nước RO,4,"8.500.000","34.000.000",02/07/2026',
  'DH-0002,Nguyễn Thị Hương,"45 Trần Phú, Hải Châu, Đà Nẵng",Nồi chiên không dầu,2,"2.190.000","4.380.000",05/07/2026',
  'DH-0003,"Siêu thị ""Xanh"" Hà Nội","88 Kim Mã, Ba Đình, Hà Nội",Quạt điều hòa,15,"3.450.000","51.750.000",11/07/2026',
  "",
].join("\n");

const python = [
  "import pandas as pd",
  "",
  "# Đọc dữ liệu doanh thu quý 3",
  "df = pd.read_excel('Doanh thu Q3 2026 theo khu vực.xlsx', sheet_name='Doanh thu Q3')",
  "tong = df.groupby('Khu vực')['Tổng quý (₫)'].sum()",
  "print(tong.sort_values(ascending=False))",
  "",
].join("\n");

/** Files a saved run_python answer produced, of every previewable kind plus one that only downloads. */
export const generatedFixtures: Fixture[] = [
  {
    id: "0b7c7f64-1d0a-4a4e-9c35-2f6f3c1a0001",
    filename: "Doanh thu Q3 2026 theo khu vực.xlsx",
    mediaType: xlsx,
    sizeBytes: 18_432,
  },
  {
    id: "0b7c7f64-1d0a-4a4e-9c35-2f6f3c1a0002",
    filename: "doanh-thu-chi-tiet.csv",
    mediaType: "text/csv",
    text: csv,
  },
  {
    id: "0b7c7f64-1d0a-4a4e-9c35-2f6f3c1a0003",
    filename: "Báo cáo quý 3.docx",
    mediaType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    file: "bao-cao-q3.docx",
  },
  {
    id: "0b7c7f64-1d0a-4a4e-9c35-2f6f3c1a0004",
    filename: "Tài chính Q3.pdf",
    mediaType: "application/pdf",
    file: "tai-chinh-q3.pdf",
  },
  {
    id: "0b7c7f64-1d0a-4a4e-9c35-2f6f3c1a0005",
    filename: "bieu_do_doanh_thu.png",
    mediaType: "image/png",
    file: "bieu-do-doanh-thu.png",
  },
  {
    id: "0b7c7f64-1d0a-4a4e-9c35-2f6f3c1a0006",
    filename: "phan_tich_doanh_thu.py",
    mediaType: "text/plain",
    text: python,
  },
  {
    id: "0b7c7f64-1d0a-4a4e-9c35-2f6f3c1a0007",
    filename: "Trình chiếu quý 3.pptx",
    mediaType: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    sizeBytes: 96_210,
  },
  {
    id: "0b7c7f64-1d0a-4a4e-9c35-2f6f3c1a0008",
    filename: "Doanh thu theo tháng.png",
    mediaType: "image/png",
    file: "bieu-do-doanh-thu.png",
    chart: {
      type: "line",
      title: "Doanh thu theo tháng",
      x_label: "Tháng",
      y_label: "Tỷ đồng (₫)",
      y_unit: "₫",
      elements: [
        {
          label: "Miền Bắc",
          points: [
            ["T7", 1.62],
            ["T8", 1.75],
            ["T9", 1.74],
          ],
        },
        {
          label: "Miền Trung",
          points: [
            ["T7", 0.71],
            ["T8", 0.78],
            ["T9", 0.82],
          ],
        },
        {
          label: "Miền Nam",
          points: [
            ["T7", 1.58],
            ["T8", 1.71],
            ["T9", 1.76],
          ],
        },
      ],
    },
  },
  {
    id: "0b7c7f64-1d0a-4a4e-9c35-2f6f3c1a0009",
    filename: "Tỷ trọng doanh thu.png",
    mediaType: "image/png",
    file: "bieu-do-doanh-thu.png",
    chart: {
      type: "pie",
      title: "Tỷ trọng doanh thu quý 3",
      elements: [
        { label: "Miền Bắc", angle: 147.7, radius: 1 },
        { label: "Miền Trung", angle: 66.6, radius: 1 },
        { label: "Miền Nam", angle: 145.7, radius: 1 },
      ],
    },
  },
];

const money = (value: number) => `"${value.toLocaleString("vi-VN")}.000.000"`;
const regions = [
  "Khu vực,Tỉnh/Thành,Cửa hàng,Tháng 7 (₫),Tháng 8 (₫),Tháng 9 (₫),Tổng quý (₫),Chỉ tiêu (₫),Đạt (%),Nhân viên,Ghi chú,Cập nhật",
  ...Array.from({ length: 40 }, (_, i) =>
    [
      ["Miền Bắc", "Miền Trung", "Miền Nam"][i % 3],
      ["Hà Nội", "Đà Nẵng", "TP. Hồ Chí Minh", "Hải Phòng", "Cần Thơ"][i % 5],
      `Cửa hàng số ${i + 1}`,
      money(120 + i * 7),
      money(131 + i * 5),
      money(142 + i * 6),
      money(393 + i * 18),
      money(400 + i * 10),
      String(92 + (i % 11)),
      ["Trần Văn An", "Lê Thị Bình", "Phạm Minh Châu"][i % 3],
      i % 4 === 0 ? '"Khai trương chi nhánh mới, tăng ca cuối tuần"' : "",
      "30/09/2026",
    ].join(","),
  ),
  "",
].join("\n");

const spreadsheet = {
  sheets: [
    { name: "Doanh thu Q3", truncated: false, csv: regions },
    {
      name: "Chi phí vận hành và khấu hao tài sản cố định",
      truncated: true,
      csv: 'Hạng mục,Số tiền (₫)\nThuê mặt bằng,"1.240.000.000"\nKhấu hao,"310.000.000"\n',
    },
    { name: "Ghi chú", truncated: false, csv: "" },
  ],
};

async function bytes(fixture: Fixture): Promise<Buffer> {
  if ("text" in fixture) return Buffer.from(fixture.text, "utf8");
  if ("file" in fixture) return readFile(new URL(fixture.file, directory));
  return Buffer.alloc(0);
}

/** Serves the owner-private artifact content and xlsx preview routes for the fixtures above. */
export async function handleGeneratedFile(
  path: string,
  response: ServerResponse,
): Promise<boolean> {
  const match = /^\/api\/chat\/file-artifacts\/([0-9a-f-]{36})\/(content|preview|chart)$/.exec(
    path,
  );
  if (!match) return false;
  const fixture = generatedFixtures.find((item) => item.id === match[1]);
  const send = (status: number, data: unknown) => {
    response.writeHead(status, { "content-type": "application/json" });
    response.end(JSON.stringify(data));
  };
  if (!fixture) send(404, {});
  else if (match[2] === "chart") {
    if (fixture.chart) send(200, fixture.chart);
    else send(404, {});
  } else if (match[2] === "preview") {
    if (fixture.mediaType === xlsx) send(200, spreadsheet);
    else send(400, {});
  } else {
    const content = await bytes(fixture);
    response.writeHead(200, {
      "content-type": fixture.mediaType,
      "content-length": content.length,
      "x-content-type-options": "nosniff",
      "cache-control": "no-store",
    });
    response.end(content);
  }
  return true;
}

/** A saved question and answer whose run_python call produced the fixtures. */
export async function generatedFileMessages(
  sessionId: string,
  rootMessageId: string,
): Promise<ChatMessage[]> {
  const now = new Date().toISOString();
  const userId = crypto.randomUUID();
  const assistantId = crypto.randomUUID();
  const files = await Promise.all(
    generatedFixtures.map(async (fixture) => ({
      id: fixture.id,
      filename: fixture.filename,
      mediaType: fixture.mediaType,
      sizeBytes: "sizeBytes" in fixture ? fixture.sizeBytes : (await bytes(fixture)).length,
      chart: fixture.chart !== undefined,
    })),
  );
  const common = {
    sessionId,
    status: "COMPLETED" as const,
    createdAt: now,
    finishedAt: now,
    sources: [],
    artifacts: [],
    files: [],
    images: [],
    activity: { steps: [], reasoning: [] },
    research: { clarification: false, plan: null, agents: [] },
  };
  return [
    {
      ...common,
      id: userId,
      parentMessageId: rootMessageId,
      latestChildMessageId: assistantId,
      role: "USER",
      content: "Phân tích doanh thu quý 3 và xuất báo cáo Excel, Word, PDF kèm biểu đồ giúp tôi.",
      generatedFiles: [],
    },
    {
      ...common,
      id: assistantId,
      parentMessageId: userId,
      latestChildMessageId: null,
      role: "ASSISTANT",
      content:
        "Tôi đã phân tích doanh thu quý 3 và tạo các tệp dưới đây. Doanh thu cả quý đạt **12,48 tỷ ₫**, tăng 18,4% so với cùng kỳ.",
      generatedFiles: files,
    },
  ];
}
