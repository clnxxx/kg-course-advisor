<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { Calendar, ChatDotRound, Setting, SwitchButton } from '@element-plus/icons-vue'

const router = useRouter()
const authStore = useAuthStore()

const displayName = computed(() => authStore.currentUser?.realName || authStore.currentUser?.username || '用户')
const avatarInitial = computed(() => displayName.value.charAt(0).toUpperCase())

const logout = () => {
  authStore.logout()
  router.push('/login')
}
</script>

<template>
  <div class="main-layout">
    <header class="top-nav">
      <div class="nav-brand" @click="router.push('/chat')">
        <div class="brand-icon">
          <svg
            viewBox="0 0 24 24"
            width="22"
            height="22"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z" />
            <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z" />
          </svg>
        </div>
        <span class="brand-text">学生选课知识图谱助手</span>
      </div>

      <nav class="nav-links">
        <router-link to="/chat" class="nav-link" active-class="active">
          <el-icon><ChatDotRound /></el-icon>
          <span>对话工作台</span>
        </router-link>
        <router-link to="/profile" class="nav-link" active-class="active">
          <el-icon><Calendar /></el-icon>
          <span>我的课程</span>
        </router-link>
        <router-link to="/admin" class="nav-link" active-class="active">
          <el-icon><Setting /></el-icon>
          <span>课程管理</span>
        </router-link>
      </nav>

      <div class="nav-user">
        <div class="user-avatar">{{ avatarInitial }}</div>
        <div class="user-meta">
          <span class="user-name">{{ displayName }}</span>
          <span class="user-role">{{ authStore.currentUser?.role || 'STUDENT' }}</span>
        </div>
        <el-button class="logout-btn" :icon="SwitchButton" text size="small" @click="logout">
          退出
        </el-button>
      </div>
    </header>

    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<style scoped lang="scss">
.main-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background:
    radial-gradient(circle at 10% 0, rgba(16, 163, 127, 0.1), transparent 35%),
    linear-gradient(180deg, #f7faf9 0%, #f2f5f4 100%);
}

.top-nav {
  height: 64px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid rgba(17, 24, 39, 0.08);
  flex-shrink: 0;
  gap: 20px;
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  flex-shrink: 0;

  .brand-icon {
    width: 36px;
    height: 36px;
    background: linear-gradient(135deg, #111827, #1f2937);
    border-radius: var(--radius);
    display: flex;
    align-items: center;
    justify-content: center;
    color: #fff;
    box-shadow: 0 8px 24px rgba(17, 24, 39, 0.2);
  }

  .brand-text {
    font-size: 15px;
    font-weight: 600;
    color: #0f172a;
    letter-spacing: 0.02em;
  }
}

.nav-links {
  flex: 1;
  display: flex;
  justify-content: center;
  gap: 4px;
}

.nav-link {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: 999px;
  color: #475569;
  text-decoration: none;
  font-size: 13px;
  font-weight: 500;
  transition: all 0.2s ease;

  .el-icon {
    font-size: 16px;
  }

  &:hover {
    color: #111827;
    background-color: rgba(15, 23, 42, 0.06);
  }

  &.active {
    color: #0f172a;
    background-color: rgba(16, 163, 127, 0.12);
  }
}

.nav-user {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.user-avatar {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: linear-gradient(135deg, #0f172a, #334155);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
}

.user-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.user-name {
  color: #334155;
  font-size: 13px;
  font-weight: 600;
}

.user-role {
  color: #94a3b8;
  font-size: 11px;
  letter-spacing: 0.08em;
}

.logout-btn {
  color: #64748b;
  font-size: 13px;

  &:hover {
    color: #dc2626;
  }
}

.main-content {
  flex: 1;
  overflow: hidden;
}

@media (max-width: 980px) {
  .top-nav {
    height: auto;
    flex-wrap: wrap;
    padding: 12px;
    gap: 12px;
  }

  .nav-links {
    order: 3;
    width: 100%;
    justify-content: flex-start;
    overflow-x: auto;
  }
}

@media (max-width: 760px) {
  .user-meta {
    display: none;
  }
}
</style>
