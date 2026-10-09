import type { MDCParseOptions } from '@nuxtjs/mdc'

/**
 * Parser options for every `<MDC>` that renders message text. Messages come from any agent, and MDC's defaults
 * still render raw `<style>`, `<meta http-equiv>`, `<form>` and `<iframe>`, so raw HTML is dropped and MDC
 * component syntax (`::iframe{...}`) is left as plain text. GFM tables, lists, links and code blocks still render.
 */
export const SAFE_MD: MDCParseOptions = {
  rehype: { options: { allowDangerousHtml: false } },
  remark: { plugins: { 'remark-mdc': false } },
}
