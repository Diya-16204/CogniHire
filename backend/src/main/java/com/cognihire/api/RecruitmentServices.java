package com.cognihire.api;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
class RecruiterAuthService {
  private final UserAccountRepository users;
  private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
  private final JwtService jwt;

  RecruiterAuthService(UserAccountRepository users, org.springframework.security.crypto.password.PasswordEncoder passwordEncoder, JwtService jwt) {
    this.users = users; this.passwordEncoder = passwordEncoder; this.jwt = jwt;
  }

  @Transactional(readOnly = true)
  RecruiterLoginResponse login(RecruiterLoginRequest request) {
    var recruiter = users.findByEmailIgnoreCase(request.email().trim())
        .filter(user -> user.role == Role.RECRUITER)
        .filter(user -> passwordEncoder.matches(request.password(), user.passwordHash))
        .orElseThrow(() -> new BadCredentialsException("Email or password is incorrect"));
    return new RecruiterLoginResponse(jwt.issue(recruiter), "Bearer",
        new RecruiterSummary(recruiter.id.toString(), recruiter.email, recruiter.fullName, recruiter.role.name()));
  }
}

@Service
class RecruitmentAssessmentService {
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final SecureRandom RANDOM = new SecureRandom();
  private final AssessmentRepository assessments;
  private final QuestionRepository questions;
  private final UserAccountRepository users;

  RecruitmentAssessmentService(AssessmentRepository assessments, QuestionRepository questions, UserAccountRepository users) {
    this.assessments = assessments; this.questions = questions; this.users = users;
  }

  @Transactional
  AssessmentDetailsResponse create(CreateRecruitmentAssessmentRequest request, Authentication authentication) {
    var recruiter = recruiter(authentication);
    var assessment = new Assessment();
    assessment.owner = users.findById(recruiter).orElseThrow(() -> new ResourceNotFoundException("Recruiter not found"));
    assessment.title = request.title().trim();
    assessment.role = request.role().trim();
    assessment.description = request.description() == null || request.description().isBlank()
        ? "Assessment for " + assessment.role : request.description().trim();
    assessment.durationMinutes = request.durationMinutes();
    assessment.passingScore = request.passingScore().setScale(2, RoundingMode.HALF_UP);
    assessment.status = "DRAFT";
    return details(assessments.save(assessment));
  }

  @Transactional
  RecruiterQuestionResponse addQuestion(UUID assessmentId, CreateMcqQuestionRequest request, Authentication authentication) {
    var assessment = ownedAssessment(assessmentId, authentication);
    if (!"DRAFT".equals(assessment.status)) throw new IllegalStateException("Questions can only be added to a draft assessment");
    var question = new Question();
    question.assessment = assessment;
    question.questionText = request.questionText().trim();
    question.optionA = request.optionA().trim();
    question.optionB = request.optionB().trim();
    question.optionC = request.optionC().trim();
    question.optionD = request.optionD().trim();
    question.correctOption = request.correctOption();
    return recruiterQuestion(questions.save(question));
  }

  @Transactional
  PublishedAssessmentResponse publish(UUID assessmentId, Authentication authentication) {
    var assessment = ownedAssessment(assessmentId, authentication);
    if (!"DRAFT".equals(assessment.status)) throw new IllegalStateException("Only draft assessments can be published");
    int questionCount = questions.findByAssessmentIdOrderByCreatedAtAscIdAsc(assessmentId).size();
    if (questionCount < 1) throw new IllegalStateException("Add at least one question before publishing");
    assessment.testCode = uniqueTestCode();
    assessment.status = "PUBLISHED";
    return new PublishedAssessmentResponse(details(assessments.save(assessment)), assessment.testCode);
  }

  Assessment ownedAssessment(UUID id, Authentication authentication) {
    return assessments.findByIdAndOwnerId(id, recruiter(authentication))
        .orElseThrow(() -> new ResourceNotFoundException("Assessment not found"));
  }

  static UUID recruiter(Authentication authentication) {
    if (authentication == null || authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_RECRUITER")))
      throw new org.springframework.security.access.AccessDeniedException("Recruiter access required");
    try { return UUID.fromString(authentication.getName()); }
    catch (IllegalArgumentException ex) { throw new org.springframework.security.access.AccessDeniedException("Invalid recruiter identity"); }
  }

  private AssessmentDetailsResponse details(Assessment assessment) {
    return new AssessmentDetailsResponse(assessment.id.toString(), assessment.title, assessment.role,
        assessment.description, assessment.durationMinutes, assessment.passingScore, assessment.status,
        assessment.testCode, questions.findByAssessmentIdOrderByCreatedAtAscIdAsc(assessment.id).size());
  }

  static RecruiterQuestionResponse recruiterQuestion(Question q) {
    return new RecruiterQuestionResponse(q.id.toString(), q.questionText, q.optionA, q.optionB,
        q.optionC, q.optionD, q.correctOption);
  }

  private String uniqueTestCode() {
    for (int attempt = 0; attempt < 20; attempt++) {
      var code = new StringBuilder(6);
      for (int i = 0; i < 6; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
      if (!assessments.existsByTestCode(code.toString())) return code.toString();
    }
    throw new IllegalStateException("Could not generate a unique test code; please try publishing again");
  }
}

@Service
class CandidateMcqService {
  private final AssessmentRepository assessments;
  private final QuestionRepository questions;
  private final AttemptRepository attempts;
  private final AttemptAnswerRepository answers;

  CandidateMcqService(AssessmentRepository assessments, QuestionRepository questions, AttemptRepository attempts, AttemptAnswerRepository answers) {
    this.assessments = assessments; this.questions = questions; this.attempts = attempts; this.answers = answers;
  }

  @Transactional
  CandidateAssessmentResponse start(String testCode, CandidateStartRequest request) {
    var assessment = assessments.findByTestCodeIgnoreCaseAndStatus(testCode.trim(), "PUBLISHED")
        .orElseThrow(() -> new ResourceNotFoundException("Published assessment not found"));
    var normalizedEmail = request.candidateEmail().trim().toLowerCase(Locale.ROOT);
    var existing = attempts.findByAssessmentIdAndCandidateEmail(assessment.id, normalizedEmail);
    if (existing.isPresent()) {
      var attempt = existing.get();
      if (attempt.status != RecruitmentAttemptStatus.IN_PROGRESS) throw new IllegalStateException("This candidate has already completed this assessment");
      return candidateAssessment(attempt);
    }
    var attempt = new Attempt();
    attempt.assessment = assessment;
    attempt.candidateName = request.candidateName().trim();
    attempt.candidateEmail = normalizedEmail;
    attempt.status = RecruitmentAttemptStatus.IN_PROGRESS;
    attempt.startedAt = Instant.now();
    return candidateAssessment(attempts.save(attempt));
  }

  @Transactional
  CandidateTestResultResponse submit(UUID attemptId, SubmitRecruitmentAttemptRequest request) {
    var attempt = attempts.findByIdForUpdate(attemptId).orElseThrow(() -> new ResourceNotFoundException("Attempt not found"));
    if (attempt.status != RecruitmentAttemptStatus.IN_PROGRESS) throw new IllegalStateException("This attempt has already been submitted");
    if (Instant.now().isAfter(attempt.startedAt.plusSeconds(attempt.assessment.durationMinutes * 60L)))
      throw new IllegalStateException("The assessment time limit has expired");

    var assessmentQuestions = questions.findByAssessmentIdOrderByCreatedAtAscIdAsc(attempt.assessment.id);
    if (assessmentQuestions.isEmpty()) throw new IllegalStateException("This assessment has no questions");
    var submittedById = new HashMap<UUID, SubmittedAnswerRequest>();
    for (var answer : request.answers()) {
      UUID questionId;
      try { questionId = UUID.fromString(answer.questionId()); }
      catch (IllegalArgumentException ex) { throw new IllegalArgumentException("An answer contains an invalid question ID"); }
      if (submittedById.putIfAbsent(questionId, answer) != null) throw new IllegalArgumentException("A question may only be answered once");
    }
    var allowedIds = new HashSet<UUID>();
    assessmentQuestions.forEach(q -> allowedIds.add(q.id));
    if (!allowedIds.containsAll(submittedById.keySet())) throw new IllegalArgumentException("An answer references a question outside this assessment");

    int score = 0;
    for (var question : assessmentQuestions) {
      var submitted = submittedById.get(question.id);
      var selected = submitted == null || submitted.selectedOption() == null ? null : submitted.selectedOption();
      boolean correct = selected != null && selected.equals(question.correctOption);
      var answer = new AttemptAnswer();
      answer.attempt = attempt;
      answer.question = question;
      answer.selectedOption = selected;
      answer.isCorrect = correct;
      answers.save(answer);
      if (correct) score++;
    }
    var percentage = BigDecimal.valueOf(score).multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(assessmentQuestions.size()), 2, RoundingMode.HALF_UP);
    attempt.score = score;
    attempt.percentage = percentage;
    attempt.status = RecruitmentAttemptStatus.COMPLETED;
    attempt.submittedAt = Instant.now();
    attempts.save(attempt);
    boolean passed = percentage.compareTo(attempt.assessment.passingScore) >= 0;
    return new CandidateTestResultResponse(attempt.id.toString(), score, assessmentQuestions.size(), percentage,
        attempt.assessment.passingScore, passed, attempt.status, attempt.submittedAt);
  }

  private CandidateAssessmentResponse candidateAssessment(Attempt attempt) {
    var assessment = attempt.assessment;
    var safeQuestions = questions.findByAssessmentIdOrderByCreatedAtAscIdAsc(assessment.id).stream()
        .map(q -> new CandidateQuestionResponse(q.id.toString(), q.questionText, q.optionA, q.optionB, q.optionC, q.optionD)).toList();
    if (safeQuestions.isEmpty()) throw new IllegalStateException("This assessment has no questions");
    var deadline = attempt.startedAt.plusSeconds(assessment.durationMinutes * 60L);
    return new CandidateAssessmentResponse(attempt.id.toString(), assessment.title, assessment.role,
        assessment.durationMinutes, attempt.startedAt, deadline, safeQuestions);
  }
}

@Service
class SubmissionReviewService {
  private final RecruitmentAssessmentService assessmentService;
  private final AttemptRepository attempts;

  SubmissionReviewService(RecruitmentAssessmentService assessmentService, AttemptRepository attempts) {
    this.assessmentService = assessmentService; this.attempts = attempts;
  }

  @Transactional(readOnly = true)
  List<SubmissionSummaryResponse> submissions(UUID assessmentId, Authentication authentication) {
    assessmentService.ownedAssessment(assessmentId, authentication);
    return attempts.findByAssessmentIdOrderByStartedAtDesc(assessmentId).stream().map(SubmissionReviewService::summary).toList();
  }

  @Transactional
  SubmissionSummaryResponse updateStatus(UUID attemptId, UpdateSubmissionStatusRequest request, Authentication authentication) {
    if (request.status() != RecruitmentAttemptStatus.SHORTLISTED && request.status() != RecruitmentAttemptStatus.REJECTED)
      throw new IllegalArgumentException("Status must be SHORTLISTED or REJECTED");
    var attempt = attempts.findById(attemptId).orElseThrow(() -> new ResourceNotFoundException("Submission not found"));
    assessmentService.ownedAssessment(attempt.assessment.id, authentication);
    if (attempt.status == RecruitmentAttemptStatus.IN_PROGRESS || attempt.status == RecruitmentAttemptStatus.COMPLETED && attempt.submittedAt == null)
      throw new IllegalStateException("Only completed submissions can be shortlisted or rejected");
    attempt.status = request.status();
    return summary(attempts.save(attempt));
  }

  private static SubmissionSummaryResponse summary(Attempt attempt) {
    return new SubmissionSummaryResponse(attempt.id.toString(), attempt.candidateName, attempt.candidateEmail,
        attempt.score, attempt.percentage, attempt.status, attempt.startedAt, attempt.submittedAt);
  }
}
