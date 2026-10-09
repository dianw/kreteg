<script setup lang="ts">
/** The right pane when nothing is selected, or what was asked for does not exist. */
defineProps<{ icon?: string, title: string, text?: string }>()
</script>

<template>
  <div class="flex-1 flex flex-col items-center justify-center gap-3 bg-muted chat-wallpaper text-center p-6">
    <div v-if="icon" class="size-20 rounded-full bg-primary/15 text-primary grid place-items-center">
      <UIcon :name="icon" class="size-10" />
    </div>
    <h2 class="text-2xl font-light">{{ title }}</h2>
    <p v-if="text" class="text-sm text-muted max-w-sm">{{ text }}</p>
    <slot />
  </div>
</template>
