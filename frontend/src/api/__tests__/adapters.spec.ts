import { describe, expect, it } from 'vitest'

import { toComment, toMediaItem } from '../adapters'

/**
 * P0-2 回归：后端上传响应字段是 mediaType，帖子/评论里的媒体对象才是 type。
 * 只读 type 会让 type 变成 undefined → 图片被当视频渲染、提交时报「媒体类型必须为 IMAGE 或 VIDEO」。
 */
describe('toMediaItem', () => {
  it('兼容上传响应的 mediaType 字段', () => {
    const item = toMediaItem({
      id: 'm1',
      url: '/uploads/a.png',
      thumbUrl: '/uploads/a_thumb.png',
      mediaType: 'IMAGE',
      width: 800,
      height: 600,
    })
    expect(item.type).toBe('IMAGE')
    expect(item.url).toBe('/uploads/a.png')
    expect(item.width).toBe(800)
  })

  it('识别 mediaType=VIDEO', () => {
    expect(toMediaItem({ url: '/uploads/v.mp4', mediaType: 'VIDEO' }).type).toBe('VIDEO')
  })

  it('列表/详情里的 type 字段优先', () => {
    expect(toMediaItem({ url: '/uploads/v.mp4', type: 'video', mediaType: 'IMAGE' }).type).toBe('VIDEO')
  })

  it('两者都缺失时降级为 IMAGE，不抛异常', () => {
    expect(toMediaItem({ url: '/uploads/x.jpg' }).type).toBe('IMAGE')
  })
})

describe('toComment', () => {
  it('映射 emoji（FR-CMT-03 表情评论）', () => {
    const comment = toComment({
      id: 'c1',
      postId: 'p1',
      authorId: 'u1',
      authorNickname: '岛民',
      content: '',
      emoji: ['😀', '🎉'],
      likeCount: 0,
      createdAt: '2026-11-15T00:00:00Z',
    })
    expect(comment.emoji).toEqual(['😀', '🎉'])
    expect(comment.content).toBe('')
  })

  it('emoji 缺省为空数组，脏数据被过滤', () => {
    expect(toComment({ id: 'c2', postId: 'p1' }).emoji).toEqual([])
    expect(toComment({ id: 'c3', postId: 'p1', emoji: ['😀', 123, null] }).emoji).toEqual(['😀'])
  })
})
