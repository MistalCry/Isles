/**
 * 图片裁剪的纯计算逻辑（从 ImageCropUpload 抽出，便于单元测试）。
 *
 * <p>约定：导出尺寸按**裁剪区域的原图像素**计算，只有超过 `maxOutputWidth` 时才等比缩小；
 * `maxOutputWidth <= 0` 表示不设上限（帖子内容图保持原像素）。
 */

export interface CropRect {
  x: number
  y: number
  width: number
  height: number
}

export interface CropRectInput {
  naturalWidth: number
  naturalHeight: number
  boxWidth: number
  boxHeight: number
  /** 渲染缩放（baseScale * zoom） */
  scale: number
  offsetX: number
  offsetY: number
}

export interface ExportSize {
  width: number
  height: number
  /** 是否因为超过上限被缩小 */
  downscaled: boolean
}

/** 计算当前取景框对应的原图像素区域 */
export function resolveCropRect(input: CropRectInput): CropRect {
  const { naturalWidth, naturalHeight, boxWidth, boxHeight, scale, offsetX, offsetY } = input
  const safeScale = scale > 0 ? scale : 1
  const x = Math.max(0, -offsetX / safeScale)
  const y = Math.max(0, -offsetY / safeScale)
  const width = Math.min(naturalWidth - x, boxWidth / safeScale)
  const height = Math.min(naturalHeight - y, boxHeight / safeScale)
  return {
    x,
    y,
    width: Math.max(1, Math.round(width)),
    height: Math.max(1, Math.round(height)),
  }
}

/** 计算导出尺寸：默认保持裁剪区域原像素，超过上限才等比缩小 */
export function resolveExportSize(rect: CropRect, maxOutputWidth: number): ExportSize {
  const capped = maxOutputWidth > 0 && rect.width > maxOutputWidth
  const width = capped ? maxOutputWidth : rect.width
  return {
    width,
    height: Math.max(1, Math.round((rect.height * width) / rect.width)),
    downscaled: capped,
  }
}

/** 让图片刚好覆盖取景框的基础缩放 */
export function resolveBaseScale(
  naturalWidth: number,
  naturalHeight: number,
  boxWidth: number,
  boxHeight: number,
): number {
  if (!naturalWidth || !naturalHeight) return 1
  return Math.max(boxWidth / naturalWidth, boxHeight / naturalHeight)
}
