import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { chatApi } from '@/api'
import type {
  ActionPreviewArtifact,
  ActionResultArtifact,
  ChatMessage,
  ChatRequest,
  ChatStreamEvent,
  GraphContext,
  RecommendationArtifact,
} from '@/types'

const SESSION_STORAGE_KEY_PREFIX = 'chat_session_user_'
const WORKSPACE_STORAGE_KEY_PREFIX = 'chat_workspace_'

type MobilePanel = 'chat' | 'graph' | 'recommendation'

interface PersistedWorkspaceState {
  activeGraphContext: GraphContext | null
  activeRecommendation: RecommendationArtifact | null
  activeActionPreview: ActionPreviewArtifact | null
  activeActionResult: ActionResultArtifact | null
  selectedArtifactId: string | null
  highlightedNodeIds: string[]
  mobilePanel: MobilePanel
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const boundUserId = ref<number | null>(resolveCurrentUserId())
  const sessionId = ref<string>(getOrCreateSessionId(boundUserId.value))
  const loading = ref(false)
  const error = ref<string | null>(null)
  const streamingMessage = ref('')

  const activeGraphContext = ref<GraphContext | null>(null)
  const activeRecommendation = ref<RecommendationArtifact | null>(null)
  const activeActionPreview = ref<ActionPreviewArtifact | null>(null)
  const activeActionResult = ref<ActionResultArtifact | null>(null)
  const followStream = ref(true)
  const selectedArtifactId = ref<string | null>(null)
  const highlightedNodeIds = ref<string[]>([])
  const mobilePanel = ref<MobilePanel>('chat')

  const chatMessages = computed(() => messages.value)
  const isLoading = computed(() => loading.value)
  const currentSessionId = computed(() => sessionId.value)

  const syncSessionWithCurrentUser = () => {
    const nextUserId = resolveCurrentUserId()
    const userChanged = boundUserId.value !== nextUserId
    const nextSessionId = getOrCreateSessionId(nextUserId)
    const sessionChanged = sessionId.value !== nextSessionId

    boundUserId.value = nextUserId
    sessionId.value = nextSessionId

    if (userChanged || sessionChanged) {
      messages.value = []
      resetWorkspaceState(false)
      restoreWorkspaceState()
    }
  }

  const sendMessage = async (content: string): Promise<void> => {
    if (!content.trim()) {
      return
    }

    syncSessionWithCurrentUser()

    messages.value.push({
      id: generateMessageId(),
      role: 'user',
      content: content.trim(),
      timestamp: new Date(),
    })

    loading.value = true
    error.value = null

    try {
      const request: ChatRequest = {
        message: content.trim(),
        sessionId: sessionId.value,
      }

      const response = await chatApi.sendMessage(request)
      messages.value.push({
        id: generateMessageId(),
        role: 'assistant',
        content: response.data || '抱歉，我没有完全理解你的问题。',
        timestamp: new Date(),
      })
    } catch (err) {
      error.value = err instanceof Error ? err.message : '发送消息失败'
      messages.value.push({
        id: generateMessageId(),
        role: 'assistant',
        content: '抱歉，服务出现了一些问题，请稍后再试。',
        timestamp: new Date(),
      })
    } finally {
      loading.value = false
    }
  }

  const sendMessageStream = async (content: string): Promise<void> => {
    if (!content.trim()) {
      return
    }

    syncSessionWithCurrentUser()

    messages.value.push({
      id: generateMessageId(),
      role: 'user',
      content: content.trim(),
      timestamp: new Date(),
    })

    loading.value = true
    error.value = null
    streamingMessage.value = ''
    resetWorkspaceState(true)

    messages.value.push({
      id: generateMessageId(),
      role: 'assistant',
      content: '',
      timestamp: new Date(),
      isStreaming: true,
      streamingStatus: '正在准备回答...',
    })

    const applySseLine = (line: string) => {
      if (!line.startsWith('data:')) {
        return
      }

      const rawPayload = line.slice(5).trim()
      if (!rawPayload || rawPayload === '[DONE]') {
        return
      }

      const lastMessage = messages.value[messages.value.length - 1]
      if (!lastMessage || lastMessage.role !== 'assistant') {
        return
      }

      let parsed: ChatStreamEvent | null = null
      try {
        parsed = JSON.parse(rawPayload) as ChatStreamEvent
      } catch {
        if (rawPayload.startsWith('__STATUS__:')) {
          lastMessage.streamingStatus = translateStatus(rawPayload.slice('__STATUS__:'.length).trim())
          return
        }
        streamingMessage.value += rawPayload
        lastMessage.content = streamingMessage.value
        return
      }

      handleStreamEvent(parsed, lastMessage)
    }

    try {
      const request: ChatRequest = {
        message: content.trim(),
        sessionId: sessionId.value,
      }

      const stream = await chatApi.sendMessageStream(request)
      const reader = stream.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) {
          break
        }

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          applySseLine(line.replace(/\r$/, ''))
        }
      }

      if (buffer) {
        applySseLine(buffer.replace(/\r$/, ''))
      }

      finalizeStreamingMessage()
      persistWorkspaceState()
    } catch (err) {
      error.value = err instanceof Error ? err.message : '发送消息失败'
      const lastMessage = messages.value[messages.value.length - 1]
      if (lastMessage && lastMessage.role === 'assistant') {
        lastMessage.content = '抱歉，服务出现了一些问题，请稍后再试。'
        lastMessage.isStreaming = false
        lastMessage.streamingStatus = undefined
      }
    } finally {
      loading.value = false
      streamingMessage.value = ''
    }
  }

  const handleStreamEvent = (event: ChatStreamEvent, lastMessage: ChatMessage) => {
    switch (event.type) {
      case 'status': {
        const payload = event.payload as { status: string }
        lastMessage.streamingStatus = translateStatus(payload.status)
        break
      }
      case 'message_delta': {
        const payload = event.payload as { delta: string }
        streamingMessage.value += payload.delta
        lastMessage.content = streamingMessage.value
        break
      }
      case 'graph_snapshot': {
        const payload = event.payload as GraphContext
        activeGraphContext.value = payload
        highlightedNodeIds.value = payload.focusNodeIds || []
        if (followStream.value && payload.focusNodeIds?.length) {
          selectedArtifactId.value = payload.focusNodeIds[0] ?? null
        }
        mobilePanel.value = mobilePanel.value === 'chat' ? 'graph' : mobilePanel.value
        persistWorkspaceState()
        break
      }
      case 'graph_highlight': {
        const payload = event.payload as { focusNodeIds: string[] }
        highlightedNodeIds.value = payload.focusNodeIds || []
        if (followStream.value && payload.focusNodeIds?.length) {
          selectedArtifactId.value = payload.focusNodeIds[0] ?? null
        }
        persistWorkspaceState()
        break
      }
      case 'recommendation':
        activeRecommendation.value = event.payload as RecommendationArtifact
        mobilePanel.value = 'recommendation'
        persistWorkspaceState()
        break
      case 'action_preview':
        activeActionPreview.value = event.payload as ActionPreviewArtifact
        persistWorkspaceState()
        break
      case 'action_result':
        activeActionResult.value = event.payload as ActionResultArtifact
        activeActionPreview.value = event.payload as ActionResultArtifact
        persistWorkspaceState()
        break
      case 'done':
        finalizeStreamingMessage()
        persistWorkspaceState()
        break
      default:
        break
    }
  }

  const loadHistory = async (): Promise<void> => {
    try {
      syncSessionWithCurrentUser()
      const response = await chatApi.getHistory(sessionId.value)
      messages.value = (response.data || []).map((message) => ({
        id: generateMessageId(),
        role: message.role as 'user' | 'assistant',
        content: message.content,
        timestamp: new Date(),
      }))
      restoreWorkspaceState()
    } catch (err) {
      console.error('加载聊天历史失败', err)
    }
  }

  const resetLocalConversation = () => {
    messages.value = []
    syncSessionWithCurrentUser()
    sessionId.value = createAndStoreSessionId(boundUserId.value)
    resetWorkspaceState(true)
  }

  const clearMessages = async (): Promise<void> => {
    syncSessionWithCurrentUser()
    loading.value = true
    error.value = null

    try {
      await chatApi.deleteHistory(sessionId.value)
      resetLocalConversation()
    } catch (err) {
      error.value = err instanceof Error ? err.message : '删除对话失败'
      throw err
    } finally {
      loading.value = false
    }
  }

  const regenerateSession = () => {
    syncSessionWithCurrentUser()
    sessionId.value = createAndStoreSessionId(boundUserId.value)
    resetWorkspaceState(true)
  }

  const setFollowStream = (nextValue: boolean) => {
    followStream.value = nextValue
    persistWorkspaceState()
  }

  const setSelectedArtifactId = (nodeId: string | null) => {
    selectedArtifactId.value = nodeId
    persistWorkspaceState()
  }

  const setMobilePanel = (panel: MobilePanel) => {
    mobilePanel.value = panel
    persistWorkspaceState()
  }

  const focusCourseInGraph = (courseId: string) => {
    const matchedNode = activeGraphContext.value?.nodes.find(
      (node) =>
        node.metadata?.entityType === 'Course' &&
        String(node.metadata?.entityId || '').toLowerCase() === courseId.toLowerCase(),
    )

    if (matchedNode) {
      selectedArtifactId.value = matchedNode.id
      highlightedNodeIds.value = [matchedNode.id]
      mobilePanel.value = 'graph'
      persistWorkspaceState()
    }
  }

  const setActionPreview = (artifact: ActionPreviewArtifact | null) => {
    activeActionPreview.value = artifact
    persistWorkspaceState()
  }

  const setGraphContext = (graphContext: GraphContext | null) => {
    activeGraphContext.value = graphContext
    highlightedNodeIds.value = graphContext?.focusNodeIds || []
    selectedArtifactId.value = graphContext?.focusNodeIds?.[0] || null
    persistWorkspaceState()
  }

  const restoreWorkspaceState = () => {
    const raw = safeStorageGetItem(getWorkspaceStorageKey(sessionId.value))
    if (!raw) {
      return
    }

    try {
      const parsed = JSON.parse(raw) as PersistedWorkspaceState
      activeGraphContext.value = parsed.activeGraphContext
      activeRecommendation.value = parsed.activeRecommendation
      activeActionPreview.value = parsed.activeActionPreview
      activeActionResult.value = parsed.activeActionResult
      selectedArtifactId.value = parsed.selectedArtifactId
      highlightedNodeIds.value = parsed.highlightedNodeIds || []
      mobilePanel.value = parsed.mobilePanel || 'chat'
    } catch (err) {
      console.error('恢复工作台状态失败', err)
    }
  }

  const clearError = () => {
    error.value = null
  }

  const finalizeStreamingMessage = () => {
    const lastMessage = messages.value[messages.value.length - 1]
    if (lastMessage && lastMessage.role === 'assistant') {
      lastMessage.isStreaming = false
      lastMessage.streamingStatus = undefined
    }
  }

  const resetWorkspaceState = (persist: boolean) => {
    activeGraphContext.value = null
    activeRecommendation.value = null
    activeActionPreview.value = null
    activeActionResult.value = null
    selectedArtifactId.value = null
    highlightedNodeIds.value = []
    mobilePanel.value = 'chat'

    if (persist) {
      persistWorkspaceState()
    }
  }

  const persistWorkspaceState = () => {
    try {
      const payload: PersistedWorkspaceState = {
        activeGraphContext: activeGraphContext.value,
        activeRecommendation: activeRecommendation.value,
        activeActionPreview: activeActionPreview.value,
        activeActionResult: activeActionResult.value,
        selectedArtifactId: selectedArtifactId.value,
        highlightedNodeIds: highlightedNodeIds.value,
        mobilePanel: mobilePanel.value,
      }
      safeStorageSetItem(getWorkspaceStorageKey(sessionId.value), JSON.stringify(payload))
    } catch (error) {
      console.warn(`序列化工作台状态失败: ${getWorkspaceStorageKey(sessionId.value)}`, error)
    }
  }

  return {
    messages,
    sessionId,
    loading,
    error,
    streamingMessage,
    activeGraphContext,
    activeRecommendation,
    activeActionPreview,
    activeActionResult,
    followStream,
    selectedArtifactId,
    highlightedNodeIds,
    mobilePanel,
    chatMessages,
    isLoading,
    currentSessionId,
    syncSessionWithCurrentUser,
    sendMessage,
    sendMessageStream,
    loadHistory,
    clearMessages,
    resetLocalConversation,
    regenerateSession,
    clearError,
    setFollowStream,
    setSelectedArtifactId,
    setMobilePanel,
    focusCourseInGraph,
    setActionPreview,
    setGraphContext,
    restoreWorkspaceState,
  }
})

function createAndStoreSessionId(userId: number | null): string {
  const nextSessionId = generateSessionId(userId)
  safeStorageSetItem(getSessionStorageKey(userId), nextSessionId)
  return nextSessionId
}

function getOrCreateSessionId(userId: number | null): string {
  const storageKey = getSessionStorageKey(userId)
  const storedSessionId = safeStorageGetItem(storageKey)
  if (storedSessionId) {
    return storedSessionId
  }
  return createAndStoreSessionId(userId)
}

function getSessionStorageKey(userId: number | null): string {
  return userId == null ? `${SESSION_STORAGE_KEY_PREFIX}guest` : `${SESSION_STORAGE_KEY_PREFIX}${userId}`
}

function getWorkspaceStorageKey(sessionId: string): string {
  return `${WORKSPACE_STORAGE_KEY_PREFIX}${sessionId}`
}

function generateSessionId(userId: number | null): string {
  const owner = userId == null ? 'guest' : `user_${userId}`
  return `${owner}_session_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`
}

function generateMessageId(): string {
  return `msg_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`
}

function safeStorageGetItem(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch (error) {
    console.warn(`读取本地存储失败: ${key}`, error)
    return null
  }
}

function safeStorageSetItem(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch (error) {
    console.warn(`写入本地存储失败: ${key}`, error)
  }
}

function resolveCurrentUserId(): number | null {
  const token = safeStorageGetItem('token')
  if (!token) {
    return null
  }

  const parts = token.split('.')
  if (parts.length !== 3) {
    return null
  }

  const payload = parts[1]
  if (!payload) {
    return null
  }

  try {
    const parsed = JSON.parse(decodeBase64Url(payload)) as { sub?: string | number }
    const userId = Number(parsed.sub)
    return Number.isFinite(userId) ? userId : null
  } catch {
    return null
  }
}

function decodeBase64Url(value: string): string {
  let normalized = value.replace(/-/g, '+').replace(/_/g, '/')
  const padding = normalized.length % 4
  if (padding > 0) {
    normalized += '='.repeat(4 - padding)
  }
  return atob(normalized)
}

function translateStatus(status: string): string {
  const table: Record<string, string> = {
    'Request received, analyzing intent': '正在理解你的问题',
    'Calling agents and graph tools': '正在联动图谱与智能体',
    'Running QA agent': '正在检索问答证据',
    'Running recommender agent': '正在生成推荐与学习路径',
    'Running action agent': '正在处理选课操作',
    'Searching knowledge graph: courses': '正在查询课程图谱',
    'Searching knowledge graph: course concepts': '正在分析课程知识点',
    'Searching knowledge graph: prerequisites': '正在梳理先修关系',
    'Searching knowledge graph: course teachers': '正在查询授课教师',
    'Searching knowledge graph: teacher courses': '正在查询教师课程',
    'Searching knowledge graph: enrolled courses': '正在读取已选课程',
    'Searching knowledge graph: courses by concept': '正在按知识点匹配课程',
    'Searching knowledge graph: course recommendations': '正在生成推荐子图',
    'Executing enrollment operation': '正在执行选课',
    'Executing drop operation': '正在执行退课',
    'Error while generating answer': '回答生成失败',
    'Answer generation completed': '回答生成完成',
  }

  return table[status] || status
}
