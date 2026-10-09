<script setup lang="ts" generic="T extends string">
/** Rounded toggle chips choosing one of a few list filters. */
defineProps<{ options: { label: string, value: T }[] }>()
const model = defineModel<T>({ required: true })
</script>

<template>
  <UButton v-for="o in options" :key="o.value" :label="o.label" size="xs" class="rounded-full"
           :color="model === o.value ? 'primary' : 'neutral'" :variant="model === o.value ? 'subtle' : 'soft'"
           @click="model = o.value" />
</template>
