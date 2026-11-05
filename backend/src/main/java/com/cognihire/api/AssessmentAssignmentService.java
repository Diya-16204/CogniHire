package com.cognihire.api;

import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
class AssessmentAssignmentService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final AssessmentRepository assessments;
  private final UserAccountRepository users;
  private final AssessmentAssignmentRepository assignments;
  private final PasswordEncoder passwordEncoder;

  AssessmentAssignmentService(AssessmentRepository assessments, UserAccountRepository users,
      AssessmentAssignmentRepository assignments, PasswordEncoder passwordEncoder) {
    this.assessments = assessments;
    this.users = users;
    this.assignments = assignments;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  CandidateEmailsAssignResponse assign(UUID assessmentId, CandidateEmailsAssignRequest request, Authentication auth) {
    UUID recruiterId = RecruitmentAssessmentService.recruiter(auth);
    Assessment assessment = assessments.findByIdAndOwnerId(assessmentId, recruiterId)
        .orElseThrow(() -> new ResourceNotFoundException("Assessment not found"));
    if (!"PUBLISHED".equals(assessment.status))
      throw new IllegalStateException("Publish the assessment before assigning candidates");

    List<CandidateAssignmentRow> results = new ArrayList<>();
    int assignedCount = 0, alreadyAssignedCount = 0, failedCount = 0;
    var emails = new LinkedHashSet<String>();
    for (String rawEmail : request.candidateEmails()) {
      String email = rawEmail.trim().toLowerCase(Locale.ROOT);
      if (!email.isBlank()) emails.add(email);
    }

    for (String email : emails) {
      UserAccount candidate = users.findByEmailIgnoreCase(email).orElse(null);
      boolean accountCreated = false;
      if (candidate == null) {
        String localPart = email.substring(0, email.indexOf('@'));
        String fullName = localPart.length() > 160 ? localPart.substring(0, 160) : localPart;
        candidate = new UserAccount(email, fullName, passwordEncoder.encode(randomTemporaryPassword()), Role.CANDIDATE);
        candidate.accountClaimed = false;
        candidate = users.saveAndFlush(candidate);
        accountCreated = true;
      } else if (candidate.role != Role.CANDIDATE) {
        results.add(new CandidateAssignmentRow(email, candidate.fullName, "FAILED", false,
            "This email belongs to a non-candidate account."));
        failedCount++;
        continue;
      }

      var existing = assignments.findByAssessmentIdAndCandidateId(assessment.id, candidate.id);
      if (existing.isPresent()) {
        results.add(new CandidateAssignmentRow(email, candidate.fullName, "ALREADY_ASSIGNED", accountCreated,
            "This candidate already has the assessment."));
        alreadyAssignedCount++;
      } else {
        var assignment = new AssessmentAssignment();
        assignment.assessment = assessment;
        assignment.candidate = candidate;
        assignments.save(assignment);
        results.add(new CandidateAssignmentRow(email, candidate.fullName, "ASSIGNED", accountCreated,
            accountCreated
                ? "Assigned. The candidate must create an account with this email to set a password before signing in."
                : "Assigned. The assessment is available in the candidate workspace."));
        assignedCount++;
      }
    }
    return new CandidateEmailsAssignResponse(assignedCount, alreadyAssignedCount, failedCount, results);
  }

  private String randomTemporaryPassword() {
    byte[] bytes = new byte[48];
    RANDOM.nextBytes(bytes);
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
