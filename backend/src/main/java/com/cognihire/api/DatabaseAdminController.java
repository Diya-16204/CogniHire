package com.cognihire.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class DatabaseAdminController {
  private static final String SUCCESS_MESSAGE = "All user data, assessments, attempts, and interactions have been cleared. Schema history preserved.";
  private final DatabaseResetService resetService;

  DatabaseAdminController(DatabaseResetService resetService) {
    this.resetService = resetService;
  }

  record ResetResponse(String status, String message) {}

  @PostMapping("/api/admin/reset-db")
  ResetResponse resetDatabase(Authentication authentication) {
    if (authentication == null || authentication.getAuthorities().stream()
        .noneMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access is required");
    }
    resetService.clearApplicationData();
    return new ResetResponse("success", SUCCESS_MESSAGE);
  }
}
