import { Component, EventEmitter, inject, Output } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Product } from '../models/catalog';
import { InventoryService } from '../services/inventory.service';
import { uniqueSku } from '../validators/unique-sku.validator';

@Component({
  selector: 'app-product-form',
  imports: [ReactiveFormsModule],
  templateUrl: './product-form.html',
  styleUrl: './product-form.css'
})
export class ProductForm {
  @Output() readonly addProduct = new EventEmitter<Omit<Product, 'id'>>();

  private readonly inventory = inject(InventoryService);

  readonly form = new FormGroup({
    sku: new FormControl('', {
      validators: [Validators.required, uniqueSku((sku) => this.inventory.hasSku(sku))]
    }),
    name: new FormControl('', [Validators.required]),
    unitPrice: new FormControl<number | null>(null, [Validators.required, Validators.min(0)]),
    stock: new FormControl<number | null>(null, [Validators.required, Validators.min(0)])
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { sku, name, unitPrice, stock } = this.form.value;
    this.addProduct.emit({
      sku: sku!.trim(),
      name: name!.trim(),
      unitPrice: Number(unitPrice),
      stock: Number(stock)
    });
    this.form.reset();
  }
}