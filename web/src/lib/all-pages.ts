/** Reads every page of a list endpoint; pickers must not silently hide choices after the first page. */
export async function allPages<T>(load: (offset: number) => Promise<T[]>) {
  const items: T[] = [];
  for (let offset = 0; offset <= 10000; offset += 100) {
    const page = await load(offset);
    items.push(...page);
    if (page.length < 100) return items;
  }
  throw new Error("Danh sách vượt giới hạn tải. Hãy thu gọn trước khi chọn.");
}
