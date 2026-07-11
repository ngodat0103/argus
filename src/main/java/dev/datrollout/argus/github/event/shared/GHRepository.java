package dev.datrollout.argus.github.event.shared;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GHRepository(String name, String full_name, GHOwner owner, boolean is_private) {}
