<script setup lang="ts">
/** The top bar of a detail pane, with a back link that only shows on narrow screens. */
defineProps<{ back: string, title: string, subtitle?: string, subtitleTitle?: string, ui?: { subtitle?: string } }>()
</script>

<template>
  <header class="h-16 shrink-0 flex items-center gap-3 px-3 border-b border-default bg-elevated/40">
    <UButton :to="back" icon="i-lucide-arrow-left" color="neutral" variant="ghost" class="md:hidden" aria-label="Back" />
    <slot name="avatar">
      <UAvatar :text="initials(title)" size="md" class="bg-primary/15 text-primary" />
    </slot>
    <div class="min-w-0 flex-1">
      <h1 class="font-semibold truncate">{{ title }}</h1>
      <p v-if="subtitle" class="text-xs text-muted truncate" :class="ui?.subtitle" :title="subtitleTitle">{{ subtitle }}</p>
    </div>
    <slot name="actions" />
  </header>
</template>
