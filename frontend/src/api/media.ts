import { t } from '@/i18n'
import { MAX_IMAGE_SIZE_MB, MAX_VIDEO_SIZE_MB } from '@/constants/app'
import type { MediaItem, MediaType } from '@/types/models'
import { toMediaItem } from './adapters'
import { http } from './client'

/**
 * HEIC/HEIF：浏览器普遍无法本地解码（<img> 与 canvas 都会失败），
 * 但后端仍按原图保存。用于「不静默失败」的提示与裁剪降级。
 */
export function isPreviewUnsupported(file: File): boolean {
  const type = (file.type || '').toLowerCase()
  const name = (file.name || '').toLowerCase()
  return type.includes('heic') || type.includes('heif') || /\.(heic|heif)$/.test(name)
}

export function detectMediaType(file: File): MediaType | null {
  if (file.type.startsWith('image/')) return 'IMAGE'
  if (file.type.startsWith('video/')) return 'VIDEO'
  return null
}

export function validateMediaFile(file: File): string | null {
  const mediaType = detectMediaType(file)
  if (!mediaType) return t('media.uploadTypeInvalid')
  const maxMb = mediaType === 'VIDEO' ? MAX_VIDEO_SIZE_MB : MAX_IMAGE_SIZE_MB
  if (file.size > maxMb * 1024 * 1024) {
    return t('media.sizeLimit', {
      type: mediaType === 'VIDEO' ? t('common.video') : t('common.image'),
      max: maxMb,
    })
  }
  return null
}

/**
 * 上传超时：按 0.5MB/s 的保守带宽估算，至少给 120 秒。
 * 固定 120s 会让大视频在慢网上必然超时（200MB 视频要 400s 左右）。
 */
export function uploadTimeoutMs(sizeBytes: number): number {
  const estimated = Math.ceil((sizeBytes / (0.5 * 1024 * 1024)) * 1000)
  return Math.max(120_000, estimated)
}

export async function uploadMedia(file: File, onProgress?: (percent: number) => void): Promise<MediaItem> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('type', detectMediaType(file) === 'VIDEO' ? 'video' : 'image')

  const { data } = await http.post<unknown>('/media/upload', formData, {
    timeout: uploadTimeoutMs(file.size),
    onUploadProgress: (event) => {
      if (event.total && onProgress) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    },
  })
  return toMediaItem(data)
}
