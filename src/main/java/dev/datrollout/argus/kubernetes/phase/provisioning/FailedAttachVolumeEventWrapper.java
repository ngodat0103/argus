package dev.datrollout.argus.kubernetes.phase.provisioning;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
@AllArgsConstructor
public class FailedAttachVolumeEventWrapper extends ProvisioningEventWrapper {}
