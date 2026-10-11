import type { Conversation, MemberStatus, Message, Participant, Receipt } from '~/types/kreteg'

/** Hub-wide events, sent on every stream. */
interface HubEvents {
  conversation: Conversation
  participant: Participant
}

/** Events of the conversation the stream follows. */
interface ConversationEvents {
  message: Message
  status: MemberStatus
  receipt: Receipt
  members: { members: string[] }
}

/** Errors in a row, without a connection opening in between, before the app falls back to polling. */
const MAX_FAILURES = 3
/** How long to wait before a new EventSource when the browser has given up reconnecting the old one. */
const RECONNECT_MS = 3000
/** While polling, how often to try the stream again. */
const RETRY_MS = 30_000
/** How long a conversation page that went away stays followed, so moving to the next one reconnects once, not twice. */
const UNFOLLOW_MS = 1000

type Handler = (data: any) => void

/** True while the stream keeps failing; every page then polls what it shows, as before the stream existed. */
const polling = ref(false)
let source: EventSource | undefined
let failures = 0
let timer: ReturnType<typeof setTimeout> | undefined
let unfollowTimer: ReturnType<typeof setTimeout> | undefined
/** The conversation to follow, and where its messages continue from. */
let following: { id: string, since: () => number } | null = null
const hubHandlers = new Map<string, Set<Handler>>()
const conversationHandlers = new Map<string, Set<{ id: string, fn: Handler }>>()
const openHandlers = new Set<(conversation: string | null) => void>()

function dispatch(type: string, event: MessageEvent, conversation: string | null) {
  let data: unknown
  try {
    data = JSON.parse(event.data)
  } catch (e) {
    console.warn('kreteg: bad event', type, e)
    return
  }
  hubHandlers.get(type)?.forEach(fn => fn(data))
  // Each stream carries one conversation's events, so they reach only the pages showing that conversation. A page
  // that has just opened may still be on the previous page's stream for a moment.
  if (conversation) conversationHandlers.get(type)?.forEach(h => h.id === conversation && h.fn(data))
}

/**
 * (Re)opens GET /api/events, following `following` if set. EventSource reconnects on its own with Last-Event-ID,
 * which the server prefers over `since`. It gives up for good on an HTTP error, so a new one is opened from the latest
 * seq. After MAX_FAILURES errors in a row the app falls back to polling and tries the stream again every RETRY_MS.
 */
function connect() {
  clearTimeout(timer)
  source?.close()
  const conversation = following?.id ?? null
  const query = following ? `?conversation=${encodeURIComponent(following.id)}&since=${following.since()}` : ''
  const es = new EventSource(`/api/events${query}`)
  source = es
  es.onopen = () => {
    failures = 0
    polling.value = false
    openHandlers.forEach(fn => fn(conversation))
  }
  es.onerror = () => {
    if (source !== es) return
    failures++
    if (failures >= MAX_FAILURES) {
      es.close()
      polling.value = true
      timer = setTimeout(connect, RETRY_MS)
    } else if (es.readyState === EventSource.CLOSED) {
      timer = setTimeout(connect, RECONNECT_MS)
    }
  }
  for (const type of ['conversation', 'participant', 'message', 'status', 'receipt', 'members']) {
    es.addEventListener(type, e => dispatch(type, e as MessageEvent, conversation))
  }
}

/** Removes a handler when the component or scope that added it goes away. */
function disposable(remove: () => void) {
  if (getCurrentScope()) onScopeDispose(remove)
  return remove
}

/**
 * The one event stream of this tab. Started by the hub plugin; pages subscribe to the events they show, and the
 * conversation page says which conversation the stream follows.
 */
export function useHub() {
  return {
    polling: readonly(polling),

    start() {
      if (!source) connect()
    },

    /** Follows a conversation from `since`; the stream reopens with it. */
    follow(id: string, since: () => number) {
      clearTimeout(unfollowTimer)
      const same = following?.id === id
      following = { id, since }
      // Back on the conversation just left: the stream still follows it, and the page has loaded what it missed
      if (!same) connect()
    },

    /** Stops following `id`, unless another page has followed something else since. */
    unfollow(id: string) {
      if (following?.id !== id) return
      clearTimeout(unfollowTimer)
      unfollowTimer = setTimeout(() => {
        if (following?.id !== id) return
        following = null
        connect()
      }, UNFOLLOW_MS)
    },

    on<K extends keyof HubEvents>(type: K, fn: (data: HubEvents[K]) => void) {
      const set = hubHandlers.get(type) ?? new Set()
      hubHandlers.set(type, set.add(fn))
      return disposable(() => set.delete(fn))
    },

    /** Events of conversation `id`, delivered only while the stream follows it. */
    onConversation<K extends keyof ConversationEvents>(id: string, type: K, fn: (data: ConversationEvents[K]) => void) {
      const set = conversationHandlers.get(type) ?? new Set()
      const entry = { id, fn }
      conversationHandlers.set(type, set.add(entry))
      return disposable(() => set.delete(entry))
    },

    /**
     * A connection opened, following `conversation` (or none). Only messages and statuses are replayed on connect,
     * so whoever shows anything else reloads it here.
     */
    onOpen(fn: (conversation: string | null) => void) {
      openHandlers.add(fn)
      return disposable(() => openHandlers.delete(fn))
    },
  }
}
