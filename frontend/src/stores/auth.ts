import axios from 'axios'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { LoginRequest, RegisterRequest, UpdateProfileRequest, User } from '@/types'
import { authApi } from '@/api'
import { useChatStore } from './chat'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const token = ref<string | null>(localStorage.getItem('token'))
  const loading = ref(false)
  const error = ref<string | null>(null)

  const isAuthenticated = computed(() => !!token.value)
  const currentUser = computed(() => user.value)

  const login = async (credentials: LoginRequest): Promise<boolean> => {
    loading.value = true
    error.value = null

    try {
      const response = await authApi.login(credentials)

      if (response.data.success && response.data.token) {
        token.value = response.data.token
        user.value = response.data.user || null
        localStorage.setItem('token', response.data.token)
        useChatStore().syncSessionWithCurrentUser()
        return true
      }

      error.value = response.data.message || '登录失败'
      return false
    } catch (err) {
      error.value = getApiErrorMessage(err, '登录失败')
      return false
    } finally {
      loading.value = false
    }
  }

  const register = async (data: RegisterRequest): Promise<boolean> => {
    loading.value = true
    error.value = null

    try {
      const response = await authApi.register(data)

      if (response.data.success && response.data.token) {
        token.value = response.data.token
        user.value = response.data.user || null
        localStorage.setItem('token', response.data.token)
        useChatStore().syncSessionWithCurrentUser()
        return true
      }

      error.value = response.data.message || '注册失败'
      return false
    } catch (err) {
      error.value = getApiErrorMessage(err, '注册失败')
      return false
    } finally {
      loading.value = false
    }
  }

  const updateProfile = async (data: UpdateProfileRequest): Promise<boolean> => {
    loading.value = true
    error.value = null

    try {
      const response = await authApi.updateProfile(data)
      user.value = response.data
      return true
    } catch (err) {
      error.value = getApiErrorMessage(err, '资料更新失败')
      return false
    } finally {
      loading.value = false
    }
  }

  const logout = () => {
    user.value = null
    token.value = null
    localStorage.removeItem('token')
    useChatStore().resetLocalConversation()
  }

  const initializeAuth = async (): Promise<boolean> => {
    const storedToken = localStorage.getItem('token')
    if (!storedToken) {
      token.value = null
      user.value = null
      return false
    }

    try {
      const response = await authApi.getCurrentUser()
      if (response.data) {
        token.value = storedToken
        user.value = response.data
        useChatStore().syncSessionWithCurrentUser()
        return true
      }
    } catch {
      localStorage.removeItem('token')
    }

    token.value = null
    user.value = null
    return false
  }

  const clearError = () => {
    error.value = null
  }

  return {
    user,
    token,
    loading,
    error,
    isAuthenticated,
    currentUser,
    login,
    register,
    updateProfile,
    logout,
    initializeAuth,
    clearError,
  }
})

function getApiErrorMessage(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error)) {
    const apiMessage = error.response?.data?.message
    if (typeof apiMessage === 'string' && apiMessage.trim()) {
      return apiMessage
    }
  }

  return error instanceof Error ? error.message : fallback
}
