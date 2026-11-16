import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PostCard from '@/components/PostCard.vue'
import MediaViewer from '@/components/MediaViewer.vue'
import { setLocale } from '@/i18n'
import type { MediaItem, Post } from '@/types/models'

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

function makeMedia(count: number): MediaItem[] {
  return Array.from({ length: count }, (_, index) => ({
    id: `01MEDIA00000000000000000${index}`,
    type: 'IMAGE',
    url: `/uploads/2026/11/p${index}.png`,
    thumbUrl: `/uploads/2026/11/p${index}_thumb.png`,
    sort: index,
  })) as MediaItem[]
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

  it('有封面时不再渲染媒体区（一条多图长文不该占满一屏）', () => {
    const wrapper = mount(PostCard, {
      props: {
        post: makePost({ coverUrl: '/uploads/2026/11/cover.png', media: makeMedia(5) }),
      },
      shallow: true,
    })
    expect(wrapper.find('.post-card__cover').exists()).toBe(true)
    expect(wrapper.find('.post-card__media').exists()).toBe(false)
  })

  it('没有封面时不渲染封面容器（不占位）', () => {
    const wrapper = mount(PostCard, { props: { post: makePost() }, shallow: true })
    expect(wrapper.find('.post-card__cover').exists()).toBe(false)
  })

  it('无封面时媒体最多 3 张，多出的用 +N 提示，且媒体区是详情链接', () => {
    const wrapper = mount(PostCard, {
      props: { post: makePost({ media: makeMedia(5) }) },
      shallow: true,
    })
    const viewer = wrapper.findComponent(MediaViewer)
    expect(viewer.exists()).toBe(true)
    expect(viewer.props('media')).toHaveLength(3)
    expect(viewer.props('overflowCount')).toBe(2)
    // 卡片里点媒体应该进详情，而不是弹灯箱
    expect(viewer.props('interactive')).toBe(false)

    // RouterLink 在测试里是 <a> 桩（不解析路由），to 会透传到 data-to
    const mediaLink = wrapper.find('.post-card__media')
    expect(mediaLink.exists()).toBe(true)
    expect(mediaLink.attributes('data-to')).toContain('01POST0000000000000000000')
    expect(mediaLink.attributes('aria-label')).toContain('2')
  })

  it('媒体不超过 3 张时不显示 +N', () => {
    const wrapper = mount(PostCard, {
      props: { post: makePost({ media: makeMedia(3) }) },
      shallow: true,
    })
    const viewer = wrapper.findComponent(MediaViewer)
    expect(viewer.props('media')).toHaveLength(3)
    expect(viewer.props('overflowCount')).toBe(0)
  })

  it('标题与正文摘要正常渲染', () => {
    const wrapper = mount(PostCard, { props: { post: makePost() }, shallow: true })
    expect(wrapper.find('.post-card__title').text()).toBe('标题')
    expect(wrapper.find('.post-card__excerpt').text()).toContain('正文内容')
  })

  it('有标题时摘要压到 2 行且截断到 120 字', () => {
    const long = 'a'.repeat(300)
    const wrapper = mount(PostCard, {
      props: { post: makePost({ title: '标题', content: long }) },
      shallow: true,
    })
    const excerptEl = wrapper.find('.post-card__excerpt')
    expect(excerptEl.classes()).toContain('line-clamp-2')
    // 120 字 + 省略号
    expect(excerptEl.text()).toBe(`${'a'.repeat(120)}…`)
  })

  it('无标题短帖保留 3 行、截断到 220 字', () => {
    const long = 'b'.repeat(400)
    const wrapper = mount(PostCard, {
      props: { post: makePost({ title: null, content: long }) },
      shallow: true,
    })
    const excerptEl = wrapper.find('.post-card__excerpt')
    expect(wrapper.find('.post-card__title').exists()).toBe(false)
    expect(excerptEl.classes()).toContain('line-clamp-3')
    expect(excerptEl.text()).toBe(`${'b'.repeat(220)}…`)
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
