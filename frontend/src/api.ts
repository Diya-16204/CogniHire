import axios from 'axios'

declare module 'axios' { interface AxiosRequestConfig { __coldStartRetry?: boolean } }

const configuredApiUrl = (import.meta.env.VITE_API_BASE_URL || import.meta.env.VITE_API_URL)?.trim().replace(/\/+$/, '')
const apiBaseUrl = configuredApiUrl
  ? (configuredApiUrl.endsWith('/api/v1') ? configuredApiUrl : `${configuredApiUrl}/api/v1`)
  : 'http://localhost:8080/api/v1'

const configuredWsUrl = import.meta.env.VITE_WS_BASE_URL?.trim().replace(/\/+$/, '')
export const wsBaseUrl = configuredWsUrl
  ? configuredWsUrl.replace(/^https:/i, 'wss:').replace(/^http:/i, 'ws:')
  : `${apiBaseUrl.startsWith('https:') ? 'wss:' : 'ws:'}//${new URL(apiBaseUrl).host}/ws/interview`

export const api = axios.create({ baseURL: apiBaseUrl, timeout: 90000 })

// Render's free instance may take a while to wake. Retry safe reads and login
// once on transient network/gateway failures; never replay assessment writes.
api.interceptors.response.use(undefined, async (error) => {
  const config = error.config
  const method = (config?.method ?? 'get').toLowerCase()
  const isLogin = method === 'post' && (config?.url ?? '').includes('/auth/login')
  const transient = !error.response || [502, 503, 504].includes(error.response.status)
  if (config && (method === 'get' || isLogin) && transient && !config.__coldStartRetry) {
    config.__coldStartRetry = true
    await new Promise(resolve => window.setTimeout(resolve, 1800))
    return api.request(config)
  }
  return Promise.reject(error)
})

export type RecruiterFollowUp = {
  id: string
  question: string
  answer: string | null
  createdAt: string
  answeredAt: string | null
}

export async function fetchRecruiterFollowUps(attemptId: string) {
  const { data } = await api.get<RecruiterFollowUp[]>(`/candidate/attempts/${attemptId}/interventions`)
  return data
}

api.interceptors.request.use((config) => {
  const token = sessionStorage.getItem('cognihire-token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
api.interceptors.response.use((response) => response, (error) => {
  const requestPath = error.config?.url ?? ''
  const isAuthRequest = requestPath.includes('/auth/')
  if (error.response?.status === 401 && !isAuthRequest) {
    sessionStorage.clear()
    sessionStorage.setItem('cognihire-auth-notice', 'Your session expired or was rejected. Sign in again.')
    window.location.assign('/login')
  }
  return Promise.reject(error)
})

export type Assessment = { id: string; title: string; description: string; durationMinutes: number; status: string; version: number; testCode?: string | null; aiPolicy: { enabled: boolean; allowedCategories: string[]; maxHintsPerAttempt: number; maxPenalty: number }; questions: Question[] }
export type Question = { id: string; title: string; statement: string; difficulty: string; points: number; position: number; publicSamples: unknown[] }
export function errorMessage(error: unknown) {
  if (axios.isAxiosError(error)) {
    const responseData = error.response?.data as { message?: unknown; detail?: unknown } | undefined
    const backendMessage = [responseData?.message, responseData?.detail]
      .find((value): value is string => typeof value === 'string' && value.trim().length > 0)
    if (error.response?.status === 401) {
      return (error.config?.url ?? '').includes('/auth/')
        ? 'Email or password is incorrect.'
        : 'Your session expired or is invalid. Sign in again.'
    }
    if (error.response?.status === 403) return backendMessage ?? 'The server denied this request (403). Check the active login token and account permissions.'
    if (error.response?.status === 404) {
      if (backendMessage) return backendMessage
      const method = (error.config?.method ?? 'GET').toUpperCase()
      const url = `${error.config?.baseURL ?? ''}${error.config?.url ?? ''}`
      return `Not found (404): ${method} ${url}. Restart the backend if its source was recently updated.`
    }
    if (backendMessage) return backendMessage
    if (!error.response) return 'Cannot reach the CogniHire backend. It may be starting after an idle period; wait briefly and retry.'
    return error.message
  }
  return 'Something went wrong. Please try again.'
}
