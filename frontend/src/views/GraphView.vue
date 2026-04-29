<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import KnowledgeGraphCanvas from '@/components/KnowledgeGraphCanvas.vue'
import { graphApi } from '@/api'
import type { GraphContext, GraphNode } from '@/types'

const loading = ref(true)
const errorText = ref('')
const nodeLimit = ref(80)
const query = ref('')
const selectedNodeId = ref<string | null>(null)
const selectedNode = ref<GraphNode | null>(null)
const graphContext = ref<GraphContext | null>(null)

const graphSubtitle = computed(() => {
  if (!graphContext.value?.stats) {
    return '浏览全局课程、知识点与教师之间的完整网络。'
  }
  return `当前展示 ${graphContext.value.stats.nodeCount} 个节点与 ${graphContext.value.stats.edgeCount} 条关系`
})

const loadGraph = async (focusId?: string) => {
  loading.value = true
  errorText.value = ''

  try {
    const response = await graphApi.getGraphData(nodeLimit.value, focusId)
    const payload = response.data?.data || response.data
    const nodes = Array.isArray(payload?.nodes) ? payload.nodes : []
    const edges = Array.isArray(payload?.edges) ? payload.edges : []
    graphContext.value = {
      nodes,
      edges,
      focusNodeIds: focusId ? [focusId] : [],
      annotations: [],
      stats: {
        nodeCount: payload?.nodeCount || nodes.length,
        edgeCount: payload?.edgeCount || edges.length,
      },
    }
    selectedNodeId.value = focusId && nodes.some((node: GraphNode) => node.id === focusId) ? focusId : null
    selectedNode.value = nodes.find((node: GraphNode) => node.id === selectedNodeId.value) || null
  } catch (error) {
    console.error('加载图谱失败', error)
    errorText.value = '图谱加载失败，请检查后端服务后重试。'
    graphContext.value = null
  } finally {
    loading.value = false
  }
}

const locateNode = async () => {
  const keyword = query.value.trim()
  if (!keyword) {
    ElMessage.warning('请输入节点名称关键字')
    return
  }

  const localTarget = graphContext.value?.nodes.find((node) => node.label.toLowerCase().includes(keyword.toLowerCase()))
  if (localTarget) {
    selectedNodeId.value = localTarget.id
    return
  }

  try {
    const searchRes = await graphApi.searchNodes(keyword, 5)
    const first = searchRes.data?.[0]
    if (!first?.id) {
      ElMessage.info('未找到匹配节点')
      return
    }
    await loadGraph(first.id)
    ElMessage.success(`已定位到「${first.label}」`)
  } catch (error) {
    console.error('搜索节点失败', error)
    ElMessage.error('搜索失败，请稍后重试')
  }
}

onMounted(() => {
  void loadGraph()
})
</script>

<template>
  <div class="graph-page">
    <section class="graph-header">
      <div>
        <span class="header-kicker">Knowledge Atlas</span>
        <h1>知识图谱</h1>
        <p>从课程、知识点与教师三条主轴查看完整网络，适合作为深度探索页使用。</p>
      </div>

      <div class="header-actions">
        <el-input
          v-model="query"
          placeholder="搜索课程 / 知识点 / 教师"
          clearable
          class="search-input"
          @keyup.enter="locateNode"
        />
        <el-button round @click="locateNode">定位节点</el-button>
        <el-button type="primary" round :loading="loading" @click="loadGraph()">刷新图谱</el-button>
      </div>
    </section>

    <section class="control-bar">
      <div class="slider-block">
        <span>节点规模 {{ nodeLimit }}</span>
        <el-slider v-model="nodeLimit" :min="30" :max="250" :step="10" :show-tooltip="false" />
      </div>
      <el-button round @click="loadGraph(selectedNodeId || undefined)">按当前规模刷新</el-button>
    </section>

    <el-alert v-if="errorText" :title="errorText" type="error" :closable="false" class="error-alert" />

    <div class="graph-layout">
      <KnowledgeGraphCanvas
        :context="graphContext"
        :loading="loading"
        :selected-node-id="selectedNodeId"
        static-layout
        :max-physics-nodes="60"
        :show-detail-card="false"
        panel-title="图谱舞台"
        :panel-subtitle="graphSubtitle"
        empty-text="暂无图谱数据"
        @select-node="selectedNode = $event"
      />

      <aside class="inspector">
        <div class="inspector-card">
          <span class="inspector-kicker">Node Inspector</span>
          <h2>{{ selectedNode?.label || '等待选择节点' }}</h2>
          <p>
            {{
              selectedNode?.title ||
              '点击左侧任意节点后，这里会显示节点说明与当前上下文摘要。'
            }}
          </p>
        </div>

        <div class="inspector-card soft">
          <span class="inspector-kicker">当前范围</span>
          <div class="stats-grid">
            <div>
              <strong>{{ graphContext?.stats.nodeCount || 0 }}</strong>
              <span>节点</span>
            </div>
            <div>
              <strong>{{ graphContext?.stats.edgeCount || 0 }}</strong>
              <span>关系</span>
            </div>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped lang="scss">
.graph-page {
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 16px;
  background:
    radial-gradient(circle at 0 0, rgba(16, 185, 129, 0.12), transparent 24%),
    radial-gradient(circle at 100% 0, rgba(59, 130, 246, 0.12), transparent 24%),
    linear-gradient(180deg, #eef4f3 0%, #e8efee 100%);
}

.graph-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 12px;

  h1 {
    margin: 8px 0 6px;
    color: #0f172a;
    font-size: 28px;
    line-height: 1.08;
  }

  p {
    margin: 0;
    color: #475569;
    line-height: 1.75;
    font-size: 14px;
  }
}

.header-kicker,
.inspector-kicker {
  display: inline-flex;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.06);
  color: #64748b;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.search-input {
  width: 280px;
}

.control-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  padding: 10px 14px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.76);
  border: 1px solid rgba(148, 163, 184, 0.18);
  box-shadow: 0 18px 36px rgba(15, 23, 42, 0.06);
}

.slider-block {
  flex: 1;

  span {
    display: block;
    margin-bottom: 8px;
    color: #334155;
    font-size: 13px;
    font-weight: 600;
  }
}

.error-alert {
  margin-bottom: 16px;
}

.graph-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) 320px;
  gap: 18px;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.inspector {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.inspector-card {
  padding: 18px;
  border-radius: 24px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  background: rgba(255, 255, 255, 0.86);
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);

  h2 {
    margin: 12px 0 10px;
    color: #0f172a;
    font-size: 24px;
  }

  p {
    color: #475569;
    line-height: 1.8;
    font-size: 14px;
  }

  &.soft {
    background: linear-gradient(180deg, rgba(255, 255, 255, 0.88), rgba(240, 253, 250, 0.82));
  }
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 14px;

  div {
    padding: 14px;
    border-radius: 18px;
    background: rgba(15, 23, 42, 0.04);
  }

  strong {
    display: block;
    color: #0f172a;
    font-size: 24px;
  }

  span {
    display: block;
    margin-top: 4px;
    color: #64748b;
    font-size: 12px;
  }
}

@media (max-width: 1080px) {
  .graph-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .graph-page {
    padding: 16px;
  }

  .graph-header,
  .control-bar,
  .header-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .search-input {
    width: 100%;
  }

  .graph-header h1 {
    font-size: 28px;
  }
}
</style>
