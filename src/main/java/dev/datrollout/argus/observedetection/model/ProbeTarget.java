package dev.datrollout.argus.observedetection.model;

import java.net.URI;
import java.util.List;
import java.util.Map;

public class ProbeTarget {

    /** Whether this target was discovered from a Kubernetes Service or a Pod. */
    public enum SourceKind { SERVICE, POD }

    private String namespace;
    private String serviceName;
    private URI baseUrl;
    private Map<String, String> labels;
    private Map<String, String> annotations;
    private List<Integer> ports;
    private List<String> containerImages;
    private SourceKind sourceKind;

    private ProbeTarget(Builder builder) {
        this.namespace = builder.namespace;
        this.serviceName = builder.serviceName;
        this.baseUrl = builder.baseUrl;
        this.labels = builder.labels;
        this.annotations = builder.annotations;
        this.ports = builder.ports;
        this.containerImages = builder.containerImages;
        this.sourceKind = builder.sourceKind;
    }

    public String getNamespace() { return namespace; }
    public String getServiceName() { return serviceName; }
    public URI getBaseUrl() { return baseUrl; }
    public Map<String, String> getLabels() { return labels; }
    public Map<String, String> getAnnotations() { return annotations; }
    public List<Integer> getPorts() { return ports; }
    public List<String> getContainerImages() { return containerImages; }
    public SourceKind getSourceKind() { return sourceKind; }

    public String key() {
        return namespace + "/" + serviceName;
    }

    /** Returns a shallow copy of this target with the {@code baseUrl} replaced. */
    public ProbeTarget withBaseUrl(URI newBaseUrl) {
        return builder()
                .namespace(this.namespace)
                .serviceName(this.serviceName)
                .baseUrl(newBaseUrl)
                .labels(this.labels)
                .annotations(this.annotations)
                .ports(this.ports)
                .containerImages(this.containerImages)
                .sourceKind(this.sourceKind)
                .build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String namespace;
        private String serviceName;
        private URI baseUrl;
        private Map<String, String> labels = Map.of();
        private Map<String, String> annotations = Map.of();
        private List<Integer> ports = List.of();
        private List<String> containerImages = List.of();
        private SourceKind sourceKind = SourceKind.SERVICE;

        public Builder namespace(String namespace) { this.namespace = namespace; return this; }
        public Builder serviceName(String serviceName) { this.serviceName = serviceName; return this; }
        public Builder baseUrl(URI baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder labels(Map<String, String> labels) { this.labels = labels; return this; }
        public Builder annotations(Map<String, String> annotations) { this.annotations = annotations; return this; }
        public Builder ports(List<Integer> ports) { this.ports = ports; return this; }
        public Builder containerImages(List<String> containerImages) { this.containerImages = containerImages; return this; }
        public Builder sourceKind(SourceKind sourceKind) { this.sourceKind = sourceKind; return this; }

        public ProbeTarget build() { return new ProbeTarget(this); }
    }
}
