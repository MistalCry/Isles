import { createPinia } from 'pinia'

/** 单例 pinia：请求层在非组件上下文（拦截器）里也要能找到 store。 */
export const pinia = createPinia()
