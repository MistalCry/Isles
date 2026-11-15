/** 客户端表单校验规则：只为体验服务，最终以服务端校验为准（规范 §3.3）。 */
import { t } from '@/i18n'

const USERNAME_PATTERN = /^[a-zA-Z0-9_-]{3,20}$/

export function validateUsername(value: string): string | null {
  if (!value) return t('auth.login.usernameRequired')
  if (!USERNAME_PATTERN.test(value)) return t('validation.usernamePattern')
  return null
}

export function validatePassword(value: string): string | null {
  if (!value) return t('auth.login.passwordRequired')
  if (value.length < 8) return t('validation.passwordMin')
  if (value.length > 64) return t('validation.passwordMax')
  if (!/[a-zA-Z]/.test(value) || !/\d/.test(value)) return t('validation.passwordPattern')
  return null
}

export function validateNickname(value: string): string | null {
  if (!value) return null
  if (value.length > 20) return t('validation.nicknameMax')
  return null
}

export function validateChannelName(value: string): string | null {
  const name = value.trim()
  if (!name) return t('validation.channelNameRequired')
  if (name.length < 2) return t('validation.channelNameMin')
  if (name.length > 30) return t('validation.channelNameMax')
  return null
}

export function validateChannelDescription(value: string): string | null {
  if (value.length > 200) return t('validation.channelDescMax')
  return null
}

/**
 * 评论校验：文字、媒体、表情**至少一项**非空（docs/03 FR-CMT-03，与后端 CreateCommentRequest 一致）。
 */
export function validateComment(value: string, mediaCount = 0, emojiCount = 0): string | null {
  if (!value.trim() && mediaCount === 0 && emojiCount === 0) return t('validation.comment.empty')
  if (value.length > 2000) return t('validation.comment.tooLong')
  return null
}
