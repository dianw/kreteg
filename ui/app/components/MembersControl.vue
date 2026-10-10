<script setup lang="ts">
import type { ConversationDetail, Participant } from '~/types/kreteg'

/** The members of a conversation, and, for a member, a picker to add other participants to it. */
const props = defineProps<{
  conversation: ConversationDetail
  participants: Map<string, Participant>
  canAdd: boolean
}>()
const emit = defineEmits<{ added: [] }>()

const api = useKretegApi()
const identity = useIdentity()
const open = ref(false)
const picked = ref<string[]>([])
const adding = ref(false)

// Participants still around come first, as on the participants page
const STATUS_ORDER = { live: 0, idle: 1, stale: 2 } as const
const candidates = computed(() => [...props.participants.values()]
  .filter(p => !props.conversation.members.includes(p.name))
  .sort((a, b) => STATUS_ORDER[a.status] - STATUS_ORDER[b.status] || a.name.localeCompare(b.name))
  .map(p => p.name))

watch(open, (isOpen) => {
  if (isOpen) picked.value = []
})

async function add() {
  adding.value = true
  try {
    for (const name of picked.value) await api.join(props.conversation.id, name)
    picked.value = []
    emit('added')
  } finally {
    adding.value = false
  }
}
</script>

<template>
  <UPopover v-model:open="open" :content="{ align: 'end' }">
    <UTooltip text="Members">
      <UButton icon="i-lucide-users" color="neutral" variant="ghost" aria-label="Members" />
    </UTooltip>
    <template #content>
      <div class="p-3 space-y-3 w-72">
        <p class="text-xs font-semibold text-muted">{{ conversation.members.length }} members</p>
        <ul class="space-y-2 max-h-64 overflow-y-auto">
          <li v-for="name in conversation.members" :key="name" class="flex items-center gap-2 text-sm">
            <UChip :color="STATUS_COLOR[participants.get(name)?.status ?? 'stale']" position="bottom-right" inset>
              <UAvatar :text="initials(name)" size="sm" class="bg-primary/15 text-primary" />
            </UChip>
            <span class="truncate" :class="nameColor(name)">{{ name === identity.name.value ? `${name} (You)` : name }}</span>
          </li>
        </ul>
        <form v-if="canAdd" class="space-y-2 border-t border-default pt-3" @submit.prevent="add">
          <USelectMenu v-model="picked" :items="candidates" multiple class="w-full" placeholder="Add participants"
                       :disabled="!candidates.length" />
          <p class="text-xs text-muted">They can read the whole history. @mention them to ask them something.</p>
          <div class="flex justify-end">
            <UButton type="submit" label="Add" icon="i-lucide-user-plus" :loading="adding" :disabled="!picked.length" />
          </div>
        </form>
      </div>
    </template>
  </UPopover>
</template>
