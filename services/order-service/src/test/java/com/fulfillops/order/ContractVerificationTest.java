package com.fulfillops.order;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.fulfillops.common.TokenIssuer;
import java.util.List;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Provider side: replays every consumer's recorded interactions against the
 * real order-service (real Postgres, demo data) and fails the build if any
 * consumer expectation no longer holds.
 */
@Provider("order-service")
@PactFolder("../../contracts/pacts")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "fulfillops.security.signer=true",
        "SELLER_PASSWORD={noop}seller-password",
        "CS_PASSWORD={noop}cs-password"})
@ActiveProfiles("demo")
@Import(PostgresTestcontainer.class)
class ContractVerificationTest {

    @LocalServerPort int port;
    @Autowired TokenIssuer tokens;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    /** Recorded tokens are placeholders; swap in a real one carrying the consumer's role. */
    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verify(PactVerificationContext context, HttpRequest request) {
        if (request.containsHeader("Authorization")) {
            String role = context.getConsumer().getName().equals("cs-console") ? "CS" : "SELLER";
            request.setHeader("Authorization", "Bearer " + tokens.issue("pact", List.of(role)));
        }
        context.verifyInteraction();
    }

    @State({"demo orders exist", "account cs with password cs-password and role CS",
            "account seller with password seller-password and role SELLER"})
    void configuredByDemoProfileAndTestProperties() {
    }

    @State("order ORD-1048 is PENDING")
    void ord1048Pending() {
        jdbc.update("update orders set status = 'PENDING', refunded_at = null where order_number = 'ORD-1048'");
    }

    @State("order ORD-1050 is PROCESSING")
    void ord1050Processing() {
        jdbc.update("update orders set status = 'PROCESSING', refunded_at = null where order_number = 'ORD-1050'");
    }
}
