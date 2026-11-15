import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import CommentItem from '@/components/CommentItem.vue'
import type { Comment } from '@/types/models'

function makeComment(overrides: Partial<Comment> = {}): Comment {
  return {
    id: '01COMMENT0000000000000000',
    postId: '01POST0000000000000000000',
    author: {
      id: '01USER0000000000000000000',
      username: 'islander',
      nickname: '岛民',
      avatarUrl: '',
    },
    content: '',
    media: [],
    emoji: [],
    likeCount: 0,
    isLiked: false,
    createdAt: '2026-11-15T10:00:00Z',
    replies: [],
    ...overrides,
  } as Comment
}

describe('CommentItem', () => {
  it('纯表情评论也能渲染（FR-CMT-03：文字/媒体/表情至少一项）', () => {
    const wrapper = mount(CommentItem, {
      props: { comment: makeComment({ emoji: ['🎉', '🏝️'] }) },
      shallow: true,
    })
    expect(wrapper.find('.comment-item__emoji').text()).toBe('🎉 🏝️')
    // 没有文字时不渲染空段落
    expect(wrapper.find('.comment-item__content').exists()).toBe(false)
  })

  it('正文里的 @[id:昵称] 还原为 @昵称', () => {
    const wrapper = mount(CommentItem, {
      props: {
        comment: makeComment({ content: '回复 @[01USER0000000000000000000:岛民] 说得对' }),
      },
      shallow: true,
    })
    const text = wrapper.find('.comment-item__content').text()
    expect(text).toContain('@岛民')
    expect(text).not.toContain('01USER0000000000000000000')
  })

  it('只有自己的评论显示删除入口', () => {
    const comment = makeComment({ author: { id: 'me', username: 'me', nickname: '我', avatarUrl: '' } as never })

    const mine = mount(CommentItem, {
      props: { comment, currentUserId: 'me' },
      shallow: true,
    })
    expect(mine.find('.comment-action--danger').exists()).toBe(true)

    const others = mount(CommentItem, {
      props: { comment, currentUserId: 'someone-else' },
      shallow: true,
    })
    expect(others.find('.comment-action--danger').exists()).toBe(false)
  })

  it('删除按钮点击后向父组件抛出 delete 事件', async () => {
    const comment = makeComment({ author: { id: 'me', username: 'me', nickname: '我', avatarUrl: '' } as never })
    const wrapper = mount(CommentItem, {
      props: { comment, currentUserId: 'me' },
      shallow: true,
    })
    await wrapper.find('.comment-action--danger').trigger('click')
    expect(wrapper.emitted('delete')?.[0]?.[0]).toMatchObject({ id: comment.id })
  })

  it('回复提示渲染为「回复 @昵称」（文案里的 @ 已转义，不会触发 i18n 语法错误）', () => {
    const wrapper = mount(CommentItem, {
      props: {
        comment: makeComment({
          content: '同意',
          replyToUser: { id: '01USER0000000000000000000', username: 'islander', nickname: '岛民', avatarUrl: '' },
        }),
      },
      shallow: true,
    })
    expect(wrapper.find('.comment-item__reply-to').text()).toBe('回复 @岛民')
  })

  it('楼中楼回复同样渲染表情与提及', () => {
    const wrapper = mount(CommentItem, {
      props: {
        comment: makeComment({
          content: '主楼',
          replies: [
            makeComment({
              id: '01REPLY00000000000000000',
              content: '楼中楼 @[01USER0000000000000000000:岛民]',
              emoji: ['👍'],
            }),
          ],
        }),
      },
      shallow: true,
    })
    expect(wrapper.find('.comment-reply .comment-item__emoji').text()).toBe('👍')
    expect(wrapper.find('.comment-reply .comment-item__content').text()).toContain('@岛民')
  })
})
