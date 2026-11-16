import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import type { MediaItem } from '@/types/models'

// 视频懒播放回归：列表里多个视频同时 autoplay 会抢带宽、同时出声。
// 约定：进入视口（≥50%）才 play，离开或未达阈值立即 pause；不支持 IntersectionObserver 的环境保持暂停。
class MockIntersectionObserver {
  static instances: MockIntersectionObserver[] = []

  callback: IntersectionObserverCallback
  options?: IntersectionObserverInit
  observed: Element[] = []

  constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
    this.callback = callback
    this.options = options
    MockIntersectionObserver.instances.push(this)
  }

  observe(element: Element): void {
    this.observed.push(element)
  }

  unobserve(): void {}

  disconnect(): void {
    this.observed = []
  }

  takeRecords(): IntersectionObserverEntry[] {
    return []
  }

  /** 模拟一次交叉观察回调 */
  emit(entries: Array<Partial<IntersectionObserverEntry>>): void {
    this.callback(entries as IntersectionObserverEntry[], this as unknown as IntersectionObserver)
  }
}

import MediaViewer from '@/components/MediaViewer.vue'

const VIDEO: MediaItem = {
  type: 'VIDEO',
  url: '/uploads/2026/11/clip.mp4',
  thumbUrl: '/uploads/2026/11/clip-poster.png',
} as MediaItem

function mountViewer(media: MediaItem[] = [VIDEO]) {
  return mount(MediaViewer, {
    props: { media, interactive: false },
    global: { stubs: { 'el-icon': { template: '<i><slot /></i>' } } },
  })
}

describe('MediaViewer 视频懒播放', () => {
  beforeEach(() => {
    MockIntersectionObserver.instances = []
    vi.stubGlobal('IntersectionObserver', MockIntersectionObserver)
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('挂载时创建观察器并把视频元素纳入观察（threshold 含 0.5）', () => {
    const wrapper = mountViewer()
    const observer = MockIntersectionObserver.instances[0]
    expect(observer).toBeTruthy()
    expect(observer.options?.threshold).toEqual([0, 0.5, 1])

    const video = wrapper.find('video').element
    expect(observer.observed).toContain(video)
  })

  it('不可见时不自动播放（保持 paused）', () => {
    const wrapper = mountViewer()
    const observer = MockIntersectionObserver.instances[0]
    const video = wrapper.find('video').element as HTMLVideoElement

    observer.emit([{ target: video, isIntersecting: false, intersectionRatio: 0 }])
    expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled()
    expect(video.paused).toBe(true)
  })

  it('可见面积 ≥50% 时自动播放', () => {
    const wrapper = mountViewer()
    const observer = MockIntersectionObserver.instances[0]
    const video = wrapper.find('video').element as HTMLVideoElement

    observer.emit([{ target: video, isIntersecting: true, intersectionRatio: 0.8 }])
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledTimes(1)
  })

  it('可见但不足 50% 时不播放（避免刚露一角就开始响）', () => {
    const wrapper = mountViewer()
    const observer = MockIntersectionObserver.instances[0]
    const video = wrapper.find('video').element as HTMLVideoElement

    observer.emit([{ target: video, isIntersecting: true, intersectionRatio: 0.3 }])
    expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled()
  })

  it('播放中离开视口立即暂停', () => {
    const wrapper = mountViewer()
    const observer = MockIntersectionObserver.instances[0]
    const video = wrapper.find('video').element as HTMLVideoElement
    Object.defineProperty(video, 'paused', { value: false, configurable: true })

    observer.emit([{ target: video, isIntersecting: false, intersectionRatio: 0 }])
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalledTimes(1)
  })

  it('纯图片不创建观察目标（没有 video 元素）', () => {
    const wrapper = mountViewer([{ type: 'IMAGE', url: '/uploads/2026/11/a.png' } as MediaItem])
    const observer = MockIntersectionObserver.instances[0]
    expect(wrapper.find('video').exists()).toBe(false)
    expect(observer?.observed ?? []).toHaveLength(0)
  })
})
