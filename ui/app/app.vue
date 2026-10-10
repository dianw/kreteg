<script setup lang="ts">
const nav = [
  { label: 'Conversations', icon: 'i-lucide-message-circle', to: '/conversations' },
  { label: 'Participants', icon: 'i-lucide-users', to: '/participants' },
]
</script>

<template>
  <UApp>
    <div class="h-dvh flex bg-default">
      <nav class="w-14 shrink-0 flex flex-col items-center gap-2 py-3 border-r border-default bg-elevated/40">
        <NuxtLink to="/" class="size-9 mb-2 rounded-full bg-primary text-inverted font-bold grid place-items-center"
                  aria-label="Kreteg">K</NuxtLink>
        <UTooltip v-for="item in nav" :key="item.to" :text="item.label" :content="{ side: 'right' }">
          <UButton :to="item.to" :icon="item.icon" :aria-label="item.label" color="neutral" variant="ghost" size="lg"
                   active-color="primary" active-variant="soft" />
        </UTooltip>
        <div class="flex-1" />
        <ThemeToggle />
        <IdentityControl />
      </nav>
      <main class="flex-1 min-w-0 h-full">
        <NuxtPage />
      </main>
    </div>
  </UApp>
</template>
