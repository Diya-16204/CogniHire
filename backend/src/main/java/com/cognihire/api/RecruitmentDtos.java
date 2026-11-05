package com.cognihire.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

record RecruiterLoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
record RecruiterLoginResponse(String token, String tokenType, RecruiterSummary recruiter) {}
record RecruiterSummary(String id, String email, String fullName, String role) {}

record CreateRecruitmentAssessmentRequest(
    @NotBlank @Size(max = 180) String title,
    @NotBlank @Size(max = 160) String role,
    @Size(max = 10000) String description,
    @Min(1) @Max(600) int durationMinutes,
    @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal passingScore) {}
record CreateMcqQuestionRequest(
    @NotBlank @Size(max = 20000) String questionText,
    @NotBlank @Size(max = 1000) String optionA,
    @NotBlank @Size(max = 1000) String optionB,
    @NotBlank @Size(max = 1000) String optionC,
    @NotBlank @Size(max = 1000) String optionD,
    @NotBlank @Pattern(regexp = "[ABCD]") String correctOption) {}
record CreateUnifiedRecruiterAssessmentRequest(
    @NotBlank @Size(max = 180) String title,
    @NotBlank @Size(max = 160) String role,
    @Size(max = 10000) String description,
    @Min(1) @Max(600) int durationMinutes,
    @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal passingScore,
    @Size(max = 20) List<@NotBlank @Size(max = 4000) String> generalQuestions,
    @Size(max = 100) List<@Valid UnifiedMcqQuestionInput> subjectQuestions,
    boolean enableGithubAnalysis) {}
record UnifiedMcqQuestionInput(@NotBlank @Size(max = 120) String subject,
    @NotBlank @Size(max = 20000) String questionText, @NotBlank @Size(max = 1000) String optionA,
    @NotBlank @Size(max = 1000) String optionB, @NotBlank @Size(max = 1000) String optionC,
    @NotBlank @Size(max = 1000) String optionD, @NotBlank @Pattern(regexp = "[ABCD]") String correctOption) {}
record UnifiedAssessmentCreatedResponse(String id, String title, String status, String testCode, int subjectQuestionCount,
    int interviewQuestionCount, boolean githubAnalysisEnabled, String githubRepository) {}
record CandidateEmailsAssignRequest(@NotEmpty @Size(max = 100) List<@NotBlank @Email @Size(max = 320) String> candidateEmails) {}
record CandidateAssignmentRow(String email, String fullName, String status, boolean accountCreated, String message) {}
record CandidateEmailsAssignResponse(int assigned, int alreadyAssigned, int failed, List<CandidateAssignmentRow> candidates) {}
record RecruiterQuestionResponse(String id, String questionText, String optionA, String optionB, String optionC, String optionD, String correctOption) {}
record AssessmentDetailsResponse(String id, String title, String role, String description, int durationMinutes,
                                 BigDecimal passingScore, String status, String testCode, int questionCount) {}
record PublishedAssessmentResponse(AssessmentDetailsResponse assessment, String testCode) {}

record CandidateStartRequest(@NotBlank @Size(max = 160) String candidateName, @Email @NotBlank @Size(max = 320) String candidateEmail) {}
record CandidateAssessmentStartRequest(@Size(max = 500) String githubRepositoryUrl) {}
record CandidateQuestionResponse(String id, String questionText, String optionA, String optionB, String optionC, String optionD) {}
record CandidateAssessmentResponse(String attemptId, String title, String role, int durationMinutes, Instant startedAt,
                                   Instant deadlineAt, List<CandidateQuestionResponse> questions) {}
record SubmittedAnswerRequest(@NotBlank String questionId, @Pattern(regexp = "[ABCD]") String selectedOption) {}
record SubmitRecruitmentAttemptRequest(@NotNull @Size(max = 500) List<@Valid SubmittedAnswerRequest> answers) {}
record CandidateTestResultResponse(String attemptId, int score, int totalQuestions, BigDecimal percentage, BigDecimal passingScore,
                                   boolean passed, RecruitmentAttemptStatus status, Instant submittedAt) {}
record SubmissionSummaryResponse(String attemptId, String candidateName, String candidateEmail, Integer score,
                                 BigDecimal percentage, RecruitmentAttemptStatus status, Instant startedAt, Instant submittedAt) {}
record UpdateSubmissionStatusRequest(@NotNull RecruitmentAttemptStatus status) {}
record ApiErrorResponse(Instant timestamp, int status, String error, String message, String path) {}
