import DOMPurify from 'dompurify'

/**
 * 富文本净化：后端登录用户内容可能包含 HTML，前端渲染前必须走白名单净化（规范 §9.3）。
 * M1 帖子详情使用 v-html 展示正文，此处统一净化，禁止直接渲染未处理内容。
 */
export function sanitizeHtml(html: string): string {
  if (!html) return ''
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['target', 'rel'],
    FORBID_TAGS: ['style', 'form', 'input', 'button'],
  })
}

/** 根据内容格式返回可安全渲染的 HTML（纯文本转义后换行）。 */
export function renderContent(content: string, format?: string | null): string {
  if (!content) return ''
  if (format === 'HTML' || format === 'MARKDOWN') {
    return sanitizeHtml(content)
  }
  const escaped = content
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
  return escaped.replace(/\n/g, '<br />')
}
