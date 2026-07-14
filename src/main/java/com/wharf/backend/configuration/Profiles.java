package com.wharf.backend.configuration;

/**
 * Named Spring profile constants — never reference profile names as raw strings.
 */
public final class Profiles {

    public static final String DEV = "dev";
    public static final String PROD = "prod";
    public static final String TEST = "test";

    private Profiles() {
    }
}
