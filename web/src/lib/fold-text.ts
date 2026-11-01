/**
 * Text as a typed search compares it: without tone and vowel marks, "đ" read as "d", in lower case. Vietnamese is
 * often typed without its marks, so "tom tat" finds "Tóm tắt hợp đồng".
 */
export function foldText(value: string) {
  return value
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .replace(/[đĐ]/g, "d")
    .toLocaleLowerCase("vi");
}
