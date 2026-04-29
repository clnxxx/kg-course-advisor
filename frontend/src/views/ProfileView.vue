<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { authApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import type { UserScheduleCourse, UserScheduleResponse } from '@/types'
import { Calendar, Clock, Reading, RefreshRight, Warning } from '@element-plus/icons-vue'

const authStore = useAuthStore()
const loading = ref(true)
const errorText = ref('')
const schedule = ref<UserScheduleResponse | null>(null)

const weekdayLabels: Record<number, string> = {
  1: '周一',
  2: '周二',
  3: '周三',
  4: '周四',
  5: '周五',
}

const slotDefaults: Record<string, string> = {
  'morning-1': '08:00 - 09:40',
  'morning-2': '10:00 - 11:40',
  'afternoon-1': '14:00 - 15:40',
  'afternoon-2': '16:00 - 17:40',
}

const slotRows = [
  { halfDay: 'morning', periodIndex: 1, label: '上午第 1 节' },
  { halfDay: 'morning', periodIndex: 2, label: '上午第 2 节' },
  { halfDay: 'afternoon', periodIndex: 1, label: '下午第 1 节' },
  { halfDay: 'afternoon', periodIndex: 2, label: '下午第 2 节' },
]

const weekdayColumns = computed(() =>
  Object.entries(weekdayLabels).map(([value, label]) => ({ value: Number(value), label })),
)

const courses = computed(() => schedule.value?.courses ?? [])

const scheduledCourses = computed(() =>
  courses.value.filter((course) => course.weekdayIndex && course.halfDay && course.periodIndex),
)

const unscheduledCourses = computed(() =>
  courses.value.filter((course) => !course.weekdayIndex || !course.halfDay || !course.periodIndex),
)

const summary = computed(() => schedule.value?.summary ?? {
  totalCourses: 0,
  scheduledCourses: 0,
  unscheduledCourses: 0,
})

const displayName = computed(
  () => schedule.value?.displayName || authStore.currentUser?.realName || authStore.currentUser?.username || '我的课程',
)

const activeDays = computed(() => new Set(scheduledCourses.value.map((course) => course.weekdayIndex)).size)

const timetableMatrix = computed(() => {
  const matrix = new Map<string, UserScheduleCourse[]>()

  for (const course of scheduledCourses.value) {
    const key = buildMatrixKey(course.weekdayIndex, course.halfDay, course.periodIndex)
    if (!matrix.has(key)) {
      matrix.set(key, [])
    }
    matrix.get(key)!.push(course)
  }

  return matrix
})

const fetchSchedule = async () => {
  loading.value = true
  errorText.value = ''

  try {
    const response = await authApi.getMySchedule()
    schedule.value = response.data
  } catch (error) {
    console.error('加载用户课表失败', error)
    errorText.value = error instanceof Error ? error.message : '课表加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

const buildMatrixKey = (weekdayIndex?: number | null, halfDay?: string | null, periodIndex?: number | null) =>
  `${weekdayIndex || 0}-${halfDay || 'unknown'}-${periodIndex || 0}`

const getCoursesForCell = (weekdayIndex: number, halfDay: string, periodIndex: number) =>
  timetableMatrix.value.get(buildMatrixKey(weekdayIndex, halfDay, periodIndex)) ?? []

const getSlotTime = (halfDay: string, periodIndex: number) => {
  const matched = scheduledCourses.value.find(
    (course) => course.halfDay === halfDay && course.periodIndex === periodIndex && course.startTime && course.endTime,
  )

  if (matched?.startTime && matched?.endTime) {
    return `${matched.startTime} - ${matched.endTime}`
  }

  return slotDefaults[`${halfDay}-${periodIndex}`] || '时间待定'
}

const getTeacherText = (course: UserScheduleCourse) => {
  const teachers = (course.teacherNames || []).filter(Boolean)
  return teachers.length ? teachers.join(' / ') : '教师待补充'
}

const getTimeText = (course: UserScheduleCourse) => {
  if (!course.weekdayIndex || !course.halfDay || !course.periodIndex) {
    return '时间待定'
  }

  const weekday = weekdayLabels[course.weekdayIndex] || '未排课'
  const halfDayLabel = course.halfDay === 'morning' ? '上午' : '下午'
  const timeRange =
    course.startTime && course.endTime
      ? `${course.startTime} - ${course.endTime}`
      : getSlotTime(course.halfDay, course.periodIndex)

  return `${weekday} ${halfDayLabel} 第 ${course.periodIndex} 节 · ${timeRange}`
}

onMounted(() => {
  void fetchSchedule()
})
</script>

<template>
  <div class="schedule-page" v-loading="loading" element-loading-text="正在加载我的课程安排...">
    <section class="hero-card">
      <div class="hero-copy">
        <span class="hero-kicker">我的课程</span>
        <h1>{{ displayName }}</h1>
        <p>查看当前已选课程、上课时间和一周安排。</p>
        <div class="hero-badges">
          <span class="hero-badge">已选 {{ summary.totalCourses }} 门</span>
          <span class="hero-badge">已排课 {{ summary.scheduledCourses }} 门</span>
          <span class="hero-badge">活跃日 {{ activeDays }} 天</span>
        </div>
      </div>

      <div class="hero-actions">
        <el-button class="refresh-btn" type="primary" plain :icon="RefreshRight" @click="fetchSchedule">
          刷新课表
        </el-button>
      </div>
    </section>

    <el-alert v-if="errorText" :title="errorText" type="error" :closable="false" class="error-alert" />

    <template v-if="!loading">
      <div v-if="summary.totalCourses === 0" class="empty-shell">
        <el-empty description="当前还没有已选课程" />
      </div>

      <template v-else>
        <section class="stats-grid">
          <article class="stat-card">
            <div class="stat-icon">
              <el-icon><Reading /></el-icon>
            </div>
            <div>
              <span class="stat-label">已选课程</span>
              <strong class="stat-value">{{ summary.totalCourses }}</strong>
            </div>
          </article>

          <article class="stat-card">
            <div class="stat-icon accent-green">
              <el-icon><Calendar /></el-icon>
            </div>
            <div>
              <span class="stat-label">已排时间</span>
              <strong class="stat-value">{{ summary.scheduledCourses }}</strong>
            </div>
          </article>

          <article class="stat-card">
            <div class="stat-icon accent-amber">
              <el-icon><Warning /></el-icon>
            </div>
            <div>
              <span class="stat-label">时间待定</span>
              <strong class="stat-value">{{ summary.unscheduledCourses }}</strong>
            </div>
          </article>
        </section>

        <section class="schedule-grid">
          <div class="panel-card timetable-panel">
            <div class="panel-head">
              <div>
                <h2>每周时间安排</h2>
                <p>按时间槽查看本周安排。</p>
              </div>
            </div>

            <div class="timetable">
              <div class="timetable-row timetable-header">
                <div class="slot-cell side-cell">时间槽</div>
                <div v-for="day in weekdayColumns" :key="day.value" class="slot-cell day-cell">
                  {{ day.label }}
                </div>
              </div>

              <div v-for="row in slotRows" :key="`${row.halfDay}-${row.periodIndex}`" class="timetable-row">
                <div class="slot-cell side-cell meta-cell">
                  <strong>{{ row.label }}</strong>
                  <span>{{ getSlotTime(row.halfDay, row.periodIndex) }}</span>
                </div>

                <div
                  v-for="day in weekdayColumns"
                  :key="`${day.value}-${row.halfDay}-${row.periodIndex}`"
                  class="slot-cell course-cell"
                >
                  <template v-if="getCoursesForCell(day.value, row.halfDay, row.periodIndex).length">
                    <div
                      v-for="course in getCoursesForCell(day.value, row.halfDay, row.periodIndex)"
                      :key="course.courseId"
                      class="course-pill"
                    >
                      <strong>{{ course.courseName }}</strong>
                      <span>{{ getTeacherText(course) }}</span>
                    </div>
                  </template>
                  <span v-else class="empty-slot">空闲</span>
                </div>
              </div>
            </div>
          </div>

          <div class="side-column">
            <div class="panel-card">
              <div class="panel-head">
                <div>
                  <h2>已选课程列表</h2>
                  <p>按当前排课顺序展示。</p>
                </div>
              </div>

              <div class="course-list">
                <article v-for="course in courses" :key="course.courseId" class="course-card">
                  <div class="course-card-top">
                    <h3>{{ course.courseName }}</h3>
                    <el-tag v-if="course.timeId" size="small" effect="plain">已排课</el-tag>
                    <el-tag v-else size="small" type="warning" effect="plain">待排课</el-tag>
                  </div>

                  <p class="course-time">
                    <el-icon><Clock /></el-icon>
                    <span>{{ getTimeText(course) }}</span>
                  </p>

                  <p class="course-teacher">
                    <el-icon><Reading /></el-icon>
                    <span>{{ getTeacherText(course) }}</span>
                  </p>

                  <p class="course-content">
                    {{ course.courseContent || '暂无课程简介' }}
                  </p>
                </article>
              </div>
            </div>

            <div v-if="unscheduledCourses.length" class="panel-card soft-panel">
              <div class="panel-head">
                <div>
                  <h2>待补时间课程</h2>
                  <p>这些课程暂时还没有时间安排。</p>
                </div>
              </div>

              <div class="pending-list">
                <div v-for="course in unscheduledCourses" :key="`${course.courseId}-pending`" class="pending-item">
                  <strong>{{ course.courseName }}</strong>
                  <span>{{ getTeacherText(course) }}</span>
                </div>
              </div>
            </div>
          </div>
        </section>
      </template>
    </template>
  </div>
</template>

<style scoped lang="scss">
.schedule-page {
  height: 100%;
  overflow-y: auto;
  padding: 24px;
  background:
    radial-gradient(circle at top right, rgba(37, 99, 235, 0.09), transparent 28%),
    radial-gradient(circle at left bottom, rgba(16, 163, 127, 0.1), transparent 26%),
    linear-gradient(180deg, #f5f8f7 0%, #eef3f2 100%);
}

.hero-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 28px;
  border-radius: 24px;
  color: #fff;
  background:
    linear-gradient(140deg, rgba(15, 23, 42, 0.96), rgba(15, 118, 110, 0.9)),
    linear-gradient(180deg, #0f172a 0%, #134e4a 100%);
  box-shadow: 0 26px 60px rgba(15, 23, 42, 0.2);
}

.hero-copy {
  max-width: 720px;

  h1 {
    margin: 10px 0 12px;
    font-size: 34px;
    line-height: 1.1;
  }

  p {
    margin: 0;
    line-height: 1.8;
    color: rgba(255, 255, 255, 0.86);
  }
}

.hero-kicker {
  display: inline-flex;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  font-size: 12px;
  letter-spacing: 0.08em;
}

.hero-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 18px;
}

.hero-badge {
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.12);
  font-size: 12px;
}

.hero-actions {
  display: flex;
  align-items: flex-start;
}

.refresh-btn {
  border-radius: 999px;
}

.error-alert {
  margin-top: 18px;
}

.empty-shell {
  margin-top: 20px;
  padding: 40px 20px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-top: 22px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(15, 23, 42, 0.08);
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.07);
}

.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(37, 99, 235, 0.12);
  color: #2563eb;
  font-size: 18px;

  &.accent-green {
    background: rgba(16, 163, 127, 0.12);
    color: #0f766e;
  }

  &.accent-amber {
    background: rgba(245, 158, 11, 0.14);
    color: #b45309;
  }
}

.stat-label {
  display: block;
  color: #64748b;
  font-size: 13px;
}

.stat-value {
  display: block;
  margin-top: 6px;
  color: #0f172a;
  font-size: 28px;
  line-height: 1;
}

.schedule-grid {
  margin-top: 22px;
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(320px, 0.9fr);
  gap: 18px;
}

.side-column {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.panel-card {
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(15, 23, 42, 0.08);
  box-shadow: 0 18px 42px rgba(15, 23, 42, 0.08);
  overflow: hidden;
}

.soft-panel {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(255, 251, 235, 0.92));
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 22px 0;

  h2 {
    margin: 0;
    font-size: 22px;
    color: #0f172a;
  }

  p {
    margin: 6px 0 0;
    color: #64748b;
    line-height: 1.7;
    font-size: 14px;
  }
}

.timetable-panel {
  padding-bottom: 20px;
}

.timetable {
  padding: 18px 20px 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.timetable-row {
  display: grid;
  grid-template-columns: 140px repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.slot-cell {
  min-height: 110px;
  border-radius: 18px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: #f8fafc;
  padding: 14px;
}

.timetable-header .slot-cell {
  min-height: auto;
  padding: 12px 14px;
  background: rgba(15, 23, 42, 0.04);
}

.side-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: #334155;
  font-weight: 600;
}

.meta-cell {
  flex-direction: column;
  gap: 6px;

  strong {
    color: #0f172a;
    font-size: 14px;
  }

  span {
    color: #64748b;
    font-size: 12px;
    line-height: 1.6;
  }
}

.day-cell {
  font-weight: 700;
  color: #0f172a;
}

.course-cell {
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(248, 250, 252, 0.9));
}

.course-pill {
  padding: 12px;
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(16, 163, 127, 0.12), rgba(37, 99, 235, 0.1));
  border: 1px solid rgba(16, 163, 127, 0.16);

  strong {
    display: block;
    color: #0f172a;
    line-height: 1.5;
  }

  span {
    display: block;
    margin-top: 6px;
    color: #475569;
    font-size: 12px;
    line-height: 1.5;
  }
}

.empty-slot {
  margin: auto;
  color: #94a3b8;
  font-size: 13px;
}

.course-list {
  padding: 18px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.course-card {
  padding: 16px;
  border-radius: 18px;
  background: #f8fafc;
  border: 1px solid rgba(148, 163, 184, 0.16);
}

.course-card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;

  h3 {
    margin: 0;
    color: #0f172a;
    font-size: 16px;
    line-height: 1.5;
  }
}

.course-time,
.course-teacher {
  margin: 12px 0 0;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: #334155;
  font-size: 13px;
  line-height: 1.7;

  .el-icon {
    margin-top: 2px;
    color: #0f766e;
  }
}

.course-content {
  margin: 12px 0 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.8;
}

.pending-list {
  padding: 18px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.pending-item {
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(245, 158, 11, 0.18);

  strong {
    display: block;
    color: #0f172a;
  }

  span {
    display: block;
    margin-top: 6px;
    color: #64748b;
    font-size: 13px;
  }
}

@media (max-width: 1180px) {
  .schedule-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 920px) {
  .stats-grid {
    grid-template-columns: 1fr;
  }

  .timetable {
    overflow-x: auto;
  }

  .timetable-row {
    min-width: 880px;
  }
}

@media (max-width: 760px) {
  .schedule-page {
    padding: 16px;
  }

  .hero-card {
    flex-direction: column;
    padding: 22px;
  }

  .hero-copy h1 {
    font-size: 28px;
  }

  .panel-head {
    padding: 18px 18px 0;
  }

  .timetable,
  .course-list,
  .pending-list {
    padding-left: 16px;
    padding-right: 16px;
  }
}
</style>
