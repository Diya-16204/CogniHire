import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import Editor from '@monaco-editor/react'
import { Client, ReconnectionTimeMode } from '@stomp/stompjs'
import { ArrowLeft, ArrowRight, Check, Clock3, Code2, ShieldCheck } from 'lucide-react'
import { api, errorMessage, wsBaseUrl } from './api'
import AIInterviewPanel, { type AIInterviewTurn } from './AIInterviewPanel'
import LiveVideoCall from './LiveVideoCall'
import CandidateProctorHud from './CandidateProctorHud'

type Sample = { input: string; output?: string }
type Question = { id: string; title: string; statement: string; difficulty: string; points: number; publicSamples: Sample[] }
type InterviewTurn = { question: string; answer: string }
type RunResult = { status: string; stdout: string | null; stderr: string | null; compileOutput: string | null; message: string | null; time: number | null; memory: number | null; expectedOutput?: string | null }
type Attempt = { id: string; status: string; deadlineAt: string; policySnapshot: { enabled: boolean }; questions: Question[]; roundTwoStarted: boolean; roundTwoInterviewCompleted?: boolean }
type RoundTwoQuestion = { id: string; subject: string; questionText: string; optionA: string; optionB: string; optionC: string; optionD: string }
type RoundTwoReviewQuestion = RoundTwoQuestion & { selectedOption: string | null }
type RoundTwoProgress = { questionNumber: number; totalQuestions: number; complete: boolean; question: RoundTwoQuestion | null; questions: RoundTwoReviewQuestion[] }
type RoundTwoInterviewUi = { status: 'connecting' | 'reviewing' | 'streaming' | 'question' | 'complete' | 'error'; subject: string; question: string; message: string; questionNumber: number; totalQuestions: number; delaySeconds: number; transcript: AIInterviewTurn[]; draft: string }
type Work = { code: string; language: string; reasoning: string; predictions: string[]; transcript: InterviewTurn[]; prompt: string; answer: string; phase: 'warmup' | 'interview' | 'code'; codeSubmitted: boolean }
const blankWork = (): Work => ({ code: '', language: 'python', reasoning: '', predictions: [], transcript: [], prompt: '', answer: '', phase: 'warmup', codeSubmitted: false })

function Button({ children, onClick, disabled, kind = '' }: { children: ReactNode; onClick?: () => void; disabled?: boolean; kind?: string }) {
  return <button type="button" className={`button ${kind}`} onClick={onClick} disabled={disabled}>{children}</button>
}

export default function CandidateWorkspace() {
  const { assignmentId } = useParams()
  const [attempt, setAttempt] = useState<Attempt | null>(null)
  const [work, setWork] = useState<Record<string, Work>>({})
  const [index, setIndex] = useState(0)
  const [visitedIndex, setVisitedIndex] = useState(0)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [recruiterNotice, setRecruiterNotice] = useState('')
  const [recruiterTakeover, setRecruiterTakeover] = useState(false)
  const [candidateReply, setCandidateReply] = useState('')
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState('Not saved')
  const [runResult, setRunResult] = useState<RunResult | null>(null)
  const [submitted, setSubmitted] = useState(false)
  const [roundTwo, setRoundTwo] = useState<RoundTwoProgress | null>(null)
  const [roundTwoIndex, setRoundTwoIndex] = useState(0)
  const [roundTwoInterview, setRoundTwoInterview] = useState<RoundTwoInterviewUi | null>(null)
  const [roundTwoInterviewOpen, setRoundTwoInterviewOpen] = useState(false)
  const [callSocketReady, setCallSocketReady] = useState(false)
  const stompClient = useRef<Client | null>(null)
  const editorSnapshot = useRef<{ questionId: string; language: string; code: string } | null>(null)
  const draftTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const editorSyncTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const finalSubmitAfterInterview = useRef(false)

  useEffect(() => {
    let live = true
    api.post(`/candidate/assignments/${assignmentId}/start`).then(async ({ data }) => {
      if (!live) return
      setAttempt(data)
      const drafts = await Promise.all(data.questions.map((q: Question) => api.get(`/candidate/attempts/${data.id}/questions/${q.id}/draft`).then(r => [q.id, r.data] as const).catch(() => [q.id, null] as const)))
      if (!live) return
      setWork(Object.fromEntries(drafts.map(([id, draft]) => {
        const evidence = draft?.evidence
        return [id, { ...blankWork(), code: draft?.sourceCode ?? '', language: 'python', reasoning: evidence?.dryRunReasoning ?? '', predictions: evidence?.predictedOutputs ?? [], transcript: evidence?.interviewTranscript ?? [], phase: evidence?.phase ?? 'warmup', prompt: evidence?.interviewPrompt ?? '', codeSubmitted: evidence?.codeSubmitted ?? false }]
      })))
      const lastVisited = Math.max(0, drafts.reduce((last, [, draft], questionIndex) => draft ? Math.max(last, questionIndex) : last, 0))
      setVisitedIndex(lastVisited)
      setIndex(lastVisited)
      if (data.roundTwoStarted) {
        const progress = await api.get<RoundTwoProgress>(`/candidate/attempts/${data.id}/round-two/current`)
        if (live) {
          setRoundTwo(progress.data); setRoundTwoIndex(Math.max(0, Math.min(progress.data.questionNumber - 1, progress.data.totalQuestions - 1)))
          if (data.roundTwoInterviewCompleted) { setRoundTwoInterview({ status: 'complete', subject: '', question: '', message: 'Round 2 interview complete. Your transcript is saved.', questionNumber: 0, totalQuestions: 0, delaySeconds: 0, transcript: data.roundTwoInterviewTranscript ?? [], draft: '' }) }
        }
      }
    }).catch(e => { if (live) setError(errorMessage(e)) })
    return () => { live = false }
  }, [assignmentId])

  useEffect(() => {
    if (!attempt) return
    const token = sessionStorage.getItem('cognihire-token')
    if (!token) { setRoundTwoInterview(prev => prev ? { ...prev, status: 'error', message: 'Your login session is missing. Sign in again.' } : prev); return }
      const client = new Client({ brokerURL: wsBaseUrl, connectHeaders: { Authorization: `Bearer ${token}` }, reconnectDelay: 3000, maxReconnectDelay: 15000, reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL, heartbeatIncoming: 10000, heartbeatOutgoing: 10000, debug: () => {} })
    stompClient.current = client
    client.onConnect = () => {
      setCallSocketReady(true)
      client.subscribe('/user/queue/interview', frame => {
        const event = JSON.parse(frame.body) as { type: string; subject?: string; question?: string; text?: string; questionNumber?: number; totalQuestions?: number; delaySeconds?: number; transcript?: AIInterviewTurn[] }
        if (event.type === 'RECRUITER_JOINED') { setRecruiterTakeover(true); setRecruiterNotice(event.text ?? 'A recruiter has joined this assessment.') }
        if (event.type === 'RECRUITER_MESSAGE') setRecruiterNotice(event.text ?? 'The recruiter sent a message.')
        if (event.type === 'AI_RESUMED') { setRecruiterTakeover(false); setRecruiterNotice('The recruiter has left and the automated interviewer has resumed.') }
        if (event.type === 'TAKEOVER_WAITING') { setRecruiterTakeover(true); setRecruiterNotice(event.text ?? 'The recruiter has taken over this interview.') }
        if (event.type === 'PROMPT_QUEUED') setRecruiterNotice(event.text ?? 'The recruiter added a follow-up question to the interview.')
        if (event.type === 'TAKEOVER_WAITING') setRecruiterNotice(event.text ?? 'Your response was sent to the recruiter. Please wait for the next step.')
        if (event.type === 'COMPLETE') setRecruiterNotice('')
        if (!roundTwoInterviewOpen) return
        if (event.type === 'COMPLETE') setAttempt(currentAttempt => currentAttempt ? { ...currentAttempt, status: 'ROUND_2_COMPLETED', roundTwoInterviewCompleted: true } : currentAttempt)
        if (event.type === 'COMPLETE' && finalSubmitAfterInterview.current) { finalSubmitAfterInterview.current = false; setTimeout(() => { void submit() }, 0) }
        setRoundTwoInterview(prev => {
          const baseState = prev ?? { status: 'connecting' as const, subject: '', question: '', message: '', questionNumber: 0, totalQuestions: 0, delaySeconds: 0, transcript: [], draft: '' }
          if (event.type === 'COMPLETE') {
            return { ...baseState, status: 'complete', message: event.text ?? 'Round 2 interview complete.', transcript: event.transcript ?? baseState.transcript, draft: '' }
          }
          if (event.type === 'ERROR') return { ...baseState, status: 'error', message: event.text ?? 'The interview service could not continue.' }
          if (event.type === 'REVIEWING' || event.type === 'ACK') return { ...baseState, status: 'reviewing', subject: event.subject ?? baseState.subject, message: event.text ?? 'AI is reviewing your answer…', delaySeconds: event.delaySeconds ?? 0, transcript: event.transcript ?? baseState.transcript, draft: event.type === 'ACK' ? '' : baseState.draft }
          if (event.type === 'QUESTION_START') {
            return { ...baseState, status: 'streaming', subject: event.subject ?? '', question: '', message: 'The interviewer is asking…', questionNumber: event.questionNumber ?? 0, totalQuestions: event.totalQuestions ?? 0, delaySeconds: 0, transcript: event.transcript ?? baseState.transcript, draft: '' }
          }
          if (event.type === 'QUESTION_CHUNK') return { ...baseState, status: 'streaming', question: baseState.question + (event.question ?? ''), message: 'The interviewer is asking…' }
          if (event.type === 'QUESTION') {
            return { ...baseState, status: 'question', subject: event.subject ?? '', question: event.question ?? '', questionNumber: event.questionNumber ?? 0, totalQuestions: event.totalQuestions ?? 0, message: '', delaySeconds: 0, transcript: event.transcript ?? baseState.transcript, draft: '' }
          }
          return baseState
        })
      })
      const latestEditor = editorSnapshot.current
      if (latestEditor) client.publish({ destination: `/app/attempt/${attempt.id}/editor-sync`, body: JSON.stringify(latestEditor) })
      if (roundTwoInterviewOpen) client.publish({ destination: '/app/interview.start', body: JSON.stringify({ attemptId: attempt.id }) })
    }
    client.onStompError = frame => setRoundTwoInterview(prev => prev ? { ...prev, status: 'error', message: frame.headers.message || 'Interview connection was rejected.' } : prev)
    client.onWebSocketError = () => { setCallSocketReady(false); setRoundTwoInterview(prev => prev && prev.status !== 'complete' ? { ...prev, status: 'connecting', message: 'Connection interrupted. Reconnecting while the service wakes up…' } : prev) }
    client.onWebSocketClose = () => { setCallSocketReady(false); setRoundTwoInterview(prev => prev && prev.status !== 'complete' ? { ...prev, status: 'connecting', message: 'Connection interrupted. Reconnecting…' } : prev) }
    client.activate()
    return () => { setCallSocketReady(false); if (draftTimer.current) clearTimeout(draftTimer.current); if (editorSyncTimer.current) clearTimeout(editorSyncTimer.current); stompClient.current = null; void client.deactivate() }
  }, [roundTwoInterviewOpen, attempt?.id])

  function openRoundTwoInterview() {
    setRoundTwoInterview({ status: 'connecting', subject: '', question: '', message: 'Connecting to the interviewer…', questionNumber: 0, totalQuestions: 0, delaySeconds: 0, transcript: [], draft: '' })
    setRoundTwoInterviewOpen(true)
  }
  function updateRoundTwoInterviewDraft(value: string) {
    setRoundTwoInterview(prev => prev ? { ...prev, draft: value } : prev)
    if (draftTimer.current) clearTimeout(draftTimer.current)
    draftTimer.current = setTimeout(() => {
      if (attempt && stompClient.current?.connected) stompClient.current.publish({ destination: '/app/interview.draft', body: JSON.stringify({ attemptId: attempt.id, text: value }) })
    }, 200)
  }
  function sendRoundTwoInterviewAnswer() {
    if (!attempt || !roundTwoInterview?.draft.trim() || !stompClient.current?.connected) return
    if (roundTwoInterview.questionNumber >= roundTwoInterview.totalQuestions) finalSubmitAfterInterview.current = true
    stompClient.current.publish({ destination: '/app/interview.answer', body: JSON.stringify({ attemptId: attempt.id, text: roundTwoInterview.draft }) })
    setRoundTwoInterview(prev => prev ? { ...prev, status: 'reviewing', message: 'AI is evaluating your response…', draft: '' } : prev)
  }
  function sendRecruiterMessage() {
    const text = candidateReply.trim()
    if (!attempt || !text || !stompClient.current?.connected) return
    stompClient.current.publish({ destination: `/app/attempt/${attempt.id}/candidate-message`, body: JSON.stringify({ text }) })
    setCandidateReply(''); setRecruiterNotice('Your message was sent to the recruiter.')
  }
  function viewRecruiterFollowUp() {
    window.dispatchEvent(new Event('cognihire:view-follow-up'))
  }

  const question = attempt?.questions[index]
  const current = question ? (work[question.id] ?? blankWork()) : blankWork()
  if (question) editorSnapshot.current = { questionId: question.id, language: 'python', code: current.code }
  const deadline = useMemo(() => attempt ? new Date(attempt.deadlineAt).toLocaleString() : '', [attempt])
  function update(patch: Partial<Work>) {
    if (!question) return
    setWork(prev => ({ ...prev, [question.id]: { ...(prev[question.id] ?? blankWork()), ...patch } }))
  }

  async function requestInterviewQuestion() {
    if (!attempt || !question) return
    const prior = current.transcript
    const last = prior[prior.length - 1]
    const { data } = await api.post(`/candidate/attempts/${attempt.id}/questions/${question.id}/assistance/interview`, {
      transcript: last ? prior.slice(0, -1) : [], answer: last?.answer ?? '',
    })
    update({ prompt: data.message })
  }

  async function submitCodeForInterview() {
    if (!attempt || !question) return
    if (!current.code.trim()) { setError('Write your Python code before submitting it.'); return }
    setBusy(true); setError(''); setNotice('')
    try {
      await api.post(`/candidate/attempts/${attempt.id}/questions/${question.id}/code-submit`, { sourceCode: current.code, language: 'python', dryRunReasoning: current.reasoning, predictedOutputs: current.predictions, interviewTranscript: current.transcript, phase: 'interview', interviewPrompt: '' })
      update({ codeSubmitted: true, phase: 'interview', prompt: '', answer: '' })
      try { await requestInterviewQuestion() }
      catch (e) { setNotice(`AI interview request failed: ${errorMessage(e)} Your code is saved; retry the interviewer or continue when ready.`) }
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }

  async function sendInterviewAnswer() {
    if (!attempt || !question || !current.answer.trim() || !current.prompt) return
    const priorTranscript = current.transcript
    const transcript = [...priorTranscript, { question: current.prompt, answer: current.answer.trim() }]
    setBusy(true); setError(''); setNotice('')
    try {
      const { data } = await api.post(`/candidate/attempts/${attempt.id}/questions/${question.id}/assistance/interview`, { transcript: priorTranscript, answer: current.answer.trim() })
      update({ transcript, prompt: data.message, answer: '' })
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }

  async function saveDraft(phase = current.phase) {
    if (!attempt || !question) return
    setBusy(true); setError('')
    try {
      await api.post(`/candidate/attempts/${attempt.id}/questions/${question.id}/draft`, { sourceCode: current.code, language: current.language, dryRunReasoning: current.reasoning, predictedOutputs: current.predictions, interviewTranscript: current.transcript, phase, interviewPrompt: current.prompt, codeSubmitted: current.codeSubmitted })
      setSaved('Saved just now')
    } catch (e) { setError(errorMessage(e)); setSaved('Could not save') }
    finally { setBusy(false) }
  }

  async function revisitPhase(phase: Work['phase']) {
    if (!question || busy) return
    const phaseOrder: Work['phase'][] = ['warmup', 'code', 'interview']
    const requested = phaseOrder.indexOf(phase)
    const reached = current.codeSubmitted ? phaseOrder.length - 1 : phaseOrder.indexOf(current.phase)
    if (requested > reached || phase === current.phase) return
    update({ phase })
    await saveDraft(phase)
    setError(''); setNotice('')
  }

  async function runSample() {
    if (!attempt || !question) return
    setBusy(true); setError(''); setNotice(''); setRunResult(null)
    try {
      const { data } = await api.post(`/candidate/attempts/${attempt.id}/questions/${question.id}/run`, { sourceCode: current.code, language: current.language || 'python' })
      setRunResult(data)
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }

  async function submit() {
    if (!attempt) return
    const missing = attempt.questions.find(q => {
      const w = work[q.id] ?? blankWork()
      return !w.codeSubmitted || w.phase !== 'interview' || !w.code.trim() || !w.reasoning.trim() || w.predictions.length !== q.publicSamples.length || w.predictions.some(x => !x.trim())
    })
    if (missing) { setError(`Complete the dry-run, submit code, and finish the interview step for “${missing.title}” first.`); return }
    setBusy(true); setError(''); setNotice('')
    try {
      await api.post(`/candidate/attempts/${attempt.id}/submit`, { questions: attempt.questions.map(q => { const w = work[q.id]; return { questionId: q.id, sourceCode: w.code, language: w.language, dryRunReasoning: w.reasoning, predictedOutputs: w.predictions, interviewTranscript: w.transcript } }) })
      setSubmitted(true)
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }

  async function nextStep() {
    if (!attempt) return
    if (!question || !current.codeSubmitted) { setError('Submit the code before finishing this interview step.'); return }
    setBusy(true); setError(''); setNotice('')
    try {
      await api.post(`/candidate/attempts/${attempt.id}/questions/${question.id}/interview/complete`, { transcript: current.transcript })
      if (index < attempt.questions.length - 1) { const next = index + 1; setIndex(next); setVisitedIndex(v => Math.max(v, next)); setRunResult(null); return }
      const { data } = await api.post<RoundTwoProgress>(`/candidate/attempts/${attempt.id}/round-two/start`)
      setRoundTwo(data); setRoundTwoIndex(Math.max(0, Math.min(data.questionNumber - 1, data.totalQuestions - 1)))
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }

  async function answerRoundTwo(selectedOption: string) {
    const activeQuestion = roundTwo?.questions[roundTwoIndex]
    if (!attempt || !activeQuestion || busy) return
    setBusy(true); setError('')
    try {
      const { data } = await api.post<RoundTwoProgress>(`/candidate/attempts/${attempt.id}/round-two/answer`, { questionId: activeQuestion.id, selectedOption })
      setRoundTwo(data)
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }

  async function retryInterview() {
    if (!attempt) return
    setBusy(true); setNotice('')
    try { await requestInterviewQuestion() }
    catch (e) { setNotice(`AI interview request failed: ${errorMessage(e)} You can continue when ready.`) }
    finally { setBusy(false) }
  }

  if (error && !attempt) return <div className="candidate-content"><div className="alert error">{error}</div><Link to="/candidate">Back to assessments</Link></div>
  if (!attempt) return <div className="candidate-content">Loading your assessment…</div>
  if (submitted) return <div className="candidate-content"><div className="panel empty-state"><span className="empty-icon"><Check/></span><h1>Submission received</h1><p>Your code, dry-run notes, and interview responses have been sent to the recruiter. Final submission is not automatically graded.</p><Link className="button secondary" to="/candidate">Back to assessments</Link></div></div>
  if (!['IN_PROGRESS', 'ROUND_2_COMPLETED'].includes(attempt.status)) return <div className="candidate-content"><div className="alert error">This assessment has already been submitted or has expired.</div><Link to="/candidate">Back to assessments</Link></div>
  if (roundTwo) {
    const activeRoundTwoQuestion = roundTwo.questions[roundTwoIndex]
    const liveInterview = roundTwoInterview && roundTwoInterview.status !== 'complete'
    return <div className="coding-workspace guided-workspace">
    <CandidateProctorHud attemptId={attempt.id} deadlineAt={attempt.deadlineAt} client={stompClient.current} connected={callSocketReady}/>
    <header className="coding-header"><Link to="/candidate" className="back-link">← Assessments</Link><strong>Round 2 · {roundTwoInterview?.status === 'complete' ? 'Interview review' : liveInterview ? 'AI interview' : activeRoundTwoQuestion?.subject ?? 'Review'}</strong><span className="timer"><Clock3 size={14}/>Deadline {deadline}</span></header>
    <LiveVideoCall client={stompClient.current} connected={callSocketReady} attemptId={attempt.id} role="CANDIDATE"/><main className="guided-flow">{(recruiterNotice || recruiterTakeover) && <><div className="alert info live-recruiter-notice">{recruiterNotice || 'A recruiter is connected to this session.'}{recruiterNotice.toLowerCase().includes('follow-up') && <button type="button" onClick={viewRecruiterFollowUp}>View</button>}<button type="button" onClick={() => setRecruiterNotice('')}>Dismiss</button></div>{recruiterTakeover && <form className="candidate-live-reply" onSubmit={event => { event.preventDefault(); sendRecruiterMessage() }}><textarea aria-label="Message the recruiter" value={candidateReply} onChange={event => setCandidateReply(event.target.value)} maxLength={2000} placeholder="Message the recruiter directly…"/><Button onClick={sendRecruiterMessage} disabled={!candidateReply.trim() || !stompClient.current?.connected}>Send to recruiter <ArrowRight size={14}/></Button></form>}</>}<div className="guided-progress"><span>{liveInterview ? `AI interview · Question ${roundTwoInterview?.questionNumber || 1} of ${roundTwoInterview?.totalQuestions || roundTwo.totalQuestions}` : `Round 2 · Question ${roundTwoIndex + 1} of ${roundTwo.totalQuestions}`}</span><strong>{roundTwo.complete ? 'AI technical interview' : 'Subject MCQs'}</strong><span>{roundTwo.complete ? 'Answers save as you go' : 'Revisit any question before final submission'}</span></div>
      {!liveInterview && <nav className="candidate-step-nav" aria-label="Round 2 question navigation">{roundTwo.questions.map((q, i) => <button type="button" key={q.id} className={`candidate-step ${i === roundTwoIndex ? 'active' : ''} ${q.selectedOption ? 'done' : ''}`} onClick={() => { setRoundTwoIndex(i); setError('') }}>Q{i + 1}{q.selectedOption ? ' · answered' : ''}</button>)}</nav>}
      {error && <div className="alert error">{error}</div>}
      {roundTwo.complete && !roundTwoInterview && <section className="guided-stage guided-stage-main"><div className="eyebrow">PART 1 COMPLETE</div><h1>Subject questions complete</h1><p>The interviewer will ask recruiter-set questions for this assessment. If none were added, role-based technical questions will be used.</p><Button onClick={openRoundTwoInterview} disabled={busy}>Start AI interview <ArrowRight size={14}/></Button></section>}
      {roundTwo.complete && roundTwoInterview && <AIInterviewPanel status={roundTwoInterview.status} subject={roundTwoInterview.subject} question={roundTwoInterview.question} message={roundTwoInterview.message} questionNumber={roundTwoInterview.questionNumber} totalQuestions={roundTwoInterview.totalQuestions} delaySeconds={roundTwoInterview.delaySeconds} transcript={roundTwoInterview.transcript} draft={roundTwoInterview.draft} onDraftChange={updateRoundTwoInterviewDraft} onSendAnswer={sendRoundTwoInterviewAnswer} onRetry={() => { setRoundTwoInterviewOpen(false); setRoundTwoInterview(prev => prev ? { ...prev, status: 'connecting', message: 'Reconnecting…' } : prev); setTimeout(() => setRoundTwoInterviewOpen(true), 50) }} onFinalSubmit={submit} connected={Boolean(stompClient.current?.connected)} busy={busy}/>}
      {!roundTwo.complete && activeRoundTwoQuestion ? <section className="guided-stage guided-stage-main round-two-stage"><div className="eyebrow">{activeRoundTwoQuestion.subject}</div><h1>{activeRoundTwoQuestion.questionText}</h1><div className="round-two-options">{(['A','B','C','D'] as const).map(option => <button className={`round-two-option ${activeRoundTwoQuestion.selectedOption === option ? 'selected' : ''}`} key={option} disabled={busy} onClick={() => answerRoundTwo(option)}><strong>{option}</strong><span>{activeRoundTwoQuestion[`option${option}` as 'optionA'|'optionB'|'optionC'|'optionD']}</span></button>)}</div><p className="muted">Select or change an answer. You can revisit any Round 2 question before final submission.</p><div className="guided-actions">{roundTwoIndex > 0 && <Button kind="secondary" onClick={() => setRoundTwoIndex(i => i - 1)}><ArrowLeft size={14}/> Previous</Button>}{roundTwoIndex + 1 < roundTwo.questions.length && <Button kind="secondary" onClick={() => setRoundTwoIndex(i => i + 1)}>Next question <ArrowRight size={14}/></Button>}</div></section> : null}
    </main><div className="guided-policy"><ShieldCheck size={15}/> You can revisit and change answers until you submit. The assessment timer continues.</div>
  </div>
  }
  if (!question) return <div className="candidate-content"><div className="alert error">This assessment has no questions.</div></div>

  const stepName = current.phase === 'warmup' ? 'Dry-run' : current.phase === 'code' ? 'Coding' : 'AI interview'
  return <div className="coding-workspace guided-workspace">
    <CandidateProctorHud attemptId={attempt.id} deadlineAt={attempt.deadlineAt} client={stompClient.current} connected={callSocketReady}/>
    <header className="coding-header"><Link to="/candidate" className="back-link">← Assessments</Link><strong>{question.title}</strong><span className="timer"><Clock3 size={14}/>Deadline {deadline}</span></header>
    <LiveVideoCall client={stompClient.current} connected={callSocketReady} attemptId={attempt.id} role="CANDIDATE"/><main className="guided-flow">
      <div className="guided-progress"><span>Question {index + 1} of {attempt.questions.length}</span><strong>{stepName}</strong><span>{question.difficulty.toLowerCase()} · {question.points} points</span></div>
      <nav className="candidate-step-nav" aria-label="Assessment question navigation">{attempt.questions.slice(0, visitedIndex + 1).map((q, i) => <button type="button" key={q.id} className={`candidate-step ${i === index ? 'active' : ''} ${work[q.id]?.codeSubmitted ? 'done' : ''}`} onClick={() => { setIndex(i); setRunResult(null); setError('') }}>Q{i + 1} · {q.title}</button>)}</nav>
      <nav className="candidate-step-nav phase-nav" aria-label="Question phase navigation">{([['warmup','Dry-run'],['code','Coding'],['interview','AI interview']] as const).map(([phase,label]) => { const order = ['warmup','code','interview']; const reached = current.codeSubmitted ? 2 : order.indexOf(current.phase); return <button type="button" key={phase} className={`candidate-step ${current.phase === phase ? 'active' : ''}`} disabled={busy || order.indexOf(phase) > reached} onClick={() => void revisitPhase(phase)}>{label}</button> })}</nav>
      {error && <div className="alert error">{error}</div>}{(recruiterNotice || recruiterTakeover) && <><div className="alert info live-recruiter-notice">{recruiterNotice || 'A recruiter is connected to this session.'}{recruiterNotice.toLowerCase().includes('follow-up') && <button type="button" onClick={viewRecruiterFollowUp}>View</button>}<button type="button" onClick={() => setRecruiterNotice('')}>Dismiss</button></div>{recruiterTakeover && <form className="candidate-live-reply" onSubmit={event => { event.preventDefault(); sendRecruiterMessage() }}><textarea aria-label="Message the recruiter" value={candidateReply} onChange={event => setCandidateReply(event.target.value)} maxLength={2000} placeholder="Message the recruiter directly…"/><Button onClick={sendRecruiterMessage} disabled={!candidateReply.trim() || !stompClient.current?.connected}>Send to recruiter <ArrowRight size={14}/></Button></form>}</>}{notice && <div className="alert info">{notice}<button type="button" onClick={() => setNotice('')}>Dismiss</button></div>}
      {current.phase === 'warmup' && <section className="guided-stage guided-stage-main"><h1>Dry-run warm-up</h1><p className="problem-text">{question.statement}</p><p>Trace the public examples first. Their expected outputs are hidden during this step.</p>
        {question.publicSamples.map((sample, i) => <div className="sample-case" key={i}><strong>Example {i + 1}</strong><span>Input</span><code>{sample.input}</code><label>Predicted output<textarea value={current.predictions[i] ?? ''} onChange={e => update({ predictions: question.publicSamples.map((_, n) => n === i ? e.target.value : (current.predictions[n] ?? '')), codeSubmitted: false })} rows={2} maxLength={2000} placeholder="Write the output you expect"/></label></div>)}
        <label>Your reasoning<textarea value={current.reasoning} onChange={e => update({ reasoning: e.target.value, codeSubmitted: false })} rows={4} maxLength={3000} placeholder="Explain how you traced the example and which edge case you considered."/></label>
        <div className="guided-actions">{index > 0 && <Button kind="secondary" onClick={() => setIndex(i => i - 1)}><ArrowLeft size={14}/> Previous question</Button>}<Button kind="secondary" onClick={saveDraft} disabled={busy}>{busy ? 'Saving…' : 'Save progress'} <Check size={14}/></Button><Button onClick={() => { update({ phase: 'code' }); setError(''); void saveDraft('code') }} disabled={busy || !current.reasoning.trim() || current.predictions.length !== question.publicSamples.length || current.predictions.some(x => !x?.trim())}>Next: Coding <ArrowRight size={14}/></Button></div>
      </section>}
      {current.phase === 'code' && <section className="guided-stage guided-stage-main guided-code-stage"><div className="guided-section-heading"><div><h1>Write and test your Python code</h1><p>{question.title}</p></div><span><Code2 size={15}/> Python 3</span></div><div className="guided-problem-summary">{question.statement}</div>
        <Editor height={runResult ? '48vh' : '58vh'} language="python" value={current.code} onChange={v => { const code = v ?? ''; update({ code, codeSubmitted: false }); setSaved('Not saved'); if (editorSyncTimer.current) clearTimeout(editorSyncTimer.current); editorSyncTimer.current = setTimeout(() => { if (attempt && question && stompClient.current?.connected) stompClient.current.publish({ destination: `/app/attempt/${attempt.id}/editor-sync`, body: JSON.stringify({ questionId: question.id, language: 'python', code }) }) }, 180) }} theme="vs-dark" options={{ minimap: { enabled: false }, fontSize: 18, wordWrap: 'on', padding: { top: 14 }, scrollBeyondLastLine: false }}/>
        {runResult && (() => {
          const expected = runResult.expectedOutput ?? ''
          const actual = runResult.stdout ?? ''
          const executionOk = runResult.status.toLowerCase() === 'accepted' && !runResult.stderr && !runResult.compileOutput
          const normalize = (value: string) => value.trim().replace(/\s+/g, ' ')
          const matches = executionOk && runResult.expectedOutput != null && normalize(actual) === normalize(expected)
          return <div className={`run-result ${matches ? 'run-result-pass' : executionOk ? 'run-result-fail' : 'run-result-error'}`}>
            <div className="run-result-heading"><strong>{executionOk ? (matches ? 'Sample result · Correct' : 'Sample result · Incorrect') : `Execution · ${runResult.status}`}</strong>{runResult.time !== null && <span>{runResult.time}s</span>}</div>
            {executionOk && <div className="run-result-comparison"><div><small>Expected output</small><pre>{expected || '(empty output)'}</pre></div><div><small>Your output</small><pre>{actual || '(empty output)'}</pre></div></div>}
            {!executionOk && <p className="run-result-message">{runResult.message || 'The program did not finish successfully.'}</p>}
            {runResult.stderr && <><small>Error output</small><pre>{runResult.stderr}</pre></>}{runResult.compileOutput && <><small>Compile output</small><pre>{runResult.compileOutput}</pre></>}
            {executionOk && runResult.expectedOutput == null && <p className="run-result-message">No expected output is configured for this public sample, so it cannot be marked correct or incorrect.</p>}
          </div>
        })()}
        <div className="guided-actions"><div><Button kind="secondary" onClick={() => void revisitPhase('warmup')} disabled={busy}><ArrowLeft size={14}/> Back to dry-run</Button><span className="muted">Run sample uses the first public input.</span></div><div><Button kind="secondary" onClick={saveDraft} disabled={busy}>{busy ? 'Working…' : 'Save draft'} <Check size={14}/></Button><Button kind="secondary" onClick={runSample} disabled={busy || !current.code.trim()}>{busy ? 'Running…' : 'Run sample'} <Code2 size={14}/></Button><Button onClick={submitCodeForInterview} disabled={busy || !current.code.trim()}>{busy ? 'Submitting code…' : 'Submit code'} <ArrowRight size={14}/></Button></div></div>
        <span className="editor-save-state">{saved}</span>
      </section>}
      {current.phase === 'interview' && <section className="guided-stage guided-stage-main guided-interview-stage"><h1>AI interviewer</h1><p>Answer the interviewer’s reasoning questions. It will not provide the solution.</p>
        {current.prompt ? <><div className="interview-prompt">{current.prompt}</div><textarea value={current.answer} onChange={e => update({ answer: e.target.value })} rows={4} maxLength={1500} placeholder="Type your answer…"/><div className="guided-actions"><Button kind="secondary" onClick={saveDraft} disabled={busy}>{busy ? 'Saving…' : 'Save progress'} <Check size={14}/></Button><Button onClick={sendInterviewAnswer} disabled={busy || !current.answer.trim() || current.transcript.length >= 8}>{busy ? 'Thinking…' : 'Send answer'} <ArrowRight size={14}/></Button></div></> : <div><p className="muted">The AI interviewer has not returned a question. Check the error notice above, or retry.</p><Button kind="secondary" onClick={retryInterview} disabled={busy}>{busy ? 'Connecting…' : 'Retry interviewer'}</Button></div>}
        {current.transcript.map((turn, i) => <details className="interview-history" key={i}><summary>Previous exchange {i + 1}</summary><p><b>Interviewer:</b> {turn.question}</p><p><b>You:</b> {turn.answer}</p></details>)}
        <div className="guided-actions interview-finish"><div>{current.codeSubmitted && <Button kind="secondary" onClick={() => void revisitPhase('code')} disabled={busy}><ArrowLeft size={14}/> Back to code</Button>}{index > 0 && <Button kind="secondary" onClick={() => setIndex(i => i - 1)} disabled={busy}><ArrowLeft size={14}/> Previous question</Button>}<span className="muted">Your code has been submitted for this question.</span></div><Button onClick={nextStep} disabled={busy || !current.codeSubmitted}>{busy ? 'Continuing…' : index < attempt.questions.length - 1 ? 'Next question' : 'Continue to Round 2'} <ArrowRight size={14}/></Button></div>
      </section>}
    </main>
    <div className="guided-policy"><ShieldCheck size={15}/> Candidate code is only run when you click Run sample. Final submission is sent to the recruiter and is not automatically graded.</div>
  </div>
}
