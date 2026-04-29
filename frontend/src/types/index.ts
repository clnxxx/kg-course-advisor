export interface User {
  id: number
  username: string
  email?: string
  studentId?: string
  realName?: string
  major?: string
  grade?: number
  role: 'STUDENT' | 'TEACHER' | 'ADMIN'
  createdAt?: string
  updatedAt?: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  password: string
  email?: string
  studentId?: string
  realName?: string
  major?: string
  grade?: number
}

export interface UpdateProfileRequest {
  email?: string | null
}

export interface UserScheduleCourse {
  courseId: string
  courseName: string
  courseContent?: string | null
  teacherNames: string[]
  timeId?: string | null
  weekdayIndex?: number | null
  weekdayCode?: string | null
  halfDay?: 'morning' | 'afternoon' | string | null
  periodIndex?: number | null
  startTime?: string | null
  endTime?: string | null
}

export interface UserScheduleResponse {
  userId: number
  displayName: string
  courses: UserScheduleCourse[]
  summary: {
    totalCourses: number
    scheduledCourses: number
    unscheduledCourses: number
  }
}

export interface AuthResponse {
  success: boolean
  message: string
  token?: string
  user?: User
}

export interface AuthApiResponse {
  success: boolean
  message: string
  token?: string
  user?: {
    id: number
    username: string
    email?: string
    studentId?: string
    realName?: string
    major?: string
    grade?: number
    role: 'STUDENT' | 'TEACHER' | 'ADMIN'
  }
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: Date
  isStreaming?: boolean
  streamingStatus?: string
}

export interface ChatRequest {
  message: string
  sessionId?: string
}

export interface ApiResponse<T = unknown> {
  success: boolean
  message: string
  data?: T
}

export interface GraphMetadata {
  entityType?: string
  entityId?: string
  elementId?: string
  content?: string
  weekdayIndex?: number | null
  halfDay?: string | null
  periodIndex?: number | null
  startTime?: string | null
  endTime?: string | null
  [key: string]: unknown
}

export interface GraphNode {
  id: string
  label: string
  group: string
  title: string
  highlighted?: boolean
  metadata?: GraphMetadata
}

export interface GraphEdge {
  from: string
  to: string
  label: string
  highlighted?: boolean
  metadata?: Record<string, unknown>
}

export interface GraphAnnotation {
  id: string
  title: string
  content: string
  tone?: 'info' | 'warning' | 'success' | 'danger' | string
  relatedNodeIds?: string[]
}

export interface GraphContext {
  nodes: GraphNode[]
  edges: GraphEdge[]
  focusNodeIds: string[]
  annotations: GraphAnnotation[]
  stats: {
    nodeCount: number
    edgeCount: number
  }
}

export interface GraphContextRequest {
  scenario: string
  courseIds?: string[]
  courseNames?: string[]
  conceptNames?: string[]
  teacherNames?: string[]
  includeSchedule?: boolean
  includePrerequisites?: boolean
  includeConflicts?: boolean
  limit?: number
}

export interface RecommendationCourse {
  id: string
  name: string
  content?: string
  reason?: string
  teacherNames: string[]
  prerequisites: string[]
  concepts: string[]
  isEnrolled?: boolean
  pathOrder: number | null
  graphFocus: string[]
}

export interface RecommendationArtifact {
  status: string
  keywords?: string[]
  recommendationReason?: string
  matchedCourses?: RecommendationCourse[]
  courses: RecommendationCourse[]
  learningPath: string[]
  suggestedAction?: string
}

export interface ConflictCourseRef {
  id?: string
  name: string
}

export interface TargetTimeRef {
  weekdayIndex?: number | null
  halfDay?: string | null
  periodIndex?: number | null
  startTime?: string | null
  endTime?: string | null
}

export interface ActionPreviewArtifact {
  status: string
  operationType: string
  affectedCourse?: {
    id?: string
    name?: string
    content?: string
  }
  conflictCourses?: ConflictCourseRef[]
  targetTime?: TargetTimeRef | null
  canProceed?: boolean
}

export interface ActionResultArtifact extends ActionPreviewArtifact {
  message?: string
  graphFocus?: string[]
  currentScheduleRefs?: Array<{
    courseId?: string | null
    courseName?: string | null
    weekdayIndex?: number | null
    halfDay?: string | null
    periodIndex?: number | null
  }>
}

export interface StatusStreamPayload {
  status: string
}

export interface MessageDeltaPayload {
  delta: string
}

export interface GraphHighlightPayload {
  focusNodeIds: string[]
}

export interface DonePayload {
  completed: boolean
}

export type ChatStreamEvent =
  | { type: 'status'; payload: StatusStreamPayload }
  | { type: 'message_delta'; payload: MessageDeltaPayload }
  | { type: 'graph_snapshot'; payload: GraphContext }
  | { type: 'graph_highlight'; payload: GraphHighlightPayload }
  | { type: 'recommendation'; payload: RecommendationArtifact }
  | { type: 'action_preview'; payload: ActionPreviewArtifact }
  | { type: 'action_result'; payload: ActionResultArtifact }
  | { type: 'done'; payload: DonePayload }
  | { type: string; payload: unknown }
