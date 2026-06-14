package dev.datrollout.argus.kubernetes.detection.phase.runtime;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@SuperBuilder
@AllArgsConstructor
public class LivenessFailedEventWrapper extends LoggablePodEventWrapper {}
