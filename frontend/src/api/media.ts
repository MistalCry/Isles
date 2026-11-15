import { t } from '@/i18n'
import { MAX_IMAGE_SIZE_MB, MAX_VIDEO_SIZE_MB } from '@/constants/app'
import type { MediaItem, MediaType } from '@/types/models'
import { toMediaItem } from './adapters'
import { http } from './client'

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

export async function uploadMedia(file: File, onProgress?: (percent: number) => void): Promise<MediaItem> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('type', detectMediaType(file) === 'VIDEO' ? 'video' : 'image')

  const { data } = await http.post<unknown>('/media/upload', formData, {
    timeout: 120000,
    onUploadProgress: (event) => {
      if (event.total && onProgress) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    },
  })
  return toMediaItem(data)
}
