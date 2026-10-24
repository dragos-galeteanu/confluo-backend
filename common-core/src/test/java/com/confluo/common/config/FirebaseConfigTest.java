package com.confluo.common.config;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FirebaseConfig}.
 *
 * <p>Only the "already initialized" path is exercised: the other branch calls
 * {@code GoogleCredentials.getApplicationDefault()}, which reaches for ambient cloud credentials and
 * cannot run in a unit test. That path is covered by actually starting a service.</p>
 */
class FirebaseConfigTest {

    private static final String PROJECT_ID = "confluo-test";

    private static void deleteEveryApp() {
        List.copyOf(FirebaseApp.getApps()).forEach(FirebaseApp::delete);
    }

    /** credentials that never leave the JVM: a static token with a far-off expiry */
    private static GoogleCredentials stubCredentials() {
        return GoogleCredentials.create(
                new AccessToken("stub-token", Date.from(Instant.now().plusSeconds(3600))));
    }

    @BeforeEach
    void clearRegistry() {
        deleteEveryApp();
    }

    @AfterEach
    void clearRegistryAgain() {
        deleteEveryApp();
    }

    @Test
    void anAppAlreadyInTheRegistryIsReused() throws IOException {
        FirebaseApp existing = FirebaseApp.initializeApp(FirebaseOptions.builder()
                .setCredentials(stubCredentials())
                .setProjectId(PROJECT_ID)
                .build());

        FirebaseConfig config = new FirebaseConfig();
        ReflectionTestUtils.setField(config, "projectId", PROJECT_ID);
        config.initialize();

        assertThat(ReflectionTestUtils.getField(config, "firebaseApp")).isSameAs(existing);
        assertThat(FirebaseApp.getApps()).hasSize(1);
    }

    @Test
    void initializingTwiceDoesNotCreateASecondApp() throws IOException {
        FirebaseApp.initializeApp(FirebaseOptions.builder()
                .setCredentials(stubCredentials())
                .setProjectId(PROJECT_ID)
                .build());

        FirebaseConfig first = new FirebaseConfig();
        ReflectionTestUtils.setField(first, "projectId", PROJECT_ID);
        first.initialize();

        FirebaseConfig second = new FirebaseConfig();
        ReflectionTestUtils.setField(second, "projectId", PROJECT_ID);
        second.initialize();

        assertThat(FirebaseApp.getApps()).hasSize(1);
        assertThat(ReflectionTestUtils.getField(first, "firebaseApp"))
                .isSameAs(ReflectionTestUtils.getField(second, "firebaseApp"));
    }

    @Test
    void theAuthBeanIsBoundToTheInitializedApp() throws IOException {
        FirebaseApp existing = FirebaseApp.initializeApp(FirebaseOptions.builder()
                .setCredentials(stubCredentials())
                .setProjectId(PROJECT_ID)
                .build());

        FirebaseConfig config = new FirebaseConfig();
        ReflectionTestUtils.setField(config, "projectId", PROJECT_ID);
        config.initialize();

        assertThat(config.firebaseAuth()).isSameAs(FirebaseAuth.getInstance(existing));
    }
}
