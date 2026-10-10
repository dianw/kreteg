// A static SPA: `nuxt generate` writes .output/public, which the kreteg-ui jar puts under META-INF/resources for
// Quarkus to serve. Nuxt's own server runs only in `nuxt dev`.
export default defineNuxtConfig({
  compatibilityDate: '2026-10-01',
  ssr: false,
  modules: ['@nuxt/ui', '@nuxtjs/mdc'],
  css: ['~/assets/css/main.css'],
  // Renders the Markdown agents send. Message text comes from any agent, so <MDC> callers turn off raw HTML and
  // MDC component syntax through parserOptions.
  mdc: {
    highlight: {
      // Highlight in the browser: the default /api/_mdc/highlight route would hit the /api proxy in dev and doesn't
      // exist in the static build
      noApiRoute: true,
      langs: ['ts', 'js', 'json', 'java', 'sh', 'bash', 'yaml', 'md', 'diff', 'sql', 'xml', 'vue'],
    },
  },
  app: {
    head: {
      title: 'Kreteg',
      // viewport-fit=cover lets the bottom bar and composer pad themselves clear of the home indicator
      viewport: 'width=device-width, initial-scale=1, viewport-fit=cover',
    },
  },
  icon: {
    // A client-only app has no icon server, so unbundled icons are fetched from the public Iconify API. Bundle the
    // ones the app uses (from @iconify-json/lucide; Nuxt UI adds its own) so the UI works offline.
    clientBundle: { scan: true },
  },
  // '::' listens on IPv6 and IPv4. The default binds only ::1, and a browser that tries 127.0.0.1 first for
  // localhost can stall for ~20s before falling back.
  devServer: { host: '::', port: 3000 },
  nitro: {
    // Dev only: the app calls /api on its own origin, as it does when Quarkus serves it. Quarkus listens on IPv4.
    devProxy: {
      '/api': { target: 'http://127.0.0.1:5784/api', changeOrigin: true },
    },
  },
})
