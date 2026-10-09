<script setup lang="ts">
/** One row of a ListPane: avatar, title with a trailing note, and a muted subtitle. */
defineProps<{
  to: string
  title: string
  subtitle?: string | null
  note?: string
  noteTitle?: string
  selected?: boolean
}>()
</script>

<template>
  <li>
    <NuxtLink :to="to" class="flex items-center gap-3 px-3 py-3 hover:bg-elevated/60 transition-colors"
              :class="selected ? 'bg-elevated' : ''">
      <div class="shrink-0">
        <slot name="avatar">
          <UAvatar :text="initials(title)" size="lg" class="bg-primary/15 text-primary" />
        </slot>
      </div>
      <div class="flex-1 min-w-0 border-b border-default pb-3 -mb-3">
        <div class="flex items-baseline gap-2">
          <span class="font-medium truncate flex-1">{{ title }}</span>
          <span v-if="note" class="text-xs text-muted shrink-0" :title="noteTitle">{{ note }}</span>
        </div>
        <p class="text-sm text-muted truncate">{{ subtitle || ' ' }}</p>
      </div>
    </NuxtLink>
  </li>
</template>
