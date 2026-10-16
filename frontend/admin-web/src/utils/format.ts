/** 后端 LocalDateTime 序列化成 ISO-8601（2026-10-16T14:02:06.6208509），展示时裁成秒 */
export function formatDateTime(v?: string): string {
  if (!v) return '-'
  return v.replace('T', ' ').replace(/\.\d+$/, '')
}

export function money(v?: number | string | null): string {
  if (v === null || v === undefined || v === '') return '-'
  return `¥${Number(v).toFixed(2)}`
}
