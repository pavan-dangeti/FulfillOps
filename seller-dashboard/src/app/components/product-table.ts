import { CurrencyPipe } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { Product } from '../models/catalog';

@Component({
  selector: 'app-product-table',
  imports: [CurrencyPipe],
  templateUrl: './product-table.html',
  styleUrl: './product-table.css'
})
export class ProductTable {
  @Input({ required: true }) products: Product[] = [];
  @Output() readonly stockChange = new EventEmitter<{ id: string; stock: number }>();

  editingId: string | null = null;
  draft = '';

  beginEdit(product: Product): void {
    this.editingId = product.id;
    this.draft = String(product.stock);
  }

  commit(product: Product): void {
    const stock = Number(this.draft);
    if (Number.isFinite(stock) && stock >= 0 && stock !== product.stock) {
      this.stockChange.emit({ id: product.id, stock });
    }
    this.editingId = null;
  }

  cancel(): void {
    this.editingId = null;
  }
}