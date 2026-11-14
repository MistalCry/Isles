import { onBeforeUnmount, onMounted, watch, type Ref } from 'vue'

/**
 * 无限滚动哨兵：目标元素进入视口且允许加载时触发 loadMore。
 * 不支持 IntersectionObserver 的环境静默降级为「加载更多」按钮。
 */
export function useInfiniteScroll(
  target: Ref<HTMLElement | null>,
  loadMore: () => void,
  canLoad: () => boolean,
): void {
  let observer: IntersectionObserver | null = null

  function observe(element: HTMLElement | null): void {
    if (observer && element) observer.observe(element)
  }

  onMounted(() => {
    if (typeof window === 'undefined' || !('IntersectionObserver' in window)) return
    observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting) && canLoad()) {
          loadMore()
        }
      },
      { rootMargin: '400px 0px' },
    )
    observe(target.value)
  })

  watch(target, (element, previous) => {
    if (observer && previous) observer.unobserve(previous)
    observe(element)
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observer = null
  })
}
