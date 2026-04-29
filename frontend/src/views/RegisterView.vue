<script setup lang="ts">
import { ref, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import type { FormInstance, FormRules } from 'element-plus';
import { User, UserFilled, Lock, Message, Document, Reading } from '@element-plus/icons-vue';

const router = useRouter();
const authStore = useAuthStore();

const formRef = ref<FormInstance>();

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  email: '',
  studentId: '',
  realName: '',
  major: '',
  grade: undefined as number | undefined,
});

const validateConfirmPassword = (_rule: unknown, value: string, callback: (error?: Error) => void) => {
  if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'));
  } else {
    callback();
  }
};

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '用户名长度应在 3 到 50 个字符之间', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度至少 6 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' },
  ],
  email: [{ type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' }],
};

const handleRegister = async () => {
  if (!formRef.value) return;
  await formRef.value.validate(async (valid) => {
    if (valid) {
      const success = await authStore.register({
        username: form.username,
        password: form.password,
        email: form.email || undefined,
        studentId: form.studentId || undefined,
        realName: form.realName || undefined,
        major: form.major || undefined,
        grade: form.grade || undefined,
      });
      if (success) {
        router.push('/chat');
      }
    }
  });
};

const goToLogin = () => {
  router.push('/login');
};
</script>

<template>
  <div class="register-page">
    <div class="register-left">
      <div class="brand-area">
        <div class="brand-logo">
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
        <h1>创建新账户</h1>
        <p class="brand-desc">填写基本信息后即可开始使用学生选课智能体系统。</p>
      </div>
    </div>

    <div class="register-right">
      <div class="form-area">
        <h2>注册</h2>
        <p class="form-subtitle">请完善以下信息</p>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="handleRegister" class="register-form">
          <el-row :gutter="14">
            <el-col :span="12">
              <el-form-item label="用户名" prop="username">
                <el-input v-model="form.username" placeholder="请输入用户名" :prefix-icon="User" size="large" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="真实姓名" prop="realName">
                <el-input v-model="form.realName" placeholder="可选" :prefix-icon="UserFilled" size="large" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="14">
            <el-col :span="12">
              <el-form-item label="密码" prop="password">
                <el-input v-model="form.password" type="password" placeholder="请输入密码" :prefix-icon="Lock" size="large" show-password />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="确认密码" prop="confirmPassword">
                <el-input
                  v-model="form.confirmPassword"
                  type="password"
                  placeholder="请再次输入密码"
                  :prefix-icon="Lock"
                  size="large"
                  show-password
                />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="14">
            <el-col :span="12">
              <el-form-item label="邮箱" prop="email">
                <el-input v-model="form.email" placeholder="可选" :prefix-icon="Message" size="large" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="学号" prop="studentId">
                <el-input v-model="form.studentId" placeholder="可选" :prefix-icon="Document" size="large" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="14">
            <el-col :span="12">
              <el-form-item label="专业" prop="major">
                <el-input v-model="form.major" placeholder="可选" :prefix-icon="Reading" size="large" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="年级" prop="grade">
                <el-input-number v-model="form.grade" :min="1" :max="6" size="large" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-alert v-if="authStore.error" :title="authStore.error" type="error" :closable="false" class="error-alert" />

          <el-form-item>
            <el-button type="primary" size="large" class="submit-btn" :loading="authStore.loading" @click="handleRegister">
              注册
            </el-button>
          </el-form-item>

          <div class="switch-link">
            <span>已有账号？</span>
            <el-link type="primary" @click="goToLogin">立即登录</el-link>
          </div>
        </el-form>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.register-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 420px 1fr;
  background: linear-gradient(180deg, #f8fbfa 0%, #f1f5f4 100%);
}

.register-left {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 38px;
  background:
    radial-gradient(circle at 20% 15%, rgba(16, 163, 127, 0.24), transparent 34%),
    radial-gradient(circle at 85% 85%, rgba(30, 41, 59, 0.28), transparent 40%),
    #0f172a;
}

.brand-area {
  color: #fff;
  max-width: 280px;

  .brand-logo {
    width: 60px;
    height: 60px;
    border-radius: 16px;
    background: rgba(255, 255, 255, 0.14);
    border: 1px solid rgba(255, 255, 255, 0.15);
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: 20px;
  }

  h1 {
    font-size: 34px;
    font-weight: 650;
    line-height: 1.2;
    margin-bottom: 12px;
  }

  .brand-desc {
    font-size: 15px;
    line-height: 1.8;
    color: rgba(255, 255, 255, 0.88);
  }
}

.register-right {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 26px;
  overflow-y: auto;
}

.form-area {
  width: 100%;
  max-width: 620px;
  padding: 28px;
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 18px;
  box-shadow: 0 20px 44px rgba(15, 23, 42, 0.12);

  h2 {
    font-size: 30px;
    font-weight: 650;
    color: #0f172a;
    margin-bottom: 4px;
  }

  .form-subtitle {
    color: #64748b;
    font-size: 14px;
    margin-bottom: 20px;
  }
}

.register-form {
  :deep(.el-form-item__label) {
    font-weight: 500;
    color: #0f172a;
    font-size: 13px;
  }

  :deep(.el-input__wrapper) {
    border-radius: 12px;
  }

  :deep(.el-input-number .el-input__wrapper) {
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

@media (max-width: 960px) {
  .register-page {
    grid-template-columns: 1fr;
  }

  .register-left {
    min-height: 180px;
    padding: 24px;
  }

  .brand-area {
    max-width: 100%;

    h1 {
      font-size: 28px;
    }
  }

  .register-right {
    padding: 20px;
  }

  .form-area {
    padding: 20px;
  }
}
</style>
