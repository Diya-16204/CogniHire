import { useEffect, useState } from 'react'
import { Code2, FileText } from 'lucide-react'
import { api, errorMessage } from './api'

type Submission = { attemptId: string; candidateName: string; candidateEmail: string; startedAt: string; submittedAt: string; roundTwoScore: number | null; roundTwoTotal: number | null; recruiterJoinedDuringTest: boolean; recruiterJoinedAt: string | null; interviewTranscript: { index: number; subject: string; question: string; answer: string; answeredAt: string }[]; recruiterInterventions: { id: string; question: string; answer: string; createdAt: string; answeredAt: string | null }[]; roundTwoAnswers: { subject: string; questionText: string; selectedOption: string; correctOption: string; correct: boolean; answeredAt: string }[]; questions: { questionId: string; title: string; language: string; sourceCode: string; evidence: { dryRunReasoning?: string; predictedOutputs?: string[]; interviewTranscript?: { question: string; answer: string }[]; codeWasExecuted?: boolean } }[] }

export default function SubmittedWorkPanel({ assessmentId }: { assessmentId: string }) {
  const [items, setItems] = useState<Submission[]>([])
  const [error, setError] = useState('')
  const [loaded, setLoaded] = useState(false)
  useEffect(() => {
    let live = true
    api.get(`/assessments/${assessmentId}/submitted-work`).then(({ data }) => { if (live) setItems(data) }).catch(e => { if (live) setError(errorMessage(e)) }).finally(() => { if (live) setLoaded(true) })
    return () => { live = false }
  }, [assessmentId])

  return <section className="panel submitted-work-panel">
    <div className="submitted-work-heading"><span className="quick-icon blue"><FileText size={17}/></span><div><h2>Candidate submissions</h2><p>Submitted code and interview evidence. Sample runs are candidate initiated; submissions are not automatically graded.</p></div></div>
    {error && <div className="alert error">{error}</div>}
    {!loaded ? <p className="muted">Loading submissions…</p> : !items.length ? <p className="muted">No candidates have submitted this assessment yet.</p> : items.map(item => <article className="submitted-candidate" key={item.attemptId}>
      <header><div><strong>{item.candidateName}</strong><span>{item.candidateEmail}</span></div><time>{new Date(item.submittedAt).toLocaleString()}</time></header>
      <div className={`submission-audit-badge ${item.recruiterJoinedDuringTest ? 'intervened' : 'autonomous'}`}>{item.recruiterJoinedDuringTest ? <>🟢 Live Recruiter Intervened{item.recruiterJoinedAt ? ` · Joined at ${new Date(item.recruiterJoinedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}` : ''}</> : '⚪ Fully Autonomous AI Session'}</div>
      {(item.interviewTranscript?.length > 0 || item.recruiterInterventions?.length > 0 || item.roundTwoAnswers?.length > 0 || item.questions?.length > 0) && <details className="submitted-question audit-timeline" open><summary>Assessment audit timeline · {(item.interviewTranscript?.length ?? 0) + (item.recruiterInterventions?.length ?? 0) + (item.roundTwoAnswers?.length ?? 0) + (item.questions?.length ?? 0)} responses</summary>
        {[...(item.roundTwoAnswers ?? []).map((turn, index) => ({ kind: `Subject MCQ · ${turn.subject}`, question: turn.questionText, answer: `Selected ${turn.selectedOption ?? 'no option'}${turn.correct ? ' · Correct' : ''}`, at: turn.answeredAt, index })), ...(item.questions ?? []).map((turn, index) => ({ kind: `Coding submission · ${turn.language}`, question: turn.title, answer: turn.sourceCode, at: item.submittedAt, index })), ...(item.interviewTranscript ?? []).map(turn => ({ kind: 'AI interviewer', question: turn.question, answer: turn.answer, at: turn.answeredAt, index: turn.index })), ...(item.recruiterInterventions ?? []).map(turn => ({ kind: 'Recruiter follow-up', question: turn.question, answer: turn.answer, at: turn.createdAt, index: Number.MAX_SAFE_INTEGER }))].sort((a, b) => new Date(a.at).getTime() - new Date(b.at).getTime() || a.index - b.index).map((turn, i) => <div className={`submitted-turn audit-turn ${turn.kind === 'Recruiter follow-up' ? 'recruiter-followup' : ''} ${turn.kind.startsWith('Coding submission') ? 'audit-code-turn' : ''}`} key={`${turn.kind}-${i}`}><span className="audit-turn-tag">{turn.kind}</span><time>{turn.at ? new Date(turn.at).toLocaleString() : 'Time unavailable'}</time><p><b>Question:</b> {turn.question}</p>{turn.kind.startsWith('Coding submission') ? <pre className="submitted-code">{turn.answer || 'No code recorded'}</pre> : <p><b>Candidate:</b> {turn.answer || 'No response recorded'}</p>}</div>)}
      </details>}
      <details className="submitted-question"><summary>Round 2 · Subject MCQs · {item.roundTwoScore ?? 0}/{item.roundTwoTotal ?? item.roundTwoAnswers.length}</summary>
        {item.roundTwoAnswers.map((answer, i) => <div className="submitted-turn" key={`${item.attemptId}-mcq-${i}`}><h4>{answer.subject}</h4><p>{answer.questionText}</p><p>Candidate: {answer.selectedOption} · Correct answer: {answer.correctOption} · {answer.correct ? 'Correct' : 'Incorrect'}</p></div>)}
      </details>
      {item.questions.map(q => <details key={q.questionId} className="submitted-question"><summary><Code2 size={14}/>{q.title} · {q.language}</summary>
        <h4>Dry-run reasoning</h4><p>{q.evidence?.dryRunReasoning || 'Not provided'}</p>
        {!!q.evidence?.predictedOutputs?.length && <><h4>Predicted outputs</h4><ol>{q.evidence.predictedOutputs.map((output, i) => <li key={i}><pre>{output}</pre></li>)}</ol></>}
        {!!q.evidence?.interviewTranscript?.length && <><h4>AI interviewer conversation</h4>{q.evidence.interviewTranscript.map((turn, i) => <div className="submitted-turn" key={i}><p><b>Interviewer:</b> {turn.question}</p><p><b>Candidate:</b> {turn.answer}</p></div>)}</>}
        <h4>Submitted code · {q.language}</h4><pre className="submitted-code">{q.sourceCode}</pre>
      </details>)}
    </article>)}
  </section>
}
