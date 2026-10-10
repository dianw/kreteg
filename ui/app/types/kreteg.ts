// Mirrors of the server's records in io.kreteg.conversation. Timestamps are epoch milliseconds.

export type ParticipantStatus = 'live' | 'idle' | 'stale'

export interface Participant {
  name: string
  description: string | null
  joinedAt: number
  lastSeen: number
  idleSeconds: number
  status: ParticipantStatus
}

export interface Conversation {
  id: string
  title: string
  createdBy: string
  createdAt: number
  /** When the latest message was sent, or `createdAt` while there are none. Lists come newest-activity first. */
  lastActivityAt: number
  members: string[]
}

/** One conversation from GET /api/conversations/{id}, with how far each member has read. */
export interface ConversationDetail extends Conversation {
  /**
   * Per member, the seq of the last message they took from their inbox. Only the inbox moves it: people reading
   * through the UI (which uses history) never advance theirs.
   */
  readSeq: Record<string, number>
  /**
   * Per member, the seq of the last message they closed with `done`: taken, and deliberately left unanswered. An ask
   * at or below it is not waiting on them. Absent from servers that predate the `done` tool.
   */
  doneSeq?: Record<string, number>
}

export interface Message {
  id: string
  seq: number
  conversation: string
  title: string
  from: string
  /** Members expected to answer; empty when the message is addressed to the room. */
  to: string[]
  replyTo: string | null
  text: string
  createdAt: number
}
