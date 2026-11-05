package com.confluo.common.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Bootstraps the Firebase Admin SDK and exposes it to the rest of the application.
 *
 * <p>The SDK is initialized once per JVM from the ambient application default credentials
 * (the {@code GOOGLE_APPLICATION_CREDENTIALS} service account file locally, the attached
 * service account when running on Google Cloud), so no key material is carried in configuration.</p>
 *
 * <p>Activated unless {@code firebase.enabled} is explicitly set to {@code false}, which lets
 * tests and local runs start without contacting Firebase.</p>
 */
@Configuration
@Slf4j
@ConditionalOnProperty(name = "firebase.enabled", havingValue = "true", matchIfMissing = true)
public class FirebaseConfig {

    /**
     * Google Cloud project the Admin SDK should operate against.
     */
    @Value("${firebase.project-id}")
    private String projectId;

    /**
     * The initialized Firebase application, backing the {@link #firebaseAuth()} bean.
     */
    private FirebaseApp firebaseApp;

    /**
     * Initializes the Firebase Admin SDK before any bean depending on it is created.
     *
     * <p>{@link FirebaseApp} keeps a JVM-wide registry of initialized apps, so a second
     * initialization would fail. Reusing an existing instance keeps the configuration safe when
     * the context is rebuilt within the same JVM, as it is between test classes.</p>
     *
     * @throws IOException if the application default credentials cannot be resolved or read
     */
    @PostConstruct
    public void initialize() throws IOException {
        if (FirebaseApp.getApps().isEmpty()) {
            // Resolve credentials from the environment instead of a bundled service account key
            GoogleCredentials credentials;
            credentials = GoogleCredentials.getApplicationDefault();
            log.info("Firebase Admin SDK using application default credentials");

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setProjectId(projectId)
                    .build();

            firebaseApp = FirebaseApp.initializeApp(options);
            log.info("Firebase Admin SDK initialized successfully for project: {}", projectId);
        } else {
            // Another context in this JVM already initialized the SDK, so reuse its instance
            firebaseApp = FirebaseApp.getInstance();
            log.info("Firebase Admin SDK already initialized");
        }
    }

    /**
     * Exposes {@link FirebaseAuth} as a Spring bean rather than requiring callers to reach for the
     * static {@code FirebaseAuth.getInstance()} factory method.
     *
     * <p>This makes services that depend on Firebase Auth constructor-injectable (and therefore easy
     * to mock in unit tests), and it lets Spring's dependency graph enforce that {@link #initialize()}
     * has already run before anything tries to use it — the previous static-lookup pattern relied on
     * an implicit ordering that Spring did not actually guarantee.</p>
     *
     * @return the {@link FirebaseAuth} instance bound to this service's {@link FirebaseApp}
     */
    @Bean
    public FirebaseAuth firebaseAuth() {
        return FirebaseAuth.getInstance(firebaseApp);
    }

}
