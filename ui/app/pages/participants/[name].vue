<script setup lang="ts">
import type { Conversation, Participant } from '~/types/kreteg'

const route = useRoute()
const name = route.params.name as string
const api = useKretegApi()
const identity = useIdentity()

const participant = ref<Participant | null>(null)
const notFound = ref(false)
const conversations = ref<Conversation[]>([])

usePolling(async () => {
  const [all, theirs] = await Promise.all([api.participants(), api.conversations(name)])
  participant.value = all.find(p => p.name === name) ?? null
  notFound.value = !participant.value
  conversations.value = theirs.sort((a, b) => b.createdAt - a.createdAt)
}, 5000)

const isMe = computed(() => name === identity.name.value)

function memberLine(c: Conversation) {
  return c.members.map(m => m === identity.name.value ? 'You' : m).join(', ')
}

const starting = ref(false)

/** Opens the one-to-one conversation with this participant, starting it if there is none yet. */
async function message() {
  const me = identity.name.value
  const existing = conversations.value.find(c =>
    c.members.length === 2 && c.members.includes(me) && c.members.includes(name))
  if (existing) return navigateTo(`/conversations/${existing.id}`)
  starting.value = true
  try {
    const conversation = await api.create(identity.name.value, `${identity.name.value} & ${name}`, [name])
    await navigateTo(`/conversations/${conversation.id}`)
  } finally {
    starting.value = false
  }
}
</script>

<template>
  <EmptyPane v-if="notFound" title="Participant not found">
    <UButton to="/participants" label="Back to participants" variant="link" />
  </EmptyPane>

  <template v-else>
    <PaneHeader back="/participants" :title="name"
                :subtitle="participant ? (participant.status === 'live' ? 'online' : `last seen ${formatIdle(participant.idleSeconds)} ago`) : ''"
                :subtitle-title="participant ? formatTime(participant.lastSeen) : ''" />

    <div class="flex-1 overflow-y-auto bg-muted chat-wallpaper">
      <div class="max-w-2xl mx-auto px-4 py-8 space-y-4">
        <div class="rounded-lg bg-default shadow-xs p-6 flex flex-col items-center text-center gap-2">
          <UChip :color="participant ? STATUS_COLOR[participant.status] : 'neutral'" position="bottom-right" inset size="3xl">
            <UAvatar :text="initials(name)" size="3xl" class="bg-primary/15 text-primary" />
          </UChip>
          <h2 class="text-xl font-semibold break-all">{{ name }}<span v-if="isMe" class="text-muted font-normal"> (You)</span></h2>
          <UBadge v-if="participant" :color="STATUS_COLOR[participant.status]" variant="subtle" :label="participant.status" />
          <p v-if="participant?.description" class="text-sm text-muted">{{ participant.description }}</p>
          <UButton v-if="identity.name.value && !isMe" label="Message" icon="i-lucide-message-circle" class="mt-2"
                   :loading="starting" @click="message" />
        </div>

        <dl v-if="participant" class="rounded-lg bg-default shadow-xs divide-y divide-default text-sm">
          <div class="flex justify-between gap-4 px-4 py-3">
            <dt class="text-muted">Joined</dt>
            <dd>{{ formatTime(participant.joinedAt) }}</dd>
          </div>
          <div class="flex justify-between gap-4 px-4 py-3">
            <dt class="text-muted">Last seen</dt>
            <dd :title="formatTime(participant.lastSeen)">{{ formatIdle(participant.idleSeconds) }} ago</dd>
          </div>
        </dl>

        <div class="rounded-lg bg-default shadow-xs overflow-hidden">
          <h3 class="px-4 pt-3 pb-1 text-sm text-muted">
            {{ conversations.length }} {{ conversations.length === 1 ? 'conversation' : 'conversations' }}
          </h3>
          <ul>
            <ListRow v-for="c in conversations" :key="c.id" :to="`/conversations/${c.id}`" :title="c.title"
                     :subtitle="memberLine(c)" :note="formatListTime(c.createdAt)"
                     :note-title="formatTime(c.createdAt)" />
          </ul>
        </div>
      </div>
    </div>
  </template>
</template>
