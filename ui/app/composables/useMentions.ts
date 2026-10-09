function escapeRegExp(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** The names that `text` mentions as `@name`. A trailing full stop still counts: "thanks @bob." mentions bob. */
export function mentionedIn(text: string, names: string[]): string[] {
  return names.filter(n => new RegExp(`(^|[^\\w.-])@${escapeRegExp(n)}(?![\\w-]|\\.[\\w-])`).test(text))
}

/**
 * `@name` completion for a textarea: typing `@` lists the matching candidates, arrow keys move through them and
 * Enter or Tab inserts the chosen name. Bind `onInput`, `onKeydown`, `onCaretMove` and `close` to the textarea.
 */
export function useMentions(
  text: Ref<string>,
  candidates: Ref<string[]>,
  textarea: () => HTMLTextAreaElement | null | undefined,
) {
  const query = ref<string | null>(null)
  const active = ref(0)
  let start = 0

  const suggestions = computed(() => {
    if (query.value === null) return []
    const q = query.value.toLowerCase()
    return candidates.value
      .filter(n => n.toLowerCase().includes(q))
      .sort((a, b) => Number(!a.toLowerCase().startsWith(q)) - Number(!b.toLowerCase().startsWith(q)))
      .slice(0, 8)
  })
  const open = computed(() => suggestions.value.length > 0)

  /** Finds the `@query` the caret is in, if any. */
  function update() {
    const el = textarea()
    if (!el) return
    const caret = el.selectionStart
    const match = /(^|\s)@([\w.-]*)$/.exec(text.value.slice(0, caret))
    const next = match ? match[2]! : null
    if (next !== query.value) active.value = 0
    query.value = next
    if (match) start = caret - match[2]!.length - 1
  }

  function pick(name: string) {
    const el = textarea()
    const caret = el?.selectionStart ?? text.value.length
    const inserted = `@${name} `
    text.value = text.value.slice(0, start) + inserted + text.value.slice(caret)
    query.value = null
    nextTick(() => {
      el?.focus()
      el?.setSelectionRange(start + inserted.length, start + inserted.length)
    })
  }

  function onKeydown(e: KeyboardEvent) {
    if (!open.value || e.isComposing) return
    const n = suggestions.value.length
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      active.value = (active.value + (e.key === 'ArrowDown' ? 1 : n - 1)) % n
    } else if (e.key === 'Enter' || e.key === 'Tab') {
      pick(suggestions.value[active.value]!)
    } else if (e.key === 'Escape') {
      query.value = null
    } else {
      return
    }
    // Keep the key from also sending the message or closing the prompt
    e.preventDefault()
    e.stopImmediatePropagation()
  }

  function onCaretMove(e: Event) {
    // Arrow keys are handled on keydown while the list is open; their keyup must not reset the choice
    if (e instanceof KeyboardEvent && open.value && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) return
    update()
  }

  return {
    suggestions,
    active,
    open,
    pick,
    onInput: update,
    onKeydown,
    onCaretMove,
    close: () => { query.value = null },
  }
}
