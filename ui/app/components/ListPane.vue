<script setup lang="ts">
/** The left column of a two-pane page: title, search, filter chips and a scrolling list of rows. */
defineProps<{
  title: string
  /** Whether a detail is open: on narrow screens the list then gives way to it. */
  active: boolean
}>()
const search = defineModel<string>('search', { default: '' })
</script>

<template>
  <aside class="w-full md:w-80 lg:w-96 shrink-0 flex-col border-r border-default"
         :class="active ? 'hidden md:flex' : 'flex'">
    <div class="h-16 shrink-0 flex items-center gap-2 px-4">
      <h1 class="text-xl font-bold flex-1">{{ title }}</h1>
      <slot name="actions" />
    </div>

    <div class="px-3 pb-2 space-y-2">
      <UInput v-model="search" icon="i-lucide-search" placeholder="Search" variant="soft" class="w-full" />
      <div v-if="$slots.filters" class="flex gap-2">
        <slot name="filters" />
      </div>
    </div>

    <ul class="flex-1 overflow-y-auto">
      <slot />
    </ul>
  </aside>
</template>
