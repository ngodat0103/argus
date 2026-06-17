package dev.datrollout.argus.kubernetes.phase.runtime;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
public class ReadinessFailedEventWrapper extends LoggablePodEventWrapper {
    @Override
    public String getFailedContainerName() {
        return "";
    }
}
