/** A cursor page, the position of its first item, and the pages before it. */
export type SelectionPaging = {
  cursor?: string;
  start: number;
  previous: Array<{ cursor?: string; start: number }>;
};

export const firstSelectionPage: SelectionPaging = { start: 1, previous: [] };

export function nextSelectionPage(
  paging: SelectionPaging,
  cursor: string,
  count: number,
): SelectionPaging {
  return {
    cursor,
    start: paging.start + count,
    previous: [...paging.previous, { cursor: paging.cursor, start: paging.start }],
  };
}

export function previousSelectionPage(paging: SelectionPaging): SelectionPaging {
  const page = paging.previous.at(-1);
  return page ? { ...page, previous: paging.previous.slice(0, -1) } : paging;
}
