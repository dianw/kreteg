/**
 * Runs `task` now and every `intervalMs` while the component is mounted, skipping ticks while the tab
 * is hidden and catching up as soon as it becomes visible again. A tick never overlaps the previous one.
 * With `enabled`, ticks run only while it returns true, and one runs as soon as it turns true.
 */
export function usePolling(task: () => Promise<unknown>, intervalMs: number, enabled: () => boolean = () => true) {
  let timer: ReturnType<typeof setInterval> | undefined
  let running = false

  async function tick() {
    if (running || document.hidden || !enabled()) return
    running = true
    try {
      await task()
    } catch {
      // Already reported by the API wrapper; try again next tick
    } finally {
      running = false
    }
  }

  function onVisibility() {
    if (!document.hidden) tick()
  }

  watch(enabled, on => on && tick())

  onMounted(() => {
    tick()
    timer = setInterval(tick, intervalMs)
    document.addEventListener('visibilitychange', onVisibility)
  })
  onBeforeUnmount(() => {
    clearInterval(timer)
    document.removeEventListener('visibilitychange', onVisibility)
  })

  return { refresh: tick }
}
