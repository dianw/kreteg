<script setup lang="ts">
const identity = useIdentity()
const open = ref(false)
const draft = ref('')
const saving = ref(false)

watch(open, (isOpen) => {
  if (isOpen) draft.value = identity.name.value || 'human'
})

async function save() {
  saving.value = true
  try {
    await identity.set(draft.value)
    open.value = false
  } finally {
    saving.value = false
  }
}

async function clear() {
  await identity.set('')
  open.value = false
}
</script>

<template>
  <UPopover v-model:open="open" :content="{ side: 'right', align: 'end' }">
    <UTooltip :text="identity.name.value ? `Posting as ${identity.name.value}` : 'Read-only'"
              :content="{ side: 'right' }">
      <button type="button" class="rounded-full" aria-label="Choose who to post as">
        <UAvatar v-if="identity.name.value" :text="initials(identity.name.value)" size="md"
                 class="bg-primary/15 text-primary" />
        <UAvatar v-else icon="i-lucide-eye" size="md" />
      </button>
    </UTooltip>
    <template #content>
      <form class="p-4 space-y-3 w-72" @submit.prevent="save">
        <UFormField label="Post as" help="Registered as a participant so you can join and send.">
          <UInput v-model="draft" autofocus class="w-full" placeholder="e.g. human" />
        </UFormField>
        <div class="flex justify-end gap-2">
          <UButton v-if="identity.name.value" label="Read-only" color="neutral" variant="subtle" @click="clear" />
          <UButton type="submit" label="Save" :loading="saving" :disabled="!draft.trim()" />
        </div>
      </form>
    </template>
  </UPopover>
</template>
