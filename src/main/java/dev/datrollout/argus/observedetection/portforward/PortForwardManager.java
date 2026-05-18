package dev.datrollout.argus.observedetection.portforward;

import dev.datrollout.argus.observedetection.model.ProbeTarget;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Opens on-demand port-forward tunnels through the Kubernetes API server via the
 * Fabric8 client. No {@code kubectl} binary required; uses the same kubeconfig
 * context already in use by the rest of the application.
 *
 * <p>Kubernetes only exposes a {@code /portforward} subresource on <em>Pods</em>.
 * For Service-sourced targets the Fabric8 service API handles the delegation; for
 * Pod-sourced targets (e.g. StatefulSet pods discovered directly) we forward to
 * the pod resource instead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortForwardManager {

    private final KubernetesClient kubernetesClient;

    /**
     * Forwards {@code remotePort} of the given target to an OS-assigned local port.
     *
     * <p>The returned {@link LocalPortForward} is {@link AutoCloseable}; wrap it in a
     * try-with-resources block to guarantee the tunnel is torn down after the probe.
     *
     * @param target     Kubernetes resource to tunnel into
     * @param remotePort port number to forward
     * @return an active {@link LocalPortForward}; caller must close it
     * @throws IOException if the tunnel cannot be established or the local port is invalid
     */
    public LocalPortForward open(ProbeTarget target, int remotePort) throws IOException {
        log.debug("Opening port-forward for {} ({}) port {}", target.key(), target.getSourceKind(), remotePort);

        LocalPortForward pf = target.getSourceKind() == ProbeTarget.SourceKind.POD
                ? kubernetesClient
                        .pods()
                        .inNamespace(target.getNamespace())
                        .withName(target.getServiceName())
                        .portForward(remotePort)
                : kubernetesClient
                        .services()
                        .inNamespace(target.getNamespace())
                        .withName(target.getServiceName())
                        .portForward(remotePort);

        int localPort = pf.getLocalPort();
        if (localPort <= 0) {
            pf.close();
            throw new IOException("Port-forward returned invalid local port " + localPort + " for " + target.key());
        }

        log.info("Port-forward ready: localhost:{} -> {} ({})", localPort, target.key(), target.getSourceKind());
        return pf;
    }
}
