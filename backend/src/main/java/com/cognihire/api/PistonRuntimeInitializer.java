package com.cognihire.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
class PistonRuntimeInitializer {
  private static final Logger log=LoggerFactory.getLogger(PistonRuntimeInitializer.class);
  private final String baseUrl,pythonVersion;
  private final boolean enabled;
  private final ObjectMapper mapper;
  private final HttpClient client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

  PistonRuntimeInitializer(@Value("${piston.url:http://localhost:2000}") String baseUrl,
      @Value("${judge0.piston-python-version:3.10.0}") String pythonVersion,
      @Value("${piston.enabled:true}") boolean enabled,ObjectMapper mapper) {
    this.baseUrl=baseUrl==null?"":baseUrl.trim().replaceAll("/+$","");
    this.pythonVersion=pythonVersion==null||pythonVersion.isBlank()?"3.10.0":pythonVersion.trim();
    this.enabled=enabled;
    this.mapper=mapper;
  }

  @EventListener(ApplicationReadyEvent.class)
  void ensurePythonRuntimeAfterStartup() {
    if(!enabled||!isPistonConfigured()) return;
    Thread.startVirtualThread(this::ensurePythonRuntime);
  }

  private boolean isPistonConfigured() {
    try {
      URI uri=URI.create(baseUrl);
      return uri.getPort()==2000 && (uri.getPath()==null || uri.getPath().isBlank() || uri.getPath().equals("/"));
    } catch(Exception ignored) { return false; }
  }

  private void ensurePythonRuntime() {
    URI packagesUri=URI.create(baseUrl+"/api/v2/packages");
    try {
      var listRequest=HttpRequest.newBuilder(packagesUri).timeout(Duration.ofSeconds(20))
          .header("Accept","application/json").GET().build();
      var listResponse=client.send(listRequest,HttpResponse.BodyHandlers.ofString());
      if(listResponse.statusCode()<200||listResponse.statusCode()>=300) {
        log.warn("Could not inspect Piston runtimes (HTTP {}): {}. Python {} was not auto-installed.",
            listResponse.statusCode(),responseMessage(listResponse.body()),pythonVersion);
        return;
      }
      JsonNode packages=mapper.readTree(listResponse.body());
      if(isPythonInstalled(packages)) {
        log.info("Piston Python {} runtime is installed.",pythonVersion);
        return;
      }

      log.info("Piston Python {} runtime is missing; requesting installation.",pythonVersion);
      String body=mapper.writeValueAsString(Map.of("language","python","version",pythonVersion));
      var installRequest=HttpRequest.newBuilder(packagesUri).timeout(Duration.ofMinutes(30))
          .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
      var installResponse=client.send(installRequest,HttpResponse.BodyHandlers.ofString());
      if(installResponse.statusCode()<200||installResponse.statusCode()>=300) {
        log.warn("Piston could not install Python {} (HTTP {}): {}",pythonVersion,installResponse.statusCode(),responseMessage(installResponse.body()));
        return;
      }
      log.info("Piston Python {} runtime installation completed.",pythonVersion);
    } catch(InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Piston Python runtime setup was interrupted.");
    } catch(Exception e) {
      log.warn("Could not check or install Piston Python {}: {}",pythonVersion,e.getMessage());
    }
  }

  private boolean isPythonInstalled(JsonNode packages) {
    if(!packages.isArray()) return false;
    for(JsonNode pkg:packages) {
      if("python".equalsIgnoreCase(pkg.path("language").asText())
          && pythonVersion.equals(pkg.path("language_version").asText())
          && pkg.path("installed").asBoolean(false)) return true;
    }
    return false;
  }

  private String responseMessage(String body) {
    try {
      String message=mapper.readTree(body).path("message").asText();
      if(!message.isBlank()) return message.length()>300?message.substring(0,300):message;
    } catch(Exception ignored) { }
    return "Piston returned an invalid error response";
  }
}
