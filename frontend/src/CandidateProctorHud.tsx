import { useCallback, useEffect, useRef, useState } from 'react'
import type { Client } from '@stomp/stompjs'
import { Camera, CameraOff, Clock3, Eye, ShieldCheck } from 'lucide-react'

export default function CandidateProctorHud({ attemptId, deadlineAt, client, connected }: {
  attemptId: string
  deadlineAt: string
  client: Client | null
  connected: boolean
}) {
  const [remaining, setRemaining] = useState('—:—:—')
  const [focused, setFocused] = useState(true)
  const [tabSwitches, setTabSwitches] = useState(0)
  const [showWarning, setShowWarning] = useState(false)
  const [preview, setPreview] = useState(false)
  const [cameraError, setCameraError] = useState('')
  const video = useRef<HTMLVideoElement | null>(null)
  const stream = useRef<MediaStream | null>(null)

  useEffect(() => {
    const update = () => {
      const seconds = Math.max(0, Math.floor((new Date(deadlineAt).getTime() - Date.now()) / 1000))
      setRemaining([Math.floor(seconds / 3600), Math.floor((seconds % 3600) / 60), seconds % 60].map(n => String(n).padStart(2, '0')).join(':'))
    }
    update()
    const timer = window.setInterval(update, 1000)
    return () => window.clearInterval(timer)
  }, [deadlineAt])

  const endPreview = useCallback(() => {
    stream.current?.getTracks().forEach(track => track.stop())
    stream.current = null
    if (video.current) video.current.srcObject = null
    setPreview(false)
  }, [])

  useEffect(() => {
    const visibility = () => {
      const visible = document.visibilityState === 'visible'
      setFocused(visible)
      if (!visible) { setTabSwitches(count => count + 1); setShowWarning(true) }
      if (client?.connected) client.publish({
        destination: `/app/attempt/${attemptId}/proctor-event`,
        body: JSON.stringify({ type: visible ? 'TAB_VISIBLE' : 'TAB_HIDDEN' }),
      })
    }
    document.addEventListener('visibilitychange', visibility)
    return () => document.removeEventListener('visibilitychange', visibility)
  }, [attemptId, client, connected])

  useEffect(() => {
    if (video.current && stream.current) video.current.srcObject = stream.current
  }, [preview])

  useEffect(() => () => endPreview(), [endPreview])

  async function startPreview() {
    setCameraError('')
    try {
      const cameraStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false })
      stream.current = cameraStream
      setPreview(true)
    } catch (error) {
      setCameraError(error instanceof Error ? error.message : 'Camera permission was unavailable.')
    }
  }

  return <>
    <div className="proctor-hud" role="status">
      <div className="proctor-live"><i/><strong>ASSESSMENT SESSION ACTIVE</strong><span>{connected ? 'Focus changes shared live' : 'Live monitor reconnecting'}</span></div>
      <div className="proctor-hud-status"><span className={preview ? 'status-on' : ''}><Camera size={14}/>{preview ? 'Camera preview on' : 'Camera preview off'}</span><span className={focused ? 'status-on' : 'status-warning'}><Eye size={14}/>{focused ? `Assessment tab focused · ${tabSwitches} switches` : 'Tab switch detected'}</span><span className="proctor-timer"><Clock3 size={14}/>{remaining}</span></div>
      <button className="proctor-preview-toggle" type="button" onClick={() => preview ? endPreview() : void startPreview()}>{preview ? <CameraOff size={14}/> : <Camera size={14}/>} {preview ? 'Stop local preview' : 'Enable local camera preview'}</button>
    </div>
    {preview && <aside className="proctor-camera-preview"><video ref={video} autoPlay muted playsInline/><span className="preview-badge"><i/> LOCAL PREVIEW</span><div className="camera-grid" aria-hidden="true"/><small><ShieldCheck size={12}/> Camera is not recorded or sent to the recruiter by this preview.</small></aside>}
    {cameraError && <div className="proctor-camera-error" role="status">Camera preview unavailable: {cameraError}</div>}
    {showWarning && <div className="proctor-warning-banner" role="alert"><Eye size={16}/> Assessment tab switch detected. {connected ? 'The recruiter was notified.' : 'The live connection is offline; this event may not have reached the recruiter.'}<button type="button" onClick={() => setShowWarning(false)}>Dismiss</button></div>}
  </>
}
