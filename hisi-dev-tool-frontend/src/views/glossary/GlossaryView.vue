<template>
  <div class="glossary-page">
    <div class="page-header">
      <div>
        <h2>术语管理</h2>
        <p>配置项目术语对照表，LLM 生成语义描述时将自动遵守术语规范</p>
      </div>
      <el-button type="primary" @click="openCreateDialog">
        <el-icon><Plus /></el-icon>
        新增术语
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="terms" v-loading="loading" empty-text="暂无术语，点击「新增术语」添加" stripe>
        <el-table-column prop="wrongTerm" label="错误术语" width="180">
          <template #default="{ row }">
            <el-tag type="danger" effect="plain">{{ row.wrongTerm }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="" width="60" align="center">
          <template #default>
            <el-icon><Right /></el-icon>
          </template>
        </el-table-column>
        <el-table-column prop="correctTerm" label="正确术语" width="180">
          <template #default="{ row }">
            <el-tag type="success" effect="plain">{{ row.correctTerm }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="context" label="说明" min-width="200" show-overflow-tooltip />
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.updatedAt || row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditDialog(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑术语' : '新增术语'"
      width="480px"
      destroy-on-close
    >
      <el-form :model="form" label-width="80px" @submit.prevent="handleSubmit">
        <el-form-item label="错误术语" required>
          <el-input v-model="form.wrongTerm" placeholder="LLM 可能错误使用的术语" />
        </el-form-item>
        <el-form-item label="正确术语" required>
          <el-input v-model="form.correctTerm" placeholder="应该使用的正确术语" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.context" placeholder="可选，如适用场景说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit" :loading="submitting">
          {{ isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Right } from '@element-plus/icons-vue'
import { glossaryApi } from '@/api/glossary'
import { useAppStore } from '@/stores/app'
import type { GlossaryTerm } from '@/types/glossary'

const appStore = useAppStore()
const terms = ref<GlossaryTerm[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const isEdit = ref(false)
const editingId = ref<number | null>(null)

const form = ref({
  wrongTerm: '',
  correctTerm: '',
  context: ''
})

function formatTime(epoch?: number): string {
  if (!epoch) return '-'
  return new Date(epoch * 1000).toLocaleString('zh-CN')
}

async function loadTerms() {
  const projectPath = appStore.projectDir
  if (!projectPath) return

  loading.value = true
  try {
    terms.value = await glossaryApi.list(projectPath) as unknown as GlossaryTerm[]
  } catch {
    ElMessage.error('加载术语列表失败')
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  isEdit.value = false
  editingId.value = null
  form.value = { wrongTerm: '', correctTerm: '', context: '' }
  dialogVisible.value = true
}

function openEditDialog(term: GlossaryTerm) {
  isEdit.value = true
  editingId.value = term.id!
  form.value = {
    wrongTerm: term.wrongTerm,
    correctTerm: term.correctTerm,
    context: term.context || ''
  }
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!form.value.wrongTerm.trim() || !form.value.correctTerm.trim()) {
    ElMessage.warning('错误术语和正确术语不能为空')
    return
  }

  const projectPath = appStore.projectDir
  if (!projectPath) {
    ElMessage.warning('请先选择项目')
    return
  }

  submitting.value = true
  try {
    const payload: GlossaryTerm = {
      projectPath,
      wrongTerm: form.value.wrongTerm.trim(),
      correctTerm: form.value.correctTerm.trim(),
      context: form.value.context.trim() || undefined
    }

    if (isEdit.value && editingId.value != null) {
      await glossaryApi.update(editingId.value, payload)
      ElMessage.success('术语已更新')
    } else {
      await glossaryApi.create(payload)
      ElMessage.success('术语已创建')
    }

    dialogVisible.value = false
    await loadTerms()
  } catch {
    ElMessage.error(isEdit.value ? '更新失败' : '创建失败')
  } finally {
    submitting.value = false
  }
}

async function handleDelete(term: GlossaryTerm) {
  try {
    await ElMessageBox.confirm(
      `确定删除术语「${term.wrongTerm} → ${term.correctTerm}」？`,
      '删除确认',
      { type: 'warning' }
    )
    await glossaryApi.delete(term.id!)
    ElMessage.success('已删除')
    await loadTerms()
  } catch {
    // user cancelled
  }
}

onMounted(() => {
  loadTerms()
})
</script>

<style scoped>
.glossary-page {
  padding: 24px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
}

.page-header h2 {
  margin: 0 0 8px 0;
}

.page-header p {
  margin: 0;
  color: #606266;
  font-size: 14px;
}
</style>
