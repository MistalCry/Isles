/** 客户端表单校验规则：只为体验服务，最终以服务端校验为准（规范 §3.3）。 */

const USERNAME_PATTERN = /^[a-zA-Z0-9_-]{3,20}$/

export function validateUsername(value: string): string | null {
  if (!value) return '请输入用户名'
  if (!USERNAME_PATTERN.test(value)) return '用户名需为 3–20 位字母、数字、下划线或连字符'
  return null
}

export function validatePassword(value: string): string | null {
  if (!value) return '请输入密码'
  if (value.length < 8) return '密码至少 8 位'
  if (value.length > 64) return '密码不能超过 64 位'
  if (!/[a-zA-Z]/.test(value) || !/\d/.test(value)) return '密码需同时包含字母和数字'
  return null
}

export function validateNickname(value: string): string | null {
  if (!value) return null
  if (value.length > 20) return '昵称不能超过 20 个字符'
  return null
}

export function validateChannelName(value: string): string | null {
  const name = value.trim()
  if (!name) return '请输入频道名称'
  if (name.length < 2) return '频道名称至少 2 个字符'
  if (name.length > 30) return '频道名称最多 30 个字符'
  return null
}

export function validateChannelDescription(value: string): string | null {
  if (value.length > 200) return '简介不能超过 200 个字符'
  return null
}

export function validateComment(value: string): string | null {
  if (!value.trim()) return '评论内容不能为空'
  if (value.length > 2000) return '评论不能超过 2000 个字符'
  return null
}
