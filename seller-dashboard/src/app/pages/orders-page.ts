import { Component, inject, OnInit, signal } from '@angular/core';
import { AsyncPipe, CurrencyPipe } from '@angular/common';
import { OrdersService } from '../services/orders.service';
import { Order } from '../models/catalog';
import { describeError } from '../services/errors';

const STATUS_CLASS: Record<Order['status'], string> = {
  PENDING: 'badge pending',
  PROCESSING: 'badge processing',
  SHIPPED: 'badge shipped',
  DELIVERED: 'badge delivered',
  REFUNDED: 'badge refunded'
};

@Component({
  selector: 'app-orders-page',
  imports: [AsyncPipe, CurrencyPipe],
  templateUrl: './orders-page.html',
  styleUrl: './orders-page.css'
})
export class OrdersPage implements OnInit {
  private readonly orders = inject(OrdersService);
  readonly orders$ = this.orders.orders$;
  readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    try {
      await this.orders.load();
    } catch (e) {
      this.error.set(describeError(e));
    }
  }

  statusClass(status: Order['status']): string {
    return STATUS_CLASS[status];
  }
}
