<script setup lang="ts">
// Cycles system → light → dark. Nuxt UI's UColorModeButton only flips light/dark, which loses "follow the OS"
// once clicked. The choice is kept in localStorage by @nuxtjs/color-mode.
const colorMode = useColorMode()

const modes = [
  { value: 'system', label: 'System theme', icon: 'i-lucide-monitor' },
  { value: 'light', label: 'Light theme', icon: 'i-lucide-sun' },
  { value: 'dark', label: 'Dark theme', icon: 'i-lucide-moon' },
] as const

const index = computed(() => Math.max(0, modes.findIndex(m => m.value === colorMode.preference)))
const current = computed(() => modes[index.value]!)
const next = computed(() => modes[(index.value + 1) % modes.length]!)
</script>

<template>
  <UTooltip :text="`${current.label} (switch to ${next.label.toLowerCase()})`" :content="{ side: 'right' }">
    <UButton :icon="current.icon" :aria-label="`Switch to ${next.label.toLowerCase()}`" color="neutral"
             variant="ghost" size="lg" @click="colorMode.preference = next.value" />
  </UTooltip>
</template>
