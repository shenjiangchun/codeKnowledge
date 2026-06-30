<script setup lang="ts">
import { ref } from 'vue'
import { useRamChatStore } from '@/stores/ramChatStore'
import { Promotion } from '@element-plus/icons-vue'

const store = useRamChatStore()
const text = ref('')
const sending = ref(false)

async function send() {
  const trimmed = text.value.trim()
  if (!trimmed || sending.value) return
  sending.value = true
  try {
    await store.sendMessage(trimmed)
    text.value = ''
  } catch (e: unknown) {
    console.error('[ChatInput] send failed', e)
  } finally {
    sending.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}
</script>

<template>
  <div class="input-box">
    <el-input
      v-model="text"
      type="textarea"
      :rows="2"
      placeholder="输入问题，回车发送（Shift+Enter 换行）"
      resize="none"
      @keydown="onKeydown"
    />
    <div class="input-actions">
      <el-button type="primary" :icon="Promotion" :loading="sending" @click="send">
        发送
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.input-box {
  padding: 12px 20px;
}
.input-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}
</style>
