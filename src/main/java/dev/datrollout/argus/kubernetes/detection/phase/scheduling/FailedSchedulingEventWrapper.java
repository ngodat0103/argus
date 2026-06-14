package dev.datrollout.argus.kubernetes.detection.phase.scheduling;

import dev.datrollout.argus.kubernetes.detection.phase.K8sEventWrapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
// Todo Will extend this class in multiple concrete in the future
public class FailedSchedulingEventWrapper extends K8sEventWrapper {}
