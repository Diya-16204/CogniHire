import { useEffect, useRef, useState } from 'react'
import { ArrowRight, Mic, MicOff, Volume2 } from 'lucide-react'

export type AIInterviewTurn = { index: number; subject: string; question: string; answer: string }
export type AIInterviewStatus = 'connecting' | 'reviewing' | 'streaming' | 'question' | 'complete' | 'error'

type Props = {
  status: AIInterviewStatus
  subject: string
  question: string
  message: string
  questionNumber: number
  totalQuestions: number
  delaySeconds: number
  transcript: AIInterviewTurn[]
  draft: string
  onDraftChange: (value: string) => void
  onSendAnswer: () => void
  onRetry: () => void
  onFinalSubmit: () => void
  connected: boolean
  busy?: boolean
}

type SpeechRecognitionResultLike = { 0: { transcript: string }; isFinal: boolean }
type SpeechRecognitionEventLike = { results: ArrayLike<SpeechRecognitionResultLike> }
type SpeechRecognitionLike = {
  lang: string
  continuous: boolean
  interimResults: boolean
  onresult: ((event: SpeechRecognitionEventLike) => void) | null
  onerror: (() => void) | null
  onend: (() => void) | null
  start: () => void
  stop: () => void
}
type SpeechWindow = Window & { SpeechRecognition?: new () => SpeechRecognitionLike; webkitSpeechRecognition?: new () => SpeechRecognitionLike }

export default function AIInterviewPanel(props: Props) {
  const [listening, setListening] = useState(false)
  const [micError, setMicError] = useState('')
  const recognition = useRef<SpeechRecognitionLike | null>(null)
  const answerRef = useRef<HTMLTextAreaElement | null>(null)
  const draftBeforeSpeech = useRef('')
  const finalQuestion = props.status === 'question' && props.questionNumber >= props.totalQuestions

  useEffect(() => {
    if (props.status !== 'question' || !props.question || !('speechSynthesis' in window)) return
    window.speechSynthesis.cancel()
    const utterance = new SpeechSynthesisUtterance(`${props.subject}. ${props.question}`)
    utterance.lang = 'en-IN'
    utterance.rate = 0.96
    window.speechSynthesis.speak(utterance)
    return () => window.speechSynthesis.cancel()
  }, [props.status, props.subject, props.question])

  useEffect(() => { if (props.status === 'question') answerRef.current?.focus() }, [props.status, props.question])

  useEffect(() => () => {
    recognition.current?.stop()
    if ('speechSynthesis' in window) window.speechSynthesis.cancel()
  }, [])

  function toggleMicrophone() {
    setMicError('')
    if (listening) { recognition.current?.stop(); return }
    const speechWindow = window as SpeechWindow
    const Recognition = speechWindow.SpeechRecognition ?? speechWindow.webkitSpeechRecognition
    if (!Recognition) { setMicError('Speech recognition is not available in this browser. You can type your answer instead.'); return }
    try {
      const instance = new Recognition()
      recognition.current = instance
      draftBeforeSpeech.current = props.draft.trim()
      instance.lang = 'en-IN'
      instance.continuous = true
      instance.interimResults = true
      instance.onresult = event => {
        const spoken = Array.from(event.results).map(result => result[0].transcript).join('').trim()
        props.onDraftChange([draftBeforeSpeech.current, spoken].filter(Boolean).join(' '))
      }
      instance.onerror = () => { setListening(false); setMicError('Microphone recognition stopped. Check browser microphone permission or type your answer.') }
      instance.onend = () => setListening(false)
      instance.start()
      setListening(true)
    } catch { setMicError('Could not start microphone recognition. Check browser microphone permission.') }
  }

  return <section className="guided-stage guided-stage-main guided-live-interview ai-interview-panel">
    <div className="live-interview-heading"><div><div className="eyebrow">PART 2 · {props.subject || 'TECHNICAL INTERVIEW'}</div><h1>AI technical interview</h1></div><span>{props.questionNumber} / {props.totalQuestions}</span></div>
    <div className="interview-history-list">{props.transcript.map(turn => <article className="interview-turn" key={turn.index}><strong>{turn.subject} · Interviewer</strong><p>{turn.question}</p><strong>You</strong><p>{turn.answer}</p></article>)}</div>
    {(props.status === 'question' || props.status === 'streaming') && <>
      <div className="interview-prompt live-interview-question"><div className="ai-interview-prompt-heading"><strong>{props.subject}</strong><span><Volume2 size={14}/> Spoken aloud</span></div><p>{props.question}{props.status === 'streaming' && <span className="streaming-caret" aria-label="Question streaming"/>}</p></div>
      <label className="live-answer-label">Your answer<textarea ref={answerRef} value={props.draft} onChange={event => props.onDraftChange(event.target.value)} rows={6} maxLength={3000} disabled={props.status === 'streaming'} placeholder="Speak or type your answer…"/></label>
      {micError && <p className="alert info">{micError}</p>}
      <div className="guided-actions ai-interview-actions">
        <div><button type="button" className={`button ${listening ? 'danger' : 'secondary'}`} onClick={toggleMicrophone} disabled={props.status !== 'question'}>{listening ? <MicOff size={15}/> : <Mic size={15}/>} {listening ? 'Stop speaking' : 'Start speaking'}</button><span className="muted">You can also type or edit your answer.</span></div>
        <button type="button" className="button primary" onClick={props.onSendAnswer} disabled={!props.draft.trim() || !props.connected || props.busy}>{props.busy ? 'Sending…' : finalQuestion ? 'Submit Final Test' : 'Next Question'} <ArrowRight size={14}/></button>
      </div>
    </>}
    {props.status === 'connecting' && <p className="alert info">{props.message || 'Connecting to the interviewer…'}</p>}
    {props.status === 'reviewing' && <div className="interview-reviewing"><span className="reviewing-pulse"/><strong>{props.message || 'AI is evaluating your response…'}</strong><small>Next question in about {props.delaySeconds} seconds</small></div>}
    {props.status === 'complete' && <div className="ai-interview-complete"><div><strong>Interview complete</strong><p>Your answers are saved. Submit the final test when you are ready.</p></div><button type="button" className="button primary" onClick={props.onFinalSubmit} disabled={props.busy}>{props.busy ? 'Submitting…' : 'Submit Final Test'} <ArrowRight size={14}/></button></div>}
    {props.status === 'error' && <div className="alert error">{props.message}<div className="guided-actions"><button type="button" className="button secondary" onClick={props.onRetry}>Reconnect</button></div></div>}
  </section>
}
