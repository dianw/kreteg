import type { MDCParseOptions } from '@nuxtjs/mdc'
import type { ElementContent, Root, RootContent } from 'hast'

/**
 * Parser options for every `<MDC>` that renders message text. Messages come from any agent, and MDC's defaults
 * still render raw `<style>`, `<meta http-equiv>`, `<form>` and `<iframe>`, so raw HTML is dropped and MDC
 * component syntax (`::iframe{...}`) is left as plain text. GFM tables, lists, links and code blocks still render.
 */
export const SAFE_MD: MDCParseOptions = {
  rehype: { options: { allowDangerousHtml: false } },
  remark: { plugins: { 'remark-mdc': false } },
}

/** Elements whose text is never scanned for mentions: code stays literal, and a link is already a link. */
const NO_MENTIONS = new Set(['code', 'pre', 'a'])

/**
 * A rehype plugin that wraps `@name` for the given members in `<span class="kreteg-mention">`, plus `is-me` for
 * `me`. Matches follow the same rules as `mentionedIn`, so what is highlighted is what was sent as `to`.
 */
function rehypeMentions(options: { names: string[], me: string }) {
  const names = [...options.names].sort((a, b) => b.length - a.length)
  if (!names.length) return () => {}
  const pattern = new RegExp(
    `(^|[^\\w.-])@(${names.map(n => n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})(?![\\w-]|\\.[\\w-])`, 'g')

  function split(value: string): ElementContent[] {
    const out: ElementContent[] = []
    let last = 0
    for (const match of value.matchAll(pattern)) {
      const start = match.index + match[1]!.length
      if (start > last) out.push({ type: 'text', value: value.slice(last, start) })
      out.push({
        type: 'element',
        tagName: 'span',
        properties: { className: match[2] === options.me ? ['kreteg-mention', 'is-me'] : ['kreteg-mention'] },
        children: [{ type: 'text', value: `@${match[2]}` }],
      })
      last = start + match[2]!.length + 1
    }
    if (last < value.length) out.push({ type: 'text', value: value.slice(last) })
    return out
  }

  function walk(node: Root | RootContent) {
    if (!('children' in node)) return
    if (node.type === 'element' && NO_MENTIONS.has(node.tagName)) return
    node.children = node.children.flatMap((child) => {
      if (child.type === 'text' && child.value.includes('@')) return split(child.value)
      walk(child)
      return [child]
    }) as typeof node.children
  }

  return (tree: Root) => walk(tree)
}

/** SAFE_MD plus highlighting of the conversation's members where the text mentions them. */
export function messageMarkdown(names: string[], me: string): MDCParseOptions {
  return {
    ...SAFE_MD,
    rehype: { ...SAFE_MD.rehype, plugins: { mentions: { instance: rehypeMentions, options: { names, me } } } },
  }
}
