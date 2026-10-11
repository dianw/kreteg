import type { Conversation, Participant, ParticipantStatus } from '~/types/kreteg'

/** The server's presence thresholds (Participant.of): live when seen within a minute, idle within 15 minutes. */
const LIVE_MS = 60_000
const IDLE_MS = 15 * 60_000
/** How often presence is recomputed, and how often lists are polled while the stream is down. */
const TICK_MS = 5000

/** Shared by every page: a clock that ticks so that presence and other ages move on while nothing else changes. */
const now = ref(Date.now())

/**
 * Participants as last sent, with when they arrived. `idleSeconds` in a record is as of when the server sent it, so
 * idleness is that plus the time since: it needs no agreement between the server's clock and the browser's.
 */
const participantRecords = ref(new Map<string, { p: Participant, receivedAt: number }>())
const conversationRecords = ref(new Map<string, Conversation>())
const participantsLoaded = ref(false)
const conversationsLoaded = ref(false)
let started = false

function putParticipant(p: Participant, receivedAt = Date.now()) {
  const held = participantRecords.value.get(p.name)
  // A list fetched before an event that overtook it is older news for that participant
  if (held && held.p.lastSeen > p.lastSeen && held.p.description === p.description) return
  participantRecords.value.set(p.name, { p, receivedAt })
}

function putConversation(c: Conversation) {
  const held = conversationRecords.value.get(c.id)
  if (held && held.lastActivityAt > c.lastActivityAt) return
  conversationRecords.value.set(c.id, c)
}

/**
 * Starts keeping participants and conversations current: fetched on every open of the stream, then updated by its
 * `participant` and `conversation` events, and polled only while the stream is down. Called once, by the hub plugin.
 */
export function startDirectory(api: ReturnType<typeof useKretegApi>) {
  if (started) return
  started = true
  const hub = useHub()

  async function loadParticipants() {
    const receivedAt = Date.now()
    const list = await api.participants()
    const names = new Set(list.map(p => p.name))
    for (const name of [...participantRecords.value.keys()]) if (!names.has(name)) participantRecords.value.delete(name)
    list.forEach(p => putParticipant(p, receivedAt))
    participantsLoaded.value = true
  }

  async function loadConversations() {
    const list = await api.conversations()
    list.forEach(putConversation)
    conversationsLoaded.value = true
  }

  function loadAll() {
    // Failures are reported by the API wrapper
    loadParticipants().catch(() => {})
    loadConversations().catch(() => {})
  }

  hub.on('participant', p => putParticipant(p))
  hub.on('conversation', putConversation)
  hub.onOpen(loadAll)
  watch(hub.polling, on => on && loadAll())

  setInterval(() => {
    now.value = Date.now()
    if (hub.polling.value && !document.hidden) loadAll()
  }, TICK_MS)
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && hub.polling.value) loadAll()
  })
}

function presence(idleMs: number): ParticipantStatus {
  return idleMs < LIVE_MS ? 'live' : idleMs < IDLE_MS ? 'idle' : 'stale'
}

/** Every participant, with `status` and `idleSeconds` as of now. */
export function useParticipants() {
  const byName = computed(() => {
    const out = new Map<string, Participant>()
    for (const { p, receivedAt } of participantRecords.value.values()) {
      const idleMs = Math.max(0, p.idleSeconds * 1000 + now.value - receivedAt)
      out.set(p.name, { ...p, idleSeconds: Math.floor(idleMs / 1000), status: presence(idleMs) })
    }
    return out
  })
  return {
    byName,
    /** Most recently seen first, as GET /api/participants orders them. */
    list: computed(() => [...byName.value.values()].sort((a, b) => b.lastSeen - a.lastSeen)),
    loaded: readonly(participantsLoaded),
  }
}

/** Every conversation, latest activity first. */
export function useConversations() {
  const list = computed(() => [...conversationRecords.value.values()].sort((a, b) => b.lastActivityAt - a.lastActivityAt))
  return {
    list,
    loaded: readonly(conversationsLoaded),
    /** Adds one the app has just created, before its event arrives. */
    put: putConversation,
  }
}

/** The shared clock, ticking every few seconds. */
export function useNow() {
  return readonly(now)
}
