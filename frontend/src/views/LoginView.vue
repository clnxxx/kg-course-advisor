<script setup lang="ts">
import { ref, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import type { FormInstance, FormRules } from 'element-plus';
import { User, Lock } from '@element-plus/icons-vue';

const router = useRouter();
const authStore = useAuthStore();

const formRef = ref<FormInstance>();

const form = reactive({
  username: '',
  password: '',
});

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '用户名长度应在 3 到 50 个字符之间', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度至少 6 位', trigger: 'blur' },
  ],
};

const handleLogin = async () => {
  if (!formRef.value) return;
  await formRef.value.validate(async (valid) => {
    if (valid) {
      const success = await authStore.login({
        username: form.username,
        password: form.password,
      });
      if (success) {
        router.push('/chat');
      }
    }
  });
};

const goToRegister = () => {
  router.push('/register');
};
</script>

<template>
  <div class="auth-page">
    <div class="auth-hero">
      <div class="hero-card">
        <div class="hero-logo">
          <svg
            viewBox="0 0 24 24"
            width="34"
            height="34"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z" />
            <path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z" />
          </svg>
        </div>
        <h1>欢迎回来</h1>
        <p>登录学生选课智能体系统，继续查看课程推荐和选课关系图谱。</p>
      </div>
    </div>

    <div class="auth-panel">
      <div class="form-area">
        <h2>登录</h2>
        <p class="form-subtitle">使用你的账号继续</p>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleLogin" class="auth-form">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="请输入用户名" :prefix-icon="User" size="large" />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              :prefix-icon="Lock"
              size="large"
              show-password
            />
          </el-form-item>

          <el-alert v-if="authStore.error" :title="authStore.error" type="error" :closable="false" class="error-alert" />

          <el-form-item>
            <el-button type="primary" size="large" class="submit-btn" :loading="authStore.loading" @click="handleLogin">
              登录
            </el-button>
          </el-form-item>

          <div class="switch-link">
            <span>还没有账号？</span>
            <el-link type="primary" @click="goToRegister">立即注册</el-link>
          </div>
        </el-form>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.auth-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  background: linear-gradient(180deg, #f8fbfa 0%, #f1f5f4 100%);
}

.auth-hero {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
  background:
    radial-gradient(circle at 20% 20%, rgba(16, 163, 127, 0.2), transparent 38%),
    radial-gradient(circle at 80% 70%, rgba(15, 23, 42, 0.17), transparent 40%),
    #0f172a;
}

.hero-card {
  max-width: 440px;
  color: #fff;

  .hero-logo {
    width: 64px;
    height: 64px;
    border-radius: 16px;
    background: rgba(255, 255, 255, 0.12);
    border: 1px solid rgba(255, 255, 255, 0.16);
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: 22px;
  }

  h1 {
    font-size: 36px;
    margin-bottom: 10px;
    font-weight: 650;
    letter-spacing: 0.01em;
  }

  p {
    line-height: 1.85;
    color: rgba(255, 255, 255, 0.86);
    font-size: 15px;
  }
}

.auth-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
}

.form-area {
  width: 100%;
  max-width: 400px;
  padding: 34px;
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 18px;
  box-shadow: 0 20px 44px rgba(15, 23, 42, 0.12);

  h2 {
    font-size: 28px;
    font-weight: 650;
    color: #0f172a;
    margin-bottom: 4px;
  }

  .form-subtitle {
    color: #64748b;
    font-size: 14px;
    margin-bottom: 24px;
  }
}

.auth-form {
  :deep(.el-form-item__label) {
    font-weight: 500;
    color: #0f172a;
    font-size: 13px;
  }

  :deep(.el-input__wrapper) {
    border-radius: 12px;
  }
}

.submit-btn {
  width: 100%;
  margin-top: 6px;
  border-radius: 12px;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
}

.error-alert {
  margin-bottom: 16px;
}

.switch-link {
  text-align: center;
  margin-top: 22px;
  color: #64748b;
  font-size: 14px;

  span {
    margin-right: 4px;
  }
}

@media (max-width: 900px) {
  .auth-page {
    grid-template-columns: 1fr;
  }

  .auth-hero {
    min-height: 220px;
    padding: 24px;
  }

  .hero-card {
    h1 {
      font-size: 28px;
    }

    p {
      font-size: 14px;
    }
  }

  .auth-panel {
    padding: 20px;
  }

  .form-area {
    padding: 24px;
  }
}
</style>
