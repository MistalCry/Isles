import { describe, expect, it } from 'vitest'
import { uploadTimeoutMs } from '@/api/media'

// 上传超时回归：固定超时会让大文件在慢网下必然失败。
// 约定：按 0.5MB/s 保守带宽估算，且不低于 120 秒兜底。
const MB = 1024 * 1024
const MIN_TIMEOUT = 120_000

describe('uploadTimeoutMs 按文件大小计算超时', () => {
  it('小文件走 120 秒兜底', () => {
    expect(uploadTimeoutMs(0)).toBe(MIN_TIMEOUT)
    expect(uploadTimeoutMs(1024)).toBe(MIN_TIMEOUT)
    expect(uploadTimeoutMs(10 * MB)).toBe(MIN_TIMEOUT)
  })

  it('大文件按 0.5MB/s 估算：200MB ≈ 400 秒', () => {
    expect(uploadTimeoutMs(200 * MB)).toBe(400_000)
    expect(uploadTimeoutMs(100 * MB)).toBe(200_000)
  })

  it('刚好跨过兜底阈值时按估算值走', () => {
    // 60MB → 120 秒，与兜底相等；61MB 起估算值更大
    expect(uploadTimeoutMs(60 * MB)).toBe(MIN_TIMEOUT)
    expect(uploadTimeoutMs(61 * MB)).toBeGreaterThan(MIN_TIMEOUT)
  })

  it('随文件增大单调不减', () => {
    const sizes = [0, 1 * MB, 50 * MB, 60 * MB, 120 * MB, 200 * MB]
    const timeouts = sizes.map((size) => uploadTimeoutMs(size))
    for (let i = 1; i < timeouts.length; i += 1) {
      expect(timeouts[i]).toBeGreaterThanOrEqual(timeouts[i - 1])
    }
  })
})
