package dev.datrollout.argus.kubernetes.phase.runtime;

import dev.datrollout.argus.kubernetes.phase.K8sEventWrapper;
import java.util.List;
import java.util.regex.Pattern;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
@NoArgsConstructor
@Setter(value = AccessLevel.PROTECTED)
public abstract class LoggablePodEventWrapper extends K8sEventWrapper {
    protected List<String> lineLogs;
    private static final Pattern CONTAINER_PATTERN = Pattern.compile("spec\\.(init)?[cC]ontainers\\{([^}]+)}");
    private static final Pattern FAILED_CONTAINER_PATTERN = Pattern.compile("failed container (.+)");
    private static final Pattern GENERIC_CONTAINER_PATTERN = Pattern.compile("container[:\\s]+([\\w-]+)");

    public abstract String getFailedContainerName();
}
