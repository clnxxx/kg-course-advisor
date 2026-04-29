<script setup lang="ts">
import { computed, ref, onMounted } from 'vue';
import { adminApi } from '@/api';

interface Stats {
  totalCourses: number;
  totalConcepts: number;
  totalTeachers: number;
  totalEnrollments: number;
}

interface Course {
  id: string;
  name: string;
  content: string;
  teacher: string;
  enrollCount: number;
}

const stats = ref<Stats>({
  totalCourses: 0,
  totalConcepts: 0,
  totalTeachers: 0,
  totalEnrollments: 0,
});

const courses = ref<Course[]>([]);
const loading = ref(true);
const errorText = ref('');

const keyword = ref('');
const teacherFilter = ref('');

const statCards = [
  { key: 'totalCourses', label: '课程总数', icon: '📚', accent: '#2563eb' },
  { key: 'totalConcepts', label: '知识点总数', icon: '🧩', accent: '#10a37f' },
  { key: 'totalTeachers', label: '教师总数', icon: '👩‍🏫', accent: '#0f766e' },
  { key: 'totalEnrollments', label: '选课记录', icon: '📝', accent: '#f59e0b' },
] as const;

const teacherOptions = computed(() => {
  const set = new Set(courses.value.map((c) => c.teacher).filter(Boolean));
  return Array.from(set).sort((a, b) => a.localeCompare(b, 'zh-CN'));
});

const filteredCourses = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  return courses.value.filter((course) => {
    const matchesKeyword =
      !kw ||
      course.name.toLowerCase().includes(kw) ||
      String(course.id).toLowerCase().includes(kw) ||
      (course.content || '').toLowerCase().includes(kw) ||
      (course.teacher || '').toLowerCase().includes(kw);

    const matchesTeacher = !teacherFilter.value || course.teacher === teacherFilter.value;
    return matchesKeyword && matchesTeacher;
  });
});

const totalFilteredEnrollments = computed(() =>
  filteredCourses.value.reduce((sum, item) => sum + (item.enrollCount || 0), 0),
);

const fetchData = async () => {
  loading.value = true;
  errorText.value = '';
  try {
    const [statsRes, coursesRes] = await Promise.all([adminApi.getStats(), adminApi.getCourses()]);

    stats.value = {
      totalCourses: statsRes.data?.totalCourses || 0,
      totalConcepts: statsRes.data?.totalConcepts || 0,
      totalTeachers: statsRes.data?.totalTeachers || 0,
      totalEnrollments: statsRes.data?.totalEnrollments || 0,
    };

    courses.value = Array.isArray(coursesRes.data) ? coursesRes.data : [];
  } catch (error) {
    console.error('加载管理数据失败', error);
    errorText.value = '数据加载失败，请稍后重试。';
  } finally {
    loading.value = false;
  }
};

const resetFilters = () => {
  keyword.value = '';
  teacherFilter.value = '';
};

onMounted(fetchData);
</script>

<template>
  <div class="admin-container">
    <div class="admin-inner">
      <div class="page-header">
        <div>
          <h1>课程管理</h1>
          <p>查看课程、知识点与选课数据概览</p>
        </div>
        <el-button type="primary" round :loading="loading" @click="fetchData">刷新数据</el-button>
      </div>

      <div class="stats-grid">
        <div v-for="card in statCards" :key="card.key" class="stat-card">
          <div class="stat-icon" :style="{ color: card.accent }">{{ card.icon }}</div>
          <div class="stat-info">
            <span class="stat-label">{{ card.label }}</span>
            <span class="stat-value">{{ stats[card.key as keyof Stats] }}</span>
          </div>
        </div>
      </div>

      <el-alert v-if="errorText" :title="errorText" type="error" :closable="false" class="error-alert" />

      <div class="table-section">
        <div class="table-header">
          <h2>课程列表</h2>
          <div class="table-header-meta">
            <span class="table-count">筛选后 {{ filteredCourses.length }} 条</span>
            <span class="table-count">选课总计 {{ totalFilteredEnrollments }}</span>
          </div>
        </div>

        <div class="filters">
          <el-input v-model="keyword" placeholder="搜索课程名 / 教师 / 简介 / ID" clearable class="filter-input" />
          <el-select v-model="teacherFilter" clearable placeholder="按教师筛选" class="filter-select">
            <el-option v-for="teacher in teacherOptions" :key="teacher" :label="teacher" :value="teacher" />
          </el-select>
          <el-button round @click="resetFilters">重置筛选</el-button>
        </div>

        <el-table
          :data="filteredCourses"
          v-loading="loading"
          stripe
          style="width: 100%"
          max-height="540"
          :header-cell-style="{
            background: 'rgba(248,250,252,0.9)',
            color: '#334155',
            fontWeight: 600,
            fontSize: '13px',
          }"
          :cell-style="{ fontSize: '13px', color: '#334155' }"
        >
          <template #empty>
            <el-empty description="没有匹配的课程数据" />
          </template>

          <el-table-column prop="id" label="ID" width="100" sortable />

          <el-table-column prop="name" label="课程名称" min-width="200" sortable>
            <template #default="{ row }">
              <span class="course-name">{{ row.name }}</span>
            </template>
          </el-table-column>

          <el-table-column prop="content" label="简介" min-width="320" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="content-text">{{ row.content || '-' }}</span>
            </template>
          </el-table-column>

          <el-table-column prop="teacher" label="授课教师" width="150">
            <template #default="{ row }">
              <el-tag v-if="row.teacher" size="small" effect="plain" class="teacher-tag">{{ row.teacher }}</el-tag>
              <span v-else class="no-data">-</span>
            </template>
          </el-table-column>

          <el-table-column prop="enrollCount" label="选课人数" width="120" sortable align="center">
            <template #default="{ row }">
              <span class="enroll-count" :class="{ highlight: row.enrollCount >= 100 }">{{ row.enrollCount }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.admin-container {
  height: 100%;
  overflow-y: auto;
  padding: 18px;
}

.admin-inner {
  max-width: 1180px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;

  h1 {
    font-size: 30px;
    font-weight: 650;
    color: #0f172a;
    letter-spacing: 0.01em;
    margin-bottom: 4px;
  }

  p {
    color: #64748b;
    font-size: 14px;
  }
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(15, 23, 42, 0.09);
  border-radius: 14px;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.06);

  &:hover {
    transform: translateY(-1px);
    box-shadow: 0 16px 30px rgba(15, 23, 42, 0.1);
  }
}

.stat-icon {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  font-size: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(248, 250, 252, 0.95);
  border: 1px solid rgba(148, 163, 184, 0.25);
}

.stat-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-label {
  color: #64748b;
  font-size: 12px;
}

.stat-value {
  color: #0f172a;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.1;
}

.error-alert {
  margin-bottom: 12px;
}

.table-section {
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(15, 23, 42, 0.09);
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 16px 34px rgba(15, 23, 42, 0.08);
}

.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);

  h2 {
    font-size: 16px;
    font-weight: 650;
    color: #0f172a;
  }
}

.table-header-meta {
  display: flex;
  align-items: center;
  gap: 12px;
}

.table-count {
  color: #64748b;
  font-size: 12px;
}

.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
  background: rgba(248, 250, 252, 0.55);
}

.filter-input {
  flex: 1;
}

.filter-select {
  width: 200px;
}

.course-name {
  font-weight: 600;
  color: #0f172a;
}

.content-text {
  color: #334155;
}

.teacher-tag {
  border-color: rgba(16, 163, 127, 0.34);
  color: #0f766e;
}

.enroll-count {
  font-weight: 700;
  color: #10a37f;

  &.highlight {
    color: #2563eb;
  }
}

.no-data {
  color: #94a3b8;
}

:deep(.el-table) {
  --el-table-border-color: rgba(15, 23, 42, 0.07);
  --el-table-row-hover-bg-color: rgba(248, 250, 252, 0.88);
}

@media (max-width: 1000px) {
  .stats-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .filters {
    flex-wrap: wrap;
  }

  .filter-select {
    width: 180px;
  }
}

@media (max-width: 720px) {
  .admin-container {
    padding: 12px;
  }

  .page-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .page-header h1 {
    font-size: 24px;
  }

  .stats-grid {
    grid-template-columns: 1fr;
  }

  .table-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 6px;
  }

  .table-header-meta {
    gap: 8px;
    flex-wrap: wrap;
  }

  .filter-select,
  .filter-input {
    width: 100%;
  }
}
</style>
