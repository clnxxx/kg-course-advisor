<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { ElInput } from 'element-plus'
import {
  Connection,
  Delete,
  Promotion,
  RefreshRight,
  Setting,
  Star,
  SwitchButton,
  User,
  Warning,
} from '@element-plus/icons-vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import KnowledgeGraphCanvas from '@/components/KnowledgeGraphCanvas.vue'
import { graphApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useChatStore } from '@/stores/chat'
import type {
  ActionPreviewArtifact,
  ConflictCourseRef,
  GraphContext,
  GraphEdge,
  GraphNode,
  RecommendationArtifact,
  RecommendationCourse,
  TargetTimeRef,
} from '@/types'

marked.setOptions({ breaks: true, gfm: true })

const chatStore = useChatStore()
const authStore = useAuthStore()

const inputMessage = ref('')
const messagesContainer = ref<HTMLElement | null>(null)
const inputRef = ref<InstanceType<typeof ElInput> | null>(null)
const selectedGraphNode = ref<GraphNode | null>(null)
const actionDrawerVisible = ref(false)
const actionDrawerLoading = ref(false)
const previewCourse = ref<RecommendationCourse | null>(null)
const previewGraphContext = ref<GraphContext | null>(null)
const previewActionArtifact = ref<ActionPreviewArtifact | null>(null)

const sampleQuestions = [
  { label: '教师查询', text: '数据结构课程的授课老师是谁？' },
  { label: '学习路径', text: '帮我规划数据科学方向学习路径' },
  { label: 'AI 选课', text: '我适合先学哪几门 AI 课程？' },
  { label: '图谱浏览', text: '给我看一下课程之间的先修关系' },
]

sampleQuestions.splice(3, 1, {
  label: '鍏堜慨鍏崇郴',
  text: '缁欐垜鐪嬩竴涓嬫暟鎹粨鏋勮绋嬬殑鍏堜慨鍏崇郴',
})

sampleQuestions.splice(3, 1, {
  label: '先修关系',
  text: '给我看一下数据结构课程的先修关系',
})

sampleQuestions.splice(3, 1, {
  label: '先修关系',
  text: '给我看一下数据结构课程的先修关系',
})

sampleQuestions.splice(
  0,
  sampleQuestions.length,
  { label: '授课老师', text: '数据结构课程的授课老师是谁' },
  { label: '学习路径', text: '帮我规划数据科学方向学习路径' },
  { label: 'AI 选课', text: '我适合先学哪几门 AI 课程' },
  { label: '先修关系', text: '给我看一下数据结构课程的先修关系' },
)

const normalizeMarkdownContent = (content: string): string =>
  content
    .replace(/\r\n/g, '\n')
    .replace(/(^|\n)(\s*[-*+•●○◦▪▫■□])\s*\n(?=\S)/g, '$1- ')
    .replace(/(^|\n)(\s*\d+\.)\s*\n(?=\S)/g, '$1$2 ')
    .replace(/(^|\n)\s*[•●○◦▪▫■□]\s+/g, '$1- ')

const renderMarkdown = (content: string): string =>
  DOMPurify.sanitize(marked.parse(normalizeMarkdownContent(content)) as string)

type DisplayRecommendationCourse = RecommendationCourse & {
  displayOrder: number | null
}

const orderedRecommendations = computed<DisplayRecommendationCourse[]>(() => {
  const artifact = chatStore.activeRecommendation
  if (!artifact?.courses?.length) {
    return []
  }

  const uniqueCourses = artifact.courses.filter((course, index, list) => {
    const courseKey = `${course.name}::${(course.teacherNames || []).join('|')}`
    return list.findIndex((item) => `${item.name}::${(item.teacherNames || []).join('|')}` === courseKey) === index
  })

  return [...uniqueCourses]
    .sort((left, right) => {
    if (left.pathOrder == null && right.pathOrder == null) {
      return 0
    }
    if (left.pathOrder == null) {
      return 1
    }
    if (right.pathOrder == null) {
      return -1
    }
    return left.pathOrder - right.pathOrder
    })
    .map((course, index) => ({
      ...course,
      displayOrder: index + 1,
    }))
})

const latestAssistantMessageId = computed(() => {
  for (let index = chatStore.chatMessages.length - 1; index >= 0; index -= 1) {
    const message = chatStore.chatMessages[index]
    if (message?.role === 'assistant') {
      return message.id
    }
  }
  return null
})

const activeRecommendation = computed<RecommendationArtifact | null>(() => chatStore.activeRecommendation)
const effectiveGraphContext = computed<GraphContext | null>(() => previewGraphContext.value || chatStore.activeGraphContext)
const effectiveActionPreview = computed<ActionPreviewArtifact | null>(
  () => previewActionArtifact.value || chatStore.activeActionPreview,
)
const graphFocusCount = computed(() => effectiveGraphContext.value?.focusNodeIds?.length || 0)
const annotationCount = computed(() => effectiveGraphContext.value?.annotations?.length || 0)

const workspaceMode = computed(() => {
  if (chatStore.activeActionResult?.status === 'SUCCESS') {
    return '选课已执行'
  }
  if (previewCourse.value) {
    return '待确认选课'
  }
  if (chatStore.activeRecommendation?.courses?.length) {
    return '推荐路径'
  }
  if (chatStore.activeGraphContext?.nodes?.length) {
    return '问答证据'
  }
  return '对话工作台'
})

const workspaceLead = computed(() => {
  if (previewCourse.value) {
    return `正在评估「${previewCourse.value.name}」的先修链、时间安排与冲突范围。`
  }
  if (chatStore.activeActionResult?.message) {
    return chatStore.activeActionResult.message
  }
  if (activeRecommendation.value?.recommendationReason) {
    return activeRecommendation.value.recommendationReason
  }
  return '左侧持续对话，右侧实时解释图谱；推荐、证据和选课影响会同步出现在同一个工作台里。'
})

const graphPanelTitle = computed(() => {
  if (selectedGraphNode.value) {
    return `${selectedGraphNode.value.label} · 实时上下文`
  }
  if (previewCourse.value) {
    return `${previewCourse.value.name} · 决策子图`
  }
  if (activeRecommendation.value?.courses?.length) {
    return '推荐路径子图'
  }
  if (chatStore.activeGraphContext?.nodes?.length) {
    return '问答证据图谱'
  }
  return '实时知识图谱'
})

const graphPanelSubtitle = computed(() => {
  const context = effectiveGraphContext.value
  if (!context?.stats) {
    return '回答流式生成时，课程、知识点、教师与时间节点会在这里同步展开。'
  }

  const parts = [`${context.stats.nodeCount} 个节点`, `${context.stats.edgeCount} 条关系`]
  if (previewCourse.value) {
    parts.unshift('选课影响范围')
  } else if (activeRecommendation.value?.courses?.length) {
    parts.unshift('推荐路径联动')
  } else {
    parts.unshift('证据高亮中')
  }
  return parts.join(' · ')
})

const evidenceAnnotations = computed(() => effectiveGraphContext.value?.annotations || [])

const actionResultTone = computed(() => {
  const status = chatStore.activeActionResult?.status
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'warning'
  if (status === 'ERROR') return 'danger'
  return 'info'
})

const sendMessage = async () => {
  if (!inputMessage.value.trim() || chatStore.isLoading) {
    return
  }

  const message = inputMessage.value.trim()
  inputMessage.value = ''
  previewCourse.value = null
  previewGraphContext.value = null
  previewActionArtifact.value = null
  await chatStore.sendMessageStream(message)
  scrollToBottom()
}

const sendSampleQuestion = (question: string) => {
  inputMessage.value = question
  void sendMessage()
}

const clearChat = async () => {
  try {
    actionDrawerVisible.value = false
    previewCourse.value = null
    previewGraphContext.value = null
    previewActionArtifact.value = null
    await chatStore.clearMessages()
    selectedGraphNode.value = null
  } catch (error) {
    console.error('清空对话失败', error)
  }
}

const scrollToBottom = async () => {
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

const formatTime = (date: Date) =>
  new Date(date).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  })

const formatWeekday = (weekdayIndex?: number | null) => {
  const map = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
  if (weekdayIndex == null) {
    return '未排课'
  }
  return map[weekdayIndex - 1] || `周${weekdayIndex}`
}

const formatHalfDay = (halfDay?: string | null) => {
  if (!halfDay) {
    return ''
  }
  if (halfDay === 'morning') {
    return '上午'
  }
  if (halfDay === 'afternoon') {
    return '下午'
  }
  return halfDay
}

const formatTargetTime = (targetTime?: TargetTimeRef | null) => {
  if (!targetTime) {
    return '暂无明确排课时间'
  }

  const parts = [
    formatWeekday(targetTime.weekdayIndex),
    formatHalfDay(targetTime.halfDay),
    targetTime.periodIndex != null ? `第 ${targetTime.periodIndex} 节` : '',
  ].filter(Boolean)

  if (targetTime.startTime && targetTime.endTime) {
    parts.push(`${targetTime.startTime} - ${targetTime.endTime}`)
  }

  return parts.length ? parts.join(' · ') : '暂无明确排课时间'
}

const isLastAssistantMessage = (messageId: string) => latestAssistantMessageId.value === messageId

const focusFromAnnotation = (nodeIds?: string[]) => {
  const targetId = nodeIds?.[0]
  if (!targetId) {
    return
  }
  previewGraphContext.value = null
  previewActionArtifact.value = null
  chatStore.setSelectedArtifactId(targetId)
  chatStore.setMobilePanel('graph')
}

const focusRecommendationCourse = async (course: RecommendationCourse) => {
  previewCourse.value = null
  previewGraphContext.value = null
  previewActionArtifact.value = null

  if (course.id) {
    chatStore.focusCourseInGraph(course.id)
  }

  const hasNodeInCurrentContext = chatStore.activeGraphContext?.nodes.some(
    (node) =>
      node.metadata?.entityType === 'Course' &&
      String(node.metadata?.entityId || '').toLowerCase() === String(course.id || '').toLowerCase(),
  )

  if (hasNodeInCurrentContext) {
    chatStore.setMobilePanel('graph')
    return
  }

  try {
    const response = await graphApi.getGraphContext({
      scenario: 'recommendation',
      courseIds: course.id ? [course.id] : undefined,
      courseNames: [course.name],
      conceptNames: course.concepts,
      includePrerequisites: true,
      limit: 26,
    })
    chatStore.setGraphContext(response.data)
    chatStore.setMobilePanel('graph')
  } catch (error) {
    console.error('聚焦推荐课程失败', error)
    ElMessage.warning('图谱聚焦失败，请稍后再试')
  }
}

const buildPreviewArtifactFromGraph = (course: RecommendationCourse, context: GraphContext): ActionPreviewArtifact => {
  const targetNode = context.nodes.find(
    (node) =>
      node.group === 'Course' && String(node.metadata?.entityId || '').toLowerCase() === String(course.id || '').toLowerCase(),
  )

  const conflictCourseIds = new Set(
    context.edges
      .filter((edge) => edge.label === 'USER_COURSE' && edge.metadata?.kind === 'conflict-course')
      .map((edge) => edge.to),
  )

  const conflictCourses: ConflictCourseRef[] = context.nodes
    .filter((node) => conflictCourseIds.has(node.id))
    .map((node) => ({
      id: String(node.metadata?.entityId || ''),
      name: node.label,
    }))

  const targetTime = resolveTargetTime(context, targetNode?.id || null)

  return {
    status: conflictCourses.length ? 'FAILED' : 'PENDING_CONFIRMATION',
    operationType: 'SELECT',
    affectedCourse: {
      id: course.id,
      name: course.name,
      content: course.content,
    },
    conflictCourses,
    targetTime,
    canProceed: conflictCourses.length === 0,
  }
}

const resolveTargetTime = (context: GraphContext, courseNodeId: string | null): TargetTimeRef | null => {
  if (!courseNodeId) {
    return null
  }

  const candidateEdge = context.edges.find(
    (edge) =>
      edge.from === courseNodeId &&
      edge.label === 'COURSE_TIME' &&
      (edge.metadata?.kind === 'target-time' || edge.highlighted),
  )

  if (!candidateEdge) {
    return null
  }

  const timeNode = context.nodes.find((node) => node.id === candidateEdge.to)
  if (!timeNode) {
    return null
  }

  return {
    weekdayIndex: typeof timeNode.metadata?.weekdayIndex === 'number' ? timeNode.metadata.weekdayIndex : null,
    halfDay: typeof timeNode.metadata?.halfDay === 'string' ? timeNode.metadata.halfDay : null,
    periodIndex: typeof timeNode.metadata?.periodIndex === 'number' ? timeNode.metadata.periodIndex : null,
    startTime: typeof timeNode.metadata?.startTime === 'string' ? timeNode.metadata.startTime : null,
    endTime: typeof timeNode.metadata?.endTime === 'string' ? timeNode.metadata.endTime : null,
  }
}

const openActionDrawer = async (course: RecommendationCourse) => {
  previewCourse.value = course
  previewGraphContext.value = null
  previewActionArtifact.value = null
  actionDrawerVisible.value = true
  actionDrawerLoading.value = true

  try {
    const response = await graphApi.getGraphContext({
      scenario: 'action',
      courseIds: course.id ? [course.id] : undefined,
      courseNames: [course.name],
      includeSchedule: true,
      includePrerequisites: true,
      includeConflicts: true,
      limit: 36,
    })

    previewGraphContext.value = response.data
    previewActionArtifact.value = buildPreviewArtifactFromGraph(course, response.data)

    try {
      chatStore.setGraphContext(response.data)
      chatStore.setSelectedArtifactId(response.data.focusNodeIds?.[0] || null)
      chatStore.setMobilePanel('graph')
    } catch (stateError) {
      console.warn('选课预览状态同步失败', stateError)
    }
  } catch (error) {
    console.error('加载选课预览失败', error)
    ElMessage.error('选课预览加载失败，请稍后再试')
  } finally {
    actionDrawerLoading.value = false
  }
}

const confirmEnrollment = async () => {
  if (!previewCourse.value || chatStore.isLoading) {
    return
  }

  const payload = previewCourse.value.id || previewCourse.value.name
  actionDrawerVisible.value = false
  await chatStore.sendMessageStream(`帮我选 ${payload}`)
  previewCourse.value = null
  previewGraphContext.value = null
  previewActionArtifact.value = null
  scrollToBottom()
}

const showRealtimeGraph = () => {
  chatStore.setMobilePanel('graph')
}

const closeActionDrawer = () => {
  previewCourse.value = null
  previewGraphContext.value = null
  previewActionArtifact.value = null
}

const describeRecommendationMeta = (course: DisplayRecommendationCourse) => {
  const parts = []
  if (course.displayOrder != null) {
    parts.push(`路径 ${course.displayOrder}`)
  }
  if (course.teacherNames.length) {
    parts.push(`${course.teacherNames.length} 位教师`)
  }
  if (course.prerequisites.length) {
    parts.push(`${course.prerequisites.length} 个先修概念`)
  }
  return parts.join(' · ') || '课程推荐'
}

const describeRecommendationSummary = (course: DisplayRecommendationCourse) => {
  const parts = []
  if (course.displayOrder != null) {
    parts.push(`路径 ${course.displayOrder}`)
  }
  if (course.teacherNames.length) {
    parts.push(`${course.teacherNames.length} 位教师`)
  }
  if (course.prerequisites.length) {
    parts.push(`${course.prerequisites.length} 门先修课程`)
  }
  return parts.join(' · ') || '课程推荐'
}

const describeGraphReason = (course: DisplayRecommendationCourse) => {
  if (course.reason) {
    return course.reason
  }
  if (course.concepts.length) {
    return `命中概念：${course.concepts.join('、')}`
  }
  return '可在图谱中展开教师、知识点与先修链。'
}

const hasMessages = computed(() => chatStore.chatMessages.length > 0)

onMounted(async () => {
  if (!authStore.currentUser) {
    await authStore.initializeAuth()
  }
  chatStore.syncSessionWithCurrentUser()
  if (!chatStore.chatMessages.length) {
    await chatStore.loadHistory()
  } else {
    chatStore.restoreWorkspaceState()
  }
  await scrollToBottom()
  inputRef.value?.focus()
})

watch(
  () => {
    const last = chatStore.chatMessages[chatStore.chatMessages.length - 1]
    return {
      len: chatStore.chatMessages.length,
      lastContent: last?.content || '',
      lastStreaming: last?.isStreaming || false,
      lastStatus: last?.streamingStatus || '',
      recommendationSize: chatStore.activeRecommendation?.courses?.length || 0,
      previewStatus: chatStore.activeActionPreview?.status || '',
      resultStatus: chatStore.activeActionResult?.status || '',
    }
  },
  async () => {
    await scrollToBottom()
  },
  { flush: 'post' },
)

watch(
  () => chatStore.activeActionResult,
  (value) => {
    if (!value) {
      return
    }
    previewCourse.value = null
    previewGraphContext.value = null
    previewActionArtifact.value = null
  },
)
</script>

<template>
  <div class="chat-workbench">
    <section class="workspace-topbar">
      <div class="workspace-actions">
        <div class="mode-pill">
          <span class="mode-dot" />
          {{ workspaceMode }}
        </div>
        <el-button round :icon="Connection" @click="showRealtimeGraph">实时图谱</el-button>
        <el-button round :icon="RefreshRight" :loading="chatStore.isLoading" @click="clearChat">新会话</el-button>
      </div>
    </section>

    <div class="mobile-tabs">
      <button
        class="mobile-tab"
        :class="{ active: chatStore.mobilePanel === 'chat' }"
        @click="chatStore.setMobilePanel('chat')"
      >
        对话
      </button>
      <button
        class="mobile-tab"
        :class="{ active: chatStore.mobilePanel === 'graph' }"
        @click="chatStore.setMobilePanel('graph')"
      >
        图谱
      </button>
      <button
        class="mobile-tab"
        :class="{ active: chatStore.mobilePanel === 'recommendation' }"
        @click="chatStore.setMobilePanel('recommendation')"
      >
        推荐
      </button>
    </div>

    <div class="workspace-grid">
      <section class="timeline-pane" :class="{ 'mobile-hidden': chatStore.mobilePanel !== 'chat' }">
        <div ref="messagesContainer" class="messages-container">
          <div class="messages-inner">
            <div v-if="!hasMessages" class="welcome-shell glass-card">
              <div class="welcome-copy">
                <span class="welcome-kicker">Graph-first Q&A</span>
                <h2>开始提问</h2>
              </div>

              <div class="sample-grid">
                <button
                  v-for="item in sampleQuestions"
                  :key="item.text"
                  class="sample-card"
                  @click="sendSampleQuestion(item.text)"
                >
                  <span class="sample-label">{{ item.label }}</span>
                  <strong>{{ item.text }}</strong>
                </button>
              </div>
            </div>

            <div
              v-for="message in chatStore.chatMessages"
              :key="message.id"
              :class="['message-row', message.role]"
            >
              <div class="message-wrap">
                <div class="msg-avatar">
                  <div v-if="message.role === 'user'" class="avatar user-avatar">
                    <el-icon :size="16"><User /></el-icon>
                  </div>
                  <div v-else class="avatar ai-avatar">
                    <el-icon :size="16"><Connection /></el-icon>
                  </div>
                </div>

                <div class="msg-body">
                  <div class="msg-meta">
                    <span class="msg-name">{{ message.role === 'user' ? '你' : '图谱助手' }}</span>
                    <span class="msg-time">{{ formatTime(message.timestamp) }}</span>
                  </div>

                  <div class="msg-content" :class="{ streaming: message.isStreaming }">
                    <div
                      v-if="message.role === 'assistant'"
                      class="markdown-body"
                      v-html="renderMarkdown(message.content)"
                    />
                    <span v-else>{{ message.content }}</span>

                    <div
                      v-if="message.isStreaming && message.streamingStatus"
                      class="streaming-status"
                    >
                      {{ message.streamingStatus }}
                    </div>
                    <span v-if="message.isStreaming" class="streaming-dot" />
                  </div>

                  <div
                    v-if="message.role === 'assistant' && isLastAssistantMessage(message.id) && evidenceAnnotations.length"
                    class="evidence-chips"
                  >
                    <button
                      v-for="annotation in evidenceAnnotations"
                      :key="annotation.id"
                      class="evidence-chip"
                      @click="focusFromAnnotation(annotation.relatedNodeIds)"
                    >
                      <span>{{ annotation.title }}</span>
                      <small>{{ annotation.content }}</small>
                    </button>
                  </div>
                </div>
              </div>
            </div>

            <div
              v-if="activeRecommendation?.courses?.length"
              class="artifact-section desktop-only"
            >
              <div class="recommend-grid">
                <article
                  v-for="course in orderedRecommendations"
                  :key="course.id || course.name"
                  class="recommend-card glass-card"
                >
                  <div class="recommend-top">
                    <div>
                      <span class="recommend-order">路径 {{ course.displayOrder ?? '·' }}</span>
                      <h4>{{ course.name }}</h4>
                    </div>
                    <el-tag v-if="course.isEnrolled" type="success" round>已选</el-tag>
                  </div>

                  <p class="recommend-meta">{{ describeRecommendationSummary(course) }}</p>
                  <p v-if="course.teacherNames.length" class="recommend-teachers">
                    教师：{{ course.teacherNames.slice(0, 3).join(' / ') }}{{ course.teacherNames.length > 3 ? ' 等' : '' }}
                  </p>
                  <p class="recommend-reason">{{ describeGraphReason(course) }}</p>

                  <div v-if="course.concepts.length" class="chip-row">
                    <span v-for="concept in course.concepts" :key="concept" class="soft-chip">{{ concept }}</span>
                  </div>

                  <div v-if="course.prerequisites.length" class="chip-row subdued">
                    <span v-for="item in course.prerequisites" :key="item" class="soft-chip subdued">{{ item }}</span>
                  </div>

                  <div class="recommend-actions">
                    <el-button round @click="focusRecommendationCourse(course)">图谱聚焦</el-button>
                    <el-button
                      type="primary"
                      round
                      :icon="Star"
                      :disabled="course.isEnrolled"
                      @click="openActionDrawer(course)"
                    >
                      选课评估
                    </el-button>
                  </div>
                </article>
              </div>
            </div>

            <div
              v-if="chatStore.activeActionResult?.message"
              class="artifact-section"
            >
              <article class="action-result-card glass-card" :class="actionResultTone">
                <div class="action-result-head">
                  <div>
                    <span class="action-status">{{ chatStore.activeActionResult.status }}</span>
                    <h4>{{ chatStore.activeActionResult.affectedCourse?.name || '课程操作' }}</h4>
                  </div>
                  <el-icon :size="20">
                    <Warning v-if="actionResultTone === 'warning' || actionResultTone === 'danger'" />
                    <SwitchButton v-else />
                  </el-icon>
                </div>

                <p>{{ chatStore.activeActionResult.message }}</p>

                <div class="action-detail-grid">
                  <div>
                    <span>目标时间</span>
                    <strong>{{ formatTargetTime(chatStore.activeActionResult.targetTime) }}</strong>
                  </div>
                  <div>
                    <span>冲突课程</span>
                    <strong>
                      {{
                        chatStore.activeActionResult.conflictCourses?.length
                          ? chatStore.activeActionResult.conflictCourses.map((item) => item.name).join('、')
                          : '无冲突'
                      }}
                    </strong>
                  </div>
                </div>
              </article>
            </div>
          </div>
        </div>
      </section>

      <section class="graph-pane" :class="{ 'mobile-hidden': chatStore.mobilePanel !== 'graph' }">
        <KnowledgeGraphCanvas
          :context="effectiveGraphContext"
          :loading="chatStore.isLoading || actionDrawerLoading"
          :selected-node-id="chatStore.selectedArtifactId"
          :panel-title="graphPanelTitle"
          :panel-subtitle="graphPanelSubtitle"
          :show-header="false"
          :show-detail-card="false"
          :show-annotations="false"
          empty-text="暂无图谱数据"
          @select-node="
            (node) => {
              selectedGraphNode = node
              chatStore.setSelectedArtifactId(node?.id || null)
            }
          "
        />
      </section>

      <section
        class="mobile-recommend-pane"
        :class="{ 'mobile-hidden': chatStore.mobilePanel !== 'recommendation' }"
      >
        <div class="mobile-recommend-scroll">
          <div v-if="!activeRecommendation?.courses?.length" class="mobile-empty glass-card">
            <h3>暂无推荐结果</h3>
          </div>

          <template v-else>
            <article
              v-for="course in orderedRecommendations"
              :key="course.id || course.name"
              class="recommend-card glass-card"
            >
              <div class="recommend-top">
                <div>
                  <span class="recommend-order">路径 {{ course.displayOrder ?? '·' }}</span>
                  <h4>{{ course.name }}</h4>
                </div>
                <el-tag v-if="course.isEnrolled" type="success" round>已选</el-tag>
              </div>

              <p class="recommend-meta">{{ describeRecommendationSummary(course) }}</p>
              <p v-if="course.teacherNames.length" class="recommend-teachers">
                教师：{{ course.teacherNames.slice(0, 3).join(' / ') }}{{ course.teacherNames.length > 3 ? ' 等' : '' }}
              </p>
              <p class="recommend-reason">{{ describeGraphReason(course) }}</p>

              <div class="chip-row">
                <span v-for="concept in course.concepts" :key="concept" class="soft-chip">{{ concept }}</span>
              </div>

              <div class="recommend-actions">
                <el-button round @click="focusRecommendationCourse(course)">图谱聚焦</el-button>
                <el-button
                  type="primary"
                  round
                  :disabled="course.isEnrolled"
                  @click="openActionDrawer(course)"
                >
                  选课评估
                </el-button>
              </div>
            </article>
          </template>
        </div>
      </section>
    </div>

    <section class="input-dock">
      <div class="input-shell glass-card">
        <div class="input-meta">
          <span class="input-mode">{{ workspaceMode }}</span>
        </div>

        <el-input
          ref="inputRef"
          v-model="inputMessage"
          type="textarea"
          :rows="2"
          :autosize="{ minRows: 1, maxRows: 5 }"
          placeholder="输入问题"
          resize="none"
          :disabled="chatStore.isLoading"
          @keydown.enter.exact.prevent="sendMessage"
        />

        <div class="input-actions">
          <div class="input-secondary-actions">
            <el-button size="small" round :icon="Delete" :loading="chatStore.isLoading" @click="clearChat">
              清空对话
            </el-button>
            <el-button size="small" round :icon="Setting" @click="chatStore.setFollowStream(!chatStore.followStream)">
              {{ chatStore.followStream ? '锁定图谱' : '恢复跟随' }}
            </el-button>
          </div>

          <el-button type="primary" round :icon="Promotion" :loading="chatStore.isLoading" @click="sendMessage">
            发送
          </el-button>
        </div>
      </div>
    </section>

    <el-drawer
      v-model="actionDrawerVisible"
      title="选课决策抽屉"
      size="440px"
      class="selection-drawer"
      @closed="closeActionDrawer"
    >
      <div v-if="previewCourse" class="drawer-content">
        <div class="drawer-hero">
          <span class="section-kicker">Action Preview</span>
          <h3>{{ previewCourse.name }}</h3>
          <p>{{ previewCourse.reason || '课程信息' }}</p>
        </div>

        <el-skeleton v-if="actionDrawerLoading" :rows="8" animated />

        <template v-else>
          <div class="drawer-section">
            <span class="drawer-label">授课教师</span>
            <div class="chip-row">
              <span v-for="teacher in previewCourse.teacherNames" :key="teacher" class="soft-chip">
                {{ teacher }}
              </span>
            </div>
          </div>

          <div class="drawer-section">
            <span class="drawer-label">核心知识点</span>
            <div class="chip-row">
              <span v-for="concept in previewCourse.concepts" :key="concept" class="soft-chip">
                {{ concept }}
              </span>
            </div>
          </div>

          <div class="drawer-section">
            <span class="drawer-label">先修链</span>
            <div class="chip-row">
              <span
                v-for="item in previewCourse.prerequisites"
                :key="item"
                class="soft-chip subdued"
              >
                {{ item }}
              </span>
              <span v-if="!previewCourse.prerequisites.length" class="drawer-empty">当前未识别到显式先修概念</span>
            </div>
          </div>

          <div class="drawer-stats">
            <div class="drawer-stat">
              <span>目标时间</span>
              <strong>{{ formatTargetTime(effectiveActionPreview?.targetTime) }}</strong>
            </div>
            <div class="drawer-stat">
              <span>冲突情况</span>
              <strong>
                {{
                  effectiveActionPreview?.conflictCourses?.length
                    ? effectiveActionPreview.conflictCourses.map((item) => item.name).join('、')
                    : '当前未发现显式冲突'
                }}
              </strong>
            </div>
          </div>

          <div class="drawer-actions">
            <el-button round @click="focusRecommendationCourse(previewCourse)">只看图谱</el-button>
            <el-button
              type="primary"
              round
              :disabled="effectiveActionPreview?.canProceed === false"
              :loading="chatStore.isLoading"
              @click="confirmEnrollment"
            >
              确认选这门课
            </el-button>
          </div>

          <p v-if="effectiveActionPreview?.canProceed === false" class="drawer-warning">
            检测到时间冲突，建议先在图谱中查看冲突子图再决定是否继续调整课表。
          </p>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.chat-workbench {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 20px;
  gap: 16px;
  background:
    radial-gradient(circle at 0 0, rgba(16, 185, 129, 0.16), transparent 26%),
    radial-gradient(circle at 100% 0, rgba(14, 165, 233, 0.14), transparent 26%),
    linear-gradient(180deg, #edf4f2 0%, #e6efec 46%, #edf2f1 100%);
}

.glass-card {
  border: 1px solid rgba(148, 163, 184, 0.18);
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(18px);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.45),
    0 20px 40px rgba(15, 23, 42, 0.08);
}

.workspace-topbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 18px;
}
.section-kicker,
.welcome-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 999px;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #0f766e;
  background: rgba(16, 185, 129, 0.12);
}

.workspace-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.mode-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.84);
  color: #f8fafc;
  box-shadow: 0 14px 30px rgba(15, 23, 42, 0.22);
  font-size: 13px;
  font-weight: 600;
}

.mode-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #34d399;
  box-shadow: 0 0 18px rgba(52, 211, 153, 0.9);
}

.mobile-tabs {
  display: none;
}

.workspace-grid {
  min-height: 0;
  flex: 1;
  display: grid;
  grid-template-columns: minmax(0, 1.06fr) minmax(360px, 0.94fr);
  gap: 16px;
}

.timeline-pane,
.graph-pane,
.mobile-recommend-pane {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.timeline-head,
.graph-stage-head {
  border-radius: 24px;
  padding: 18px 20px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;

  h2 {
    margin: 10px 0 6px;
    color: #0f172a;
    font-size: 24px;
  }

  p {
    color: #475569;
    line-height: 1.75;
    font-size: 13px;
  }
}

.timeline-badges,
.graph-mini-stats {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.metric-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.06);
  color: #334155;
  font-size: 12px;
  font-weight: 600;
}

.messages-container {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  padding-right: 4px;
}

.messages-inner {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.welcome-shell {
  border-radius: 28px;
  padding: 28px;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 22px;
  background:
    radial-gradient(circle at 0 0, rgba(16, 185, 129, 0.16), transparent 28%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.82), rgba(255, 255, 255, 0.72));
}

.welcome-copy {
  h2 {
    margin: 12px 0 10px;
    font-size: 30px;
    line-height: 1.1;
    color: #0f172a;
  }

  p {
    color: #475569;
    font-size: 15px;
    line-height: 1.9;
    max-width: 640px;
  }
}

.sample-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.sample-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px;
  border: 1px solid rgba(148, 163, 184, 0.16);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.7);
  text-align: left;
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;

  &:hover {
    transform: translateY(-2px);
    border-color: rgba(16, 185, 129, 0.45);
    box-shadow: 0 16px 30px rgba(15, 23, 42, 0.08);
  }

  strong {
    color: #0f172a;
    font-size: 15px;
    line-height: 1.55;
  }
}

.sample-label,
.recommend-order,
.action-status {
  display: inline-flex;
  align-items: center;
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.08);
  color: #475569;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.message-row {
  animation: messageIn 0.24s ease;

  &.user .message-wrap {
    margin-left: auto;
    flex-direction: row-reverse;
  }

  &.user .msg-body {
    align-items: flex-end;
  }

  &.user .msg-meta {
    flex-direction: row-reverse;
  }

  &.user .msg-content {
    color: #f8fafc;
    background:
      radial-gradient(circle at 100% 0, rgba(52, 211, 153, 0.18), transparent 30%),
      linear-gradient(135deg, #0f172a, #16233b 70%, #1b3354);
    border-radius: 18px 18px 8px 18px;
    box-shadow: 0 18px 36px rgba(15, 23, 42, 0.18);
  }

  &.assistant .msg-content {
    background: rgba(255, 255, 255, 0.82);
    border: 1px solid rgba(148, 163, 184, 0.16);
    border-radius: 18px 18px 18px 8px;
    box-shadow: 0 14px 28px rgba(15, 23, 42, 0.05);
  }
}

.message-wrap {
  max-width: 92%;
  display: flex;
  gap: 12px;
}

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.user-avatar {
  color: #fff;
  background: linear-gradient(135deg, #0f172a, #1e293b);
}

.ai-avatar {
  color: #0f172a;
  background: rgba(255, 255, 255, 0.84);
  border: 1px solid rgba(148, 163, 184, 0.18);
}

.msg-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.msg-meta {
  display: flex;
  gap: 8px;
  align-items: center;
}

.msg-name {
  color: #334155;
  font-size: 12px;
  font-weight: 700;
}

.msg-time {
  color: #94a3b8;
  font-size: 12px;
}

.msg-content {
  padding: 14px 16px;
  line-height: 1.72;
  font-size: 14px;
  color: #0f172a;
  word-break: break-word;

  &.streaming .streaming-dot {
    display: inline-block;
    width: 7px;
    height: 7px;
    margin-left: 6px;
    border-radius: 999px;
    background: #34d399;
    box-shadow: 0 0 16px rgba(52, 211, 153, 0.75);
    animation: blink 1s infinite;
    vertical-align: middle;
  }
}

.streaming-status {
  margin-top: 10px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(16, 185, 129, 0.12);
  color: #047857;
  font-size: 12px;
  font-weight: 600;
}

.evidence-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.evidence-chip {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px 12px;
  border: 1px solid rgba(16, 185, 129, 0.16);
  border-radius: 16px;
  background: rgba(240, 253, 250, 0.9);
  color: #0f766e;
  cursor: pointer;
  text-align: left;
  transition: transform 0.18s ease, border-color 0.18s ease;

  &:hover {
    transform: translateY(-1px);
    border-color: rgba(16, 185, 129, 0.4);
  }

  span {
    font-size: 12px;
    font-weight: 700;
  }

  small {
    font-size: 11px;
    line-height: 1.5;
    color: #0f766e;
  }
}

.artifact-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 4px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  h3 {
    margin-top: 8px;
    font-size: 24px;
    color: #0f172a;
  }
}

.recommend-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.recommend-card,
.action-result-card,
.inspector-card,
.mobile-empty {
  border-radius: 24px;
  padding: 18px;
}

.recommend-top,
.action-result-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.recommend-top h4,
.action-result-head h4,
.inspector-card h3,
.mobile-empty h3 {
  margin-top: 10px;
  font-size: 22px;
  line-height: 1.18;
  color: #0f172a;
}

.recommend-meta {
  margin-top: 10px;
  font-size: 12px;
  color: #64748b;
}

.recommend-teachers {
  margin-top: 8px;
  font-size: 13px;
  color: #334155;
  font-weight: 600;
  line-height: 1.6;
}

.recommend-reason,
.action-result-card p,
.inspector-card p,
.mobile-empty p {
  margin-top: 12px;
  color: #475569;
  line-height: 1.75;
  font-size: 14px;
}

.chip-row {
  margin-top: 14px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  &.subdued {
    margin-top: 10px;
  }
}

.soft-chip {
  display: inline-flex;
  align-items: center;
  padding: 7px 11px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.06);
  color: #334155;
  font-size: 12px;
  font-weight: 600;

  &.subdued {
    background: rgba(16, 185, 129, 0.08);
    color: #0f766e;
  }
}

.recommend-actions,
.drawer-actions {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.graph-control-group {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.graph-toggle {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #334155;
  font-size: 13px;
  font-weight: 600;
}

.graph-inspector-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.action-result-card {
  &.success {
    border-color: rgba(16, 185, 129, 0.2);
    background: linear-gradient(180deg, rgba(240, 253, 250, 0.96), rgba(255, 255, 255, 0.86));
  }

  &.warning,
  &.danger {
    border-color: rgba(245, 158, 11, 0.24);
    background: linear-gradient(180deg, rgba(255, 251, 235, 0.96), rgba(255, 255, 255, 0.86));
  }
}

.action-detail-grid,
.drawer-stats {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.action-detail-grid div,
.drawer-stat {
  padding: 14px;
  border-radius: 18px;
  background: rgba(15, 23, 42, 0.05);

  span {
    display: block;
    color: #64748b;
    font-size: 12px;
  }

  strong {
    display: block;
    margin-top: 6px;
    color: #0f172a;
    font-size: 14px;
    line-height: 1.6;
  }
}

.mobile-recommend-pane {
  display: none;
}

.mobile-recommend-scroll {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.input-dock {
  flex-shrink: 0;
}

.input-shell {
  border-radius: 26px;
  padding: 16px 18px;
}

.input-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.input-mode {
  display: inline-flex;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.08);
  color: #334155;
  font-size: 12px;
  font-weight: 700;
}

.input-hint {
  color: #64748b;
  font-size: 12px;
}

:deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  background: transparent;
  padding: 0;
  font-size: 14px;
  line-height: 1.7;
  min-height: 56px !important;
}

.input-actions {
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.input-secondary-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.drawer-content {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.drawer-hero {
  padding: 18px;
  border-radius: 20px;
  background:
    radial-gradient(circle at 100% 0, rgba(16, 185, 129, 0.16), transparent 30%),
    linear-gradient(180deg, rgba(240, 253, 250, 0.96), rgba(255, 255, 255, 0.92));
  border: 1px solid rgba(16, 185, 129, 0.16);

  h3 {
    margin-top: 10px;
    color: #0f172a;
    font-size: 24px;
  }

  p {
    margin-top: 10px;
    color: #475569;
    line-height: 1.75;
  }
}

.drawer-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.drawer-label {
  color: #334155;
  font-size: 13px;
  font-weight: 700;
}

.drawer-empty,
.drawer-warning {
  color: #b45309;
  font-size: 13px;
  line-height: 1.7;
}

:deep(.selection-drawer .el-drawer__body) {
  padding-top: 0;
}

:deep(.markdown-body) {
  color: #0f172a;
  font-size: 14px;
  line-height: 1.78;

  p,
  ul,
  ol,
  table,
  blockquote {
    margin: 8px 0;
  }

  ul,
  ol {
    padding-left: 20px;
  }

  li {
    margin: 4px 0;
  }

  li > p:only-child {
    display: inline;
    margin: 0;
  }

  code {
    background: rgba(15, 23, 42, 0.06);
    border: 1px solid rgba(15, 23, 42, 0.1);
    border-radius: 6px;
    padding: 2px 6px;
    font-size: 0.88em;
  }

  pre {
    background: #0f172a;
    border-radius: 12px;
    padding: 14px;
    overflow-x: auto;

    code {
      border: none;
      background: transparent;
      color: #e2e8f0;
      padding: 0;
    }
  }

  blockquote {
    padding: 8px 12px;
    border-left: 3px solid #10b981;
    border-radius: 0 10px 10px 0;
    background: rgba(16, 185, 129, 0.08);
  }

  table {
    width: 100%;
    border-collapse: collapse;

    th,
    td {
      border: 1px solid rgba(148, 163, 184, 0.2);
      padding: 8px;
      text-align: left;
    }

    th {
      background: rgba(15, 23, 42, 0.04);
      font-weight: 700;
    }
  }
}

@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }

  50% {
    opacity: 0.25;
  }
}

@keyframes messageIn {
  from {
    opacity: 0;
    transform: translateY(8px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 1180px) {
  .workspace-grid {
    grid-template-columns: minmax(0, 1fr) minmax(320px, 0.9fr);
  }

  .recommend-grid,
  .graph-inspector-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 900px) {
  .chat-workbench {
    padding: 16px;
  }

  .workspace-topbar,
  .input-meta,
  .input-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .workspace-grid {
    display: flex;
    flex-direction: column;
  }

  .mobile-tabs {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
  }

  .mobile-tab {
    padding: 11px 0;
    border: 1px solid rgba(148, 163, 184, 0.16);
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.76);
    color: #475569;
    font-size: 13px;
    font-weight: 700;

    &.active {
      background: #0f172a;
      color: #f8fafc;
      box-shadow: 0 16px 28px rgba(15, 23, 42, 0.16);
    }
  }

  .timeline-pane,
  .graph-pane,
  .mobile-recommend-pane {
    min-height: 0;
  }

  .timeline-pane.mobile-hidden,
  .graph-pane.mobile-hidden,
  .mobile-recommend-pane.mobile-hidden {
    display: none;
  }

  .desktop-only {
    display: none;
  }

  .mobile-recommend-pane {
    display: flex;
  }

  .messages-container,
  .mobile-recommend-scroll {
    max-height: calc(100vh - 420px);
  }

  .sample-grid {
    grid-template-columns: 1fr;
  }

  .message-wrap {
    max-width: 100%;
  }
}

@media (max-width: 640px) {
  .welcome-copy h2,
  .mobile-empty h3 {
    font-size: 24px;
  }

  .recommend-grid,
  .action-detail-grid,
  .drawer-stats {
    grid-template-columns: 1fr;
  }

  .input-secondary-actions,
  .recommend-actions,
  .drawer-actions {
    width: 100%;
  }

  .recommend-actions :deep(.el-button),
  .drawer-actions :deep(.el-button),
  .input-secondary-actions :deep(.el-button) {
    flex: 1;
  }
}
</style>
