package com.cognihire.api;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
class DatabaseResetService {
  private final JdbcTemplate jdbcTemplate;

  DatabaseResetService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  void clearApplicationData() {
    try {
      var script = new ClassPathResource("db/reset_data.sql");
      String sql = StreamUtils.copyToString(script.getInputStream(), StandardCharsets.UTF_8);
      jdbcTemplate.execute(sql);
    } catch (IOException e) {
      throw new IllegalStateException("Could not load the database reset script", e);
    }
  }
}
