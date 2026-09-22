/** Names a downloaded file after the meeting, so a folder of them reads as a folder of meetings. */
export function slug(title: string) {
  return (
    title
      .normalize("NFD")
      .replace(/[̀-ͯ]/g, "")
      .replace(/đ/gi, "d")
      .replace(/[^a-zA-Z0-9]+/g, "-")
      .replace(/^-|-$/g, "")
      .toLowerCase() || "cuoc-hop"
  );
}
