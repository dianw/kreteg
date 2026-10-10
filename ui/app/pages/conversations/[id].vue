<script setup lang="ts">
import type { ConversationDetail, Message, Participant } from '~/types/kreteg'

const route = useRoute()
const id = route.params.id as string
const api = useKretegApi()
const identity = useIdentity()

const conversation = ref<ConversationDetail | null>(null)
const notFound = ref(false)
const messages = ref<Message[]>([])
const byId = computed(() => new Map(messages.value.map(m => [m.id, m])))
const isMember = computed(() => !!identity.name.value && !!conversation.value?.members.includes(identity.name.value))
const scroller = useTemplateRef<HTMLElement>('scroller')

// Members and read positions change while the conversation is open, so they are polled as often as the messages
usePolling(async () => {
  conversation.value = await api.conversation(id)
  notFound.value = !conversation.value
}, 2000)

// Statuses tell whether an agent that owes an answer is still around
const participants = ref(new Map<string, Participant>())
usePolling(async () => {
  participants.value = new Map((await api.participants()).map(p => [p.name, p]))
}, 5000)

/** Agents read through their inbox, so they have read positions; people read through the UI's history and don't. */
function isAgent(name: string) {
  const p = participants.value.get(name)
  return !!p && p.description !== PERSON_DESCRIPTION
}

function nearBottom() {
  const el = scroller.value
  return !el || el.scrollHeight - el.scrollTop - el.clientHeight < 120
}

function scrollToBottom() {
  scroller.value?.scrollTo({ top: scroller.value.scrollHeight })
}

/**
 * The message the view is held on while older pages load above it, and its distance from the top of the scroller.
 * Bubbles grow after they are added (Markdown is parsed asynchronously), and Safari has no CSS scroll anchoring, so
 * the view is held by hand.
 */
let anchor: { id: string, top: number } | null = null

function offsetOf(messageId: string) {
  const el = document.querySelector(`[data-message-id="${messageId}"]`)
  const box = scroller.value
  return el && box ? el.getBoundingClientRect().top - box.getBoundingClientRect().top : null
}

function holdAnchor() {
  if (!anchor || !scroller.value) return
  const top = offsetOf(anchor.id)
  if (top !== null) scroller.value.scrollTop += top - anchor.top
}

// Whether the view follows the newest message: true while the reader is at the bottom. Bubbles keep growing after
// they are added, so the view is moved down whenever the thread changes size.
let pinned = true
function onScroll() {
  pinned = nearBottom()
  if (anchor) anchor.top = offsetOf(anchor.id) ?? anchor.top
  maybeLoadOlder()
}

const thread = useTemplateRef<HTMLElement>('thread')
let resizes: ResizeObserver | undefined
watch(thread, (el) => {
  resizes?.disconnect()
  if (!el) return
  resizes = new ResizeObserver(() => {
    if (pinned) scrollToBottom()
    else holdAnchor()
  })
  resizes.observe(el)
})
onBeforeUnmount(() => resizes?.disconnect())

// A conversation opens on its latest page; older pages load as the reader scrolls up
const PAGE = 50
const LATEST = Number.MAX_SAFE_INTEGER
const loaded = ref(false)
const hasOlder = ref(false)
const loadingOlder = ref(false)

async function loadOlder() {
  const first = messages.value[0]
  if (!first || !hasOlder.value || loadingOlder.value) return
  loadingOlder.value = true
  try {
    const page = await api.historyBefore(id, first.seq, PAGE)
    hasOlder.value = page.length === PAGE
    anchor = { id: first.id, top: offsetOf(first.id) ?? 0 }
    messages.value.unshift(...page)
    await nextTick()
    if (pinned) scrollToBottom()
    else holdAnchor()
  } finally {
    loadingOlder.value = false
  }
}

/** Loads the page above when the reader is near the top, or when the thread doesn't fill the view yet. */
function maybeLoadOlder() {
  const el = scroller.value
  if (el && el.scrollTop < 300 && hasOlder.value && !loadingOlder.value) loadOlder().then(() => nextTick(maybeLoadOlder)).catch(() => {})
}

// History, not inbox: reading must not consume anyone's messages
const history = usePolling(async () => {
  if (!loaded.value) {
    const page = await api.historyBefore(id, LATEST, PAGE)
    messages.value = page
    hasOlder.value = page.length === PAGE
    loaded.value = true
    await nextTick()
    scrollToBottom()
    maybeLoadOlder()
    return
  }
  let page: Message[]
  do {
    const since = messages.value.at(-1)?.seq ?? 0
    page = await api.history(id, since, PAGE)
    if (page.length) {
      if (page.some(m => m.from === identity.name.value)) pinned = true
      messages.value.push(...page)
    }
  } while (page.length === PAGE)
}, 2000)

/**
 * Messages you sent that history has not returned yet, shown at the end of the thread straight away. Once the server
 * answers, `sent` holds its record; the entry is dropped when that id arrives through history. The server's message
 * is never pushed into `messages` directly: history pages from the last seq, so a gap before it would be skipped.
 */
interface Outgoing {
  key: string
  text: string
  to: string[]
  replyTo: string | null
  createdAt: number
  sent: Message | null
  failed: boolean
}
const outgoing = ref<Outgoing[]>([])
let outgoingCount = 0
/** Row key per message id, so a bubble keeps its key (and its rendered Markdown) when history takes it over. */
const rowKeys = new Map<string, string>()
const shownOutgoing = computed(() => outgoing.value.filter(o => !o.sent || !byId.value.has(o.sent.id)))
watch(byId, (ids) => {
  outgoing.value = outgoing.value.filter(o => !o.sent || !ids.has(o.sent.id))
})

/** An outgoing message as the bubble shows it until the server's record is known. */
function draftMessage(o: Outgoing): Message {
  return o.sent ?? {
    id: o.key, seq: Number.MAX_SAFE_INTEGER, conversation: id, title: conversation.value?.title ?? '',
    from: identity.name.value, to: o.to, replyTo: o.replyTo, text: o.text, createdAt: o.createdAt,
  }
}

// Sends go out one at a time, so the server orders them as they were typed
let sendQueue: Promise<unknown> = Promise.resolve()
function deliver(o: Outgoing) {
  o.failed = false
  sendQueue = sendQueue.then(async () => {
    try {
      o.sent = await api.send(id, identity.name.value, o.text, o.to, o.replyTo)
      rowKeys.set(o.sent.id, o.key)
      history.refresh()
    } catch {
      // Reported by the API wrapper; the bubble offers retry
      o.failed = true
    }
  })
}

function discard(o: Outgoing) {
  outgoing.value = outgoing.value.filter(x => x !== o)
}

/**
 * Read receipts for your own message, counting the agents it asks to answer, or every agent when it names nobody.
 * Null when no agent is addressed, since people never mark anything read.
 */
function receipt(m: Message) {
  const c = conversation.value
  if (!c) return null
  const addressees = (m.to.length ? m.to : c.members).filter(n => n !== m.from && isAgent(n))
  if (!addressees.length) return null
  const unread = addressees.filter(n => (c.readSeq[n] ?? 0) < m.seq)
  return unread.length
    ? { read: false, title: `Not received yet by ${unread.join(', ')}` }
    : { read: true, title: `Received by ${addressees.join(', ')}` }
}

/** How long after being asked an agent still counts as working on it, rather than gone quiet. */
const WORKING_WINDOW_MS = 15 * 60_000

/**
 * Agents that have taken a message asking them to answer and not posted since. The watch script takes a message
 * as soon as it arrives and wakes the agent with it, so having it means working on it. An agent that closed the ask
 * with `done` chose not to answer, so it isn't waited on.
 */
const waitingOn = computed(() => {
  const c = conversation.value
  if (!c) return []
  return c.members.filter(isAgent).flatMap((name) => {
    let ask: Message | undefined
    for (let i = messages.value.length - 1; i >= 0 && !ask; i--) {
      const m = messages.value[i]!
      if (m.from === name) return []
      if (m.to.includes(name)) ask = m
    }
    if (!ask || (c.readSeq[name] ?? 0) < ask.seq || (c.doneSeq?.[name] ?? 0) >= ask.seq) return []
    const working = participants.value.get(name)?.status === 'live' && Date.now() - ask.createdAt < WORKING_WINDOW_MS
    return [{ name, working }]
  })
})
const workingLine = computed(() => {
  const names = waitingOn.value.filter(w => w.working).map(w => w.name)
  return names.length ? `${names.join(', ')} ${names.length === 1 ? 'is' : 'are'} working…` : ''
})

// Members' @names are highlighted in message text. The names are part of the cache key, so a member joining or a
// change of who you post as renders the messages again rather than reusing the earlier parse.
const markdown = computed(() => messageMarkdown(conversation.value?.members ?? [], identity.name.value))
const markdownKey = computed(() => `${identity.name.value}|${conversation.value?.members.join(',') ?? ''}`)

/** One bubble per message, with what is needed to group consecutive messages like a messenger app. */
const rows = computed(() => {
  const outgoingBy = new Map(shownOutgoing.value.map(o => [o.key, o]))
  const all = [...messages.value, ...shownOutgoing.value.map(draftMessage)]
  return all.map((m, i) => toRow(m, all[i - 1], all[i + 1], outgoingBy.get(rowKeys.get(m.id) ?? m.id)))
})

function toRow(m: Message, prev: Message | undefined, next: Message | undefined, out: Outgoing | undefined) {
  const newDay = !prev || !isSameDay(prev.createdAt, m.createdAt)
  const mine = m.from === identity.name.value
  const unsent = !!out && !out.sent
  return {
    m,
    key: rowKeys.get(m.id) ?? m.id,
    /** Set while the message is yours and history has not returned it yet. */
    out,
    /** Not yet accepted by the server, so it has no id to reply to or read receipts. */
    unsent,
    mine,
    newDay,
    firstOfGroup: newDay || prev?.from !== m.from,
    // Recipients the text does not already @mention, shown above it
    unmentioned: m.to.filter(t => !mentionedIn(m.text, [t]).length),
    receipt: mine && !unsent ? receipt(m) : null,
    lastOfGroup: !next || next.from !== m.from || !isSameDay(next.createdAt, m.createdAt),
    chat: { id: m.id, role: mine ? 'user' as const : 'assistant' as const, parts: [{ type: 'text' as const, text: m.text }] },
  }
}

function bubbleUi(row: ReturnType<typeof toRow>) {
  return {
    root: 'scroll-mt-20',
    container: [row.lastOfGroup ? 'pb-3' : 'pb-0.5', 'max-w-[85%] md:max-w-[70%]'],
    content: [
      'relative space-y-1 px-2.5 py-1.5 min-h-0 rounded-lg shadow-xs text-sm text-default',
      row.mine ? 'bg-primary-100 dark:bg-primary-900/60' : 'bg-default ring-0',
      row.firstOfGroup && (row.mine ? 'rounded-tr-none' : 'rounded-tl-none'),
    ],
  }
}

// Composer
const text = ref('')
const replyTo = ref<Message | null>(null)
const recipients = computed(() => conversation.value?.members.filter(m => m !== identity.name.value) ?? [])
const memberLine = computed(() =>
  conversation.value?.members.map(m => m === identity.name.value ? 'You' : m).join(', ') ?? '')

const prompt = useTemplateRef('prompt')
const mentions = useMentions(text, recipients, () => prompt.value?.textareaRef)

/** Quotes the message and, unless it is your own, mentions its sender so they are asked to answer. */
function reply(message: Message) {
  replyTo.value = message
  if (message.from !== identity.name.value && !mentionedIn(text.value, [message.from]).length) {
    text.value = `@${message.from} ${text.value}`
  }
  nextTick(() => prompt.value?.textareaRef?.focus())
}

/**
 * Double-clicking a bubble replies to it, as the reply button does. Links and buttons keep their own behaviour, and
 * code keeps double-click as word selection, since ids, paths and commands are what readers copy from a message.
 * Elsewhere the word the double-click selected is deselected so it doesn't look like a copy target.
 */
function onBubbleDblclick(event: MouseEvent, message: Message) {
  if (!isMember.value) return
  const target = event.target as Element | null
  if (!target?.closest('[data-slot="content"]') || target.closest('a, button, pre, code')) return
  window.getSelection()?.removeAllRanges()
  reply(message)
}

// Copy puts the message's Markdown source, not the rendered text, on the clipboard
const toast = useToast()
const copiedId = ref<string | null>(null)
let copiedTimer: ReturnType<typeof setTimeout> | undefined
async function copy(message: Message) {
  try {
    await navigator.clipboard.writeText(message.text)
    copiedId.value = message.id
    clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => (copiedId.value = null), 1500)
  } catch (e) {
    toast.add({ title: 'Could not copy', description: e instanceof Error ? e.message : String(e), color: 'error' })
  }
}
onBeforeUnmount(() => clearTimeout(copiedTimer))

/** Shows the message at once and clears the composer; the server's answer replaces the draft when it comes. */
function send() {
  if (!text.value.trim()) return
  outgoing.value.push({
    key: `local-${++outgoingCount}`,
    text: text.value,
    // Members mentioned as @name are the ones expected to answer
    to: mentionedIn(text.value, recipients.value),
    replyTo: replyTo.value?.id ?? null,
    createdAt: Date.now(),
    sent: null,
    failed: false,
  })
  text.value = ''
  replyTo.value = null
  pinned = true
  // The reactive copy, so the bubble follows its state
  deliver(outgoing.value.at(-1)!)
}

// Joining without a name yet registers one first, so a person gets in with one step
const joinName = ref('human')
const joining = ref(false)

async function join() {
  joining.value = true
  try {
    if (!identity.name.value) await identity.set(joinName.value)
    // Already a member under that name: nothing to join, the composer appears
    if (!isMember.value) {
      await api.join(id, identity.name.value)
      conversation.value = await api.conversation(id)
    }
  } finally {
    joining.value = false
  }
}

/** Scrolls to a message, loading older pages first when it is above the ones loaded. */
async function jumpTo(messageId: string) {
  while (!byId.value.has(messageId) && hasOlder.value) await loadOlder()
  await nextTick()
  document.querySelector(`[data-message-id="${messageId}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

/** The first line of a message as plain text, for reply quotes: Markdown markers are dropped. */
function firstLine(message: Message) {
  const line = (message.text.split('\n').find(l => l.trim() && !l.trim().startsWith('```')) ?? '')
    .replace(/^\s*(#{1,6}\s+|>\s*|[-*+]\s+|\d+[.)]\s+)/, '')
    .replace(/(\*\*|\*|`)(.+?)\1/g, '$2')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
  return line.length > 120 ? line.slice(0, 120) + '…' : line
}
</script>

<template>
  <EmptyPane v-if="notFound" title="Conversation not found">
    <UButton to="/conversations" label="Back to conversations" variant="link" />
  </EmptyPane>

  <template v-else>
    <PaneHeader back="/conversations" :title="conversation?.title ?? '…'" :subtitle="workingLine || memberLine"
                :subtitle-title="conversation ? `Started by ${conversation.createdBy}, ${formatTime(conversation.createdAt)}` : ''"
                :ui="{ subtitle: workingLine ? 'text-primary' : '' }" />

    <div ref="scroller" class="flex-1 overflow-y-auto [overflow-anchor:none] bg-muted chat-wallpaper"
         @scroll.passive="onScroll">
      <div ref="thread" class="max-w-4xl mx-auto px-2 md:px-6 py-4">
        <p v-if="loaded && !messages.length" class="text-center text-sm text-muted py-8">No messages yet.</p>
        <!-- Always the same height while there is more to load, so the spinner showing doesn't move the messages -->
        <p v-else-if="hasOlder" class="h-8 flex items-center justify-center">
          <UIcon name="i-lucide-loader-circle" class="size-4 text-muted animate-spin"
                 :class="loadingOlder ? '' : 'invisible'" :aria-hidden="!loadingOlder" aria-label="Loading earlier messages" />
        </p>

        <UChatMessages :should-scroll-to-bottom="false" compact
                       :ui="{ root: 'gap-0 px-0', viewport: 'sticky bottom-4 inset-x-0 h-0 z-10', autoScroll: 'bottom-0 shadow' }">
          <template v-for="row in rows" :key="row.key">
            <div v-if="row.newDay" class="flex justify-center my-3">
              <span class="text-xs text-muted bg-default rounded-md px-3 py-1 shadow-xs">
                {{ formatDay(row.m.createdAt) }}
              </span>
            </div>

            <UChatMessage v-bind="row.chat" :data-message-id="row.m.id" :side="row.mine ? 'right' : 'left'"
                          variant="soft" compact :ui="bubbleUi(row)" @dblclick="!row.unsent && onBubbleDblclick($event, row.m)">
              <template #content>
                <p v-if="!row.mine && row.firstOfGroup" class="text-xs font-semibold" :class="nameColor(row.m.from)">
                  {{ row.m.from }}
                </p>

                <button v-if="row.m.replyTo" type="button"
                        class="block w-full text-left rounded-md border-l-4 border-primary bg-black/5 dark:bg-white/5 px-2 py-1 text-xs"
                        @click="jumpTo(row.m.replyTo)">
                  <template v-if="byId.get(row.m.replyTo)">
                    <span class="block font-semibold" :class="nameColor(byId.get(row.m.replyTo)!.from)">
                      {{ byId.get(row.m.replyTo)!.from === identity.name.value ? 'You' : byId.get(row.m.replyTo)!.from }}
                    </span>
                    <span class="block text-muted truncate">{{ firstLine(byId.get(row.m.replyTo)!) }}</span>
                  </template>
                  <span v-else class="text-muted italic">An earlier message</span>
                </button>

                <p v-if="row.unmentioned.length" class="text-xs font-medium text-primary">
                  <span v-for="t in row.unmentioned" :key="t" class="mr-1.5">@{{ t === identity.name.value ? 'You' : t }}</span>
                </p>

                <MDC :value="row.m.text" :cache-key="`${row.key}|${markdownKey}`" :parser-options="markdown" class="kreteg-md break-words" />
                <!-- Copy and reply sit on the time line, shown on hover (always on touch screens, which have none); negative
                     margins keep the line's height -->
                <div class="flex items-center justify-end gap-1 text-[11px] leading-none text-muted">
                  <div class="flex -my-1.5 opacity-0 group-hover/message:opacity-100 focus-within:opacity-100 [@media(hover:none)]:opacity-100">
                    <UButton :icon="copiedId === row.m.id ? 'i-lucide-check' : 'i-lucide-copy'" size="xs" color="neutral"
                             variant="ghost" class="p-1" :ui="{ leadingIcon: 'size-3.5' }"
                             :aria-label="copiedId === row.m.id ? 'Copied' : 'Copy Markdown'"
                             :title="copiedId === row.m.id ? 'Copied' : 'Copy Markdown'" @click="copy(row.m)" />
                    <UButton v-if="isMember && !row.unsent" icon="i-lucide-reply" size="xs" color="neutral" variant="ghost"
                             class="p-1" :ui="{ leadingIcon: 'size-3.5' }"
                             aria-label="Reply" title="Reply (or double-click the message)" @click="reply(row.m)" />
                  </div>
                  <template v-if="row.out?.failed">
                    <span class="text-error">Not sent</span>
                    <UButton label="Retry" size="xs" color="error" variant="link" class="p-0" @click="deliver(row.out)" />
                    <UButton label="Discard" size="xs" color="neutral" variant="link" class="p-0" @click="discard(row.out)" />
                  </template>
                  <span :title="formatTime(row.m.createdAt)">{{ formatClock(row.m.createdAt) }}</span>
                  <UIcon v-if="row.unsent && !row.out?.failed" name="i-lucide-clock" class="size-3" title="Sending…" />
                  <UIcon v-if="row.receipt" :name="row.receipt.read ? 'i-lucide-check-check' : 'i-lucide-check'"
                         :class="['size-3.5', row.receipt.read ? 'text-info' : '']" :title="row.receipt.title" />
                </div>
              </template>
            </UChatMessage>
          </template>

          <div v-for="w in waitingOn" :key="`waiting-${w.name}`" class="pb-3">
            <div class="inline-block rounded-lg rounded-tl-none bg-default shadow-xs px-2.5 py-1.5 space-y-1">
              <p class="text-xs font-semibold" :class="nameColor(w.name)">{{ w.name }}</p>
              <div v-if="w.working" class="flex items-center gap-2 text-xs text-muted">
                <span aria-hidden="true" class="h-4 flex items-center gap-1 *:size-1.5 *:rounded-full *:bg-(--ui-text-dimmed)
                  motion-safe:[&>*:nth-child(1)]:animate-[bounce_1s_infinite]
                  motion-safe:[&>*:nth-child(2)]:animate-[bounce_1s_0.15s_infinite]
                  motion-safe:[&>*:nth-child(3)]:animate-[bounce_1s_0.3s_infinite]"><span /><span /><span /></span>
                working on it
              </div>
              <p v-else class="text-xs text-muted italic">went quiet without answering</p>
            </div>
          </div>
        </UChatMessages>
      </div>
    </div>

    <footer v-if="conversation" class="shrink-0 border-t border-default bg-elevated/40 px-3 pt-2 pb-[max(0.5rem,env(safe-area-inset-bottom))]">
      <div class="max-w-4xl mx-auto">
        <form v-if="!identity.name.value" class="flex flex-wrap items-center justify-center gap-2 py-1"
              @submit.prevent="join">
          <span class="text-sm text-muted">Join this conversation as</span>
          <UInput v-model="joinName" placeholder="Your name" aria-label="Your name" class="w-40" />
          <UButton type="submit" label="Join" icon="i-lucide-log-in" :loading="joining" :disabled="!joinName.trim()" />
        </form>
        <div v-else-if="!isMember" class="flex flex-wrap items-center justify-center gap-3 py-1">
          <span class="text-sm text-muted">You ({{ identity.name.value }}) are not a member.</span>
          <UButton label="Join" icon="i-lucide-log-in" :loading="joining" @click="join" />
        </div>
        <div v-else class="relative">
        <ul v-if="mentions.open.value" role="listbox" aria-label="Mention a member"
            class="absolute bottom-full left-0 mb-2 w-64 z-20 rounded-lg bg-default shadow-lg ring ring-default py-1">
          <li v-for="(name, i) in mentions.suggestions.value" :key="name" role="option" :aria-selected="i === mentions.active.value"
              class="flex items-center gap-2 px-3 py-1.5 cursor-pointer text-sm"
              :class="i === mentions.active.value ? 'bg-elevated' : ''"
              @mousedown.prevent="mentions.pick(name)" @mouseenter="mentions.active.value = i">
            <UAvatar :text="initials(name)" size="2xs" class="bg-primary/15 text-primary" />
            <span class="truncate" :class="nameColor(name)">{{ name }}</span>
          </li>
        </ul>
        <!-- 16px text below md: iOS zooms the page into a smaller input when it gets focus -->
        <UChatPrompt ref="prompt" v-model="text" placeholder="Type a message, @ to mention (Markdown supported)"
                     variant="subtle" :maxrows="8" :submit-on-enter="!mentions.open.value"
                     :ui="{ base: 'text-base md:text-sm' }" class="bg-default" @submit="send"
                     @input="mentions.onInput" @keydown="mentions.onKeydown" @keyup="mentions.onCaretMove"
                     @click="mentions.onCaretMove" @blur="mentions.close">
          <template v-if="replyTo" #header>
            <div class="flex items-center gap-2 w-full rounded-md border-l-4 border-primary bg-elevated px-2 py-1 text-xs">
              <div class="min-w-0 flex-1">
                <span class="block font-semibold" :class="nameColor(replyTo.from)">
                  {{ replyTo.from === identity.name.value ? 'You' : replyTo.from }}
                </span>
                <span class="block text-muted truncate">{{ firstLine(replyTo) }}</span>
              </div>
              <UButton icon="i-lucide-x" size="xs" color="neutral" variant="ghost" aria-label="Cancel reply"
                       @click="replyTo = null" />
            </div>
          </template>
          <template #footer>
            <span class="text-xs text-muted flex-1">Enter to send, Shift+Enter for a new line</span>
            <UChatPromptSubmit :disabled="!text.trim()" class="rounded-full" />
          </template>
        </UChatPrompt>
        </div>
      </div>
    </footer>
  </template>
</template>
