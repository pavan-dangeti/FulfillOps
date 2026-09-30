package com.fulfillops.inventory;

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

/** Replays every consumer's recorded interactions against the real inventory-service. */
@Provider("inventory-service")
@PactFolder("../../contracts/pacts")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "fulfillops.security.signer=true")
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

    /** Recorded tokens are placeholders; the seller dashboard is the only consumer and acts as SELLER. */
    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verify(PactVerificationContext context, HttpRequest request) {
        if (request.containsHeader("Authorization")) {
            request.setHeader("Authorization", "Bearer " + tokens.issue("pact", List.of("SELLER")));
        }
        context.verifyInteraction();
    }

    @State("product SKU-1001 exists with 142 units on hand")
    void sku1001() {
        jdbc.update("update products set on_hand = 142, reserved = 0 where sku = 'SKU-1001'");
    }

    @State("no product SKU-TEST-1 exists")
    void noTestSku() {
        jdbc.update("delete from products where sku = 'SKU-TEST-1'");
    }
}
