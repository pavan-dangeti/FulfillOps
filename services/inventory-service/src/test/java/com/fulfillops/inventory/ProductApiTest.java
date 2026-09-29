package com.fulfillops.inventory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fulfillops.common.TokenIssuer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = "fulfillops.security.jwt-secret=test-secret-test-secret-test-secret-1234")
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class ProductApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenIssuer tokens;
    @Autowired JdbcTemplate jdbc;

    private MockHttpServletRequestBuilder as(String role, MockHttpServletRequestBuilder req) {
        return req.header("Authorization", "Bearer " + tokens.issue(role.toLowerCase(), List.of(role)));
    }

    private void create(String sku, int stock) throws Exception {
        mvc.perform(as("SELLER", post("/api/products")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"%s","name":"Lamp","unitPrice":12.50,"stock":%d}""".formatted(sku, stock)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(sku.toUpperCase()))
                .andExpect(jsonPath("$.available").value(stock));
    }

    @Test
    void rejectsRequestsWithoutAValidToken() throws Exception {
        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/products").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
    }

    @Test
    void customerServiceCanReadButNotWrite() throws Exception {
        mvc.perform(as("CS", get("/api/products"))).andExpect(status().isOk());
        mvc.perform(as("CS", post("/api/products")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-CS\",\"name\":\"x\",\"unitPrice\":1,\"stock\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateSkuIsAConflictRegardlessOfCase() throws Exception {
        create("sku-dup", 3);
        mvc.perform(as("SELLER", post("/api/products")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-DUP\",\"name\":\"x\",\"unitPrice\":1,\"stock\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        mvc.perform(as("SELLER", post("/api/products")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-NEG\",\"name\":\"x\",\"unitPrice\":1,\"stock\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as("SELLER", post("/api/products")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"bad sku!\",\"name\":\"x\",\"unitPrice\":1,\"stock\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stockCannotDropBelowReservedUnits() throws Exception {
        create("SKU-RES", 10);
        jdbc.update("update inventory_svc.products set reserved = 4 where sku = 'SKU-RES'");

        mvc.perform(as("SELLER", put("/api/products/SKU-RES/stock")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":3}"))
                .andExpect(status().isConflict());
        mvc.perform(as("SELLER", put("/api/products/SKU-RES/stock")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(0));
    }

    @Test
    void databaseRefusesToReserveMoreThanOnHand() {
        jdbc.update("insert into inventory_svc.products (sku, name, unit_price, on_hand) values ('SKU-DB', 'x', 1, 2)");
        assertThatThrownBy(() -> jdbc.update("update inventory_svc.products set reserved = 3 where sku = 'SKU-DB'"))
                .hasMessageContaining("reserved_within_on_hand");
    }

    @Test
    void unknownSkuIsNotFound() throws Exception {
        mvc.perform(as("SELLER", get("/api/products/NOPE"))).andExpect(status().isNotFound());
    }
}
