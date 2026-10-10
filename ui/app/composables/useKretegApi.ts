import type { Conversation, ConversationDetail, Message, Participant } from '~/types/kreteg'

/**
 * The REST API in ConversationResource. The UI reads messages through history only: the inbox endpoint consumes
 * messages, so calling it would take them away from the agent they belong to.
 */
export function useKretegApi() {
  const toast = useToast()

  async function call<T>(request: () => Promise<T>): Promise<T> {
    try {
      return await request()
    } catch (e: any) {
      toast.add({ title: e?.data?.error ?? e?.message ?? 'Request failed', color: 'error' })
      throw e
    }
  }

  return {
    register: (name: string, description: string) =>
      call(() => $fetch<Participant>('/api/participants', { method: 'POST', body: { name, description } })),
    participants: () => call(() => $fetch<Participant[]>('/api/participants')),
    conversations: (member?: string) =>
      call(() => $fetch<Conversation[]>('/api/conversations', { query: member ? { member } : {} })),
    /** Null when there is no such conversation; that is a normal answer here, so it is not reported. */
    conversation: (id: string) =>
      call(() => $fetch<ConversationDetail>(`/api/conversations/${encodeURIComponent(id)}`)
        .catch((e) => {
          if (e?.statusCode === 404) return null
          throw e
        })),
    create: (from: string, title: string, members: string[]) =>
      call(() => $fetch<Conversation>('/api/conversations', { method: 'POST', body: { from, title, members } })),
    join: (id: string, name: string) =>
      call(() => $fetch<Conversation>(`/api/conversations/${encodeURIComponent(id)}/members`,
        { method: 'POST', body: { name } })),
    send: (id: string, from: string, text: string, to: string[], replyTo: string | null) =>
      call(() => $fetch<Message>(`/api/conversations/${encodeURIComponent(id)}/messages`,
        { method: 'POST', body: { from, text, to, replyTo } })),
    history: (id: string, since: number, limit = 100) =>
      call(() => $fetch<Message[]>(`/api/conversations/${encodeURIComponent(id)}/messages`,
        { query: { since, limit } })),
    /** The last `limit` messages before seq `before`, oldest first; empty once the start is reached. */
    historyBefore: (id: string, before: number, limit = 100) =>
      call(() => $fetch<Message[]>(`/api/conversations/${encodeURIComponent(id)}/messages`,
        { query: { before, limit } })),
  }
}
