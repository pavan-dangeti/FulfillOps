package com.fulfillops.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProductService {

    private final ProductRepository products;

    public ProductService(ProductRepository products) {
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<Product> list() {
        return products.findAllByOrderBySkuAsc();
    }

    @Transactional(readOnly = true)
    public Product get(String sku) {
        return products.findBySku(normalize(sku))
                .orElseThrow(() -> new NoSuchElementException("Unknown SKU: " + sku));
    }

    /** A duplicate SKU racing past the exists-check still hits the unique index (409). */
    public Product create(String sku, String name, BigDecimal unitPrice, int onHand) {
        String key = normalize(sku);
        if (products.existsBySku(key)) {
            throw new IllegalStateException("SKU already exists: " + key);
        }
        return products.saveAndFlush(new Product(key, name.trim(), unitPrice, onHand));
    }

    /** A concurrent edit of the same SKU loses on @Version (409) instead of silently overwriting. */
    public Product setOnHand(String sku, int onHand) {
        Product product = get(sku);
        product.setOnHand(onHand);
        return products.saveAndFlush(product);
    }

    static String normalize(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
    }
}
