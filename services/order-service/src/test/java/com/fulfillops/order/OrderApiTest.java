package com.fulfillops.order;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "fulfillops.security.signer=true",
        "SELLER_PASSWORD={noop}seller-pw",
        "CS_PASSWORD={noop}cs-pw",
        "OPS_PASSWORD={noop}ops-pw"})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@Import(PostgresTestcontainer.class)
class OrderApiTest {

    @Autowired MockMvc mvc;

    private String login(String user, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder req) {
        return req.header("Authorization", "Bearer " + token);
    }

    @Test
    void badCredentialsAreRejectedTheSameWayForKnownAndUnknownUsers() throws Exception {
        for (String json : new String[] {
                "{\"username\":\"seller\",\"password\":\"wrong\"}",
                "{\"username\":\"nobody\",\"password\":\"seller-pw\"}"}) {
            mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void repeatedWrongPasswordsLockTheAccountBriefly() throws Exception {
        for (int i = 0; i < AuthController.FREE_ATTEMPTS; i++) {
            mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"ops\",\"password\":\"guess-" + i + "\"}"))
                    .andExpect(status().isUnauthorized());
        }
        // Even the right password is refused during the cool-down.
        mvc.perform(post("/api/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ops\",\"password\":\"ops-pw\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void publishesOnlyThePublicSigningKey() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].n").exists())
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void ordersRequireAToken() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void sellersSeeOrdersWithoutCustomerEmail() throws Exception {
        String seller = login("seller", "seller-pw");
        mvc.perform(as(seller, get("/api/orders/ORD-1048")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Priya Nair"))
                .andExpect(jsonPath("$.email").doesNotExist());

        String cs = login("cs", "cs-pw");
        mvc.perform(as(cs, get("/api/orders/ORD-1048")))
                .andExpect(jsonPath("$.email").value("priya.nair@example.com"));
    }

    @Test
    void searchMatchesOrderNumberOrCustomer() throws Exception {
        String cs = login("cs", "cs-pw");
        mvc.perform(as(cs, get("/api/orders").param("q", "priya")))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orderNumber").value("ORD-1048"));
        mvc.perform(as(cs, get("/api/orders"))).andExpect(jsonPath("$", hasSize(12)));
    }

    @Test
    void onlyCustomerServiceCanRefundAndRefundIsIdempotent() throws Exception {
        String seller = login("seller", "seller-pw");
        mvc.perform(as(seller, post("/api/orders/ORD-1044/refund"))).andExpect(status().isForbidden());

        String cs = login("cs", "cs-pw");
        String first = mvc.perform(as(cs, post("/api/orders/ORD-1044/refund")))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(as(cs, post("/api/orders/ORD-1044/refund")))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAt").value((String) JsonPath.read(first, "$.refundedAt")));
    }

    @Test
    void shipMovesProcessingToShippedButNotRefundedOrders() throws Exception {
        String cs = login("cs", "cs-pw");
        mvc.perform(as(cs, post("/api/orders/ORD-1045/ship"))).andExpect(jsonPath("$.status").value("SHIPPED"));
        mvc.perform(as(cs, post("/api/orders/ORD-1039/ship"))).andExpect(status().isConflict());
    }

    @Test
    void unknownOrderIsNotFound() throws Exception {
        mvc.perform(as(login("cs", "cs-pw"), get("/api/orders/ORD-0"))).andExpect(status().isNotFound());
    }
}
