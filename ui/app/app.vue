<script setup lang="ts">
const nav = [
  { label: 'Conversations', icon: 'i-lucide-message-circle', to: '/conversations' },
  { label: 'Participants', icon: 'i-lucide-users', to: '/participants' },
]

// On narrow screens the rail is a bottom tab bar, which gives way to an open conversation or participant so the
// composer keeps the full height
const route = useRoute()
const detailOpen = computed(() => !!route.params.id || !!route.params.name)
</script>

<template>
  <UApp>
    <div class="h-dvh flex max-md:flex-col-reverse bg-default">
      <!-- Phone and desktop classes never overlap (max-md: / md:), so neither depends on stylesheet order -->
      <nav class="shrink-0 items-center gap-2 border-default bg-elevated/40
                  max-md:flex-row max-md:justify-around max-md:h-14 max-md:pb-[env(safe-area-inset-bottom)] max-md:border-t
                  md:flex md:flex-col md:w-14 md:py-3 md:border-r"
           :class="detailOpen ? 'max-md:hidden' : 'flex'">
        <NuxtLink to="/" class="max-md:hidden md:grid size-9 mb-2 rounded-full bg-primary text-inverted font-bold place-items-center"
                  aria-label="Kreteg">K</NuxtLink>
        <UTooltip v-for="item in nav" :key="item.to" :text="item.label" :content="{ side: 'right' }">
          <UButton :to="item.to" :icon="item.icon" :aria-label="item.label" color="neutral" variant="ghost" size="lg"
                   active-color="primary" active-variant="soft" />
        </UTooltip>
        <div class="max-md:hidden flex-1" />
        <ThemeToggle />
        <IdentityControl />
      </nav>
      <main class="flex-1 min-w-0 min-h-0 h-full">
        <NuxtPage />
      </main>
    </div>
  </UApp>
</template>
