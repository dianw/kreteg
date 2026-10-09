export function formatTime(epochMs: number): string {
  return new Date(epochMs).toLocaleString()
}

/** Hours and minutes, as shown inside a message bubble. */
export function formatClock(epochMs: number): string {
  return new Date(epochMs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function startOfDay(epochMs: number): number {
  const d = new Date(epochMs)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

export function isSameDay(a: number, b: number): boolean {
  return startOfDay(a) === startOfDay(b)
}

/** "Today", "Yesterday" or a date, for the separators between days in a conversation. */
export function formatDay(epochMs: number): string {
  const days = Math.round((startOfDay(Date.now()) - startOfDay(epochMs)) / 86_400_000)
  if (days === 0) return 'Today'
  if (days === 1) return 'Yesterday'
  return new Date(epochMs).toLocaleDateString([], { day: 'numeric', month: 'long', year: 'numeric' })
}

/** The time today, otherwise the date, as in a chat list. */
export function formatListTime(epochMs: number): string {
  if (isSameDay(epochMs, Date.now())) return formatClock(epochMs)
  return formatDay(epochMs) === 'Yesterday' ? 'Yesterday' : new Date(epochMs).toLocaleDateString()
}

export function formatIdle(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h`
  return `${Math.floor(seconds / 86400)}d`
}

export const STATUS_COLOR = { live: 'success', idle: 'warning', stale: 'neutral' } as const

export function initials(name: string): string {
  const words = name.split(/[\s_\-.]+/).filter(Boolean)
  return ((words[0]?.[0] ?? '') + (words[1]?.[0] ?? '')).toUpperCase() || '?'
}

const NAME_COLORS = [
  'text-rose-600 dark:text-rose-400',
  'text-orange-600 dark:text-orange-400',
  'text-amber-700 dark:text-amber-400',
  'text-emerald-700 dark:text-emerald-400',
  'text-teal-700 dark:text-teal-400',
  'text-sky-700 dark:text-sky-400',
  'text-indigo-600 dark:text-indigo-400',
  'text-violet-600 dark:text-violet-400',
  'text-fuchsia-700 dark:text-fuchsia-400',
] as const

/** A stable text color per participant, so senders are told apart in a group conversation. */
export function nameColor(name: string): string {
  let hash = 0
  for (const c of name) hash = (hash * 31 + c.charCodeAt(0)) | 0
  return NAME_COLORS[Math.abs(hash) % NAME_COLORS.length]!
}
