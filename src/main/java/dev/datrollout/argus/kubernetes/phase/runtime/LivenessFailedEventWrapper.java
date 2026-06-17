package dev.datrollout.argus.kubernetes.phase.runtime;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class LivenessFailedEventWrapper extends LoggablePodEventWrapper {
    @Override
    public String getFailedContainerName() {
        return "";
    }
}
