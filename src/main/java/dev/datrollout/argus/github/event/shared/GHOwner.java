package dev.datrollout.argus.github.event.shared;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GHOwner(String login, long id) {}
