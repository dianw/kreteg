const STORAGE_KEY = 'kreteg.identity'

/** How the UI describes the participants it registers, which is how a person is told apart from an agent. */
export const PERSON_DESCRIPTION = 'Person using the Kreteg web UI'

function load(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? ''
  } catch {
    return ''
  }
}

/**
 * The participant name this browser posts as. Empty means read-only. Kept in localStorage for this viewer only;
 * the server just sees another registered participant.
 */
export function useIdentity() {
  const name = useState<string>('identity', load)
  const api = useKretegApi()

  async function set(newName: string) {
    const trimmed = newName.trim()
    if (trimmed) {
      await api.register(trimmed, PERSON_DESCRIPTION)
    }
    name.value = trimmed
    try {
      if (trimmed) localStorage.setItem(STORAGE_KEY, trimmed)
      else localStorage.removeItem(STORAGE_KEY)
    } catch {
      // Storage unavailable: the identity lasts for this page load only
    }
  }

  return { name: readonly(name), set }
}
