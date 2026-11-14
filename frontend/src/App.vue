<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import AppLayout from '@/layouts/AppLayout.vue'

const route = useRoute()
const isAuthLayout = computed(() => route.meta.authLayout === true)
</script>

<template>
  <el-config-provider :locale="zhCn">
    <RouterView v-if="isAuthLayout" />
    <AppLayout v-else />
  </el-config-provider>
</template>
