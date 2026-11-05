import { useCallback, useEffect, useRef, useState } from 'react'
import type { Client } from '@stomp/stompjs'
import { Camera, CameraOff, ChevronDown, ChevronUp, Clock3, Eye, ShieldCheck } from 'lucide-react'
import { fetchRecruiterFollowUps, type RecruiterFollowUp } from './api'

type PresenceEvent = { recruiterPresent: boolean }
type FollowUp = RecruiterFollowUp & { attemptId?: string; sender?: string }

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
  const [recruiterPresent, setRecruiterPresent] = useState(false)
  const [followUps, setFollowUps] = useState<FollowUp[]>([])
  const [followUpDrafts, setFollowUpDrafts] = useState<Record<string, string>>({})
  const [sendingFollowUps, setSendingFollowUps] = useState<string[]>([])
  const [showFollowUpPanel, setShowFollowUpPanel] = useState(false)
  const [followUpsMinimized, setFollowUpsMinimized] = useState(false)
  const [followUpsLoading, setFollowUpsLoading] = useState(false)
  const [followUpLoadError, setFollowUpLoadError] = useState('')
  const video = useRef<HTMLVideoElement | null>(null)
  const stream = useRef<MediaStream | null>(null)

  async function refreshFollowUps() {
    setFollowUpsLoading(true)
    setFollowUpLoadError('')
    try {
      const data = await fetchRecruiterFollowUps(attemptId)
      setFollowUps(current => {
        const merged = new Map(current.map(item => [item.id, item]))
        data.forEach(item => merged.set(item.id, { ...merged.get(item.id), ...item }))
        return [...merged.values()]
      })
    } catch (error) {
      setFollowUpLoadError(error instanceof Error ? error.message : 'Could not load recruiter follow-ups. Select View to retry.')
    } finally {
      setFollowUpsLoading(false)
    }
  }

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
    if (!connected || !client?.connected) return
    const presenceSub = client.subscribe(`/topic/assessment/${attemptId}/presence`, frame => {
      const event = JSON.parse(frame.body) as PresenceEvent
      setRecruiterPresent(Boolean(event.recruiterPresent))
    })
    const followUpSub = client.subscribe(`/topic/assessment/${attemptId}/interventions`, frame => {
      const event = JSON.parse(frame.body) as FollowUp & { type?: string }
      if (event.question) {
        setFollowUps(current => current.some(item => item.id === event.id) ? current : [...current, event])
        setShowFollowUpPanel(true)
        setFollowUpsMinimized(false)
        void refreshFollowUps()
        return
      }
      if (event.answer !== undefined) {
        setFollowUps(current => current.map(item => item.id === event.id ? { ...item, answer: event.answer, answeredAt: event.answeredAt } : item))
        setSendingFollowUps(current => current.filter(id => id !== event.id))
      }
    })
    return () => { presenceSub.unsubscribe(); followUpSub.unsubscribe() }
  }, [attemptId, client, connected])

  useEffect(() => {
    if (connected) void refreshFollowUps()
  }, [attemptId, connected])

  useEffect(() => {
    const showPanel = () => {
      setShowFollowUpPanel(true)
      setFollowUpsMinimized(false)
      void refreshFollowUps()
      window.setTimeout(() => {
        document.getElementById('candidate-recruiter-followups')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
        document.getElementById('candidate-recruiter-followup-answer')?.focus({ preventScroll: true })
      }, 50)
    }
    window.addEventListener('cognihire:view-follow-up', showPanel)
    return () => window.removeEventListener('cognihire:view-follow-up', showPanel)
  }, [])

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

  function sendFollowUpAnswer(item: FollowUp) {
    const answer = (followUpDrafts[item.id] ?? '').trim()
    if (!answer || !client?.connected || sendingFollowUps.includes(item.id)) return
    setSendingFollowUps(current => [...current, item.id])
    client.publish({ destination: `/app/attempt/${attemptId}/intervention-answer`, body: JSON.stringify({ interventionId: item.id, answer }) })
  }

  return <>
    <div className="proctor-status-bar"><div className="proctor-hud" role="status">
      <div className="proctor-live"><i/><strong>ASSESSMENT SESSION ACTIVE</strong><span>{connected ? 'Focus changes shared live' : 'Live monitor reconnecting'}</span></div>
      <div className="proctor-hud-status"><span className={recruiterPresent ? 'status-warning' : 'status-on'}>{recruiterPresent ? '🔴 RECRUITER LIVE IN SESSION' : '🔒 AI PROCTORED MODE'}</span><span className={preview ? 'status-on' : ''}><Camera size={14}/>{preview ? 'Camera preview on' : 'Camera preview off'}</span><span className={focused ? 'status-on' : 'status-warning'}><Eye size={14}/>{focused ? `Assessment tab focused · ${tabSwitches} switches` : 'Tab switch detected'}</span><span className="proctor-timer timer-pill-badge"><Clock3 size={14}/>{remaining}</span></div>
      <button className="proctor-preview-toggle" type="button" onClick={() => preview ? endPreview() : void startPreview()}>{preview ? <CameraOff size={14}/> : <Camera size={14}/>} {preview ? 'Stop local preview' : 'Enable local camera preview'}</button>
    </div>{showWarning && <div className="proctor-warning-banner tab-warning-toast" role="alert"><Eye size={16}/> Assessment tab switch detected. {connected ? 'The recruiter was notified.' : 'The live connection is offline; this event may not have reached the recruiter.'}<button type="button" onClick={() => setShowWarning(false)}>Dismiss</button></div>}</div>
    {preview && <aside className="proctor-camera-preview"><video ref={video} autoPlay muted playsInline/><span className="preview-badge"><i/> LOCAL PREVIEW</span><div className="camera-grid" aria-hidden="true"/><small><ShieldCheck size={12}/> Camera is not recorded or sent to the recruiter by this preview.</small></aside>}
    {cameraError && <div className="proctor-camera-error" role="status">Camera preview unavailable: {cameraError}</div>}
    {showFollowUpPanel && <section className={`proctor-followups ${followUpsMinimized ? 'is-minimized' : ''}`} id="candidate-recruiter-followups" aria-label="Recruiter follow-up questions">
      <header className="proctor-followups-header"><strong>Recruiter Follow-Ups{followUps.length > 0 ? ` · ${followUps.length}` : ''}</strong><button type="button" onClick={() => setFollowUpsMinimized(value => !value)} aria-expanded={!followUpsMinimized} aria-label={followUpsMinimized ? 'Expand recruiter follow-ups' : 'Minimize recruiter follow-ups'}>{followUpsMinimized ? <ChevronUp size={16}/> : <ChevronDown size={16}/>}{followUpsMinimized ? 'Expand' : 'Minimize'}</button></header>
      {!followUpsMinimized && (followUps.length ? followUps.map(item => <article className="proctor-followup-card" key={item.id}><div className="proctor-followup-meta"><strong>Recruiter Follow-Up</strong><time>{item.createdAt ? new Date(item.createdAt).toLocaleString() : 'Just now'}</time></div><p>{item.question}</p>{item.answeredAt ? <div className="proctor-followup-answer"><b>Your answer</b><p>{item.answer?.trim() || 'No answer text'}</p></div> : <form onSubmit={event => { event.preventDefault(); sendFollowUpAnswer(item) }}><label htmlFor={`candidate-recruiter-followup-answer-${item.id}`}>Your answer</label><textarea id={`candidate-recruiter-followup-answer-${item.id}`} aria-label="Answer recruiter follow-up" maxLength={4000} value={followUpDrafts[item.id] ?? ''} onChange={event => setFollowUpDrafts(current => ({ ...current, [item.id]: event.target.value }))} placeholder="Type your response…"/><button type="submit" disabled={!followUpDrafts[item.id]?.trim() || !connected || sendingFollowUps.includes(item.id)}>{sendingFollowUps.includes(item.id) ? 'Sending…' : 'Send answer'}</button></form>}</article>) : <article className="proctor-followup-card"><strong>Recruiter follow-up</strong><p>{followUpsLoading ? 'Loading the recruiter’s question…' : followUpLoadError || 'No follow-up questions were found. Select View again to refresh.'}</p></article>)}
    </section>}
  </>
}
