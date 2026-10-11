import type { MemberStatus, Message, Receipt } from '~/types/kreteg'

export interface ConversationEventHandlers {
  /** Seq of the newest message the page holds; a new connection replays everything after it. */
  since: () => number
  message: (message: Message) => void
  status: (status: MemberStatus) => void
  receipt: (receipt: Receipt) => void
  members: (members: string[]) => void
  /**
   * A connection was opened. Only messages and statuses are replayed on connect, so the page reloads what else it
   * shows (read positions, members) and drops statuses it held, which the replay sends again if they still hold.
   */
  open: () => void
  /** True while the stream keeps failing and the page should poll instead; false once a connection opens again. */
  fallback: (polling: boolean) => void
}

/** Errors in a row, without a connection opening in between, before the page falls back to polling. */
const MAX_FAILURES = 3
/** How long to wait before a new EventSource when the browser has given up reconnecting the old one. */
const RECONNECT_MS = 3000
/** While polling, how often to try the stream again. */
const RETRY_MS = 30_000

/**
 * Follows GET /api/conversations/{id}/events. EventSource reconnects on its own with Last-Event-ID, which the server
 * prefers over `since`. It gives up for good on an HTTP error, so the composable then opens a new one from the latest
 * seq. After MAX_FAILURES errors in a row it reports a fallback and keeps trying every RETRY_MS.
 */
export function useConversationEvents(id: string, on: ConversationEventHandlers) {
  let source: EventSource | undefined
  let failures = 0
  let polling = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let stopped = false

  function parse<T>(event: MessageEvent, apply: (value: T) => void) {
    try {
      apply(JSON.parse(event.data) as T)
    } catch (e) {
      console.warn('kreteg: bad event', event.type, e)
    }
  }

  function setPolling(value: boolean) {
    if (polling === value) return
    polling = value
    on.fallback(value)
  }

  function connect() {
    clearTimeout(timer)
    source?.close()
    if (stopped) return
    const es = new EventSource(`/api/conversations/${encodeURIComponent(id)}/events?since=${on.since()}`)
    source = es
    es.onopen = () => {
      failures = 0
      setPolling(false)
      on.open()
    }
    es.onerror = () => {
      if (source !== es) return
      failures++
      if (failures >= MAX_FAILURES) {
        es.close()
        setPolling(true)
        timer = setTimeout(connect, RETRY_MS)
      } else if (es.readyState === EventSource.CLOSED) {
        timer = setTimeout(connect, RECONNECT_MS)
      }
    }
    es.addEventListener('message', e => parse<Message>(e, on.message))
    es.addEventListener('status', e => parse<MemberStatus>(e as MessageEvent, on.status))
    es.addEventListener('receipt', e => parse<Receipt>(e as MessageEvent, on.receipt))
    es.addEventListener('members', e => parse<{ members: string[] }>(e as MessageEvent, v => on.members(v.members)))
  }

  function close() {
    stopped = true
    clearTimeout(timer)
    source?.close()
    source = undefined
  }

  onBeforeUnmount(close)

  return { connect, close }
}
