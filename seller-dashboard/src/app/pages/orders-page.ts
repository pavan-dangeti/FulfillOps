import { Component, inject } from '@angular/core';
import { AsyncPipe, CurrencyPipe } from '@angular/common';
import { OrdersService } from '../services/orders.service';
import { Order } from '../models/catalog';

const STATUS_CLASS: Record<Order['status'], string> = {
  PENDING: 'badge pending',
  PROCESSING: 'badge processing',
  SHIPPED: 'badge shipped',
  DELIVERED: 'badge delivered'
};

@Component({
  selector: 'app-orders-page',
  imports: [AsyncPipe, CurrencyPipe],
  templateUrl: './orders-page.html',
  styleUrl: './orders-page.css'
})
export class OrdersPage {
  private readonly orders = inject(OrdersService);
  readonly orders$ = this.orders.orders$;

  statusClass(status: Order['status']): string {
    return STATUS_CLASS[status];
  }
}