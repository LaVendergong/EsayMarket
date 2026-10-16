export function formatDateTime(v?: string): string {
  if (!v) return '-'
  return v.replace('T', ' ').replace(/\.\d+$/, '')
}

export function money(v?: number | string | null): string {
  if (v === null || v === undefined || v === '') return '-'
  return `¥${Number(v).toFixed(2)}`
}
