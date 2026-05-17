package dev.datrollout.argus.observedetection.client;

import java.net.URI;

public interface ObservabilityClient {

    URI getEndpoint();

    String clientType();
}
