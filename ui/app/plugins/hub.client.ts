// The tab's one event stream, and the participants and conversations it keeps current, live for the whole app rather
// than per page: pages come and go, and the browser allows only a few connections per origin.
export default defineNuxtPlugin(() => {
  startDirectory(useKretegApi())
  useHub().start()
})
