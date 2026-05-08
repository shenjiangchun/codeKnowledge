<template>
  <div class="class-method-selector">
    <el-select
      v-model="selectedClass"
      filterable
      remote
      clearable
      placeholder="选择或搜索类名"
      :remote-method="handleClassSearch"
      :loading="classLoading"
      @change="handleClassChange"
      style="width: 100%"
    >
      <el-option
        v-for="item in filteredClasses"
        :key="item.className"
        :label="item.className"
        :value="item.className"
      >
        <span>{{ item.className }}</span>
        <span style="color: #999; font-size: 12px; margin-left: 8px;">
          ({{ item.methodCount }} methods)
        </span>
      </el-option>
    </el-select>

    <el-select
      v-model="selectedMethod"
      filterable
      clearable
      placeholder="选择方法"
      :loading="methodLoading"
      :disabled="!selectedClass"
      @change="handleMethodChange"
      style="width: 100%; margin-top: 8px;"
    >
      <el-option
        v-for="item in methods"
        :key="item.methodName"
        :label="item.methodName"
        :value="item.methodName"
      >
        <div>
          <div>{{ item.methodName }}</div>
          <div style="color: #999; font-size: 12px;">{{ item.signature }}</div>
        </div>
      </el-option>
    </el-select>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { knowledgeGraphApi } from '@/api/knowledgeGraph'
import { useAppStore } from '@/stores/app'

interface ClassInfo {
  className: string
  methodCount: number
}

interface MethodInfo {
  methodName: string
  signature: string
}

const props = defineProps<{
  project?: string
}>()

const emit = defineEmits<{
  (e: 'change', value: { className: string; methodName: string } | null): void
}>()

const appStore = useAppStore()
const classLoading = ref(false)
const methodLoading = ref(false)
const classes = ref<ClassInfo[]>([])
const methods = ref<MethodInfo[]>([])
const classSearchText = ref('')
const selectedClass = ref('')
const selectedMethod = ref('')

const filteredClasses = computed(() => {
  if (!classSearchText.value) return classes.value
  return classes.value.filter(c =>
    c.className.toLowerCase().includes(classSearchText.value.toLowerCase())
  )
})

const handleClassSearch = (query: string) => {
  classSearchText.value = query
}

const handleClassChange = async (value: string) => {
  selectedMethod.value = ''
  methods.value = []
  emit('change', null)

  if (!value) return

  methodLoading.value = true
  try {
    // 构建完整的项目路径
    let projectPath = props.project || ''
    if (appStore.projectDir && props.project && !props.project.includes(':') && !props.project.startsWith('/')) {
      projectPath = `${appStore.projectDir}\\${props.project}`
    }
    const res = await knowledgeGraphApi.getMethodsByClass(value, projectPath)
    // 转换为组件期望的格式
    methods.value = (res || []).map((m: any) => ({
      methodName: m.methodName,
      signature: m.signature
    }))
  } catch (error) {
    ElMessage.error('加载方法列表失败')
  } finally {
    methodLoading.value = false
  }
}

const handleMethodChange = (value: string) => {
  if (value && selectedClass.value) {
    emit('change', {
      className: selectedClass.value,
      methodName: value
    })
  } else {
    emit('change', null)
  }
}

const loadClasses = async () => {
  if (!props.project) return
  classLoading.value = true
  try {
    // 构建完整的项目路径
    let projectPath = props.project
    if (appStore.projectDir && !props.project?.includes(':') && !props.project?.startsWith('/')) {
      projectPath = `${appStore.projectDir}\\${props.project}`
    }
    const res = await knowledgeGraphApi.getClasses(projectPath)
    // 转换为组件期望的格式
    classes.value = (res || []).map((c: string) => ({
      className: c,
      methodCount: 0 // 不显示方法数量了
    }))
  } catch (error) {
    ElMessage.error('加载类列表失败')
  } finally {
    classLoading.value = false
  }
}

watch(() => props.project, loadClasses, { immediate: true })
</script>

<style scoped>
.class-method-selector {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>