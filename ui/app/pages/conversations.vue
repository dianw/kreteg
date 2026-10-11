<script setup lang="ts">
import type { Conversation } from '~/types/kreteg'

const route = useRoute()
const api = useKretegApi()
const identity = useIdentity()
// Kept current by the event stream, latest activity first
const conversations = useConversations()
const participants = useParticipants()
const scope = ref<'all' | 'mine'>('all')
const search = ref('')
const activeId = computed(() => route.params.id as string | undefined)

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  const me = identity.name.value
  return conversations.list.value.filter(c =>
    (scope.value === 'all' || !me || c.members.includes(me))
    && (!q || c.title.toLowerCase().includes(q) || c.members.some(m => m.toLowerCase().includes(q))))
})

function memberLine(c: Conversation) {
  return c.members.map(m => m === identity.name.value ? 'You' : m).join(', ')
}

// New conversation
const createOpen = ref(false)
const title = ref('')
const members = ref<string[]>([])
const others = ref<string[]>([])
const creating = ref(false)

watch(createOpen, (open) => {
  if (!open) return
  title.value = ''
  members.value = []
  others.value = participants.list.value.map(p => p.name).filter(n => n !== identity.name.value)
})

async function create() {
  creating.value = true
  try {
    const conversation = await api.create(identity.name.value, title.value.trim(), members.value)
    createOpen.value = false
    conversations.put(conversation)
    await navigateTo(`/conversations/${conversation.id}`)
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <div class="flex h-full">
    <ListPane v-model:search="search" title="Chats" :active="!!activeId">
      <template #actions>
        <UModal v-model:open="createOpen" title="New conversation">
          <UTooltip :text="identity.name.value ? 'New conversation' : 'Choose who to post as first'">
            <UButton icon="i-lucide-square-pen" color="neutral" variant="ghost" aria-label="New conversation"
                     :disabled="!identity.name.value" />
          </UTooltip>
          <template #body>
            <form id="create-conversation" class="space-y-4" @submit.prevent="create">
              <UFormField label="Title" required>
                <UInput v-model="title" autofocus class="w-full" />
              </UFormField>
              <UFormField label="Members" :help="`You (${identity.name.value}) are added automatically.`">
                <USelectMenu v-model="members" :items="others" multiple class="w-full" placeholder="Pick participants" />
              </UFormField>
            </form>
          </template>
          <template #footer>
            <UButton type="submit" form="create-conversation" label="Create" :loading="creating"
                     :disabled="!title.trim()" />
          </template>
        </UModal>
      </template>

      <template v-if="identity.name.value" #filters>
        <FilterChips v-model="scope" :options="[{ label: 'All', value: 'all' }, { label: 'Mine', value: 'mine' }]" />
      </template>

      <ListRow v-for="c in filtered" :key="c.id" :to="`/conversations/${c.id}`" :title="c.title"
               :subtitle="memberLine(c)" :note="formatListTime(c.lastActivityAt)" :note-title="formatTime(c.lastActivityAt)"
               :selected="c.id === activeId" />
      <li v-if="!filtered.length" class="px-4 py-8 text-center text-sm text-muted">
        {{ search ? 'No matching conversations.' : 'No conversations yet.' }}
      </li>
    </ListPane>

    <section class="flex-1 min-w-0 flex-col" :class="activeId ? 'flex' : 'hidden md:flex'">
      <NuxtPage :page-key="r => r.fullPath" />
    </section>
  </div>
</template>
