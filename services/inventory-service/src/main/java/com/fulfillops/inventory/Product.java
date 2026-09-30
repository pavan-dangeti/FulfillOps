package com.fulfillops.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;

/**
 * A SKU and its stock. {@code onHand} is physical units; {@code reserved} is
 * units promised to in-flight orders. Only {@code onHand - reserved} can be sold.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal unitPrice;

    private int onHand;

    private int reserved;

    @Version
    private long version;

    protected Product() {
    }

    public Product(String sku, String name, BigDecimal unitPrice, int onHand) {
        this.sku = sku;
        this.name = name;
        this.unitPrice = unitPrice;
        this.onHand = onHand;
    }

    /** A seller recount. Cannot drop below what is already promised to orders. */
    public void setOnHand(int onHand) {
        if (onHand < reserved) {
            throw new IllegalStateException(
                    "Cannot set stock of %s to %d: %d units are reserved by open orders".formatted(sku, onHand, reserved));
        }
        this.onHand = onHand;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getOnHand() {
        return onHand;
    }

    public int getReserved() {
        return reserved;
    }

    public int getAvailable() {
        return onHand - reserved;
    }
}
