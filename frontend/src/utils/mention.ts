/**
 * @提及 token 约定（与后端 MentionService 对齐）。
 *
 * 存储形态：`@[ULID:昵称]`——带 id 才能精确命中重名成员；
 * 展示形态：`@昵称`——用户看到的就是普通 @ 文本。
 * 手打的 `@昵称` 仍然兼容（后端按昵称匹配所有同名成员）。
 */
const TOKEN_PATTERN = /@\[([A-Za-z0-9]{20,32}):([^\]\s]{1,32})\]/g

/** 生成可写入正文的提及 token */
export function mentionToken(id: string, name: string): string {
  return `@[${id}:${name}]`
}

/** 把 token 还原成人类可读的 @昵称（用于展示、摘要、通知文案） */
export function formatMentions(text?: string | null): string {
  if (!text) return ''
  return text.replace(TOKEN_PATTERN, '@$2')
}

/** 是否包含提及 token（用于决定是否需要额外处理） */
export function hasMentionToken(text?: string | null): boolean {
  if (!text) return false
  TOKEN_PATTERN.lastIndex = 0
  return TOKEN_PATTERN.test(text)
}
