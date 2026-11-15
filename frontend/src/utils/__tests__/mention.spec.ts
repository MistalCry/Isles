import { describe, expect, it } from 'vitest'

import { formatMentions, hasMentionToken, mentionToken } from '../mention'

describe('mention token', () => {
  it('生成 @[id:昵称] 形态，重名也能精确命中', () => {
    expect(mentionToken('01M7D44A4KSN2B93N0AZETHA98', 'Admin')).toBe('@[01M7D44A4KSN2B93N0AZETHA98:Admin]')
  })

  it('展示时还原成 @昵称', () => {
    const text = '你好 @[01M7D44A4KSN2B93N0AZETHA98:Admin] 和 @[01M7D5XYEXK8H22SYXXRJEKBAA:IslesBot]'
    expect(formatMentions(text)).toBe('你好 @Admin 和 @IslesBot')
  })

  it('手打的 @昵称保持原样', () => {
    expect(formatMentions('@Admin 看这里')).toBe('@Admin 看这里')
  })

  it('空值与不含 token 的文本安全返回', () => {
    expect(formatMentions(null)).toBe('')
    expect(formatMentions(undefined)).toBe('')
    expect(formatMentions('普通文本')).toBe('普通文本')
  })

  it('能识别是否包含 token', () => {
    expect(hasMentionToken('@[01M7D44A4KSN2B93N0AZETHA98:Admin]')).toBe(true)
    expect(hasMentionToken('@Admin')).toBe(false)
    expect(hasMentionToken(null)).toBe(false)
  })

  it('同一段文本里的多个 token 全部还原', () => {
    const text = '@[01M7D44A4KSN2B93N0AZETHA98:甲] @[01M7D44A4KSN2B93N0AZETHA98:乙]'
    expect(formatMentions(text)).toBe('@甲 @乙')
  })
})
