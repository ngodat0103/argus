package dev.datrollout.argus.kubernetes.phase.runtime;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

@SuperBuilder
@Getter
@Slf4j
public class ReadinessFailedEventWrapper extends LoggablePodEventWrapper {}
