import axios from 'axios'
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import type {
  AuthApiResponse,
  LoginRequest,
  RegisterRequest,
  UpdateProfileRequest,
  ChatRequest,
  GraphContext,
  GraphContextRequest,
  User,
  UserScheduleResponse,
} from '@/types'
import { useAuthStore } from '@/stores/auth'

const apiClient: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('token')
    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error),
)

apiClient.interceptors.response.use(
  (response: AxiosResponse) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      const authStore = useAuthStore()
      authStore.logout()
      window.location.href = '/login'
    }
    return Promise.reject(error)
  },
)

export const authApi = {
  login: (data: LoginRequest): Promise<AxiosResponse<AuthApiResponse>> => apiClient.post('/auth/login', data),
  register: (data: RegisterRequest): Promise<AxiosResponse<AuthApiResponse>> => apiClient.post('/auth/register', data),
  getCurrentUser: (): Promise<AxiosResponse<User>> => apiClient.get('/auth/me'),
  getMySchedule: (): Promise<AxiosResponse<UserScheduleResponse>> => apiClient.get('/auth/me/schedule'),
  updateProfile: (data: UpdateProfileRequest): Promise<AxiosResponse<User>> => apiClient.put('/auth/me', data),
}

export const chatApi = {
  sendMessage: (data: ChatRequest): Promise<AxiosResponse<string>> => apiClient.post('/chat', data),

  sendMessageStream: async (data: ChatRequest): Promise<ReadableStream<Uint8Array>> => {
    const baseURL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api'
    const response = await fetch(`${baseURL}/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        Authorization: `Bearer ${localStorage.getItem('token') || ''}`,
      },
      body: JSON.stringify(data),
    })

    if (!response.ok) {
      const errText = await response.text().catch(() => '')
      if (response.status === 401) {
        localStorage.removeItem('token')
        const authStore = useAuthStore()
        authStore.logout()
        window.location.href = '/login'
      }
      throw new Error(errText || `流式请求失败 (${response.status})`)
    }

    if (!response.body) {
      throw new Error('Response body is null')
    }

    return response.body as ReadableStream<Uint8Array>
  },

  getHistory: (sessionId?: string): Promise<AxiosResponse<{ role: string; content: string }[]>> =>
    apiClient.get('/chat/history', { params: sessionId ? { sessionId } : undefined }),

  deleteHistory: (sessionId?: string): Promise<AxiosResponse<void>> =>
    apiClient.delete('/chat/history', { params: sessionId ? { sessionId } : undefined }),
}

export const adminApi = {
  getStats: (): Promise<AxiosResponse<any>> => apiClient.get('/admin/stats'),
  getCourses: (): Promise<AxiosResponse<any>> => apiClient.get('/admin/courses'),
}

export const graphApi = {
  getGraphData: (limit: number = 100, focusId?: string): Promise<AxiosResponse<any>> =>
    apiClient.get('/graph/data', { params: { limit, focusId } }),
  searchNodes: (keyword: string, limit: number = 10): Promise<AxiosResponse<any[]>> =>
    apiClient.get('/graph/search', { params: { keyword, limit } }),
  getGraphContext: (data: GraphContextRequest): Promise<AxiosResponse<GraphContext>> =>
    apiClient.post('/graph/context', data),
}

export default apiClient
