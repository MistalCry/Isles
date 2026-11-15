import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PostCard from '@/components/PostCard.vue'
import { setLocale } from '@/i18n'
import type { Post } from '@/types/models'

// 卡片只测「渲染结果」，接口调用全部打桩，避免测试依赖网络
vi.mock('@/api', () => ({
  postsApi: { toggleLike: vi.fn(), share: vi.fn() },
}))

function makePost(overrides: Partial<Post> = {}): Post {
  return {
    id: '01POST0000000000000000000',
    channelId: '01CHAN0000000000000000000',
    author: {
      id: '01USER0000000000000000000',
      username: 'islander',
      nickname: '岛民',
      avatarUrl: '',
    },
    title: '标题',
    content: '正文内容',
    contentFormat: 'PLAIN',
    createdAt: '2026-11-15T10:00:00Z',
    likeCount: 12345,
    commentCount: 7,
    viewCount: 3,
    isLiked: false,
    isPinned: false,
    isEssence: false,
    media: [],
    ...overrides,
  } as Post
}

describe('PostCard', () => {
  it('有封面时渲染 16:9 封面图（P0-3：封面不能只写不读）', () => {
    const wrapper = mount(PostCard, {
      props: { post: makePost({ coverUrl: '/uploads/2026/11/cover.png' }) },
      shallow: true,
    })
    const cover = wrapper.find('.post-card__cover img')
    expect(cover.exists()).toBe(true)
    expect(cover.attributes('src')).toBe('/uploads/2026/11/cover.png')
    // 懒加载：避免列表一次性拉全量图
    expect(cover.attributes('loading')).toBe('lazy')
  })

  it('没有封面时不渲染封面容器（不占位）', () => {
    const wrapper = mount(PostCard, { props: { post: makePost() }, shallow: true })
    expect(wrapper.find('.post-card__cover').exists()).toBe(false)
  })

  it('标题与正文摘要正常渲染', () => {
    const wrapper = mount(PostCard, { props: { post: makePost() }, shallow: true })
    expect(wrapper.find('.post-card__title').text()).toBe('标题')
    expect(wrapper.find('.post-card__excerpt').text()).toContain('正文内容')
  })

  it('大数字按当前语言紧凑展示（中文 1.2万 / 英文 12.3K）', () => {
    setLocale('zh-CN')
    const zh = mount(PostCard, { props: { post: makePost() }, shallow: true })
    expect(zh.findAll('.post-action')[0].text()).toBe('1.2万')
    expect(zh.findAll('.post-action')[1].text()).toBe('7')

    setLocale('en-US')
    const en = mount(PostCard, { props: { post: makePost() }, shallow: true })
    expect(en.findAll('.post-action')[0].text()).toBe('12.3K')

    // 复位，避免影响后续用例
    setLocale('zh-CN')
  })

  it('摘要里的 @[id:昵称] token 会还原成可读的 @昵称', () => {
    const wrapper = mount(PostCard, {
      props: {
        post: makePost({ content: '你好 @[01USER0000000000000000000:岛民] 看看这个' }),
      },
      shallow: true,
    })
    const text = wrapper.find('.post-card__excerpt').text()
    expect(text).toContain('@岛民')
    expect(text).not.toContain('01USER0000000000000000000')
  })
})
