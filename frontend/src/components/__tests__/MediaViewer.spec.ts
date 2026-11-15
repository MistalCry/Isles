import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MediaViewer from '@/components/MediaViewer.vue'
import type { MediaItem } from '@/types/models'

function image(overrides: Partial<MediaItem> = {}): MediaItem {
  return {
    id: '01MEDIA0000000000000000000',
    type: 'IMAGE',
    url: '/uploads/2026/11/full.png',
    thumbUrl: '/uploads/2026/11/thumb.png',
    ...overrides,
  } as MediaItem
}

describe('MediaViewer', () => {
  it('列表用缩略图，点击后用原图（轻量展示 + 查看原图）', async () => {
    const wrapper = mount(MediaViewer, { props: { media: [image()] } })

    const img = wrapper.find('.media-viewer__image img')
    expect(img.attributes('src')).toBe('/uploads/2026/11/thumb.png')
    expect(img.attributes('loading')).toBe('lazy')

    await wrapper.find('.media-viewer__image').trigger('click')
    // 灯箱通过 Teleport 挂到 body，需从 document 查询
    const lightboxImg = document.querySelector<HTMLImageElement>('.media-lightbox__image')
    expect(lightboxImg).not.toBeNull()
    // 灯箱使用原图地址（列表只加载缩略图）
    expect(lightboxImg?.getAttribute('src')).toBe('/uploads/2026/11/full.png')
  })

  it('视频用首帧封面作为 poster（列表不再黑块）', () => {
    const wrapper = mount(MediaViewer, {
      props: {
        media: [
          {
            id: '01MEDIA1111111111111111111',
            type: 'VIDEO',
            url: '/uploads/2026/11/clip.mp4',
            thumbUrl: '/uploads/2026/11/clip_poster.jpg',
          } as MediaItem,
        ],
      },
    })
    const video = wrapper.find('video')
    expect(video.exists()).toBe(true)
    expect(video.attributes('poster')).toBe('/uploads/2026/11/clip_poster.jpg')
    expect(video.attributes('preload')).toBe('metadata')
    // 列表自动播放必须静音，否则浏览器会拦截
    expect(video.attributes('muted')).toBeDefined()
  })

  it('没有媒体时不渲染任何容器（不占位）', () => {
    const wrapper = mount(MediaViewer, { props: { media: [] } })
    expect(wrapper.find('.media-viewer').exists()).toBe(false)
  })

  it('过滤掉没有 url 的脏数据', () => {
    const wrapper = mount(MediaViewer, {
      props: { media: [{ id: 'x', type: 'IMAGE', url: '' } as MediaItem] },
    })
    expect(wrapper.find('.media-viewer').exists()).toBe(false)
  })
})
