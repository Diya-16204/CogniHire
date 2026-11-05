import { useCallback, useEffect, useRef, useState } from 'react'
import Editor from '@monaco-editor/react'
import { Client, ReconnectionTimeMode } from '@stomp/stompjs'
import { Activity, Eye, MessageSquarePlus, Radio, RefreshCw, Send, UserRound } from 'lucide-react'
import { api, errorMessage, wsBaseUrl } from './api'
import LiveVideoCall from './LiveVideoCall'

type Session = { attemptId: string; candidateId: string; candidateName: string; candidateEmail: string; assessmentId: string; assessmentTitle: string; status: string; startedAt: string; deadlineAt: string }
type CodeSnapshot = { attemptId: string; questionId: string; questionTitle: string; language: string; code: string; updatedAt: string }
type Detail = { session: Session; editor: CodeSnapshot | null; transcript: Turn[]; takeoverActive: boolean }
type Turn = { index?: number; type?: string; subject?: string; question?: string; answer?: string; text?: string; updatedAt?: string }
type RunResult = { status: string; stdout?: string | null; stderr?: string | null; compileOutput?: string | null; message?: string | null; expectedOutput?: string | null; updatedAt?: string }

export default function LiveCandidateMonitor() {
  const [sessions, setSessions] = useState<Session[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [detail, setDetail] = useState<Detail | null>(null)
  const [code, setCode] = useState('')
  const [runs, setRuns] = useState<RunResult[]>([])
  const [transcript, setTranscript] = useState<Turn[]>([])
  const [prompt, setPrompt] = useState('')
  const [takeover, setTakeover] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [socketReady, setSocketReady] = useState(false)
  const clientRef = useRef<Client | null>(null)

  const refresh = useCallback(async () => {
    try {
      const { data } = await api.get<Session[]>('/recruiter/live-sessions')
      setSessions(data)
      setError('')
      if (selectedId && !data.some(session => session.attemptId === selectedId)) setSelectedId('')
    } catch (e) { setError(errorMessage(e)) }
    finally { setLoading(false) }
  }, [selectedId])

  useEffect(() => {
    void refresh()
    const timer = window.setInterval(() => void refresh(), 5000)
    return () => window.clearInterval(timer)
  }, [refresh])

  useEffect(() => {
    if (!selectedId) { setDetail(null); setCode(''); setTranscript([]); setRuns([]); setSocketReady(false); return }
    let active = true
    api.get<Detail>(`/recruiter/live-sessions/${selectedId}`).then(({ data }) => {
      if (!active) return
      setDetail(data); setCode(data.editor?.code ?? ''); setTranscript(data.transcript ?? []); setRuns([]); setTakeover(data.takeoverActive); setError('')
    }).catch(e => { if (active) setError(errorMessage(e)) })

    const token = sessionStorage.getItem('cognihire-token')
    if (!token) { setError('Your login session is missing. Sign in again.'); return () => { active = false } }
    const client = new Client({ brokerURL: wsBaseUrl, connectHeaders: { Authorization: `Bearer ${token}` }, reconnectDelay: 3000, maxReconnectDelay: 15000, reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL, heartbeatIncoming: 10000, heartbeatOutgoing: 10000, debug: () => {} })
    clientRef.current = client
    client.onConnect = () => {
      if (active) setSocketReady(true)
      if (active) { setError(''); setNotice('') }
      client.subscribe(`/topic/attempt/${selectedId}/editor`, frame => {
        const event = JSON.parse(frame.body) as CodeSnapshot & RunResult & { type: string }
        if (event.type === 'CODE_UPDATE') setCode(event.code ?? '')
        if (event.type === 'EXECUTION_RESULT') setRuns(current => [event, ...current].slice(0, 5))
        if (event.type === 'CODE_UPDATE') setDetail(current => current ? { ...current, editor: event } : current)
      })
      client.subscribe(`/topic/attempt/${selectedId}/transcript`, frame => {
        const event = JSON.parse(frame.body) as Turn
        setTranscript(current => {
          if (event.type === 'ANSWER_DRAFT') {
            const withoutDraft = current.filter(turn => turn.type !== 'ANSWER_DRAFT')
            return [...withoutDraft, event]
          }
          return [...current.filter(turn => turn.type !== 'ANSWER_DRAFT'), event]
        })
      })
    }
    client.onStompError = frame => { if (active) setError(frame.headers.message || 'Live monitoring subscription was rejected.') }
    client.onWebSocketError = () => { if (active) { setSocketReady(false); setNotice('Live connection interrupted. Reconnecting while the service wakes up…') } }
    client.onWebSocketClose = () => { if (active) setSocketReady(false) }
    client.activate()
    return () => { active = false; setSocketReady(false); clientRef.current = null; void client.deactivate() }
  }, [selectedId])

  function intervene(mode: 'PROMPT' | 'TAKEOVER' | 'DIRECT_MESSAGE' | 'RESUME', text = '') {
    if (!selectedId || !clientRef.current?.connected) { setError('Live connection is not ready. Wait a moment and retry.'); return }
    if (mode === 'PROMPT' && !text.trim()) return
    setBusy(true); setError(''); setNotice('')
    clientRef.current.publish({ destination: `/app/attempt/${selectedId}/intervene`, body: JSON.stringify({ mode, text }) })
    if (mode === 'TAKEOVER') setTakeover(true)
    if (mode === 'RESUME') setTakeover(false)
    if (mode === 'PROMPT') { setPrompt(''); setNotice('Question added to the candidate’s interview queue.') }
    else if (mode === 'DIRECT_MESSAGE') { setPrompt(''); setNotice('Message sent to the candidate.') }
    else setNotice(mode === 'TAKEOVER' ? 'Automated interviewing paused. The candidate has been notified.' : 'Automated interviewing resumed.')
    window.setTimeout(() => setBusy(false), 300)
  }

  const selected = detail?.session
  return <div className="live-monitor-page">
    <div className="page-heading"><div><div className="eyebrow">WORKSPACE / LIVE SESSIONS</div><h1>Live sessions</h1><p className="muted">Observe active candidate assessments and join an interview when needed.</p></div><button type="button" className="button secondary" onClick={() => void refresh()}><RefreshCw size={15}/> Refresh</button></div>
    {error && <div className="alert error">{error}</div>}{notice && <div className="alert info">{notice}<button type="button" onClick={() => setNotice('')}>Dismiss</button></div>}
    <div className="live-monitor-layout">
      <aside className="panel live-session-list"><div className="panel-head"><div><h2><Radio size={16}/> Active candidates</h2><p>{sessions.length} in progress · refreshes every 5 seconds</p></div><span className="live-indicator">LIVE</span></div>
        {loading ? <div className="empty-state">Loading sessions…</div> : sessions.length === 0 ? <div className="empty-state"><Activity size={25}/><h3>No active sessions</h3><p>Candidate attempts appear here after they begin a published assessment.</p></div> : sessions.map(session => <button type="button" key={session.attemptId} className={`live-session-item ${session.attemptId === selectedId ? 'selected' : ''}`} onClick={() => setSelectedId(session.attemptId)}><span className="live-avatar"><UserRound size={16}/></span><span className="live-candidate-meta"><strong>{session.candidateName}</strong><small>{session.candidateEmail}</small><small>{session.assessmentTitle}</small></span><i/></button>)}
      </aside>
      <main className="live-monitor-main">
        {!selected ? <section className="panel empty-state"><Eye size={28}/><h2>Select a candidate</h2><p>Choose an active session to view their editor and interview activity.</p></section> : <>
          <section className="panel live-session-summary"><div><span className="eyebrow">CANDIDATE SESSION</span><h2>{selected.candidateName}</h2><p>{selected.candidateEmail} · {selected.assessmentTitle}</p></div><span className="live-status"><i/> In progress</span></section>
          <section className="panel live-editor-panel"><div className="live-panel-heading"><div><h2>Candidate code</h2><p>{detail?.editor?.questionTitle ?? 'Waiting for the candidate to edit code'} · {detail?.editor?.language ?? 'Python'}</p></div><small>{detail?.editor?.updatedAt ? `Updated ${new Date(detail.editor.updatedAt).toLocaleTimeString()}` : 'Live editor stream'}</small></div><Editor height="390px" language={detail?.editor?.language?.toLowerCase() === 'python' ? 'python' : 'plaintext'} value={code} theme="vs-dark" options={{ readOnly: true, minimap: { enabled: false }, fontSize: 18, wordWrap: 'on', scrollBeyondLastLine: false, domReadOnly: true }}/>
            {runs.length > 0 && <div className="live-run-list"><h3>Latest execution results</h3>{runs.map((run, index) => <div className="live-run-result" key={`${run.updatedAt ?? index}-${index}`}><strong>{run.status}</strong>{run.expectedOutput != null && <div><small>Expected output</small><pre>{run.expectedOutput}</pre></div>}<div><small>Actual output</small><pre>{run.stdout || run.stderr || run.compileOutput || run.message || '(no output)'}</pre></div></div>)}</div>}
          </section>
          <div className="live-monitor-columns">
            <section className="panel live-transcript-panel"><div className="live-panel-heading"><div><h2>Security & interview feed</h2><p>Live attempt events, focus changes, and interview transcript</p></div></div><div className="live-transcript-list">{transcript.length === 0 ? <p className="muted">Waiting for candidate activity…</p> : transcript.map((turn, i) => <article className={`live-transcript-event ${turn.type?.startsWith('PROCTOR_') ? 'security-audit-event' : ''}`} key={`${turn.updatedAt ?? i}-${i}`}><small>{turn.type?.replaceAll('_', ' ') ?? 'Interview turn'}{turn.updatedAt ? ` · ${new Date(turn.updatedAt).toLocaleTimeString()}` : ''}</small>{turn.question && <p><strong>{turn.subject || 'Question'}:</strong> {turn.question}</p>}{turn.answer && <p><strong>Candidate:</strong> {turn.answer}</p>}{turn.text && <p>{turn.text}</p>}</article>)}</div></section>
            <section className="panel live-control-panel"><div className="live-panel-heading"><div><h2>Security command center</h2><p>Interventions are visible to the candidate.</p></div><MessageSquarePlus size={18}/></div><LiveVideoCall client={clientRef.current} connected={socketReady} attemptId={selectedId} role="RECRUITER"/><form onSubmit={event => { event.preventDefault(); intervene(takeover ? 'DIRECT_MESSAGE' : 'PROMPT', prompt) }}><label htmlFor="live-prompt">{takeover ? 'Send a direct message to the candidate' : 'Inject a custom interview prompt'}</label><textarea id="live-prompt" value={prompt} maxLength={2000} onChange={event => setPrompt(event.target.value)} placeholder={takeover ? 'Message the candidate…' : 'Ask a project-specific follow-up…'}/><button type="submit" className="button secondary" disabled={busy || !prompt.trim()}><Send size={14}/> {takeover ? 'Send direct message' : 'Inject prompt'}</button></form><div className="live-takeover-control"><strong>{takeover ? 'Live recruiter takeover is active' : 'Automated interviewer is active'}</strong><p>{takeover ? 'The candidate has been notified. You can message them directly or resume the AI.' : 'Take over to pause the automated question loop.'}</p><button type="button" className={`button ${takeover ? 'primary' : 'secondary'}`} disabled={busy} onClick={() => intervene(takeover ? 'RESUME' : 'TAKEOVER')}>{takeover ? 'Resume AI interviewer' : 'Take over session'}</button></div></section>
          </div>
        </>}
      </main>
    </div>
  </div>
}
