<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { DataSet } from 'vis-data'
import { Network } from 'vis-network'
import type { GraphContext, GraphNode } from '@/types'

const props = withDefaults(
  defineProps<{
    context: GraphContext | null
    loading?: boolean
    panelTitle?: string
    panelSubtitle?: string
    selectedNodeId?: string | null
    showHeader?: boolean
    showDetailCard?: boolean
    showToolbar?: boolean
    showStats?: boolean
    showAnnotations?: boolean
    staticLayout?: boolean
    maxPhysicsNodes?: number
    emptyText?: string
  }>(),
  {
    loading: false,
    panelTitle: '知识图谱',
    panelSubtitle: '',
    selectedNodeId: null,
    showHeader: true,
    showDetailCard: true,
    showToolbar: true,
    showStats: true,
    showAnnotations: true,
    staticLayout: false,
    maxPhysicsNodes: 80,
    emptyText: '暂无图谱数据',
  },
)

const emit = defineEmits<{
  (event: 'select-node', node: GraphNode | null): void
}>()

const networkContainer = ref<HTMLElement | null>(null)
const selectedNode = ref<GraphNode | null>(null)
const physicsEnabled = ref(true)

let network: Network | null = null
let renderFrame: number | null = null

const legendItems = [
  { label: '课程', key: 'Course', color: '#3b82f6' },
  { label: '知识点', key: 'Concept', color: '#10b981' },
  { label: '教师', key: 'Teacher', color: '#f59e0b' },
  { label: '时间', key: 'Time', color: '#ef4444' },
  { label: '学生', key: 'Student', color: '#8b5cf6' },
]

const stats = computed(() => props.context?.stats || { nodeCount: 0, edgeCount: 0 })
const annotations = computed(() => props.context?.annotations || [])
const nodeCount = computed(() => props.context?.nodes.length || 0)
const isPhysicsLocked = computed(() => props.staticLayout || nodeCount.value > props.maxPhysicsNodes)
const showEdgeLabels = computed(() => {
  const edgeCount = props.context?.edges.length || 0
  return nodeCount.value <= 8 && edgeCount <= 10
})

const groupColors: Record<string, { background: string; border: string }> = {
  Course: { background: '#3b82f6', border: '#2563eb' },
  Concept: { background: '#10b981', border: '#059669' },
  Teacher: { background: '#f59e0b', border: '#d97706' },
  Time: { background: '#ef4444', border: '#dc2626' },
  TimeSlot: { background: '#ef4444', border: '#dc2626' },
  Student: { background: '#8b5cf6', border: '#7c3aed' },
  User: { background: '#8b5cf6', border: '#7c3aed' },
}

const resolveNodeSize = (node: GraphNode) => {
  if (node.group === 'Teacher') return node.highlighted ? 24 : 20
  if (node.group === 'Course') return node.highlighted ? 19 : 16
  if (node.group === 'Time') return node.highlighted ? 17 : 14
  if (node.group === 'Student' || node.group === 'User') return node.highlighted ? 20 : 16
  return node.highlighted ? 14 : 11
}

const resolveNodeColor = (node: GraphNode) => {
  const palette = groupColors[node.group] || { background: '#64748b', border: '#475569' }
  if (node.highlighted) {
    return {
      background: palette.background,
      border: '#f8fafc',
      highlight: {
        background: palette.background,
        border: '#ffffff',
      },
      hover: {
        background: palette.background,
        border: '#ffffff',
      },
    }
  }

  return {
    background: palette.background,
    border: palette.border,
    highlight: {
      background: palette.background,
      border: '#f8fafc',
    },
    hover: {
      background: palette.background,
      border: '#f8fafc',
    },
  }
}

const buildStaticPosition = (node: GraphNode, index: number, nodes: GraphNode[]) => {
  const groupOrder = ['Teacher', 'Course', 'Concept', 'Time', 'TimeSlot', 'Student', 'User']
  const groupIndex = Math.max(groupOrder.indexOf(node.group), 0)
  const sameGroupNodes = nodes.filter((item) => item.group === node.group)
  const sameGroupIndex = sameGroupNodes.findIndex((item) => item.id === node.id)
  const safeGroupCount = Math.max(sameGroupNodes.length, 1)
  const baseRadius = Math.max(260, Math.sqrt(nodes.length) * 64)
  const radius = baseRadius * (0.42 + groupIndex * 0.18)
  const angle = (sameGroupIndex / safeGroupCount) * Math.PI * 2 + groupIndex * 0.58

  return {
    x: Math.cos(angle) * radius,
    y: Math.sin(angle) * radius,
    fixed: false,
    physics: false,
    sortIndex: index,
  }
}

const focusNode = (nodeId: string | null) => {
  if (!network || !nodeId) {
    return
  }

  network.focus(nodeId, {
    scale: 1.18,
    animation: {
      duration: 420,
      easingFunction: 'easeInOutQuad',
    },
  })

  const matched = props.context?.nodes.find((item) => item.id === nodeId) || null
  selectedNode.value = matched
  emit('select-node', matched)
}

const renderNetwork = () => {
  if (!networkContainer.value) {
    return
  }

  if (network) {
    network.destroy()
    network = null
  }

  const nodes = props.context?.nodes || []
  const nodeIdSet = new Set(nodes.map((node) => node.id))
  const edges = (props.context?.edges || []).filter((edge) => nodeIdSet.has(edge.from) && nodeIdSet.has(edge.to))
  if (!nodes.length) {
    selectedNode.value = null
    emit('select-node', null)
    return
  }

  const useStaticLayout = isPhysicsLocked.value
  const visNodes = new DataSet(
    nodes.map((node, index) => {
      const staticPosition = useStaticLayout ? buildStaticPosition(node, index, nodes) : {}

      return {
        id: node.id,
        label: node.label,
        title: node.title || node.label,
        shape: 'dot',
        size: resolveNodeSize(node),
        color: resolveNodeColor(node),
        font: {
          color: '#e2e8f0',
          face: 'IBM Plex Sans, PingFang SC, sans-serif',
          size: useStaticLayout && nodes.length > 120 ? 10 : 12,
        },
        borderWidth: node.highlighted ? 3 : 1.5,
        ...staticPosition,
      }
    }),
  )

  const visEdges = new DataSet(
    edges.map((edge, index) => ({
      id: `${edge.from}-${edge.to}-${edge.label}-${index}`,
      from: edge.from,
      to: edge.to,
      label: showEdgeLabels.value ? edge.label : '',
      title: edge.label,
      arrows: 'to',
      smooth: { enabled: true, type: 'continuous', roundness: 0.4 },
      color: edge.highlighted
        ? { color: '#22c55e', highlight: '#10b981', hover: '#34d399' }
        : { color: 'rgba(148,163,184,0.46)', highlight: '#10b981', hover: '#60a5fa' },
      font: {
        color: edge.highlighted ? '#bbf7d0' : '#94a3b8',
        size: 9,
        face: 'IBM Plex Sans, PingFang SC, sans-serif',
        strokeWidth: 0,
      },
      width: edge.highlighted ? 2.4 : 1.2,
    })),
  )

  network = new Network(
    networkContainer.value,
    { nodes: visNodes, edges: visEdges },
    {
      physics: {
        enabled: physicsEnabled.value && !useStaticLayout,
        solver: 'forceAtlas2Based',
        forceAtlas2Based: {
          gravitationalConstant: -72,
          centralGravity: 0.015,
          springLength: 122,
          springConstant: 0.06,
          damping: 0.7,
        },
        stabilization: { iterations: useStaticLayout ? 0 : 120, fit: true },
      },
      interaction: {
        hover: true,
        dragNodes: true,
        dragView: true,
        zoomView: true,
        tooltipDelay: 120,
      },
      layout: {
        improvedLayout: !useStaticLayout,
      },
      nodes: {
        shadow: {
          enabled: !useStaticLayout || nodes.length <= 120,
          color: 'rgba(15,23,42,0.28)',
          size: 16,
          x: 0,
          y: 6,
        },
      },
      edges: {
        selectionWidth: 0,
      },
    },
  )

  window.requestAnimationFrame(() => fitGraph())

  network.on('click', ({ nodes: clickedNodes }) => {
    if (!clickedNodes.length) {
      selectedNode.value = null
      emit('select-node', null)
      return
    }

    const matched = nodes.find((item) => item.id === clickedNodes[0]) || null
    selectedNode.value = matched
    emit('select-node', matched)
  })

  focusNode(props.selectedNodeId || props.context?.focusNodeIds?.[0] || null)
}

const scheduleRenderNetwork = () => {
  if (renderFrame !== null) {
    window.cancelAnimationFrame(renderFrame)
  }

  void nextTick(() => {
    renderFrame = window.requestAnimationFrame(() => {
      renderFrame = null
      renderNetwork()
    })
  })
}

const fitGraph = () => {
  if (!network) {
    return
  }
  network.fit({
    animation: {
      duration: 380,
      easingFunction: 'easeInOutQuad',
    },
  })
}

const togglePhysics = () => {
  if (isPhysicsLocked.value) {
    return
  }

  physicsEnabled.value = !physicsEnabled.value
  if (network) {
    network.setOptions({
      physics: {
        enabled: physicsEnabled.value,
      },
    })
  }
}

watch(() => props.context, scheduleRenderNetwork)
watch(
  () => props.selectedNodeId,
  (nodeId) => {
    if (nodeId) {
      focusNode(nodeId)
    }
  },
)

onMounted(scheduleRenderNetwork)

onBeforeUnmount(() => {
  if (renderFrame !== null) {
    window.cancelAnimationFrame(renderFrame)
  }
  if (network) {
    network.destroy()
    network = null
  }
})

defineExpose({
  fitGraph,
  focusNode,
  togglePhysics,
})
</script>

<template>
  <div class="kg-panel">
    <div v-if="showHeader" class="panel-top">
      <div>
        <span class="panel-kicker">Live Graph</span>
        <h3>{{ panelTitle }}</h3>
        <p v-if="panelSubtitle">{{ panelSubtitle }}</p>
      </div>

      <div v-if="showToolbar" class="toolbar">
        <el-button round size="small" @click="fitGraph">重置视图</el-button>
        <el-button round size="small" :disabled="isPhysicsLocked" @click="togglePhysics">
          {{ isPhysicsLocked ? '静态布局' : physicsEnabled ? '暂停布局' : '恢复布局' }}
        </el-button>
      </div>
    </div>

    <div v-if="showAnnotations && annotations.length" class="annotation-list">
      <div
        v-for="annotation in annotations"
        :key="annotation.id"
        class="annotation-chip"
        :class="annotation.tone || 'info'"
      >
        <strong>{{ annotation.title }}</strong>
        <span>{{ annotation.content }}</span>
      </div>
    </div>

    <div class="canvas-shell" :class="{ loading }">
      <div v-if="!context?.nodes?.length && !loading" class="empty-state">
        <el-empty :description="emptyText" />
      </div>
      <div ref="networkContainer" class="network-canvas" />
    </div>

    <div class="panel-bottom">
      <div class="legend-list">
        <span v-for="item in legendItems" :key="item.key" class="legend-item">
          <span class="legend-dot" :style="{ backgroundColor: item.color }" />
          {{ item.label }}
        </span>
      </div>

      <div v-if="showStats" class="stats">
        <span>{{ stats.nodeCount }} 节点</span>
        <span>{{ stats.edgeCount }} 关系</span>
      </div>
    </div>

    <transition v-if="showDetailCard" name="detail-fade">
      <div v-if="selectedNode" class="detail-card">
        <div class="detail-head">
          <span class="detail-type">{{ selectedNode.group }}</span>
          <strong>{{ selectedNode.label }}</strong>
        </div>
        <p class="detail-desc">{{ selectedNode.title || '暂无说明' }}</p>
      </div>
    </transition>
  </div>
</template>

<style scoped lang="scss">
.kg-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
  padding: 18px;
  border-radius: 28px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  background:
    radial-gradient(circle at 18% 0, rgba(37, 99, 235, 0.18), transparent 26%),
    radial-gradient(circle at 82% 100%, rgba(16, 185, 129, 0.18), transparent 30%),
    linear-gradient(180deg, rgba(8, 15, 32, 0.96), rgba(12, 20, 36, 0.94));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.06),
    0 24px 60px rgba(15, 23, 42, 0.24);
  overflow: hidden;
}

.panel-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;

  h3 {
    margin: 6px 0 4px;
    color: #f8fafc;
    font-size: 22px;
    font-weight: 650;
  }

  p {
    color: rgba(226, 232, 240, 0.72);
    line-height: 1.7;
    font-size: 13px;
  }
}

.panel-kicker {
  display: inline-flex;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  color: #cbd5e1;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.toolbar {
  display: flex;
  gap: 8px;
}

.annotation-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.annotation-chip {
  padding: 12px 14px;
  border-radius: 16px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  background: rgba(15, 23, 42, 0.38);

  strong {
    display: block;
    color: #f8fafc;
    font-size: 13px;
  }

  span {
    display: block;
    margin-top: 4px;
    color: rgba(226, 232, 240, 0.74);
    line-height: 1.6;
    font-size: 12px;
  }

  &.warning {
    border-color: rgba(245, 158, 11, 0.3);
    background: rgba(120, 53, 15, 0.28);
  }

  &.success {
    border-color: rgba(16, 185, 129, 0.3);
    background: rgba(6, 95, 70, 0.26);
  }
}

.canvas-shell {
  position: relative;
  flex: 1;
  min-height: 320px;
  border-radius: 24px;
  border: 1px solid rgba(148, 163, 184, 0.16);
  background:
    radial-gradient(circle at 50% 50%, rgba(59, 130, 246, 0.06), transparent 44%),
    rgba(7, 12, 24, 0.72);
  overflow: hidden;

  &.loading::after {
    content: '';
    position: absolute;
    inset: 0;
    background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.04), transparent);
    animation: pulse 1.2s linear infinite;
    pointer-events: none;
  }
}

.network-canvas {
  width: 100%;
  height: 100%;
}

.empty-state {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(7, 12, 24, 0.9);
}

.panel-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.legend-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgba(226, 232, 240, 0.72);
  font-size: 12px;
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  box-shadow: 0 0 12px currentColor;
}

.stats {
  display: inline-flex;
  gap: 12px;
  color: rgba(226, 232, 240, 0.72);
  font-size: 12px;
}

.detail-card {
  position: absolute;
  right: 18px;
  bottom: 18px;
  width: min(280px, calc(100% - 36px));
  padding: 16px;
  border-radius: 18px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  background: rgba(15, 23, 42, 0.82);
  backdrop-filter: blur(14px);
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.3);
}

.detail-head {
  display: flex;
  flex-direction: column;
  gap: 4px;

  strong {
    color: #f8fafc;
    font-size: 16px;
  }
}

.detail-type {
  color: #94a3b8;
  font-size: 11px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.detail-desc {
  margin-top: 10px;
  color: rgba(226, 232, 240, 0.76);
  font-size: 13px;
  line-height: 1.7;
}

.detail-fade-enter-active,
.detail-fade-leave-active {
  transition: all 0.22s ease;
}

.detail-fade-enter-from,
.detail-fade-leave-to {
  transform: translateY(8px);
  opacity: 0;
}

@keyframes pulse {
  from {
    transform: translateX(-100%);
  }

  to {
    transform: translateX(100%);
  }
}

@media (max-width: 760px) {
  .kg-panel {
    padding: 14px;
    border-radius: 22px;
  }

  .panel-top {
    flex-direction: column;
  }

  .annotation-list {
    grid-template-columns: 1fr;
  }

  .panel-bottom {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
