package dev.datrollout.argus.observedetection.model;

public enum ConfidenceLevel {
    CONFIRMED,
    PROBABLE,
    POSSIBLE,
    IGNORE;

    public static ConfidenceLevel fromScore(double score) {
        if (score >= 100) return CONFIRMED;
        if (score >= 70)  return PROBABLE;
        if (score >= 40)  return POSSIBLE;
        return IGNORE;
    }
}
