<script setup lang="ts">
import { onMounted } from 'vue';
import { useAuthStore } from '@/stores/auth';

const authStore = useAuthStore();

onMounted(async () => {
  await authStore.initializeAuth();
});
</script>

<template>
  <router-view v-slot="{ Component }">
    <transition name="fade" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>

<style>
:root {
  --primary: #10a37f;
  --primary-light: #22c59b;
  --primary-weak: #dcf7ef;

  --bg: #f3f6f5;
  --bg-card: rgba(255, 255, 255, 0.88);
  --bg-card-solid: #ffffff;

  --border: rgba(15, 23, 42, 0.1);
  --border-light: rgba(15, 23, 42, 0.06);

  --text-primary: #0f172a;
  --text-secondary: #475569;
  --text-muted: #94a3b8;

  --radius-sm: 8px;
  --radius: 10px;
  --radius-md: 14px;
  --radius-lg: 18px;

  --shadow-sm: 0 4px 14px rgba(15, 23, 42, 0.05);
  --shadow: 0 10px 24px rgba(15, 23, 42, 0.08);
  --shadow-lg: 0 18px 42px rgba(15, 23, 42, 0.12);
}

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html,
body,
#app {
  height: 100%;
  font-family: 'IBM Plex Sans', 'PingFang SC', 'Noto Sans SC', 'Microsoft YaHei', sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  color: var(--text-primary);
  background-color: var(--bg);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.22s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

::-webkit-scrollbar-track {
  background: transparent;
}

::-webkit-scrollbar-thumb {
  background: rgba(100, 116, 139, 0.35);
  border-radius: 8px;
}

::-webkit-scrollbar-thumb:hover {
  background: rgba(100, 116, 139, 0.55);
}

::selection {
  background-color: var(--primary-weak);
  color: #065f46;
}

.el-button--primary {
  --el-button-bg-color: var(--primary);
  --el-button-border-color: var(--primary);
  --el-button-hover-bg-color: var(--primary-light);
  --el-button-hover-border-color: var(--primary-light);
  --el-button-active-bg-color: #0b8a6b;
  --el-button-active-border-color: #0b8a6b;
}

.el-card,
.el-table,
.el-input__wrapper,
.el-textarea__inner {
  border-color: var(--border);
}
</style>
