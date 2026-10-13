package com.cognihire.api;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

enum Role { RECRUITER, CANDIDATE, ADMIN }

@Entity @Table(name="users")
class UserAccount {
  @Id UUID id = UUID.randomUUID();
  @Column(nullable=false, unique=true, length=320) String email;
  @Column(name="full_name", nullable=false, length=160) String fullName;
  @Column(name="password_hash", nullable=false, length=100) String passwordHash;
  @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) Role role;
  @Column(name="created_at", nullable=false) Instant createdAt = Instant.now();
  protected UserAccount() {}
  UserAccount(String email, String fullName, String passwordHash, Role role) { this.email=email; this.fullName=fullName; this.passwordHash=passwordHash; this.role=role; }
}

@Entity @Table(name="assessments")
class Assessment {
  @Id UUID id = UUID.randomUUID();
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="owner_id", nullable=false) UserAccount owner;
  @Column(nullable=false, length=180) String title;
  @Column(nullable=false, columnDefinition="text") String description;
  @Column(name="duration_minutes", nullable=false) int durationMinutes;
  @Column(nullable=false, length=20) String status="DRAFT";
  @Column(name="ai_enabled", nullable=false) boolean aiEnabled;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="allowed_assistance", nullable=false, columnDefinition="jsonb") List<String> allowedAssistance=List.of();
  @Column(name="max_hints_per_attempt", nullable=false) int maxHintsPerAttempt;
  @Column(name="max_penalty", nullable=false) double maxPenalty=0.30;
  @Column(nullable=false) int version=1;
  @Column(name="created_at", nullable=false) Instant createdAt=Instant.now();
  @Column(name="updated_at", nullable=false) Instant updatedAt=Instant.now();
  protected Assessment() {}
}

@Entity @Table(name="coding_questions")
class CodingQuestion {
  @Id UUID id=UUID.randomUUID();
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="assessment_id", nullable=false) Assessment assessment;
  @Column(nullable=false, length=180) String title;
  @Column(nullable=false, columnDefinition="text") String statement;
  @Column(nullable=false, length=20) String difficulty;
  @Column(nullable=false) int points;
  @Column(nullable=false) int position;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="public_samples", nullable=false, columnDefinition="jsonb") List<Object> publicSamples=List.of();
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="hidden_tests", nullable=false, columnDefinition="jsonb") List<Object> hiddenTests=List.of();
  @Column(name="reference_solution", columnDefinition="text") String referenceSolution;
  @Column(name="created_at", nullable=false) Instant createdAt=Instant.now();
  protected CodingQuestion() {}
}

interface UserAccountRepository extends org.springframework.data.jpa.repository.JpaRepository<UserAccount,UUID> { java.util.Optional<UserAccount> findByEmailIgnoreCase(String email); }
interface AssessmentRepository extends org.springframework.data.jpa.repository.JpaRepository<Assessment,UUID> { List<Assessment> findByOwnerIdOrderByUpdatedAtDesc(UUID ownerId); }
interface CodingQuestionRepository extends org.springframework.data.jpa.repository.JpaRepository<CodingQuestion,UUID> { List<CodingQuestion> findByAssessmentIdOrderByPosition(UUID assessmentId); }

@Entity @Table(name="assessment_assignments", uniqueConstraints=@UniqueConstraint(columnNames={"assessment_id","candidate_id"}))
class AssessmentAssignment {
  @Id UUID id=UUID.randomUUID();
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="assessment_id",nullable=false) Assessment assessment;
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="candidate_id",nullable=false) UserAccount candidate;
  @Column(name="assigned_at",nullable=false) Instant assignedAt=Instant.now();
  protected AssessmentAssignment() {}
}
interface AssessmentAssignmentRepository extends org.springframework.data.jpa.repository.JpaRepository<AssessmentAssignment,UUID> {
  List<AssessmentAssignment> findByCandidateIdOrderByAssignedAtDesc(UUID candidateId);
  java.util.Optional<AssessmentAssignment> findByAssessmentIdAndCandidateId(UUID assessmentId,UUID candidateId);
}

@Entity @Table(name="assessment_attempts")
class AssessmentAttempt {
  @Id UUID id=UUID.randomUUID();
  @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="assignment_id",nullable=false,unique=true) AssessmentAssignment assignment;
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="candidate_id",nullable=false) UserAccount candidate;
  @Column(nullable=false,length=20) String status="IN_PROGRESS";
  @Column(name="started_at",nullable=false) Instant startedAt=Instant.now();
  @Column(name="deadline_at",nullable=false) Instant deadlineAt;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="policy_snapshot",nullable=false,columnDefinition="jsonb") Object policySnapshot;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name="question_snapshot",nullable=false,columnDefinition="jsonb") List<Object> questionSnapshot;
  @Column(name="raw_score") Double rawScore;
  @Column(name="adjusted_score") Double adjustedScore;
  protected AssessmentAttempt() {}
}
interface AssessmentAttemptRepository extends org.springframework.data.jpa.repository.JpaRepository<AssessmentAttempt,UUID> { java.util.Optional<AssessmentAttempt> findByAssignmentId(UUID assignmentId); }

@Entity @Table(name="code_submissions")
class CodeSubmission {
  @Id UUID id=UUID.randomUUID();
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="attempt_id",nullable=false) AssessmentAttempt attempt;
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="question_id",nullable=false) CodingQuestion question;
  @Column(nullable=false,length=40) String language;
  @Column(name="source_code",nullable=false,columnDefinition="text") String sourceCode;
  @Column(nullable=false,length=24) String status="SAVED";
  @Column(name="provider_token",length=200) String providerToken;
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") Object result;
  @Column(name="created_at",nullable=false) Instant createdAt=Instant.now();
  protected CodeSubmission() {}
}
interface CodeSubmissionRepository extends org.springframework.data.jpa.repository.JpaRepository<CodeSubmission,UUID> {
  java.util.Optional<CodeSubmission> findFirstByAttemptIdAndQuestionIdAndStatusOrderByCreatedAtDesc(UUID attemptId,UUID questionId,String status);
}

@Entity @Table(name="ai_interactions")
class AiInteraction {
  @Id UUID id=UUID.randomUUID();
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="attempt_id",nullable=false) AssessmentAttempt attempt;
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="question_id",nullable=false) CodingQuestion question;
  @Column(nullable=false,length=40) String category;
  @Column(name="request_text",nullable=false,columnDefinition="text") String requestText;
  @Column(name="response_text",nullable=false,columnDefinition="text") String responseText;
  @Column(nullable=false) boolean delivered;
  @Column(name="penalty_fraction",nullable=false) double penaltyFraction;
  @Column(name="disclosure_key",nullable=false,length=64) String disclosureKey;
  @Column(name="created_at",nullable=false) Instant createdAt=Instant.now();
  protected AiInteraction() {}
}
interface AiInteractionRepository extends org.springframework.data.jpa.repository.JpaRepository<AiInteraction,UUID> {
  long countByAttemptIdAndDeliveredTrue(UUID attemptId);
  long countByAttemptIdAndQuestionIdAndDeliveredTrue(UUID attemptId,UUID questionId);
  boolean existsByAttemptIdAndQuestionIdAndDeliveredTrueAndCategoryIn(UUID attemptId,UUID questionId,List<String> categories);
  boolean existsByAttemptIdAndQuestionIdAndCreatedAtAfter(UUID attemptId,UUID questionId,Instant after);
  java.util.Optional<AiInteraction> findFirstByAttemptIdAndQuestionIdAndDisclosureKeyAndDeliveredTrue(UUID attemptId,UUID questionId,String disclosureKey);
}
