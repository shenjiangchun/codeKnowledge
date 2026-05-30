<template>
  <el-table :data="apis" stripe>
    <el-table-column prop="method" label="方法" width="80">
      <template #default="{ row }">
        <el-tag :type="getMethodType(row.method)" size="small">
          {{ row.method }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column prop="path" label="路径" />
    <el-table-column prop="description" label="描述" />
  </el-table>
</template>

<script setup lang="ts">
defineProps<{
  apis: Array<{ method: string; path: string; description: string }>
}>()

const getMethodType = (method: string) => {
  const types: Record<string, string> = {
    GET: 'success',
    POST: 'primary',
    PUT: 'warning',
    DELETE: 'danger',
    PATCH: 'info'
  }
  return types[method] || ''
}
</script>