package com.cognihire.api;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

enum RecruitmentAttemptStatus { IN_PROGRESS, COMPLETED, SHORTLISTED, REJECTED }

@Getter @Setter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity @Table(name = "assessment_mcq_questions")
class Question {
  @Id UUID id = UUID.randomUUID();
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "assessment_id", nullable = false) Assessment assessment;
  @Column(nullable = false, length = 120) String subject = "General";
  @Column(name = "question_text", nullable = false, columnDefinition = "text") String questionText;
  @Column(name = "option_a", nullable = false, length = 1000) String optionA;
  @Column(name = "option_b", nullable = false, length = 1000) String optionB;
  @Column(name = "option_c", nullable = false, length = 1000) String optionC;
  @Column(name = "option_d", nullable = false, length = 1000) String optionD;
  @Column(name = "correct_option", nullable = false, length = 1) String correctOption;
  @Column(name = "created_at", nullable = false) Instant createdAt = Instant.now();
}

@Getter @Setter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity @Table(name = "assessment_mcq_attempts", uniqueConstraints = @UniqueConstraint(name = "uq_mcq_attempt_candidate", columnNames = {"assessment_id", "candidate_email"}))
class Attempt {
  @Id UUID id = UUID.randomUUID();
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "assessment_id", nullable = false) Assessment assessment;
  @Column(name = "candidate_name", nullable = false, length = 160) String candidateName;
  @Column(name = "candidate_email", nullable = false, length = 320) String candidateEmail;
  @Column Integer score;
  @Column(precision = 5, scale = 2) BigDecimal percentage;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) RecruitmentAttemptStatus status = RecruitmentAttemptStatus.IN_PROGRESS;
  @Column(name = "started_at", nullable = false) Instant startedAt = Instant.now();
  @Column(name = "submitted_at") Instant submittedAt;
}

@Getter @Setter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity @Table(name = "assessment_mcq_attempt_answers", uniqueConstraints = @UniqueConstraint(name = "uq_mcq_answer_attempt_question", columnNames = {"attempt_id", "question_id"}))
class AttemptAnswer {
  @Id UUID id = UUID.randomUUID();
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "attempt_id", nullable = false) Attempt attempt;
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "question_id", nullable = false) Question question;
  @Column(name = "selected_option", length = 1) String selectedOption;
  @Column(name = "is_correct", nullable = false) boolean isCorrect;
}

interface QuestionRepository extends org.springframework.data.jpa.repository.JpaRepository<Question, UUID> {
  java.util.List<Question> findByAssessmentIdOrderByCreatedAtAscIdAsc(UUID assessmentId);
}

@Getter @Setter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity @Table(name = "round_two_interview_questions", uniqueConstraints = @UniqueConstraint(name = "uq_round_two_interview_question_position", columnNames = {"assessment_id", "position"}))
class RoundTwoInterviewQuestion {
  @Id UUID id = UUID.randomUUID();
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "assessment_id", nullable = false) Assessment assessment;
  @Column(nullable = false, length = 120) String subject;
  @Column(name = "question_text", nullable = false, columnDefinition = "text") String questionText;
  @Column(nullable = false) int position;
  @Column(name = "created_at", nullable = false) Instant createdAt = Instant.now();
}

interface RoundTwoInterviewQuestionRepository extends org.springframework.data.jpa.repository.JpaRepository<RoundTwoInterviewQuestion, UUID> {
  java.util.List<RoundTwoInterviewQuestion> findByAssessmentIdOrderByPositionAscCreatedAtAsc(UUID assessmentId);
  int countByAssessmentId(UUID assessmentId);
}

@Getter @Setter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity @Table(name = "coding_attempt_mcq_answers", uniqueConstraints = @UniqueConstraint(name = "uq_coding_mcq_attempt_question", columnNames = {"attempt_id", "question_id"}))
class CodingAttemptMcqAnswer {
  @Id UUID id = UUID.randomUUID();
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "attempt_id", nullable = false) AssessmentAttempt attempt;
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "question_id", nullable = false) Question question;
  @Column(name = "selected_option", nullable = false, length = 1) String selectedOption;
  @Column(name = "is_correct", nullable = false) boolean isCorrect;
  @Column(name = "answered_at", nullable = false) Instant answeredAt = Instant.now();
}

interface CodingAttemptMcqAnswerRepository extends org.springframework.data.jpa.repository.JpaRepository<CodingAttemptMcqAnswer, UUID> {
  java.util.List<CodingAttemptMcqAnswer> findByAttemptIdOrderByAnsweredAtAscIdAsc(UUID attemptId);
  java.util.Optional<CodingAttemptMcqAnswer> findByAttemptIdAndQuestionId(UUID attemptId, UUID questionId);
  long countByAttemptId(UUID attemptId);
}

@Getter @Setter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity @Table(name = "round_two_interview_turns", uniqueConstraints = @UniqueConstraint(name = "uq_round_two_interview_turn", columnNames = {"attempt_id", "turn_index"}))
class RoundTwoInterviewTurn {
  @Id UUID id = UUID.randomUUID();
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "attempt_id", nullable = false) AssessmentAttempt attempt;
  @Column(name = "turn_index", nullable = false) int turnIndex;
  @Column(nullable = false, length = 120) String subject;
  @Column(nullable = false, columnDefinition = "text") String prompt;
  @Column(nullable = false, columnDefinition = "text") String answer;
  @Column(name = "answered_at", nullable = false) Instant answeredAt = Instant.now();
}

interface RoundTwoInterviewTurnRepository extends org.springframework.data.jpa.repository.JpaRepository<RoundTwoInterviewTurn, UUID> {
  java.util.List<RoundTwoInterviewTurn> findByAttemptIdOrderByTurnIndexAsc(UUID attemptId);
  boolean existsByAttemptIdAndTurnIndex(UUID attemptId, int turnIndex);
}

@Getter @Setter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity @Table(name = "recruiter_interventions")
class RecruiterIntervention {
  @Id UUID id = UUID.randomUUID();
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "attempt_id", nullable = false) AssessmentAttempt attempt;
  @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "recruiter_id", nullable = false) UserAccount recruiter;
  @Column(nullable = false, columnDefinition = "text") String question;
  @Column(nullable = false, columnDefinition = "text") String answer = "";
  @Column(name = "created_at", nullable = false) Instant createdAt = Instant.now();
  @Column(name = "answered_at") Instant answeredAt;
}

interface RecruiterInterventionRepository extends org.springframework.data.jpa.repository.JpaRepository<RecruiterIntervention, UUID> {
  java.util.List<RecruiterIntervention> findByAttemptIdOrderByCreatedAtAsc(UUID attemptId);
  java.util.Optional<RecruiterIntervention> findByIdAndAttemptId(UUID id, UUID attemptId);
}

interface AttemptRepository extends org.springframework.data.jpa.repository.JpaRepository<Attempt, UUID> {
  java.util.Optional<Attempt> findByAssessmentIdAndCandidateEmail(UUID assessmentId, String candidateEmail);
  java.util.List<Attempt> findByAssessmentIdOrderByStartedAtDesc(UUID assessmentId);
  @org.springframework.data.jpa.repository.Lock(LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select a from Attempt a where a.id = :id")
  java.util.Optional<Attempt> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
}

interface AttemptAnswerRepository extends org.springframework.data.jpa.repository.JpaRepository<AttemptAnswer, UUID> {
  java.util.List<AttemptAnswer> findByAttemptId(UUID attemptId);
}
