import { describe, expect, it } from 'vitest'

import { validateComment } from '../validators'

describe('validateComment', () => {
  it('纯文字可以发', () => {
    expect(validateComment('你好')).toBeNull()
  })

  it('纯图片可以发（FR-CMT-03：文字/媒体/表情至少一项）', () => {
    expect(validateComment('', 1, 0)).toBeNull()
  })

  it('纯表情可以发', () => {
    expect(validateComment('', 0, 2)).toBeNull()
  })

  it('三者都为空时拒绝', () => {
    expect(validateComment('   ', 0, 0)).toBe('评论内容、图片或表情至少要有一项')
  })

  it('超长文字被拒绝', () => {
    expect(validateComment('a'.repeat(2001))).toBe('评论不能超过 2000 个字符')
  })

  it('2000 字刚好通过', () => {
    expect(validateComment('a'.repeat(2000))).toBeNull()
  })
})
