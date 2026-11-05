package com.cognihire.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

record RoundTwoInterviewPrompt(String id,String subject,String questionText,int position) {}

final class RoundTwoInterviewPromptCatalog {
  private RoundTwoInterviewPromptCatalog() {}

  static List<RoundTwoInterviewPrompt> fallback(String requestedRole) {
    String role=requestedRole==null||requestedRole.isBlank()?"Software Engineer":requestedRole.trim();
    String lower=role.toLowerCase(Locale.ROOT);
    List<String> questions;
    if(lower.contains("backend")||lower.contains("server"))questions=List.of(
        "How would you design a REST API that remains reliable as traffic grows?",
        "How do you keep database writes consistent when multiple requests update the same resource?",
        "How would you investigate and reduce latency in a backend service?");
    else if(lower.contains("frontend")||lower.contains("ui"))questions=List.of(
        "How do you keep a frontend responsive while loading data from several services?",
        "How would you decide what state belongs locally and what should be shared across components?",
        "How do you investigate a rendering performance problem in a web application?");
    else if(lower.contains("data")||lower.contains("machine learning")||lower.equals("ml")||lower.startsWith("ml "))questions=List.of(
        "How would you validate a data pipeline before trusting its output?",
        "How do you detect and handle missing or skewed data in a production workflow?",
        "How would you monitor a machine learning model after deployment?");
    else if(lower.contains("devops")||lower.contains("platform")||lower.contains("site reliability"))questions=List.of(
        "How would you design a deployment that can be rolled back safely?",
        "What signals would you use to diagnose an outage in a distributed service?",
        "How do you balance reliability, deployment speed, and infrastructure cost?");
    else questions=List.of(
        "Walk me through the design choices you made for a recent technical project.",
        "How would you investigate a production issue that is difficult to reproduce?",
        "How do you decide when a solution should favor simplicity over extensibility?");
    var result=new ArrayList<RoundTwoInterviewPrompt>();
    for(int i=0;i<questions.size();i++)result.add(new RoundTwoInterviewPrompt("fallback-"+(i+1),role,questions.get(i),i));
    return List.copyOf(result);
  }
}
