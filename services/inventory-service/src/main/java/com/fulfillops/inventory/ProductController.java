package com.fulfillops.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SELLER', 'CS')")
    public List<ProductView> list() {
        return service.list().stream().map(ProductView::from).toList();
    }

    @GetMapping("/{sku}")
    @PreAuthorize("hasAnyRole('SELLER', 'CS')")
    public ProductView get(@PathVariable String sku) {
        return ProductView.from(service.get(sku));
    }

    @PostMapping
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<ProductView> create(@Valid @RequestBody NewProduct body) {
        Product created = service.create(body.sku(), body.name(), body.unitPrice(), body.stock());
        return ResponseEntity.created(URI.create("/api/products/" + created.getSku())).body(ProductView.from(created));
    }

    @PutMapping("/{sku}/stock")
    @PreAuthorize("hasRole('SELLER')")
    public ProductView setStock(@PathVariable String sku, @Valid @RequestBody StockUpdate body) {
        return ProductView.from(service.setOnHand(sku, body.stock()));
    }

    public record NewProduct(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String sku,
            @NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
            @Min(0) int stock) {
    }

    public record StockUpdate(@Min(0) int stock) {
    }

    /** {@code id} is the SKU: it is the stable key clients address products by. */
    public record ProductView(String id, String sku, String name, BigDecimal unitPrice, int stock, int reserved, int available) {

        static ProductView from(Product p) {
            return new ProductView(p.getSku(), p.getSku(), p.getName(), p.getUnitPrice(),
                    p.getOnHand(), p.getReserved(), p.getAvailable());
        }
    }
}
