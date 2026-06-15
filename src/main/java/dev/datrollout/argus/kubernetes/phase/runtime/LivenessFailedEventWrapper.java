package dev.datrollout.argus.kubernetes.phase.runtime;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@SuperBuilder
public class LivenessFailedEventWrapper extends LoggablePodEventWrapper {}
