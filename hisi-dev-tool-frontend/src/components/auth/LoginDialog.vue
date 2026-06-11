<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
    width="400px"
    :close-on-click-modal="false"
    title="登录 / 注册"
  >
    <el-tabs v-model="activeTab">
      <el-tab-pane label="登录" name="login">
        <el-form @submit.prevent="handleLogin" label-position="top">
          <el-form-item label="用户名">
            <el-input v-model="loginForm.username" placeholder="请输入用户名" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="loginForm.password" type="password" show-password placeholder="请输入密码" />
          </el-form-item>
          <el-button type="primary" :loading="loading" native-type="submit" style="width: 100%">
            登录
          </el-button>
        </el-form>
      </el-tab-pane>
      <el-tab-pane label="注册" name="register">
        <el-form @submit.prevent="handleRegister" label-position="top">
          <el-form-item label="用户名">
            <el-input v-model="registerForm.username" placeholder="2-32位字符" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="registerForm.password" type="password" show-password placeholder="至少4位" />
          </el-form-item>
          <el-button type="success" :loading="loading" native-type="submit" style="width: 100%">
            注册
          </el-button>
        </el-form>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

defineProps<{ modelValue: boolean }>()
defineEmits<{ 'update:modelValue': [value: boolean] }>()

const authStore = useAuthStore()
const activeTab = ref('login')
const loading = ref(false)

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive({ username: '', password: '' })

async function handleLogin() {
  if (!loginForm.username || !loginForm.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await authStore.login(loginForm.username, loginForm.password)
    ElMessage.success('登录成功')
    loginForm.username = ''
    loginForm.password = ''
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '登录失败'
    ElMessage.error(msg)
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  if (!registerForm.username || !registerForm.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await authStore.register(registerForm.username, registerForm.password)
    ElMessage.success('注册成功')
    registerForm.username = ''
    registerForm.password = ''
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '注册失败'
    ElMessage.error(msg)
  } finally {
    loading.value = false
  }
}
</script>
