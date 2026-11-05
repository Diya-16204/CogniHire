import { useEffect, useState } from 'react'
import { Code2, FileText } from 'lucide-react'
import { api, errorMessage } from './api'

type Submission = { attemptId: string; candidateName: string; candidateEmail: string; startedAt: string; submittedAt: string; roundTwoScore: number | null; roundTwoTotal: number | null; roundTwoAnswers: { subject: string; questionText: string; selectedOption: string; correctOption: string; correct: boolean }[]; questions: { questionId: string; title: string; language: string; sourceCode: string; evidence: { dryRunReasoning?: string; predictedOutputs?: string[]; interviewTranscript?: { question: string; answer: string }[]; codeWasExecuted?: boolean } }[] }

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
