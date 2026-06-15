package dev.datrollout.argus.kubernetes.phase.scheduling;

import dev.datrollout.argus.kubernetes.phase.K8sEventWrapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@AllArgsConstructor
// Todo Will extend this class in multiple concrete in the future
public class FailedSchedulingEventWrapper extends K8sEventWrapper {}
