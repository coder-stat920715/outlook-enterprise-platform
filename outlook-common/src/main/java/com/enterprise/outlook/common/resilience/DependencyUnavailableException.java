package com.enterprise.outlook.common.resilience;

public class DependencyUnavailableException extends RuntimeException {
    private final String dependency;
    public DependencyUnavailableException(String dependency, Throwable cause) {
        super("Dependency unavailable: " + dependency, cause);
        this.dependency = dependency;
    }
    public String dependency() { return dependency; }
}
