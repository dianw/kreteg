/**
 * Runs `task` now and every `intervalMs` while the component is mounted, skipping ticks while the tab
 * is hidden and catching up as soon as it becomes visible again. A tick never overlaps the previous one.
 */
export function usePolling(task: () => Promise<unknown>, intervalMs: number) {
  let timer: ReturnType<typeof setInterval> | undefined
  let running = false

  async function tick() {
    if (running || document.hidden) return
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
