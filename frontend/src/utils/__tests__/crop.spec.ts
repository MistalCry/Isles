import { describe, expect, it } from 'vitest'

import { resolveBaseScale, resolveCropRect, resolveExportSize } from '../crop'

const box = { boxWidth: 320, boxHeight: 320 }

describe('resolveBaseScale', () => {
  it('按覆盖取景框计算缩放（宽图以高为准）', () => {
    expect(resolveBaseScale(4000, 3000, 320, 320)).toBeCloseTo(320 / 3000, 6)
  })

  it('尺寸缺失时退化为 1', () => {
    expect(resolveBaseScale(0, 0, 320, 320)).toBe(1)
  })
})

describe('resolveCropRect', () => {
  it('取景框居中且未缩放时，裁剪区域等于取景框对应的原图像素', () => {
    const scale = 320 / 3000
    const rect = resolveCropRect({
      naturalWidth: 4000,
      naturalHeight: 3000,
      ...box,
      scale,
      offsetX: -(4000 * scale - 320) / 2,
      offsetY: 0,
    })
    expect(rect.width).toBe(3000)
    expect(rect.height).toBe(3000)
  })

  it('偏移量不会让裁剪区域越界', () => {
    const rect = resolveCropRect({
      naturalWidth: 800,
      naturalHeight: 600,
      ...box,
      scale: 1,
      offsetX: 999,
      offsetY: 999,
    })
    expect(rect.x).toBe(0)
    expect(rect.y).toBe(0)
    expect(rect.width).toBeGreaterThan(0)
    expect(rect.height).toBeGreaterThan(0)
  })
})

describe('resolveExportSize', () => {
  it('不设上限时保持裁剪区域原像素（帖子图片高清）', () => {
    const size = resolveExportSize({ x: 0, y: 0, width: 3000, height: 2000 }, 0)
    expect(size).toEqual({ width: 3000, height: 2000, downscaled: false })
  })

  it('超过上限才等比缩小（头像 1024）', () => {
    const size = resolveExportSize({ x: 0, y: 0, width: 3000, height: 3000 }, 1024)
    expect(size.width).toBe(1024)
    expect(size.height).toBe(1024)
    expect(size.downscaled).toBe(true)
  })

  it('小于上限时不做放大', () => {
    const size = resolveExportSize({ x: 0, y: 0, width: 600, height: 400 }, 1024)
    expect(size).toEqual({ width: 600, height: 400, downscaled: false })
  })

  it('等比缩放时高度按原比例换算（16:9 封面）', () => {
    const size = resolveExportSize({ x: 0, y: 0, width: 4000, height: 2250 }, 1920)
    expect(size.width).toBe(1920)
    expect(size.height).toBe(1080)
    expect(size.downscaled).toBe(true)
  })

  it('极端窄条也不会算出 0 高度', () => {
    const size = resolveExportSize({ x: 0, y: 0, width: 5000, height: 2 }, 100)
    expect(size.height).toBeGreaterThanOrEqual(1)
  })
})
