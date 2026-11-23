package com.routinebridge.rootcore;

import android.content.ComponentName;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class RootCorePolicy {

    private final Set<String> providerAuthorities;
    private final Set<String> activityComponents;

    private RootCorePolicy(
            Set<String> providerAuthorities,
            Set<String> activityComponents
    ) {
        this.providerAuthorities = Collections.unmodifiableSet(
                new LinkedHashSet<>(providerAuthorities)
        );
        this.activityComponents = Collections.unmodifiableSet(
                new LinkedHashSet<>(activityComponents)
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RootCorePolicy denyAll() {
        return builder().build();
    }

    public boolean allowsProviderAuthority(String authority) {
        return authority != null && providerAuthorities.contains(authority);
    }

    public boolean allowsActivity(ComponentName component) {
        return component != null
                && activityComponents.contains(component.flattenToString());
    }

    String encode() {
        try {
            JSONObject json = new JSONObject();
            JSONArray providers = new JSONArray();
            JSONArray activities = new JSONArray();

            for (String authority : providerAuthorities) {
                providers.put(authority);
            }
            for (String component : activityComponents) {
                activities.put(component);
            }

            json.put("providers", providers);
            json.put("activities", activities);

            return Base64.encodeToString(
                    json.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP
            );
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Cannot encode RootCore policy", error
            );
        }
    }

    public static RootCorePolicy decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("RootCore policy is empty");
        }

        try {
            String source = new String(
                    Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP),
                    StandardCharsets.UTF_8
            );

            JSONObject json = new JSONObject(source);
            JSONArray providers = json.getJSONArray("providers");
            JSONArray activities = json.getJSONArray("activities");

            Builder builder = builder();

            for (int i = 0; i < providers.length(); i++) {
                builder.allowProviderAuthority(providers.getString(i));
            }

            for (int i = 0; i < activities.length(); i++) {
                String value = activities.getString(i);
                ComponentName component =
                        ComponentName.unflattenFromString(value);

                if (component == null) {
                    throw new IllegalArgumentException(
                            "Invalid Activity component: " + value
                    );
                }
                builder.allowActivity(component);
            }

            return builder.build();
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "Invalid RootCore policy", error
            );
        }
    }

    public static final class Builder {
        private final Set<String> providers = new LinkedHashSet<>();
        private final Set<String> activities = new LinkedHashSet<>();

        public Builder allowProviderAuthority(String authority) {
            if (authority == null || authority.isBlank()
                    || authority.contains("/")
                    || authority.contains(":")
                    || authority.contains(" ")) {
                throw new IllegalArgumentException(
                        "Invalid Provider authority"
                );
            }
            providers.add(authority);
            return this;
        }

        public Builder allowActivity(ComponentName component) {
            if (component == null
                    || component.getPackageName() == null
                    || component.getClassName() == null) {
                throw new IllegalArgumentException(
                        "Invalid Activity component"
                );
            }
            activities.add(component.flattenToString());
            return this;
        }

        public RootCorePolicy build() {
            return new RootCorePolicy(providers, activities);
        }
    }
}
