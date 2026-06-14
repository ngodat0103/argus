package dev.datrollout.argus.kubernetes.detection.phase.provisioning;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

@Getter
@SuperBuilder
@Slf4j
public class FailedCreatePodSandBoxEventWrapper extends ProvisioningEventWrapper {}
