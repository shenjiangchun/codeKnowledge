import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api/auth'

const TOKEN_KEY = 'hisi-token'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const username = ref<string | null>(null)
  const role = ref<string | null>(null)
  const showLoginDialog = ref(false)
  const initialized = ref(false)

  const isLoggedIn = computed(() => !!token.value && !!username.value)
  const isAdmin = computed(() => role.value === 'ADMIN')

  async function init() {
    if (!token.value) {
      initialized.value = true
      return
    }
    try {
      const me = await authApi.me()
      if (me) {
        username.value = me.username
        role.value = me.role
      } else {
        clearAuth()
      }
    } catch {
      clearAuth()
    } finally {
      initialized.value = true
    }
  }

  function setAuth(newToken: string, newUser: string, newRole: string) {
    token.value = newToken
    username.value = newUser
    role.value = newRole
    localStorage.setItem(TOKEN_KEY, newToken)
  }

  function clearAuth() {
    token.value = null
    username.value = null
    role.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  async function login(user: string, password: string) {
    const res = await authApi.login(user, password)
    setAuth(res.token, res.username, res.role)
    showLoginDialog.value = false
  }

  async function register(user: string, password: string) {
    const res = await authApi.register(user, password)
    setAuth(res.token, res.username, res.role)
    showLoginDialog.value = false
  }

  function logout() {
    clearAuth()
  }

  return {
    token, username, role, showLoginDialog, initialized,
    isLoggedIn, isAdmin,
    init, login, register, logout
  }
})
