package com.example.inventoryorganizer.config;

import java.util.ArrayList;
import java.util.List;

/**
 * A named content profile for a bundle. Stores the item rules that define what should go
 * inside this bundle. During OI sort, each physical bundle is matched to the profile whose
 * rules best fit its current contents (by item count overlap); unmatched bundles are left
 * completely untouched.
 */
public class BundleProfile {

    private String name;
    private List<String> rules = new ArrayList<>();

    public BundleProfile() {}

    public BundleProfile(String name) {
        this.name = name;
    }

    public String getName() { return name != null ? name : ""; }
    public void setName(String n) { this.name = n; }

    /**
     * Returns the content rules, with any literal "any"/empty entries stripped. Self-healing against
     * configs saved before the bundle-profile editor stopped writing "any" for every untouched grid
     * slot — a stray "any" makes matchesAnyBundleRule/bundleMatchesRule treat the whole profile as an
     * unconditional catch-all, so it must never be allowed to linger in a saved profile.
     */
    public List<String> getRules() {
        if (rules == null) rules = new ArrayList<>();
        rules.removeIf(r -> r == null || r.isEmpty() || r.equals("any"));
        return rules;
    }

    public void setRules(List<String> r) {
        this.rules = r != null ? new ArrayList<>(r) : new ArrayList<>();
    }

    public BundleProfile copy() {
        BundleProfile c = new BundleProfile(this.name);
        c.rules = new ArrayList<>(getRules());
        return c;
    }
}
