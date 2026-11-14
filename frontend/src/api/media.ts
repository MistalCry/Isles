import { MAX_IMAGE_SIZE_MB, MAX_VIDEO_SIZE_MB } from '@/constants/app'
import type { MediaItem, MediaType } from '@/types/models'
import { http } from './client'

export function detectMediaType(file: File): MediaType | null {
  if (file.type.startsWith('image/')) return 'IMAGE'
  if (file.type.startsWith('video/')) return 'VIDEO'
  return null
}

export function validateMediaFile(file: File): string | null {
  const mediaType = detectMediaType(file)
  if (!mediaType) return '仅支持图片（JPG/PNG/WebP/GIF）或视频（MP4/WebM）文件'
  const maxMb = mediaType === 'VIDEO' ? MAX_VIDEO_SIZE_MB : MAX_IMAGE_SIZE_MB
  if (file.size > maxMb * 1024 * 1024) {
    return `${mediaType === 'VIDEO' ? '视频' : '图片'}大小不能超过 ${maxMb}MB`
  }
  return null
}

export async function uploadMedia(file: File, onProgress?: (percent: number) => void): Promise<MediaItem> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('type', detectMediaType(file) === 'VIDEO' ? 'video' : 'image')

  const { data } = await http.post<MediaItem>('/media/upload', formData, {
    timeout: 120000,
    onUploadProgress: (event) => {
      if (event.total && onProgress) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    },
  })
  return data
}
