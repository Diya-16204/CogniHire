package com.cognihire.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/v1/auth")
class AuthController {
  private final UserAccountRepository users; private final PasswordEncoder encoder; private final JwtService jwt;
  AuthController(UserAccountRepository users, PasswordEncoder encoder, JwtService jwt) { this.users=users; this.encoder=encoder; this.jwt=jwt; }
  record RegisterRequest(@Email @NotBlank String email, @NotBlank @Size(min=2,max=160) String fullName, @NotBlank @Size(min=12,max=128) String password, @NotBlank String role) {}
  record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
  record AuthResponse(String token, String tokenType, UserResponse user) {}
  record UserResponse(String id,String email,String fullName,String role) {}
  private AuthResponse response(UserAccount u) { return new AuthResponse(jwt.issue(u),"Bearer",new UserResponse(u.id.toString(),u.email,u.fullName,u.role.name())); }
  @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
  AuthResponse register(@Valid @RequestBody RegisterRequest req) {
    Role role; try { role=Role.valueOf(req.role().toUpperCase()); } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported role"); }
    if (role==Role.ADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator accounts are provisioned by an administrator");
    if (users.findByEmailIgnoreCase(req.email()).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT,"An account with this email already exists");
    return response(users.save(new UserAccount(req.email().trim().toLowerCase(),req.fullName().trim(),encoder.encode(req.password()),role)));
  }
  @PostMapping("/login") AuthResponse login(@Valid @RequestBody LoginRequest req) {
    var u=users.findByEmailIgnoreCase(req.email()).filter(x->encoder.matches(req.password(),x.passwordHash)).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Email or password is incorrect"));
    return response(u);
  }
}
