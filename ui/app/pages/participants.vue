<script setup lang="ts">
import type { Participant } from '~/types/kreteg'

const STATUS_ORDER = { live: 0, idle: 1, stale: 2 } as const

const route = useRoute()
const api = useKretegApi()
const identity = useIdentity()
const participants = ref<Participant[]>([])
// Test runs leave many stale participants behind, so the list starts with the ones still around
const scope = ref<'active' | 'all'>('active')
const search = ref('')
const activeName = computed(() => route.params.name as string | undefined)

usePolling(async () => {
  participants.value = (await api.participants()).sort((a, b) =>
    STATUS_ORDER[a.status] - STATUS_ORDER[b.status] || b.lastSeen - a.lastSeen)
}, 5000)

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  return participants.value.filter(p =>
    (scope.value === 'all' || p.status !== 'stale')
    && (!q || p.name.toLowerCase().includes(q) || !!p.description?.toLowerCase().includes(q)))
})
</script>

<template>
  <div class="flex h-full">
    <ListPane v-model:search="search" title="Participants" :active="!!activeName">
      <template #filters>
        <FilterChips v-model="scope" :options="[{ label: 'Active', value: 'active' }, { label: 'All', value: 'all' }]" />
      </template>

      <ListRow v-for="p in filtered" :key="p.name" :to="`/participants/${encodeURIComponent(p.name)}`"
               :title="p.name === identity.name.value ? `${p.name} (You)` : p.name" :subtitle="p.description"
               :note="p.status === 'live' ? 'online' : `${formatIdle(p.idleSeconds)} ago`"
               :note-title="`Last seen ${formatTime(p.lastSeen)}`" :selected="p.name === activeName">
        <template #avatar>
          <UChip :color="STATUS_COLOR[p.status]" position="bottom-right" inset size="lg">
            <UAvatar :text="initials(p.name)" size="lg" class="bg-primary/15 text-primary" />
          </UChip>
        </template>
      </ListRow>
      <li v-if="!filtered.length" class="px-4 py-8 text-center text-sm text-muted">
        {{ search ? 'No matching participants.'
          : scope === 'active' ? 'Nobody has been active in the last 15 minutes.' : 'No participants have registered yet.' }}
      </li>
    </ListPane>

    <section class="flex-1 min-w-0 flex-col" :class="activeName ? 'flex' : 'hidden md:flex'">
      <NuxtPage :page-key="r => r.fullPath" />
    </section>
  </div>
</template>
