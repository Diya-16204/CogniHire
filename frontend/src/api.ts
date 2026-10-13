import axios from 'axios'

export const api = axios.create({ baseURL: import.meta.env.VITE_API_URL ?? 'http://localhost:8080/api/v1', timeout: 20000 })
api.interceptors.request.use((config) => {
  const token = sessionStorage.getItem('cognihire-token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
api.interceptors.response.use((response) => response, (error) => {
  if (error.response?.status === 401) sessionStorage.removeItem('cognihire-token')
  return Promise.reject(error)
})

export type Assessment = { id: string; title: string; description: string; durationMinutes: number; status: string; version: number; aiPolicy: { enabled: boolean; allowedCategories: string[]; maxHintsPerAttempt: number; maxPenalty: number }; questions: Question[] }
export type Question = { id: string; title: string; statement: string; difficulty: string; points: number; position: number; publicSamples: unknown[] }
export function errorMessage(error: unknown) {
  if (axios.isAxiosError(error)) return error.response?.data?.detail ?? error.response?.data?.message ?? error.message
  return 'Something went wrong. Please try again.'
}
