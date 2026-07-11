package dev.datrollout.argus.github.event.shared;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GHSender(String login, long id) {}
